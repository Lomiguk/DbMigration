package state

import java.time.LocalDateTime

/**
 * Состояние миграции для одной таблицы
 */
data class TableMigrationState(
    val tableName: String,
    val status: MigrationStatus,
    val processedRows: Long = 0,
    val totalRows: Long = 0,
    val lastProcessedUuid: String? = null,
    val lastBatchNumber: Int = 0,
    val startedAt: LocalDateTime? = null,
    val completedAt: LocalDateTime? = null,
    val errorMessage: String? = null,
    val retryCount: Int = 0,
    val updatedAt: LocalDateTime? = null
)

/**
 * Статус миграции таблицы
 */
enum class MigrationStatus {
    PENDING,      // Ожидает обработки
    IN_PROGRESS,  // В процессе
    COMPLETED,    // Завершена успешно
    FAILED,       // Завершена с ошибкой
    RETRYING      // Повторная попытка
}

/**
 * Контекст для возобновления миграции
 */
data class ResumeContext(
    val migrationId: String,
    val lastCheckpoint: LocalDateTime?,
    val failedTables: List<String>,
    val pendingTables: List<String>,
    val completedTables: List<String>
)
