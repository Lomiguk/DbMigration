package logging

import core.DependencyAnalysis
import core.TableIdentityInfo
import org.slf4j.LoggerFactory
import java.io.File
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
    private var adaptiveBatchLog: PrintWriter? = null
    private var walSyncLog: PrintWriter? = null
    private var schemaInventoryLog: PrintWriter? = null
    private var dependencyAnalysisLog: PrintWriter? = null
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
        File(runLogDir).mkdirs()

        // Файлы теперь называются просто и понятно, так как они лежат в папке запуска
        batchLog = PrintWriter(FileWriter("$runLogDir/batch_performance.csv"))
        batchPhaseLog = PrintWriter(FileWriter("$runLogDir/batch_phase_performance.csv"))
        mappingLog = PrintWriter(FileWriter("$runLogDir/mapping_performance.csv"))
        dbLookupLog = PrintWriter(FileWriter("$runLogDir/mapping_db_lookup.csv"))
        cacheLog = PrintWriter(FileWriter("$runLogDir/cache_snapshots.csv"))
        adaptiveBatchLog = PrintWriter(FileWriter("$runLogDir/adaptive_batch_decisions.csv"))
        walSyncLog = PrintWriter(FileWriter("$runLogDir/wal_sync_performance.csv"))
        schemaInventoryLog = PrintWriter(FileWriter("$runLogDir/schema_inventory.csv"))
        dependencyAnalysisLog = PrintWriter(FileWriter("$runLogDir/dependency_analysis.csv"))
        jvmLog = PrintWriter(FileWriter("$runLogDir/jvm_snapshots.csv"))
        runConfigLog = PrintWriter(FileWriter("$runLogDir/run_config.txt"))
        poolLog = PrintWriter(FileWriter("$runLogDir/connection_pool.csv"))
        summaryLog = PrintWriter(FileWriter("$runLogDir/summary.txt"))
        
        batchLog?.println("timestamp,table,batch_number,batch_size,records_total,insert_duration_ms,mapping_duration_ms,commit_duration_ms,total_batch_ms,records_per_sec")
        batchPhaseLog?.println("timestamp,table,batch_number,batch_size,phase,duration_ms,records_total,records_per_sec")
        mappingLog?.println("timestamp,table,batch_number,records_saved,mapping_duration_ms,records_per_sec")
        dbLookupLog?.println("timestamp,strategy,table,duration_ms,found")
        cacheLog?.println("timestamp,strategy,cache_size,lazy_cache_size,pinned_cache_size,hit_rate,eviction_count,request_count,miss_count")
        adaptiveBatchLog?.println("timestamp,table,batch_number,previous_batch_size,next_batch_size,batch_duration_ms,target_duration_ms,reason")
        walSyncLog?.println("timestamp,slot_name,events_read,events_applied,events_failed,read_duration_ms,apply_duration_ms,total_duration_ms,last_lsn")
        schemaInventoryLog?.println("timestamp,table,pk_columns,pk_types,eligible,skip_reason")
        dependencyAnalysisLog?.println("timestamp,record_type,tables,details")
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
        writeRunManifest(commandName, properties)
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
        recordsTotal: Long,
        batchSize: Int = recordsTotal.toInt()
    ) {
        ensureInitialized()

        val elapsed = System.currentTimeMillis() - totalStartTime
        val recordsPerSec = if (durationMs > 0) recordsTotal * 1000 / durationMs else 0
        batchPhaseLog?.println("$elapsed,$tableName,$batchNumber,$batchSize,$phase,$durationMs,$recordsTotal,$recordsPerSec")
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
        batchSize: Int,
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
        batchLog?.println("$elapsed,$tableName,$batchNumber,$batchSize,$totalRecords,$insertDuration,$mappingDuration,$commitDuration,$totalBatchDuration,$recordsPerSec")
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

    fun logAdaptiveBatchDecision(
        tableName: String,
        batchNumber: Long,
        previousBatchSize: Int,
        nextBatchSize: Int,
        batchDurationMs: Long,
        targetDurationMs: Long,
        reason: String
    ) {
        ensureInitialized()

        val elapsed = System.currentTimeMillis() - totalStartTime
        adaptiveBatchLog?.println(
            "$elapsed,$tableName,$batchNumber,$previousBatchSize,$nextBatchSize,$batchDurationMs,$targetDurationMs,${csv(reason)}"
        )
        adaptiveBatchLog?.flush()
    }

    fun logWalSync(
        slotName: String,
        eventsRead: Int,
        eventsApplied: Int,
        eventsFailed: Int,
        readDurationMs: Long,
        applyDurationMs: Long,
        totalDurationMs: Long,
        lastLsn: String
    ) {
        ensureInitialized()

        val elapsed = System.currentTimeMillis() - totalStartTime
        walSyncLog?.println(
            "$elapsed,$slotName,$eventsRead,$eventsApplied,$eventsFailed,$readDurationMs,$applyDurationMs,$totalDurationMs,$lastLsn"
        )
        walSyncLog?.flush()
    }

    fun logSchemaInventory(tables: List<TableIdentityInfo>) {
        ensureInitialized()

        val elapsed = System.currentTimeMillis() - totalStartTime
        tables.forEach { table ->
            schemaInventoryLog?.println(
                "$elapsed,${csv(table.tableName)},${csv(table.primaryKeyColumns.joinToString("|"))}," +
                    "${csv(table.primaryKeyTypes.joinToString("|"))},${table.eligibleForUuidMigration}," +
                    csv(table.skipReason ?: "")
            )
        }
        schemaInventoryLog?.flush()
    }

    fun logDependencyAnalysis(analysis: DependencyAnalysis) {
        ensureInitialized()

        val elapsed = System.currentTimeMillis() - totalStartTime
        dependencyAnalysisLog?.println(
            "$elapsed,migration_order,${csv(analysis.migrationOrder.joinToString("|"))},${analysis.migrationOrder.size}"
        )
        analysis.cyclicComponents.forEach { component ->
            dependencyAnalysisLog?.println(
                "$elapsed,cycle,${csv(component.joinToString("|"))},${component.size}"
            )
        }
        if (analysis.blockedTables.isNotEmpty()) {
            dependencyAnalysisLog?.println(
                "$elapsed,blocked_tables,${csv(analysis.blockedTables.joinToString("|"))},${analysis.blockedTables.size}"
            )
        }
        analysis.blockedRelations.forEach { relation ->
            dependencyAnalysisLog?.println(
                "$elapsed,blocked_relation,${csv("${relation.parentTable}->${relation.childTable}")},"
                    + csv("${relation.parentTable},${relation.childTable}")
            )
        }
        dependencyAnalysisLog?.flush()
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
                summaryLog?.println("  Avg speed: $avgRecPerSec rec/sec")
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
        summaryLog?.println("  - $runLogDir/adaptive_batch_decisions.csv")
        summaryLog?.println("  - $runLogDir/wal_sync_performance.csv")
        summaryLog?.println("  - $runLogDir/schema_inventory.csv")
        summaryLog?.println("  - $runLogDir/dependency_analysis.csv")
        summaryLog?.println("  - $runLogDir/jvm_snapshots.csv")
        summaryLog?.println("  - $runLogDir/run_config.txt")
        summaryLog?.println("  - $runLogDir/run_manifest.json")
        summaryLog?.println("  - $runLogDir/connection_pool.csv")
        summaryLog?.println("  - $runLogDir/summary.txt")
        summaryLog?.println("=============================================================")

        // Сбрасываем буферы и закрываем файлы
        batchLog?.flush()
        batchPhaseLog?.flush()
        mappingLog?.flush()
        dbLookupLog?.flush()
        cacheLog?.flush()
        adaptiveBatchLog?.flush()
        walSyncLog?.flush()
        schemaInventoryLog?.flush()
        dependencyAnalysisLog?.flush()
        jvmLog?.flush()
        runConfigLog?.flush()
        poolLog?.flush()
        summaryLog?.flush()
        
        batchLog?.close()
        batchPhaseLog?.close()
        mappingLog?.close()
        dbLookupLog?.close()
        cacheLog?.close()
        adaptiveBatchLog?.close()
        walSyncLog?.close()
        schemaInventoryLog?.close()
        dependencyAnalysisLog?.close()
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

    private fun writeRunManifest(commandName: String, properties: Map<String, String>) {
        val manifest = buildString {
            appendLine("{")
            appendLine("  \"command\": ${json(commandName)},")
            appendLine("  \"started_at\": ${json(SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(Date()))},")
            appendLine("  \"working_directory\": ${json(File(".").absoluteFile.normalize().path)},")
            appendLine("  \"git_branch\": ${json(runCommand("git", "rev-parse", "--abbrev-ref", "HEAD"))},")
            appendLine("  \"git_commit\": ${json(runCommand("git", "rev-parse", "HEAD"))},")
            appendLine("  \"java_version\": ${json(System.getProperty("java.version"))},")
            appendLine("  \"os_name\": ${json(System.getProperty("os.name"))},")
            appendLine("  \"available_processors\": ${Runtime.getRuntime().availableProcessors()},")
            appendLine("  \"properties\": {")
            properties.toSortedMap().entries.forEachIndexed { index, entry ->
                val suffix = if (index < properties.size - 1) "," else ""
                appendLine("    ${json(entry.key)}: ${json(entry.value)}$suffix")
            }
            appendLine("  }")
            appendLine("}")
        }

        File("$runLogDir/run_manifest.json").writeText(manifest)
    }

    private fun runCommand(vararg command: String): String {
        return try {
            val process = ProcessBuilder(*command)
                .redirectErrorStream(true)
                .start()
            if (!process.waitFor(1, TimeUnit.SECONDS)) {
                process.destroyForcibly()
                "unknown"
            } else {
                process.inputStream.bufferedReader().readText().trim().ifBlank { "unknown" }
            }
        } catch (_: Exception) {
            "unknown"
        }
    }

    private fun json(value: String): String =
        "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\""

    private fun csv(value: String): String {
        val needsQuoting = value.any { it == ',' || it == '"' || it == '\n' || it == '\r' }
        if (!needsQuoting) return value
        return "\"" + value.replace("\"", "\"\"") + "\""
    }

}
