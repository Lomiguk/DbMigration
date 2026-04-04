package logging

import com.zaxxer.hikari.HikariDataSource
import org.slf4j.LoggerFactory
import java.io.FileWriter
import java.io.PrintWriter
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/**
 * Сборщик ценных метрик производительности
 * Пишет в файлы для последующего анализа
 */
object PerformanceLogger {

    private val logger = LoggerFactory.getLogger(PerformanceLogger::class.java)
    private val logDir = "performance_logs"
    private val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss").format(Date())
    
    // Основные файлы логов - создаём только при первом использовании
    private var batchLog: PrintWriter? = null
    private var mappingLog: PrintWriter? = null
    private var poolLog: PrintWriter? = null
    private var summaryLog: PrintWriter? = null

    // Накопленная статистика
    private val batchStats = ConcurrentHashMap<String, BatchStats>()
    private val totalStartTime = System.currentTimeMillis()
    private var initialized = false

    private val scheduler = Executors.newSingleThreadScheduledExecutor()
    private var isMonitoring = false

    private fun ensureInitialized() {
        if (initialized) return
        
        // Создаём директорию
        java.io.File(logDir).mkdirs()
        
        // Заголовки CSV
        batchLog = PrintWriter(FileWriter("$logDir/batch_performance_$timestamp.csv"))
        mappingLog = PrintWriter(FileWriter("$logDir/mapping_performance_$timestamp.csv"))
        poolLog = PrintWriter(FileWriter("$logDir/connection_pool_$timestamp.csv"))
        summaryLog = PrintWriter(FileWriter("$logDir/summary_$timestamp.txt"))
        
        batchLog?.println("timestamp,table,batch_number,records_total,insert_duration_ms,mapping_duration_ms,commit_duration_ms,total_batch_ms,records_per_sec")
        mappingLog?.println("timestamp,table,batch_number,records_saved,mapping_duration_ms,records_per_sec")
        poolLog?.println("timestamp,table,active_connections,idle_connections,total_connections,waiting_threads")
        
        summaryLog?.println("═══════════════════════════════════════════════════════════")
        summaryLog?.println("PERFORMANCE LOG")
        summaryLog?.println("Started: ${SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(Date())}")
        summaryLog?.println("═══════════════════════════════════════════════════════════")
        summaryLog?.flush()
        
        initialized = true
    }

    data class BatchStats(
        val tableName: String,
        var totalBatches: Long = 0,
        var totalRecords: Long = 0,
        var totalDuration: Long = 0,
        var minBatchMs: Long = Long.MAX_VALUE,
        var maxBatchMs: Long = 0,
        var minInsertMs: Long = Long.MAX_VALUE,
        var maxInsertMs: Long = 0,
        var minMappingMs: Long = Long.MAX_VALUE,
        var maxMappingMs: Long = 0
    )

    /**
     * Логирование выполнения батча
     */
    fun logBatch(
        tableName: String,
        batchNumber: Long,
        totalRecords: Long,
        insertDuration: Long,
        mappingDuration: Long,
        commitDuration: Long,
        totalBatchDuration: Long
    ) {
        ensureInitialized()
        
        val timestamp = System.currentTimeMillis()
        val elapsed = timestamp - totalStartTime
        val recordsPerSec = if (totalBatchDuration > 0) totalRecords * 1000 / totalBatchDuration else 0

        // CSV лог
        batchLog?.println("$elapsed,$tableName,$batchNumber,$totalRecords,$insertDuration,$mappingDuration,$commitDuration,$totalBatchDuration,$recordsPerSec")
        batchLog?.flush()

        // Накопление статистики
        val stats = batchStats.getOrPut(tableName) { BatchStats(tableName) }
        synchronized(stats) {
            stats.totalBatches++
            stats.totalRecords = totalRecords
            stats.totalDuration += totalBatchDuration
            stats.minBatchMs = minOf(stats.minBatchMs, totalBatchDuration)
            stats.maxBatchMs = maxOf(stats.maxBatchMs, totalBatchDuration)
            stats.minInsertMs = minOf(stats.minInsertMs, insertDuration)
            stats.maxInsertMs = maxOf(stats.maxInsertMs, insertDuration)
            stats.minMappingMs = minOf(stats.minMappingMs, mappingDuration)
            stats.maxMappingMs = maxOf(stats.maxMappingMs, mappingDuration)
        }

        // Детальный лог для аномалий
        if (mappingDuration > 500) {
            logger.warn("[SLOW_MAPPING] $tableName batch $batchNumber: mapping took ${mappingDuration}ms for ${totalRecords % 10000} records")
        }
        if (insertDuration > 200) {
            logger.warn("[SLOW_INSERT] $tableName batch $batchNumber: insert took ${insertDuration}ms for ${totalRecords % 10000} records")
        }
        if (commitDuration > 100) {
            logger.warn("[SLOW_COMMIT] $tableName batch $batchNumber: commit took ${commitDuration}ms")
        }
    }

    /**
     * Логирование операции маппинга
     */
    fun logMapping(
        tableName: String,
        batchNumber: Long,
        recordsSaved: Long,
        durationMs: Long
    ) {
        ensureInitialized()
        
        val timestamp = System.currentTimeMillis()
        val elapsed = timestamp - totalStartTime
        val recordsPerSec = if (durationMs > 0) recordsSaved * 1000L / durationMs else 0

        mappingLog?.println("$elapsed,$tableName,$batchNumber,$recordsSaved,$durationMs,$recordsPerSec")
        mappingLog?.flush()
    }

    /**
     * Логирование состояния пула соединений
     */
    fun logPoolState(
        tableName: String,
        active: Int,
        idle: Int,
        total: Int,
        waiting: Int
    ) {
        ensureInitialized()
        
        val timestamp = System.currentTimeMillis()
        val elapsed = timestamp - totalStartTime

        poolLog?.println("$elapsed,$tableName,$active,$idle,$total,$waiting")
        poolLog?.flush()
    }

    /**
     * Начало миграции таблицы
     */
    fun startTable(tableName: String) {
        ensureInitialized()
        
        summaryLog?.println("\n───────────────────────────────────────────────────────")
        summaryLog?.println("Table: $tableName")
        summaryLog?.println("Started: ${SimpleDateFormat("HH:mm:ss").format(Date())}")
        summaryLog?.flush()
    }

    /**
     * Завершение миграции таблицы
     */
    fun completeTable(
        tableName: String,
        totalRecords: Long,
        totalDuration: Long,
        avgRecordsPerSec: Double
    ) {
        ensureInitialized()
        
        val stats = batchStats[tableName]
        
        summaryLog?.println("Completed: ${SimpleDateFormat("HH:mm:ss").format(Date())}")
        summaryLog?.println("Total records: $totalRecords")
        summaryLog?.println("Total duration: ${totalDuration / 1000.0}s")
        summaryLog?.println("Average speed: ${avgRecordsPerSec.toInt()} rec/sec")
        
        if (stats != null) {
            synchronized(stats) {
                summaryLog?.println("Batches: ${stats.totalBatches}")
                summaryLog?.println("Batch time (ms): min=${stats.minBatchMs}, max=${stats.maxBatchMs}, avg=${stats.totalDuration / stats.totalBatches}")
                summaryLog?.println("Insert time (ms): min=${stats.minInsertMs}, max=${stats.maxInsertMs}")
                summaryLog?.println("Mapping time (ms): min=${stats.minMappingMs}, max=${stats.maxMappingMs}")
            }
        }
        
        summaryLog?.flush()
    }

    /**
     * Финальный отчёт
     */
    fun finish() {
        scheduler.shutdownNow()
        ensureInitialized()
        
        summaryLog?.println("\n═══════════════════════════════════════════════════════════")
        summaryLog?.println("FINAL SUMMARY")
        summaryLog?.println("Completed: ${SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(Date())}")
        summaryLog?.println("═══════════════════════════════════════════════════════════")

        batchStats.values.forEach { stats ->
            synchronized(stats) {
                val avgBatchMs = stats.totalDuration / stats.totalBatches
                val avgRecPerSec = if (avgBatchMs > 0) (stats.totalRecords % 10000) * 1000 / avgBatchMs else 0
                
                summaryLog?.println("\n${stats.tableName}:")
                summaryLog?.println("  Total records: ${stats.totalRecords}")
                summaryLog?.println("  Total batches: ${stats.totalBatches}")
                summaryLog?.println("  Avg batch time: ${avgBatchMs}ms")
                summaryLog?.println("  Avg speed: ${avgRecPerSec} rec/sec")
                summaryLog?.println("  Batch range: ${stats.minBatchMs}ms - ${stats.maxBatchMs}ms")
                summaryLog?.println("  Insert range: ${stats.minInsertMs}ms - ${stats.maxInsertMs}ms")
                summaryLog?.println("  Mapping range: ${stats.minMappingMs}ms - ${stats.maxMappingMs}ms")
            }
        }

        summaryLog?.println("\n═══════════════════════════════════════════════════════════")
        summaryLog?.println("Log files:")
        summaryLog?.println("  - $logDir/batch_performance_$timestamp.csv")
        summaryLog?.println("  - $logDir/mapping_performance_$timestamp.csv")
        summaryLog?.println("  - $logDir/connection_pool_$timestamp.csv")
        summaryLog?.println("  - $logDir/summary_$timestamp.txt")
        summaryLog?.println("═══════════════════════════════════════════════════════════")

        // Сбрасываем буферы и закрываем файлы
        batchLog?.flush()
        mappingLog?.flush()
        poolLog?.flush()
        summaryLog?.flush()
        
        batchLog?.close()
        mappingLog?.close()
        poolLog?.close()
        summaryLog?.close()

        logger.info("Performance logs written to: $logDir/")
    }

    fun startPoolMonitoring(dataSource: HikariDataSource) {
        if (isMonitoring) return
        isMonitoring = true

        val mxBean = dataSource.hikariPoolMXBean
        if (mxBean != null) {
            scheduler.scheduleAtFixedRate({
                try {
                    val time = System.currentTimeMillis() - totalStartTime
                    val active = mxBean.activeConnections
                    val idle = mxBean.idleConnections
                    val total = mxBean.totalConnections
                    val waiting = mxBean.threadsAwaitingConnection

                    poolLog?.println("$time,$active,$idle,$total,$waiting")
                    poolLog?.flush()
                } catch (e: Exception) { /* Игнорируем ошибки при завершении пула */ }
            }, 0, 1, TimeUnit.SECONDS) // Собираем метрику каждую секунду
        }
    }
}
