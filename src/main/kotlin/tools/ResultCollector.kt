package tools

import java.io.File
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import javax.sql.DataSource

class ResultCollector(
    private val config: RunConfiguration,
    private val sourceDS: DataSource,
    private val targetDS: DataSource
) {
    private val tableResults = mutableListOf<TableMetrics>()
    private val runId = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"))
    private val runDir = "results/run_$runId"

    init {
        File(runDir).mkdirs()
    }

    fun collect(tableName: String, timeMs: Long) {
        val rowCount = fetchLong(targetDS, "SELECT count(*) FROM $tableName")
        val sourceSize = fetchLong(sourceDS, "SELECT pg_indexes_size('$tableName')") / (1024.0 * 1024.0)
        val targetSize = fetchLong(targetDS, "SELECT pg_indexes_size('$tableName')") / (1024.0 * 1024.0)
        tableResults.add(TableMetrics(tableName, rowCount, sourceSize, targetSize, timeMs))
    }

    private fun fetchLong(ds: DataSource, sql: String): Long {
        ds.connection.use { it.createStatement().executeQuery(sql).let { rs -> return if (rs.next()) rs.getLong(1) else 0L } }
    }

    fun saveToHistory() {
        // Сохраняем параметры запуска
        File("$runDir/config.txt").writeText("""
            Timestamp: ${config.timestamp}
            Total Records: ${config.totalRecords}
            Sync Strategy: ${config.syncStrategy}
            Cache Limit: ${config.cacheLimit}
        """.trimIndent())

        // Сохраняем CSV конкретного прогона
        File("$runDir/metrics.csv").printWriter().use { out ->
            out.println("table,rows,uuid_idx_mb,int_idx_mb,time_ms")
            tableResults.forEach { m ->
                out.println("${m.tableName},${m.rowCount},${m.uuidIndexSizeMB},${m.intIndexSizeMB},${m.migrationTimeMs}")
            }
        }

        // Дублируем в общую историю для графиков
        val historyFile = File("migration_history.csv")
        if (!historyFile.exists()) historyFile.writeText("run_id,table,rows,uuid_idx,int_idx,time\n")
        tableResults.forEach { m ->
            historyFile.appendText("$runId,${m.tableName},${m.rowCount},${m.uuidIndexSizeMB},${m.intIndexSizeMB},${m.migrationTimeMs}\n")
        }

        println(">>> Результаты каталогизированы в папку: $runDir")
    }
}