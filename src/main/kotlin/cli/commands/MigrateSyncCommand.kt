package cli.commands

import com.github.ajalt.mordant.terminal.Terminal
import com.zaxxer.hikari.HikariDataSource
import config.MigrateCommand
import core.DependencyResolver
import core.MetadataReader
import engine.DataMigrator
import engine.MappingServiceFactory
import engine.MappingStrategy
import logging.MetricsService
import sync.ChangeCapture
import ui.MigrationUi
import utils.HikariFactory
import kotlin.system.measureTimeMillis

/**
 * Команда: migrate sync
 * Запуск механизма инкрементальной синхронизации (delta sync)
 */
class MigrateSyncCommand : MigrateCommand(
    "sync",
    "Инкрементальная синхронизация новых данных"
) {

    private val terminal = Terminal()
    private val ui = MigrationUi(terminal)

    override fun run() {
        val config = buildConfig()
        ui.printSectionTitle("Инкрементальная синхронизация")

        var sourceDs: HikariDataSource? = null
        var targetDs: HikariDataSource? = null

        try {
            // Подключение к базам данных
            ui.printInfo("Source: ${config.sourceJdbcUrl}")
            ui.printInfo("Target: ${config.targetJdbcUrl}")

            sourceDs = HikariFactory.createDataSource(config.sourceJdbcUrl, config.sourceUser, config.sourcePassword, config.maxPoolSize)
            targetDs = HikariFactory.createDataSource(config.targetJdbcUrl, config.targetUser, config.targetPassword, config.maxPoolSize)

            // Получение порядка таблиц
            val reader = MetadataReader(sourceDs)
            val tables = reader.getAllTablesWithUuidPk()
            val relations = reader.getForeignKeys()

            val resolver = DependencyResolver()
            resolver.buildGraph(tables, relations)
            val migrationOrder = resolver.getMigrationOrder()

            // Получение количества уже мигрированных записей
            ui.printInfo("Анализ текущего состояния...")
            val mappingService = MappingServiceFactory.create(targetDs, MappingStrategy.EAGER, 10_000_000)
            ui.printInfo("Предзагрузка маппингов для синхронизации...")
            mappingService.preloadAllMappings(tables)
            MetricsService.registerCacheMetrics(mappingService)
            val migrator = DataMigrator(sourceDs, targetDs, mappingService, reader)
            val syncEngine = ChangeCapture(migrator, mappingService)

            var totalNewRows = 0L
            var totalSkippedRows = 0L
            var totalDuration = 0L

            migrationOrder.forEach { table ->
                val existingIds = mappingService.getAllMappedUuids(table)
                val skippedBefore = existingIds.size

                val duration = measureTimeMillis {
                    syncEngine.syncUpdates(listOf(table))
                }

                val newCount = mappingService.getAllMappedUuids(table).size
                val newRows = newCount - skippedBefore

                totalNewRows += newRows
                totalSkippedRows += skippedBefore
                totalDuration += duration

                ui.printSyncStatus(table, newRows.toLong(), skippedBefore.toLong(), duration)
            }

            // Итоговая сводка
            ui.printSectionTitle("Результаты синхронизации")
            ui.printSuccess("Новых записей: $totalNewRows")
            ui.printInfo("Пропущено записей: $totalSkippedRows")
            ui.printInfo("Общее время: ${totalDuration}ms")

            val avgSpeed = if (totalDuration > 0) {
                (totalNewRows * 1000.0 / totalDuration).toInt()
            } else 0

            ui.printInfo("Скорость: $avgSpeed rec/sec")

        } catch (e: Exception) {
            ui.printError("Ошибка синхронизации: ${e.message}")
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
