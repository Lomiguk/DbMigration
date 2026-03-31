package ui

import com.github.ajalt.mordant.terminal.Terminal
import kotlin.math.roundToInt

/**
 * Консольный UI для отображения прогресса миграции
 */
class MigrationUi(private val terminal: Terminal = Terminal()) {

    /**
     * Вывод заголовка секции
     */
    fun printSectionTitle(title: String) {
        terminal.println()
        terminal.println("═".repeat(60))
        terminal.println("▐ $title ▐")
        terminal.println("═".repeat(60))
    }

    /**
     * Вывод информационного сообщения
     */
    fun printInfo(message: String) {
        terminal.println("ℹ $message")
    }

    /**
     * Вывод сообщения об успехе
     */
    fun printSuccess(message: String) {
        terminal.println("✓ $message")
    }

    /**
     * Вывод предупреждения
     */
    fun printWarning(message: String) {
        terminal.println("⚠ $message")
    }

    /**
     * Вывод ошибки
     */
    fun printError(message: String) {
        terminal.println("✗ $message")
    }

    /**
     * Вывод статистики миграции
     */
    fun printMigrationStats(
        tableName: String,
        rowCount: Long,
        duration: Long,
        recordsPerSec: Double
    ) {
        terminal.println()
        terminal.println("Table: $tableName")
        terminal.println("  Rows: $rowCount")
        terminal.println("  Duration: ${duration}ms")
        terminal.println("  Speed: ${recordsPerSec.toInt()} rec/sec")
        terminal.println()
    }

    /**
     * Вывод сводки по завершении миграции
     */
    fun printSummary(
        totalTables: Int,
        totalRows: Long,
        totalDuration: Long,
        success: Boolean
    ) {
        terminal.println()
        terminal.println("═".repeat(60))

        if (success) {
            terminal.println("✓ MIGRATION COMPLETED SUCCESSFULLY")
        } else {
            terminal.println("✗ MIGRATION FAILED")
        }

        terminal.println()
        terminal.println("Total tables: $totalTables")
        terminal.println("Total rows: $totalRows")
        terminal.println("Total time: ${totalDuration}ms")

        if (totalDuration > 0) {
            val avgSpeed = (totalRows * 1000.0 / totalDuration).roundToInt()
            terminal.println("Average speed: $avgSpeed rec/sec")
        }

        terminal.println("═".repeat(60))
    }

    /**
     * Вывод статуса синхронизации
     */
    fun printSyncStatus(tableName: String, newRows: Long, skippedRows: Long, duration: Long) {
        terminal.println()
        terminal.println("Sync: $tableName")
        terminal.println("  New rows: $newRows")
        terminal.println("  Skipped: $skippedRows")
        terminal.println("  Duration: ${duration}ms")
    }

    /**
     * Вывод прогресс-бара
     */
    fun renderProgress(tableName: String, current: Long, total: Long) {
        val percentage = ((current.toDouble() / total) * 100).roundToInt()
        val barWidth = 40
        val filledWidth = ((current.toDouble() / total) * barWidth).roundToInt()
        val emptyWidth = barWidth - filledWidth

        val bar = "█".repeat(filledWidth) + "░".repeat(emptyWidth)
        terminal.print("\r[$bar] $percentage% ($current/$total)")
        if (current >= total) {
            terminal.println()
        }
    }

    /**
     * Вывод прогресса выполнения
     */
    fun printProgress(message: String, current: Int, total: Int) {
        val percentage = (current.toDouble() / total * 100).roundToInt()
        terminal.print("\r$message: $current/$total ($percentage%)")
        if (current >= total) {
            terminal.println()
        }
    }
}
