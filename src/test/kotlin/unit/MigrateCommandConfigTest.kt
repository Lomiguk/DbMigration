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

        command.main(arrayOf("--mapping-strategy=LAZY", "--cache-limit", "100000"))

        assertThat(command.config.cacheLimit).isEqualTo(100_000)
        assertThat(command.config.mappingStrategy).isEqualTo(MappingStrategy.LAZY)
    }

    private class ConfigProbeCommand : MigrateCommand("probe", "Probe config parsing") {
        lateinit var config: MigrationConfig

        override fun run() {
            config = buildConfig()
        }
    }
}
