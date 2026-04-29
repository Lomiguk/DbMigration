package unit

import engine.DataMigrator
import engine.MappingServiceBase
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Test
import sync.ChangeCapture
import java.util.UUID

class ChangeCaptureUnitTest {

    @Test
    fun `syncUpdates should pass already mapped ids to table migration`() {
        val migrator = mockk<DataMigrator>(relaxed = true)
        val mappingService = mockk<MappingServiceBase>()
        val userId = UUID.randomUUID()
        val orderId = UUID.randomUUID()

        every { mappingService.getAllMappedUuids("users") } returns setOf(userId)
        every { mappingService.getAllMappedUuids("orders") } returns setOf(orderId)

        ChangeCapture(migrator, mappingService).syncUpdates(listOf("users", "orders"))

        verify { mappingService.getAllMappedUuids("users") }
        verify { mappingService.getAllMappedUuids("orders") }
        verify { migrator.migrateTable("users", setOf(userId)) }
        verify { migrator.migrateTable("orders", setOf(orderId)) }
    }
}
