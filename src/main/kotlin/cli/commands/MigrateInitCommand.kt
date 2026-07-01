package cli.commands

import com.github.ajalt.mordant.terminal.Terminal
import com.zaxxer.hikari.HikariDataSource
import config.MigrateCommand
import core.MetadataReader
import core.MigrationScopePlanner
import ui.MigrationUi
import utils.HikariFactory

/**
 * Команда: migrate init
 * Анализ графа зависимостей и создание пустой схемы target_db
 */
class MigrateInitCommand : MigrateCommand(
    "init",
    "Анализ схемы и создание графа зависимостей"
) {

    private val terminal = Terminal()
    private val ui = MigrationUi(terminal)

    override fun run() {
        val config = buildConfig()
        ui.printSectionTitle("Инициализация миграции")

        var sourceDs: HikariDataSource? = null

        try {
            // Подключение к source database
            ui.printInfo("Подключение к source database: ${config.sourceJdbcUrl}")
            sourceDs = HikariFactory.createDataSource(config.sourceJdbcUrl, config.sourceUser, config.sourcePassword, config.maxPoolSize)

            // Анализ метаданных
            ui.printInfo("Чтение метаданных схемы...")
            val reader = MetadataReader(sourceDs)
            val scope = MigrationScopePlanner.analyze(reader)
            MigrationScopeReporter.report(scope, ui, terminal)
            ui.printSuccess("Найдено внешних ключей: ${scope.relations.size}")

            // Построение графа зависимостей
            ui.printInfo("Построение графа зависимостей...")
            val migrationOrder = scope.migrationOrder

            // Вывод порядка миграции
            ui.printSectionTitle("Порядок миграции таблиц")
            migrationOrder.forEachIndexed { index, table ->
                terminal.println("${(index + 1).toString().padStart(2)}. $table")
            }

            ui.printSuccess("\nГраф зависимостей построен корректно!")
            ui.printInfo("Используйте 'migrate copy' для начала переноса данных")

        } catch (e: Exception) {
            ui.printError("Ошибка инициализации: ${e.message}")
            if (config.verbose) {
                e.printStackTrace()
            }
            throw e
        } finally {
            logging.MetricsService.pushMetrics()

            sourceDs?.close()
        }
    }
}
