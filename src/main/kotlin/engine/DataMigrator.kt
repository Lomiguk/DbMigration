package engine

import core.ForeignKeyColumn
import core.MetadataReader
import logging.MetricsService
import logging.PerformanceLogger
import org.postgresql.copy.CopyManager
import org.postgresql.core.BaseConnection
import state.StateRepository
import java.io.StringReader
import java.sql.Connection
import java.util.*
import java.util.concurrent.TimeUnit
import javax.sql.DataSource

class DataMigrator(
    private val sourceDataSource: DataSource,
    private val targetDataSource: DataSource,
    private val mappingService: MappingServiceBase,
    private val metadataReader: MetadataReader,
    private val stateRepository: StateRepository? = null,
    private val migrationId: String? = null,
    private val batchSize: Int = 1000,
    private val adaptiveBatchConfig: AdaptiveBatchConfig = AdaptiveBatchConfig.DISABLED
) {
    private data class BatchResult(
        val totalDurationMs: Long
    )

    private data class PendingRow(
        val oldPk: UUID,
        val values: Map<String, Any?>
    )
    private val effectiveBatchSize = batchSize.coerceAtLeast(1)

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
        val tableStart = System.currentTimeMillis()
        
        val foreignKeys = metadataReader.getForeignKeysForTable(tableName)
        val columns = metadataReader.getTableColumns(tableName).keys.filter { it != "id" }
        val alreadyMappedIds = mappingService.getAllMappedUuids(tableName)
        val skippedIds = if (alreadyMappedIds.isEmpty()) existingIds else existingIds + alreadyMappedIds

        // Получение последнего состояния для resume
        val lastState = migrationId?.let { stateRepository?.getTableState(it, tableName) }
        val resumeBatchNumber = lastState?.lastBatchNumber?.toLong() ?: 0L
        val lastProcessedUuid = lastState?.lastProcessedUuid?.let { UUID.fromString(it) }

        sourceDataSource.connection.use { sourceConn ->
            targetDataSource.connection.use { targetConn ->
                targetConn.autoCommit = false
                targetConn.createStatement().execute("SET synchronous_commit = OFF")

                sourceConn.autoCommit = false

                try {
                    val stmt = sourceConn.createStatement()
                    // Keep cursor chunks aligned with migration batches without loading the full table.
                    stmt.fetchSize = maxOf(5000, effectiveBatchSize)

                    val rs = stmt.executeQuery("SELECT * FROM $tableName ORDER BY id")

                    val targetColumns = listOf("id") + columns
                    val currentBatchRows = mutableListOf<PendingRow>()
                    val batchController = AdaptiveBatchController(adaptiveBatchConfig, effectiveBatchSize)
                    var newRecordsInTable = 0L
                    var currentBatchNumber: Long = resumeBatchNumber
                    var lastUuid: UUID? = null
                    var sourceReadStart = System.currentTimeMillis()

                    while (rs.next()) {
                        // TODO where is check?
                        val oldPk = rs.getObject("id") as UUID

                        // Пропуск уже обработанных записей при resume
                        if (lastProcessedUuid != null && oldPk <= lastProcessedUuid) {
                            continue
                        }

                        if (skippedIds.contains(oldPk)) continue

                        currentBatchRows.add(PendingRow(oldPk, columns.associateWith { col -> rs.getObject(col) }))
                        newRecordsInTable++
                        lastUuid = oldPk

                        if (currentBatchRows.size >= batchController.currentBatchSize) {
                            currentBatchNumber++
                            val batchSizeForThisBatch = batchController.currentBatchSize
                            logBatchPhase(tableName, currentBatchNumber, "source_read", System.currentTimeMillis() - sourceReadStart, currentBatchRows.size.toLong(), batchSizeForThisBatch)
                            val batchResult = processBatch(targetConn, tableName, targetColumns, columns, foreignKeys, currentBatchRows, currentBatchNumber, batchSizeForThisBatch)
                            logAdaptiveBatchDecision(
                                tableName,
                                currentBatchNumber,
                                batchResult.totalDurationMs,
                                batchController.onBatchCompleted(batchResult.totalDurationMs, currentBatchRows.size.toLong())
                            )

                            // Сохранение прогресса для возможности resume
                            migrationId?.let { mid ->
                                stateRepository?.saveProgress(mid, tableName, newRecordsInTable, lastUuid, currentBatchNumber)
                            }

                            currentBatchRows.clear()
                            sourceReadStart = System.currentTimeMillis()
                        }
                    }

                    if (currentBatchRows.isNotEmpty()) {
                        currentBatchNumber++
                        val batchSizeForThisBatch = batchController.currentBatchSize
                        logBatchPhase(tableName, currentBatchNumber, "source_read", System.currentTimeMillis() - sourceReadStart, currentBatchRows.size.toLong(), batchSizeForThisBatch)
                        val batchResult = processBatch(targetConn, tableName, targetColumns, columns, foreignKeys, currentBatchRows, currentBatchNumber, batchSizeForThisBatch)
                        logAdaptiveBatchDecision(
                            tableName,
                            currentBatchNumber,
                            batchResult.totalDurationMs,
                            batchController.onBatchCompleted(batchResult.totalDurationMs, currentBatchRows.size.toLong())
                        )

                        migrationId?.let { mid ->
                            stateRepository?.saveProgress(mid, tableName, newRecordsInTable, lastUuid, currentBatchNumber)
                        }
                    }

                    if (newRecordsInTable > 0) {
                        val tableDuration = System.currentTimeMillis() - tableStart
                        println("Синхронизация $tableName: добавлено $newRecordsInTable новых строк.")

                        // Завершение логирования таблицы
                        PerformanceLogger.completeTable(
                            tableName = tableName,
                            totalRecords = newRecordsInTable,
                            totalDuration = tableDuration,
                            avgRecordsPerSec = if (tableDuration > 0) newRecordsInTable * 1000.0 / tableDuration else 0.0
                        )
                    }
                } finally {
                    try {
                        targetConn.createStatement().execute("SET synchronous_commit = ON")
                    } catch (_: Exception) {
                        // Connection close will discard the session setting if the transaction is already aborted.
                    }
                }
            }
        }
    }

    private fun processBatch(
        targetConn: Connection,
        tableName: String,
        targetColumns: List<String>,
        columns: List<String>,
        foreignKeys: List<ForeignKeyColumn>,
        rows: List<PendingRow>,
        batchNumber: Long,
        batchSizeForThisBatch: Int
    ): BatchResult {
        val batchStart = System.currentTimeMillis()
        val recordsTotal = rows.size.toLong()

        var phaseStart = System.currentTimeMillis()
        val foreignKeysByColumn = foreignKeys.associateBy { it.columnName }
        val mappedForeignKeys = resolveForeignKeys(foreignKeysByColumn, rows)
        val fkLookupDuration = System.currentTimeMillis() - phaseStart
        logBatchPhase(tableName, batchNumber, "fk_lookup", fkLookupDuration, recordsTotal, batchSizeForThisBatch)

        phaseStart = System.currentTimeMillis()
        val allocatedIds = allocateTargetIds(targetConn, tableName, rows.size)
        val idAllocationDuration = System.currentTimeMillis() - phaseStart
        logBatchPhase(tableName, batchNumber, "id_allocation", idAllocationDuration, recordsTotal, batchSizeForThisBatch)

        phaseStart = System.currentTimeMillis()
        val copyPayload = buildCopyPayload(columns, foreignKeysByColumn, mappedForeignKeys, rows, allocatedIds)
        val csvBuildDuration = System.currentTimeMillis() - phaseStart
        logBatchPhase(tableName, batchNumber, "csv_build", csvBuildDuration, recordsTotal, batchSizeForThisBatch)

        // Замер INSERT через Micrometer Timer
        val insertTimer = MetricsService.getMigrationBatchTimer(tableName, "insert")
        val insertDuration = insertTimer.recordCallable {
            val startedAt = System.currentTimeMillis()
            copyRows(targetConn, tableName, targetColumns, copyPayload)
            System.currentTimeMillis() - startedAt
        }!!
        logBatchPhase(tableName, batchNumber, "copy_data", insertDuration, recordsTotal, batchSizeForThisBatch)

        val batchMappings = mutableMapOf<UUID, Long>()
        rows.forEachIndexed { index, row ->
            batchMappings[row.oldPk] = allocatedIds[index]
        }

        // Замер MAPPING через Micrometer Timer
        val mappingTimer = MetricsService.getMigrationBatchTimer(tableName, "mapping")
        val mappingDuration = mappingTimer.recordCallable<Long> {
            val startedAt = System.currentTimeMillis()
            mappingService.saveMappingBatch(tableName, batchMappings, targetConn)
            System.currentTimeMillis() - startedAt
        }!!
        logBatchPhase(tableName, batchNumber, "mapping_save", mappingDuration, recordsTotal, batchSizeForThisBatch)

        phaseStart = System.currentTimeMillis()
        targetConn.commit()
        val commitDuration = System.currentTimeMillis() - phaseStart
        logBatchPhase(tableName, batchNumber, "commit", commitDuration, recordsTotal, batchSizeForThisBatch)

        val totalBatchDuration = System.currentTimeMillis() - batchStart

        // Increment counter для успешно мигрированных строк
        val rowsCounter = MetricsService.getMigrationRowsCounter(tableName)
        rowsCounter.increment(rows.size.toDouble())
        MetricsService.recordMigrationBatch(tableName, recordsTotal, totalBatchDuration)

        // Логирование метрик (legacy CSV — deprecated)
        PerformanceLogger.logBatch(
            tableName = tableName,
            batchNumber = batchNumber,
            batchSize = batchSizeForThisBatch,
            totalRecords = rows.size.toLong(),
            insertDuration = insertDuration,
            mappingDuration = mappingDuration,
            commitDuration = commitDuration,
            totalBatchDuration = totalBatchDuration
        )

        PerformanceLogger.logMapping(
            tableName = tableName,
            batchNumber = batchNumber,
            recordsSaved = rows.size.toLong(),
            durationMs = mappingDuration
        )

        return BatchResult(totalBatchDuration)
    }

    private fun logAdaptiveBatchDecision(
        tableName: String,
        batchNumber: Long,
        batchDurationMs: Long,
        decision: AdaptiveBatchDecision?
    ) {
        if (decision == null) return

        PerformanceLogger.logAdaptiveBatchDecision(
            tableName = tableName,
            batchNumber = batchNumber,
            previousBatchSize = decision.previousBatchSize,
            nextBatchSize = decision.nextBatchSize,
            batchDurationMs = batchDurationMs,
            targetDurationMs = adaptiveBatchConfig.targetBatchDurationMs,
            reason = decision.reason
        )

        println(
            "Adaptive batch $tableName#$batchNumber: " +
                "${decision.previousBatchSize} -> ${decision.nextBatchSize} (${decision.reason})"
        )
    }

    private fun logBatchPhase(
        tableName: String,
        batchNumber: Long,
        phase: String,
        durationMs: Long,
        recordsTotal: Long,
        batchSizeForThisBatch: Int = recordsTotal.toInt()
    ) {
        PerformanceLogger.logBatchPhase(tableName, batchNumber, phase, durationMs, recordsTotal, batchSizeForThisBatch)
        MetricsService.getMigrationBatchTimer(tableName, phase).record(durationMs, TimeUnit.MILLISECONDS)
    }

    private fun buildCopyPayload(
        columns: List<String>,
        foreignKeysByColumn: Map<String, ForeignKeyColumn>,
        mappedForeignKeys: Map<String, Map<UUID, Long>>,
        rows: List<PendingRow>,
        allocatedIds: List<Long>
    ): String {
        val builder = StringBuilder(rows.size * columns.size * 16)
        rows.forEachIndexed { rowIndex, row ->
            builder.append(allocatedIds[rowIndex])
            columns.forEach { col ->
                builder.append(',')
                val value = row.values[col]
                val fk = foreignKeysByColumn[col]
                val targetValue = if (fk != null && value is UUID) {
                    mappedForeignKeys[fk.refTable]?.get(value)
                } else {
                    value
                }
                builder.append(csvValue(targetValue))
            }
            builder.append('\n')
        }
        return builder.toString()
    }

    private fun copyRows(conn: Connection, tableName: String, targetColumns: List<String>, copyPayload: String) {
        val copySql = "COPY ${quoteIdentifier(tableName)} (${targetColumns.joinToString(", ") { quoteIdentifier(it) }}) FROM STDIN WITH (FORMAT csv, NULL '\\N')"
        val copyManager = CopyManager(conn.unwrap(BaseConnection::class.java))
        copyManager.copyIn(copySql, StringReader(copyPayload))
    }

    private fun csvValue(value: Any?): String {
        if (value == null) return "\\N"
        val text = when (value) {
            is Number -> value.toString()
            is Boolean -> value.toString()
            else -> value.toString()
        }
        val needsQuoting = text.any { it == ',' || it == '"' || it == '\n' || it == '\r' } || text == "\\N"
        if (!needsQuoting) return text
        return "\"" + text.replace("\"", "\"\"") + "\""
    }

    private fun quoteIdentifier(identifier: String): String =
        "\"" + identifier.replace("\"", "\"\"") + "\""

    private fun resolveForeignKeys(
        foreignKeysByColumn: Map<String, ForeignKeyColumn>,
        rows: List<PendingRow>
    ): Map<String, Map<UUID, Long>> {
        val uuidsByRefTable = mutableMapOf<String, MutableSet<UUID>>()

        rows.forEach { row ->
            foreignKeysByColumn.forEach { (columnName, fk) ->
                val value = row.values[columnName]
                if (value is UUID) {
                    uuidsByRefTable.getOrPut(fk.refTable) { mutableSetOf() }.add(value)
                }
            }
        }

        return uuidsByRefTable.mapValues { (refTable, uuids) ->
            mappingService.getNewIds(refTable, uuids)
        }
    }

    private fun allocateTargetIds(conn: Connection, tableName: String, count: Int): List<Long> {
        if (count == 0) return emptyList()

        val ids = ArrayList<Long>(count)
        conn.prepareStatement("SELECT nextval(pg_get_serial_sequence(?, 'id')) FROM generate_series(1, ?)").use { pstmt ->
            pstmt.setString(1, "public.$tableName")
            pstmt.setInt(2, count)
            val rs = pstmt.executeQuery()
            while (rs.next()) {
                ids.add(rs.getLong(1))
            }
        }

        check(ids.size == count) {
            "Allocated ${ids.size} ids for $tableName, expected $count"
        }

        return ids
    }
}
