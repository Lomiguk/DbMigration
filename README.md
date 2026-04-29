# PostgreSQL UUID → BIGINT Migration Tool

Инструмент корпоративного уровня для миграции PostgreSQL баз данных с 128-битных UUID на 64-битные BIGINT идентификаторы. Обеспечивает сохранение ссылочной целостности, **Zero-downtime** переключение и мониторинг в реальном времени.

## Ключевые преимущества

- **~43-45% меньше индексы** (UUID: ~37 MB → BIGINT: ~21 MB для 1M записей).
- Улучшение JOIN производительности в **2.2 раза**.
- **Zero-downtime миграция** благодаря логической репликации (CDC через WAL).
- Защита от OutOfMemory при помощи серверных курсоров (Server-side Cursors).
- **O(1) In-Memory Cache** для мгновенной подмены внешних ключей.
- Автоматическая топологическая сортировка графа таблиц (JGraphT).
- Встроенный стек **Observability** (Prometheus + Grafana).

## Быстрый старт

```bash
# 1. Запуск БД и инфраструктуры мониторинга
docker compose up -d

# 2. Генерация тестовых данных
./gradlew run --args="generate-data --count 50000"

# 3. Анализ схемы и графа зависимостей
./gradlew run --args="init"

# 4. Первичная миграция данных (без индексов)
./gradlew run --args="copy"

# 4b. Или с переносом индексов из source (рекомендуется)
./gradlew run --args="copy --migrate-indexes"

# 5. Синхронизация дельты
./gradlew run --args="sync"

# 6. Проверка статуса
./gradlew run --args="status"
```

## Требования

- JDK 17.
- Docker и Docker Compose.
- PostgreSQL logical replication для source БД. В локальном стенде она уже включена в `compose.yaml`.
- Gradle Wrapper из репозитория: `./gradlew` для Linux/macOS или `.\gradlew.bat` для Windows.

## Конфигурация

По умолчанию приложение использует локальный стенд из `compose.yaml`:

| Назначение | Host | Port | Database | User |
|------------|------|------|----------|------|
| Source DB | localhost | 5431 | source_db | user |
| Target DB | localhost | 5432 | target_db | user |

Создать пример конфигурационного файла:

```bash
./gradlew run --args="config-init"
```

Команда создаст `migration-config.yaml`. После редактирования его можно передать любой CLI-команде:

```bash
./gradlew run --args="copy --config migration-config.yaml"
```

## Основные команды CLI

| Команда | Назначение |
|---------|------------|
| `config-init` | Создать пример `migration-config.yaml` |
| `generate-data` | Сгенерировать тестовые данные в source БД |
| `init` | Проанализировать source-схему и построить граф зависимостей |
| `copy` | Выполнить первичную миграцию данных |
| `copy --migrate-indexes` | Перенести реальные индексы из source в target |
| `copy --create-fk-indexes` | Создать индексы на FK-колонках в target |
| `sync` | Выполнить дельта-синхронизацию новых строк |
| `replicate` | Применить изменения через WAL/CDC |
| `replicate --continuous` | Запустить непрерывную WAL-репликацию |
| `validate` | Проверить количество строк, FK и mapping |
| `rollback` | Откатить миграцию |
| `backup` | Управлять backup/restore |
| `status` | Показать статус миграции и метрики |

## Тестирование

Unit-тесты не требуют Docker:

```bash
./gradlew test --tests unit.*
```

Интеграционные и benchmark-тесты используют Testcontainers, поэтому Docker должен быть запущен:

```bash
./gradlew test --tests integration.*
./gradlew test --tests benchmark.RealtimeReplicationTest
./gradlew benchmarkTest
```

## Локальные сервисы

После `docker compose up -d` доступны:

| Сервис | URL |
|--------|-----|
| Source PostgreSQL | `localhost:5431` |
| Target PostgreSQL | `localhost:5432` |
| Prometheus | `http://localhost:9090` |
| Pushgateway | `http://localhost:9091` |
| Grafana | `http://localhost:3000` (`admin` / `admin`) |

## Ограничения и проверки перед production

- Инструмент рассчитан на миграцию PostgreSQL-схем с UUID primary keys в target-схему с BIGINT/BIGSERIAL identifiers.
- Перед production-запуском сделайте backup source/target БД.
- После `copy` или `replicate` запускайте `validate`.
- Для production-нагрузки предпочтительно использовать `copy --migrate-indexes`, чтобы перенести реальные индексы source-схемы.
- `copy --create-fk-indexes` является опциональным режимом и создает индексы только по FK-колонкам.

## Документация

| Документ | Описание |
|----------|----------|
| [GUIDE.md](docs/GUIDE.md) | Полное руководство: CLI, backup, rollback, mapping, индексы, performance |
| [ARCHITECTURE.md](docs/ARCHITECTURE.md) | Архитектура, ключевые алгоритмы и структура графа |
| [OBSERVABILITY.md](docs/OBSERVABILITY.md) | Мониторинг: Grafana, PromQL, CSV-аудит, управление индексами |
| [TESTING.md](docs/TESTING.md) | Тестирование: unit, integration, benchmark |
| [DEVELOPMENT.md](docs/DEVELOPMENT.md) | План разработки, статус этапов и бэклог |

## Технологический стек

- **Kotlin 2.2** (JVM 17) — основной язык
- **PostgreSQL JDBC 42.7** — драйвер БД
- **HikariCP 5.1** — пул соединений
- **JGraphT 1.5** — топологическая сортировка зависимостей
- **Clikt + Mordant** — CLI-фреймворк
- **Micrometer + Prometheus** — метрики
- **Grafana** — визуализация
- **JUnit 5 + Testcontainers** — тестирование

## Структура БД (Тестовый стенд)

Проект включает тестовую E-commerce / ERP схему на 20 таблиц со сложными связями (до 5 уровней глубины).

| Домен | Таблицы | Глубина |
|-------|---------|---------|
| Users | regions, users, profiles, user_settings | 2 |
| Warehouse | suppliers, categories, products, warehouse_stocks | 2 |
| Sales | customers, orders, order_items, shipments | 3 |
| Analytics | product_reviews, audit_logs, discount_coupons, order_coupons, support_tickets, ticket_messages, marketing_campaigns, campaign_stats | 2-3 |
