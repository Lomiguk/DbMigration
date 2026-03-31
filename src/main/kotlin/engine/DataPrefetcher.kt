package engine

import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import org.slf4j.LoggerFactory
import java.sql.Connection
import java.sql.ResultSet
import java.util.*
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.LinkedBlockingQueue
import javax.sql.DataSource

/**
 * Префетчер для асинхронной загрузки данных
 * Загружает следующие батчи пока текущие обрабатываются
 */
class DataPrefetcher(
    private val sourceDataSource: DataSource,
    private val prefetchBatchCount: Int = 3,
    private val batchSize: Int = 1000
) {
    private val logger = LoggerFactory.getLogger(DataPrefetcher::class.java)
    private val coroutineScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    /**
     * Батч данных
     */
    data class DataBatch(
        val batchNumber: Int,
        val rows: List<Map<String, Any?>>,
        val lastUuid: UUID?,
        val isLast: Boolean
    )

    /**
     * Статистика префетчинга
     */
    data class PrefetchStats(
        val batchesLoaded: Int,
        val totalRows: Long,
        val averageLoadTimeMs: Double,
        val prefetchHitRate: Double
    )

    private val loadTimes = mutableListOf<Long>()
    private var prefetchHits = 0
    private var totalRequests = 0

    /**
     * Асинхронная загрузка данных таблицы с префетчингом
     */
    fun prefetchTable(
        tableName: String,
        columns: List<String>
    ): PrefetchChannel {
        val channel = Channel<DataBatch>(prefetchBatchCount)
        val loadTimes = mutableListOf<Long>()

        coroutineScope.launch {
            try {
                sourceDataSource.connection.use { conn ->
                    var batchNumber = 0
                    var lastUuid: UUID? = null
                    var isLast = false

                    while (!isLast) {
                        val startTime = System.currentTimeMillis()

                        val batch = loadBatch(conn, tableName, columns, lastUuid, batchSize)
                        lastUuid = batch.lastUuid
                        isLast = batch.isLast
                        batchNumber++

                        loadTimes.add(System.currentTimeMillis() - startTime)

                        // Отправляем батч в канал
                        channel.send(DataBatch(
                            batchNumber = batchNumber,
                            rows = batch.rows,
                            lastUuid = lastUuid,
                            isLast = isLast
                        ))
                    }
                }
            } catch (e: Exception) {
                logger.error("Error prefetching table $tableName: ${e.message}", e)
                channel.close(e)
            } finally {
                channel.close()
            }
        }

        return PrefetchChannel(channel, loadTimes)
    }

    /**
     * Загрузка одного батча
     */
    private fun loadBatch(
        conn: Connection,
        tableName: String,
        columns: List<String>,
        lastUuid: UUID?,
        batchSize: Int
    ): BatchData {
        val rows = mutableListOf<Map<String, Any?>>()
        var lastUuidInBatch: UUID? = null

        val columnList = columns.joinToString(", ")
        val whereClause = if (lastUuid != null) {
            "WHERE id > '$lastUuid'"
        } else {
            ""
        }

        val sql = """
            SELECT $columnList FROM $tableName $whereClause
            ORDER BY id
            LIMIT $batchSize
        """.trimIndent()

        conn.createStatement().use { stmt ->
            val rs = stmt.executeQuery(sql)

            while (rs.next()) {
                val row = mutableMapOf<String, Any?>()
                columns.forEach { column ->
                    row[column] = rs.getObject(column)
                }
                rows.add(row)

                // Сохраняем последний UUID для следующей итерации
                if (columnList.contains("id")) {
                    lastUuidInBatch = rs.getObject("id") as? UUID
                }
            }
        }

        return BatchData(
            rows = rows,
            lastUuid = lastUuidInBatch,
            isLast = rows.size < batchSize
        )
    }

    data class BatchData(
        val rows: List<Map<String, Any?>>,
        val lastUuid: UUID?,
        val isLast: Boolean
    )

    /**
     * Канал для получения префетченных данных
     */
    class PrefetchChannel(
        private val channel: Channel<DataBatch>,
        private val loadTimes: MutableList<Long>
    ) {
        private var currentBatch: DataBatch? = null

        /**
         * Получение следующего батча
         */
        suspend fun next(): DataBatch? {
            return channel.receiveCatching().getOrNull()
        }

        /**
         * Проверка наличия следующего батча
         */
        fun hasNext(): Boolean {
            return !channel.isClosedForReceive || currentBatch?.isLast == false
        }

        /**
         * Закрытие канала
         */
        fun close() {
            channel.close()
        }

        /**
         * Статистика
         */
        fun getStats(): PrefetchStats {
            val totalRows = loadTimes.size * 1000L // приблизительное значение
            val avgLoadTime = if (loadTimes.isNotEmpty()) {
                loadTimes.average()
            } else {
                0.0
            }

            return PrefetchStats(
                batchesLoaded = loadTimes.size,
                totalRows = totalRows,
                averageLoadTimeMs = avgLoadTime,
                prefetchHitRate = 1.0 // Префетчинг всегда "попадает" так как загружает заранее
            )
        }
    }

    /**
     * Статистика префетчера
     */
    fun getStats(): PrefetchStats {
        val totalRows = loadTimes.size * batchSize.toLong()
        return PrefetchStats(
            batchesLoaded = loadTimes.size,
            totalRows = totalRows,
            averageLoadTimeMs = if (loadTimes.isNotEmpty()) loadTimes.average() else 0.0,
            prefetchHitRate = if (totalRequests > 0) prefetchHits.toDouble() / totalRequests else 0.0
        )
    }

    /**
     * Остановка префетчера
     */
    fun shutdown() {
        coroutineScope.cancel()
    }
}
