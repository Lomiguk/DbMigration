# PostgreSQL UUID → BIGINT Migration Tool

Инструмент для миграции PostgreSQL баз данных с UUID на BIGINT идентификаторы с сохранением ссылочной целостности, WAL/CDC-синхронизацией и сбором performance-метрик.

## Ключевые преимущества

- **~43-45% меньше индексы** (UUID: ~37 MB → BIGINT: ~21 MB для 1M записей)
- Улучшение JOIN производительности в **2.2 раза**
- Zero-downtime миграция с WAL-репликацией
- Автоматическая трансформация всех FK связей
- Стратегии mapping-кэша: `EAGER`, `LAZY`, `HYBRID`
- Воспроизводимый seed для тестовых данных
- CSV-логи и Grafana dashboard для анализа производительности

## Быстрый старт

```bash
# 1. Запуск БД и мониторинга
docker compose up -d

# 2. Генерация тестовых данных
./gradlew run --args="generate-data --count 50000 --seed 42"

# 3. Анализ схемы
./gradlew run --args="init"

# 4. Первичная миграция
./gradlew run --args="copy --mapping-strategy=LAZY --cache-limit 100000"

# 5. Синхронизация
./gradlew run --args="sync"

# 6. Проверка статуса
./gradlew run --args="status"
```

## Стратегии mapping-кэша

| Стратегия | Поведение | Основной выигрыш | Цена |
|-----------|-----------|------------------|------|
| `EAGER` | Полностью предзагружает mapping в память | Максимальная скорость lookup | RAM растёт вместе с mapping |
| `LAZY` | Использует bounded Caffeine cache и DB lookup на miss | Предсказуемый heap при `--cache-limit` | Больше запросов к БД |
| `HYBRID` | Держит малые таблицы в pinned cache, остальные лениво | Компромисс скорости и памяти | Выигрыш зависит от FK-профиля |

## Архитектура

```
src/main/kotlin/
├── cli/           # CLI команды (Clikt + Mordant)
├── core/          # MetadataReader, DependencyResolver
├── engine/        # DataMigrator, MappingService
├── state/         # StateRepository — хранение состояния
├── sync/          # ChangeCapture — delta sync
├── replication/   # WAL репликация (CDC)
├── rollback/      # RollbackService
└── validation/    # DataIntegrityValidator
```

## Схема БД (20 таблиц)

| Домен | Таблицы | Глубина зависимостей |
|-------|---------|---------------------|
| Users | regions, users, profiles, user_settings | 2 |
| Warehouse | suppliers, categories, products, warehouse_stocks | 2 |
| Sales | customers, orders, order_items, shipments | 3 |
| Analytics | product_reviews, audit_logs, discount_coupons, order_coupons, support_tickets, ticket_messages, marketing_campaigns, campaign_stats | 2-3 |

## Документация

| Документ | Описание |
|----------|----------|
| [GUIDE.md](GUIDE.md) | Полное руководство: CLI, backup, rollback, mapping, performance |
| [ARCHITECTURE.md](ARCHITECTURE.md) | Технические решения, архитектура, компромиссы |
| [OBSERVABILITY.md](OBSERVABILITY.md) | Grafana, PromQL, CSV-аудит и performance-метрики |
| [PERFORMANCE_BENCHMARK.md](PERFORMANCE_BENCHMARK.md) | Чистый протокол сравнения EAGER/LAZY/HYBRID |
| [TESTING.md](TESTING.md) | Тестирование: unit, integration, benchmark |
| [DEVELOPMENT.md](DEVELOPMENT.md) | План разработки, статус этапов |

## Технологический стек

| Компонент | Технология |
|-----------|------------|
| Язык | Kotlin/JVM |
| CLI | Clikt + Mordant |
| Connection Pool | HikariCP |
| Графы | JGraphT |
| Кэш | Caffeine |
| Метрики | Micrometer + Prometheus + Grafana |
| Тесты | JUnit 5 + Testcontainers |

## Результаты (1M записей)

| Метрика | UUID | BIGINT | Улучшение |
|---------|------|--------|-----------|
| Размер индексов | ~37-38 MB | ~21 MB | **~43-45%** |
| JOIN скорость | базовая | ускоренная | **2.2x** |
| Последовательный доступ | случайный | последовательный | лучше locality |
