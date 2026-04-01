package validation

import java.util.*
import javax.sql.DataSource

/**
 * Сервис валидации целостности данных после миграции
 */
class DataIntegrityValidator(private val sourceDs: DataSource, private val targetDs: DataSource) {

    /**
     * Результат валидации таблицы
     */
    data class ValidationResult(
        val tableName: String,
        val isValid: Boolean,
        val sourceCount: Long,
        val targetCount: Long,
        val countMatch: Boolean,
        val fkViolations: List<FkViolation>,
        val missingMappings: List<UUID>,
        val errorMessage: String? = null
    )

    /**
     * Нарушение внешнего ключа
     */
    data class FkViolation(
        val tableName: String,
        val columnName: String,
        val invalidValue: Long,
        val referencedTable: String
    )

    /**
     * Валидация количества записей
     */
    fun validateRowCount(tableName: String): Pair<Long, Long> {
        val sourceCount = countRows(sourceDs, tableName)
        val targetCount = countRows(targetDs, tableName)
        return Pair(sourceCount, targetCount)
    }

    /**
     * Полная валидация таблицы
     */
    fun validateTable(tableName: String): ValidationResult {
        try {
            val (sourceCount, targetCount) = validateRowCount(tableName)
            val countMatch = sourceCount == targetCount

            val fkViolations = validateForeignKeys(tableName)
            val missingMappings = validateMappings(tableName)

            return ValidationResult(
                tableName = tableName,
                isValid = countMatch && fkViolations.isEmpty() && missingMappings.isEmpty(),
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
                missingMappings = emptyList(),
                errorMessage = e.message
            )
        }
    }

    /**
     * Валидация всех таблиц
     */
    fun validateAllTables(tables: List<String>): List<ValidationResult> {
        return tables.map { validateTable(it) }
    }

    /**
     * Валидация внешних ключей
     */
    private fun validateForeignKeys(tableName: String): List<FkViolation> {
        val violations = mutableListOf<FkViolation>()

        targetDs.connection.use { conn ->
            // Получаем информацию о FK из metadata
            val fkRs = conn.metaData.getImportedKeys(null, "public", tableName)
            
            while (fkRs.next()) {
                val fkColumnName = fkRs.getString("FKCOLUMN_NAME")
                val pkTableName = fkRs.getString("PKTABLE_NAME")

                // Проверяем наличие нарушенных FK
                val checkSql = """
                    SELECT DISTINCT t.$fkColumnName
                    FROM $tableName t
                    LEFT JOIN $pkTableName pt ON t.$fkColumnName = pt.id
                    WHERE t.$fkColumnName IS NOT NULL AND pt.id IS NULL
                """.trimIndent()

                val rs = conn.createStatement().executeQuery(checkSql)
                while (rs.next()) {
                    violations.add(
                        FkViolation(
                            tableName = tableName,
                            columnName = fkColumnName,
                            invalidValue = rs.getLong(1),
                            referencedTable = pkTableName
                        )
                    )
                }
            }
        }

        return violations
    }

    /**
     * Валидация маппинга UUID → BIGINT
     */
    private fun validateMappings(tableName: String): List<UUID> {
        val missingUuids = mutableListOf<UUID>()

        targetDs.connection.use { conn ->
            // Проверяем наличие всех записей в migration_mapping
            val sql = """
                SELECT old_uuid FROM migration_mapping 
                WHERE table_name = ? AND old_uuid IS NOT NULL
            """.trimIndent()

            val pstmt = conn.prepareStatement(sql)
            pstmt.setString(1, tableName)
            val rs = pstmt.executeQuery()

            while (rs.next()) {
                val uuid = rs.getObject("old_uuid") as? UUID
                if (uuid != null) {
                    // Проверяем существует ли запись в target
                    val checkSql = "SELECT COUNT(*) FROM $tableName WHERE id = ?"
                    val checkPstmt = conn.prepareStatement(checkSql)
                    
                    // Получаем BIGINT id из маппинга
                    val mappingSql = "SELECT new_id FROM migration_mapping WHERE table_name = ? AND old_uuid = ?"
                    val mappingPstmt = conn.prepareStatement(mappingSql)
                    mappingPstmt.setString(1, tableName)
                    mappingPstmt.setObject(2, uuid)
                    val mappingRs = mappingPstmt.executeQuery()
                    
                    if (mappingRs.next()) {
                        val newId = mappingRs.getLong("new_id")
                        checkPstmt.setLong(1, newId)
                        val checkRs = checkPstmt.executeQuery()
                        if (checkRs.next() && checkRs.getLong(1) == 0L) {
                            missingUuids.add(uuid)
                        }
                    }
                }
            }
        }

        return missingUuids
    }

    fun validateIdSequence(tableName: String): Boolean {
        targetDs.connection.use { conn ->
            val sql = """
                SELECT 
                    COUNT(*) as total,
                    MAX(id) - MIN(id) + 1 as expected_total
                FROM $tableName
            """.trimIndent()

            val rs = conn.createStatement().executeQuery(sql)
            if (rs.next()) {
                val total = rs.getLong("total")
                val expectedTotal = rs.getLong("expected_total")
                return total == expectedTotal
            }
        }
        return false
    }

    /**
     * Валидация checksum для критических данных
     */
    fun validateChecksum(tableName: String, columns: List<String>): Boolean {
        val sourceChecksum = calculateChecksum(sourceDs, tableName, columns)
        val targetChecksum = calculateChecksum(targetDs, tableName, columns)
        return sourceChecksum == targetChecksum
    }

    /**
     * Расчёт checksum для набора колонок
     */
    private fun calculateChecksum(ds: DataSource, tableName: String, columns: List<String>): Long {
        ds.connection.use { conn ->
            val columnsSql = columns.joinToString(" || ", "'", "'")
            val sql = """
                SELECT SUM(HASHTEXT($columnsSql)) as checksum
                FROM $tableName
            """.trimIndent()

            val rs = conn.createStatement().executeQuery(sql)
            return if (rs.next()) rs.getLong("checksum") else 0L
        }
    }

    private fun countRows(ds: DataSource, tableName: String): Long {
        ds.connection.use { conn ->
            val rs = conn.createStatement().executeQuery("SELECT COUNT(*) FROM $tableName")
            return if (rs.next()) rs.getLong(1) else 0L
        }
    }

    /**
     * Отчёт о валидации
     */
    fun generateValidationReport(tables: List<String>): String {
        val results = validateAllTables(tables)
        val validCount = results.count { it.isValid }
        val invalidCount = results.size - validCount

        return buildString {
            appendLine("=== Data Integrity Validation Report ===")
            appendLine()
            appendLine("Summary:")
            appendLine("  Total tables: ${tables.size}")
            appendLine("  Valid: $validCount")
            appendLine("  Invalid: $invalidCount")
            appendLine()

            results.forEach { result ->
                appendLine("Table: ${result.tableName}")
                appendLine("  Source count: ${result.sourceCount}")
                appendLine("  Target count: ${result.targetCount}")
                appendLine("  Counts match: ${result.countMatch}")
                appendLine("  FK violations: ${result.fkViolations.size}")
                appendLine("  Missing mappings: ${result.missingMappings.size}")
                if (result.errorMessage != null) {
                    appendLine("  Error: ${result.errorMessage}")
                }
                appendLine("  Status: ${if (result.isValid) "✓ VALID" else "✗ INVALID"}")
                appendLine()
            }
        }
    }
}
