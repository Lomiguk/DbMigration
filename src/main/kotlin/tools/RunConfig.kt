package tools

import java.time.LocalDateTime

// Параметры конкретного прогона
data class RunConfiguration(
    val totalRecords: Int,
    val batchSize: Int,
    val cacheLimit: Int,
    val dbVersion: String = "PostgreSQL 15",
    val timestamp: LocalDateTime = LocalDateTime.now()
)

// РЕАЛИЗАЦИЯ: Добавьте этот класс для хранения метрик каждой таблицы
data class TableMetrics(
    val tableName: String,
    val rowCount: Long,
    val uuidIndexSizeMB: Double,
    val intIndexSizeMB: Double,
    val migrationTimeMs: Long
)