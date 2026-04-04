package state

import java.sql.Connection
import java.sql.PreparedStatement
import java.time.LocalDateTime
import java.util.*
import javax.sql.DataSource

/**
 * Репозиторий для хранения и получения состояния миграции
 */
class StateRepository(private val dataSource: DataSource) {

    init {
        createStateTable()
    }

    /**
     * Создание таблицы состояния миграции
     */
    fun createStateTable() {
        dataSource.connection.use { conn ->
            conn.createStatement().execute("""
                CREATE TABLE IF NOT EXISTS migration_state (
                    migration_id VARCHAR(100) NOT NULL,
                    table_name VARCHAR(100) NOT NULL,
                    status VARCHAR(50) NOT NULL,
                    processed_rows BIGINT DEFAULT 0,
                    total_rows BIGINT DEFAULT 0,
                    last_processed_uuid VARCHAR(100),
                    last_batch_number INT DEFAULT 0,
                    started_at TIMESTAMP,
                    completed_at TIMESTAMP,
                    error_message TEXT,
                    retry_count INT DEFAULT 0,
                    updated_at TIMESTAMP DEFAULT NOW(),
                    PRIMARY KEY (migration_id, table_name)
                );
                
                CREATE INDEX IF NOT EXISTS idx_migration_state_status 
                ON migration_state(status);
                
                CREATE INDEX IF NOT EXISTS idx_migration_state_updated 
                ON migration_state(updated_at);
            """.trimIndent())
        }
    }

    /**
     * Инициализация новой миграции
     */
    fun initMigration(migrationId: String, tables: List<String>, sourceDb: String, targetDb: String) {
        dataSource.connection.use { conn ->
            conn.autoCommit = false
            try {
                // Очищаем предыдущее состояние для этой миграции
                conn.prepareStatement(
                    "DELETE FROM migration_state WHERE migration_id = ?"
                ).use { pstmt ->
                    pstmt.setString(1, migrationId)
                    pstmt.executeUpdate()
                }

                // Добавляем состояние для каждой таблицы
                val pstmt = conn.prepareStatement("""
                    INSERT INTO migration_state 
                    (migration_id, table_name, status, started_at)
                    VALUES (?, ?, ?, ?)
                """)

                tables.forEach { table ->
                    pstmt.setString(1, migrationId)
                    pstmt.setString(2, table)
                    pstmt.setString(3, MigrationStatus.PENDING.name)
                    pstmt.setTimestamp(4, null)
                    pstmt.addBatch()
                }
                pstmt.executeBatch()
                conn.commit()
            } catch (e: Exception) {
                conn.rollback()
                throw e
            }
        }
    }

    /**
     * Начало миграции таблицы
     */
    fun startTable(migrationId: String, tableName: String, totalRows: Long = 0) {
        dataSource.connection.use { conn ->
            conn.prepareStatement("""
                UPDATE migration_state 
                SET status = ?, 
                    started_at = NOW(), 
                    total_rows = ?,
                    updated_at = NOW()
                WHERE migration_id = ? AND table_name = ?
            """).use { pstmt ->
                pstmt.setString(1, MigrationStatus.IN_PROGRESS.name)
                pstmt.setLong(2, totalRows)
                pstmt.setString(3, migrationId)
                pstmt.setString(4, tableName)
                pstmt.executeUpdate()
            }
        }
    }

    /**
     * Сохранение прогресса после батча
     */
    fun saveProgress(
        migrationId: String,
        tableName: String,
        processedRows: Long,
        lastUuid: UUID? = null,
        batchNumber: Long
    ) {
        dataSource.connection.use { conn ->
            conn.prepareStatement("""
                UPDATE migration_state 
                SET processed_rows = ?, 
                    last_processed_uuid = ?,
                    last_batch_number = ?,
                    updated_at = NOW()
                WHERE migration_id = ? AND table_name = ?
            """).use { pstmt ->
                pstmt.setLong(1, processedRows)
                pstmt.setString(2, lastUuid?.toString())
                pstmt.setLong(3, batchNumber)
                pstmt.setString(4, migrationId)
                pstmt.setString(5, tableName)
                pstmt.executeUpdate()
            }
        }
    }

    /**
     * Завершение миграции таблицы
     */
    fun completeTable(migrationId: String, tableName: String, totalRows: Long) {
        dataSource.connection.use { conn ->
            conn.prepareStatement("""
                UPDATE migration_state 
                SET status = ?, 
                    completed_at = NOW(),
                    processed_rows = ?,
                    total_rows = ?,
                    updated_at = NOW()
                WHERE migration_id = ? AND table_name = ?
            """).use { pstmt ->
                pstmt.setString(1, MigrationStatus.COMPLETED.name)
                pstmt.setLong(2, totalRows)
                pstmt.setLong(3, totalRows)
                pstmt.setString(4, migrationId)
                pstmt.setString(5, tableName)
                pstmt.executeUpdate()
            }
        }
    }

    /**
     * Пометка таблицы как неудачной
     */
    fun failTable(
        migrationId: String,
        tableName: String,
        errorMessage: String,
        retryCount: Int = 0
    ) {
        dataSource.connection.use { conn ->
            conn.prepareStatement("""
                UPDATE migration_state 
                SET status = ?, 
                    error_message = ?,
                    retry_count = ?,
                    updated_at = NOW()
                WHERE migration_id = ? AND table_name = ?
            """).use { pstmt ->
                pstmt.setString(1, MigrationStatus.FAILED.name)
                pstmt.setString(2, errorMessage)
                pstmt.setInt(3, retryCount)
                pstmt.setString(4, migrationId)
                pstmt.setString(5, tableName)
                pstmt.executeUpdate()
            }
        }
    }

    /**
     * Получение состояния для таблицы
     */
    fun getTableState(migrationId: String, tableName: String): TableMigrationState? {
        dataSource.connection.use { conn ->
            conn.prepareStatement("""
                SELECT * FROM migration_state 
                WHERE migration_id = ? AND table_name = ?
            """).use { pstmt ->
                pstmt.setString(1, migrationId)
                pstmt.setString(2, tableName)
                val rs = pstmt.executeQuery()
                return if (rs.next()) {
                    mapResultSetToState(rs)
                } else {
                    null
                }
            }
        }
    }

    /**
     * Получение всех состояний для миграции
     */
    fun getAllTableStates(migrationId: String): List<TableMigrationState> {
        val states = mutableListOf<TableMigrationState>()
        dataSource.connection.use { conn ->
            conn.prepareStatement("""
                SELECT * FROM migration_state 
                WHERE migration_id = ?
                ORDER BY 
                    CASE status 
                        WHEN 'COMPLETED' THEN 1 
                        WHEN 'IN_PROGRESS' THEN 2 
                        WHEN 'FAILED' THEN 3 
                        WHEN 'RETRYING' THEN 4 
                        ELSE 5 
                    END,
                    table_name
            """).use { pstmt ->
                pstmt.setString(1, migrationId)
                val rs = pstmt.executeQuery()
                while (rs.next()) {
                    states.add(mapResultSetToState(rs))
                }
            }
        }
        return states
    }

    /**
     * Получение контекста для возобновления
     */
    fun getResumeContext(migrationId: String): ResumeContext {
        val states = getAllTableStates(migrationId)
        return ResumeContext(
            migrationId = migrationId,
            lastCheckpoint = states.maxByOrNull { it.updatedAt ?: LocalDateTime.MIN }?.updatedAt,
            failedTables = states.filter { it.status == MigrationStatus.FAILED }.map { it.tableName },
            pendingTables = states.filter { 
                it.status == MigrationStatus.PENDING || it.status == MigrationStatus.IN_PROGRESS 
            }.map { it.tableName },
            completedTables = states.filter { it.status == MigrationStatus.COMPLETED }.map { it.tableName }
        )
    }

    /**
     * Проверка существования миграции
     */
    fun migrationExists(migrationId: String): Boolean {
        dataSource.connection.use { conn ->
            conn.prepareStatement("""
                SELECT COUNT(*) FROM migration_state WHERE migration_id = ?
            """).use { pstmt ->
                pstmt.setString(1, migrationId)
                val rs = pstmt.executeQuery()
                return rs.next() && rs.getInt(1) > 0
            }
        }
    }

    /**
     * Получение списка успешно мигрированных таблиц
     */
    fun getCompletedTables(migrationId: String): List<String> {
        val tables = mutableListOf<String>()
        dataSource.connection.use { conn ->
            conn.prepareStatement("""
                SELECT table_name FROM migration_state 
                WHERE migration_id = ? AND status = ?
                ORDER BY completed_at
            """).use { pstmt ->
                pstmt.setString(1, migrationId)
                pstmt.setString(2, MigrationStatus.COMPLETED.name)
                val rs = pstmt.executeQuery()
                while (rs.next()) {
                    tables.add(rs.getString("table_name"))
                }
            }
        }
        return tables
    }

    /**
     * Пометка таблицы как откатанной
     */
    fun markRolledBack(migrationId: String, tableName: String) {
        dataSource.connection.use { conn ->
            conn.prepareStatement("""
                UPDATE migration_state 
                SET status = ?, 
                    completed_at = NULL,
                    processed_rows = 0,
                    error_message = ?,
                    updated_at = NOW()
                WHERE migration_id = ? AND table_name = ?
            """).use { pstmt ->
                pstmt.setString(1, MigrationStatus.PENDING.name)
                pstmt.setString(2, "Rolled back")
                pstmt.setString(3, migrationId)
                pstmt.setString(4, tableName)
                pstmt.executeUpdate()
            }
        }
    }

    /**
     * Удаление состояния для таблицы (полная очистка)
     */
    fun removeTableState(migrationId: String, tableName: String) {
        dataSource.connection.use { conn ->
            conn.prepareStatement("""
                DELETE FROM migration_state 
                WHERE migration_id = ? AND table_name = ?
            """).use { pstmt ->
                pstmt.setString(1, migrationId)
                pstmt.setString(2, tableName)
                pstmt.executeUpdate()
            }
        }
    }

    /**
     * Получение последней активной миграции
     */
    fun getLastActiveMigration(): String? {
        dataSource.connection.use { conn ->
            conn.prepareStatement("""
                SELECT migration_id FROM migration_state 
                WHERE status IN (?, ?)
                ORDER BY updated_at DESC
                LIMIT 1
            """).use { pstmt ->
                pstmt.setString(1, MigrationStatus.IN_PROGRESS.name)
                pstmt.setString(2, MigrationStatus.RETRYING.name)
                val rs = pstmt.executeQuery()
                return if (rs.next()) rs.getString(1) else null
            }
        }
    }

    /**
     * Генерация уникального ID миграции
     */
    fun generateMigrationId(): String {
        return "migration_${LocalDateTime.now().toString().replace(Regex("[^0-9]"), "")}"
    }

    private fun mapResultSetToState(rs: java.sql.ResultSet): TableMigrationState {
        return TableMigrationState(
            tableName = rs.getString("table_name"),
            status = MigrationStatus.valueOf(rs.getString("status")),
            processedRows = rs.getLong("processed_rows"),
            totalRows = rs.getLong("total_rows"),
            lastProcessedUuid = rs.getString("last_processed_uuid"),
            lastBatchNumber = rs.getInt("last_batch_number"),
            startedAt = rs.getTimestamp("started_at")?.toLocalDateTime(),
            completedAt = rs.getTimestamp("completed_at")?.toLocalDateTime(),
            errorMessage = rs.getString("error_message"),
            retryCount = rs.getInt("retry_count"),
            updatedAt = rs.getTimestamp("updated_at")?.toLocalDateTime()
        )
    }
}
