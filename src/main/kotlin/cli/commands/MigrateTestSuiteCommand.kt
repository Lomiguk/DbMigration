package cli.commands

import com.github.ajalt.mordant.terminal.Terminal
import com.zaxxer.hikari.HikariDataSource
import config.MigrateCommand
import logging.MetricsService
import testing.MigrationTestSuite
import ui.MigrationUi
import utils.HikariFactory

/**
 * Команда: migrate test-suite
 * Запуск комплексной тестовой системы
 */
class MigrateTestSuiteCommand : MigrateCommand(
    "test-suite",
    "Запуск комплексной тестовой системы"
) {

    private val terminal = Terminal()
    private val ui = MigrationUi(terminal)

    override fun run() {
        val config = buildConfig()
        val recordCount = 1_000_000  // По умолчанию
        
        ui.printSectionTitle("Comprehensive Migration Test Suite")
        ui.printInfo("Record count: $recordCount")
        ui.printInfo("Source: ${config.sourceJdbcUrl}")
        ui.printInfo("Target: ${config.targetJdbcUrl}")

        var sourceDs: HikariDataSource? = null
        var targetDs: HikariDataSource? = null

        try {
            sourceDs = HikariFactory.createDataSource(config.sourceJdbcUrl, config.sourceUser, config.sourcePassword, config.maxPoolSize)
            targetDs = HikariFactory.createDataSource(config.targetJdbcUrl, config.targetUser, config.targetPassword, config.maxPoolSize)

            val testSuite = MigrationTestSuite(sourceDs, targetDs)
            val results = testSuite.runAllTests(recordCount)

            // Вывод результатов
            ui.printSectionTitle("TEST RESULTS SUMMARY")

            results.forEach { result ->
                val status = if (result.success) "✓ PASSED" else "✗ FAILED"
                ui.printInfo("$status - ${result.testName} (${result.duration}ms)")
                if (!result.success) {
                    ui.printError("  Error: ${result.errorMessage}")
                }
            }

            val passed = results.count { it.success }
            val total = results.size

            ui.printSectionTitle("Summary")
            ui.printInfo("Total: $passed/$total tests passed")

            if (passed == total) {
                ui.printSuccess("All tests passed! System is ready for production.")
            } else {
                ui.printError("$total - $passed tests failed. Review required.")
                throw RuntimeException("Test suite failed: ${total - passed} tests")
            }

        } catch (e: Exception) {
            ui.printError("Test suite failed: ${e.message}")
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
