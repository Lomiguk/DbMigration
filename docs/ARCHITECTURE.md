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

Двухуровневый кэш UUID → BIGINT:
- **L1:** ConcurrentHashMap (in-memory, O(1))
- **L2:** Таблица `migration_mapping` (persistent)

### DataMigrator

Основной движок миграции:
- Пакетная обработка (batch processing)
- `reWriteBatchedInserts=true` для оптимизации
- `RETURN_GENERATED_KEYS` для получения BIGINT ID
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

**Формула памяти:**
```
Память (MB) = Записи × FK × 24 байта / 1024 / 1024
```

### 5. Размер батча: 10,000 записей

**Почему:** Оптимальный баланс скорость/RAM по бенчмаркам

**Альтернативы:** 1K (тесты), 50K (мощные серверы), 100K (риск OOM)

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

**Файлы:** `batch_performance_*.csv`, `mapping_performance_*.csv`, `connection_pool_*.csv`, `summary_*.txt`

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

**Формула:** `cacheLimit = min(availableRAM / 100, 10_000_000)`

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

## Алгоритмы

### Топологическая сортировка (DependencyResolver)

Используется алгоритм Кана:
1. Найти все таблицы с indegree = 0 (нет входящих FK)
2. Добавить в очередь миграции
3. Уменьшить indegree зависимых таблиц
4. Повторить пока все таблицы не обработаны

**Сложность:** O(V + E), где V — таблицы, E — FK связи

### Memory-Filtered Delta Sync (ChangeCapture)

1. Получить все UUID из target (уже мигрированные)
2. Загрузить в HashSet (O(1) lookup)
3. Прочитать source таблицы
4. Отфильтровать записи через HashSet
5. Мигрировать только новые

**Преимущество:** ~16 мс на фильтрацию vs EXISTS/ON CONFLICT запросы к БД

### WAL Replication (CDC)

1. Создать logical replication slot (`pgoutput`)
2. Начать primary migration (DataMigrator)
3. После миграции читать WAL с момента старта
4. Парсить события: Begin → Relation → Insert/Update/Delete → Commit
5. Применить в target с трансформацией UUID FK → BIGINT
6. Отслеживать lag (байты отставания)

---

## Измеримые результаты (1M записей)

| Метрика | Значение |
|---------|----------|
| Размер индексов | -43.2% |
| Скорость JOIN | +2.2x |
| Производительность миграции | 5000-10000 rec/s |
| Время синхронизации (10K новых) | < 30 сек |
| Время отката (1M записей) | 1-2 мин |
