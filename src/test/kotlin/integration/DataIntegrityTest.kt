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
import org.assertj.core.api.Assertions.assertThatThrownBy
import java.util.*

/**
 * Data integrity tests after migration
 * Verifies all FK relationships preserved and data is correct
 */
@DisplayName("Data Integrity Validation After Migration")
class DataIntegrityTest : BaseIntegrationTest() {

    private lateinit var metadataReader: MetadataReader
    private lateinit var mappingService: engine.MappingServiceBase
    private lateinit var dataMigrator: DataMigrator

    @BeforeEach
    fun setUp() {
        cleanupTables("order_items", "profiles", "orders", "products", "users")
        executeTargetScript("DROP TABLE IF EXISTS migration_mapping CASCADE")
        executeScript(MigrationTestFixtures.SOURCE_SCHEMA_UUID)
        executeTargetScript(MigrationTestFixtures.TARGET_SCHEMA_BIGINT)

        metadataReader = MetadataReader(sourceDataSource)
        mappingService = MappingServiceFactory.create(targetDataSource, MappingStrategy.EAGER, 500_000)
        dataMigrator = DataMigrator(sourceDataSource, targetDataSource, mappingService, metadataReader)
    }

    @Nested
    @DisplayName("Referential Integrity")
    inner class ReferentialIntegrity {

        @Test
        fun `should preserve FK integrity after migration`() {
            insertTestDataWithIntegrity()
            val order = listOf("users", "products", "profiles", "orders", "order_items")
            order.forEach { table -> dataMigrator.migrateTable(table) }

            targetDataSource.connection.use { conn ->
                val insertResult = try {
                    conn.createStatement().execute(
                        "INSERT INTO profiles (user_id, first_name) VALUES (99999, 'Invalid')"
                    )
                    true
                } catch (e: Exception) {
                    false
                }

                assertThat(insertResult).isFalse()
            }
        }

        @Test
        fun `should migrate all related records correctly`() {
            val userUuids = insertTestData()
            dataMigrator.migrateTable("users")
            dataMigrator.migrateTable("profiles")

            targetDataSource.connection.use { conn ->
                val rs = conn.createStatement().executeQuery("""
                    SELECT p.id, p.user_id, u.id as user_exists
                    FROM profiles p
                    JOIN users u ON p.user_id = u.id
                """)

                var count = 0
                while (rs.next()) {
                    count++
                    assertThat(rs.getLong("user_exists")).isGreaterThan(0)
                }

                assertThat(count).isEqualTo(2)
            }
        }

        @Test
        fun `should prevent deleting referenced parent records`() {
            val userUuids = insertTestData()
            dataMigrator.migrateTable("users")
            dataMigrator.migrateTable("profiles")
            val newUserId = mappingService.getNewId("users", userUuids[0])

            assertThatThrownBy {
                targetDataSource.connection.use { conn ->
                    conn.createStatement().execute("DELETE FROM users WHERE id = $newUserId")
                }
            }.isInstanceOf(Exception::class.java)

            assertThat(countRowsTarget("profiles")).isEqualTo(2)
        }
    }

    @Nested
    @DisplayName("Data Completeness")
    inner class DataCompleteness {

        @Test
        fun `should migrate all records without loss`() {
            val uuids = List(10) { UUID.randomUUID() }
            insertMultipleUsers(uuids)
            dataMigrator.migrateTable("users")

            assertThat(countRowsTarget("users")).isEqualTo(10)

            uuids.forEach { uuid ->
                val newId = mappingService.getNewId("users", uuid)
                assertThat(newId).isNotNull().isGreaterThan(0)
            }
        }

        @Test
        fun `should preserve all data columns`() {
            val userUuid = UUID.randomUUID()
            insertSpecificUser(userUuid, "test@example.com", "2024-01-01 12:00:00")
            dataMigrator.migrateTable("users")

            targetDataSource.connection.use { conn ->
                val rs = conn.createStatement().executeQuery(
                    "SELECT email, created_at FROM users LIMIT 1"
                )
                assertThat(rs.next()).isTrue()
                assertThat(rs.getString("email")).isEqualTo("test@example.com")
                assertThat(rs.getTimestamp("created_at")).isNotNull()
            }
        }

        @Test
        fun `should skip records passed as existing ids on partial migration`() {
            val uuids = List(10) { UUID.randomUUID() }
            insertMultipleUsers(uuids)
            val existingIds = setOf(uuids[0], uuids[1], uuids[2], uuids[3], uuids[4])
            dataMigrator.migrateTable("users", existingIds)
            dataMigrator.migrateTable("users", existingIds)

            assertThat(countRowsTarget("users")).isEqualTo(5)
        }
    }

    @Nested
    @DisplayName("Mapping Consistency")
    inner class MappingConsistency {

        @Test
        fun `should have consistent mapping for all records`() {
            val uuids = List(5) { UUID.randomUUID() }
            insertMultipleUsers(uuids)
            dataMigrator.migrateTable("users")

            targetDataSource.connection.use { conn ->
                val rs = conn.createStatement().executeQuery(
                    "SELECT COUNT(*) FROM migration_mapping WHERE table_name = 'users'"
                )
                assertThat(rs.next()).isTrue()
                assertThat(rs.getLong(1)).isEqualTo(5)
            }
        }

        @Test
        fun `should maintain one-to-one UUID-BIGINT correspondence`() {
            val uuids = List(3) { UUID.randomUUID() }
            insertMultipleUsers(uuids)
            dataMigrator.migrateTable("users")

            val mappings = mutableMapOf<Long, UUID>()
            uuids.forEach { uuid ->
                val newId = mappingService.getNewId("users", uuid)
                assertThat(newId).isNotNull()
                assertThat(mappings).doesNotContainKey(newId)
                mappings[newId!!] = uuid
            }
        }

        @Test
        fun `should allow finding UUID by BIGINT`() {
            val uuids = List(3) { UUID.randomUUID() }
            insertMultipleUsers(uuids)
            dataMigrator.migrateTable("users")

            targetDataSource.connection.use { conn ->
                uuids.forEach { uuid ->
                    val newId = mappingService.getNewId("users", uuid)

                    val rs = conn.prepareStatement(
                        "SELECT old_uuid FROM migration_mapping WHERE table_name = ? AND new_id = ?"
                    )
                    rs.setString(1, "users")
                    rs.setLong(2, newId!!)
                    val resultRs = rs.executeQuery()

                    assertThat(resultRs.next()).isTrue()
                    assertThat(resultRs.getObject("old_uuid") as UUID).isEqualTo(uuid)
                }
            }
        }
    }

    @Nested
    @DisplayName("Post-Sync Validation")
    inner class PostSyncValidation {

        @Test
        fun `should preserve integrity after incremental sync`() {
            val uuids1 = List(5) { UUID.randomUUID() }
            insertMultipleUsers(uuids1)
            dataMigrator.migrateTable("users")

            val uuids2 = List(3) { UUID.randomUUID() }
            insertMultipleUsers(uuids2)

            val existingIds = mappingService.getAllMappedUuids("users")
            dataMigrator.migrateTable("users", existingIds)

            assertThat(countRowsTarget("users")).isEqualTo(8)

            (uuids1 + uuids2).forEach { uuid ->
                assertThat(mappingService.getNewId("users", uuid)).isNotNull()
            }
        }
    }

    private fun insertTestData(): List<UUID> {
        val uuids = List(3) { UUID.randomUUID() }
        getConnection().use { conn ->
            val pstmt = conn.prepareStatement("INSERT INTO users (id, email) VALUES (?, ?)")
            uuids.forEachIndexed { i, uuid ->
                pstmt.setObject(1, uuid)
                pstmt.setString(2, "user${i + 1}@test.com")
                pstmt.addBatch()
            }
            pstmt.executeBatch()

            val profilePstmt = conn.prepareStatement(
                "INSERT INTO profiles (user_id, first_name, last_name) VALUES (?, ?, ?)"
            )
            profilePstmt.setObject(1, uuids[0])
            profilePstmt.setString(2, "John")
            profilePstmt.setString(3, "Doe")
            profilePstmt.addBatch()

            profilePstmt.setObject(1, uuids[1])
            profilePstmt.setString(2, "Jane")
            profilePstmt.setString(3, "Smith")
            profilePstmt.addBatch()
            profilePstmt.executeBatch()
        }
        return uuids
    }

    private fun insertTestDataWithIntegrity() {
        insertTestData()
        getConnection().use { conn ->
            conn.createStatement().execute("""
                INSERT INTO products (name, price) VALUES 
                    ('Product 1', 10.00),
                    ('Product 2', 20.00);
            """)

            conn.createStatement().execute("""
                INSERT INTO orders (user_id, total_amount, status)
                SELECT id, 100.0, 'PENDING' FROM users LIMIT 2;
            """)

            conn.createStatement().execute("""
                INSERT INTO order_items (order_id, product_id, quantity)
                SELECT 
                    (SELECT id FROM orders LIMIT 1),
                    (SELECT id FROM products LIMIT 1),
                    2;
            """)
        }
    }

    private fun insertMultipleUsers(uuids: List<UUID>) {
        getConnection().use { conn ->
            val pstmt = conn.prepareStatement("INSERT INTO users (id, email) VALUES (?, ?)")
            uuids.forEachIndexed { i, uuid ->
                pstmt.setObject(1, uuid)
                pstmt.setString(2, "user${i}@test.com")
                pstmt.addBatch()
            }
            pstmt.executeBatch()
        }
    }

    private fun insertSpecificUser(uuid: UUID, email: String, createdAt: String) {
        getConnection().use { conn ->
            conn.prepareStatement(
                "INSERT INTO users (id, email, created_at) VALUES (?, ?, ?::timestamp)"
            ).use { pstmt ->
                pstmt.setObject(1, uuid)
                pstmt.setString(2, email)
                pstmt.setString(3, createdAt)
                pstmt.executeUpdate()
            }
        }
    }
}
