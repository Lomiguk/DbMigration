package unit

import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import utils.HikariFactory
import java.sql.Connection
import java.sql.PreparedStatement
import java.sql.Statement
import java.util.UUID
import javax.sql.DataSource

class HikariFactoryTest {

    @Test
    fun `saveMappingBatch should return only inserted mappings and commit transaction`() {
        val firstUuid = UUID.randomUUID()
        val duplicateUuid = UUID.randomUUID()
        val mappings = linkedMapOf(firstUuid to 10L, duplicateUuid to 20L)
        val dataSource = mockk<DataSource>()
        val connection = mockk<Connection>()
        val statement = mockk<PreparedStatement>()

        every { dataSource.connection } returns connection
        every { connection.autoCommit = false } returns Unit
        every { connection.prepareStatement(any<String>()) } returns statement
        every { connection.commit() } returns Unit
        every { connection.close() } returns Unit
        every { statement.setString(any(), any()) } returns Unit
        every { statement.setObject(any(), any()) } returns Unit
        every { statement.setLong(any(), any()) } returns Unit
        every { statement.addBatch() } returns Unit
        every { statement.executeBatch() } returns intArrayOf(1, 0)
        every { statement.close() } returns Unit

        val inserted = HikariFactory.saveMappingBatch(dataSource, "users", mappings)

        assertThat(inserted).containsExactlyEntriesOf(mapOf(firstUuid to 10L))
        verify { connection.autoCommit = false }
        verify { connection.commit() }
        verify { connection.close() }
    }

    @Test
    fun `saveMappingBatchInConnection should treat success without info as inserted`() {
        val firstUuid = UUID.randomUUID()
        val secondUuid = UUID.randomUUID()
        val mappings = linkedMapOf(firstUuid to 10L, secondUuid to 20L)
        val connection = mockk<Connection>()
        val statement = mockk<PreparedStatement>()

        every { connection.prepareStatement(any<String>()) } returns statement
        every { statement.setString(any(), any()) } returns Unit
        every { statement.setObject(any(), any()) } returns Unit
        every { statement.setLong(any(), any()) } returns Unit
        every { statement.addBatch() } returns Unit
        every { statement.executeBatch() } returns intArrayOf(Statement.SUCCESS_NO_INFO, 0)
        every { statement.close() } returns Unit

        val inserted = HikariFactory.saveMappingBatchInConnection(connection, "users", mappings)

        assertThat(inserted).containsExactlyEntriesOf(mapOf(firstUuid to 10L))
    }
}
