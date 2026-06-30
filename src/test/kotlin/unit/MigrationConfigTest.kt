package unit

import config.MigrationConfig
import config.createSampleConfigFile
import engine.MappingStrategy
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path

class MigrationConfigTest {

    @Test
    fun `should build default jdbc urls`() {
        val config = MigrationConfig()

        assertThat(config.sourceJdbcUrl).isEqualTo("jdbc:postgresql://localhost:5431/source_db")
        assertThat(config.targetJdbcUrl).isEqualTo("jdbc:postgresql://localhost:5432/target_db")
    }

    @Test
    fun `should build jdbc urls from custom database settings`() {
        val config = MigrationConfig(
            sourceHost = "source.internal",
            sourcePort = 15432,
            sourceDatabase = "legacy",
            targetHost = "target.internal",
            targetPort = 25432,
            targetDatabase = "warehouse",
            mappingStrategy = MappingStrategy.HYBRID,
            batchSize = 2500,
            maxPoolSize = 20
        )

        assertThat(config.sourceJdbcUrl).isEqualTo("jdbc:postgresql://source.internal:15432/legacy")
        assertThat(config.targetJdbcUrl).isEqualTo("jdbc:postgresql://target.internal:25432/warehouse")
        assertThat(config.mappingStrategy).isEqualTo(MappingStrategy.HYBRID)
        assertThat(config.batchSize).isEqualTo(2500)
        assertThat(config.maxPoolSize).isEqualTo(20)
    }

    @Test
    fun `should create sample config file`(@TempDir tempDir: Path) {
        val configPath = tempDir.resolve("migration-config.yaml")

        createSampleConfigFile(configPath.toString())

        assertThat(configPath).exists()
        assertThat(configPath).content()
            .contains("sourceHost: localhost")
            .contains("targetDatabase: target_db")
            .contains("syncStrategy: MEMORY_FILTERED")
            .contains("adaptiveBatchSize: false")
            .contains("maxBatchSize: 4000")
            .contains("targetBatchDurationMs: 150")
            .contains("adaptiveWarmupBatches: 2")
            .contains("minAdaptiveRows: 5000")
    }
}
