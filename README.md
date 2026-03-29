# PostgreSQL UUID to BIGINT Migration Tool

A research project for migrating PostgreSQL databases from UUID to BIGINT identifiers with comprehensive performance metrics comparison.

## 📋 Overview

This tool demonstrates and measures the benefits of migrating from UUID primary keys to BIGINT (sequential) identifiers in PostgreSQL databases. The migration preserves all foreign key relationships while significantly reducing index sizes.

### Key Benefits

- **~43-45% smaller indexes** (UUID: ~37-38 MB → BIGINT: ~21 MB for 1M records)
- Better query performance due to sequential index access
- Reduced storage requirements
- Maintained data integrity through automatic FK transformation

## 🏗️ Architecture

```
src/main/kotlin/
├── Main.kt                    # Entry point - orchestrates 6-step migration
├── core/
│   ├── MetadataReader.kt      # Reads table metadata and FK relationships
│   └── DependencyResolver.kt  # Builds dependency graph (JGraphT), topological sort
├── engine/
│   ├── MappingService.kt      # UUID→BIGINT mapping with 2-level cache
│   └── DataMigrator.kt        # Core migration engine, batch processing
├── sync/
│   └── ChangeCapture.kt       # Incremental delta synchronization
└── tools/
    ├── RunConfig.kt           # Configuration parameters
    ├── TestDataGenerator.kt   # Generates test data (1M records)
    └── ResultCollector.kt     # Collects and saves performance metrics
```

## 🗄️ Database Schema

The project works with **20 tables** organized in 4 logical domains:

| Domain | Tables | Dependency Depth |
|--------|--------|------------------|
| **Users** | `regions`, `users`, `profiles`, `user_settings` | 2 levels |
| **Warehouse** | `suppliers`, `categories`, `products`, `warehouse_stocks` | 2 levels |
| **Sales** | `customers`, `orders`, `order_items`, `shipments` | 3 levels |
| **Analytics** | `product_reviews`, `audit_logs`, `discount_coupons`, `order_coupons`, `support_tickets`, `ticket_messages`, `marketing_campaigns`, `campaign_stats` | 2-3 levels |

### Dependency Graph

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

## 🚀 Quick Start

### Prerequisites

- JDK 17+
- Docker & Docker Compose
- Gradle 8.x

### Setup

1. **Start PostgreSQL containers:**
   ```bash
   docker-compose up -d
   ```
   
   This creates two databases:
   - `source_db` on `localhost:5431` (UUID schema)
   - `target_db` on `localhost:5432` (BIGINT schema)

2. **Initialize the source database:**
   ```bash
   docker exec -i source_db psql -U postgres -d source_db < init.sql
   ```

3. **Run the migration:**
   ```bash
   ./gradlew run
   ```

## 🔄 Migration Process

The tool executes a **6-step migration workflow**:

```
┌─────────────────────────────────────────────────────────────┐
│  STEP 1: Generate Test Data                                 │
│  → TestDataGenerator creates 1M records with UUID PKs       │
└─────────────────────────────────────────────────────────────┘
                           ↓
┌─────────────────────────────────────────────────────────────┐
│  STEP 2: Analyze Database Structure                         │
│  → MetadataReader reads tables and FK constraints           │
│  → DependencyResolver builds graph and sorts tables         │
└─────────────────────────────────────────────────────────────┘
                           ↓
┌─────────────────────────────────────────────────────────────┐
│  STEP 3: Create Target Schema                               │
│  → DataMigrator creates tables with BIGSERIAL PRIMARY KEY   │
│  → UUID FK columns are converted to BIGINT                  │
└─────────────────────────────────────────────────────────────┘
                           ↓
┌─────────────────────────────────────────────────────────────┐
│  STEP 4: Initial Data Migration                             │
│  → Read from source in batches of 1000 records              │
│  → For each UUID FK, lookup in MappingService               │
│  → Insert into target with new BIGINT ID                    │
│  → Save UUID→BIGINT mapping to cache and DB                 │
└─────────────────────────────────────────────────────────────┘
                           ↓
┌─────────────────────────────────────────────────────────────┐
│  STEP 5: Incremental Synchronization                        │
│  → Generate 100 new records in source                       │
│  → ChangeCapture gets list of already migrated UUIDs        │
│  → Migrate only new/changed records (delta sync)            │
└─────────────────────────────────────────────────────────────┘
                           ↓
┌─────────────────────────────────────────────────────────────┐
│  STEP 6: Collect and Save Metrics                           │
│  → ResultCollector compares index sizes                     │
│  → Save results to results/run_<timestamp>/ and CSV         │
└─────────────────────────────────────────────────────────────┘
```

## ⚙️ Configuration

### Run Configuration (`RunConfig.kt`)

```kotlin
totalRecords = 1_000_000    // Records per table
batchSize = 1000            // Batch processing size
cacheLimit = 500_000        // In-memory cache limit
syncStrategy = "MEMORY_FILTERED"
```

### Build Configuration (`build.gradle.kts`)

| Dependency | Version | Purpose |
|------------|---------|---------|
| PostgreSQL Driver | 42.7.1 | JDBC driver |
| HikariCP | 5.1.0 | Connection pooling |
| JGraphT | 1.5.2 | Dependency graph algorithms |
| Kotlin Coroutines | 1.7.3 | Async processing |
| Exposed | 0.46.0 | Schema inspection |
| Logback | 1.4.11 | Logging |
| Testcontainers | 1.19.3 | Integration testing |

## 📊 Results

Results are saved to `results/run_<timestamp>/` directory and aggregated in `migration_history.csv`.

### Performance Comparison (1M records)

| Metric | UUID | BIGINT | Improvement |
|--------|------|--------|-------------|
| **Index Size** | ~37-38 MB | ~21 MB | **~43-45%** |
| **Storage** | Higher | Lower | Significant |
| **Sequential Access** | Random | Sequential | Better cache locality |

## 🔑 Key Features

1. **Topological Sorting** - Guarantees correct migration order based on FK dependencies
2. **Two-Level Mapping Cache** - ConcurrentHashMap (in-memory) + Database for persistence
3. **Batch Processing** - 1000 records per batch for optimal performance
4. **Delta Synchronization** - Only migrates new/changed data
5. **Automatic FK Transformation** - Transparent UUID→BIGINT conversion in relationships
6. **Comprehensive Metrics** - Index size comparison before/after migration

## 📁 Project Structure

```
DbMigrationArticleProject/
├── src/main/kotlin/           # Kotlin source code
├── init.sql                   # Source database schema (20 UUID tables)
├── compose.yaml               # Docker Compose configuration
├── build.gradle.kts           # Gradle build configuration
├── migration_history.csv      # Historical migration results
├── resultAnalizer.py          # Python script for result analysis
└── results/                   # Migration run results
```

## 🧪 Testing

Run tests with:
```bash
./gradlew test
```

The project uses Testcontainers for integration tests with real PostgreSQL instances.

## 🔍 Analysis

Use the provided Python script to analyze results:
```bash
python resultAnalizer.py
```

## 📝 License

This is a research project for educational and experimental purposes.

## 🤝 Contributing

Feel free to submit issues and enhancement requests!

## 📞 Author

Research project for database migration performance analysis.
