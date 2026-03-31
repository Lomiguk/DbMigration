package replication

import org.postgresql.PGConnection
import org.postgresql.replication.PGReplicationStream
import org.postgresql.replication.ReplicationType
import org.slf4j.LoggerFactory
import java.nio.ByteBuffer
import java.sql.Connection
import java.time.LocalDateTime
import java.util.*
import javax.sql.DataSource

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

    /**
     * Инициализация replication подключения
     */
    fun initialize(slotName: String): PGConnection {
        val conn = sourceDataSource.connection as PGConnection

        // Создаём replication slot если не существует
        val slotManager = SlotManager(sourceDataSource)
        if (!slotManager.slotExists(slotName)) {
            slotManager.createSlot(slotName, temporary = config.temporary)
        }

        // Открываем replication поток
        replicationStream = conn
            .getReplicationAPI()
            .replicationStream()
            .logical()
            .withSlotName(slotName)
            .withSlotOption("publication_names", config.publicationName)
            .withSlotOption("proto_version", "1")
            .start()

        isRunning = true
        logger.info("WAL reader initialized with slot: $slotName")

        return conn
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
    private fun parseWalEvent(buffer: ByteBuffer, lsn: String): WalEvent? {
        // Пропускаем заголовок сообщения
        // Формат: https://www.postgresql.org/docs/current/protocol-logicalrep-message-formats.html

        if (buffer.remaining() < 1) return null

        val messageType = buffer.get().toInt().toChar()

        return when (messageType) {
            'I' -> parseInsert(buffer, lsn)
            'U' -> parseUpdate(buffer, lsn)
            'D' -> parseDelete(buffer, lsn)
            'B' -> null // Begin transaction
            'C' -> null // Commit transaction
            'R' -> null // Relation
            'T' -> null // Type
            'Y' -> null // Origin
            else -> {
                logger.debug("Unknown message type: $messageType")
                null
            }
        }
    }

    /**
     * Парсинг INSERT события
     */
    private fun parseInsert(buffer: ByteBuffer, lsn: String): WalInsertEvent {
        val tableName = parseTableName(buffer)
        val tuple = parseTuple(buffer)

        return WalInsertEvent(
            tableName = tableName,
            commitLsn = lsn,
            timestamp = LocalDateTime.now(),
            newTuple = tuple
        )
    }

    /**
     * Парсинг UPDATE события
     */
    private fun parseUpdate(buffer: ByteBuffer, lsn: String): WalUpdateEvent {
        val tableName = parseTableName(buffer)

        // Old tuple (может отсутствовать в зависимости от replica identity)
        val oldTuple = if (buffer.get().toInt().toChar() == 'K' || buffer.get().toInt().toChar() == 'O') {
            parseTuple(buffer)
        } else {
            null
        }

        // New tuple
        buffer.get() // 'N'
        val newTuple = parseTuple(buffer)

        return WalUpdateEvent(
            tableName = tableName,
            commitLsn = lsn,
            timestamp = LocalDateTime.now(),
            oldTuple = oldTuple,
            newTuple = newTuple
        )
    }

    /**
     * Парсинг DELETE события
     */
    private fun parseDelete(buffer: ByteBuffer, lsn: String): WalDeleteEvent {
        val tableName = parseTableName(buffer)

        // Old tuple
        buffer.get() // 'K' или 'O'
        val oldTuple = parseTuple(buffer)

        return WalDeleteEvent(
            tableName = tableName,
            commitLsn = lsn,
            timestamp = LocalDateTime.now(),
            oldTuple = oldTuple
        )
    }

    /**
     * Парсинг имени таблицы
     */
    private fun parseTableName(buffer: ByteBuffer): String {
        // Пропускаем relation ID (4 байта)
        buffer.int

        // Namespace (schema)
        val schemaLength = buffer.short.toInt()
        val schema = ByteArray(schemaLength).also { buffer.get(it) }.toString(Charsets.UTF_8)

        // Table name
        val tableLength = buffer.short.toInt()
        val table = ByteArray(tableLength).also { buffer.get(it) }.toString(Charsets.UTF_8)

        return "$schema.$table"
    }

    /**
     * Парсинг кортежа (набора колонок)
     */
    private fun parseTuple(buffer: ByteBuffer): Map<String, Any?> {
        val tuple = mutableMapOf<String, Any?>()

        val columnCount = buffer.short.toInt()

        repeat(columnCount) { i ->
            val flags = buffer.get().toInt()
            val isNull = (flags and 0x80) != 0

            if (isNull) {
                // Имя колонки неизвестно из WAL, используем индекс
                tuple["column_$i"] = null
            } else {
                val length = buffer.int
                val valueBytes = ByteArray(length).also { buffer.get(it) }
                val value = String(valueBytes, Charsets.UTF_8)

                // Парсим тип значения
                tuple["column_$i"] = parseValue(value)
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
            value.startsWith("'") && value.endsWith("'") -> value.removeSurrounding("'")
            value.matches(Regex("\\d+")) -> value.toLong()
            value.matches(Regex("\\d+\\.\\d+")) -> value.toDouble()
            value.equals("t", ignoreCase = true) -> true
            value.equals("f", ignoreCase = true) -> false
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
        replicationStream?.close()
        replicationStream = null
        logger.info("WAL reader stopped")
    }

    /**
     * Проверка активности чтения
     */
    fun isRunning(): Boolean = isRunning
}
