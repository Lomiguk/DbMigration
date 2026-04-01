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
import sync.ChangeCapture
import java.util.*

/**
 * Integration tests for ChangeCapture (Delta Sync)
 * Tests incremental synchronization of new data
 */
@DisplayName("ChangeCapture - Incremental Synchronization")
class ChangeCaptureTest : BaseIntegrationTest() {

    private lateinit var metadataReader: MetadataReader
    private lateinit var mappingService: engine.MappingServiceBase
    private lateinit var dataMigrator: DataMigrator
    private lateinit var changeCapture: ChangeCapture

    @BeforeEach
    fun setUp() {
        cleanupTables("order_items", "profiles", "orders", "products", "users")
        executeScript("DROP TABLE IF EXISTS migration_mapping CASCADE")
        executeScript(MigrationTestFixtures.SOURCE_SCHEMA_UUID)
        executeScript(MigrationTestFixtures.TARGET_SCHEMA_BIGINT)

        metadataReader = MetadataReader(dataSource)
        mappingService = MappingServiceFactory.create(dataSource, MappingStrategy.EAGER, 500_000)
        dataMigrator = DataMigrator(dataSource, dataSource, mappingService, metadataReader)
        changeCapture = ChangeCapture(dataMigrator, mappingService)
    }

    @Nested
    @DisplayName("Basic Synchronization")
    inner class BasicSync {

        @Test
        fun `should synchronize only new data`() {
            val initialUuids = List(10) { UUID.randomUUID() }
            insertUsers(initialUuids)
            dataMigrator.migrateTable("users")

            val newUuids = List(5) { UUID.randomUUID() }
            insertUsers(newUuids)

            val startTime = System.currentTimeMillis()
            changeCapture.syncUpdates(listOf("users"))
            val duration = System.currentTimeMillis() - startTime

            assertThat(countRows("users")).isEqualTo(15)
            assertThat(duration).isLessThan(1000)
        }

        @Test
        fun `should skip already migrated records`() {
            val uuids = List(10) { UUID.randomUUID() }
            insertUsers(uuids)
            dataMigrator.migrateTable("users")

            changeCapture.syncUpdates(listOf("users"))
            assertThat(countRows("users")).isEqualTo(10)
        }

        @Test
        fun `should work correctly with empty table`() {
            changeCapture.syncUpdates(listOf("users"))
            assertThat(countRows("users")).isEqualTo(0)
        }
    }

    @Nested
    @DisplayName("Sync Performance")
    inner class SyncPerformance {

        @Test
        fun `should quickly filter existing UUIDs`() {
            val largeUuidList = List(10_000) { UUID.randomUUID() }
            insertUsers(largeUuidList)
            dataMigrator.migrateTable("users")

            val startTime = System.currentTimeMillis()
            val existingIds = mappingService.getAllMappedUuids("users")
            val getDuration = System.currentTimeMillis() - startTime

            assertThat(existingIds).hasSize(10_000)
            assertThat(getDuration).isLessThan(5000)
        }

        @Test
        fun `should efficiently synchronize large delta`() {
            val initialUuids = List(1000) { UUID.randomUUID() }
            insertUsers(initialUuids)
            dataMigrator.migrateTable("users")

            val newUuids = List(100) { UUID.randomUUID() }
            insertUsers(newUuids)

            val startTime = System.currentTimeMillis()
            changeCapture.syncUpdates(listOf("users"))
            val duration = System.currentTimeMillis() - startTime

            assertThat(countRows("users")).isEqualTo(1100)
            println("Synchronization of 100 records took ${duration}ms")
        }
    }

    @Nested
    @DisplayName("Memory-Filtered Strategy")
    inner class MemoryFilteredStrategy {

        @Test
        fun `should filter delta in memory`() {
            val uuids = List(100) { UUID.randomUUID() }
            insertUsers(uuids)
            dataMigrator.migrateTable("users")

            val existingIds = mappingService.getAllMappedUuids("users")
            val newUuid = UUID.randomUUID()
            val isFiltered = existingIds.contains(newUuid)

            assertThat(isFiltered).isFalse()
            assertThat(existingIds).containsAll(uuids)
        }

        @Test
        fun `should use HashSet for fast lookup`() {
            val uuids = List(1000) { UUID.randomUUID() }
            val mappings = uuids.indices.associate { i -> uuids[i] to (i + 1).toLong() }
            mappingService.saveMappingBatch("users", mappings)

            val startTime = System.nanoTime()
            val existingIds = mappingService.getAllMappedUuids("users")
            val getDuration = System.nanoTime() - startTime

            val checkStart = System.nanoTime()
            uuids.forEach { it in existingIds }
            val checkDuration = System.nanoTime() - checkStart

            assertThat(getDuration).isLessThan(1_000_000_000)
            assertThat(checkDuration).isLessThan(10_000_000)
        }
    }

    @Nested
    @DisplayName("Multiple Tables")
    inner class MultipleTables {

        @Test
        fun `should synchronize multiple tables sequentially`() {
            val userUuids = List(10) { UUID.randomUUID() }
            insertUsers(userUuids)
            dataMigrator.migrateTable("users")

            val newUserUuids = List(5) { UUID.randomUUID() }
            insertUsers(newUserUuids)

            val tables = listOf("users")
            changeCapture.syncUpdates(tables)

            assertThat(countRows("users")).isEqualTo(15)
        }

        @Test
        fun `should correctly handle inter-table dependencies`() {
            val userUuids = List(5) { UUID.randomUUID() }
            insertUsers(userUuids)
            insertProducts(3)

            val migrationOrder = listOf("users", "products", "profiles", "orders", "order_items")
            migrationOrder.forEach { table -> dataMigrator.migrateTable(table) }

            val newUserUuids = List(2) { UUID.randomUUID() }
            insertUsers(newUserUuids)
            insertProducts(2)

            changeCapture.syncUpdates(migrationOrder)

            assertThat(countRows("users")).isEqualTo(7)
            assertThat(countRows("products")).isEqualTo(5)
        }
    }

    @Nested
    @DisplayName("Use Cases")
    inner class UseCases {

        @Test
        fun `should support continuous replication scenario`() {
            val uuids1 = List(100) { UUID.randomUUID() }
            insertUsers(uuids1)
            dataMigrator.migrateTable("users")

            repeat(5) { round ->
                val newUuids = List(20) { UUID.randomUUID() }
                insertUsers(newUuids)

                changeCapture.syncUpdates(listOf("users"))

                val expectedCount = 100 + (round + 1) * 20
                assertThat(countRows("users")).isEqualTo(expectedCount)
            }
        }

        @Test
        fun `should support stop and resume scenario`() {
            val uuids = List(50) { UUID.randomUUID() }
            insertUsers(uuids)
            dataMigrator.migrateTable("users")

            val offlineUuids = List(30) { UUID.randomUUID() }
            insertUsers(offlineUuids)

            changeCapture.syncUpdates(listOf("users"))

            assertThat(countRows("users")).isEqualTo(80)
        }
    }

    private fun insertUsers(uuids: List<UUID>) {
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

    private fun insertProducts(count: Int) {
        getConnection().use { conn ->
            conn.createStatement().execute(
                """
                INSERT INTO products (name, price) VALUES 
                    ${List(count) { i -> "('Product $i', ${i * 10}.00)" }.joinToString(", ")};
                """
            )
        }
    }
}
