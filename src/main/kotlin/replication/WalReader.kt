package replication

import com.zaxxer.hikari.HikariDataSource
import org.postgresql.PGConnection
import org.postgresql.replication.PGReplicationStream
import org.postgresql.util.PSQLException
import org.slf4j.LoggerFactory
import java.nio.ByteBuffer
import java.sql.Connection
import java.sql.DriverManager
import java.time.LocalDateTime
import java.util.Properties
import java.util.UUID
import javax.sql.DataSource

private val uuidRegex = Regex("^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$")

/**
 * Читатель WAL журнала PostgreSQL
 * Использует logical replication для чтения изменений
 */
class WalReader(
    private val sourceDataSource: DataSource,
    private val config: ReplicationConfig = ReplicationConfig()
) {

    private val logger = LoggerFactory.getLogger(WalReader::class.java)
    private var replicationStream: PGReplicationStream? = null
    private var isRunning = false

    private val relationMap = mutableMapOf<Int, String>()

    private val relationColumns = mutableMapOf<Int, List<String>>()

    private var replConnection: Connection? = null

    /**
     * Инициализация replication подключения
     */
    fun initialize(slotName: String): PGConnection {
        // Достаем параметры из пула
        val hikariDs = sourceDataSource as HikariDataSource

        // Добавляем критически важный флаг репликации
        val props = Properties().apply {
            setProperty("user", hikariDs.username)
            setProperty("password", hikariDs.password)
            setProperty("assumeMinServerVersion", "9.4")
            setProperty("replication", "database")
        }

        // Создаем прямое (не пуловое) соединение
        val sqlConn = DriverManager.getConnection(hikariDs.jdbcUrl, props)
        val replConn = sqlConn.unwrap(PGConnection::class.java)

        this.replConnection = sqlConn

        // Создаём replication slot если не существует
        val slotManager = SlotManager(sourceDataSource)
        if (!slotManager.slotExists(slotName)) {
            slotManager.createSlot(slotName, temporary = config.temporary)
        }

        // Открываем replication поток через наше выделенное соединение
        replicationStream = replConn
            .replicationAPI
            .replicationStream()
            .logical()
            .withSlotName(slotName)
            .withSlotOption("publication_names", config.publicationName)
            .withSlotOption("proto_version", "1")
            .withStatusInterval(10, java.util.concurrent.TimeUnit.SECONDS)
            .start()

        isRunning = true
        logger.info("WAL reader initialized with slot: $slotName")

        return replConn
    }

    /**
     * Чтение следующего события из WAL
     */
    fun readNextEvent(): WalEvent? {
        val stream = replicationStream ?: return null

        try {
            val byteBuffer = stream.readPending()

            if (byteBuffer == null) {
                // Нет доступных данных, ждём
                Thread.sleep(config.pollIntervalMs)
                return null
            }

            return parseWalEvent(byteBuffer, stream.lastReceiveLSN.toString())
        } catch (e: PSQLException) {
            // Если ошибка произошла во время остановки приложения (Ctrl+C),
            // просто тихо выходим, это нормальное поведение.
            if (!isRunning) {
                logger.error("Error while reading event", e)
                return null
            }
            // Иначе пробрасываем ошибку дальше
            throw e
        } catch (e: Exception) {
            logger.error("Error reading WAL event: ${e.message}", e)
            throw e
        }
    }

    /**
     * Чтение пакета событий
     */
    fun readBatch(batchSize: Int): List<WalEvent> {
        val events = mutableListOf<WalEvent>()
        val startTime = System.currentTimeMillis()

        while (events.size < batchSize && isRunning) {
            val event = readNextEvent()
            if (event != null) {
                events.add(event)
            }

            // Таймаут для предотвращения бесконечного ожидания
            if (System.currentTimeMillis() - startTime > 5000) {
                break
            }
        }

        return events
    }

    /**
     * Парсинг WAL события из ByteBuffer
     */
    private fun parseWalEvent(buffer: ByteBuffer, commitLsn: String): WalEvent? {
        val messageType = buffer.get().toInt().toChar()
        return when (messageType) {
            'R' -> { // Relation message (содержит имя таблицы)
                parseRelation(buffer)
                null // Это служебное сообщение, не отправляем его дальше
            }
            'I' -> parseInsert(buffer, commitLsn)
            'U' -> parseUpdate(buffer, commitLsn)
            'D' -> parseDelete(buffer, commitLsn)
            else -> null // Игнорируем B (Begin), C (Commit) и другие
        }
    }

    private fun parseRelation(buffer: ByteBuffer) {
        val relationId = buffer.int
        readNullTerminatedString(buffer) // Пропускаем namespace
        val tableName = readNullTerminatedString(buffer)
        buffer.get() // Пропускаем replicaIdentity
        val numColumns = buffer.short

        val columns = mutableListOf<String>()
        (0 until numColumns).forEach { _ ->
            buffer.get() // Пропускаем flags
            val colName = readNullTerminatedString(buffer)
            columns.add(colName) // Сохраняем имя колонки
            buffer.int // Пропускаем dataType
            buffer.int // Пропускаем typmod
        }
        // Сохраняем маппинг
        relationMap[relationId] = tableName
        relationColumns[relationId] = columns
    }

    private fun readNullTerminatedString(buffer: ByteBuffer): String {
        val bytes = mutableListOf<Byte>()
        while (buffer.hasRemaining()) {
            val b = buffer.get()
            if (b == 0.toByte()) break
            bytes.add(b)
        }
        return String(bytes.toByteArray(), Charsets.UTF_8)
    }

    /**
     * Парсинг INSERT события
     */
    private fun parseInsert(buffer: ByteBuffer, commitLsn: String): WalInsertEvent? {
        val relationId = buffer.int
        val tableName = relationMap[relationId] ?: return null
        val columns = relationColumns[relationId] ?: emptyList() // Достаем имена колонок

        buffer.get() // Пропускаем tupleType ('N')
        val tuple = parseTuple(buffer, columns) // Передаем колонки

        return WalInsertEvent(tableName, commitLsn, LocalDateTime.now(), tuple)
    }

    /**
     * Парсинг UPDATE события
     */
    private fun parseUpdate(buffer: ByteBuffer, commitLsn: String): WalUpdateEvent? {
        val relationId = buffer.int
        val tableName = relationMap[relationId] ?: return null
        val columns = relationColumns[relationId] ?: emptyList() // Достаем имена колонок

        var tupleType = buffer.get().toInt().toChar()
        var oldTuple: Map<String, Any?>? = null

        if (tupleType == 'O' || tupleType == 'K') {
            oldTuple = parseTuple(buffer, columns) // Передаем колонки
            tupleType = buffer.get().toInt().toChar()
        }

        val newTuple = if (tupleType == 'N') parseTuple(buffer, columns) else emptyMap()

        return WalUpdateEvent(tableName, commitLsn, LocalDateTime.now(), oldTuple, newTuple)
    }

    /**
     * Парсинг DELETE события
     */
    private fun parseDelete(buffer: ByteBuffer, commitLsn: String): WalDeleteEvent? {
        val relationId = buffer.int
        val tableName = relationMap[relationId] ?: return null
        val columns = relationColumns[relationId] ?: emptyList() // Достаем имена колонок

        buffer.get() // Пропускаем tupleType
        val oldTuple = parseTuple(buffer, columns) // Передаем колонки

        return WalDeleteEvent(tableName, commitLsn, LocalDateTime.now(), oldTuple)
    }

    /**
     * Парсинг кортежа (набора колонок)
     */
    private fun parseTuple(buffer: ByteBuffer, columns: List<String>): Map<String, Any?> {
        val tuple = mutableMapOf<String, Any?>()
        val numColumns = buffer.short

        for (i in 0 until numColumns) {
            val kind = buffer.get().toInt().toChar()

            // Если есть имя колонки, используем его, иначе fallback
            val colName = if (i < columns.size) columns[i] else "column_$i"

            when (kind) {
                'n' -> {
                    tuple[colName] = null
                }
                'u' -> {
                    tuple[colName] = null
                }
                else -> {
                    val length = buffer.int
                    val valueBytes = ByteArray(length).also { buffer.get(it) }
                    val value = String(valueBytes, Charsets.UTF_8)
                    tuple[colName] = parseValue(value)
                }
            }
        }

        return tuple
    }

    /**
     * Парсинг значения из строкового представления
     */
    private fun parseValue(value: String): Any? {
        return when {
            value.equals("null", ignoreCase = true) -> null
            value.startsWith("'") && value.endsWith("'") -> {
                val cleanValue = value.removeSurrounding("'")
                // Проверяем, не спрятан ли UUID внутри кавычек
                if (cleanValue.matches(uuidRegex)) UUID.fromString(cleanValue) else cleanValue
            }
            value.matches(Regex("-?\\d+")) -> value.toLong()
            value.matches(Regex("-?\\d+\\.\\d+")) -> value.toDouble()
            value.equals("t", ignoreCase = true) -> true
            value.equals("f", ignoreCase = true) -> false
            value.matches(uuidRegex) -> UUID.fromString(value)
            else -> value
        }
    }

    /**
     * Подтверждение обработки LSN
     */
    fun confirmLsn(lsn: String) {
        // Упрощённая реализация - просто сохраняем последний LSN
        // В production нужно использовать правильный API PostgreSQL
        lastAppliedLsn = lsn
    }

    private var lastAppliedLsn: String = "0/0"

    /**
     * Остановка чтения
     */
    fun stop() {
        isRunning = false
        logger.info("Stopping WAL reader...")
        try {
            replicationStream?.close()
        } catch (e: Exception) {
            logger.debug("Stream gracefully closed during shutdown: ${e.message}")
        } finally {
            replicationStream = null

            try {
                replConnection?.close()
            } catch (_: Exception) {}
            replConnection = null

            logger.info("WAL reader stopped")
        }
    }

}
