# PostgreSQL UUID -> BIGINT Migration Tool

Enterprise-grade tool for migrating PostgreSQL databases from 128-bit UUID identifiers to 64-bit BIGINT identifiers. It preserves referential integrity, supports zero-downtime cutover, and provides real-time monitoring.

## Key Benefits

- **~43-45% smaller indexes** (UUID: ~37 MB -> BIGINT: ~21 MB for 1M rows).
- JOIN performance improvement by **2.2x**.
- **Zero-downtime migration** through logical replication (CDC via WAL).
- OutOfMemory protection through server-side cursors and bounded-cache strategies.
- Three mapping-cache strategies: **EAGER**, **LAZY**, and **HYBRID**.
- Reproducible test data generation with `generate-data --seed`.
- Automatic topological sorting of table dependencies with JGraphT.
- Built-in **observability** stack with Prometheus and Grafana.

## Quick Start

```bash
# 1. Start databases and monitoring infrastructure
docker compose up -d

# 2. Generate test data
./gradlew run --args="generate-data --count 50000 --seed 42"

# 3. Analyze schema and dependency graph
./gradlew run --args="init"

# 4. Run initial data migration without indexes
./gradlew run --args="copy --mapping-strategy=LAZY --cache-limit 100000"

# 4b. Or instead of the previous command: migrate with source indexes, recommended
./gradlew run --args="copy --mapping-strategy=LAZY --cache-limit 100000 --migrate-indexes"

# 5. Synchronize delta changes
./gradlew run --args="sync"

# 6. Check status
./gradlew run --args="status"
```

## Requirements

- JDK 17.
- Docker and Docker Compose.
- PostgreSQL logical replication for the source database. The local environment already enables it in `compose.yaml`.
- Gradle Wrapper from this repository: `./gradlew` for Linux/macOS or `.\gradlew.bat` for Windows.

## Configuration

By default, the application uses the local environment from `compose.yaml`:

| Purpose | Host | Port | Database | User |
|---------|------|------|----------|------|
| Source DB | localhost | 5431 | source_db | user |
| Target DB | localhost | 5432 | target_db | user |

Create a sample configuration file:

```bash
./gradlew run --args="config-init"
```

The command creates `migration-config.yaml`. After editing it, pass it to any CLI command:

```bash
./gradlew run --args="copy --config migration-config.yaml"
```

## Main CLI Commands

| Command | Purpose |
|---------|---------|
| `config-init` | Create a sample `migration-config.yaml` |
| `generate-data --seed 42` | Generate reproducible test data in the source database |
| `init` | Analyze the source schema and build the dependency graph |
| `copy` | Run the initial data migration |
| `copy --migrate-indexes` | Migrate real indexes from source to target |
| `copy --create-fk-indexes` | Create indexes on FK columns in target |
| `sync` | Run delta synchronization for new rows |
| `replicate` | Apply changes through WAL/CDC |
| `replicate --continuous` | Start continuous WAL replication |
| `validate` | Validate row counts, foreign keys, and mapping |
| `rollback` | Roll back migration |
| `backup` | Manage backup and restore operations |
| `status` | Show migration status and metrics |

## Mapping Cache Strategies

| Strategy | Behavior | Main benefit | Main risk |
|----------|----------|--------------|-----------|
| `EAGER` | Preloads mapping entries and serves lookups from RAM | Fastest lookup path | Memory grows with mapping size |
| `LAZY` | Uses bounded Caffeine cache and queries `migration_mapping` on cache misses | Predictable heap with `--cache-limit` | More DB lookups and lower throughput |
| `HYBRID` | Pins small-table mappings and uses bounded lazy cache for the rest | Speed/memory compromise | Benefit depends on FK access pattern |

For fair comparison, use a clean cycle per run: `docker compose down -v`, `docker compose up -d`, `generate-data --seed <N>`, then `copy`. Each run writes durable metrics to `performance_logs/run_<timestamp>/`.

## Testing

Unit tests do not require Docker:

```bash
./gradlew test --tests unit.*
```

Integration and benchmark tests use Testcontainers, so Docker must be running:

```bash
./gradlew test --tests integration.*
./gradlew test --tests benchmark.RealtimeReplicationTest
./gradlew benchmarkTest
```

## Local Services

After `docker compose up -d`, the following services are available:

| Service | URL |
|---------|-----|
| Source PostgreSQL | `localhost:5431` |
| Target PostgreSQL | `localhost:5432` |
| Prometheus | `http://localhost:9090` |
| Pushgateway | `http://localhost:9091` |
| Grafana | `http://localhost:3000` (`admin` / `admin`) |

## Production Notes and Limitations

- The tool is designed for PostgreSQL schemas with UUID primary keys migrated into a target schema with BIGINT/BIGSERIAL identifiers.
- Create backups of source and target databases before production migration.
- Run `validate` after `copy` or `replicate`.
- For production workloads, prefer `copy --migrate-indexes` to recreate the real source index layout.
- `copy --create-fk-indexes` is optional and creates indexes only on FK columns.

## Documentation

| Document | Description |
|----------|-------------|
| [GUIDE.md](docs/GUIDE.md) | Complete guide: CLI, backup, rollback, mapping, indexes, performance |
| [ARCHITECTURE.md](docs/ARCHITECTURE.md) | Architecture, core algorithms, and dependency graph structure |
| [OBSERVABILITY.md](docs/OBSERVABILITY.md) | Monitoring: Grafana, PromQL, CSV audit, index management |
| [TESTING.md](docs/TESTING.md) | Testing: unit, integration, benchmark |
| [PERFORMANCE_BENCHMARK.md](docs/PERFORMANCE_BENCHMARK.md) | Clean EAGER/LAZY/HYBRID comparison protocol |
| [DEVELOPMENT.md](docs/DEVELOPMENT.md) | Development plan, stage status, and backlog |

## Technology Stack

- **Kotlin 2.2** (JVM 17) as the main language.
- **PostgreSQL JDBC 42.7** as the database driver.
- **HikariCP 5.1** for connection pooling.
- **JGraphT 1.5** for topological sorting of dependencies.
- **Clikt + Mordant** for CLI.
- **Micrometer + Prometheus** for metrics.
- **Grafana** for visualization.
- **JUnit 5 + Testcontainers** for testing.

## Test Database Structure

The project includes a test E-commerce / ERP schema with 20 tables and complex relations up to 5 levels deep.

| Domain | Tables | Depth |
|--------|--------|-------|
| Users | regions, users, profiles, user_settings | 2 |
| Warehouse | suppliers, categories, products, warehouse_stocks | 2 |
| Sales | customers, orders, order_items, shipments | 3 |
| Analytics | product_reviews, audit_logs, discount_coupons, order_coupons, support_tickets, ticket_messages, marketing_campaigns, campaign_stats | 2-3 |
