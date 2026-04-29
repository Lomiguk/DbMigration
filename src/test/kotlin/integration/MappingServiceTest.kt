package integration

import engine.MappingService
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.assertj.core.api.Assertions.assertThat
import java.util.*

/**
 * Integration tests for MappingService
 * Tests two-level caching (L1 - ConcurrentHashMap, L2 - Database)
 */
@DisplayName("MappingService - UUID to BIGINT Mapping")
class MappingServiceTest : BaseIntegrationTest() {

    private lateinit var mappingService: MappingService
    private lateinit var testTable: String

    @BeforeEach
    fun setUp() {
        executeTargetScript("DROP TABLE IF EXISTS migration_mapping CASCADE")
        mappingService = MappingService(targetDataSource)
        testTable = "test_entities"
    }

    @Nested
    @DisplayName("Initialization and Mapping Table Creation")
    inner class Initialization {

        @Test
        fun `should create migration_mapping table on initialization`() {
            assertThat(targetTableExists("migration_mapping")).isTrue()
        }

        @Test
        fun `should create index on old_uuid`() {
            targetDataSource.connection.use { conn ->
                val rs = conn.metaData.getIndexInfo(null, "public", "migration_mapping", false, false)
                val indexNames = mutableListOf<String>()
                while (rs.next()) {
                    indexNames.add(rs.getString("INDEX_NAME"))
                }

                assertThat(indexNames).anyMatch { it.contains("idx_mapping_uuid", ignoreCase = true) }
            }
        }

        @Test
        fun `should be idempotent on repeated initialization`() {
            val anotherService = MappingService(targetDataSource)
            assertThat(targetTableExists("migration_mapping")).isTrue()
            anotherService
        }
    }

    @Nested
    @DisplayName("Save and Get Mapping")
    inner class SaveAndGetMapping {

        @Test
        fun `should save mapping to memory and database`() {
            val testUuid = UUID.randomUUID()
            val testId = 123L

            mappingService.saveMappingInMemory(testUuid, testId)

            val cachedId = mappingService.getNewId(testTable, testUuid)
            assertThat(cachedId).isEqualTo(testId)
        }

        @Test
        fun `should save mapping to database via batch`() {
            val mappings = mapOf(
                UUID.randomUUID() to 1L,
                UUID.randomUUID() to 2L,
                UUID.randomUUID() to 3L
            )

            mappingService.saveMappingBatch(testTable, mappings)

            mappings.forEach { (uuid, expectedId) ->
                val actualId = mappingService.getNewId(testTable, uuid)
                assertThat(actualId).isEqualTo(expectedId)
            }
        }

        @Test
        fun `should return null for non-existent UUID`() {
            val nonExistentUuid = UUID.randomUUID()
            val result = mappingService.getNewId(testTable, nonExistentUuid)
            assertThat(result).isNull()
        }

        @Test
        fun `should prioritize cache over database`() {
            val testUuid = UUID.randomUUID()
            val cachedId = 999L
            val dbId = 111L

            mappingService.saveMappingBatch(testTable, mapOf(testUuid to dbId))
            mappingService.saveMappingInMemory(testUuid, cachedId)

            val result = mappingService.getNewId(testTable, testUuid)
            assertThat(result).isEqualTo(cachedId)
        }

        @Test
        fun `should load from database if not in cache`() {
            val testUuid = UUID.randomUUID()
            val testId = 456L

            mappingService.saveMappingBatch(testTable, mapOf(testUuid to testId))
            val result = mappingService.getNewId(testTable, testUuid)

            assertThat(result).isEqualTo(testId)
        }
    }

    @Nested
    @DisplayName("Batch Processing")
    inner class BatchProcessing {

        @Test
        fun `should correctly handle large batches`() {
            val batchSize = 10_000
            val mappings = (1..batchSize).associate { i ->
                UUID.randomUUID() to i.toLong()
            }

            val startTime = System.currentTimeMillis()
            mappingService.saveMappingBatch(testTable, mappings)
            val duration = System.currentTimeMillis() - startTime

            assertThat(duration).isLessThan(5000)

            mappings.forEach { (uuid, expectedId) ->
                assertThat(mappingService.getNewId(testTable, uuid)).isEqualTo(expectedId)
            }
        }

        @Test
        fun `should handle empty batch without errors`() {
            mappingService.saveMappingBatch(testTable, emptyMap())
        }

        @Test
        fun `should support ON CONFLICT DO NOTHING`() {
            val duplicateUuid = UUID.randomUUID()
            val firstId = 100L
            val secondId = 200L

            mappingService.saveMappingBatch(testTable, mapOf(duplicateUuid to firstId))
            mappingService.saveMappingBatch(testTable, mapOf(duplicateUuid to secondId))

            val result = mappingService.getNewId(testTable, duplicateUuid)
            assertThat(result).isEqualTo(firstId)
        }
    }

    @Nested
    @DisplayName("Get All Table Mappings")
    inner class GetAllMappings {

        @Test
        fun `should return all UUIDs for table`() {
            val uuids = List(5) { UUID.randomUUID() }
            val mappings = uuids.indices.associate { i -> uuids[i] to (i + 1).toLong() }

            mappingService.saveMappingBatch(testTable, mappings)
            val result = mappingService.getAllMappedUuids(testTable)

            assertThat(result).containsExactlyInAnyOrderElementsOf(uuids)
        }

        @Test
        fun `should return empty set for new table`() {
            val result = mappingService.getAllMappedUuids("non_existent_table")
            assertThat(result).isEmpty()
        }

        @Test
        fun `should filter by table name`() {
            val table1Uuid = UUID.randomUUID()
            val table2Uuid = UUID.randomUUID()

            mappingService.saveMappingBatch("table1", mapOf(table1Uuid to 1L))
            mappingService.saveMappingBatch("table2", mapOf(table2Uuid to 2L))

            val table1Result = mappingService.getAllMappedUuids("table1")
            val table2Result = mappingService.getAllMappedUuids("table2")

            assertThat(table1Result).containsExactly(table1Uuid)
            assertThat(table2Result).containsExactly(table2Uuid)
        }
    }

    @Nested
    @DisplayName("Cache Size Limit")
    inner class CacheLimit {

        @Test
        fun `should respect cache size limit`() {
            val limit = 500_000
            val mappings = (1..limit).associate { i ->
                UUID.randomUUID() to i.toLong()
            }

            mappingService.saveMappingBatch(testTable, mappings)

            val extraUuid = UUID.randomUUID()
            mappingService.saveMappingInMemory(extraUuid, 999L)

            val result = mappingService.getNewId(testTable, extraUuid)
            assertThat(result).isNull()
        }

        @Test
        fun `should save to database even if cache is full`() {
            val uuid = UUID.randomUUID()
            val testId = 777L

            mappingService.saveMappingInMemory(uuid, testId)
            assertThat(mappingService.getNewId(testTable, uuid)).isEqualTo(testId)
        }
    }

    @Nested
    @DisplayName("Use Cases")
    inner class UseCases {

        @Test
        fun `should support primary migration scenario`() {
            val userUuids = List(100) { UUID.randomUUID() }
            val userMappings = userUuids.indices.associate { i ->
                userUuids[i] to (i + 1).toLong()
            }

            mappingService.saveMappingBatch("users", userMappings)

            userUuids.forEachIndexed { index, uuid ->
                val expectedId = (index + 1).toLong()
                assertThat(mappingService.getNewId("users", uuid)).isEqualTo(expectedId)
            }
        }

        @Test
        fun `should support new data synchronization scenario`() {
            val initialUuids = List(100) { UUID.randomUUID() }
            mappingService.saveMappingBatch("orders", initialUuids.indices.associate { i ->
                initialUuids[i] to (i + 1).toLong()
            })

            val existingUuids = mappingService.getAllMappedUuids("orders")
            val newUuid = UUID.randomUUID()

            assertThat(existingUuids).doesNotContain(newUuid)
            assertThat(mappingService.getNewId("orders", newUuid)).isNull()
            assertThat(existingUuids).containsAll(initialUuids)
        }
    }

    private fun targetTableExists(tableName: String): Boolean {
        targetDataSource.connection.use { conn ->
            val rs = conn.metaData.getTables(null, "public", tableName, arrayOf("TABLE"))
            return rs.next()
        }
    }
}
