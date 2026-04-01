package integration

import core.MetadataReader
import engine.DataMigrator
import engine.MappingServiceFactory
import engine.MappingStrategy
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.assertj.core.api.Assertions.assertThat
import java.util.*

/**
 * Integration tests for DataMigrator
 * Tests full data migration cycle from UUID to BIGINT schema
 */
@DisplayName("DataMigrator - Data Migration Between Schemas")
class DataMigratorTest : BaseIntegrationTest() {

    private lateinit var sourceMetadataReader: MetadataReader
    private lateinit var targetMetadataReader: MetadataReader
    private lateinit var mappingService: engine.MappingServiceBase
    private lateinit var dataMigrator: DataMigrator

    @BeforeEach
    fun setUp() {
        cleanupTables("order_items", "profiles", "orders", "products", "users")
        executeScript("DROP TABLE IF EXISTS migration_mapping CASCADE")
        executeScript(MigrationTestFixtures.SOURCE_SCHEMA_UUID)
        executeScript(MigrationTestFixtures.TARGET_SCHEMA_BIGINT)

        sourceMetadataReader = MetadataReader(dataSource)
        targetMetadataReader = MetadataReader(dataSource)
        mappingService = MappingServiceFactory.create(dataSource, MappingStrategy.EAGER, 500_000)
        dataMigrator = DataMigrator(dataSource, dataSource, mappingService, sourceMetadataReader)
    }

    @Nested
    @DisplayName("Create Target Schema")
    inner class CreateTargetSchema {

        @Test
        fun `should create tables with BIGSERIAL primary key`() {
            val tables = listOf("users", "products", "orders")
            dataMigrator.createTargetSchema(tables)

            getConnection().use { conn ->
                val rs = conn.metaData.getColumns(null, "public", "users", "id")
                assertThat(rs.next()).isTrue()
                assertThat(rs.getString("TYPE_NAME")).isEqualTo("int8")
            }
        }

        @Test
        fun `should convert UUID FK columns to BIGINT`() {
            val tables = listOf("profiles")
            dataMigrator.createTargetSchema(tables)

            getConnection().use { conn ->
                val rs = conn.metaData.getColumns(null, "public", "profiles", "user_id")
                assertThat(rs.next()).isTrue()
                assertThat(rs.getString("TYPE_NAME")).isEqualTo("int8")
            }
        }

        @Test
        fun `should preserve non-PK columns unchanged`() {
            val tables = listOf("users")
            dataMigrator.createTargetSchema(tables)

            getConnection().use { conn ->
                val rs = conn.metaData.getColumns(null, "public", "users", "email")
                assertThat(rs.next()).isTrue()
                assertThat(rs.getString("TYPE_NAME")).startsWith("varchar")
            }
        }
    }

    @Nested
    @DisplayName("Data Migration")
    inner class MigrateData {

        @Test
        fun `should migrate data from UUID table to BIGINT table`() {
            val userUuids = insertTestData()
            dataMigrator.migrateTable("users")

            getConnection().use { conn ->
                val rs = conn.createStatement().executeQuery("SELECT COUNT(*) FROM users")
                assertThat(rs.next()).isTrue()
                assertThat(rs.getLong(1)).isEqualTo(3)

                userUuids.forEach { uuid ->
                    val newId = mappingService.getNewId("users", uuid)
                    assertThat(newId).isNotNull().isGreaterThan(0)
                }
            }
        }

        @Test
        fun `should migrate data preserving FK relationships`() {
            val userUuids = insertTestData()
            insertProductsTestData()
            dataMigrator.migrateTable("users")
            dataMigrator.migrateTable("products")
            dataMigrator.migrateTable("orders")

            getConnection().use { conn ->
                val rs = conn.createStatement().executeQuery(
                    "SELECT o.id, o.user_id FROM orders o LIMIT 1"
                )
                assertThat(rs.next()).isTrue()
                val orderId = rs.getLong("id")
                val userId = rs.getLong("user_id")

                assertThat(orderId).isGreaterThan(0)
                assertThat(userId).isGreaterThan(0)
            }
        }

        @Test
        fun `should skip already migrated records`() {
            val userUuids = insertTestData()
            dataMigrator.migrateTable("users")
            val initialCount = countRows("users")

            dataMigrator.migrateTable("users")
            assertThat(countRows("users")).isEqualTo(initialCount)
        }

        @Test
        fun `should skip records from existingIds list`() {
            val userUuids = insertTestData()
            val existingIds = setOf(userUuids.first())
            dataMigrator.migrateTable("users", existingIds)

            assertThat(countRows("users")).isEqualTo(2)
        }

        @Test
        fun `should work correctly with empty table`() {
            dataMigrator.migrateTable("users")
            assertThat(countRows("users")).isEqualTo(0)
        }
    }

    @Nested
    @DisplayName("ID Mapping")
    inner class IdMapping {

        @Test
        fun `should save UUID to BIGINT mapping for each record`() {
            val userUuids = insertTestData()
            dataMigrator.migrateTable("users")

            getConnection().use { conn ->
                val rs = conn.createStatement().executeQuery(
                    "SELECT COUNT(*) FROM migration_mapping WHERE table_name = 'users'"
                )
                assertThat(rs.next()).isTrue()
                assertThat(rs.getLong(1)).isEqualTo(3)
            }
        }

        @Test
        fun `should generate unique BIGINT IDs for each UUID`() {
            val userUuids = insertTestData()
            dataMigrator.migrateTable("users")

            val mappedIds = userUuids.mapNotNull { uuid ->
                mappingService.getNewId("users", uuid)
            }

            assertThat(mappedIds).hasSize(3)
            assertThat(mappedIds.toSet()).hasSize(3)
        }
    }

    @Nested
    @DisplayName("Complex Scenarios")
    inner class ComplexScenarios {

        @Test
        fun `should migrate related schema data in correct order`() {
            insertFullTestData()
            val migrationOrder = listOf("users", "products", "profiles", "orders", "order_items")

            migrationOrder.forEach { table ->
                dataMigrator.migrateTable(table)
            }

            assertThat(countRows("users")).isEqualTo(3)
            assertThat(countRows("products")).isEqualTo(2)
            assertThat(countRows("profiles")).isEqualTo(2)
            assertThat(countRows("orders")).isEqualTo(2)
            assertThat(countRows("order_items")).isEqualTo(3)

            getConnection().use { conn ->
                val rs = conn.createStatement().executeQuery(
                    "SELECT COUNT(*) FROM migration_mapping"
                )
                assertThat(rs.next()).isTrue()
                assertThat(rs.getLong(1)).isEqualTo(12)
            }
        }

        @Test
        fun `should support incremental migration`() {
            val userUuids1 = insertTestData()
            dataMigrator.migrateTable("users")
            val firstMigrationCount = countRows("users")

            executeScript("""
                INSERT INTO users (email) VALUES 
                    ('incremental1@test.com'),
                    ('incremental2@test.com');
            """)

            val existingIds = mappingService.getAllMappedUuids("users")
            dataMigrator.migrateTable("users", existingIds)

            assertThat(countRows("users")).isEqualTo(firstMigrationCount + 2)
        }
    }

    private fun insertTestData(): List<UUID> {
        val uuids = List(3) { UUID.randomUUID() }
        getConnection().use { conn ->
            val pstmt = conn.prepareStatement(
                "INSERT INTO users (id, email) VALUES (?, ?)"
            )
            uuids.forEachIndexed { i, uuid ->
                pstmt.setObject(1, uuid)
                pstmt.setString(2, "user${i + 1}@test.com")
                pstmt.addBatch()
            }
            pstmt.executeBatch()
        }
        return uuids
    }

    private fun insertProductsTestData() {
        getConnection().use { conn ->
            conn.createStatement().execute("""
                INSERT INTO products (name, price) VALUES 
                    ('Product 1', 10.00),
                    ('Product 2', 20.00);
            """)
        }
    }

    private fun insertFullTestData() {
        val userUuids = insertTestData()
        insertProductsTestData()

        getConnection().use { conn ->
            val profilePstmt = conn.prepareStatement(
                "INSERT INTO profiles (user_id, first_name, last_name) VALUES (?, ?, ?)"
            )
            profilePstmt.setObject(1, userUuids[0])
            profilePstmt.setString(2, "John")
            profilePstmt.setString(3, "Doe")
            profilePstmt.addBatch()

            profilePstmt.setObject(1, userUuids[1])
            profilePstmt.setString(2, "Jane")
            profilePstmt.setString(3, "Smith")
            profilePstmt.addBatch()
            profilePstmt.executeBatch()

            val orderPstmt = conn.prepareStatement(
                "INSERT INTO orders (user_id, total_amount, status) VALUES (?, ?, ?)"
            )
            orderPstmt.setObject(1, userUuids[0])
            orderPstmt.setDouble(2, 100.0)
            orderPstmt.setString(3, "COMPLETED")
            orderPstmt.addBatch()

            orderPstmt.setObject(1, userUuids[1])
            orderPstmt.setDouble(2, 200.0)
            orderPstmt.setString(3, "PENDING")
            orderPstmt.addBatch()
            orderPstmt.executeBatch()

            conn.createStatement().execute("""
                INSERT INTO order_items (order_id, product_id, quantity)
                SELECT 
                    (SELECT id FROM orders LIMIT 1),
                    (SELECT id FROM products LIMIT 1),
                    2
                UNION ALL
                SELECT 
                    (SELECT id FROM orders LIMIT 1),
                    (SELECT id FROM products OFFSET 1 LIMIT 1),
                    1
                UNION ALL
                SELECT 
                    (SELECT id FROM orders OFFSET 1 LIMIT 1),
                    (SELECT id FROM products LIMIT 1),
                    3;
            """)
        }
    }
}
