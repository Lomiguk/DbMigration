package replication

import core.MetadataReader
import engine.MappingServiceBase
import org.slf4j.LoggerFactory
import java.sql.Connection
import java.sql.PreparedStatement
import java.util.UUID
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
    private val sessionMappingCache = java.util.concurrent.ConcurrentHashMap<SessionMappingKey, Long>()

    private data class SessionMappingKey(
        val tableName: String,
        val oldUuid: UUID
    )

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
        val fkMappings = resolveForeignKeyMappings(tableName, events.map { it.newTuple })

        val results = mutableListOf<WalProcessEvent>()
        val mappingsToSave = mutableMapOf<UUID, Long>()
        val successfulEvents = mutableListOf<WalInsertEvent>()

        try {
            conn.prepareStatement(sql, PreparedStatement.RETURN_GENERATED_KEYS).use { pstmt ->
                for (event in events) {
                    try {
                        columns.forEachIndexed { index, column ->
                            val value = event.newTuple[column]
                            pstmt.setObject(index + 1, transformValue(tableName, column, value, fkMappings))
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
                                sessionMappingCache[SessionMappingKey(tableName, oldUuid)] = newId
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
        val targetIds = resolveMappings(tableName, events.mapNotNull { event ->
            (event.oldTuple?.get("id") ?: event.newTuple["id"])?.toString()?.let { UUID.fromString(it) }
        })
        val fkMappings = resolveForeignKeyMappings(tableName, events.map { it.newTuple })

        val results = mutableListOf<WalProcessEvent>()
        val mappingUpdates = mutableListOf<Triple<UUID, UUID, Long>>()
        val successfulEvents = mutableListOf<WalUpdateEvent>()

        try {
            conn.prepareStatement(sql).use { pstmt ->
                for (event in events) {
                    try {
                        val oldIdStr = event.oldTuple?.get("id")?.toString()
                        val newIdStr = event.newTuple["id"]?.toString()

                        val targetId = if (oldIdStr != null) {
                            targetIds[UUID.fromString(oldIdStr)]
                        } else if (newIdStr != null) {
                            targetIds[UUID.fromString(newIdStr)]
                        } else null

                        if (targetId == null) {
                            results.add(WalProcessEvent(false, tableName, "UPDATE", event.commitLsn, errorMessage = "Cannot find mapping for UUID"))
                            continue
                        }

                        columns.forEachIndexed { index, column ->
                            pstmt.setObject(index + 1, transformValue(tableName, column, event.newTuple[column], fkMappings))
                        }
                        pstmt.setLong(columns.size + 1, targetId)
                        pstmt.addBatch()

                        if (oldIdStr != newIdStr && newIdStr != null && oldIdStr != null) {
                            val newUuid = java.util.UUID.fromString(newIdStr)
                            mappingUpdates.add(Triple(UUID.fromString(oldIdStr), newUuid, targetId))
                            sessionMappingCache[SessionMappingKey(tableName, newUuid)] = targetId
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
        val targetIds = resolveMappings(tableName, events.mapNotNull { event ->
            event.oldTuple["id"]?.toString()?.let { UUID.fromString(it) }
        })
        val results = mutableListOf<WalProcessEvent>()
        val successfulEvents = mutableListOf<WalDeleteEvent>()

        try {
            conn.prepareStatement(sql).use { pstmt ->
                for (event in events) {
                    try {
                        val oldUuidStr = event.oldTuple["id"]?.toString() ?: continue
                        val oldUuid = UUID.fromString(oldUuidStr)

                        val targetId = targetIds[oldUuid]
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

    private fun transformValue(
        tableName: String,
        columnName: String,
        value: Any?,
        fkMappings: Map<String, Map<UUID, Long>>
    ): Any? {
        if (value !is UUID) return value
        val referencedTable = fkCache[tableName]?.get(columnName) ?: return value

        return fkMappings[referencedTable]?.get(value)
            ?: throw java.lang.IllegalStateException("Отсутствует маппинг для FK $tableName.$columnName = $value")
    }

    private fun resolveForeignKeyMappings(
        tableName: String,
        tuples: List<Map<String, Any?>>
    ): Map<String, Map<UUID, Long>> {
        val uuidsByRefTable = mutableMapOf<String, MutableSet<UUID>>()
        val tableFks = fkCache[tableName].orEmpty()

        tuples.forEach { tuple ->
            tableFks.forEach { (columnName, referencedTable) ->
                val value = tuple[columnName]
                if (value is UUID) {
                    uuidsByRefTable.getOrPut(referencedTable) { mutableSetOf() }.add(value)
                }
            }
        }

        return uuidsByRefTable.mapValues { (referencedTable, uuids) ->
            resolveMappings(referencedTable, uuids)
        }
    }

    private fun resolveMappings(tableName: String, uuids: Collection<UUID>): Map<UUID, Long> {
        val result = mutableMapOf<UUID, Long>()
        val misses = mutableListOf<UUID>()

        uuids.distinct().forEach { uuid ->
            val cached = sessionMappingCache[SessionMappingKey(tableName, uuid)]
            if (cached != null) {
                result[uuid] = cached
            } else {
                misses.add(uuid)
            }
        }

        if (misses.isNotEmpty()) {
            val loaded = mappingService.getNewIds(tableName, misses)
            loaded.forEach { (uuid, newId) ->
                result[uuid] = newId
                sessionMappingCache[SessionMappingKey(tableName, uuid)] = newId
            }
        }

        return result
    }
}
