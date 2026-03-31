package engine

import core.MetadataReader
import state.StateRepository
import java.sql.PreparedStatement
import java.sql.Statement.RETURN_GENERATED_KEYS
import java.util.*
import javax.sql.DataSource

class DataMigrator(
    private val sourceDataSource: DataSource,
    private val targetDataSource: DataSource,
    private val mappingService: MappingService,
    private val metadataReader: MetadataReader,
    private val stateRepository: StateRepository? = null,
    private val migrationId: String? = null
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

        // Получение последнего состояния для resume
        val lastState = migrationId?.let { stateRepository?.getTableState(it, tableName) }
        val resumeBatchNumber = lastState?.lastBatchNumber ?: 0
        val lastProcessedUuid = lastState?.lastProcessedUuid?.let { UUID.fromString(it) }

        sourceDataSource.connection.use { sourceConn ->
            targetDataSource.connection.use { targetConn ->
                targetConn.autoCommit = false

                val rs = sourceConn.createStatement().executeQuery("SELECT * FROM $tableName ORDER BY id")

                val placeholders = columns.joinToString(", ") { "?" }
                val sql = "INSERT INTO $tableName (${columns.joinToString(", ")}) VALUES ($placeholders)"
                val preparedStatement = targetConn.prepareStatement(sql, RETURN_GENERATED_KEYS)

                val currentBatchOldUuids = mutableListOf<UUID>()
                var newRecordsInTable = 0
                var currentBatchNumber = 0
                var lastUuid: UUID? = null

                while (rs.next()) {
                    val oldPk = rs.getObject("id") as UUID

                    // Пропуск уже обработанных записей при resume
                    if (lastProcessedUuid != null && oldPk <= lastProcessedUuid) {
                        continue
                    }

                    if (existingIds.contains(oldPk)) continue
                    if (mappingService.getNewId(tableName, oldPk) != null) continue

                    currentBatchOldUuids.add(oldPk)
                    newRecordsInTable++
                    lastUuid = oldPk

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
                        currentBatchNumber++
                        processBatch(tableName, preparedStatement, currentBatchOldUuids)
                        targetConn.commit()

                        // Сохранение прогресса для возможности resume
                        migrationId?.let { mid ->
                            stateRepository?.saveProgress(mid, tableName, newRecordsInTable.toLong(), lastUuid, currentBatchNumber)
                        }

                        currentBatchOldUuids.clear()
                    }
                }

                if (currentBatchOldUuids.isNotEmpty()) {
                    currentBatchNumber++
                    processBatch(tableName, preparedStatement, currentBatchOldUuids)
                    targetConn.commit()

                    migrationId?.let { mid ->
                        stateRepository?.saveProgress(mid, tableName, newRecordsInTable.toLong(), lastUuid, currentBatchNumber)
                    }
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