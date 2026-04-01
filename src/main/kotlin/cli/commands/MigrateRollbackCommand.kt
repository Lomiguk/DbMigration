package cli.commands

import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.mordant.terminal.Terminal
import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import config.MigrateCommand
import core.MetadataReader
import engine.DataMigrator
import engine.MappingServiceFactory
import engine.MappingStrategy
import rollback.RollbackService
import state.StateRepository
import ui.MigrationUi

/**
 * Команда: migrate rollback
 * Откат миграции (полный или для отдельной таблицы)
 */
class MigrateRollbackCommand : MigrateCommand(
    "rollback",
    "Откат миграции (полный или для отдельной таблицы)"
) {

    private val terminal = Terminal()
    private val ui = MigrationUi(terminal)

    private val table by option("--table", "-t", help = "Имя таблицы для отката")
    private val toTable by option("--to-table", help = "Откатиться к указанной таблице (все после неё)")
    private val full by option("--full", "-f", help = "Полный откат всей миграции")
        .flag()
    private val migrationId by option("--migration-id", help = "ID миграции для отката")
    private val validate by option("--validate", help = "Валидация после отката")
        .flag()

    override fun run() {
        val config = buildConfig()
        ui.printSectionTitle("Откат миграции (Rollback)")

        // Валидация входных параметров
        if (!full && table.isNullOrEmpty() && toTable.isNullOrEmpty()) {
            ui.printError("Укажите --table, --to-table или --full")
            ui.printInfo("Используйте 'migrate rollback --help' для справки")
            return
        }

        var sourceDs: HikariDataSource? = null
        var targetDs: HikariDataSource? = null

        try {
            ui.printInfo("Source: ${config.sourceJdbcUrl}")
            ui.printInfo("Target: ${config.targetJdbcUrl}")

            sourceDs = createDataSource(config.sourceJdbcUrl, config.sourceUser, config.sourcePassword, config.maxPoolSize)
            targetDs = createDataSource(config.targetJdbcUrl, config.targetUser, config.targetPassword, config.maxPoolSize)

            // Инициализация сервисов
            val stateRepository = StateRepository(targetDs)
            val mappingService = MappingServiceFactory.create(targetDs, MappingStrategy.EAGER, 10_000_000)
            val metadataReader = MetadataReader(sourceDs)

            // Определение migration ID
            val actualMigrationId = migrationId ?: stateRepository.getLastActiveMigration()
            if (actualMigrationId == null) {
                ui.printError("Нет активной миграции для отката")
                return
            }

            ui.printInfo("Migration ID: $actualMigrationId")

            // Получение списка мигрированных таблиц
            val completedTables = stateRepository.getCompletedTables(actualMigrationId)
            ui.printInfo("Мигрированные таблицы: ${completedTables.size}")

            if (completedTables.isEmpty()) {
                ui.printWarning("Нет мигрированных таблиц для отката")
                return
            }

            // Инициализация rollback сервиса
            val rollbackService = RollbackService(sourceDs, targetDs, mappingService, stateRepository, metadataReader)

            val results = when {
                full -> {
                    ui.printSectionTitle("Полный откат всех таблиц")
                    rollbackService.rollbackAll(actualMigrationId)
                }
                !toTable.isNullOrEmpty() -> {
                    ui.printSectionTitle("Откат к таблице: $toTable")
                    rollbackService.rollbackToTable(actualMigrationId, toTable!!)
                }
                !table.isNullOrEmpty() -> {
                    ui.printSectionTitle("Откат таблицы: $table")
                    listOf(rollbackService.rollbackTable(actualMigrationId, table!!))
                }
                else -> {
                    ui.printError("Не указан режим отката")
                    return
                }
            }

            // Вывод результатов
            ui.printSectionTitle("Результаты отката")

            val successCount = results.count { it.success }
            val failedCount = results.size - successCount
            val totalRows = results.sumOf { it.rowsRolledBack }
            val totalMapping = results.sumOf { it.mappingEntriesRemoved }
            val totalDuration = results.sumOf { it.duration }

            results.forEach { result ->
                if (result.success) {
                    ui.printSuccess("${result.tableName}: ✓ откатано (${result.rowsRolledBack} строк, ${result.mappingEntriesRemoved} mapping)")
                } else {
                    ui.printError("${result.tableName}: ✗ ошибка - ${result.errorMessage}")
                }
            }

            ui.printSectionTitle("Итого")
            ui.printInfo("Успешно: $successCount/${results.size}")
            ui.printInfo("Удалено строк: $totalRows")
            ui.printInfo("Удалено mapping: $totalMapping")
            ui.printInfo("Время: ${totalDuration}ms")

            // Валидация
            if (validate) {
                ui.printSectionTitle("Валидация")
                results.filter { it.success }.forEach { result ->
                    val validation = rollbackService.validateRollback(result.tableName)
                    if (validation.isValid) {
                        ui.printSuccess("${result.tableName}: ✓ валидация пройдена")
                    } else {
                        validation.issues.forEach { issue ->
                            ui.printWarning("${result.tableName}: $issue")
                        }
                    }
                }
            }

            if (failedCount > 0) {
                ui.printError("Откат завершён с ошибками: $failedCount таблиц")
                throw RuntimeException("Rollback failed for $failedCount tables")
            } else {
                ui.printSuccess("Откат завершён успешно!")
            }

        } catch (e: Exception) {
            ui.printError("Ошибка отката: ${e.message}")
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
}
