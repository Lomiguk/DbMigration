package replication

import core.MetadataReader
import engine.MappingService
import logging.MetricsService
import org.slf4j.LoggerFactory
import java.time.LocalDateTime
import javax.sql.DataSource

/**
 * Сервис логической репликации PostgreSQL
 * Обеспечивает CDC (Change Data Capture) через чтение WAL
 */
class ReplicationService(
    private val sourceDataSource: DataSource,
    private val targetDataSource: DataSource,
    private val mappingService: MappingService,
    private val config: ReplicationConfig = ReplicationConfig()
) {
    private val logger = LoggerFactory.getLogger(ReplicationService::class.java)

    private lateinit var walReader: WalReader
    private lateinit var walApplier: WalApplier
    private lateinit var slotManager: SlotManager
    private lateinit var metadataReader: MetadataReader


    private var isRunning = false
    private var eventsProcessed = 0L
    private var lastLsn: String = "0/0"



    /**
     * Инициализация сервиса репликации
     */
    fun initialize() {
        logger.info("Initializing replication service...")

        metadataReader = MetadataReader(sourceDataSource)

        slotManager = SlotManager(sourceDataSource)

        val tables = metadataReader.getAllTablesWithUuidPk()
        slotManager.setupReplicaIdentity(tables)

        walReader = WalReader(sourceDataSource, config)
        walApplier = WalApplier(targetDataSource, mappingService, metadataReader)

        // Регистрируем Gauge для отслеживания replication lag
        MetricsService.registerReplicationLagSupplier { getLag() }

        // Создаём replication slot
        if (!slotManager.slotExists(config.slotName)) {
            slotManager.createSlot(config.slotName, temporary = config.temporary)
            logger.info("Created replication slot: ${config.slotName}")
        }

        // Инициализируем читатель
        walReader.initialize(config.slotName)

        isRunning = true
        eventsProcessed = 0
        logger.info("Replication service initialized")
    }

    /**
     * Запуск непрерывной репликации
     */
    fun startReplication() {
        if (!::walReader.isInitialized) {
            initialize()
        }

        logger.info("Starting continuous replication...")

        try {
            while (isRunning) {
                // Читаем пакет событий
                val events = walReader.readBatch(config.batchSize)

                if (events.isNotEmpty()) {
                    logger.debug("Read ${events.size} WAL events")

                    // Применяем события
                    val results = walApplier.applyBatch(events)

                    // Обрабатываем результаты
                    results.forEach { result ->
                        if (result.success) {
                            eventsProcessed++
                            lastLsn = result.lsn
                            // Increment counter для примененных WAL событий
                            MetricsService.replicationEventsAppliedCounter.increment()
                        } else {
                            logger.error("Failed to apply event: ${result.errorMessage}")
                        }
                    }

                    // Подтверждаем LSN
                    walReader.confirmLsn(lastLsn)
                }

                // Небольшая пауза чтобы не нагружать CPU
                Thread.sleep(config.pollIntervalMs)
            }
        } catch (e: Exception) {
            logger.error("Replication error: ${e.message}", e)
            throw e
        }
    }

    /**
     * Синхронизация дельты (однократная)
     */
    fun syncDelta(): DeltaSyncResult {
        if (!::walReader.isInitialized) {
            initialize()
        }

        logger.info("Starting delta sync...")

        val startTime = LocalDateTime.now()
        val events = mutableListOf<WalEvent>()
        var timeout = 5000L // 5 секунд на чтение

        // Читаем доступные события
        while (timeout > 0) {
            val batch = walReader.readBatch(config.batchSize)
            if (batch.isNotEmpty()) {
                events.addAll(batch)
                timeout = 5000L // Сбрасываем таймаут при получении данных
            } else {
                timeout -= config.pollIntervalMs
            }
        }

        logger.info("Read ${events.size} events for delta sync")

        // Применяем события
        val results = walApplier.applyBatch(events)

        // Считаем статистику
        val successCount = results.count { it.success }
        val failedCount = results.size - successCount

        // Подтверждаем LSN
        results.filter { it.success }.lastOrNull()?.let {
            walReader.confirmLsn(it.lsn)
            lastLsn = it.lsn
        }

        return DeltaSyncResult(
            eventsRead = events.size,
            eventsApplied = successCount,
            eventsFailed = failedCount,
            duration = java.time.Duration.between(startTime, LocalDateTime.now()).toMillis(),
            lastLsn = lastLsn
        )
    }

    /**
     * Получение lag в байтах
     */
    fun getLag(): Long {
        return slotManager.calculateLag(config.slotName)
    }

    /**
     * Получение lag в человекочитаемом формате
     */
    fun getLagPretty(): String {
        val lag = getLag()
        return when {
            lag < 1024 -> "$lag bytes"
            lag < 1024 * 1024 -> "${lag / 1024} KB"
            else -> "${lag / (1024 * 1024)} MB"
        }
    }

    /**
     * Остановка репликации
     */
    fun stop() {
        logger.info("Stopping replication service...")
        isRunning = false
        walReader.stop()
        logger.info("Replication service stopped. Total events processed: $eventsProcessed")
    }

    /**
     * Очистка ресурсов
     */
    fun close() {
        stop()
    }
}

/**
 * Результат синхронизации дельты
 */
data class DeltaSyncResult(
    val eventsRead: Int,
    val eventsApplied: Int,
    val eventsFailed: Int,
    val duration: Long,
    val lastLsn: String
) {
    val successRate: Double
        get() = if (eventsRead > 0) eventsApplied.toDouble() / eventsRead * 100 else 0.0
}
