package monitoring

import org.slf4j.LoggerFactory
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * Монитор производительности для выявления узких мест
 */
class PerformanceMonitor {

    companion object {
        private val logger = LoggerFactory.getLogger(PerformanceMonitor::class.java)
        private val instance = PerformanceMonitor()
        
        fun getInstance(): PerformanceMonitor = instance
    }

    data class OperationStats(
        val name: String,
        val count: Long,
        val totalTimeMs: Long,
        val minTimeMs: Long,
        val maxTimeMs: Long
    ) {
        val avgTimeMs: Double get() = if (count > 0) totalTimeMs.toDouble() / count else 0.0
        val opsPerSec: Double get() = if (totalTimeMs > 0) count * 1000.0 / totalTimeMs else 0.0
    }

    private class OperationTracker {
        val count = AtomicLong(0)
        val totalTime = AtomicLong(0)
        val minTime = AtomicLong(Long.MAX_VALUE)
        val maxTime = AtomicLong(0)
    }

    private val trackers = ConcurrentHashMap<String, OperationTracker>()
    private val startTime = System.currentTimeMillis()

    /**
     * Замер выполнения операции
     */
    fun <T> measure(operation: String, block: () -> T): T {
        val tracker = trackers.getOrPut(operation) { OperationTracker() }
        val start = System.currentTimeMillis()
        
        try {
            return block()
        } finally {
            val duration = System.currentTimeMillis() - start
            tracker.count.incrementAndGet()
            tracker.totalTime.addAndGet(duration)
            tracker.minTime.updateAndGet { minOf(it, duration) }
            tracker.maxTime.updateAndGet { maxOf(it, duration) }
        }
    }

    /**
     * Отчёт по всем операциям
     */
    fun printReport() {
        val elapsed = System.currentTimeMillis() - startTime
        
        println("\n" + "=".repeat(80))
        println("PERFORMANCE REPORT (elapsed: ${elapsed / 1000.0}s)")
        println("=".repeat(80))
        println("%-40s %10s %10s %10s %10s %10s".format(
            "Operation", "Count", "Avg(ms)", "Total(s)", "Ops/sec", "Min/Max"
        ))
        println("-".repeat(80))
        
        trackers.entries.sortedByDescending { it.value.totalTime.get() }.forEach { (name, tracker) ->
            val stats = OperationStats(
                name = name,
                count = tracker.count.get(),
                totalTimeMs = tracker.totalTime.get(),
                minTimeMs = tracker.minTime.get(),
                maxTimeMs = tracker.maxTime.get()
            )
            
            println("%-40s %10d %10.2f %10.2f %10.0f %8d/%d".format(
                stats.name,
                stats.count,
                stats.avgTimeMs,
                stats.totalTimeMs / 1000.0,
                stats.opsPerSec,
                stats.minTimeMs,
                stats.maxTimeMs
            ))
        }
        
        println("=".repeat(80))
        
        // Рекомендации
        printRecommendations(elapsed)
    }

    private fun printRecommendations(totalElapsed: Long) {
        println("\nANALYSIS & RECOMMENDATIONS:")
        println("-".repeat(80))
        
        val dbOps = trackers.filter { it.key.contains("db") || it.key.contains("mapping") }
        val totalDbTime = dbOps.values.sumOf { it.totalTime.get() }
        val dbPercent = if (totalElapsed > 0) totalDbTime * 100.0 / totalElapsed else 0.0
        
        println("Database operations: ${dbPercent.toInt()}% of total time")
        
        if (dbPercent > 50) {
            println("⚠ WARNING: Database operations dominate execution time!")
            println("  Recommendation: Increase caching, batch operations")
        }
        
        // Анализ блокировок
        val connectionWait = trackers["connection_wait"]
        if (connectionWait != null && connectionWait.totalTime.get() > totalElapsed * 0.3) {
            val waitPercent = connectionWait.totalTime.get() * 100.0 / totalElapsed
            println("⚠ WARNING: Connection wait time: ${waitPercent.toInt()}%!")
            println("  Recommendation: Increase pool size or reduce connection usage")
        }
        
        // Расчёт оптимального числа потоков
        val optimalThreads = calculateOptimalThreads()
        println("\nOptimal thread pool size: $optimalThreads")
        println("  Formula: threads = CPU_cores * (1 + wait_time / compute_time)")
    }

    private fun calculateOptimalThreads(): Int {
        val cpuCores = Runtime.getRuntime().availableProcessors()
        
        // Если есть данные о времени ожидания и выполнения
        val waitTime = trackers["connection_wait"]?.totalTime?.get() ?: 0L
        val computeTime = trackers.filterKeys { !it.contains("wait") }
            .values.sumOf { it.totalTime.get() }
        
        if (computeTime > 0) {
            val waitRatio = waitTime.toDouble() / computeTime
            val optimal = (cpuCores * (1 + waitRatio)).toInt()
            return optimal.coerceIn(cpuCores, cpuCores * 4)
        }
        
        return cpuCores * 2
    }

    fun reset() {
        trackers.clear()
    }
}

/**
 * Extension функции для удобного использования
 */
fun <T> String.measure(block: () -> T): T {
    return PerformanceMonitor.getInstance().measure(this, block)
}
