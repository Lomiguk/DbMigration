package cli.commands

import com.github.ajalt.mordant.terminal.Terminal
import config.MigrateCommand
import config.createSampleConfigFile
import ui.MigrationUi

/**
 * Команда: migrate config-init
 * Создание примера конфигурационного файла
 */
class ConfigInitCommand : MigrateCommand(
    "config-init",
    "Создание конфигурационного файла"
) {

    private val terminal = Terminal()
    private val ui = MigrationUi(terminal)

    override fun run() {
        ui.printSectionTitle("Создание конфигурационного файла")

        try {
            createSampleConfigFile()
            ui.printSuccess("Конфигурационный файл создан")
            ui.printInfo("Отредактируйте файл и используйте с командой: migrate --config migration-config.yaml")

        } catch (e: Exception) {
            ui.printError("Ошибка создания конфигурации: ${e.message}")
            if (verbose) {
                e.printStackTrace()
            }
            throw e
        }
    }
}
