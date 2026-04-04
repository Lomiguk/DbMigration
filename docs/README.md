# PostgreSQL UUID → BIGINT Migration Tool

Инструмент для миграции PostgreSQL баз данных с UUID на BIGINT идентификаторы с сохранением ссылочной целостности.

## Ключевые преимущества

- **~43-45% меньше индексы** (UUID: ~37 MB → BIGINT: ~21 MB для 1M записей)
- Улучшение JOIN производительности в **2.2 раза**
- Zero-downtime миграция с WAL-репликацией
- Автоматическая трансформация всех FK связей

## Быстрый старт

```bash
# 1. Запуск БД
docker-compose up -d

# 2. Анализ схемы
./gradlew run --args="init"

# 3. Первичная миграция
./gradlew run --args="copy"

# 4. Синхронизация
./gradlew run --args="sync"

# 5. Проверка статуса
./gradlew run --args="status"
```

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
| [TESTING.md](TESTING.md) | Тестирование: unit, integration, benchmark |
| [DEVELOPMENT.md](DEVELOPMENT.md) | План разработки, статус этапов |

## Технологический стек

| Компонент | Технология |
|-----------|------------|
| Язык | Kotlin/JVM |
| CLI | Clikt + Mordant |
| Connection Pool | HikariCP |
| Графы | JGraphT |
| Тесты | JUnit 5 + Testcontainers |

## Результаты (1M записей)

| Метрика | UUID | BIGINT | Улучшение |
|---------|------|--------|-----------|
| Размер индексов | ~37-38 MB | ~21 MB | **~43-45%** |
| JOIN скорость | базовая | ускоренная | **2.2x** |
| Последовательный доступ | случайный | последовательный | лучше locality |
