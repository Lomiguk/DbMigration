# Архитектура и технические решения

## Содержание

1. [Архитектура системы](#архитектура-системы)
2. [Ключевые компоненты](#ключевые-компоненты)
3. [Технические решения](#технические-решения)
4. [Структура БД](#структура-бд)
5. [Алгоритмы](#алгоритмы)

---

## Архитектура системы

```
┌─────────────────────────────────────────────────────────────────┐
│                         CLI (Clikt)                             │
│  init │ copy │ sync │ status │ resume │ validate │ replicate    │
└─────────────────────────────────────────────────────────────────┘
                              │
        ┌─────────────────────┼─────────────────────┐
        │                     │                     │
        ▼                     ▼                     ▼
┌───────────────┐   ┌─────────────────┐   ┌──────────────────┐
│  Core Module  │   │  Engine Module  │   │  State Module    │
│               │   │                 │   │                  │
│ MetadataReader│   │  DataMigrator   │   │ StateRepository  │
│ Dependency    │   │  MappingService │   │ MigrationState   │
│ Resolver      │   │                 │   │                  │
└───────────────┘   └─────────────────┘   └──────────────────┘
        │                     │                     │
        ▼                     ▼                     ▼
┌───────────────┐   ┌─────────────────┐   ┌──────────────────┐
│  Sync Module  │   │ Replication     │   │  Rollback Module │
│               │   │ Module          │   │                  │
│ ChangeCapture │   │ SlotManager     │   │ RollbackService  │
│ (delta sync)  │   │ WalReader       │   │                  │
└───────────────┘   │ WalApplier      │   └──────────────────┘
                    │ ReplicationSvc  │
                    └─────────────────┘
```

## Ключевые компоненты

### MetadataReader

Чтение структуры БД через `information_schema`:
- Таблицы с UUID первичным ключом
- Внешние ключи (FK constraints)
- Колонки и типы данных

### DependencyResolver

Построение графа зависимостей (JGraphT) и топологическая сортировка (алгоритм Кана):
- Гарантирует порядок: родители → потомки
- Обработка diamond pattern зависимостей

### MappingService

### Стратегии mapping-кэша

Для трансляции UUID в BIGINT используется таблица `migration_mapping` и выбранная стратегия кэширования:

1. **EAGER:** перед обработкой данных загружает весь mapping в память. Это самый быстрый режим для lookup, но потребление RAM растёт вместе с числом записей.
2. **LAZY:** стартует с пустым bounded Caffeine cache. При промахе делает запрос в `migration_mapping`, возвращает найденный BIGINT и кладёт результат в кэш с лимитом `cacheLimit`.
3. **HYBRID:** держит mapping небольших таблиц в pinned cache, а большие таблицы обрабатывает через bounded lazy cache.

Ключ mapping-кэша всегда содержит пару `(table_name, old_uuid)`. Это исключает конфликт одинаковых UUID из разных таблиц.

### DataMigrator

Основной движок миграции:
- Пакетная обработка (batch processing)
- PostgreSQL `COPY FROM STDIN` для bulk-загрузки target-таблиц
- Предварительное выделение BIGINT ID из sequence на batch без `RETURN_GENERATED_KEYS`
- Bulk-сохранение `migration_mapping` через временную таблицу и `COPY`
- Сохранение прогресса после каждого батча

### ChangeCapture

Инкрементальная синхронизация (Memory-Filtered Delta Sync):
- Фильтрация уже мигрированных UUID в памяти (HashSet)
- Миграция только новых записей
- Время фильтрации: ~16 мс

### ReplicationService

WAL-репликация через logical replication (`pgoutput`):
- Создание replication слотов
- Чтение WAL событий (INSERT/UPDATE/DELETE)
- Трансформация UUID FK → BIGINT при применении
- Непрерывный режим и delta sync

### StateRepository

Хранение состояния миграции в БД:
- Таблица `migration_state`
- Прогресс по каждой таблице
- Поддержка resume и rollback

### DataIntegrityValidator

Проверки целостности:
- Сравнение количества записей (source vs target)
- Валидация FK связей
- Проверка маппинга UUID → BIGINT
- Валидация последовательности ID

### WalApplier: 
Адаптер CDC, транслирующий события из UUID-схемы в BIGINT-схему в реальном времени.

### MetricsService: 
Интеграция с Micrometer и Prometheus Pushgateway для Observability.

---

## Технические решения

### 1. Kotlin/JVM

**Почему:** Null-safety, производительность Java, лаконичность, экосистема JDBC

**Компромиссы:** Требует JDK 17+, время запуска ~2-3 сек (JVM warmup), ~100-200MB baseline RAM

### 2. Clikt + Mordant для CLI

**Почему:** Kotlin-native, типобезопасность, автоматическая справка, цвета и прогресс-бары

### 3. HikariCP для пула соединений

**Почему:** Самый быстрый connection pool, ~130KB jar, микросекундное получение соединения

**Конфигурация по умолчанию:**
```
maximumPoolSize = 10
minimumIdle = 2
connectionTimeout = 30000
```

### 4. Три стратегии маппинга (EAGER/LAZY/HYBRID)

**Почему:** Разные сценарии — разные требования к RAM и скорости

| Стратегия | Поведение | Выигрыш | Цена |
|-----------|-----------|---------|------|
| `EAGER` | Полная предзагрузка `migration_mapping` в память | Максимальная скорость lookup | RAM зависит от общего размера mapping |
| `LAZY` | Bounded Caffeine cache + DB lookup на miss | Ограниченное потребление памяти | Дополнительные запросы к БД |
| `HYBRID` | Pinned cache для малых таблиц + lazy cache для больших | Быстрый доступ к справочникам без unbounded cache | Нужно корректно подобрать лимит и small-table threshold |

### 5. Размер батча

**Почему:** Баланс скорость/RAM для основной миграции, удобный для resume и per-batch транзакций.

`batchSize` задаётся через CLI/config и используется `DataMigrator` при `copy`, `resume` и `sync`. Генератор тестовых данных использует собственный размер batch для массовой вставки и не задаёт размер batch миграции.

Опционально включается `adaptiveBatchSize`. Тогда первый batch использует `batchSize`, а затем `AdaptiveBatchController` меняет размер следующего batch по фактической длительности предыдущего. Режим не меняет границы транзакций: каждый batch по-прежнему обрабатывается и фиксируется отдельно. Проверенный стартовый профиль: `minBatchSize=250`, `maxBatchSize=4000`, `targetBatchDurationMs=150`.

### 6. Таблица migration_state

**Почему:** Transaction-safe, не теряется при рестарте, не требует Redis

**Схема:**
```sql
CREATE TABLE migration_state (
    migration_id VARCHAR(100),
    table_name VARCHAR(100),
    status VARCHAR(50),          -- PENDING, IN_PROGRESS, COMPLETED, FAILED
    processed_rows BIGINT,
    last_processed_uuid VARCHAR(100),
    last_batch_number INT,
    started_at TIMESTAMP,
    completed_at TIMESTAMP,
    error_message TEXT,
    retry_count INT,
    updated_at TIMESTAMP DEFAULT NOW(),
    PRIMARY KEY (migration_id, table_name)
);
```

### 7. Table-level rollback

**Почему:** Быстрый точечный откат, сохранение успешных миграций

**Уровни:** Table → Checkpoint → Full

### 8. CSV логирование с lazy инициализацией

**Почему:** Компактность (~10KB на 1000 батчей), легко парсить (Excel, pandas)

**Файлы:** `performance_logs/run_YYYYMMDD_HHMMSS/run_config.txt`, `run_manifest.json`, `summary.txt`, `batch_performance.csv`, `batch_phase_performance.csv`, `adaptive_batch_decisions.csv`, `wal_sync_performance.csv`, `mapping_performance.csv`, `mapping_db_lookup.csv`, `cache_snapshots.csv`, `jvm_snapshots.csv`, `connection_pool.csv`

### 9. Per-batch транзакции

**Почему:** Безопасность + resume capability + разумный overhead

```kotlin
targetConn.autoCommit = false
try {
    processBatch(...)
    targetConn.commit()
    stateRepository.saveProgress(...)
} catch (e: Exception) {
    targetConn.rollback()
    stateRepository.failTable(...)
    throw e
}
```

### 10. Ограничение кэша (cacheLimit)

**Почему:** Контроль RAM, предсказуемость, настраиваемость

`cacheLimit` ограничивает bounded cache в `LAZY` и lazy-часть `HYBRID`. `EAGER` не является bounded-режимом: он намеренно держит весь mapping в памяти ради скорости.

---

## Структура БД

### Граф зависимостей таблиц

```
regions ──→ users ──→ profiles
                    └─→ user_settings
                    └─→ audit_logs
                    └─→ product_reviews
                    └─→ support_tickets ──→ ticket_messages

categories ──→ products ──→ order_items
suppliers ──┘             └─→ product_reviews
                            └─→ warehouse_stocks

customers ──→ orders ──→ order_items
                      └─→ shipments
                      └─→ order_coupons ← discount_coupons

regions ──→ marketing_campaigns ──→ campaign_stats
```

### Порядок миграции (топологическая сортировка)

1. regions, categories, suppliers (справочники, без FK)
2. users, products, customers, discount_coupons, marketing_campaigns
3. profiles, user_settings, warehouse_stocks, orders
4. order_items, shipments, order_coupons, campaign_stats
5. product_reviews, audit_logs, support_tickets
6. ticket_messages

---

## Ключевые алгоритмы и оптимизации
Мы применили ряд архитектурных паттернов для обеспечения отказоустойчивости и производительности на объемах данных > 10 млн строк.

### Защита от OOM: Серверные курсоры (Server-Side Cursors)

Проблема: По умолчанию JDBC драйвер PostgreSQL загружает всю выборку SELECT * FROM table в оперативную память. Для таблиц-гигантов это гарантированно приводит к крашу JVM (OutOfMemoryError).
Решение: В DataMigrator включены серверные курсоры (autoCommit = false, fetchSize = 5000). База данных стримит записи небольшими батчами, поэтому чтение source-таблиц не требует загрузки всего `SELECT *` в heap. Итоговый профиль памяти всё равно зависит от выбранной mapping-стратегии: `EAGER` растёт по размеру mapping, `LAZY` и lazy-часть `HYBRID` ограничиваются cacheLimit.

### O(1) In-Memory Cache для внешних ключей (Fix N+1)
Проблема: Во время логической репликации (CDC) каждый INSERT/UPDATE требует проверки: является ли колонка с UUID внешним ключом, который нужно подменить на BIGINT? Синхронные запросы к information_schema на каждое WAL-событие создавали проблему N+1 и убивали пропускную способность репликации.
Решение: WalApplier при старте строит полный словарь графа связей в памяти (HashMap: Таблица -> Колонка -> Ссылочная таблица). Поиск и подмена внешних ключей в горячем потоке репликации происходит за O(1) в оперативной памяти без единого системного запроса к БД.

### Гарантия ссылочной целостности (Топологическая сортировка)
Проблема: Перенос данных в алфавитном порядке неизбежно вызовет нарушения Foreign Key constraints (попытка вставить заказ до вставки пользователя).
Решение: Алгоритм Кана (Kahn's algorithm).
1. Найти все таблицы с indegree = 0 (нет входящих FK).
2. Добавить в очередь миграции.
3. Уменьшить indegree зависимых таблиц.
4. Повторить, пока граф не будет полностью пройден.
   Сложность: O(V + E), где V — таблицы, E — связи.

### Memory-Filtered Delta Sync (ChangeCapture)

1. Получить все UUID из target (уже мигрированные)
2. Загрузить в HashSet (O(1) lookup)
3. Прочитать source таблицы
4. Отфильтровать записи через HashSet
5. Мигрировать только новые

Проблема: Инкрементальная синхронизация (когда в исходной базе появились новые данные во время миграции) через конструкцию INSERT ... ON CONFLICT DO NOTHING создает огромную нагрузку на целевую БД из-за перестроения индексов.
Решение: ChangeCapture выгружает набор уже мигрированных UUID (HashSet) в оперативную память. Фильтрация данных происходит на стороне Kotlin-приложения до формирования SQL-запроса (~16 мс на фильтрацию 10k записей).

**Преимущество:** ~16 мс на фильтрацию vs EXISTS/ON CONFLICT запросы к БД

### WAL Replication (CDC)
Обеспечивает непрерывную репликацию для переключения систем без даунтайма.
1. Создание logical replication slot (pgoutput).
2. Настройка Replica Identity: Автоматический вызов ALTER TABLE ... REPLICA IDENTITY FULL для всех таблиц, чтобы WAL-журнал гарантированно содержал старые значения UUID при операциях UPDATE и DELETE.
3. Парсинг бинарного протокола: Begin → Relation → Insert/Update/Delete → Commit.
4. Трансформация Tuple-данных "на лету" с использованием MappingService.

## Структура БД (Тестовый стенд)

---

## Измеримые результаты (Benchmark 1M записей)
Наш встроенный тест PerformanceBenchmarkTest математически доказывает целесообразность миграции:

| Метрика | UUID v4 (Исходная) | BIGINT (Целевая) | Разница |
|---------|--------------------|------------------|---------|
| Размер PRIMARY KEY индекса (1M строк) | ~37 MB             | ~21 MB           | -43%        |
| Скорость массовой вставки (Batch Insert) | Базовая              | Улучшена                 |  +15-20%       |
| Скорость JOIN операций (4 таблицы)| Базовая   | Улучшена                 | до 2.2x |
| B-Tree фрагментация | Высокая (random)           | Минимальная (seq)                 | Радикальное снижение        |
