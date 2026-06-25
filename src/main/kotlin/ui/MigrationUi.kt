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
        success: Boolean,
        cacheStats: Map<String, Any>? = null // <-- ДОБАВЛЕН НОВЫЙ ПАРАМЕТР
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

        if (cacheStats != null) {
            terminal.println("-".repeat(60))
            terminal.println("CACHE METRICS (W-TinyLFU Caffeine):")
            terminal.println("Final Cache Size: ${cacheStats["cache_size"]} items")

            val hitRate = cacheStats["hit_rate"] as? Double ?: 0.0
            terminal.println("Cache Hit Rate:   ${String.format("%.2f", hitRate * 100)} %")

            val evictions = cacheStats["eviction_count"] as? Long ?: 0L
            terminal.println("Eviction Count:   $evictions items (Memory protected!)")
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

}
