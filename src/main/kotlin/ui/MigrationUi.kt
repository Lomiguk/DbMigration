package ui

import com.github.ajalt.mordant.terminal.Terminal
import kotlin.math.roundToInt

/**
 * Console UI for displaying migration progress
 */
class MigrationUi(private val terminal: Terminal = Terminal()) {

    /**
     * Print section title
     */
    fun printSectionTitle(title: String) {
        terminal.println()
        terminal.println("=".repeat(60))
        terminal.println("| $title |")
        terminal.println("=".repeat(60))
    }

    /**
     * Print info message
     */
    fun printInfo(message: String) {
        terminal.println("[i] $message")
    }

    /**
     * Print success message
     */
    fun printSuccess(message: String) {
        terminal.println("[+] $message")
    }

    /**
     * Print warning message
     */
    fun printWarning(message: String) {
        terminal.println("[!] $message")
    }

    /**
     * Print error message
     */
    fun printError(message: String) {
        terminal.println("[x] $message")
    }

    /**
     * Print migration stats
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
     * Print migration summary
     */
    fun printSummary(
        totalTables: Int,
        totalRows: Long,
        totalDuration: Long,
        success: Boolean
    ) {
        terminal.println()
        terminal.println("=".repeat(60))

        if (success) {
            terminal.println("[+] MIGRATION COMPLETED SUCCESSFULLY")
        } else {
            terminal.println("[x] MIGRATION FAILED")
        }

        terminal.println()
        terminal.println("Total tables: $totalTables")
        terminal.println("Total rows: $totalRows")
        terminal.println("Total time: ${totalDuration}ms")

        if (totalDuration > 0) {
            val avgSpeed = (totalRows * 1000.0 / totalDuration).roundToInt()
            terminal.println("Average speed: $avgSpeed rec/sec")
        }

        terminal.println("=".repeat(60))
    }

    /**
     * Print sync status
     */
    fun printSyncStatus(tableName: String, newRows: Long, skippedRows: Long, duration: Long) {
        terminal.println()
        terminal.println("Sync: $tableName")
        terminal.println("  New rows: $newRows")
        terminal.println("  Skipped: $skippedRows")
        terminal.println("  Duration: ${duration}ms")
    }

    /**
     * Print progress bar
     */
    fun renderProgress(tableName: String, current: Long, total: Long) {
        val percentage = ((current.toDouble() / total) * 100).roundToInt()
        val barWidth = 40
        val filledWidth = ((current.toDouble() / total) * barWidth).roundToInt()
        val emptyWidth = barWidth - filledWidth

        val bar = "#".repeat(filledWidth) + "-".repeat(emptyWidth)
        terminal.print("\r[$bar] $percentage% ($current/$total)")
        if (current >= total) {
            terminal.println()
        }
    }

    /**
     * Print progress percentage
     */
    fun printProgress(message: String, current: Int, total: Int) {
        val percentage = (current.toDouble() / total * 100).roundToInt()
        terminal.print("\r$message: $current/$total ($percentage%)")
        if (current >= total) {
            terminal.println()
        }
    }
}
