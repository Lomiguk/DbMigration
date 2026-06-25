package logging

import org.slf4j.LoggerFactory
import java.io.FileWriter
import java.io.PrintWriter
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicInteger

/**
 * Детальный логгер использования соединений
 * Пишет в файл каждое получение/освобождение соединения с контекстом
 */
class DetailedConnectionLogger {

    companion object {
        private val logger = LoggerFactory.getLogger(DetailedConnectionLogger::class.java)
        private val instance = DetailedConnectionLogger()
        
        fun getInstance(): DetailedConnectionLogger = instance
        
        const val ENABLED = true
        const val LOG_FILE = "connection_usage.log"
    }

    data class ConnectionEvent(
        val timestamp: Long,
        val operation: String,
        val eventType: EventType,
        val threadName: String,
        val threadId: Long,
        val waitTimeMs: Long = 0,
        val durationMs: Long = 0,
        val stackTrace: String? = null
    )

    enum class EventType {
        ACQUIRE_START,
        ACQUIRE_END,
        RELEASE,
        SLOW_OPERATION,
        CONTENTION
    }

    private class OperationTracker {
        val activeCount = AtomicInteger(0)
        val totalCount = AtomicLong(0)
        val totalWaitTime = AtomicLong(0)
        val maxActive = AtomicInteger(0)
    }

    private val trackers = ConcurrentHashMap<String, OperationTracker>()
    private val events = Collections.synchronizedList(mutableListOf<ConnectionEvent>())
    private val startTime = System.currentTimeMillis()
    private var writer: PrintWriter? = null

    init {
        try {
            writer = PrintWriter(FileWriter(LOG_FILE), true)
            writeHeader()
        } catch (e: Exception) {
            logger.error("Failed to create log file: ${e.message}")
        }
    }

    private fun writeHeader() {
        writer?.println("=" .repeat(120))
        writer?.println("CONNECTION USAGE LOG")
        writer?.println("Started: ${Date(startTime)}")
        writer?.println("Format: TIMESTAMP | OPERATION | EVENT | THREAD | WAIT_MS | DURATION_MS | DETAILS")
        writer?.println("=" .repeat(120))
        writer?.flush()
    }

    /**
     * Логирование начала получения соединения
     */
    fun logAcquireStart(operation: String) {
        if (!ENABLED) return
        
        val event = ConnectionEvent(
            timestamp = System.currentTimeMillis(),
            operation = operation,
            eventType = EventType.ACQUIRE_START,
            threadName = Thread.currentThread().name,
            threadId = Thread.currentThread().id
        )
        
        events.add(event)
        writeEvent(event)
        
        val tracker = trackers.getOrPut(operation) { OperationTracker() }
        tracker.activeCount.incrementAndGet()
        tracker.totalCount.incrementAndGet()
        
        val currentActive = tracker.activeCount.get()
        val maxActive = tracker.maxActive.get()
        if (currentActive > maxActive) {
            tracker.maxActive.set(currentActive)
            logContention(operation, currentActive)
        }
    }

    /**
     * Логирование получения соединения
     */
    fun logAcquireEnd(operation: String, waitTimeMs: Long) {
        if (!ENABLED) return
        
        val event = ConnectionEvent(
            timestamp = System.currentTimeMillis(),
            operation = operation,
            eventType = EventType.ACQUIRE_END,
            threadName = Thread.currentThread().name,
            threadId = Thread.currentThread().id,
            waitTimeMs = waitTimeMs
        )
        
        events.add(event)
        writeEvent(event)
        
        if (waitTimeMs > 100) {
            logSlowAcquire(operation, waitTimeMs)
        }
        
        trackers[operation]?.totalWaitTime?.addAndGet(waitTimeMs)
    }

    /**
     * Логирование освобождения соединения
     */
    fun logRelease(operation: String, durationMs: Long) {
        if (!ENABLED) return
        
        val event = ConnectionEvent(
            timestamp = System.currentTimeMillis(),
            operation = operation,
            eventType = EventType.RELEASE,
            threadName = Thread.currentThread().name,
            threadId = Thread.currentThread().id,
            durationMs = durationMs
        )
        
        events.add(event)
        writeEvent(event)
        
        trackers[operation]?.activeCount?.decrementAndGet()
        
        if (durationMs > 1000) {
            logSlowOperation(operation, durationMs)
        }
    }

    /**
     * Выполнение операции с полным логированием
     */
    fun <T> executeWithLogging(operation: String, block: () -> T): T {
        val acquireStart = System.currentTimeMillis()
        logAcquireStart(operation)

        try {
            val result = block()
            logAcquireEnd(operation, 0)
            return result
        } finally {
            val duration = System.currentTimeMillis() - acquireStart
            logRelease(operation, duration)
        }
    }

    private fun logSlowAcquire(operation: String, waitTimeMs: Long) {
        val event = ConnectionEvent(
            timestamp = System.currentTimeMillis(),
            operation = operation,
            eventType = EventType.SLOW_OPERATION,
            threadName = Thread.currentThread().name,
            threadId = Thread.currentThread().id,
            waitTimeMs = waitTimeMs,
            stackTrace = getStackTrace()
        )
        
        events.add(event)
        writer?.println("\n⚠ SLOW ACQUIRE: $operation waited ${waitTimeMs}ms")
        writer?.println("  Thread: ${event.threadName}")
        writer?.println("  Stack trace:")
        event.stackTrace?.lines()?.take(10)?.forEach { line ->
            writer?.println("    $line")
        }
        writer?.flush()
    }

    private fun logSlowOperation(operation: String, durationMs: Long) {
        val event = ConnectionEvent(
            timestamp = System.currentTimeMillis(),
            operation = operation,
            eventType = EventType.SLOW_OPERATION,
            threadName = Thread.currentThread().name,
            threadId = Thread.currentThread().id,
            durationMs = durationMs,
            stackTrace = getStackTrace()
        )
        
        events.add(event)
        writer?.println("\n⚠ SLOW OPERATION: $operation took ${durationMs}ms")
        writer?.println("  Thread: ${event.threadName}")
        writer?.println("  Stack trace:")
        event.stackTrace?.lines()?.take(10)?.forEach { line ->
            writer?.println("    $line")
        }
        writer?.flush()
    }

    private fun logContention(operation: String, activeCount: Int) {
        if (activeCount < 3) return // Только серьёзная конкуренция
        
        val event = ConnectionEvent(
            timestamp = System.currentTimeMillis(),
            operation = operation,
            eventType = EventType.CONTENTION,
            threadName = Thread.currentThread().name,
            threadId = Thread.currentThread().id
        )
        
        events.add(event)
        writer?.println("\n🔥 CONTENTION: $operation has $activeCount concurrent connections!")
        writer?.flush()
    }

    private fun writeEvent(event: ConnectionEvent) {
        val time = SimpleDateFormat("HH:mm:ss.SSS").format(Date(event.timestamp))
        val line = String.format(
            "%s | %-40s | %-15s | %-20s | %8dms | %8dms",
            time,
            truncate(event.operation, 40),
            event.eventType,
            truncate(event.threadName, 20),
            event.waitTimeMs,
            event.durationMs
        )
        writer?.println(line)
        writer?.flush()
    }

    private fun truncate(s: String, maxLen: Int): String {
        return if (s.length > maxLen) s.substring(0, maxLen - 3) + "..." else s
    }

    private fun getStackTrace(): String {
        return Thread.currentThread().stackTrace
            .drop(2) // Пропускаем методы логгера
            .take(15)
            .joinToString("\n") { "  at ${it.className}.${it.methodName}(${it.fileName}:${it.lineNumber})" }
    }

    /**
     * Финальный отчёт
     */
    fun printFinalReport() {
        val elapsed = System.currentTimeMillis() - startTime
        
        writer?.println("\n" + "=" .repeat(120))
        writer?.println("FINAL SUMMARY")
        writer?.println("=" .repeat(120))
        writer?.println("Total elapsed: ${elapsed / 1000.0}s")
        writer?.println("Total events: ${events.size}")
        writer?.println()
        
        // Сортируем по количеству использований
        val sortedOps = trackers.entries.sortedByDescending { it.value.totalCount.get() }
        
        writer?.println("%-40s %10s %10s %12s %10s".format(
            "Operation", "Count", "Max Active", "Total Wait", "Avg Wait"
        ))
        writer?.println("-" .repeat(120))
        
        sortedOps.forEach { (name, tracker) ->
            val avgWait = if (tracker.totalCount.get() > 0) {
                tracker.totalWaitTime.get().toDouble() / tracker.totalCount.get()
            } else 0.0
            
            writer?.println("%-40s %10d %10d %10dms %8.1fms".format(
                truncate(name, 40),
                tracker.totalCount.get(),
                tracker.maxActive.get(),
                tracker.totalWaitTime.get(),
                avgWait
            ))
        }
        
        // Предупреждения
        val slowAcquires = events.filter { 
            it.eventType == EventType.SLOW_OPERATION && it.waitTimeMs > 0 
        }
        val contentions = events.filter { 
            it.eventType == EventType.CONTENTION 
        }
        
        writer?.println("\n" + "=" .repeat(120))
        writer?.println("WARNINGS")
        writer?.println("=" .repeat(120))
        
        if (slowAcquires.isNotEmpty()) {
            writer?.println("\n⚠ SLOW ACQUIRES (${slowAcquires.size} events):")
            slowAcquires.take(20).forEach { event ->
                writer?.println("  - ${event.operation}: ${event.waitTimeMs}ms (thread: ${event.threadName})")
            }
        }
        
        if (contentions.isNotEmpty()) {
            writer?.println("\n🔥 CONTENTIONS (${contentions.size} events):")
            contentions.take(20).forEach { event ->
                writer?.println("  - ${event.operation}: concurrent connections detected")
            }
        }
        
        writer?.println("\n" + "=" .repeat(120))
        writer?.println("Log file: $LOG_FILE")
        writer?.close()
        
        // Вывод в консоль краткой версии
        println("\n" + "=" .repeat(80))
        println("CONNECTION LOG SUMMARY (full log: $LOG_FILE)")
        println("=" .repeat(80))
        println("Total operations: ${events.size}")
        println("Slow acquires: ${slowAcquires.size}")
        println("Contentions: ${contentions.size}")
        println()
        println("Top 5 operations by connection count:")
        sortedOps.take(5).forEach { (name, tracker) ->
            println("  ${truncate(name, 40)}: ${tracker.totalCount.get()} calls, max ${tracker.maxActive.get()} concurrent")
        }
    }

}

/**
 * Extension функции
 */
fun <T> String.logConnectionDetailed(block: () -> T): T {
    return DetailedConnectionLogger.getInstance().executeWithLogging(this, block)
}
