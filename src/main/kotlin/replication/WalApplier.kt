package replication

import core.MetadataReader
import engine.MappingService
import org.slf4j.LoggerFactory
import java.sql.Connection
import java.sql.PreparedStatement
import javax.sql.DataSource

/**
 * Применятель WAL событий к target базе данных
 * Трансформирует UUID → BIGINT при применении изменений
 */
class WalApplier(
    private val targetDataSource: DataSource,
    private val mappingService: MappingService,
    private val metadataReader: MetadataReader
) {
    private val logger = LoggerFactory.getLogger(WalApplier::class.java)

    private val fkCache: Map<String, Map<String, String>> = buildFkCache()

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

    /**
     * Применение пакета WAL событий
     */
    fun applyBatch(events: List<WalEvent>): List<WalProcessEvent> {
        val results = mutableListOf<WalProcessEvent>()

        targetDataSource.connection.use { conn ->
            conn.autoCommit = false

            try {
                events.forEach { event ->
                    val result = when (event) {
                        is WalInsertEvent -> applyInsert(conn, event)
                        is WalUpdateEvent -> applyUpdate(conn, event)
                        is WalDeleteEvent -> applyDelete(conn, event)
                    }
                    results.add(result)
                }

                conn.commit()
            } catch (e: Exception) {
                conn.rollback()
                logger.error("Failed to apply WAL batch: ${e.message}", e)
                throw e
            }
        }

        return results
    }

    /**
     * Применение INSERT события
     */
    private fun applyInsert(conn: Connection, event: WalInsertEvent): WalProcessEvent {
        try {
            val tableName = event.tableName.removePrefix("public.")

            // Получаем список колонок из newTuple
            val columns = event.newTuple.keys.filter { it != "id" }
            val columnList = columns.joinToString(", ")

            // Генерируем placeholders
            val placeholders = columns.joinToString(", ") { "?" }

            val sql = "INSERT INTO $tableName ($columnList) VALUES ($placeholders)"

            conn.prepareStatement(sql, PreparedStatement.RETURN_GENERATED_KEYS).use { pstmt ->
                // Заполняем значения, трансформируя UUID FK в BIGINT
                columns.forEachIndexed { index, column ->
                    val value = event.newTuple[column]
                    val transformedValue = transformValue(tableName, column, value)
                    pstmt.setObject(index + 1, transformedValue)
                }

                pstmt.executeUpdate()

                // Получаем сгенерированный BIGINT ID и сохраняем маппинг
                val rs = pstmt.generatedKeys
                if (rs.next()) {
                    val newId = rs.getLong(1)
                    val oldUuid = event.newTuple["id"]?.toString()
                    if (oldUuid != null) {
                        mappingService.saveMappingInMemory(
                            java.util.UUID.fromString(oldUuid),
                            newId
                        )
                    }
                }
            }

            return WalProcessEvent(
                success = true,
                tableName = tableName,
                eventType = "INSERT",
                lsn = event.commitLsn,
                rowsAffected = 1
            )
        } catch (e: Exception) {
            logger.error("Failed to apply INSERT for ${event.tableName}: ${e.message}", e)
            return WalProcessEvent(
                success = false,
                tableName = event.tableName,
                eventType = "INSERT",
                lsn = event.commitLsn,
                errorMessage = e.message
            )
        }
    }

    /**
     * Применение UPDATE события
     */
    private fun applyUpdate(conn: Connection, event: WalUpdateEvent): WalProcessEvent {
        try {
            val tableName = event.tableName.removePrefix("public.")

            // Получаем ID из oldTuple (если есть) или newTuple
            val oldId = event.oldTuple?.get("id")?.toString()
            val newIdValue = event.newTuple["id"]?.toString()

            // Находим BIGINT ID через маппинг
            val targetId = if (oldId != null) {
                mappingService.getNewId(tableName, java.util.UUID.fromString(oldId))
            } else if (newIdValue != null) {
                mappingService.getNewId(tableName, java.util.UUID.fromString(newIdValue))
            } else {
                null
            }

            if (targetId == null) {
                return WalProcessEvent(
                    success = false,
                    tableName = tableName,
                    eventType = "UPDATE",
                    lsn = event.commitLsn,
                    errorMessage = "Cannot find mapping for UUID"
                )
            }

            // Получаем колонки для обновления (исключаем id)
            val columns = event.newTuple.keys.filter { it != "id" }

            // Строим SET clause
            val setClause = columns.joinToString(", ") { "${it} = ?" }

            val sql = "UPDATE $tableName SET $setClause WHERE id = ?"

            conn.prepareStatement(sql).use { pstmt ->
                // Заполняем значения
                columns.forEachIndexed { index, column ->
                    val value = event.newTuple[column]
                    val transformedValue = transformValue(tableName, column, value)
                    pstmt.setObject(index + 1, transformedValue)
                }

                // WHERE clause
                pstmt.setLong(columns.size + 1, targetId)
                pstmt.executeUpdate()
            }

            // Если ID изменился, обновляем маппинг
            if (oldId != newIdValue && newIdValue != null) {
                val newBigIntId = mappingService.getNewId(tableName, java.util.UUID.fromString(newIdValue))
                if (newBigIntId != null && oldId != null) {

                    // TODO

                    // Удаляем старый маппинг и создаём новый
                    // (в реальной реализации нужно обновить запись в БД)
                }
            }

            return WalProcessEvent(
                success = true,
                tableName = tableName,
                eventType = "UPDATE",
                lsn = event.commitLsn,
                rowsAffected = 1
            )
        } catch (e: Exception) {
            logger.error("Failed to apply UPDATE for ${event.tableName}: ${e.message}", e)
            return WalProcessEvent(
                success = false,
                tableName = event.tableName,
                eventType = "UPDATE",
                lsn = event.commitLsn,
                errorMessage = e.message
            )
        }
    }

    /**
     * Применение DELETE события
     */
    private fun applyDelete(conn: Connection, event: WalDeleteEvent): WalProcessEvent {
        try {
            val tableName = event.tableName.removePrefix("public.")

            // Получаем UUID из oldTuple
            val oldUuidStr = event.oldTuple["id"]?.toString() ?: return WalProcessEvent(
                success = false,
                tableName = tableName,
                eventType = "DELETE",
                lsn = event.commitLsn,
                errorMessage = "Cannot find ID in old tuple"
            )

            // Находим BIGINT ID через маппинг
            val oldUuid = java.util.UUID.fromString(oldUuidStr)
            val targetId = mappingService.getNewId(tableName, oldUuid) ?: return WalProcessEvent(
                success = false,
                tableName = tableName,
                eventType = "DELETE",
                lsn = event.commitLsn,
                errorMessage = "Cannot find mapping for UUID: $oldUuid"
            )

            val sql = "DELETE FROM $tableName WHERE id = ?"

            conn.prepareStatement(sql).use { pstmt ->
                pstmt.setLong(1, targetId)
                pstmt.executeUpdate()
            }

            return WalProcessEvent(
                success = true,
                tableName = tableName,
                eventType = "DELETE",
                lsn = event.commitLsn,
                rowsAffected = 1
            )
        } catch (e: Exception) {
            logger.error("Failed to apply DELETE for ${event.tableName}: ${e.message}", e)
            return WalProcessEvent(
                success = false,
                tableName = event.tableName,
                eventType = "DELETE",
                lsn = event.commitLsn,
                errorMessage = e.message
            )
        }
    }

    /**
     * Быстрая трансформация UUID -> BIGINT для внешних ключей (O(1) в памяти)
     */
    private fun transformValue(tableName: String, columnName: String, value: Any?): Any? {
        if (value !is java.util.UUID) return value

        // Быстрый поиск таблицы по внешнему ключу
        val referencedTable = fkCache[tableName]?.get(columnName)

        return if (referencedTable != null) {
            mappingService.getNewId(referencedTable, value)
                ?: throw IllegalStateException("Отсутствует маппинг для FK $tableName.$columnName = $value (ссылается на $referencedTable). Убедитесь, что таблица была мигрирована.")
        } else {
            value // Это первичный ключ, оставляем как есть (значение сгенерирует сама БД)
        }
    }

}
