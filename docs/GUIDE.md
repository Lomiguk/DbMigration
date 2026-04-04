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
| `--batch-size` | `-b` | Размер пакета | 1000 |
| `--cache-limit` | `-c` | Лимит кэша | 500000 |
| `--max-pool-size` | `-m` | Размер пула соединений | 10 |
| `--dry-run` | `-n` | Пробный запуск | false |
| `--verbose` | `-v` | Подробный вывод | false |
| `--config` | `-cfg` | Путь к конфигу | — |

### Примеры использования

```bash
# Базовая миграция
./gradlew run --args="init"
./gradlew run --args="copy"
./gradlew run --args="sync"

# Миграция с кастомными параметрами
./gradlew run --args="copy --batch-size 5000 --max-pool-size 20"

# Пробный запуск
./gradlew run --args="copy --dry-run --verbose"

# С конфигурационным файлом
./gradlew run --args="config-init"
# ... редактируем migration-config.yaml ...
./gradlew run --args="copy --config migration-config.yaml"

# Генерация тестовых данных
./gradlew run --args="generate-data"
./gradlew run --args="generate-data --count 100000"
./gradlew run --args="generate-data --count 1000000 --truncate"
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

### Примеры использования

**Полный цикл: генерация → миграция:**
```bash
# 1. Очистить и заполнить БД
./gradlew run --args="generate-data --count 1000000 --truncate"

# 2. Проанализировать схему
./gradlew run --args="init"

# 3. Мигрировать
./gradlew run --args="copy"
```

**Быстрое тестирование (100K):**
```bash
./gradlew run --args="generate-data --count 100000 --truncate"
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
maxPoolSize: 10
connectionTimeout: 30000

mappingStrategy: EAGER  # EAGER | LAZY | HYBRID
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

**Принцип:** Загрузить все маппинги перед миграцией

| Плюсы | Минусы |
|-------|--------|
| 0 соединений во время миграции | Требует RAM (~500MB для 20M маппингов) |
| Максимальная скорость (10000+ rec/s) | Долгая подготовка (1-2 мин) |

**Когда использовать:** достаточно RAM, миграция >1M записей

### LAZY (ленивая загрузка)

**Принцип:** Загружать маппинги по мере необходимости

| Плюсы | Минусы |
|-------|--------|
| Минимум RAM (~50MB) | Медленнее в 3-5 раз |
| Быстрый старт | Нестабильная скорость |

**Когда использовать:** ограничена память, миграция <500K записей

### Сравнение

| Записей | Стратегия | Время | Скорость | RAM | Соединения |
|---------|-----------|-------|----------|-----|------------|
| 1M | EAGER | 2 мин | 8333 rec/s | 150MB | 0 |
| 1M | LAZY | 8 мин | 2083 rec/s | 50MB | 500K |
| 6.6M | EAGER | 12 мин | 9166 rec/s | 500MB | 0 |
| 6.6M | LAZY | 45 мин | 2444 rec/s | 100MB | 3M |

### Формула расчёта памяти

```
Память (MB) = (Записи × FK на запись × 24 байта) / 1024 / 1024

Пример: 6.6M × 2.5 FK × 24 байта = 396 MB
```

### Рекомендации

| Сценарий | Стратегия | cacheLimit | maxPoolSize |
|----------|-----------|------------|-------------|
| Production (>5M) | EAGER | 20000000 | 20 |
| Тестирование (1-5M) | EAGER | 5000000 | 10 |
| Малые данные (<1M) | LAZY | 1000000 | 10 |
| Ограниченные ресурсы | LAZY | 500000 | 5 |

---

## Логирование производительности

### Файлы логов

После запуска тестов в `performance_logs/` создаются:

| Файл | Содержание |
|------|------------|
| `batch_performance_*.csv` | Метрики батчей (insert, mapping, commit) |
| `mapping_performance_*.csv` | Метрики маппинга |
| `connection_pool_*.csv` | Статистика пула соединений |
| `summary_*.txt` | Текстовая сводка |

### Формат batch_performance CSV

```csv
timestamp,table,batch_number,records_total,insert_duration_ms,mapping_duration_ms,commit_duration_ms,total_batch_ms,records_per_sec
```

### Ожидаемые метрики (1M записей)

| Метрика | Норма | Проблема если |
|---------|-------|---------------|
| Средняя скорость | 10000-50000 rec/s | < 5000 rec/s |
| Время батча (10K) | 150-300ms | > 500ms |
| INSERT время | 30-80ms | > 150ms |
| MAPPING время | 100-200ms | > 400ms |
| Active connections | 1-2 | > 3 |
| Waiting threads | 0 | > 0 |

### Анализ в Python

```python
import pandas as pd
import matplotlib.pyplot as plt

df = pd.read_csv('performance_logs/batch_performance_*.csv')
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

При каждой миграции (`copy`) автоматически собираются метрики в `performance_logs/`:

| Файл | Содержание |
|------|------------|
| `batch_performance_<ts>.csv` | Время каждого батча (insert, mapping, commit, r/s) |
| `mapping_performance_<ts>.csv` | Время маппинга UUID→BIGINT |
| `connection_pool_<ts>.csv` | Состояние пула соединений (active, idle, waiting) |
| `summary_<ts>.txt` | Текстовая сводка по таблицам |

### Анализ после миграции

```powershell
# Полный цикл с анализом
.\run-migration.ps1 -Count 100000 -Analyze

# Или вручную после миграции
python analyze-migration.py --charts --json
```

**Вывод анализатора:**
- Отчёт по каждой таблице (записи, время, скорость, батчи)
- Анализ узких мест (самая медленная таблица, максимальная дисперсия)
- Анализ пула соединений (утечки, contention)
- Распределение времени батчей (гистограмма)
- Графики: insert time, mapping time, throughput, batch distribution
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
docker-compose up -d
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
# Увеличить batch size и pool size
./gradlew run --args="copy --batch-size 5000 --max-pool-size 20"

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
