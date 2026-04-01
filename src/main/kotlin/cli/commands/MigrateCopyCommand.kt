package cli.commands

import com.github.ajalt.mordant.terminal.Terminal
import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import config.MigrateCommand
import core.DependencyResolver
import core.MetadataReader
import engine.DataMigrator
import engine.MappingServiceFactory
import engine.MappingStrategy
import ui.MigrationUi
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

            sourceDs = createDataSource(config.sourceJdbcUrl, config.sourceUser, config.sourcePassword, config.maxPoolSize)
            targetDs = createDataSource(config.targetJdbcUrl, config.targetUser, config.targetPassword, config.maxPoolSize)

            // Анализ схемы
            ui.printInfo("Анализ схемы source database...")
            val reader = MetadataReader(sourceDs)
            val tables = reader.getAllTablesWithUuidPk()
            val relations = reader.getForeignKeys()

            // Построение порядка миграции
            val resolver = DependencyResolver()
            resolver.buildGraph(tables, relations)
            val migrationOrder = resolver.getMigrationOrder()

            ui.printSuccess("Порядок миграции: ${migrationOrder.joinToString(" → ")}")

            // Создание целевой схемы
            ui.printInfo("Создание целевой схемы...")
            // Инициализация сервиса маппинга с выбранной стратегией
            val mappingService = MappingServiceFactory.create(
                targetDs,
                config.mappingStrategy,
                config.cacheLimit
            )
            
            // Предзагрузка для EAGER стратегии
            if (config.mappingStrategy == MappingStrategy.EAGER) {
                ui.printInfo("Preloading mappings (EAGER strategy)...")
                val preloadStart = System.currentTimeMillis()
                mappingService.preloadAllMappings(migrationOrder)
                ui.printInfo("Preloaded in ${System.currentTimeMillis() - preloadStart}ms")
            }
            
            val migrator = DataMigrator(sourceDs, targetDs, mappingService, reader)

            if (!config.dryRun) {
                migrator.createTargetSchema(migrationOrder)
                ui.printSuccess("Целевая схема создана")
            }

            // Миграция данных
            ui.printSectionTitle("Миграция данных")
            var totalRows = 0L
            var totalDuration = 0L

            migrationOrder.forEach { table ->
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
