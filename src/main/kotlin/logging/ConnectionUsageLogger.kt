package logging

import org.slf4j.LoggerFactory
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicInteger

/**
 * Логгер использования соединений
 * Показывает какие операции и сколько раз используют соединения
 */
class ConnectionUsageLogger {

    companion object {
        private val logger = LoggerFactory.getLogger(ConnectionUsageLogger::class.java)
        private val instance = ConnectionUsageLogger()
        
        // Включить детальное логирование
        var enabled = true
    }

    private class OperationTracker {
        val count = AtomicLong(0)
        val activeCount = AtomicInteger(0)
        val totalWaitTime = AtomicLong(0)
    }

    private val trackers = ConcurrentHashMap<String, OperationTracker>()
    private val startTime = System.currentTimeMillis()

    /**
     * Логирование получения соединения
     */
    fun logConnectionAcquired(operation: String, waitTimeMs: Long = 0) {
        if (!enabled) return
        
        val tracker = trackers.getOrPut(operation) { OperationTracker() }
        tracker.count.incrementAndGet()
        tracker.activeCount.incrementAndGet()
        tracker.totalWaitTime.addAndGet(waitTimeMs)
        
        if (waitTimeMs > 100) {
            logger.warn("CONNECTION WAIT: $operation waited ${waitTimeMs}ms (active: ${tracker.activeCount.get()})")
        }
    }

    /**
     * Логирование освобождения соединения
     */
    fun logConnectionReleased(operation: String) {
        if (!enabled) return
        
        trackers[operation]?.activeCount?.decrementAndGet()
    }

    /**
     * Выполнение операции с логированием
     */
    fun <T> executeWithLogging(operation: String, block: () -> T): T {
        val start = System.currentTimeMillis()
        logConnectionAcquired(operation, 0)
        
        try {
            return block()
        } finally {
            val duration = System.currentTimeMillis() - start
            logConnectionReleased(operation)
            
            if (duration > 1000) {
                logger.warn("SLOW OPERATION: $operation took ${duration}ms")
            }
        }
    }

    /**
     * Отчёт об использовании соединений
     */
    fun printReport() {
        val elapsed = System.currentTimeMillis() - startTime
        
        println("\n" + "=".repeat(80))
        println("CONNECTION USAGE REPORT (elapsed: ${elapsed / 1000.0}s)")
        println("=".repeat(80))
        println("%-40s %10s %10s %12s %10s".format(
            "Operation", "Count", "Active", "Total Wait", "Avg Wait"
        ))
        println("-".repeat(80))
        
        trackers.entries.sortedByDescending { it.value.count.get() }.forEach { (name, tracker) ->
            val avgWait = if (tracker.count.get() > 0) {
                tracker.totalWaitTime.get().toDouble() / tracker.count.get()
            } else 0.0
            
            println("%-40s %10d %10d %10dms %8.1fms".format(
                name,
                tracker.count.get(),
                tracker.activeCount.get(),
                tracker.totalWaitTime.get(),
                avgWait
            ))
        }
        
        println("=".repeat(80))
        
        // Предупреждения
        val highWaitOps = trackers.filter { it.value.totalWaitTime.get() > 5000 }
        if (highWaitOps.isNotEmpty()) {
            println("\n⚠ HIGH WAIT OPERATIONS (>5s total wait):")
            highWaitOps.forEach { (name, tracker) ->
                println("  - $name: ${tracker.totalWaitTime.get() / 1000.0}s total, ${tracker.count.get()} calls")
            }
        }
        
        val highActiveOps = trackers.filter { it.value.activeCount.get() > 5 }
        if (highActiveOps.isNotEmpty()) {
            println("\n⚠ HIGH ACTIVE CONNECTIONS (>5 concurrent):")
            highActiveOps.forEach { (name, tracker) ->
                println("  - $name: ${tracker.activeCount.get()} active")
            }
        }
    }
}
