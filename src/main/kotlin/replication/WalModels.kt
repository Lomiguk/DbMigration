package replication

import java.time.LocalDateTime

/**
 * Событие изменения данных из WAL
 */
sealed class WalEvent {
    abstract val tableName: String
    abstract val commitLsn: String
    abstract val timestamp: LocalDateTime
}

/**
 * INSERT событие
 */
data class WalInsertEvent(
    override val tableName: String,
    override val commitLsn: String,
    override val timestamp: LocalDateTime,
    val newTuple: Map<String, Any?>
) : WalEvent()

/**
 * UPDATE событие
 */
data class WalUpdateEvent(
    override val tableName: String,
    override val commitLsn: String,
    override val timestamp: LocalDateTime,
    val oldTuple: Map<String, Any?>?,
    val newTuple: Map<String, Any?>
) : WalEvent()

/**
 * DELETE событие
 */
data class WalDeleteEvent(
    override val tableName: String,
    override val commitLsn: String,
    override val timestamp: LocalDateTime,
    val oldTuple: Map<String, Any?>
) : WalEvent()

/**
 * Конфигурация репликации
 */
data class ReplicationConfig(
    val slotName: String = "dbmigration_slot",
    val temporary: Boolean = false,
    val pluginName: String = "pgoutput",
    val publicationName: String = "dbmigration_publication",
    val batchSize: Int = 100,
    val pollIntervalMs: Long = 100
)

/**
 * Результат обработки WAL события
 */
data class WalProcessEvent(
    val success: Boolean,
    val tableName: String,
    val eventType: String,
    val lsn: String,
    val errorMessage: String? = null,
    val rowsAffected: Int = 0
)
