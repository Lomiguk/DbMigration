package tools

import java.time.LocalDateTime

data class RunConfiguration(
    val totalRecords: Int,
    val batchSize: Int,
    val cacheLimit: Int,
    val syncStrategy: String = "MEMORY_FILTERED",
    val dbVersion: String = "PostgreSQL 15",
    val timestamp: LocalDateTime = LocalDateTime.now()
)

data class TableMetrics(
    val tableName: String,
    val rowCount: Long,
    val uuidIndexSizeMB: Double,
    val intIndexSizeMB: Double,
    val migrationTimeMs: Long
)