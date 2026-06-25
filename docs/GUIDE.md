# Руководство пользователя

## Содержание

1. [CLI команды](#cli-команды)
2. [Конфигурация](#конфигурация)
3. [Бекапы](#бекапы)
4. [Rollback](#rollback)
5. [Стратегии маппинга](#стратегии-маппинга)
6. [Логирование производительности](#логирование-производительности)
7. [Troubleshooting](#troubleshooting)

---

## CLI команды

### Основные команды

| Команда | Описание |
|---------|----------|
| `init` | Анализ схемы, построение графа зависимостей |
| `copy` | Первичная миграция данных (UUID → BIGINT) |
| `sync` | Инкрементальная синхронизация новых данных |
| `status` | Статус и метрики миграции |
| `config-init` | Создание конфигурационного файла |
| `generate-data` | Генерация тестовых данных в source БД |

### Расширенные команды

| Команда | Описание |
|---------|----------|
| `resume` | Возобновление прерванной миграции |
| `validate` | Валидация целостности данных |
| `replicate` | WAL-репликация (CDC) |
| `rollback` | Откат миграции |
| `backup` | Управление бекапами БД |

### Глобальные опции

| Опция | Краткая | Описание | По умолчанию |
|-------|---------|----------|--------------|
| `--source-host` | `-sh` | Host source БД | localhost |
| `--source-port` | `-sp` | Port source БД | 5431 |
| `--source-db` | `-sd` | Имя source БД | source_db |
| `--target-host` | `-th` | Host target БД | localhost |
| `--target-port` | `-tp` | Port target БД | 5432 |
| `--target-db` | `-td` | Имя target БД | target_db |
| `--batch-size` | `-b` | Размер пакета для `copy`, `resume` и `sync` | 1000 |
| `--cache-limit` | `-c` | Лимит кэша | 500000 |
| `--max-pool-size` | `-m` | Размер пула соединений | 10 |
| `--dry-run` | `-n` | Пробный запуск | false |
| `--verbose` | `-v` | Подробный вывод | false |
| `--config` | `-cfg` | Путь к конфигу | — |

### Опции команды copy

| Опция | Краткая | Описание | По умолчанию |
|-------|---------|----------|--------------|
| `--migrate-indexes` | `-mi` | Перенести индексы из source в target (анализ `pg_catalog`, автоматическая замена UUID→BIGINT) | ❌ |
| `--create-fk-indexes` | — | Создать индексы на всех FK-колонках (опционально) | ❌ |

### Управление индексами при миграции

По умолчанию команда `copy` создаёт **только таблицы и PRIMARY KEY**. Никакие дополнительные индексы не переносятся — это безопасная практика, позволяющая вам самостоятельно решить, какие индексы нужны.

#### Вариант 1: Перенос реальной индексной схемы (рекомендуется)

```bash
./gradlew run --args="copy --mapping-strategy=LAZY --cache-limit 100000 --migrate-indexes"
```

Анализирует `pg_catalog` source БД и воссоздаёт в target **точно те же индексы**, автоматически заменяя UUID-типы на BIGINT. Переносятся:
- **UNIQUE** индексы
- **Составные** (multi-column) индексы
- **Partial** индексы (с WHERE clause)
- Пропускаются PRIMARY KEY (уже создан через `BIGSERIAL PRIMARY KEY`)

Это безопасный подход: переносятся только индексы, которые **реально существуют** в production.

#### Вариант 2: Создание индексов на FK (опционально)

```bash
./gradlew run --args="copy --create-fk-indexes"
```

Создаёт `idx_<table>_<fk_column>` на каждой FK-колонке. **Не рекомендуется** как базовая практика — используйте только если в source действительно были индексы на этих FK.

#### Вариант 3: Без индексов (по умолчанию)

```bash
./gradlew run --args="copy"
```

Только таблицы + PK. Индексы добавляете вручную после миграции по мере необходимости.

### Примеры использования

```bash
# Базовая миграция (без индексов)
./gradlew run --args="init"
./gradlew run --args="copy"

# Миграция с переносом индексов из source
./gradlew run --args="copy --mapping-strategy=LAZY --cache-limit 100000 --migrate-indexes"

# Миграция с кастомными параметрами кэша/пула и индексами
./gradlew run --args="copy --mapping-strategy=HYBRID --cache-limit 300000 --max-pool-size 20 --migrate-indexes"

# Пробный запуск
./gradlew run --args="copy --dry-run --verbose"
```

---

## Генерация тестовых данных

### Быстрая генация

```bash
# 1M записей в основных таблицах (по умолчанию)
./gradlew run --args="generate-data"

# 100K записей
./gradlew run --args="generate-data --count 100000"

# С очисткой таблиц перед генерацией
./gradlew run --args="generate-data --count 1000000 --truncate"

# Воспроизводимый набор данных для сравнения режимов
./gradlew run --args="generate-data --count 1000000 --truncate --seed 42"
```

### Что генерируется

Генератор заполняет все **20 таблиц** с соблюдением FK зависимостей:

| Уровень | Таблицы | Кол-во (от baseCount) |
|---------|---------|----------------------|
| 0 — Справочники | regions, suppliers, categories, customers | 1%–10% |
| 1 — Основные | users, products, discount_coupons, marketing_campaigns | 1%–100% |
| 2 — Зависимые | profiles, user_settings, warehouse_stocks, product_reviews, audit_logs | 10%–100% |
| 3 — Продажи | orders, order_items, shipments, order_coupons, campaign_stats | 10%–200% |
| 4 — Поддержка | support_tickets, ticket_messages | 1%–10% |

### Опции команды

| Опция | Краткая | Описание | По умолчанию |
|-------|---------|----------|--------------|
| `--count` | — | Базовое количество записей | 1000000 |
| `--truncate` | `-t` | Очистить таблицы перед генерацией | false |
| `--seed` | — | Детерминированный seed для UUID, FK и random-полей | random |

### Примеры использования

**Полный цикл: генерация → миграция:**
```bash
# 1. Очистить и заполнить БД
./gradlew run --args="generate-data --count 1000000 --truncate --seed 42"

# 2. Проанализировать схему
./gradlew run --args="init"

# 3. Мигрировать
./gradlew run --args="copy"
```

**Быстрое тестирование (100K):**
```bash
./gradlew run --args="generate-data --count 100000 --truncate --seed 42"
./gradlew run --args="copy"
```

### Оптимизации генератора

- `rewriteBatchedInserts=true` — пакетная вставка
- `synchronous_commit = OFF` — ускорение записи
- `work_mem = 256MB` — больше памяти для операций
- Порядок вставки соответствует FK зависимостям

---

## Конфигурация

### Формат migration-config.yaml

```yaml
sourceHost: localhost
sourcePort: 5431
sourceDatabase: source_db
sourceUser: user
sourcePassword: password

targetHost: localhost
targetPort: 5432
targetDatabase: target_db
targetUser: user
targetPassword: password

batchSize: 1000
cacheLimit: 500000
mappingStrategy: LAZY
maxPoolSize: 10
connectionTimeout: 30000

syncStrategy: MEMORY_FILTERED

dryRun: false
verbose: true
```

### Переменные окружения

| Переменная | Описание |
|------------|----------|
| `SOURCE_DB_PASSWORD` | Пароль source БД |
| `TARGET_DB_PASSWORD` | Пароль target БД |
| `PGUSER` | Пользователь PostgreSQL |
| `PGPASSWORD` | Пароль PostgreSQL |

---

## Бекапы

### Создание бекапа

```bash
./gradlew run --args="backup --action=create --name=full_dataset"
```

### Восстановление

```bash
./gradlew run --args="backup --action=restore --name=full_dataset"
```

### Список бекапов

```bash
./gradlew run --args="backup --action=list"
```

### Удаление бекапа

```bash
./gradlew run --args="backup --action=delete --name=old_backup"
```

### Сценарии

**Быстрое тестирование:**
```bash
# Один раз создаём бекап
./gradlew run --args="backup --action=create --name=test_data"

# Быстрое восстановление для следующих тестов
./gradlew run --args="backup --action=restore --name=test_data"
```

**Откат после неудачной миграции:**
```bash
./gradlew run --args="backup --action=create --name=pre_migration"
./gradlew run --args="copy"
# Если ошибка:
./gradlew run --args="backup --action=restore --name=pre_migration"
```

### Производительность

| Операция | 100K | 1M | 10M |
|----------|------|----|-----|
| Создание | 5 сек | 30 сек | 5 мин |
| Восстановление | 3 сек | 20 сек | 3 мин |
| Генерация с нуля | 30 сек | 3 мин | 30 мин |

### Требования

- PostgreSQL (`pg_dump`, `pg_restore` в PATH)
- Место на диске (1 бекап ≈ 10-30% от размера БД)

---

## Rollback

### Уровни отката

| Уровень | Команда | Описание |
|---------|---------|----------|
| Table | `--table <name>` | Откат одной таблицы |
| Checkpoint | `--to-table <name>` | Откат к указанной таблице |
| Full | `--full` | Полный откат |

### Примеры

```bash
# Откат одной таблицы
./gradlew run --args="rollback --table orders"

# Откат к чекпоинту
./gradlew run --args="rollback --to-table products"

# Полный откат
./gradlew run --args="rollback --full"

# С валидацией
./gradlew run --args="rollback --table orders --validate"
```

### Что делает rollback

1. `TRUNCATE TABLE <name> CASCADE` — очистка target
2. `DELETE FROM migration_mapping WHERE table_name = '<name>'` — удаление маппинга
3. Обновление статуса в `migration_state` → PENDING

### Время отката

| Размер | Время |
|--------|-------|
| 1K записей | < 1 сек |
| 100K записей | 5-10 сек |
| 1M записей | 1-2 мин |
| 10M записей | 10-20 мин |

### ⚠️ Важно

- Rollback работает **только с target БД**, source не модифицируется
- Все данные в target после миграции будут **удалены**
- Перед откатом рекомендуется сделать бекап

---

## Стратегии маппинга

### EAGER (полная предзагрузка)

**Принцип:** загрузить mapping-записи в память перед миграцией и выполнять lookup из RAM.

| Плюсы | Минусы |
|-------|--------|
| Минимум DB lookup при FK remap | Требует RAM пропорционально числу mapping-записей |
| Максимальная скорость lookup | Долгая подготовка на больших mapping-таблицах |

**Когда использовать:** достаточно RAM, важна максимальная скорость.

### LAZY (ленивая загрузка)

**Принцип:** bounded Caffeine cache; при cache miss выполняется запрос в `migration_mapping`.

| Плюсы | Минусы |
|-------|--------|
| Предсказуемый heap при заданном `--cache-limit` | Больше DB lookup |
| Быстрый старт | Скорость зависит от hit rate и latency БД |

**Когда использовать:** ограничена память или миграция должна гарантированно не расти по heap вместе с размером БД.

### HYBRID (малые таблицы в памяти, остальные лениво)

**Принцип:** таблицы, которые помещаются в половину `cacheLimit`, выбираются как pinned и предзагружаются. Остальные lookup идут через bounded lazy cache.

| Плюсы | Минусы |
|-------|--------|
| Быстрее LAZY, если FK часто ссылаются на малые таблицы | Польза зависит от профиля FK |
| Память ниже EAGER на больших схемах | Нужно проверять метрики `pinned/lazy` и DB lookup |

**Когда использовать:** есть небольшие справочники и большие транзакционные таблицы.

### Сравнение

Не используйте фиксированные цифры из документации как результат benchmark. Для проекта важны сравнительные метрики на вашем dataset:

| Метрика | Источник |
|---------|----------|
| total duration, rows/sec | `summary.txt`, `batch_performance.csv` |
| peak heap, GC time | `jvm_snapshots.csv` |
| cache total/lazy/pinned | `cache_snapshots.csv` |
| DB lookup count/latency | `mapping_db_lookup.csv` |
| live-графики | Grafana dashboard `DB Migration Observatory` |

### Рекомендации

| Сценарий | Стратегия | cacheLimit | maxPoolSize |
|----------|-----------|------------|-------------|
| Максимальная скорость и достаточно RAM | EAGER | не критичен для RAM-limit | 10-20 |
| Ограниченный heap | LAZY | 100000-500000 | 5-10 |
| Смешанная схема: справочники + большие таблицы | HYBRID | 100000-1000000 | 10 |
| Честный benchmark | EAGER, LAZY, HYBRID | одинаковый для сравниваемых режимов | одинаковый |

---

## Логирование производительности

### Файлы логов

После запуска создаётся папка `performance_logs/run_YYYYMMDD_HHMMSS/`:

| Файл | Содержание |
|------|------------|
| `run_config.txt` | Команда, стратегия, cacheLimit, batchSize, параметры БД |
| `summary.txt` | Текстовая сводка |
| `batch_performance.csv` | Сводка батчей (`copy_data`, `mapping_save`, commit, r/s) |
| `batch_phase_performance.csv` | Детализация фаз: `fk_lookup`, `id_allocation`, `csv_build`, `copy_data`, `mapping_save`, `commit` |
| `mapping_performance.csv` | Метрики сохранения mapping batch |
| `mapping_db_lookup.csv` | DB lookup на cache miss: strategy, table, duration, found |
| `cache_snapshots.csv` | total/lazy/pinned cache, hit rate, evictions, misses |
| `jvm_snapshots.csv` | heap, non-heap, GC count/time |
| `connection_pool.csv` | Зарезервирован для HikariCP snapshots; сейчас создаётся только header |

### Формат batch_performance CSV

```csv
timestamp,table,batch_number,records_total,insert_duration_ms,mapping_duration_ms,commit_duration_ms,total_batch_ms,records_per_sec
```

### Ожидаемые метрики (1M записей)

| Метрика | Норма | Проблема если |
|---------|-------|---------------|
| Средняя скорость | сравнивайте между стратегиями на одном seed | резкая деградация на том же dataset |
| Время batch (`--batch-size 5000`) | 80-120ms | > 250ms |
| `copy_data` | 5-20ms | > 50ms |
| `mapping_save` | 30-80ms | > 150ms |
| `id_allocation` | 10-25ms | > 50ms |
| `fk_lookup` | сравнивайте EAGER/LAZY/HYBRID на одном seed | резкий рост при той же стратегии и seed |

### Анализ в Python

```python
import pandas as pd
import matplotlib.pyplot as plt

df = pd.read_csv('performance_logs/run_YYYYMMDD_HHMMSS/batch_performance.csv')
plt.plot(df['batch_number'], df['mapping_duration_ms'], label='Mapping')
plt.plot(df['batch_number'], df['insert_duration_ms'], label='Insert')
plt.legend()
plt.xlabel('Batch Number')
plt.ylabel('Duration (ms)')
plt.show()
```

---

## Анализ производительности

### Автоматический сбор метрик

При каждой миграции (`copy`) автоматически собираются метрики в `performance_logs/run_YYYYMMDD_HHMMSS/`:

| Файл | Содержание |
|------|------------|
| `batch_performance.csv` | Время каждого батча (insert, mapping, commit, r/s) |
| `batch_phase_performance.csv` | Время каждой внутренней фазы batch |
| `mapping_performance.csv` | Время сохранения mapping |
| `mapping_db_lookup.csv` | DB lookup при cache miss |
| `cache_snapshots.csv` | Состояние cache |
| `jvm_snapshots.csv` | Heap и GC |
| `summary.txt` | Текстовая сводка по таблицам |

### Анализ после миграции

```powershell
# Полный цикл: данные → миграция → delta sync → валидация → статус → анализ
.\run-migration.ps1 -Count 100000 -Analyze
```

### Полный цикл миграции через PowerShell-скрипт

Скрипт `run-migration.ps1` автоматизирует весь процесс:

1. Проверка Docker и контейнеров
2. Генерация тестовых данных (если source пуст)
3. Первичная миграция (`copy`)
4. Генерация новых данных (имитация live traffic, 1% от initial)
5. Delta sync (`sync`)
6. Валидация целостности (`validate`)
7. Финальный статус (`status`)
8. Python-анализ производительности (опционально)

#### Параметры скрипта

| Параметр | Описание | По умолчанию |
|----------|----------|--------------|
| `-Count <N>` | Количество базовых записей при генерации | 100000 |
| `-SkipGenerate` | Пропустить генерацию данных | false |
| `-SkipSync` | Пропустить delta sync | false |
| `-SkipValidate` | Пропустить валидацию | false |
| `-DryRun` | Пробный запуск без изменений | false |
| `-Analyze` | Запустить Python-анализ после миграции | false |
| `-MigrateIndexes` | Перенести индексы из source (анализ `pg_catalog`) | ❌ |
| `-CreateFkIndexes` | Создать индексы на FK-колонках (опционально) | ❌ |

#### Примеры использования

```powershell
# Быстрый тест (10K, без sync и валидации)
.\run-migration.ps1 -Count 10000 -SkipSync -SkipValidate

# Полная миграция с анализом производительности
.\run-migration.ps1 -Count 100000 -Analyze

# С переносом индексов из source (рекомендуется)
.\run-migration.ps1 -Count 100000 -MigrateIndexes -Analyze

# С FK-индексами (если в source они были на FK)
.\run-migration.ps1 -Count 100000 -CreateFkIndexes

# Только copy на существующих данных (без генерации)
.\run-migration.ps1 -Count 100000 -SkipGenerate -SkipSync -SkipValidate

# Dry run — проверить схему, но не копировать данные
.\run-migration.ps1 -Count 100000 -DryRun
```

#### Управление индексами в скрипте

| Команда | Что происходит с индексами |
|---------|---------------------------|
| `.\run-migration.ps1 -Count 100000` | Только таблицы + PK. Без дополнительных индексов. |
| `.\run-migration.ps1 -Count 100000 -MigrateIndexes` | Анализирует `pg_catalog` source, воссоздаёт те же индексы в target (UUID→BIGINT). **Рекомендуемый вариант.** |
| `.\run-migration.ps1 -Count 100000 -CreateFkIndexes` | Создаёт `idx_<table>_<fk_column>` на каждом FK. Не рекомендуется как базовая практика. |

В выводе скрипт отобразит примененную стратегию:
- `Indexes: перенесены из source` (зелёный) — при `-MigrateIndexes`
- `Indexes: FK-индексы созданы` (жёлтый) — при `-CreateFkIndexes`
- `Indexes: не переносятся (по умолчанию)` (серый) — без флагов

**Вывод анализатора:**
- Отчёт по каждой таблице (записи, время, скорость, батчи)
- Анализ узких мест (самая медленная таблица, максимальная дисперсия)
- Анализ пула соединений (утечки, contention)
- Распределение времени батчей (гистограмма)
- Графики: throughput, batch distribution, `copy_data`, `mapping_save`, `fk_lookup`, `commit`
- JSON-отчёт для внешнего анализа

### Сравнение UUID vs BIGINT

```powershell
# После миграции — сравнение размеров индексов
python resultAnalizer.py
```

Скрипт покажет:
- Размер индексов UUID vs BIGINT по каждой таблице
- Общую экономию в МБ и процентах
- Сравнение нескольких прогонов

---

## Troubleshooting

### Connection refused

**Причина:** PostgreSQL не запущен
```bash
docker ps
docker compose up -d
```

### Out of memory

**Решение 1:** Увеличить heap
```bash
export GRADLE_OPTS="-Xmx2G"
```

**Решение 2:** Использовать LAZY стратегию
```bash
./gradlew run --args="copy --mapping-strategy=LAZY"
```

**Решение 3:** Уменьшить cache-limit
```bash
./gradlew run --args="copy --cache-limit 100000"
```

### Connection pool exhausted

```bash
./gradlew run --args="copy --max-pool-size=20"
# Или использовать EAGER (меньше соединений)
./gradlew run --args="copy --mapping-strategy=EAGER"
```

### Медленная миграция

```bash
# Увеличить pool size, если есть ожидание соединений
./gradlew run --args="copy --max-pool-size 20"

# Проверить стратегию маппинга
./gradlew run --args="copy --mapping-strategy=EAGER"
```

### pg_dump: command not found

```bash
# Windows
setx PATH "%PATH%;C:\Program Files\PostgreSQL\15\bin"

# Linux
export PATH=$PATH:/usr/lib/postgresql/15/bin
```

### Нет активной миграции для rollback

```bash
# Указать migration-id явно
./gradlew run --args="rollback --table orders --migration-id migration_20260331"
```

### Out of Memory (JVM Heap Space)
Если вы видите рост памяти, сначала проверьте выбранную стратегию. `EAGER` намеренно держит весь mapping в heap. Для bounded cache используйте `LAZY` или `HYBRID`.
**Решение:**
Используйте флаг `--cache-limit`. Для большинства миграций достаточно 100 000 - 300 000 записей.

`./gradlew run --args="copy --mapping-strategy=LAZY --cache-limit 100000"`
