package validation

import javax.sql.DataSource

/**
 * Оптимизированный сервис валидации целостности данных после миграции.
 *
 * Использует массовые SQL-операции (NOT EXISTS, EXCEPT) вместо N+1 запросов.
 * Требует наличия FK-индексов на целевой БД для производительной работы.
 */
class DataIntegrityValidator(private val sourceDs: DataSource, private val targetDs: DataSource) {

    data class ValidationResult(
        val tableName: String,
        val isValid: Boolean,
        val sourceCount: Long,
        val targetCount: Long,
        val countMatch: Boolean,
        val fkViolations: List<FkViolation>,
        val missingMappings: Long,
        val errorMessage: String? = null
    )

    data class FkViolation(
        val tableName: String,
        val columnName: String,
        val orphanCount: Long,
        val referencedTable: String
    )

    // ==================== Публичные методы ====================

    fun validateRowCount(tableName: String): Pair<Long, Long> {
        val sourceCount = countRows(sourceDs, tableName)
        val targetCount = countRows(targetDs, tableName)
        return Pair(sourceCount, targetCount)
    }

    fun validateTable(tableName: String): ValidationResult {
        try {
            val (sourceCount, targetCount) = validateRowCount(tableName)
            val countMatch = sourceCount == targetCount

            val fkViolations = validateForeignKeys(tableName)
            val missingMappings = countMissingMappings(tableName)

            return ValidationResult(
                tableName = tableName,
                isValid = countMatch && fkViolations.isEmpty() && missingMappings == 0L,
                sourceCount = sourceCount,
                targetCount = targetCount,
                countMatch = countMatch,
                fkViolations = fkViolations,
                missingMappings = missingMappings
            )
        } catch (e: Exception) {
            return ValidationResult(
                tableName = tableName,
                isValid = false,
                sourceCount = 0,
                targetCount = 0,
                countMatch = false,
                fkViolations = emptyList(),
                missingMappings = 0,
                errorMessage = e.message
            )
        }
    }

    fun validateAllTables(tables: List<String>): List<ValidationResult> {
        return tables.map { validateTable(it) }
    }

    /**
     * Проверка FK через NOT EXISTS — один запрос на каждый внешний ключ.
     * Требует индекс на FK-колонке в целевой БД для производительной работы.
     */
    private fun validateForeignKeys(tableName: String): List<FkViolation> {
        val violations = mutableListOf<FkViolation>()

        targetDs.connection.use { conn ->
            val fkRs = conn.metaData.getImportedKeys(null, "public", tableName)

            while (fkRs.next()) {
                val fkColumnName = fkRs.getString("FKCOLUMN_NAME")
                val pkTableName = fkRs.getString("PKTABLE_NAME")

                // Оптимизированный запрос через NOT EXISTS вместо LEFT JOIN
                val checkSql = """
                    SELECT COUNT(*) AS orphan_count
                    FROM $tableName t
                    WHERE t.$fkColumnName IS NOT NULL
                      AND NOT EXISTS (
                          SELECT 1 FROM $pkTableName pt
                          WHERE pt.id = t.$fkColumnName
                      )
                """.trimIndent()

                val rs = conn.createStatement().executeQuery(checkSql)
                if (rs.next()) {
                    val orphanCount = rs.getLong("orphan_count")
                    if (orphanCount > 0) {
                        violations.add(
                            FkViolation(
                                tableName = tableName,
                                columnName = fkColumnName,
                                orphanCount = orphanCount,
                                referencedTable = pkTableName
                            )
                        )
                    }
                }
            }
        }

        return violations
    }

    /**
     * Массовая проверка: ищем UUID в source, которых нет в mapping.
     * Запрос к source (UUID) + к mapping в target.
     */
    private fun countMissingMappings(tableName: String): Long {
        // Берём UUID из source
        val sourceUuids = mutableSetOf<String>()
        sourceDs.connection.use { conn ->
            val sql = "SELECT id::text FROM $tableName"
            val rs = conn.createStatement().executeQuery(sql)
            while (rs.next()) {
                sourceUuids.add(rs.getString(1))
            }
        }

        // Берём замапленные UUID из target
        val mappedUuids = mutableSetOf<String>()
        targetDs.connection.use { conn ->
            val sql = "SELECT old_uuid::text FROM migration_mapping WHERE table_name = ?"
            val pstmt = conn.prepareStatement(sql)
            pstmt.setString(1, tableName)
            val rs = pstmt.executeQuery()
            while (rs.next()) {
                mappedUuids.add(rs.getString(1))
            }
        }

        // Разница = UUID без маппинга
        sourceUuids.removeAll(mappedUuids)
        return sourceUuids.size.toLong()
    }

    private fun countRows(ds: DataSource, tableName: String): Long {
        ds.connection.use { conn ->
            val rs = conn.createStatement().executeQuery("SELECT COUNT(*) FROM $tableName")
            return if (rs.next()) rs.getLong(1) else 0L
        }
    }

}
