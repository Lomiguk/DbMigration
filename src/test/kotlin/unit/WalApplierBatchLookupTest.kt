package unit

import core.ForeignKeyColumn
import core.MetadataReader
import engine.MappingServiceBase
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.runs
import io.mockk.verify
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import replication.WalApplier
import replication.WalDeleteEvent
import replication.WalInsertEvent
import java.sql.Connection
import java.sql.PreparedStatement
import java.sql.ResultSet
import java.time.LocalDateTime
import java.util.UUID
import javax.sql.DataSource

class WalApplierBatchLookupTest {

    @Test
    fun `insert batch should resolve foreign keys with one bulk mapping lookup`() {
        val userUuid = UUID.randomUUID()
        val profileUuid1 = UUID.randomUUID()
        val profileUuid2 = UUID.randomUUID()
        val metadataReader = mockk<MetadataReader>()
        val mappingService = mockk<MappingServiceBase>()
        val targetDataSource = mockk<DataSource>()
        val conn = mockConnection()
        val pstmt = mockPreparedStatement()
        val keys = mockk<ResultSet>()

        every { metadataReader.getAllTablesWithUuidPk() } returns listOf("profiles")
        every { metadataReader.getForeignKeysForTable("profiles") } returns listOf(
            ForeignKeyColumn("user_id", "users", "id")
        )
        every { targetDataSource.connection } returns conn
        every {
            conn.prepareStatement(
                "INSERT INTO profiles (user_id, bio) VALUES (?, ?)",
                PreparedStatement.RETURN_GENERATED_KEYS
            )
        } returns pstmt
        every { pstmt.generatedKeys } returns keys
        every { keys.next() } returnsMany listOf(true, true, false)
        every { keys.getLong(1) } returnsMany listOf(101L, 102L)
        every { mappingService.getNewIds("users", any()) } returns mapOf(userUuid to 10L)
        every { mappingService.saveMappingBatch("profiles", any(), conn) } just runs

        val applier = WalApplier(targetDataSource, mappingService, metadataReader)
        val results = applier.applyBatch(
            listOf(
                WalInsertEvent(
                    tableName = "public.profiles",
                    commitLsn = "0/1",
                    timestamp = LocalDateTime.now(),
                    newTuple = linkedMapOf("id" to profileUuid1, "user_id" to userUuid, "bio" to "one")
                ),
                WalInsertEvent(
                    tableName = "public.profiles",
                    commitLsn = "0/2",
                    timestamp = LocalDateTime.now(),
                    newTuple = linkedMapOf("id" to profileUuid2, "user_id" to userUuid, "bio" to "two")
                )
            )
        )

        assertThat(results).hasSize(2)
        assertThat(results).allMatch { it.success }
        verify(exactly = 1) {
            mappingService.getNewIds("users", match<Collection<UUID>> { it.toSet() == setOf(userUuid) })
        }
        verify(exactly = 0) { mappingService.getNewId(any(), any()) }
    }

    @Test
    fun `delete batch should resolve target ids with one bulk mapping lookup`() {
        val userUuid1 = UUID.randomUUID()
        val userUuid2 = UUID.randomUUID()
        val metadataReader = mockk<MetadataReader>()
        val mappingService = mockk<MappingServiceBase>()
        val targetDataSource = mockk<DataSource>()
        val conn = mockConnection()
        val pstmt = mockPreparedStatement()

        every { metadataReader.getAllTablesWithUuidPk() } returns listOf("users")
        every { metadataReader.getForeignKeysForTable("users") } returns emptyList()
        every { targetDataSource.connection } returns conn
        every { conn.prepareStatement("DELETE FROM users WHERE id = ?") } returns pstmt
        every { mappingService.getNewIds("users", any()) } returns mapOf(
            userUuid1 to 101L,
            userUuid2 to 102L
        )

        val applier = WalApplier(targetDataSource, mappingService, metadataReader)
        val results = applier.applyBatch(
            listOf(
                WalDeleteEvent(
                    tableName = "public.users",
                    commitLsn = "0/1",
                    timestamp = LocalDateTime.now(),
                    oldTuple = mapOf("id" to userUuid1)
                ),
                WalDeleteEvent(
                    tableName = "public.users",
                    commitLsn = "0/2",
                    timestamp = LocalDateTime.now(),
                    oldTuple = mapOf("id" to userUuid2)
                )
            )
        )

        assertThat(results).hasSize(2)
        assertThat(results).allMatch { it.success }
        verify(exactly = 1) {
            mappingService.getNewIds("users", match<Collection<UUID>> { it.toSet() == setOf(userUuid1, userUuid2) })
        }
        verify(exactly = 0) { mappingService.getNewId(any(), any()) }
    }

    private fun mockConnection(): Connection {
        val conn = mockk<Connection>()
        every { conn.autoCommit } returns true
        every { conn.autoCommit = any() } just runs
        every { conn.commit() } just runs
        every { conn.rollback() } just runs
        every { conn.close() } just runs
        return conn
    }

    private fun mockPreparedStatement(): PreparedStatement {
        val pstmt = mockk<PreparedStatement>()
        every { pstmt.setObject(any<Int>(), any()) } just runs
        every { pstmt.setLong(any(), any()) } just runs
        every { pstmt.addBatch() } just runs
        every { pstmt.executeBatch() } returns intArrayOf(1, 1)
        every { pstmt.close() } just runs
        return pstmt
    }
}
