package logging

import engine.MappingServiceBase
import io.micrometer.core.instrument.Counter
import io.micrometer.core.instrument.Gauge
import io.micrometer.core.instrument.Timer
import io.micrometer.core.instrument.binder.jvm.JvmGcMetrics
import io.micrometer.core.instrument.binder.jvm.JvmMemoryMetrics
import io.micrometer.core.instrument.binder.system.ProcessorMetrics
import io.micrometer.prometheusmetrics.PrometheusConfig
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry
import io.prometheus.metrics.exporter.pushgateway.PushGateway
import org.slf4j.LoggerFactory
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/**
 * Центральный сервис наблюдаемости на базе Micrometer + Prometheus.
 */
object MetricsService {

    private val logger = LoggerFactory.getLogger(MetricsService::class.java)

    /** Единственный реестр метрик для всего приложения */
    val registry: PrometheusMeterRegistry = PrometheusMeterRegistry(PrometheusConfig.DEFAULT)

    private const val JOB_NAME = "migration_tool"

    private val pushGateway = PushGateway.builder()
        .address("localhost:9091")
        .job(JOB_NAME)
        .registry(registry.prometheusRegistry)
        .build()

    // Фоновый планировщик для отправки метрик
    private var scheduler: ScheduledExecutorService? = null
    private var isInitialized = false

    init {
        logger.info("MetricsService initialized (Pushgateway mode)")

        JvmMemoryMetrics().bindTo(registry)
        JvmGcMetrics().bindTo(registry)
        ProcessorMetrics().bindTo(registry)
    }

    private val _migrationRowsTotal = mutableMapOf<String, Counter>()

    fun getMigrationRowsCounter(tableName: String): Counter =
        _migrationRowsTotal.getOrPut(tableName) {
            Counter.builder("migration_rows_total")
                .description("Total rows migrated")
                .tag("table", tableName)
                .register(registry)
        }

    private val _migrationBatchDuration = mutableMapOf<String, Timer>()

    fun getMigrationBatchTimer(tableName: String, operation: String): Timer {
        val key = "$tableName:$operation"
        return _migrationBatchDuration.getOrPut(key) {
            Timer.builder("migration.batch.duration.seconds")
                .tag("table", tableName)
                .tag("operation", operation)
                .description("Duration of batch operations")
                .register(registry)
        }
    }

    private var _mappingCacheSizeSupplier: (() -> Number)? = null

    fun registerMappingCacheSizeSupplier(supplier: () -> Number) {
        _mappingCacheSizeSupplier = supplier
        Gauge.builder("mapping.cache.size", supplier)
            .description("Current size of the UUID -> BIGINT cache")
            .register(registry)
    }

    private var _replicationLagSupplier: (() -> Number)? = null

    fun registerReplicationLagSupplier(supplier: () -> Number) {
        _replicationLagSupplier = supplier
        Gauge.builder("replication.lag.bytes", supplier)
            .description("WAL replication lag in bytes")
            .register(registry)
    }

    val replicationEventsAppliedCounter: Counter by lazy {
        Counter.builder("replication.events.applied.total")
            .description("Total number of WAL events applied")
            .register(registry)
    }

    /**
     * Запускает фоновый процесс отправки метрик каждые 5 секунд.
     */
    fun init() {
        if (isInitialized) return

        logger.info("Starting background metrics publisher (every 5s)...")

        scheduler = Executors.newSingleThreadScheduledExecutor { runnable ->
            Thread(runnable, "metrics-publisher").apply {
                isDaemon = true
            }
        }

        // Пушим метрики в фоне. Флаг silent=true, чтобы не спамить в консоль
        scheduler?.scheduleAtFixedRate({
            pushMetrics(silent = true)
        }, 5, 5, TimeUnit.SECONDS)

        isInitialized = true
    }

    /**
     * Выталкивает накопленные метрики в Pushgateway.
     */
    fun pushMetrics(silent: Boolean = false) {
        try {
            pushGateway.pushAdd()
            if (!silent) logger.info("Metrics successfully pushed to Pushgateway!")
        } catch (e: Exception) {
            if (!silent) logger.error("Failed to push metrics to Pushgateway: ${e.message}")
        }
    }

    /**
     * Останавливает фоновый поток и делает финальный пуш.
     */
    fun shutdown() {
        if (!isInitialized) return

        logger.info("Shutting down MetricsService...")
        scheduler?.shutdown()
        try {
            scheduler?.awaitTermination(2, TimeUnit.SECONDS)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        }

        // Обязательный финальный пуш перед выходом
        pushMetrics(silent = false)

        scheduler = null
        isInitialized = false
    }

    fun getReplicationEventsCounter(): Counter =
        registry.counter("replication_events_applied_total")

    fun registerReplicationLagGauge(supplier: () -> Double) {
        Gauge.builder("replication_lag_bytes") {
            try {
                supplier()
            } catch (e: java.sql.SQLException) {
                if (e.message?.contains("has been closed") == true) {
                    0.0
                } else {
                    throw e
                }
            }
        }
            .description("Replication lag in bytes")
            .register(registry)
    }

    fun registerCacheMetrics(mappingService: MappingServiceBase) {
        // Регистрируем размер кэша
        Gauge.builder("mapping_cache_size", mappingService) {
            (it.getCacheStats()["cache_size"] as Long).toDouble()
        }.register(registry)

        // Регистрируем Hit Rate (коэффициент попаданий)
        Gauge.builder("mapping_cache_hit_rate", mappingService) {
            it.getCacheStats()["hit_rate"] as Double
        }.register(registry)

        // Регистрируем количество вытесненных элементов (защита от OOM)
        Gauge.builder("mapping_cache_evictions", mappingService) {
            (it.getCacheStats()["eviction_count"] as Long).toDouble()
        }.register(registry)
    }
}