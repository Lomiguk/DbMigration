package cli.commands

import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.mordant.terminal.Terminal
import com.zaxxer.hikari.HikariDataSource
import config.MigrateCommand
import engine.MappingService
import logging.MetricsService
import replication.ReplicationService
import replication.ReplicationConfig
import ui.MigrationUi
import utils.HikariFactory

/**
 * Команда: migrate replicate
 * Запуск логической репликации (CDC через WAL)
 */
class MigrateReplicateCommand : MigrateCommand(
    "replicate",
    "Запуск логической репликации (CDC через WAL)"
) {

    private val terminal = Terminal()
    private val ui = MigrationUi(terminal)

    private val slotName by option("--slot-name", "-sn", help = "Replication slot name")
        .default("dbmigration_slot")

    private val temporary by option("--temporary", "-t", help = "Temporary slot (auto-drop on disconnect)")
        .flag()

    private val continuous by option("--continuous", help = "Continuous replication mode")
        .flag()

    override fun run() {
        val config = buildConfig()
        ui.printSectionTitle("Логическая репликация WAL")

        var sourceDs: HikariDataSource? = null
        var targetDs: HikariDataSource? = null
        var replicationService: ReplicationService? = null

        try {
            ui.printInfo("Source: ${config.sourceJdbcUrl}")
            ui.printInfo("Target: ${config.targetJdbcUrl}")
            ui.printInfo("Slot: $slotName")
            ui.printInfo("Mode: ${if (continuous) "Continuous" else "Delta Sync"}")

            sourceDs = HikariFactory.createDataSource(config.sourceJdbcUrl, config.sourceUser, config.sourcePassword, config.maxPoolSize)
            targetDs = HikariFactory.createDataSource(config.targetJdbcUrl, config.targetUser, config.targetPassword, config.maxPoolSize)

            // Инициализация сервиса репликации
            val mappingService = MappingService(targetDs)
            val replConfig = ReplicationConfig(
                slotName = slotName,
                temporary = temporary,
                batchSize = config.batchSize
            )

            replicationService = ReplicationService(sourceDs, targetDs, mappingService, replConfig)
            replicationService.initialize()

            if (continuous) {
                // Непрерывный режим
                ui.printSectionTitle("Запуск непрерывной репликации")
                ui.printInfo("Нажмите Ctrl+C для остановки")

                // Запускаем в отдельном потоке для возможности обработки сигналов
                val replicationThread = Thread {
                    try {
                        replicationService.startReplication()
                    } catch (e: Exception) {
                        ui.printError("Replication error: ${e.message}")
                    }
                }

                // Добавляем hook для остановки
                Runtime.getRuntime().addShutdownHook(Thread {
                    ui.printInfo("\nОстановка репликации...")
                    replicationService.stop()
                })

                replicationThread.start()
                replicationThread.join()
            } else {
                // Режим однократной синхронизации
                ui.printSectionTitle("Запуск синхронизации дельты")

                val startTime = System.currentTimeMillis()
                val result = replicationService.syncDelta()
                val duration = System.currentTimeMillis() - startTime

                ui.printSectionTitle("Результаты синхронизации")
                ui.printSuccess("Прочитано событий: ${result.eventsRead}")
                ui.printSuccess("Применено событий: ${result.eventsApplied}")
                if (result.eventsFailed > 0) {
                    ui.printError("Неудачных событий: ${result.eventsFailed}")
                }
                ui.printInfo("Duration: ${duration}ms")
                ui.printInfo("Success rate: ${"%.1f".format(result.successRate)}%")
                ui.printInfo("Last LSN: ${result.lastLsn}")
                ui.printInfo("Lag: ${replicationService.getLagPretty()}")
            }

        } catch (e: Exception) {
            ui.printError("Ошибка репликации: ${e.message}")
            if (config.verbose) {
                e.printStackTrace()
            }
            throw e
        } finally {
            MetricsService.pushMetrics()

            replicationService?.close()
            sourceDs?.close()
            targetDs?.close()
        }
    }
}
