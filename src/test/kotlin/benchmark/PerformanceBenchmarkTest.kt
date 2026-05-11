package benchmark

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.assertj.core.api.Assertions.assertThat
import integration.BaseIntegrationTest
import java.util.*

/**
 * Benchmark tests for UUID vs BIGINT performance comparison
 * Measures execution time and index sizes
 */
@DisplayName("Benchmark: UUID vs BIGINT Performance")
class PerformanceBenchmarkTest : BaseIntegrationTest() {

    @BeforeEach
    fun setUp() {
        cleanupTables(
            "uuid_users", "uuid_orders", "uuid_order_items",
            "bigint_users", "bigint_orders", "bigint_order_items"
        )
    }

    @AfterEach
    fun tearDown() {
        cleanupTables(
            "uuid_users", "uuid_orders", "uuid_order_items",
            "bigint_users", "bigint_orders", "bigint_order_items"
        )
    }

    @Nested
    @DisplayName("Index Size Comparison")
    inner class IndexSizeComparison {

        @Test
        fun `should show BIGINT indexes are smaller than UUID`() {
            val recordCount = 10_000
            val uuidIds = createUuidTable(recordCount)
            val bigintIds = createBigintTable(recordCount)

            getConnection().use { conn ->
                val uuidSize = getIndexSize(conn, "uuid_users", "uuid_users_pkey")
                val bigintSize = getIndexSize(conn, "bigint_users", "bigint_users_pkey")

                println("UUID index: $uuidSize bytes")
                println("BIGINT index: $bigintSize bytes")

                assertThat(bigintSize).isLessThan(uuidSize)
                val ratio = uuidSize.toDouble() / bigintSize
                println("Ratio: $ratio")
                assertThat(ratio).isGreaterThan(1.3)
            }
        }

        @Test
        fun `should show difference on large data volumes`() {
            val recordCount = 100_000
            createUuidTable(recordCount)
            createBigintTable(recordCount)

            getConnection().use { conn ->
                val uuidSize = getIndexSize(conn, "uuid_users", "uuid_users_pkey")
                val bigintSize = getIndexSize(conn, "bigint_users", "bigint_users_pkey")

                println("UUID index (${recordCount} records): ${uuidSize / 1024 / 1024} MB")
                println("BIGINT index (${recordCount} records): ${bigintSize / 1024 / 1024} MB")

                val savings = (uuidSize - bigintSize).toDouble() / uuidSize * 100
                println("Savings: $savings%")

                assertThat(savings).isGreaterThan(30.0)
            }
        }
    }

    @Nested
    @DisplayName("Insert Performance")
    inner class InsertPerformance {

        @Test
        fun `should show BIGINT insert is faster`() {
            val batchSize = 1000
            val batches = 10

            createUuidTable(0)
            createBigintTable(0)

            val uuidStartTime = System.currentTimeMillis()
            repeat(batches) {
                insertUuidBatch(batchSize)
            }
            val uuidDuration = System.currentTimeMillis() - uuidStartTime

            val bigintStartTime = System.currentTimeMillis()
            repeat(batches) {
                insertBigintBatch(batchSize)
            }
            val bigintDuration = System.currentTimeMillis() - bigintStartTime

            println("UUID insert (${batches * batchSize} records): $uuidDuration ms")
            println("BIGINT insert (${batches * batchSize} records): $bigintDuration ms")

            val uuidPerSec = (batches * batchSize) * 1000.0 / uuidDuration
            val bigintPerSec = (batches * batchSize) * 1000.0 / bigintDuration

            println("UUID records/sec: $uuidPerSec")
            println("BIGINT records/sec: $bigintPerSec")
        }
    }

    @Nested
    @DisplayName("Lookup Performance")
    inner class LookupPerformance {

        @Test
        fun `should show BIGINT index lookup is faster`() {
            val recordCount = 50_000
            val uuidIds = createUuidTable(recordCount)
            val bigintIds = createBigintTable(recordCount)

            val lookups = 1000
            val randomUuids = uuidIds.shuffled().take(lookups)
            val randomBigInts = bigintIds.shuffled().take(lookups)

            val uuidStartTime = System.nanoTime()
            getConnection().use { conn ->
                randomUuids.forEach { uuid ->
                    val rs = conn.prepareStatement("SELECT * FROM uuid_users WHERE id = ?")
                    rs.setObject(1, uuid)
                    rs.executeQuery().close()
                }
            }
            val uuidDuration = System.nanoTime() - uuidStartTime

            val bigintStartTime = System.nanoTime()
            getConnection().use { conn ->
                randomBigInts.forEach { id ->
                    val rs = conn.prepareStatement("SELECT * FROM bigint_users WHERE id = ?")
                    rs.setLong(1, id)
                    rs.executeQuery().close()
                }
            }
            val bigintDuration = System.nanoTime() - bigintStartTime

            println("UUID lookup ($lookups queries): ${uuidDuration / 1_000_000} ms")
            println("BIGINT lookup ($lookups queries): ${bigintDuration / 1_000_000} ms")

            val uuidAvg = uuidDuration.toDouble() / lookups
            val bigintAvg = bigintDuration.toDouble() / lookups

            println("UUID avg time: $uuidAvg ns")
            println("BIGINT avg time: $bigintAvg ns")
        }
    }

    @Nested
    @DisplayName("JOIN Performance")
    inner class JoinPerformance {

        @Test
        fun `should show BIGINT JOIN is faster`() {
            val orderCount = 10_000
            createUuidUsersWithOrders(orderCount)
            createBigintUsersWithOrders(orderCount)

            val iterations = 100

            val uuidStartTime = System.nanoTime()
            getConnection().use { conn ->
                repeat(iterations) {
                    val rs = conn.createStatement().executeQuery("""
                        SELECT u.email, o.total_amount
                        FROM uuid_users u
                        JOIN uuid_orders o ON u.id = o.user_id
                        LIMIT 1000
                    """)
                    rs.close()
                }
            }
            val uuidDuration = System.nanoTime() - uuidStartTime

            val bigintStartTime = System.nanoTime()
            getConnection().use { conn ->
                repeat(iterations) {
                    val rs = conn.createStatement().executeQuery("""
                        SELECT u.email, o.total_amount
                        FROM bigint_users u
                        JOIN bigint_orders o ON u.id = o.user_id
                        LIMIT 1000
                    """)
                    rs.close()
                }
            }
            val bigintDuration = System.nanoTime() - bigintStartTime

            println("UUID JOIN ($iterations iterations): ${uuidDuration / 1_000_000} ms")
            println("BIGINT JOIN ($iterations iterations): ${bigintDuration / 1_000_000} ms")

            val speedup = uuidDuration.toDouble() / bigintDuration
            println("Speedup: ${String.format("%.2f", speedup)}x")
        }

        @Test
        fun `should show advantage on complex JOINs`() {
            createUuidFullSchema(5000)
            createBigintFullSchema(5000)

            val iterations = 50

            val uuidStartTime = System.nanoTime()
            getConnection().use { conn ->
                repeat(iterations) {
                    val rs = conn.createStatement().executeQuery("""
                        SELECT u.email, o.total_amount, oi.quantity
                        FROM uuid_users u
                        JOIN uuid_orders o ON u.id = o.user_id
                        JOIN uuid_order_items oi ON o.id = oi.order_id
                        LIMIT 1000
                    """)
                    rs.close()
                }
            }
            val uuidDuration = System.nanoTime() - uuidStartTime

            val bigintStartTime = System.nanoTime()
            getConnection().use { conn ->
                repeat(iterations) {
                    val rs = conn.createStatement().executeQuery("""
                        SELECT u.email, o.total_amount, oi.quantity
                        FROM bigint_users u
                        JOIN bigint_orders o ON u.id = o.user_id
                        JOIN bigint_order_items oi ON o.id = oi.order_id
                        LIMIT 1000
                    """)
                    rs.close()
                }
            }
            val bigintDuration = System.nanoTime() - bigintStartTime

            println("Complex UUID JOIN: ${uuidDuration / 1_000_000} ms")
            println("Complex BIGINT JOIN: ${bigintDuration / 1_000_000} ms")
        }
    }

    @Nested
    @DisplayName("Aggregation Performance")
    inner class AggregationPerformance {

        @Test
        fun `should show BIGINT GROUP BY is faster`() {
            createUuidUsersWithOrders(20_000)
            createBigintUsersWithOrders(20_000)

            val iterations = 20

            val uuidStartTime = System.nanoTime()
            getConnection().use { conn ->
                repeat(iterations) {
                    val rs = conn.createStatement().executeQuery("""
                        SELECT user_id, COUNT(*), SUM(total_amount)
                        FROM uuid_orders
                        GROUP BY user_id
                    """)
                    rs.close()
                }
            }
            val uuidDuration = System.nanoTime() - uuidStartTime

            val bigintStartTime = System.nanoTime()
            getConnection().use { conn ->
                repeat(iterations) {
                    val rs = conn.createStatement().executeQuery("""
                        SELECT user_id, COUNT(*), SUM(total_amount)
                        FROM bigint_orders
                        GROUP BY user_id
                    """)
                    rs.close()
                }
            }
            val bigintDuration = System.nanoTime() - bigintStartTime

            println("UUID GROUP BY: ${uuidDuration / 1_000_000} ms")
            println("BIGINT GROUP BY: ${bigintDuration / 1_000_000} ms")
        }
    }

    private fun createUuidTable(count: Int): List<UUID> {
        val uuids = List(count) { UUID.randomUUID() }
        getConnection().use { conn ->
            conn.createStatement().execute("""
                CREATE TABLE IF NOT EXISTS uuid_users (
                    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
                    email VARCHAR(255) NOT NULL,
                    created_at TIMESTAMP DEFAULT NOW()
                );
            """)

            val pstmt = conn.prepareStatement("INSERT INTO uuid_users (id, email) VALUES (?, ?)")
            uuids.forEachIndexed { i, uuid ->
                pstmt.setObject(1, uuid)
                pstmt.setString(2, "user${i}@test.com")
                pstmt.addBatch()
            }
            pstmt.executeBatch()
        }
        return uuids
    }

    private fun createBigintTable(count: Int): List<Long> {
        val ids = mutableListOf<Long>()
        getConnection().use { conn ->
            conn.createStatement().execute("""
                CREATE TABLE IF NOT EXISTS bigint_users (
                    id BIGSERIAL PRIMARY KEY,
                    email VARCHAR(255) NOT NULL,
                    created_at TIMESTAMP DEFAULT NOW()
                );
            """)

            repeat(count) { i ->
                conn.createStatement().execute(
                    "INSERT INTO bigint_users (email) VALUES ('user${i}@test.com')",
                    java.sql.Statement.RETURN_GENERATED_KEYS
                )
                val rs = conn.createStatement().executeQuery("SELECT LASTVAL()")
                if (rs.next()) {
                    ids.add(rs.getLong(1))
                }
            }
        }
        return ids
    }

    private fun insertUuidBatch(batchSize: Int) {
        getConnection().use { conn ->
            val pstmt = conn.prepareStatement(
                "INSERT INTO uuid_users (id, email) VALUES (?, ?)"
            )
            repeat(batchSize) { i ->
                pstmt.setObject(1, UUID.randomUUID())
                pstmt.setString(2, "batch_user${System.nanoTime()}_$i@test.com")
                pstmt.addBatch()
            }
            pstmt.executeBatch()
        }
    }

    private fun insertBigintBatch(batchSize: Int) {
        getConnection().use { conn ->
            val pstmt = conn.prepareStatement(
                "INSERT INTO bigint_users (email) VALUES (?)"
            )
            repeat(batchSize) { i ->
                pstmt.setString(1, "batch_user${System.nanoTime()}_$i@test.com")
                pstmt.addBatch()
            }
            pstmt.executeBatch()
        }
    }

    private fun getIndexSize(conn: java.sql.Connection, tableName: String, indexName: String): Long {
        val rs = conn.prepareStatement("""
            SELECT pg_relation_size(?) AS size
        """)
        rs.setString(1, indexName)
        val resultRs = rs.executeQuery()
        return if (resultRs.next()) resultRs.getLong("size") else 0L
    }

    private fun createUuidUsersWithOrders(count: Int) {
        getConnection().use { conn ->
            conn.createStatement().execute("""
                CREATE TABLE IF NOT EXISTS uuid_users (
                    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
                    email VARCHAR(255) NOT NULL
                );
            """)

            conn.createStatement().execute("""
                CREATE TABLE IF NOT EXISTS uuid_orders (
                    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
                    user_id UUID REFERENCES uuid_users(id),
                    total_amount DECIMAL(10, 2)
                );
            """)

            val userIds = mutableListOf<UUID>()
            val userPstmt = conn.prepareStatement("INSERT INTO uuid_users (id, email) VALUES (?, ?)")
            repeat(count) { i ->
                val uuid = UUID.randomUUID()
                userIds.add(uuid)
                userPstmt.setObject(1, uuid)
                userPstmt.setString(2, "user$i@test.com")
                userPstmt.addBatch()
            }
            userPstmt.executeBatch()

            val orderPstmt = conn.prepareStatement(
                "INSERT INTO uuid_orders (user_id, total_amount) VALUES (?, ?)"
            )
            repeat(count * 5) { i ->
                val userUuid = userIds.random()
                orderPstmt.setObject(1, userUuid)
                orderPstmt.setDouble(2, Math.random() * 1000)
                orderPstmt.addBatch()
            }
            orderPstmt.executeBatch()
        }
    }

    private fun createBigintUsersWithOrders(count: Int) {
        getConnection().use { conn ->
            conn.createStatement().execute("""
                CREATE TABLE IF NOT EXISTS bigint_users (
                    id BIGSERIAL PRIMARY KEY,
                    email VARCHAR(255) NOT NULL
                );
            """)

            conn.createStatement().execute("""
                CREATE TABLE IF NOT EXISTS bigint_orders (
                    id BIGSERIAL PRIMARY KEY,
                    user_id BIGINT REFERENCES bigint_users(id),
                    total_amount DECIMAL(10, 2)
                );
            """)

            val userIds = mutableListOf<Long>()
            repeat(count) { i ->
                conn.createStatement().execute(
                    "INSERT INTO bigint_users (email) VALUES ('user$i@test.com')",
                    java.sql.Statement.RETURN_GENERATED_KEYS
                )
                val rs = conn.createStatement().executeQuery("SELECT LASTVAL()")
                if (rs.next()) {
                    userIds.add(rs.getLong(1))
                }
            }

            val orderPstmt = conn.prepareStatement(
                "INSERT INTO bigint_orders (user_id, total_amount) VALUES (?, ?)"
            )
            repeat(count * 5) { i ->
                val userId = userIds.random()
                orderPstmt.setLong(1, userId)
                orderPstmt.setDouble(2, Math.random() * 1000)
                orderPstmt.addBatch()
            }
            orderPstmt.executeBatch()
        }
    }

    private fun createUuidFullSchema(count: Int) {
        getConnection().use { conn ->
            conn.createStatement().execute("""
                CREATE TABLE IF NOT EXISTS uuid_users (
                    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
                    email VARCHAR(255) NOT NULL
                );
                CREATE TABLE IF NOT EXISTS uuid_orders (
                    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
                    user_id UUID REFERENCES uuid_users(id),
                    total_amount DECIMAL(10, 2)
                );
                CREATE TABLE IF NOT EXISTS uuid_order_items (
                    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
                    order_id UUID REFERENCES uuid_orders(id),
                    product_id UUID,
                    quantity INT
                );
            """)

            val userIds = mutableListOf<UUID>()
            val userPstmt = conn.prepareStatement("INSERT INTO uuid_users (id, email) VALUES (?, ?)")
            repeat(count) { i ->
                val uuid = UUID.randomUUID()
                userIds.add(uuid)
                userPstmt.setObject(1, uuid)
                userPstmt.setString(2, "user$i@test.com")
                userPstmt.addBatch()
            }
            userPstmt.executeBatch()

            val orderIds = mutableListOf<UUID>()
            val orderPstmt = conn.prepareStatement(
                "INSERT INTO uuid_orders (id, user_id, total_amount) VALUES (?, ?, ?)"
            )
            repeat(count) { i ->
                val orderId = UUID.randomUUID()
                orderIds.add(orderId)
                orderPstmt.setObject(1, orderId)
                orderPstmt.setObject(2, userIds.random())
                orderPstmt.setDouble(3, Math.random() * 1000)
                orderPstmt.addBatch()
            }
            orderPstmt.executeBatch()

            val itemPstmt = conn.prepareStatement(
                "INSERT INTO uuid_order_items (id, order_id, product_id, quantity) VALUES (?, ?, ?, ?)"
            )
            repeat(count * 3) { i ->
                itemPstmt.setObject(1, UUID.randomUUID())
                itemPstmt.setObject(2, orderIds.random())
                itemPstmt.setObject(3, UUID.randomUUID())
                itemPstmt.setInt(4, (1..10).random())
                itemPstmt.addBatch()
            }
            itemPstmt.executeBatch()
        }
    }

    private fun createBigintFullSchema(count: Int) {
        getConnection().use { conn ->
            conn.createStatement().execute("""
                CREATE TABLE IF NOT EXISTS bigint_users (
                    id BIGSERIAL PRIMARY KEY,
                    email VARCHAR(255) NOT NULL
                );
                CREATE TABLE IF NOT EXISTS bigint_orders (
                    id BIGSERIAL PRIMARY KEY,
                    user_id BIGINT REFERENCES bigint_users(id),
                    total_amount DECIMAL(10, 2)
                );
                CREATE TABLE IF NOT EXISTS bigint_order_items (
                    id BIGSERIAL PRIMARY KEY,
                    order_id BIGINT REFERENCES bigint_orders(id),
                    product_id BIGINT,
                    quantity INT
                );
            """)

            val userIds = mutableListOf<Long>()
            repeat(count) { i ->
                conn.createStatement().execute(
                    "INSERT INTO bigint_users (email) VALUES ('user$i@test.com')",
                    java.sql.Statement.RETURN_GENERATED_KEYS
                )
                val rs = conn.createStatement().executeQuery("SELECT LASTVAL()")
                if (rs.next()) {
                    userIds.add(rs.getLong(1))
                }
            }

            val orderIds = mutableListOf<Long>()
            val orderPstmt = conn.prepareStatement(
                "INSERT INTO bigint_orders (user_id, total_amount) VALUES (?, ?)",
                java.sql.Statement.RETURN_GENERATED_KEYS
            )
            repeat(count) { i ->
                orderPstmt.setLong(1, userIds.random())
                orderPstmt.setDouble(2, Math.random() * 1000)
                orderPstmt.addBatch()
            }
            orderPstmt.executeBatch()

            val rs = conn.createStatement().executeQuery("SELECT id FROM bigint_orders")
            while (rs.next()) {
                orderIds.add(rs.getLong(1))
            }

            val itemPstmt = conn.prepareStatement(
                "INSERT INTO bigint_order_items (order_id, product_id, quantity) VALUES (?, ?, ?)"
            )
            repeat(count * 3) { i ->
                itemPstmt.setLong(1, orderIds.random())
                itemPstmt.setLong(2, (1..1000).random().toLong())
                itemPstmt.setInt(3, (1..10).random())
                itemPstmt.addBatch()
            }
            itemPstmt.executeBatch()
        }
    }
}
