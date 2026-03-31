package cli.commands

import com.github.ajalt.mordant.terminal.Terminal
import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import config.MigrateCommand
import core.MetadataReader
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
            ui.printInfo("Source: ${config.sourceJdbcUrl}")
            ui.printInfo("Target: ${config.targetJdbcUrl}")

            sourceDs = createDataSource(config.sourceJdbcUrl, config.sourceUser, config.sourcePassword, config.maxPoolSize)
            targetDs = createDataSource(config.targetJdbcUrl, config.targetUser, config.targetPassword, config.maxPoolSize)

            // Получение списка таблиц
            val reader = MetadataReader(sourceDs)
            val tables = reader.getAllTablesWithUuidPk()

            ui.printInfo("Таблиц для валидации: ${tables.size}")
            ui.printSectionTitle("Запуск валидации")

            val validator = DataIntegrityValidator(sourceDs, targetDs)
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
                    if (result.missingMappings.isNotEmpty()) {
                        terminal.println("  Missing mappings: ${result.missingMappings.size}")
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
