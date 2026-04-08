package engine

import core.MetadataReader
import logging.MetricsService
import logging.PerformanceLogger
import state.StateRepository
import java.sql.PreparedStatement
import java.sql.Statement.RETURN_GENERATED_KEYS
import java.util.*
import javax.sql.DataSource

class DataMigrator(
    private val sourceDataSource: DataSource,
    private val targetDataSource: DataSource,
    private val mappingService: MappingServiceBase,
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

    /**
     * Перенос индексов из source в target.
     * Анализирует реальные индексы source БД и воссоздаёт их в target,
     * автоматически заменяя UUID-типы на BIGINT.
     *
     * Это безопаснее, чем слепое создание индексов на FK:
     * переносятся только те индексы, которые реально существуют в source.
     */
    fun migrateIndexes(tables: List<String>) {
        println(">>> Перенос индексов из source в target...")

        sourceDataSource.connection.use { sourceConn ->
            targetDataSource.connection.use { targetConn ->

                tables.forEach { tableName ->
                    val indexes = getSourceIndexes(sourceConn, tableName)

                    indexes.forEach { idx ->
                        val indexName = idx.name.replace("${tableName}_pkey", "${tableName}_pkey") // PK не трогаем — уже создан
                        if (idx.isPrimary) return@forEach // PK уже создан через BIGSERIAL PRIMARY KEY

                        val columnsStr = idx.columns.joinToString(", ") { col ->
                            // Если колонка = id, она уже BIGINT; иначе — просто имя
                            col
                        }

                        val uniqueKeyword = if (idx.isUnique) "UNIQUE " else ""
                        val whereClause = if (idx.predicate != null) " WHERE ${idx.predicate}" else ""

                        val createIndexSql = "CREATE ${uniqueKeyword}INDEX IF NOT EXISTS $indexName ON $tableName ($columnsStr)$whereClause"
                        targetConn.createStatement().execute(createIndexSql)
                        println("  [+] Index: $indexName (${if (idx.isUnique) "UNIQUE" else "NON-UNIQUE"}) → $columnsStr")
                    }
                }
            }
        }
    }

    /**
     * Опциональное создание индексов на всех FK-колонках.
     * Не рекомендуется как базовая практика — используйте migrateIndexes() для
     * точного переноса реальной индексной схемы из source.
     */
    fun createForeignKeyIndexes(tables: List<String>) {
        println(">>> Создание FK-индексов в целевой схеме (опционально)...")
        targetDataSource.connection.use { targetConn ->
            tables.forEach { tableName ->
                val foreignKeys = metadataReader.getForeignKeysForTable(tableName)
                foreignKeys.forEach { fk ->
                    val indexName = "idx_${tableName}_${fk.columnName}"
                    val createIndexSql = "CREATE INDEX IF NOT EXISTS $indexName ON $tableName (${fk.columnName})"
                    targetConn.createStatement().execute(createIndexSql)
                    println("  [+] FK Index: $indexName")
                }
            }
        }
    }

    /**
     * Получает информацию об индексах из source БД.
     */
    private data class IndexInfo(
        val name: String,
        val columns: List<String>,
        val isUnique: Boolean,
        val isPrimary: Boolean,
        val predicate: String? = null  // WHERE clause для partial indexes
    )

    private fun getSourceIndexes(conn: java.sql.Connection, tableName: String): List<IndexInfo> {
        val indexes = mutableListOf<IndexInfo>()

        // Запрос к pg_catalog для получения полной информации об индексах
        val sql = """
            SELECT
                i.relname AS index_name,
                ix.indisunique AS is_unique,
                ix.indisprimary AS is_primary,
                array_agg(a.attname ORDER BY array_position(ix.indkey, a.attnum)) AS columns,
                pg_get_expr(ix.indpred, ix.indrelid) AS predicate
            FROM pg_index ix
            JOIN pg_class t ON t.oid = ix.indrelid
            JOIN pg_class i ON i.oid = ix.indexrelid
            JOIN pg_attribute a ON a.attrelid = t.oid AND a.attnum = ANY(ix.indkey)
            WHERE t.relname = ? AND t.relnamespace = 'public'::regnamespace
            GROUP BY i.relname, ix.indisunique, ix.indisprimary, ix.indpred, ix.indkey, ix.indrelid
            ORDER BY i.relname
        """.trimIndent()

        val pstmt = conn.prepareStatement(sql)
        pstmt.setString(1, tableName)
        val rs = pstmt.executeQuery()

        while (rs.next()) {
            val columnsArray = rs.getArray("columns")
            val columnsList = if (columnsArray != null) {
                @Suppress("UNCHECKED_CAST")
                (columnsArray.array as Array<String>).toList()
            } else {
                emptyList()
            }

            indexes.add(
                IndexInfo(
                    name = rs.getString("index_name"),
                    columns = columnsList,
                    isUnique = rs.getBoolean("is_unique"),
                    isPrimary = rs.getBoolean("is_primary"),
                    predicate = rs.getString("predicate")
                )
            )
        }

        return indexes
    }

    fun migrateTable(tableName: String, existingIds: Set<UUID> = emptySet()) {
        // Инициализация логирования для этой таблицы
        PerformanceLogger.startTable(tableName)
        
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

                sourceConn.autoCommit = false

                val stmt = sourceConn.createStatement()
                // Загружать в RAM только по 5000 строк за раз
                stmt.fetchSize = 5000

                val rs = stmt.executeQuery("SELECT * FROM $tableName ORDER BY id")

                val placeholders = columns.joinToString(", ") { "?" }
                val sql = "INSERT INTO $tableName (${columns.joinToString(", ")}) VALUES ($placeholders)"
                val preparedStatement = targetConn.prepareStatement(sql, RETURN_GENERATED_KEYS)

                val currentBatchOldUuids = mutableListOf<UUID>()
                var newRecordsInTable: Long = 0L
                var currentBatchNumber: Long = 0L
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

                    if (newRecordsInTable % batchSize == 0L) {
                        currentBatchNumber++
                        processBatch(tableName, preparedStatement, currentBatchOldUuids, currentBatchNumber)
                        targetConn.commit()

                        // Сохранение прогресса для возможности resume
                        migrationId?.let { mid ->
                            stateRepository?.saveProgress(mid, tableName, newRecordsInTable, lastUuid, currentBatchNumber)
                        }

                        currentBatchOldUuids.clear()
                    }
                }

                if (currentBatchOldUuids.isNotEmpty()) {
                    currentBatchNumber++
                    processBatch(tableName, preparedStatement, currentBatchOldUuids, currentBatchNumber)
                    targetConn.commit()

                    migrationId?.let { mid ->
                        stateRepository?.saveProgress(mid, tableName, newRecordsInTable, lastUuid, currentBatchNumber)
                    }
                }

                if (newRecordsInTable > 0) {
                    println("Синхронизация $tableName: добавлено $newRecordsInTable новых строк.")
                    
                    // Завершение логирования таблицы
                    PerformanceLogger.completeTable(
                        tableName = tableName,
                        totalRecords = newRecordsInTable,
                        totalDuration = 0,  // Считаем в другом месте
                        avgRecordsPerSec = 0.0
                    )
                }
            }
        }
    }

    private fun processBatch(
        tableName: String,
        preparedStatement: PreparedStatement,
        oldUuids: List<UUID>,
        batchNumber: Long
    ) {
        val batchStart = System.currentTimeMillis()

        // Замер INSERT через Micrometer Timer
        val insertTimer = MetricsService.getMigrationBatchTimer(tableName, "insert")
        val insertDuration = insertTimer.recordCallable<Long> {
            preparedStatement.executeBatch()
            System.currentTimeMillis() - batchStart
        }!!

        // Замер generated keys
        val generatedKeys = preparedStatement.generatedKeys
        val batchMappings = mutableMapOf<UUID, Long>()

        var i = 0
        while (generatedKeys.next()) {
            val newId = generatedKeys.getLong(1)
            val oldUuid = oldUuids[i++]
            batchMappings[oldUuid] = newId

            mappingService.saveMappingInMemory(oldUuid, newId)
        }

        // Замер MAPPING через Micrometer Timer
        val mappingTimer = MetricsService.getMigrationBatchTimer(tableName, "mapping")
        val mappingDuration = mappingTimer.recordCallable<Long> {
            mappingService.saveMappingBatch(tableName, batchMappings)
            System.currentTimeMillis() - batchStart
        }!!

        val totalBatchDuration = System.currentTimeMillis() - batchStart

        // Increment counter для успешно мигрированных строк
        val rowsCounter = MetricsService.getMigrationRowsCounter(tableName)
        rowsCounter.increment(oldUuids.size.toDouble())

        // Логирование метрик (legacy CSV — deprecated)
        PerformanceLogger.logBatch(
            tableName = tableName,
            batchNumber = batchNumber,
            totalRecords = oldUuids.size.toLong(),
            insertDuration = insertDuration,
            mappingDuration = mappingDuration,
            commitDuration = 0,
            totalBatchDuration = totalBatchDuration
        )

        PerformanceLogger.logMapping(
            tableName = tableName,
            batchNumber = batchNumber,
            recordsSaved = oldUuids.size.toLong(),
            durationMs = mappingDuration
        )
    }
}