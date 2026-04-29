package unit

import core.ForeignKeyColumn
import core.MetadataReader
import engine.DataMigrator
import engine.MappingServiceBase
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Test
import java.sql.Connection
import java.sql.Statement
import javax.sql.DataSource

class DataMigratorSchemaTest {

    @Test
    fun `createTargetSchema should convert uuid columns to bigint and id to bigserial`() {
        val sourceDataSource = mockk<DataSource>()
        val targetDataSource = mockk<DataSource>()
        val targetConnection = mockk<Connection>()
        val statement = mockk<Statement>()
        val mappingService = mockk<MappingServiceBase>()
        val metadataReader = mockk<MetadataReader>()

        every { targetDataSource.connection } returns targetConnection
        every { targetConnection.close() } returns Unit
        every { targetConnection.createStatement() } returns statement
        every { statement.execute(any<String>()) } returns true
        every { metadataReader.getTableColumns("orders") } returns linkedMapOf(
            "id" to "uuid",
            "user_id" to "uuid",
            "total_amount" to "numeric",
            "status" to "varchar"
        )

        val migrator = DataMigrator(sourceDataSource, targetDataSource, mappingService, metadataReader)

        migrator.createTargetSchema(listOf("orders"))

        verify {
            statement.execute(
                "CREATE TABLE IF NOT EXISTS orders (id BIGSERIAL PRIMARY KEY, user_id BIGINT, total_amount numeric, status varchar)"
            )
        }
        verify { targetConnection.close() }
    }

    @Test
    fun `createForeignKeyIndexes should create indexes for every foreign key`() {
        val sourceDataSource = mockk<DataSource>()
        val targetDataSource = mockk<DataSource>()
        val targetConnection = mockk<Connection>()
        val statement = mockk<Statement>()
        val mappingService = mockk<MappingServiceBase>()
        val metadataReader = mockk<MetadataReader>()

        every { targetDataSource.connection } returns targetConnection
        every { targetConnection.close() } returns Unit
        every { targetConnection.createStatement() } returns statement
        every { statement.execute(any<String>()) } returns true
        every { metadataReader.getForeignKeysForTable("orders") } returns listOf(
            ForeignKeyColumn("user_id", "users", "id"),
            ForeignKeyColumn("coupon_id", "discount_coupons", "id")
        )

        val migrator = DataMigrator(sourceDataSource, targetDataSource, mappingService, metadataReader)

        migrator.createForeignKeyIndexes(listOf("orders"))

        verify { statement.execute("CREATE INDEX IF NOT EXISTS idx_orders_user_id ON orders (user_id)") }
        verify { statement.execute("CREATE INDEX IF NOT EXISTS idx_orders_coupon_id ON orders (coupon_id)") }
        verify { targetConnection.close() }
    }
}
