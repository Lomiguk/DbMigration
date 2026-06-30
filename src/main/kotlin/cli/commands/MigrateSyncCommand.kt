package cli.commands

import com.github.ajalt.mordant.terminal.Terminal
import com.zaxxer.hikari.HikariDataSource
import config.MigrateCommand
import core.DependencyResolver
import core.MetadataReader
import engine.DataMigrator
import engine.HybridTableSelector
import engine.MappingServiceFactory
import engine.MappingStrategy
import logging.MetricsService
import sync.ChangeCapture
import ui.MigrationUi
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
            val (source, target) = createDataSourcesWithLog(config, ui)
            sourceDs = source
            targetDs = target

            // Получение порядка таблиц
            val reader = MetadataReader(source)
            val tables = reader.getAllTablesWithUuidPk()
            val relations = reader.getForeignKeys()

            val resolver = DependencyResolver()
            resolver.buildGraph(tables, relations)
            val migrationOrder = resolver.getMigrationOrder()

            // Получение количества уже мигрированных записей
            ui.printInfo("Анализ текущего состояния...")
            val mappingService = MappingServiceFactory.create(target, config.mappingStrategy, config.cacheLimit)
            if (config.mappingStrategy == MappingStrategy.HYBRID) {
                val pinnedTables = HybridTableSelector.selectPinnedTables(source, migrationOrder, config.cacheLimit)
                mappingService.configurePinnedTables(pinnedTables)
                ui.printInfo("HYBRID pinned tables: ${pinnedTables.joinToString(", ").ifBlank { "none" }}")
            }
            if (config.mappingStrategy == MappingStrategy.EAGER || config.mappingStrategy == MappingStrategy.HYBRID) {
                ui.printInfo("Предзагрузка маппингов для синхронизации...")
                mappingService.preloadAllMappings(tables)
            }
            MetricsService.registerCacheMetrics(mappingService)
            val migrator = DataMigrator(
                source,
                target,
                mappingService,
                reader,
                batchSize = config.batchSize,
                adaptiveBatchConfig = config.adaptiveBatchConfig
            )
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
