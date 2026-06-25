# Чистое сравнение EAGER / LAZY / HYBRID

Этот протокол нужен для сравнения стратегий mapping-кэша без загрязнения результатами предыдущих запусков.

## Инварианты

- Каждый режим запускается после `docker compose down -v`.
- Source dataset создаётся заново с одинаковыми `--count` и `--seed`.
- `cacheLimit`, `batchSize` и `maxPoolSize` фиксируются для сравниваемой серии.
- Сравнение строится по CSV из `performance_logs/run_<timestamp>/`, Grafana используется для live-контроля.

## Базовые параметры

```text
count=1000000
seed=42
cacheLimit=100000
batchSize=1000
```

## EAGER

```powershell
docker compose down -v
docker compose up -d
.\gradlew.bat run --args="generate-data --count 1000000 --truncate true --seed 42"
.\gradlew.bat run --args="copy --mapping-strategy=EAGER --cache-limit 100000 --batch-size 1000"
New-Item -ItemType Directory -Force performance_runs | Out-Null
$latestRun = Get-ChildItem performance_logs -Directory | Sort-Object LastWriteTime -Descending | Select-Object -First 1
Copy-Item $latestRun.FullName performance_runs\eager_count1000000_seed42_cache100000 -Recurse -Force
```

## LAZY

```powershell
docker compose down -v
docker compose up -d
.\gradlew.bat run --args="generate-data --count 1000000 --truncate true --seed 42"
.\gradlew.bat run --args="copy --mapping-strategy=LAZY --cache-limit 100000 --batch-size 1000"
New-Item -ItemType Directory -Force performance_runs | Out-Null
$latestRun = Get-ChildItem performance_logs -Directory | Sort-Object LastWriteTime -Descending | Select-Object -First 1
Copy-Item $latestRun.FullName performance_runs\lazy_count1000000_seed42_cache100000 -Recurse -Force
```

## HYBRID

```powershell
docker compose down -v
docker compose up -d
.\gradlew.bat run --args="generate-data --count 1000000 --truncate true --seed 42"
.\gradlew.bat run --args="copy --mapping-strategy=HYBRID --cache-limit 100000 --batch-size 1000"
New-Item -ItemType Directory -Force performance_runs | Out-Null
$latestRun = Get-ChildItem performance_logs -Directory | Sort-Object LastWriteTime -Descending | Select-Object -First 1
Copy-Item $latestRun.FullName performance_runs\hybrid_count1000000_seed42_cache100000 -Recurse -Force
```

## Источники метрик

| Метрика | Источник |
|---------|----------|
| strategy, cacheLimit, batchSize | `run_config.txt`, `batch_performance.csv` |
| total duration, rows/sec | `summary.txt`, `batch_performance.csv` |
| phase bottlenecks | `batch_phase_performance.csv` |
| peak heap | `max(jvm_snapshots.heap_used_bytes)` |
| GC time | `last(jvm_snapshots.gc_time_ms)` |
| cache max | `max(cache_snapshots.cache_size)` |
| lazy/pinned split | `cache_snapshots.lazy_cache_size`, `cache_snapshots.pinned_cache_size` |
| DB lookup count | количество строк в `mapping_db_lookup.csv` минус заголовок |
| DB lookup latency | `avg/max(mapping_db_lookup.duration_ms)` |

## Критерии вывода

- `EAGER` выигрывает, если скорость оправдывает peak heap.
- `LAZY` выигрывает, если heap стабилен и DB lookup latency приемлемая.
- `HYBRID` выигрывает, если `heap << EAGER`, `throughput > LAZY`, `db_lookup_count < LAZY`.

Если `HYBRID` не быстрее `LAZY`, pinned-таблицы выбраны неудачно или FK-профиль данных не даёт выигрыша.
