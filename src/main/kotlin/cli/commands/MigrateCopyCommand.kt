package cli.commands

import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.mordant.terminal.Terminal
import com.zaxxer.hikari.HikariDataSource
import config.MigrateCommand
import core.MetadataReader
import core.MigrationScopePlanner
import engine.DataMigrator
import engine.HybridTableSelector
import engine.MappingServiceFactory
import engine.MappingStrategy
import logging.MetricsService
import ui.MigrationUi
import utils.HikariFactory
import kotlin.system.measureTimeMillis

/**
 * Команда: migrate copy
 * Запуск первичного переноса данных из source в target
 */
class MigrateCopyCommand : MigrateCommand(
    "copy",
    "Первичный перенос данных из source в target"
) {

    private val terminal = Terminal()
    private val ui = MigrationUi(terminal)

    // Перенос реальной индексной схемы из source (рекомендуемый способ)
    private val migrateIndexes by option("--migrate-indexes", "-mi",
        help = "Перенести индексы из source в target (анализ pg_catalog, замена UUID→BIGINT)").flag()

    // Опциональное создание индексов на всех FK-колонках (не рекомендуется как базовая практика)
    private val createFkIndexes by option("--create-fk-indexes",
        help = "Создать индексы на всех FK-колонках (опционально, не рекомендуется)").flag()

    override fun run() {
        val config = buildConfig()
        ui.printSectionTitle("Первичная миграция данных")

        if (config.dryRun) {
            ui.printWarning("DRY RUN - без фактического копирования данных")
        }

        var sourceDs: HikariDataSource? = null
        var targetDs: HikariDataSource? = null

        try {
            // Подключение к базам данных
            ui.printInfo("Source: ${config.sourceJdbcUrl}")
            ui.printInfo("Target: ${config.targetJdbcUrl}")

            sourceDs = HikariFactory.createDataSource(config.sourceJdbcUrl, config.sourceUser, config.sourcePassword, config.maxPoolSize)
            targetDs = HikariFactory.createDataSource(config.targetJdbcUrl, config.targetUser, config.targetPassword, config.maxPoolSize)

            // Анализ схемы
            ui.printInfo("Анализ схемы source database...")
            val reader = MetadataReader(sourceDs)
            val scope = MigrationScopePlanner.analyze(reader)
            MigrationScopeReporter.report(scope, ui, terminal)
            val migrationOrder = scope.migrationOrder

            ui.printSuccess("Порядок миграции: ${migrationOrder.joinToString(" → ")}")

            // Создание целевой схемы
            ui.printInfo("Создание целевой схемы...")
            // Инициализация сервиса маппинга с выбранной стратегией
            val mappingService = MappingServiceFactory.create(
                targetDs,
                config.mappingStrategy,
                config.cacheLimit
            )

            if (config.mappingStrategy == MappingStrategy.HYBRID) {
                val pinnedTables = HybridTableSelector.selectPinnedTables(sourceDs, migrationOrder, config.cacheLimit)
                mappingService.configurePinnedTables(pinnedTables)
                ui.printInfo("HYBRID pinned tables: ${pinnedTables.joinToString(", ").ifBlank { "none" }}")
            }
            
            // Предзагрузка для стратегий, которые держат часть mappings в памяти.
            if (config.mappingStrategy == MappingStrategy.EAGER || config.mappingStrategy == MappingStrategy.HYBRID) {
                ui.printInfo("Preloading mappings (${config.mappingStrategy} strategy)...")
                val preloadStart = System.currentTimeMillis()
                mappingService.preloadAllMappings(migrationOrder)
                ui.printInfo("Preloaded in ${System.currentTimeMillis() - preloadStart}ms")
            }

            MetricsService.registerCacheMetrics(mappingService)
            val migrator = DataMigrator(
                sourceDs,
                targetDs,
                mappingService,
                reader,
                batchSize = config.batchSize,
                adaptiveBatchConfig = config.adaptiveBatchConfig
            )

            if (!config.dryRun) {
                migrator.createTargetSchema(migrationOrder)
                ui.printSuccess("Целевая схема создана")
            }

            // Миграция данных
            ui.printSectionTitle("Миграция данных")
            var totalRows = 0L
            var totalDuration = 0L

            migrationOrder.forEach { table ->
                val sourceRowCount = getRowCount(sourceDs, table)
                val targetRowCountBefore = getRowCount(targetDs, table)
                val mappedRowCount = getMappingCount(targetDs, table)

                if (sourceRowCount == targetRowCountBefore && sourceRowCount == mappedRowCount) {
                    ui.printInfo("Таблица $table уже перенесена, пропуск ($sourceRowCount строк)")
                    totalRows += targetRowCountBefore
                    return@forEach
                }

                val duration = measureTimeMillis {
                    ui.printInfo("Миграция таблицы: $table")
                    if (!config.dryRun) {
                        migrator.migrateTable(table)
                    }
                }

                val rowCount = getRowCount(targetDs, table)
                totalRows += rowCount
                totalDuration += duration

                ui.printMigrationStats(table, rowCount, duration, rowCount * 1000.0 / duration)
            }

            if (!config.dryRun) {
                // Build secondary indexes after the bulk load so COPY does not maintain them row by row.
                when {
                    migrateIndexes -> {
                        migrator.migrateIndexes(migrationOrder)
                        ui.printSuccess("Индексы перенесены из source")
                    }
                    createFkIndexes -> {
                        migrator.createForeignKeyIndexes(migrationOrder)
                        ui.printSuccess("FK-индексы созданы")
                    }
                    else -> {
                        ui.printInfo("Индексы не переносятся. Используйте --migrate-indexes для переноса из source или --create-fk-indexes для создания на FK.")
                    }
                }
            }

            // Итоговая сводка
            ui.printSummary(
                totalTables = migrationOrder.size,
                totalRows = totalRows,
                totalDuration = totalDuration,
                success = true
            )
            
            // Завершение логирования производительности
            logging.PerformanceLogger.finish()

        } catch (e: Exception) {
            ui.printError("Ошибка миграции: ${e.message}")
            if (config.verbose) {
                e.printStackTrace()
            }
            ui.printSummary(
                totalTables = 0,
                totalRows = 0,
                totalDuration = 0,
                success = false
            )
            throw e
        } finally {
            // Отправляем последние метрики в Grafana
            MetricsService.pushMetrics()

            // Сбрасываем буферы CSV и сохраняем файлы на диск
            logging.PerformanceLogger.finish()

            // Закрываем пулы соединений
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

    private fun getMappingCount(ds: HikariDataSource, tableName: String): Long {
        ds.connection.use { conn ->
            conn.prepareStatement("SELECT COUNT(*) FROM migration_mapping WHERE table_name = ?").use { pstmt ->
                pstmt.setString(1, tableName)
                val rs = pstmt.executeQuery()
                return if (rs.next()) rs.getLong(1) else 0L
            }
        }
    }

}
