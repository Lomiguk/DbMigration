package cli.commands

import com.github.ajalt.mordant.terminal.Terminal
import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import config.MigrateCommand
import core.DependencyResolver
import core.MetadataReader
import ui.MigrationUi

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
            sourceDs = createDataSource(config.sourceJdbcUrl, config.sourceUser, config.sourcePassword, config.maxPoolSize)

            // Анализ метаданных
            ui.printInfo("Чтение метаданных схемы...")
            val reader = MetadataReader(sourceDs)

            val tables = reader.getAllTablesWithUuidPk()
            val relations = reader.getForeignKeys()

            ui.printSuccess("Найдено таблиц с UUID PK: ${tables.size}")
            ui.printSuccess("Найдено внешних ключей: ${relations.size}")

            // Построение графа зависимостей
            ui.printInfo("Построение графа зависимостей...")
            val resolver = DependencyResolver()
            resolver.buildGraph(tables, relations)

            val migrationOrder = resolver.getMigrationOrder()

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
            sourceDs?.close()
        }
    }

    private fun createDataSource(jdbcUrl: String, user: String, password: String, maxPoolSize: Int): HikariDataSource {
        return HikariDataSource(HikariConfig().apply {
            this.jdbcUrl = jdbcUrl
            this.username = user
            this.password = password
            this.maximumPoolSize = maxPoolSize
            this.minimumIdle = 2
            this.connectionTimeout = 30000
            this.validationTimeout = 5000
        })
    }
}
