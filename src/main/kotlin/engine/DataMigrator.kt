package engine

import core.MetadataReader
import java.sql.ResultSet
import java.util.*
import javax.sql.DataSource

class DataMigrator(
    private val sourceDataSource: DataSource,
    private val targetDataSource: DataSource,
    private val mappingService: MappingService,
    private val metadataReader: MetadataReader
) {
    fun createTargetSchema(tables: List<String>) {
        println(">>> Шаг 4: Создание чистой целевой схемы (только BIGINT)...")
        targetDataSource.connection.use { targetConn ->
            tables.forEach { tableName ->
                val columns = metadataReader.getTableColumns(tableName)
                val columnDefinitions = mutableListOf<String>()

                columns.forEach { (name, type) ->
                    when {
                        name == "id" -> columnDefinitions.add("id BIGSERIAL PRIMARY KEY")
                        else -> {
                            val targetType = if (type.lowercase() == "uuid") "BIGINT" else type
                            columnDefinitions.add("$name $targetType")
                        }
                    }
                }
                // original_uuid больше не добавляется в основную таблицу!
                val ddl = "CREATE TABLE IF NOT EXISTS $tableName (${columnDefinitions.joinToString(", ")})"
                targetConn.createStatement().execute(ddl)
            }
        }
    }

    fun migrateTable(tableName: String) {
        val foreignKeys = metadataReader.getForeignKeysForTable(tableName)
        val columns = metadataReader.getTableColumns(tableName).keys.filter { it != "id" }
        val batchSize = 1000

        sourceDataSource.connection.use { sourceConn ->
            targetDataSource.connection.use { targetConn ->
                targetConn.autoCommit = false

                val rs = sourceConn.createStatement().executeQuery("SELECT * FROM $tableName")

                val placeholders = columns.joinToString(", ") { "?" }
                val sql = "INSERT INTO $tableName (${columns.joinToString(", ")}) VALUES ($placeholders)"

                // Statement.RETURN_GENERATED_KEYS позволяет получить новые ID после пакетной вставки
                val pstmt = targetConn.prepareStatement(sql, java.sql.Statement.RETURN_GENERATED_KEYS)

                val currentBatchOldUuids = mutableListOf<UUID>()
                var count = 0

                while (rs.next()) {
                    val oldPk = rs.getObject("id") as UUID

                    // Пропускаем, если уже мигрировали (для Sync)
                    if (mappingService.getNewId(tableName, oldPk) != null) continue

                    currentBatchOldUuids.add(oldPk)

                    columns.forEachIndexed { index, col ->
                        val fk = foreignKeys.find { it.columnName == col }
                        val value = rs.getObject(col)
                        if (fk != null && value is UUID) {
                            pstmt.setObject(index + 1, mappingService.getNewId(fk.refTable, value))
                        } else {
                            pstmt.setObject(index + 1, value)
                        }
                    }
                    pstmt.addBatch()

                    if (++count % batchSize == 0) {
                        processBatch(tableName, pstmt, currentBatchOldUuids)
                        targetConn.commit()
                        currentBatchOldUuids.clear()
                    }
                }

                // Обработка последнего неполного батча
                if (currentBatchOldUuids.isNotEmpty()) {
                    processBatch(tableName, pstmt, currentBatchOldUuids)
                    targetConn.commit()
                }

                println("Миграция $tableName завершена: $count строк.")
            }
        }
    }

    private fun processBatch(tableName: String, pstmt: java.sql.PreparedStatement, oldUuids: List<UUID>) {
        pstmt.executeBatch()
        val generatedKeys = pstmt.generatedKeys
        val batchMappings = mutableMapOf<UUID, Long>()

        var i = 0
        while (generatedKeys.next()) {
            val newId = generatedKeys.getLong(1)
            val oldUuid = oldUuids[i++]
            batchMappings[oldUuid] = newId

            mappingService.saveMappingInMemory(oldUuid, newId)
        }

        // Сохраняем весь маппинг в БД целевой системы одним запросом
        mappingService.saveMappingBatch(tableName, batchMappings)
    }
}