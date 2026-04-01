package tools

import org.slf4j.LoggerFactory
import java.sql.Connection
import java.sql.DriverManager
import java.util.*
import kotlin.random.Random

/**
 * Генератор тестовых данных для полной загрузки БД
 * Поддерживает генерацию от 10K до 10M записей
 */
class LargeDataGenerator(
    private val jdbcUrl: String,
    private val user: String = "user",
    private val password: String = "password"
) {
    private val logger = LoggerFactory.getLogger(LargeDataGenerator::class.java)

    /**
     * Конфигурация генерации
     */
    data class GenerationConfig(
        val baseCount: Int = 1_000_000,  // Базовое количество записей для основных таблиц
        val batchSize: Int = 10000,        // Увеличенный размер пакета для вставки
        val rewriteBatchedInserts: Boolean = true  // Оптимизация вставки
    )

    /**
     * Статистика генерации
     */
    data class GenerationStats(
        val tableName: String,
        val rowsGenerated: Long,
        val durationMs: Long,
        val rowsPerSecond: Double
    )

    /**
     * Полная генерация всех таблиц
     */
    fun generateAll(config: GenerationConfig = GenerationConfig()): List<GenerationStats> {
        val stats = mutableListOf<GenerationStats>()
        val baseCount = config.baseCount

        logger.info("Starting data generation: $baseCount base records per table")

        getConnection(config).use { conn ->
            conn.autoCommit = false
            
            // Оптимизация: увеличиваем work_mem и отключаем synchronous_commit
            conn.createStatement().execute("SET work_mem = '256MB'")
            conn.createStatement().execute("SET maintenance_work_mem = '512MB'")
            conn.createStatement().execute("SET synchronous_commit = OFF")
            conn.createStatement().execute("SET commit_delay = 100000")

            try {
                // Уровень 0: Справочники (без FK)
                logger.info("Generating level 0 (dictionaries)...")
                stats.add(generateTable(conn, "regions", baseCount / 100, config) { batch, count ->
                    val name = "Region_${count}"
                    batch.add(arrayOf(name))
                })

                stats.add(generateTable(conn, "suppliers", baseCount / 100, config) { batch, count ->
                    val name = "Supplier_${count}"
                    batch.add(arrayOf(name))
                })

                stats.add(generateTable(conn, "categories", baseCount / 100, config) { batch, count ->
                    val title = "Category_${count}"
                    batch.add(arrayOf(title))
                })

                stats.add(generateTable(conn, "customers", baseCount / 10, config) { batch, count ->
                    val name = "Customer_${count}"
                    batch.add(arrayOf(name))
                })

                conn.commit()

                // Уровень 1: Основные таблицы (с FK на уровень 0)
                logger.info("Generating level 1 (main tables)...")
                
                val regionIds = getIds(conn, "regions", baseCount / 10)
                stats.add(generateTable(conn, "users", baseCount, config) { batch, count ->
                    val email = "user${count}@example.com"
                    val regionId = regionIds.random()
                    batch.add(arrayOf(email, regionId))
                })

                val categoryIds = getIds(conn, "categories")
                val supplierIds = getIds(conn, "suppliers")
                stats.add(generateTable(conn, "products", baseCount / 10, config) { batch, count ->
                    val categoryId = categoryIds.random()
                    val supplierId = supplierIds.random()
                    val name = "Product_${count}"
                    val price = Random.nextDouble(10.0, 10000.0)
                    batch.add(arrayOf(categoryId, supplierId, name, price))
                })

                stats.add(generateTable(conn, "discount_coupons", baseCount / 100, config) { batch, count ->
                    val code = "COUPON_${count}"
                    val discount = Random.nextInt(5, 50)
                    batch.add(arrayOf(code, discount))
                })

                stats.add(generateTable(conn, "marketing_campaigns", baseCount / 1000, config) { batch, count ->
                    val regionId = regionIds.random()
                    val name = "Campaign_${count}"
                    batch.add(arrayOf(regionId, name))
                })

                conn.commit()

                // Уровень 2: Зависимые таблицы
                logger.info("Generating level 2 (dependent tables)...")

                val userIds = getIds(conn, "users", baseCount / 10)
                stats.add(generateTable(conn, "profiles", baseCount / 2, config) { batch, count ->
                    val userId = userIds.random()
                    val bio = "Bio for user $count"
                    batch.add(arrayOf(userId, bio))
                })

                stats.add(generateTable(conn, "user_settings", baseCount / 2, config) { batch, count ->
                    val userId = userIds.random()
                    val key = "setting_${count % 100}"
                    val value = "value_${Random.nextInt(1000)}"
                    batch.add(arrayOf(userId, key, value))
                })

                val productIds = getIds(conn, "products", baseCount / 100)
                stats.add(generateTable(conn, "warehouse_stocks", baseCount / 10, config) { batch, count ->
                    val productId = productIds.random()
                    val quantity = Random.nextInt(1, 1000)
                    batch.add(arrayOf(productId, quantity))
                })

                stats.add(generateTable(conn, "product_reviews", baseCount / 10, config) { batch, count ->
                    val productId = productIds.random()
                    val userId = userIds.random()
                    val rating = Random.nextInt(1, 6)
                    batch.add(arrayOf(productId, userId, rating))
                })

                stats.add(generateTable(conn, "audit_logs", baseCount, config) { batch, count ->
                    val userId = userIds.random()
                    val action = "ACTION_${Random.nextInt(100)}"
                    batch.add(arrayOf(userId, action))
                })

                conn.commit()

                // Уровень 3: Продажи
                logger.info("Generating level 3 (sales)...")

                val customerIds = getIds(conn, "customers")
                stats.add(generateTable(conn, "orders", baseCount, config) { batch, count ->
                    val customerId = customerIds.random()
                    batch.add(arrayOf(customerId))
                })

                val orderIds = getIds(conn, "orders", baseCount / 10)
                stats.add(generateTable(conn, "order_items", baseCount * 2, config) { batch, count ->
                    val orderId = orderIds.random()
                    val productId = productIds.random()
                    val qty = Random.nextInt(1, 10)
                    batch.add(arrayOf(orderId, productId, qty))
                })

                stats.add(generateTable(conn, "shipments", baseCount / 10, config) { batch, count ->
                    val orderId = orderIds.random()
                    val address = "Address ${Random.nextInt(10000)}"
                    batch.add(arrayOf(orderId, address))
                })

                val couponIds = getIds(conn, "discount_coupons")
                stats.add(generateTable(conn, "order_coupons", baseCount / 100, config) { batch, count ->
                    val orderId = orderIds.random()
                    val couponId = couponIds.random()
                    batch.add(arrayOf(orderId, couponId))
                })

                val campaignIds = getIds(conn, "marketing_campaigns")
                stats.add(generateTable(conn, "campaign_stats", baseCount / 1000, config) { batch, count ->
                    val campaignId = campaignIds.random()
                    val clicks = Random.nextInt(100, 100000)
                    batch.add(arrayOf(campaignId, clicks))
                })

                conn.commit()

                // Уровень 4: Поддержка
                logger.info("Generating level 4 (support)...")

                stats.add(generateTable(conn, "support_tickets", baseCount / 100, config) { batch, count ->
                    val userId = userIds.random()
                    val subject = "Ticket subject ${count}"
                    batch.add(arrayOf(userId, subject))
                })

                val ticketIds = getIds(conn, "support_tickets")
                stats.add(generateTable(conn, "ticket_messages", baseCount / 10, config) { batch, count ->
                    val ticketId = ticketIds.random()
                    val body = "Message body ${count}"
                    batch.add(arrayOf(ticketId, body))
                })

                conn.commit()

                logger.info("Data generation completed successfully")

            } catch (e: Exception) {
                conn.rollback()
                logger.error("Data generation failed: ${e.message}", e)
                throw e
            } finally {
                // Возвращаем настройки
                conn.createStatement().execute("SET synchronous_commit = ON")
                conn.createStatement().execute("SET commit_delay = 0")
            }
        }

        return stats
    }

    /**
     * Генерация одной таблицы
     */
    private fun generateTable(
        conn: Connection,
        tableName: String,
        count: Int,
        config: GenerationConfig,
        rowProvider: (MutableList<Array<Any>>, Int) -> Unit
    ): GenerationStats {
        logger.info("Generating $count records for $tableName")

        val startTime = System.currentTimeMillis()
        val batchSize = config.batchSize
        val batch = mutableListOf<Array<Any>>()
        var inserted = 0L

        val sql = "INSERT INTO $tableName (id, ${getColumns(tableName)}) VALUES (?, ${getPlaceholders(tableName)})"
        val pstmt = conn.prepareStatement(sql)

        for (i in 1..count) {
            rowProvider(batch, i)

            if (batch.size >= batchSize) {
                executeBatch(pstmt, batch)
                inserted += batch.size
                batch.clear()

                if (i % (batchSize * 10) == 0) {
                    logger.debug("$tableName: $i/$count records inserted")
                }
            }
        }

        // Последний батч
        if (batch.isNotEmpty()) {
            executeBatch(pstmt, batch)
            inserted += batch.size
        }

        pstmt.close()

        val duration = System.currentTimeMillis() - startTime
        val rowsPerSec = if (duration > 0) inserted * 1000.0 / duration else 0.0

        logger.info("$tableName: $inserted records in ${duration}ms (${rowsPerSec.toInt()} rows/sec)")

        return GenerationStats(tableName, inserted, duration, rowsPerSec)
    }

    /**
     * Выполнение пакета вставок
     */
    private fun executeBatch(pstmt: java.sql.PreparedStatement, batch: List<Array<Any>>) {
        batch.forEach { row ->
            val id = UUID.randomUUID()
            pstmt.setObject(1, id)
            row.forEachIndexed { index, value ->
                pstmt.setObject(index + 2, value)
            }
            pstmt.addBatch()
        }
        pstmt.executeBatch()
    }

    /**
     * Получение колонок для таблицы
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
     * Получение плейсхолдеров для таблицы
     */
    private fun getPlaceholders(tableName: String): String {
        val columnCount = getColumns(tableName).split(",").size
        return List(columnCount) { "?" }.joinToString(", ")
    }

    /**
     * Получение ID из таблицы
     */
    private fun getIds(conn: Connection, tableName: String, limit: Int? = null): List<UUID> {
        val ids = mutableListOf<UUID>()
        val sql = if (limit != null) {
            "SELECT id FROM $tableName LIMIT $limit"
        } else {
            "SELECT id FROM $tableName"
        }

        conn.createStatement().use { stmt ->
            val rs = stmt.executeQuery(sql)
            while (rs.next()) {
                ids.add(rs.getObject("id") as UUID)
            }
        }

        logger.debug("Loaded ${ids.size} IDs from $tableName")
        return ids
    }

    /**
     * Получение соединения с оптимизациями
     */
    private fun getConnection(config: GenerationConfig): Connection {
        val url = if (config.rewriteBatchedInserts) {
            "$jdbcUrl?rewriteBatchedInserts=true"
        } else {
            jdbcUrl
        }

        return DriverManager.getConnection(url, user, password)
    }

    /**
     * Очистка всех таблиц (для повторного тестирования)
     */
    fun truncateAll() {
        logger.info("Truncating all tables...")

        getConnection(GenerationConfig()).use { conn ->
            // Порядок важен из-за FK
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

        logger.info("All tables truncated")
    }
}
