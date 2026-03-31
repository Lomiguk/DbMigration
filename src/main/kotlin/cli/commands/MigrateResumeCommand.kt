package cli.commands

import com.github.ajalt.mordant.terminal.Terminal
import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import config.MigrateCommand
import core.DependencyResolver
import core.MetadataReader
import engine.DataMigrator
import engine.MappingService
import state.StateRepository
import state.MigrationStateManager
import ui.MigrationUi

/**
 * Команда: migrate resume
 * Возобновление прерванной миграции
 */
class MigrateResumeCommand : MigrateCommand(
    "resume",
    "Возобновление прерванной миграции"
) {

    private val terminal = Terminal()
    private val ui = MigrationUi(terminal)

    override fun run() {
        val config = buildConfig()
        ui.printSectionTitle("Возобновление миграции")

        var sourceDs: HikariDataSource? = null
        var targetDs: HikariDataSource? = null

        try {
            // Подключение к базам данных
            ui.printInfo("Source: ${config.sourceJdbcUrl}")
            ui.printInfo("Target: ${config.targetJdbcUrl}")

            sourceDs = createDataSource(config.sourceJdbcUrl, config.sourceUser, config.sourcePassword, config.maxPoolSize)
            targetDs = createDataSource(config.targetJdbcUrl, config.targetUser, config.targetPassword, config.maxPoolSize)

            // Инициализация репозитория состояния
            val stateRepository = StateRepository(targetDs)

            // Проверка возможности возобновления
            if (stateRepository.getLastActiveMigration() == null) {
                ui.printWarning("Нет активной миграции для возобновления")
                ui.printInfo("Используйте 'migrate copy' для начала новой миграции")
                return
            }

            // Получение контекста возобновления
            val resumeContext = stateRepository.getResumeContext(stateRepository.getLastActiveMigration()!!)
            val migrationId = resumeContext.migrationId

            ui.printInfo("Найдена миграция: $migrationId")
            ui.printInfo("Завершённые таблицы: ${resumeContext.completedTables.size}")
            ui.printInfo("Ожидающие таблицы: ${resumeContext.pendingTables.size}")
            ui.printInfo("Неудачные таблицы: ${resumeContext.failedTables.size}")

            if (resumeContext.pendingTables.isEmpty() && resumeContext.failedTables.isEmpty()) {
                ui.printSuccess("Все таблицы уже мигрированы!")
                return
            }

            // Получение порядка таблиц
            val reader = MetadataReader(sourceDs)
            val tables = reader.getAllTablesWithUuidPk()
            val relations = reader.getForeignKeys()

            val resolver = DependencyResolver()
            resolver.buildGraph(tables, relations)
            val migrationOrder = resolver.getMigrationOrder()

            // Фильтрация только тех таблиц, которые нужно мигрировать
            val tablesToMigrate = migrationOrder.filter { table ->
                table in resumeContext.pendingTables || table in resumeContext.failedTables
            }

            ui.printSectionTitle("Порядок миграции")
            tablesToMigrate.forEachIndexed { index, table ->
                val status = when {
                    table in resumeContext.failedTables -> " (FAILED - retry)"
                    else -> ""
                }
                terminal.println("${(index + 1).toString().padStart(2)}. $table$status")
            }

            // Запуск миграции
            ui.printSectionTitle("Возобновление миграции")
            val mappingService = MappingService(targetDs)
            val migrator = DataMigrator(sourceDs, targetDs, mappingService, reader, stateRepository, migrationId)

            var totalRows = 0L
            var totalDuration = 0L

            tablesToMigrate.forEach { table ->
                val startTime = System.currentTimeMillis()
                ui.printInfo("Миграция таблицы: $table")

                migrator.migrateTable(table)

                val duration = System.currentTimeMillis() - startTime
                val rowCount = getRowCount(targetDs, table)
                totalRows += rowCount
                totalDuration += duration

                ui.printMigrationStats(table, rowCount, duration, rowCount * 1000.0 / duration)
            }

            // Итоговая сводка
            ui.printSummary(
                totalTables = tablesToMigrate.size,
                totalRows = totalRows,
                totalDuration = totalDuration,
                success = true
            )

        } catch (e: Exception) {
            ui.printError("Ошибка возобновления: ${e.message}")
            if (config.verbose) {
                e.printStackTrace()
            }
            throw e
        } finally {
            sourceDs?.close()
            targetDs?.close()
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

    private fun getRowCount(ds: HikariDataSource, tableName: String): Long {
        ds.connection.use { conn ->
            val rs = conn.createStatement().executeQuery("SELECT COUNT(*) FROM $tableName")
            return if (rs.next()) rs.getLong(1) else 0L
        }
    }
}
