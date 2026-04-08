package cli.commands

import com.github.ajalt.mordant.terminal.Terminal
import com.zaxxer.hikari.HikariDataSource
import config.MigrateCommand
import core.MetadataReader
import engine.MappingService
import logging.MetricsService
import ui.MigrationUi
import utils.HikariFactory

/**
 * Команда: migrate status
 * Вывод метрик и статуса миграции
 */
class MigrateStatusCommand : MigrateCommand(
    "status",
    "Вывод статуса и метрик миграции"
) {

    private val terminal = Terminal()
    private val ui = MigrationUi(terminal)

    override fun run() {
        val config = buildConfig()
        ui.printSectionTitle("Статус миграции")

        var sourceDs: HikariDataSource? = null
        var targetDs: HikariDataSource? = null

        try {
            // Подключение к базам данных
            ui.printInfo("Source: ${config.sourceJdbcUrl}")
            ui.printInfo("Target: ${config.targetJdbcUrl}")

            sourceDs = HikariFactory.createDataSource(config.sourceJdbcUrl, config.sourceUser, config.sourcePassword, config.maxPoolSize)
            targetDs = HikariFactory.createDataSource(config.targetJdbcUrl, config.targetUser, config.targetPassword, config.maxPoolSize)

            // Получение информации о source database
            ui.printSectionTitle("Source Database (UUID)")
            val sourceReader = MetadataReader(sourceDs)
            val sourceTables = sourceReader.getAllTablesWithUuidPk()

            sourceTables.forEach { table ->
                val rowCount = getRowCount(sourceDs, table)
                val indexSize = getIndexSize(sourceDs, table)
                terminal.println("  $table: ${rowCount} rows, ${indexSize} index")
            }

            // Получение информации о target database
            ui.printSectionTitle("Target Database (BIGINT)")
            val targetReader = MetadataReader(targetDs)
            val targetTables = targetReader.getAllTablesWithUuidPk()

            if (targetTables.isEmpty()) {
                ui.printWarning("Целевая схема ещё не создана")
            } else {
                targetTables.forEach { table ->
                    val rowCount = getRowCount(targetDs, table)
                    val indexSize = getIndexSize(targetDs, table)
                    terminal.println("  $table: ${rowCount} rows, ${indexSize} index")
                }
            }

            // Статус маппинга
            ui.printSectionTitle("Статус маппинга UUID → BIGINT")
            val mappingService = MappingService(targetDs)

            var totalMapped = 0L
            sourceTables.forEach { table ->
                val mappedCount = mappingService.getAllMappedUuids(table).size.toLong()
                totalMapped += mappedCount
                terminal.println("  $table: ${mappedCount} замапплено")
            }

            ui.printSectionTitle("Итого")
            ui.printSuccess("Всего таблиц в source: ${sourceTables.size}")
            ui.printSuccess("Всего таблиц в target: ${targetTables.size}")
            ui.printSuccess("Всего замапплено записей: ${totalMapped}")

            // Прогресс миграции
            if (sourceTables.isNotEmpty()) {
                val progress = (targetTables.size.toDouble() / sourceTables.size * 100).toInt()
                ui.printInfo("Прогресс миграции схем: ${progress}%")
            }

        } catch (e: Exception) {
            ui.printError("Ошибка получения статуса: ${e.message}")
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

    private fun getRowCount(ds: HikariDataSource, tableName: String): Long {
        ds.connection.use { conn ->
            val rs = conn.createStatement().executeQuery("SELECT COUNT(*) FROM $tableName")
            return if (rs.next()) rs.getLong(1) else 0L
        }
    }

    private fun getIndexSize(ds: HikariDataSource, tableName: String): String {
        ds.connection.use { conn ->
            try {
                val rs = conn.createStatement().executeQuery("""
                    SELECT pg_size_pretty(pg_relation_size('$tableName')) as size
                """)
                return if (rs.next()) rs.getString("size") else "N/A"
            } catch (e: Exception) {
                return "N/A"
            }
        }
    }
}
