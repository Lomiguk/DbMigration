# Мониторинг и Observability

Инструмент миграции предоставляет двухуровневую систему мониторинга:
1. **Real-time Observability (Grafana + Prometheus)** — для наблюдения за процессом "вживую".
2. **Offline Audit (CSV Logs)** — для ретроспективного анализа и хранения истории запусков.

---

## 1. Real-time Мониторинг (Grafana)

Инфраструктура метрик поднимается автоматически вместе с базами данных через `docker compose`.

**Стек технологий:**
* **Micrometer** — собирает метрики внутри Kotlin-кода.
* **Prometheus** (`localhost:9090`) — хранит time-series данные, скрейпит приложение каждые 5 секунд.
* **Grafana** (`localhost:3000`) — визуализирует данные на пред-настроенном дашборде.

### Как запустить

1. Убедитесь, что контейнеры запущены:
   ```powershell
   docker compose up -d
   ```

2. Сгенерируйте данные и запустите миграцию:
   ```powershell
   # Быстрый вариант через скрипт
   .\run-migration.ps1 -Count 50000 -SkipSync -SkipValidate -MigrateIndexes

   # Или вручную
   .\gradlew.bat run --args="generate-data --count 50000 --seed 42"
   .\gradlew.bat run --args="copy --mapping-strategy=LAZY --cache-limit 100000 --migrate-indexes"
   ```

3. **Во время выполнения `copy`** откройте Grafana:
   * Браузер → **http://localhost:3000** (логин/пароль: `admin` / `admin`)
   * Dashboards → **"DB Migration Observatory"**
   * Временной диапазон: **Last 5 minutes**
   * Автообновление: **5s** (правый верхний угол)

### Настройка Data Source (если дашборд пуст)

Если Prometheus не подключён:
1. *Administration → Data sources → Add data source*
2. Выберите **Prometheus**
3. URL: `http://prometheus:9090`
4. *Save & test*

> ⚠️ Метрики собираются только пока работает приложение. После завершения `copy` дашборд перестаёт обновляться. Открывайте Grafana **во время** миграции.

### Полезные PromQL запросы

| Метрика                                                                                  | PromQL                                                                                                                                        | Тип графика |
|------------------------------------------------------------------------------------------|-----------------------------------------------------------------------------------------------------------------------------------------------|-------------|
| **Всего мигрировано строк**                                                              | `sum(migration_rows_total) by (table)`                                                                                                        | Bar gauge / Stat |
| **Скорость миграции (строк/сек)**                                                        | `rate(migration_rows_total[1m])`                                                                                                              | Time series |
| **Размер кэша маппинга**                                                                 | `mapping_cache_size`                                                                                                                          | Stat |
| **Разделение cache на lazy/pinned**                                                      | `mapping_cache_entries{type="lazy"}`, `mapping_cache_entries{type="pinned"}`                                                                  | Time series |
| **Среднее время батча (insert)**                                                         | `rate(migration_batch_duration_seconds_sum{operation="insert"}[1m]) / rate(migration_batch_duration_seconds_count{operation="insert"}[1m])`   | Time series |
| **Среднее время батча (mapping)**                                                        | `rate(migration_batch_duration_seconds_sum{operation="mapping"}[1m]) / rate(migration_batch_duration_seconds_count{operation="mapping"}[1m])` | Time series |
| **HikariCP соединения**                                                                  | `hikaricp_connections_active`, `hikaricp_connections_idle`, `hikaricp_connections_pending`                                                    | Time series |
| **Отставание WAL репликации**                                                            | `replication_lag_bytes`                                                                                                                       | Time series |
| **Эффективность кэша (0.0 - 1.0). Позволяет оценить пользу HYBRID стратегии.**           | `mapping_cache_hit_rate`                                                                                                                      |	Gauge |
| *Количество ключей, удаленных для защиты памяти. Сигнализирует о достижении лимита RAM.* | `mapping_cache_evictions`                                                                                                                     | Counter
| *Реальное количество объектов в оперативной памяти.*                                     | `mapping_cache_size`                                                                                                                          | Gauge
| **DB lookup rate при cache miss**                                                        | `sum by (strategy, table) (rate(mapping_db_lookup_total[1m]))`                                                                                 | Time series |
| **Средняя latency DB lookup**                                                            | `sum by (strategy, table) (rate(mapping_db_lookup_duration_seconds_sum[1m])) / sum by (strategy, table) (rate(mapping_db_lookup_duration_seconds_count[1m]))` | Time series |

**Пороговые значения:**
- `mapping_cache_size` у `LAZY` должен выходить на плато около `cacheLimit`; кратковременный overshoot Caffeine допустим
- у `HYBRID` смотрите `mapping_cache_entries{type="pinned"}` и `mapping_cache_entries{type="lazy"}` отдельно
- `hikaricp_connections_pending > 0` — пул соединений исчерпан, увеличьте `--max-pool-size`
- рост `mapping_db_lookup_total` показывает цену `LAZY/HYBRID` на cache miss

---

## 2. Оффлайн Аудит (CSV Logs)

В дополнение к метрикам реального времени, приложение использует `PerformanceLogger` для генерации подробных отчётов о каждом запуске. Это необходимо для расследования инцидентов после завершения миграции.

При каждом запуске команд `copy`, `sync` или `resume` в корне проекта создаётся папка с таймстемпом:

```
performance_logs/run_YYYYMMDD_HHMMSS/
├── run_config.txt
├── summary.txt
├── batch_performance.csv
├── mapping_performance.csv
├── mapping_db_lookup.csv
├── cache_snapshots.csv
├── jvm_snapshots.csv
└── connection_pool.csv
```

### Структура отчёта

| Файл | Содержание |
|------|-----------|
| `run_config.txt` | Команда, стратегия, `cacheLimit`, batch size, параметры подключения |
| `summary.txt` | Человекочитаемый отчёт: общее время, средняя скорость, статистика по каждой таблице |
| `batch_performance.csv` | Лог каждого батча. Колонки: `timestamp, table, batch_number, records_total, insert_duration_ms, mapping_duration_ms, commit_duration_ms, total_batch_ms, records_per_sec` |
| `mapping_performance.csv` | Производительность сохранения UUID→BIGINT mapping |
| `mapping_db_lookup.csv` | DB lookup на cache miss: `timestamp, strategy, table, duration_ms, found` |
| `cache_snapshots.csv` | `cache_size`, `lazy_cache_size`, `pinned_cache_size`, hit rate, evictions, misses |
| `jvm_snapshots.csv` | Heap, non-heap, GC count/time |
| `connection_pool.csv` | Срез HikariCP: `timestamp, table, active_connections, idle_connections, total_connections, waiting_threads` |

### Ожидаемые метрики (ориентиры для 1M записей)

| Метрика | Норма | Проблема если |
|---------|-------|---------------|
| Средняя скорость | сравнивайте между стратегиями на одном seed | резкая деградация при одинаковом dataset |
| Время батча (1000) | 150–300 ms | > 500 ms |
| INSERT время | 30–80 ms | > 150 ms |
| MAPPING время | 100–200 ms | > 400 ms |
| Active connections | 1–2 | > 3 |
| Waiting threads | 0 | > 0 |

### Анализ через Python

```powershell
# Полный отчёт + графики (performance.png)
python analyze-migration.py --charts --json

# Сравнение размеров индексов UUID vs BIGINT
python resultAnalizer.py
```

**Вывод анализатора:**
- Отчёт по каждой таблице (записи, время, скорость, батчи)
- Анализ узких мест (самая медленная таблица, максимальная дисперсия)
- Анализ пула соединений (утечки, contention)
- Распределение времени батчей (гистограмма)
- JSON-отчёт для внешнего анализа

---

## 3. Чистый performance benchmark

Для сравнения режимов используйте одинаковый seed и полностью чистую БД перед каждым запуском. Не сравнивайте `EAGER`, `LAZY` и `HYBRID` на базе, где уже остались данные, mapping-таблицы или прогретый page cache после предыдущего сценария.

Минимальный протокол для каждого режима:

```powershell
docker compose down -v
docker compose up -d
.\gradlew.bat run --args="generate-data --count 1000000 --seed 42"
.\gradlew.bat run --args="copy --mapping-strategy=LAZY --cache-limit 100000 --migrate-indexes"
```

Меняйте только `--mapping-strategy` и, если нужно, `--cache-limit`. После завершения каждого прогона сохраните:
- каталог `performance_logs/run_YYYYMMDD_HHMMSS/`
- скрин или экспорт Grafana за время выполнения `copy`
- значения Docker/container memory limit, JVM flags и версию commit

Подробный пошаговый сценарий сравнения описан в [PERFORMANCE_BENCHMARK.md](PERFORMANCE_BENCHMARK.md).

---

## 4. Управление индексами

### Три подхода к индексам при миграции

| Подход | Gradle | PowerShell-скрипт | Что делает |
|--------|--------|-------------------|-----------|
| **Без индексов** (по умолчанию) | `copy` | `.\run-migration.ps1` | Только таблицы + PK. Безопасно, минимум побочных эффектов. |
| **Перенос из source** (рекомендуется) | `copy --migrate-indexes` | `.\run-migration.ps1 -MigrateIndexes` | Анализирует `pg_catalog` source, воссоздаёт те же индексы в target с автоматической заменой UUID→BIGINT. Переносятся UNIQUE, составные и partial индексы. |
| **FK-индексы** (опционально) | `copy --create-fk-indexes` | `.\run-migration.ps1 -CreateFkIndexes` | Создаёт `idx_<table>_<fk_column>` на каждом FK. Не рекомендуется как базовая практика. |

### Почему по умолчанию без индексов?

Создание индексов без анализа реальной схемы source — опасная практика:
- Могут быть созданы ненужные индексы, замедляющие запись
- Пропущены специфичные индексы (partial, composite, covering)
- В production могут быть custom-индексы, не связанные с FK

`--migrate-indexes` решает эту проблему: переносятся только те индексы, которые реально существуют, с корректной трансформацией типов.

### Индексы в тестовой схеме

Тестовая схема (`init.sql`) включает 22 кастомных индекса помимо PRIMARY KEY:

| Тип | Индексы | Примеры |
|-----|---------|---------|
| FK-индексы | 15 | `idx_users_region_id`, `idx_orders_customer_id` |
| UNIQUE | 2 | `idx_users_email`, `idx_discount_coupons_code` |
| Поисковые | 5 | `idx_orders_created_at`, `idx_warehouse_stocks_product_id` |

Это позволяет тестировать `--migrate-indexes` на реалистичном наборе.
