package engine

import core.MetadataReader
import java.sql.PreparedStatement
import java.sql.Statement.RETURN_GENERATED_KEYS
import java.util.*
import javax.sql.DataSource

class DataMigrator(
    private val sourceDataSource: DataSource,
    private val targetDataSource: DataSource,
    private val mappingService: MappingService,
    private val metadataReader: MetadataReader
) {
    fun createTargetSchema(tables: List<String>) {
        println(">>> Шаг 2.2: Создание чистой целевой схемы (только BIGINT)...")
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
                val ddl = "CREATE TABLE IF NOT EXISTS $tableName (${columnDefinitions.joinToString(", ")})"
                targetConn.createStatement().execute(ddl)
            }
        }
    }

    fun migrateTable(tableName: String, existingIds: Set<UUID> = emptySet()) {
        val foreignKeys = metadataReader.getForeignKeysForTable(tableName)
        val columns = metadataReader.getTableColumns(tableName).keys.filter { it != "id" }
        val batchSize = 1000

        sourceDataSource.connection.use { sourceConn ->
            targetDataSource.connection.use { targetConn ->
                targetConn.autoCommit = false

                // Читаем все данные из исходной таблицы
                // TODO переделать процесс под работу с пакетами. Таблица целиком может быть излишне большой
                val rs = sourceConn.createStatement().executeQuery("SELECT * FROM $tableName")

                val placeholders = columns.joinToString(", ") { "?" }
                val sql = "INSERT INTO $tableName (${columns.joinToString(", ")}) VALUES ($placeholders)"
                val preparedStatement = targetConn.prepareStatement(sql, RETURN_GENERATED_KEYS)

                val currentBatchOldUuids = mutableListOf<UUID>()
                var newRecordsInTable = 0

                while (rs.next()) {
                    // TODO тут следует аккуратнее относится (это подходит только для наших тестовых данных)
                    val oldPk = rs.getObject("id") as UUID

                    if (existingIds.contains(oldPk)) continue

                    if (mappingService.getNewId(tableName, oldPk) != null) continue

                    currentBatchOldUuids.add(oldPk)
                    newRecordsInTable++

                    columns.forEachIndexed { index, col ->
                        val fk = foreignKeys.find { it.columnName == col }
                        val value = rs.getObject(col)
                        if (fk != null && value is UUID) {
                            val preCreatedFTableId = mappingService.getNewId(fk.refTable, value)
                            preparedStatement.setObject(index + 1, preCreatedFTableId)
                        } else {
                            preparedStatement.setObject(index + 1, value)
                        }
                    }
                    preparedStatement.addBatch()

                    if (newRecordsInTable % batchSize == 0) {
                        processBatch(tableName, preparedStatement, currentBatchOldUuids)
                        targetConn.commit()
                        currentBatchOldUuids.clear()
                    }
                }

                if (currentBatchOldUuids.isNotEmpty()) {
                    processBatch(tableName, preparedStatement, currentBatchOldUuids)
                    targetConn.commit()
                }

                if (newRecordsInTable > 0) {
                    println("Синхронизация $tableName: добавлено $newRecordsInTable новых строк.")
                }
            }
        }
    }

    private fun processBatch(
        tableName: String,
        preparedStatement: PreparedStatement,
        oldUuids: List<UUID>
    ) {

        preparedStatement.executeBatch()
        val generatedKeys = preparedStatement.generatedKeys
        val batchMappings = mutableMapOf<UUID, Long>()

        var i = 0
        while (generatedKeys.next()) {
            val newId = generatedKeys.getLong(1)
            val oldUuid = oldUuids[i++]
            batchMappings[oldUuid] = newId

            mappingService.saveMappingInMemory(oldUuid, newId)
        }

        mappingService.saveMappingBatch(tableName, batchMappings)
    }
}