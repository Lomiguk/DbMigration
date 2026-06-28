package cli.commands

import com.github.ajalt.mordant.terminal.Terminal
import com.zaxxer.hikari.HikariDataSource
import config.MigrateCommand
import core.MetadataReader
import logging.MetricsService
import ui.MigrationUi
import validation.DataIntegrityValidator

/**
 * Команда: migrate validate
 * Валидация целостности данных после миграции
 */
class MigrateValidateCommand : MigrateCommand(
    "validate",
    "Валидация целостности данных после миграции"
) {

    private val terminal = Terminal()
    private val ui = MigrationUi(terminal)

    override fun run() {
        val config = buildConfig()
        ui.printSectionTitle("Валидация целостности данных")

        var sourceDs: HikariDataSource? = null
        var targetDs: HikariDataSource? = null

        try {
            val (source, target) = createDataSourcesWithLog(config, ui)
            sourceDs = source
            targetDs = target

            // Получение списка таблиц
            val reader = MetadataReader(source)
            val tables = reader.getAllTablesWithUuidPk()

            ui.printInfo("Таблиц для валидации: ${tables.size}")
            ui.printSectionTitle("Запуск валидации")

            val validator = DataIntegrityValidator(source, target)
            val results = validator.validateAllTables(tables)

            // Вывод результатов
            val validCount = results.count { it.isValid }
            val invalidCount = results.size - validCount

            ui.printSectionTitle("Результаты валидации")
            
            results.forEach { result ->
                if (result.isValid) {
                    ui.printSuccess("${result.tableName}: ✓ VALID (${result.sourceCount} rows)")
                } else {
                    ui.printError("${result.tableName}: ✗ INVALID")
                    if (!result.countMatch) {
                        terminal.println("  Counts: ${result.sourceCount} (source) vs ${result.targetCount} (target)")
                    }
                    if (result.fkViolations.isNotEmpty()) {
                        terminal.println("  FK violations: ${result.fkViolations.size}")
                    }
                    if (result.missingMappings > 0) {
                        terminal.println("  Missing mappings: ${result.missingMappings}")
                    }
                    if (result.errorMessage != null) {
                        terminal.println("  Error: ${result.errorMessage}")
                    }
                }
            }

            // Итоговая сводка
            ui.printSectionTitle("Итого")
            if (invalidCount == 0) {
                ui.printSuccess("Все таблицы прошли валидацию!")
                ui.printSuccess("Valid: $validCount/${tables.size}")
            } else {
                ui.printError("Обнаружены проблемы в $invalidCount таблицах")
                ui.printInfo("Valid: $validCount/${tables.size}")
                throw RuntimeException("Data integrity validation failed")
            }

        } catch (e: Exception) {
            ui.printError("Ошибка валидации: ${e.message}")
            if (config.verbose) {
                e.printStackTrace()
            }
            throw e
        } finally {
            MetricsService.pushMetrics()

            sourceDs?.close()
            targetDs?.close()
        }
    }
}
