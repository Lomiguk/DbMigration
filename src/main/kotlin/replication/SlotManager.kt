package replication

import org.slf4j.LoggerFactory
import java.sql.Connection
import java.sql.SQLException
import javax.sql.DataSource

/**
 * Менеджер replication слотов PostgreSQL
 */
class SlotManager(private val dataSource: DataSource) {

    private val logger = LoggerFactory.getLogger(SlotManager::class.java)

    /**
     * Гарантирует, что WAL будет содержать старые значения всех колонок
     * при UPDATE и DELETE операциях. Это критически важно для маппинга UUID -> BIGINT.
     */
    fun setupReplicaIdentity(tables: List<String>) {
        logger.info("Configuring REPLICA IDENTITY FULL for replicated tables...")
        dataSource.connection.use { conn ->
            conn.createStatement().use { stmt ->
                tables.forEach { table ->
                    try {
                        stmt.execute("ALTER TABLE ${qualifiedTable(table)} REPLICA IDENTITY FULL;")
                    } catch (e: Exception) {
                        logger.warn("Failed to set REPLICA IDENTITY for $table: ${e.message}")
                    }
                }
            }
        }
    }

    /**
     * Создание replication slot
     */
    fun createSlot(slotName: String, temporary: Boolean = false, publicationTables: List<String> = emptyList()): Boolean {
        dataSource.connection.use { conn ->
            try {
                createPublication(conn, "dbmigration_publication", publicationTables)

                // Проверяем существует ли уже слот
                if (slotExists(slotName)) {
                    logger.warn("Replication slot '$slotName' already exists")
                    return false
                }

                // Создаём replication slot с pgoutput плагином
                val sql = if (temporary) {
                    "SELECT pg_create_logical_replication_slot(?, 'pgoutput', true)"
                } else {
                    "SELECT pg_create_logical_replication_slot(?, 'pgoutput')"
                }

                conn.prepareStatement(sql).use { pstmt ->
                    pstmt.setString(1, slotName)
                    val rs = pstmt.executeQuery()
                    if (rs.next()) {
                        val slotInfo = rs.getString(1)
                        logger.info("Created replication slot: $slotInfo")
                        return true
                    }
                }
                return false
            } catch (e: SQLException) {
                logger.error("Failed to create replication slot: ${e.message}", e)
                throw e
            }
        }
    }

    /**
     * Проверка существования слота
     */
    fun slotExists(slotName: String): Boolean {
        dataSource.connection.use { conn ->
            val sql = "SELECT 1 FROM pg_replication_slots WHERE slot_name = ?"
            conn.prepareStatement(sql).use { pstmt ->
                pstmt.setString(1, slotName)
                val rs = pstmt.executeQuery()
                return rs.next()
            }
        }
    }

    /**
     * Вычисление lag в байтах
     */
    fun calculateLag(slotName: String): Long {
        dataSource.connection.use { conn ->
            val sql = """
                SELECT 
                    pg_current_wal_lsn() - confirmed_flush_lsn AS lag_bytes
                FROM pg_replication_slots
                WHERE slot_name = ?
            """.trimIndent()

            conn.prepareStatement(sql).use { pstmt ->
                pstmt.setString(1, slotName)
                val rs = pstmt.executeQuery()
                return if (rs.next()) {
                    val lagObj = rs.getObject(1)
                    lagObj?.toString()?.toLongOrNull() ?: 0L
                } else {
                    0L
                }
            }
        }
    }

    /**
     * Создание публикации для logical replication
     */
    private fun createPublication(conn: Connection, publicationName: String, tables: List<String>) {
        try {
            val sql = if (tables.isEmpty()) {
                "CREATE PUBLICATION $publicationName FOR ALL TABLES"
            } else {
                "CREATE PUBLICATION $publicationName FOR TABLE ${tables.joinToString(", ") { qualifiedTable(it) }}"
            }
            conn.createStatement().execute(sql)
            logger.info("Created publication: $publicationName (${if (tables.isEmpty()) "all tables" else "${tables.size} tables"})")
        } catch (e: SQLException) {
            // Публикация может уже существовать
            if (e.sqlState == "42710") { // duplicate_object
                if (tables.isNotEmpty()) {
                    val sql = "ALTER PUBLICATION $publicationName SET TABLE ${tables.joinToString(", ") { qualifiedTable(it) }}"
                    conn.createStatement().execute(sql)
                    logger.info("Updated publication: $publicationName (${tables.size} tables)")
                } else {
                    logger.debug("Publication already exists")
                }
            } else {
                throw e
            }
        }
    }

    private fun qualifiedTable(tableName: String): String = "${quoteIdentifier("public")}.${quoteIdentifier(tableName)}"

    private fun quoteIdentifier(identifier: String): String = "\"${identifier.replace("\"", "\"\"")}\""

    /**
     * Удаление replication slot
     */
    fun dropSlot(slotName: String) {
        dataSource.connection.use { conn ->
            try {
                val sql = "SELECT pg_drop_replication_slot(?)"
                conn.prepareStatement(sql).use { pstmt ->
                    pstmt.setString(1, slotName)
                    pstmt.execute()
                    logger.info("Dropped replication slot: $slotName")
                }
            } catch (e: SQLException) {
                logger.error("Failed to drop replication slot $slotName: ${e.message}")
                throw e
            }
        }
    }
}

