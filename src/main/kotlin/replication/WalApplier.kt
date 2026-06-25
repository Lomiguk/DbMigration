package replication

import core.MetadataReader
import engine.MappingServiceBase
import org.slf4j.LoggerFactory
import java.sql.Connection
import java.sql.PreparedStatement
import javax.sql.DataSource

/**
 * Применятель WAL событий к target базе данных
 * Оптимизированная версия с поддержкой JDBC Batching и Graceful Degradation
 */
class WalApplier(
    private val targetDataSource: DataSource,
    private val mappingService: MappingServiceBase,
    private val metadataReader: MetadataReader
) {
    private val logger = LoggerFactory.getLogger(WalApplier::class.java)

    private val fkCache: Map<String, Map<String, String>> = buildFkCache()
    private val sessionMappingCache = java.util.concurrent.ConcurrentHashMap<java.util.UUID, Long>()

    private fun buildFkCache(): Map<String, Map<String, String>> {
        logger.info("Building in-memory Foreign Key cache for WAL Applier...")
        val cache = mutableMapOf<String, MutableMap<String, String>>()
        val tables = metadataReader.getAllTablesWithUuidPk()

        for (table in tables) {
            val fks = metadataReader.getForeignKeysForTable(table)
            val tableMap = mutableMapOf<String, String>()
            for (fk in fks) {
                tableMap[fk.columnName] = fk.refTable
            }
            cache[table] = tableMap
        }
        return cache
    }

    fun applyBatch(events: List<WalEvent>): List<WalProcessEvent> {
        if (events.isEmpty()) return emptyList()
        val results = mutableListOf<WalProcessEvent>()

        targetDataSource.connection.use { conn ->
            val originalAutoCommit = conn.autoCommit
            conn.autoCommit = false

            try {
                var currentTableName = ""
                var currentEventType = ""
                val batchEvents = mutableListOf<WalEvent>()

                fun flushBatch() {
                    if (batchEvents.isEmpty()) return
                    val batchResults = when (val firstEvent = batchEvents.first()) {
                        is WalInsertEvent -> applyInsertBatch(conn, batchEvents.filterIsInstance<WalInsertEvent>())
                        is WalUpdateEvent -> applyUpdateBatch(conn, batchEvents.filterIsInstance<WalUpdateEvent>())
                        is WalDeleteEvent -> applyDeleteBatch(conn, batchEvents.filterIsInstance<WalDeleteEvent>())
                        else -> emptyList()
                    }
                    results.addAll(batchResults)
                    batchEvents.clear()
                }

                for (event in events) {
                    val type = event.javaClass.simpleName
                    val table = event.tableName

                    if (table != currentTableName || type != currentEventType) {
                        flushBatch()
                        currentTableName = table
                        currentEventType = type
                    }
                    batchEvents.add(event)
                }
                flushBatch()

                conn.commit()
                if (sessionMappingCache.size > 100_000) sessionMappingCache.clear()

            } catch (e: Exception) {
                conn.rollback()
                logger.error("Failed to apply WAL batch: ${e.message}", e)
                throw e
            } finally {
                conn.autoCommit = originalAutoCommit
            }
        }
        return results
    }

    private fun applyInsertBatch(conn: Connection, events: List<WalInsertEvent>): List<WalProcessEvent> {
        val tableName = events.first().tableName.removePrefix("public.")
        val columns = events.first().newTuple.keys.filter { it != "id" }
        val columnList = columns.joinToString(", ")
        val placeholders = columns.joinToString(", ") { "?" }
        val sql = "INSERT INTO $tableName ($columnList) VALUES ($placeholders)"

        val results = mutableListOf<WalProcessEvent>()
        val mappingsToSave = mutableMapOf<java.util.UUID, Long>()
        val successfulEvents = mutableListOf<WalInsertEvent>()

        try {
            conn.prepareStatement(sql, PreparedStatement.RETURN_GENERATED_KEYS).use { pstmt ->
                for (event in events) {
                    try {
                        columns.forEachIndexed { index, column ->
                            val value = event.newTuple[column]
                            pstmt.setObject(index + 1, transformValue(tableName, column, value))
                        }
                        pstmt.addBatch()
                        successfulEvents.add(event)
                    } catch (e: Exception) {
                        results.add(WalProcessEvent(false, tableName, "INSERT", event.commitLsn, errorMessage = e.message))
                    }
                }

                if (successfulEvents.isNotEmpty()) {
                    pstmt.executeBatch()
                    val rs = pstmt.generatedKeys
                    for (event in successfulEvents) {
                        if (rs.next()) {
                            val newId = rs.getLong(1)
                            val oldUuidStr = event.newTuple["id"]?.toString()
                            if (oldUuidStr != null) {
                                val oldUuid = java.util.UUID.fromString(oldUuidStr)
                                mappingsToSave[oldUuid] = newId
                                sessionMappingCache[oldUuid] = newId
                            }
                        }
                        results.add(WalProcessEvent(true, tableName, "INSERT", event.commitLsn, rowsAffected = 1))
                    }
                }
            }

            if (mappingsToSave.isNotEmpty()) {
                mappingService.saveMappingBatch(tableName, mappingsToSave, conn)
            }
        } catch (e: Exception) {
            logger.error("Failed to apply INSERT batch for $tableName: ${e.message}", e)
            return events.map { WalProcessEvent(false, it.tableName, "INSERT", it.commitLsn, errorMessage = e.message) }
        }
        return results
    }

    private fun applyUpdateBatch(conn: Connection, events: List<WalUpdateEvent>): List<WalProcessEvent> {
        val tableName = events.first().tableName.removePrefix("public.")
        val columns = events.first().newTuple.keys.filter { it != "id" }
        val setClause = columns.joinToString(", ") { "$it = ?" }
        val sql = "UPDATE $tableName SET $setClause WHERE id = ?"

        val results = mutableListOf<WalProcessEvent>()
        val mappingUpdates = mutableListOf<Triple<java.util.UUID, java.util.UUID, Long>>()
        val successfulEvents = mutableListOf<WalUpdateEvent>()

        try {
            conn.prepareStatement(sql).use { pstmt ->
                for (event in events) {
                    try {
                        val oldIdStr = event.oldTuple?.get("id")?.toString()
                        val newIdStr = event.newTuple["id"]?.toString()

                        val targetId = if (oldIdStr != null) {
                            val oldUuid = java.util.UUID.fromString(oldIdStr)
                            sessionMappingCache[oldUuid] ?: mappingService.getNewId(tableName, oldUuid)
                        } else if (newIdStr != null) {
                            val newUuid = java.util.UUID.fromString(newIdStr)
                            sessionMappingCache[newUuid] ?: mappingService.getNewId(tableName, newUuid)
                        } else null

                        if (targetId == null) {
                            results.add(WalProcessEvent(false, tableName, "UPDATE", event.commitLsn, errorMessage = "Cannot find mapping for UUID"))
                            continue
                        }

                        columns.forEachIndexed { index, column ->
                            pstmt.setObject(index + 1, transformValue(tableName, column, event.newTuple[column]))
                        }
                        pstmt.setLong(columns.size + 1, targetId)
                        pstmt.addBatch()

                        if (oldIdStr != newIdStr && newIdStr != null && oldIdStr != null) {
                            val newUuid = java.util.UUID.fromString(newIdStr)
                            mappingUpdates.add(Triple(java.util.UUID.fromString(oldIdStr), newUuid, targetId))
                            sessionMappingCache[newUuid] = targetId
                        }
                        successfulEvents.add(event)
                    } catch (e: Exception) {
                        results.add(WalProcessEvent(false, tableName, "UPDATE", event.commitLsn, errorMessage = e.message))
                    }
                }

                if (successfulEvents.isNotEmpty()) {
                    pstmt.executeBatch()
                    for (event in successfulEvents) {
                        results.add(WalProcessEvent(true, tableName, "UPDATE", event.commitLsn, rowsAffected = 1))
                    }
                }
            }

            if (mappingUpdates.isNotEmpty()) {
                val updateMappingSql = "UPDATE migration_mapping SET old_uuid = ? WHERE table_name = ? AND old_uuid = ?"
                conn.prepareStatement(updateMappingSql).use { mapStmt ->
                    for ((oldUuid, newUuid, _) in mappingUpdates) {
                        mapStmt.setObject(1, newUuid)
                        mapStmt.setString(2, tableName)
                        mapStmt.setObject(3, oldUuid)
                        mapStmt.addBatch()
                    }
                    mapStmt.executeBatch()
                }
                for ((oldUuid, newUuid, targetId) in mappingUpdates) {
                    mappingService.replaceMapping(tableName, oldUuid, newUuid, targetId)
                }
            }
        } catch (e: Exception) {
            logger.error("Failed to apply UPDATE batch for $tableName: ${e.message}", e)
            return events.map { WalProcessEvent(false, it.tableName, "UPDATE", it.commitLsn, errorMessage = e.message) }
        }
        return results
    }

    private fun applyDeleteBatch(conn: Connection, events: List<WalDeleteEvent>): List<WalProcessEvent> {
        val tableName = events.first().tableName.removePrefix("public.")
        val sql = "DELETE FROM $tableName WHERE id = ?"
        val results = mutableListOf<WalProcessEvent>()
        val successfulEvents = mutableListOf<WalDeleteEvent>()

        try {
            conn.prepareStatement(sql).use { pstmt ->
                for (event in events) {
                    try {
                        val oldUuidStr = event.oldTuple["id"]?.toString() ?: continue
                        val oldUuid = java.util.UUID.fromString(oldUuidStr)

                        val targetId = sessionMappingCache[oldUuid] ?: mappingService.getNewId(tableName, oldUuid)
                        if (targetId == null) {
                            results.add(WalProcessEvent(false, tableName, "DELETE", event.commitLsn, errorMessage = "Cannot find mapping for UUID: $oldUuid"))
                            continue
                        }

                        pstmt.setLong(1, targetId)
                        pstmt.addBatch()
                        successfulEvents.add(event)
                    } catch (e: Exception) {
                        results.add(WalProcessEvent(false, tableName, "DELETE", event.commitLsn, errorMessage = e.message))
                    }
                }

                if (successfulEvents.isNotEmpty()) {
                    pstmt.executeBatch()
                    for (event in successfulEvents) {
                        results.add(WalProcessEvent(true, tableName, "DELETE", event.commitLsn, rowsAffected = 1))
                    }
                }
            }
        } catch (e: Exception) {
            return events.map { WalProcessEvent(false, it.tableName, "DELETE", it.commitLsn, errorMessage = e.message) }
        }
        return results
    }

    private fun transformValue(tableName: String, columnName: String, value: Any?): Any? {
        if (value !is java.util.UUID) return value
        val referencedTable = fkCache[tableName]?.get(columnName) ?: return value

        return sessionMappingCache[value] ?: mappingService.getNewId(referencedTable, value)
        ?: throw java.lang.IllegalStateException("Отсутствует маппинг для FK $tableName.$columnName = $value")
    }
}