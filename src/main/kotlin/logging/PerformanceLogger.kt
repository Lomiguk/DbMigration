package logging

import org.slf4j.LoggerFactory
import java.io.FileWriter
import java.io.PrintWriter
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Сборщик ценных метрик производительности
 * Пишет в файлы для последующего анализа
 *
 * Complements [MetricsService] with durable CSV artifacts for offline analysis.
 */
object PerformanceLogger {

    private val logger = LoggerFactory.getLogger(PerformanceLogger::class.java)
    private val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss").format(Date())
    private val runLogDir = "performance_logs/run_$timestamp"
    
    // Основные файлы логов - создаём только при первом использовании
    private var batchLog: PrintWriter? = null
    private var batchPhaseLog: PrintWriter? = null
    private var mappingLog: PrintWriter? = null
    private var dbLookupLog: PrintWriter? = null
    private var cacheLog: PrintWriter? = null
    private var jvmLog: PrintWriter? = null
    private var runConfigLog: PrintWriter? = null
    private var poolLog: PrintWriter? = null
    private var summaryLog: PrintWriter? = null

    // Накопленная статистика
    private val batchStats = ConcurrentHashMap<String, BatchStats>()
    private val totalStartTime = System.currentTimeMillis()
    private var initialized = false
    private var finished = false

    private val scheduler = Executors.newSingleThreadScheduledExecutor { runnable ->
        Thread(runnable, "performance-logger").apply {
            isDaemon = true
        }
    }
    private var isMonitoring = false

    private fun ensureInitialized() {
        if (initialized) return

        // Создаем директорию конкретного запуска
        java.io.File(runLogDir).mkdirs()

        // Файлы теперь называются просто и понятно, так как они лежат в папке запуска
        batchLog = PrintWriter(FileWriter("$runLogDir/batch_performance.csv"))
        batchPhaseLog = PrintWriter(FileWriter("$runLogDir/batch_phase_performance.csv"))
        mappingLog = PrintWriter(FileWriter("$runLogDir/mapping_performance.csv"))
        dbLookupLog = PrintWriter(FileWriter("$runLogDir/mapping_db_lookup.csv"))
        cacheLog = PrintWriter(FileWriter("$runLogDir/cache_snapshots.csv"))
        jvmLog = PrintWriter(FileWriter("$runLogDir/jvm_snapshots.csv"))
        runConfigLog = PrintWriter(FileWriter("$runLogDir/run_config.txt"))
        poolLog = PrintWriter(FileWriter("$runLogDir/connection_pool.csv"))
        summaryLog = PrintWriter(FileWriter("$runLogDir/summary.txt"))
        
        batchLog?.println("timestamp,table,batch_number,records_total,insert_duration_ms,mapping_duration_ms,commit_duration_ms,total_batch_ms,records_per_sec")
        batchPhaseLog?.println("timestamp,table,batch_number,phase,duration_ms,records_total,records_per_sec")
        mappingLog?.println("timestamp,table,batch_number,records_saved,mapping_duration_ms,records_per_sec")
        dbLookupLog?.println("timestamp,strategy,table,duration_ms,found")
        cacheLog?.println("timestamp,strategy,cache_size,lazy_cache_size,pinned_cache_size,hit_rate,eviction_count,request_count,miss_count")
        jvmLog?.println("timestamp,heap_used_bytes,heap_committed_bytes,heap_max_bytes,non_heap_used_bytes,gc_count,gc_time_ms,available_processors")
        poolLog?.println("timestamp,table,active_connections,idle_connections,total_connections,waiting_threads")
        
        summaryLog?.println("═══════════════════════════════════════════════════════════")
        summaryLog?.println("PERFORMANCE LOG")
        summaryLog?.println("Started: ${SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(Date())}")
        summaryLog?.println("═══════════════════════════════════════════════════════════")
        summaryLog?.flush()
        
        initialized = true
        startJvmMonitoring()
    }

    fun startRun(commandName: String, properties: Map<String, String>) {
        ensureInitialized()

        runConfigLog?.println("command=$commandName")
        properties.toSortedMap().forEach { (key, value) ->
            runConfigLog?.println("$key=$value")
        }
        runConfigLog?.flush()
    }

    fun logMappingDbLookup(strategy: String, tableName: String, durationMs: Long, found: Boolean) {
        ensureInitialized()

        val elapsed = System.currentTimeMillis() - totalStartTime
        dbLookupLog?.println("$elapsed,$strategy,$tableName,$durationMs,$found")
        dbLookupLog?.flush()
    }

    fun logBatchPhase(
        tableName: String,
        batchNumber: Long,
        phase: String,
        durationMs: Long,
        recordsTotal: Long
    ) {
        ensureInitialized()

        val elapsed = System.currentTimeMillis() - totalStartTime
        val recordsPerSec = if (durationMs > 0) recordsTotal * 1000 / durationMs else 0
        batchPhaseLog?.println("$elapsed,$tableName,$batchNumber,$phase,$durationMs,$recordsTotal,$recordsPerSec")
        batchPhaseLog?.flush()
    }

    fun logCacheSnapshot(stats: Map<String, Any>) {
        ensureInitialized()

        val elapsed = System.currentTimeMillis() - totalStartTime
        val strategy = stats["strategy"] ?: "UNKNOWN"
        val cacheSize = stats["cache_size"] ?: 0L
        val lazyCacheSize = stats["lazy_cache_size"] ?: cacheSize
        val pinnedCacheSize = stats["pinned_cache_size"] ?: 0L
        val hitRate = stats["hit_rate"] ?: 0.0
        val evictionCount = stats["eviction_count"] ?: 0L
        val requestCount = stats["request_count"] ?: 0L
        val missCount = stats["miss_count"] ?: 0L

        cacheLog?.println("$elapsed,$strategy,$cacheSize,$lazyCacheSize,$pinnedCacheSize,$hitRate,$evictionCount,$requestCount,$missCount")
        cacheLog?.flush()
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
            stats.totalRecords += totalRecords
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
            logger.warn("[SLOW_MAPPING] $tableName batch $batchNumber: mapping took ${mappingDuration}ms for $totalRecords records")
        }
        if (insertDuration > 200) {
            logger.warn("[SLOW_INSERT] $tableName batch $batchNumber: insert took ${insertDuration}ms for $totalRecords records")
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
                summaryLog?.println("Copy data time (ms): min=${stats.minInsertMs}, max=${stats.maxInsertMs}")
                summaryLog?.println("Mapping save time (ms): min=${stats.minMappingMs}, max=${stats.maxMappingMs}")
            }
        }
        
        summaryLog?.flush()
    }

    /**
     * Финальный отчёт
     */
    fun finish() {
        if (finished) return
        ensureInitialized()
        scheduler.shutdownNow()
        
        summaryLog?.println("\n===========================================================")
        summaryLog?.println("FINAL SUMMARY")
        summaryLog?.println("Completed: ${SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(Date())}")
        summaryLog?.println("============================================================")

        batchStats.values.forEach { stats ->
            synchronized(stats) {
                val avgBatchMs = stats.totalDuration / stats.totalBatches
                val avgRecPerSec = if (stats.totalDuration > 0) stats.totalRecords * 1000 / stats.totalDuration else 0
                
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

        summaryLog?.println("\n============================================================")
        summaryLog?.println("Log files:")
        summaryLog?.println("  - $runLogDir/batch_performance.csv")
        summaryLog?.println("  - $runLogDir/batch_phase_performance.csv")
        summaryLog?.println("  - $runLogDir/mapping_performance.csv")
        summaryLog?.println("  - $runLogDir/mapping_db_lookup.csv")
        summaryLog?.println("  - $runLogDir/cache_snapshots.csv")
        summaryLog?.println("  - $runLogDir/jvm_snapshots.csv")
        summaryLog?.println("  - $runLogDir/run_config.txt")
        summaryLog?.println("  - $runLogDir/connection_pool.csv")
        summaryLog?.println("  - $runLogDir/summary.txt")
        summaryLog?.println("=============================================================")

        // Сбрасываем буферы и закрываем файлы
        batchLog?.flush()
        batchPhaseLog?.flush()
        mappingLog?.flush()
        dbLookupLog?.flush()
        cacheLog?.flush()
        jvmLog?.flush()
        runConfigLog?.flush()
        poolLog?.flush()
        summaryLog?.flush()
        
        batchLog?.close()
        batchPhaseLog?.close()
        mappingLog?.close()
        dbLookupLog?.close()
        cacheLog?.close()
        jvmLog?.close()
        runConfigLog?.close()
        poolLog?.close()
        summaryLog?.close()

        finished = true
        logger.info("Performance logs written to: $runLogDir/")
    }

    private fun startJvmMonitoring() {
        if (isMonitoring) return

        scheduler.scheduleAtFixedRate({
            try {
                logJvmSnapshot()
            } catch (e: Exception) {
                logger.debug("Failed to write JVM snapshot: ${e.message}")
            }
        }, 0, 5, TimeUnit.SECONDS)

        isMonitoring = true
    }

    private fun logJvmSnapshot() {
        val memory = java.lang.management.ManagementFactory.getMemoryMXBean()
        val heap = memory.heapMemoryUsage
        val nonHeap = memory.nonHeapMemoryUsage
        val gcBeans = java.lang.management.ManagementFactory.getGarbageCollectorMXBeans()
        val gcCount = gcBeans.sumOf { it.collectionCount.coerceAtLeast(0L) }
        val gcTime = gcBeans.sumOf { it.collectionTime.coerceAtLeast(0L) }
        val elapsed = System.currentTimeMillis() - totalStartTime

        jvmLog?.println(
            "$elapsed,${heap.used},${heap.committed},${heap.max},${nonHeap.used},$gcCount,$gcTime,${Runtime.getRuntime().availableProcessors()}"
        )
        jvmLog?.flush()
    }

}
