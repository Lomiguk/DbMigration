package unit

import config.MigrateCommand
import config.MigrationConfig
import engine.MappingStrategy
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class MigrateCommandConfigTest {

    @Test
    fun `should keep cli cache limit in built config`() {
        val command = ConfigProbeCommand()

        command.main(
            arrayOf(
                "--mapping-strategy=LAZY",
                "--cache-limit",
                "100000",
                "--adaptive-batch-size",
                "--min-batch-size",
                "500",
                "--max-batch-size",
                "8000",
                "--target-batch-duration-ms",
                "1500",
                "--adaptive-warmup-batches",
                "4",
                "--min-adaptive-rows",
                "12000"
            )
        )

        assertThat(command.config.cacheLimit).isEqualTo(100_000)
        assertThat(command.config.mappingStrategy).isEqualTo(MappingStrategy.LAZY)
        assertThat(command.config.adaptiveBatchSize).isTrue()
        assertThat(command.config.minBatchSize).isEqualTo(500)
        assertThat(command.config.maxBatchSize).isEqualTo(8_000)
        assertThat(command.config.targetBatchDurationMs).isEqualTo(1_500)
        assertThat(command.config.adaptiveWarmupBatches).isEqualTo(4)
        assertThat(command.config.minAdaptiveRows).isEqualTo(12_000)
    }

    private class ConfigProbeCommand : MigrateCommand("probe", "Probe config parsing") {
        lateinit var config: MigrationConfig

        override fun run() {
            config = buildConfig()
        }
    }
}
