package rollback

import org.slf4j.LoggerFactory
import state.StateRepository
import java.sql.Connection
import javax.sql.DataSource

/**
 * Сервис для отката миграции (Rollback)
 * Откатывает миграцию отдельных таблиц или всей миграции
 */
class RollbackService(
    private val sourceDataSource: DataSource,
    private val targetDataSource: DataSource,
    private val stateRepository: StateRepository
) {
    private val logger = LoggerFactory.getLogger(RollbackService::class.java)

    /**
     * Результат отката
     */
    data class RollbackResult(
        val tableName: String,
        val success: Boolean,
        val rowsRolledBack: Long,
        val mappingEntriesRemoved: Long,
        val errorMessage: String? = null,
        val duration: Long
    )

    /**
     * Откат миграции одной таблицы
     */
    fun rollbackTable(migrationId: String, tableName: String): RollbackResult {
        logger.info("Starting rollback for table: $tableName")
        val startTime = System.currentTimeMillis()

        try {
            targetDataSource.connection.use { targetConn ->
                sourceDataSource.connection.use { _ ->
                    targetConn.autoCommit = false

                    try {
                        // 1. Очистка target таблицы
                        val rowsDeleted = clearTargetTable(targetConn, tableName)
                        logger.info("Cleared $rowsDeleted rows from target table $tableName")

                        // 2. Удаление записей из migration_mapping
                        val mappingRemoved = removeMappingEntries(tableName)
                        logger.info("Removed $mappingRemoved mapping entries for $tableName")

                        // 3. Обновление состояния
                        stateRepository.markRolledBack(migrationId, tableName)

                        targetConn.commit()

                        logger.info("Rollback completed for $tableName")

                        return RollbackResult(
                            tableName = tableName,
                            success = true,
                            rowsRolledBack = rowsDeleted,
                            mappingEntriesRemoved = mappingRemoved,
                            duration = System.currentTimeMillis() - startTime
                        )
                    } catch (e: Exception) {
                        targetConn.rollback()
                        throw e
                    }
                }
            }
        } catch (e: Exception) {
            logger.error("Rollback failed for $tableName: ${e.message}", e)
            return RollbackResult(
                tableName = tableName,
                success = false,
                rowsRolledBack = 0,
                mappingEntriesRemoved = 0,
                errorMessage = e.message,
                duration = System.currentTimeMillis() - startTime
            )
        }
    }

    /**
     * Откат всех мигрированных таблиц
     */
    fun rollbackAll(migrationId: String): List<RollbackResult> {
        logger.info("Starting full rollback for migration: $migrationId")
        val results = mutableListOf<RollbackResult>()

        // Получаем список успешно мигрированных таблиц в обратном порядке
        val completedTables = stateRepository.getCompletedTables(migrationId).reversed()

        return rollbackTables(completedTables, migrationId, results)
    }

    /**
     * Откат к указанной таблице (все таблицы после неё)
     */
    fun rollbackToTable(migrationId: String, tableName: String): List<RollbackResult> {
        logger.info("Starting rollback to table: $tableName")
        val results = mutableListOf<RollbackResult>()

        // Получаем все мигрированные таблицы
        val completedTables = stateRepository.getCompletedTables(migrationId)

        // Находим индекс таблицы
        val tableIndex = completedTables.indexOf(tableName)
        if (tableIndex == -1) {
            throw IllegalArgumentException("Table $tableName not found in completed tables")
        }

        // Откатываем все таблицы после указанной (включая её)
        val tablesToRollback = completedTables.subList(tableIndex, completedTables.size).reversed()

        return rollbackTables(tablesToRollback, migrationId, results)
    }

    private fun rollbackTables(
        tablesToRollback: List<String>,
        migrationId: String,
        results: MutableList<RollbackResult>
    ): MutableList<RollbackResult> {
        logger.info("Tables to rollback: ${tablesToRollback.joinToString(", ")}")

        tablesToRollback.forEach { table ->
            val result = rollbackTable(migrationId, table)
            results.add(result)

            if (!result.success) {
                logger.error("Rollback failed for $table, stopping")
                return results
            }
        }

        return results
    }

    /**
     * Очистка target таблицы
     */
    private fun clearTargetTable(conn: Connection, tableName: String): Long {
        // Используем TRUNCATE для быстрой очистки
        val rs = conn.createStatement().executeQuery(
            "SELECT COUNT(*) FROM $tableName"
        )
        val count = if (rs.next()) rs.getLong(1) else 0L

        conn.createStatement().execute("TRUNCATE TABLE $tableName CASCADE")

        return count
    }

    /**
     * Удаление записей из migration_mapping
     */
    private fun removeMappingEntries(tableName: String): Long {
        targetDataSource.connection.use { conn ->
            // Считаем количество записей
            val rs = conn.prepareStatement(
                "SELECT COUNT(*) FROM migration_mapping WHERE table_name = ?"
            )
            rs.setString(1, tableName)
            val countRs = rs.executeQuery()
            val count = if (countRs.next()) countRs.getLong(1) else 0L

            // Удаляем записи
            val deleteStmt = conn.prepareStatement(
                "DELETE FROM migration_mapping WHERE table_name = ?"
            )
            deleteStmt.setString(1, tableName)
            deleteStmt.executeUpdate()

            return count
        }
    }

    /**
     * Валидация после отката
     */
    fun validateRollback(tableName: String): RollbackValidationResult {
        val issues = mutableListOf<String>()

        // Проверяем что target таблица пуста
        val targetCount = getRowCount(targetDataSource, tableName)
        if (targetCount > 0) {
            issues.add("Target table is not empty: $targetCount rows")
        }

        // Проверяем что mapping пуст для этой таблицы
        val mappingCount = getMappingCount(tableName)
        if (mappingCount > 0) {
            issues.add("Mapping table has entries: $mappingCount rows")
        }

        // Проверяем что source таблица не пуста
        val sourceCount = getRowCount(sourceDataSource, tableName)
        if (sourceCount == 0L) {
            issues.add("Source table is empty")
        }

        return RollbackValidationResult(
            tableName = tableName,
            isValid = issues.isEmpty(),
            issues = issues
        )
    }

    private fun getRowCount(ds: DataSource, tableName: String): Long {
        ds.connection.use { conn ->
            val rs = conn.createStatement().executeQuery("SELECT COUNT(*) FROM $tableName")
            return if (rs.next()) rs.getLong(1) else 0L
        }
    }

    private fun getMappingCount(tableName: String): Long {
        targetDataSource.connection.use { conn ->
            val rs = conn.prepareStatement(
                "SELECT COUNT(*) FROM migration_mapping WHERE table_name = ?"
            )
            rs.setString(1, tableName)
            val countRs = rs.executeQuery()
            return if (countRs.next()) countRs.getLong(1) else 0L
        }
    }

    /**
     * Результат валидации
     */
    data class RollbackValidationResult(
        val tableName: String,
        val isValid: Boolean,
        val issues: List<String>
    )
}
