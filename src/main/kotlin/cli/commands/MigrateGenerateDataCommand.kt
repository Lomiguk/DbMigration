package cli.commands

import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.mordant.terminal.Terminal
import config.MigrateCommand
import logging.PerformanceLogger
import tools.LargeDataGenerator
import ui.MigrationUi

/**
 * Команда: migrate generate-data
 * Генерация тестовых данных в source БД
 */
class MigrateGenerateDataCommand : MigrateCommand(
    "generate-data",
    "Generate test data in source database"
) {

    private val terminal = Terminal()
    private val ui = MigrationUi(terminal)

    private val count by option("--count", help = "Base record count for main tables")
        .default("1000000")

    private val truncate by option("--truncate", "-t", help = "Truncate tables before generation")
        .default("false")

    private val seed by option("--seed", help = "Deterministic random seed for reproducible datasets")

    override fun run() {
        val config = buildConfig()
        ui.printSectionTitle("Test Data Generation")

        val baseCount = count.toIntOrNull() ?: run {
            ui.printError("Invalid record count: $count")
            return
        }

        val doTruncate = truncate.toBoolean() || truncate.equals("yes", ignoreCase = true) || truncate.equals("y", ignoreCase = true)
        val randomSeed = seed?.toLongOrNull() ?: run {
            if (seed != null) {
                ui.printError("Invalid seed: $seed")
                return
            }
            null
        }

        ui.printInfo("Source: ${config.sourceJdbcUrl}")
        ui.printInfo("Base record count: $baseCount")
        ui.printInfo("Truncate before generation: $doTruncate")
        ui.printInfo("Seed: ${randomSeed ?: "random"}")
        ui.printInfo("")

        val generator = LargeDataGenerator(
            jdbcUrl = config.sourceJdbcUrl,
            user = config.sourceUser,
            password = config.sourcePassword
        )

        try {
            if (doTruncate) {
                ui.printInfo("Truncating all tables...")
                generator.truncateAll()
                ui.printSuccess("All tables truncated")
                ui.printInfo("")
            }

            ui.printInfo("Starting data generation...")
            ui.printInfo("")

            val stats = generator.generateAll(
                LargeDataGenerator.GenerationConfig(
                    baseCount = baseCount,
                    batchSize = 10000,
                    rewriteBatchedInserts = true,
                    seed = randomSeed
                )
            )

            ui.printSectionTitle("Generation Results")

            var totalRows = 0L
            var totalTime = 0L

            stats.forEach { stat ->
                totalRows += stat.rowsGenerated
                totalTime += stat.durationMs
                ui.printSuccess("${stat.tableName}: ${stat.rowsGenerated} rows (${stat.durationMs}ms, ${stat.rowsPerSecond.toInt()} rows/sec)")
            }

            ui.printInfo("")
            ui.printSectionTitle("Total")
            ui.printSuccess("Total rows: $totalRows")
            ui.printInfo("Total time: ${totalTime}ms (${if (totalTime > 0) totalRows * 1000 / totalTime else 0} rows/sec)")
            ui.printSuccess("Generation completed successfully!")

        } catch (e: Exception) {
            ui.printError("Generation failed: ${e.message}")
            if (config.verbose) {
                e.printStackTrace()
            }
            throw e
        } finally {
            PerformanceLogger.finish()
        }
    }
}
