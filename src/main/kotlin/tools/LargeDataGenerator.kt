package tools

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import org.slf4j.LoggerFactory
import java.util.*
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlin.random.Random

/**
 * Test data generator with connection pool monitoring
 * Supports 10K to 10M records generation across 20 tables
 */
class LargeDataGenerator(
    private val jdbcUrl: String,
    private val user: String = "user",
    private val password: String = "password"
) {
    private val logger = LoggerFactory.getLogger(LargeDataGenerator::class.java)

    // Connection tracking
    private val totalConnectionsCreated = AtomicLong(0)
    private val totalConnectionsClosed = AtomicLong(0)
    private val activeConnections = AtomicInteger(0)
    private val peakActiveConnections = AtomicInteger(0)

    /**
     * Generation configuration
     */
    data class GenerationConfig(
        val baseCount: Int = 1_000_000,
        val batchSize: Int = 10000,
        val rewriteBatchedInserts: Boolean = true,
        val seed: Long? = null
    )

    /**
     * Generation statistics
     */
    data class GenerationStats(
        val tableName: String,
        val rowsGenerated: Long,
        val durationMs: Long,
        val rowsPerSecond: Double
    )

    /**
     * Connection pool statistics
     */
    data class ConnectionStats(
        val totalCreated: Long,
        val totalClosed: Long,
        val active: Int,
        val peakActive: Int,
        val poolActive: Int,
        val poolIdle: Int,
        val poolTotal: Int,
        val poolWaiting: Int
    )

    /**
     * Generate data for all tables using HikariCP connection pool
     */
    fun generateAll(config: GenerationConfig = GenerationConfig()): List<GenerationStats> {
        val stats = mutableListOf<GenerationStats>()
        val baseCount = config.baseCount
        val random = config.seed?.let { Random(it) } ?: Random.Default

        logger.info("Starting data generation: $baseCount base records per table, seed=${config.seed ?: "random"}")

        val pool = createConnectionPool(config)

        try {
            pool.connection.use { conn ->
                conn.autoCommit = false

                // PostgreSQL performance optimizations
                conn.createStatement().execute("SET work_mem = '256MB'")
                conn.createStatement().execute("SET maintenance_work_mem = '512MB'")
                conn.createStatement().execute("SET synchronous_commit = OFF")
                conn.createStatement().execute("SET commit_delay = 100000")

                logConnectionStats("START", pool)

                try {
                    // Level 0: Dictionaries (no FK)
                    logger.info("Generating level 0 (dictionaries)...")
                    stats.add(generateTable(pool, "regions", baseCount / 100, config, random) { batch, count, _ ->
                        batch.add(arrayOf("Region_${count}"))
                    })

                    stats.add(generateTable(pool, "suppliers", baseCount / 100, config, random) { batch, count, _ ->
                        batch.add(arrayOf("Supplier_${count}"))
                    })

                    stats.add(generateTable(pool, "categories", baseCount / 100, config, random) { batch, count, _ ->
                        batch.add(arrayOf("Category_${count}"))
                    })

                    stats.add(generateTable(pool, "customers", baseCount / 10, config, random) { batch, count, _ ->
                        batch.add(arrayOf("Customer_${count}"))
                    })

                    conn.commit()
                    logConnectionStats("AFTER_LEVEL_0", pool)

                    // Level 1: Main tables
                    logger.info("Generating level 1 (main tables)...")

                    val regionIds = getIds(pool, "regions", baseCount / 10)
                    stats.add(generateTable(pool, "users", baseCount, config, random) { batch, count, rng ->
                        batch.add(arrayOf("user${count}_${rng.nextInt(Int.MAX_VALUE)}@example.com", regionIds.random(rng)))
                    })

                    val categoryIds = getIds(pool, "categories")
                    val supplierIds = getIds(pool, "suppliers")
                    stats.add(generateTable(pool, "products", baseCount / 10, config, random) { batch, count, rng ->
                        batch.add(arrayOf(
                            categoryIds.random(rng),
                            supplierIds.random(rng),
                            "Product_${count}",
                            rng.nextDouble(10.0, 10000.0)
                        ))
                    })

                    stats.add(generateTable(pool, "discount_coupons", baseCount / 100, config, random) { batch, count, rng ->
                        batch.add(arrayOf("COUPON_${count}_${rng.nextInt(10000, 99999)}", rng.nextInt(5, 50)))
                    })

                    stats.add(generateTable(pool, "marketing_campaigns", baseCount / 1000, config, random) { batch, count, rng ->
                        batch.add(arrayOf(regionIds.random(rng), "Campaign_${count}"))
                    })

                    conn.commit()
                    logConnectionStats("AFTER_LEVEL_1", pool)

                    // Level 2: Dependent tables
                    logger.info("Generating level 2 (dependent tables)...")

                    val userIds = getIds(pool, "users", baseCount / 10)
                    stats.add(generateTable(pool, "profiles", baseCount / 2, config, random) { batch, count, rng ->
                        batch.add(arrayOf(userIds.random(rng), "Bio for user $count"))
                    })

                    stats.add(generateTable(pool, "user_settings", baseCount / 2, config, random) { batch, count, rng ->
                        batch.add(arrayOf(userIds.random(rng), "setting_${count % 100}", "value_${rng.nextInt(1000)}"))
                    })

                    val productIds = getIds(pool, "products", baseCount / 100)
                    stats.add(generateTable(pool, "warehouse_stocks", baseCount / 10, config, random) { batch, _, rng ->
                        batch.add(arrayOf(productIds.random(rng), rng.nextInt(1, 1000)))
                    })

                    stats.add(generateTable(pool, "product_reviews", baseCount / 10, config, random) { batch, _, rng ->
                        batch.add(arrayOf(productIds.random(rng), userIds.random(rng), rng.nextInt(1, 6)))
                    })

                    stats.add(generateTable(pool, "audit_logs", baseCount, config, random) { batch, _, rng ->
                        batch.add(arrayOf(userIds.random(rng), "ACTION_${rng.nextInt(100)}"))
                    })

                    conn.commit()
                    logConnectionStats("AFTER_LEVEL_2", pool)

                    // Level 3: Sales
                    logger.info("Generating level 3 (sales)...")

                    val customerIds = getIds(pool, "customers")
                    stats.add(generateTable(pool, "orders", baseCount, config, random) { batch, _, rng ->
                        batch.add(arrayOf(customerIds.random(rng)))
                    })

                    val orderIds = getIds(pool, "orders", baseCount / 10)
                    stats.add(generateTable(pool, "order_items", baseCount * 2, config, random) { batch, _, rng ->
                        batch.add(arrayOf(orderIds.random(rng), productIds.random(rng), rng.nextInt(1, 10)))
                    })

                    stats.add(generateTable(pool, "shipments", baseCount / 10, config, random) { batch, _, rng ->
                        batch.add(arrayOf(orderIds.random(rng), "Address ${rng.nextInt(10000)}"))
                    })

                    val couponIds = getIds(pool, "discount_coupons")
                    stats.add(generateTable(pool, "order_coupons", baseCount / 100, config, random) { batch, _, rng ->
                        batch.add(arrayOf(orderIds.random(rng), couponIds.random(rng)))
                    })

                    val campaignIds = getIds(pool, "marketing_campaigns")
                    stats.add(generateTable(pool, "campaign_stats", baseCount / 1000, config, random) { batch, _, rng ->
                        batch.add(arrayOf(campaignIds.random(rng), rng.nextInt(100, 100000)))
                    })

                    conn.commit()
                    logConnectionStats("AFTER_LEVEL_3", pool)

                    // Level 4: Support
                    logger.info("Generating level 4 (support)...")

                    stats.add(generateTable(pool, "support_tickets", baseCount / 100, config, random) { batch, count, rng ->
                        batch.add(arrayOf(userIds.random(rng), "Ticket subject $count"))
                    })

                    val ticketIds = getIds(pool, "support_tickets")
                    stats.add(generateTable(pool, "ticket_messages", baseCount / 10, config, random) { batch, count, rng ->
                        batch.add(arrayOf(ticketIds.random(rng), "Message body $count"))
                    })

                    conn.commit()
                    logConnectionStats("AFTER_LEVEL_4", pool)

                    logger.info("Data generation completed successfully")

                } catch (e: Exception) {
                    conn.rollback()
                    logger.error("Data generation failed: ${e.message}", e)
                    throw e
                } finally {
                    conn.createStatement().execute("SET synchronous_commit = ON")
                    conn.createStatement().execute("SET commit_delay = 0")
                }
            }
        } finally {
            logConnectionStats("FINAL", pool)
            pool.close()
            logger.info("Connection pool closed")
        }

        return stats
    }

    /**
     * Generate data for a single table
     */
    private fun generateTable(
        pool: HikariDataSource,
        tableName: String,
        count: Int,
        config: GenerationConfig,
        random: Random,
        rowProvider: (MutableList<Array<Any>>, Int, Random) -> Unit
    ): GenerationStats {
        logger.info("Generating $count records for $tableName")

        val startTime = System.currentTimeMillis()
        val batchSize = config.batchSize
        val batch = mutableListOf<Array<Any>>()
        var inserted = 0L

        pool.connection.use { conn ->
            conn.autoCommit = false
            val sql = "INSERT INTO $tableName (id, ${getColumns(tableName)}) VALUES (?, ${getPlaceholders(tableName)})"
            conn.prepareStatement(sql).use { pstmt ->
                for (i in 1..count) {
                    rowProvider(batch, i, random)

                    if (batch.size >= batchSize) {
                        executeBatch(pstmt, batch, random)
                        conn.commit()
                        inserted += batch.size
                        batch.clear()

                        if (i % (batchSize * 10) == 0) {
                            val mxBean = pool.hikariPoolMXBean
                            println("[CONN] $tableName: $i/$count | active=${activeConnections.get()} | pool_active=${mxBean?.activeConnections} | pool_idle=${mxBean?.idleConnections} | waiting=${mxBean?.threadsAwaitingConnection}")
                        }
                    }
                }

                if (batch.isNotEmpty()) {
                    executeBatch(pstmt, batch, random)
                    conn.commit()
                    inserted += batch.size
                }
            }
        }

        val duration = System.currentTimeMillis() - startTime
        val rowsPerSec = if (duration > 0) inserted * 1000.0 / duration else 0.0

        logger.info("$tableName: $inserted records in ${duration}ms (${rowsPerSec.toInt()} rows/sec)")

        return GenerationStats(tableName, inserted, duration, rowsPerSec)
    }

    /**
     * Execute batch inserts
     */
    private fun executeBatch(pstmt: java.sql.PreparedStatement, batch: List<Array<Any>>, random: Random) {
        batch.forEach { row ->
            val id = UUID(random.nextLong(), random.nextLong())
            pstmt.setObject(1, id)
            row.forEachIndexed { index, value ->
                pstmt.setObject(index + 2, value)
            }
            pstmt.addBatch()
        }
        pstmt.executeBatch()
    }

    /**
     * Get column list for a table
     */
    private fun getColumns(tableName: String): String {
        return when (tableName) {
            "regions" -> "name"
            "suppliers" -> "name"
            "categories" -> "title"
            "customers" -> "name"
            "users" -> "email, region_id"
            "products" -> "category_id, supplier_id, name, price"
            "discount_coupons" -> "code, discount_pct"
            "marketing_campaigns" -> "region_id, name"
            "profiles" -> "user_id, bio"
            "user_settings" -> "user_id, key, value"
            "warehouse_stocks" -> "product_id, quantity"
            "product_reviews" -> "product_id, user_id, rating"
            "audit_logs" -> "user_id, action"
            "orders" -> "customer_id"
            "order_items" -> "order_id, product_id, qty"
            "shipments" -> "order_id, address"
            "order_coupons" -> "order_id, coupon_id"
            "campaign_stats" -> "campaign_id, clicks"
            "support_tickets" -> "user_id, subject"
            "ticket_messages" -> "ticket_id, body"
            else -> throw IllegalArgumentException("Unknown table: $tableName")
        }
    }

    /**
     * Get placeholders for a table
     */
    private fun getPlaceholders(tableName: String): String {
        val columnCount = getColumns(tableName).split(",").size
        return List(columnCount) { "?" }.joinToString(", ")
    }

    /**
     * Get IDs from a table
     */
    private fun getIds(pool: HikariDataSource, tableName: String, limit: Int? = null): List<UUID> {
        val ids = mutableListOf<UUID>()
        val sql = if (limit != null) {
            "SELECT id FROM $tableName ORDER BY id LIMIT $limit"
        } else {
            "SELECT id FROM $tableName ORDER BY id"
        }

        pool.connection.use { conn ->
            conn.createStatement().use { stmt ->
                val rs = stmt.executeQuery(sql)
                while (rs.next()) {
                    ids.add(rs.getObject("id") as UUID)
                }
            }
        }

        logger.debug("Loaded ${ids.size} IDs from $tableName")
        return ids
    }

    /**
     * Create HikariCP connection pool
     */
    private fun createConnectionPool(config: GenerationConfig): HikariDataSource {
        // Include credentials in JDBC URL for reliable SCRAM auth
        val jdbcUrlWithParams = if (config.rewriteBatchedInserts) {
            "$jdbcUrl?user=$user&password=$password&rewriteBatchedInserts=true"
        } else {
            "$jdbcUrl?user=$user&password=$password"
        }

        val hikariConfig = HikariConfig().apply {
            this.jdbcUrl = jdbcUrlWithParams
            maximumPoolSize = 3
            minimumIdle = 1
            connectionTimeout = 30000
            idleTimeout = 60000
            maxLifetime = 1800000
            validationTimeout = 5000
            poolName = "data-generator-pool"
        }

        val pool = HikariDataSource(hikariConfig)
        logger.info("Connection pool created: maxPoolSize=3, minIdle=1")
        return pool
    }

    /**
     * Log connection statistics to stdout (visible in Gradle output)
     */
    private fun logConnectionStats(phase: String, pool: HikariDataSource) {
        val mxBean = pool.hikariPoolMXBean
        val stats = ConnectionStats(
            totalCreated = totalConnectionsCreated.get(),
            totalClosed = totalConnectionsClosed.get(),
            active = activeConnections.get(),
            peakActive = peakActiveConnections.get(),
            poolActive = mxBean?.activeConnections ?: -1,
            poolIdle = mxBean?.idleConnections ?: -1,
            poolTotal = (mxBean?.activeConnections ?: 0) + (mxBean?.idleConnections ?: 0),
            poolWaiting = mxBean?.threadsAwaitingConnection ?: 0
        )

        println(
            "[CONN_STATS] phase=$phase | " +
            "created=${stats.totalCreated} | closed=${stats.totalClosed} | " +
            "active=${stats.active} | peak=${stats.peakActive} | " +
            "pool_active=${stats.poolActive} | pool_idle=${stats.poolIdle} | " +
            "pool_total=${stats.poolTotal} | pool_waiting=${stats.poolWaiting}"
        )
    }

    /**
     * Truncate all tables (for re-testing)
     */
    fun truncateAll() {
        logger.info("Truncating all tables...")

        val pool = createConnectionPool(GenerationConfig())

        try {
            pool.connection.use { conn ->
                val tables = listOf(
                    "ticket_messages", "support_tickets", "campaign_stats", "order_coupons",
                    "shipments", "order_items", "orders", "product_reviews", "audit_logs",
                    "warehouse_stocks", "user_settings", "profiles", "products", "users",
                    "discount_coupons", "marketing_campaigns", "customers", "categories",
                    "suppliers", "regions"
                )

                conn.autoCommit = false
                tables.forEach { table ->
                    conn.createStatement().execute("TRUNCATE TABLE $table CASCADE")
                }
                conn.commit()
            }
        } finally {
            pool.close()
        }

        logger.info("All tables truncated")
    }
}
