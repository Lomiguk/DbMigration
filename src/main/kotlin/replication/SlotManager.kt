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
     * Создание replication slot
     */
    fun createSlot(slotName: String, temporary: Boolean = false): Boolean {
        dataSource.connection.use { conn ->
            try {
                // Проверяем существует ли уже слот
                if (slotExists(slotName)) {
                    logger.warn("Replication slot '$slotName' already exists")
                    return false
                }

                // Создаём публикацию для pgoutput
                createPublication(conn, "dbmigration_publication")

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
     * Получение информации о слоте
     */
    fun getSlotInfo(slotName: String): SlotInfo? {
        dataSource.connection.use { conn ->
            val sql = """
                SELECT 
                    slot_name,
                    plugin,
                    slot_type,
                    database,
                    active,
                    restart_lsn,
                    confirmed_flush_lsn,
                    wal_status
                FROM pg_replication_slots
                WHERE slot_name = ?
            """.trimIndent()

            conn.prepareStatement(sql).use { pstmt ->
                pstmt.setString(1, slotName)
                val rs = pstmt.executeQuery()
                return if (rs.next()) {
                    SlotInfo(
                        slotName = rs.getString("slot_name"),
                        plugin = rs.getString("plugin"),
                        slotType = rs.getString("slot_type"),
                        database = rs.getString("database"),
                        active = rs.getBoolean("active"),
                        restartLsn = rs.getString("restart_lsn"),
                        confirmedFlushLsn = rs.getString("confirmed_flush_lsn"),
                        walStatus = rs.getString("wal_status")
                    )
                } else {
                    null
                }
            }
        }
    }

    /**
     * Получение текущего LSN базы данных
     */
    fun getCurrentLsn(): String {
        dataSource.connection.use { conn ->
            val sql = "SELECT pg_current_wal_lsn()::text"
            conn.createStatement().use { stmt ->
                val rs = stmt.executeQuery(sql)
                return if (rs.next()) rs.getString(1) else "0/0"
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
     * Удаление replication slot
     */
    fun dropSlot(slotName: String): Boolean {
        dataSource.connection.use { conn ->
            try {
                if (!slotExists(slotName)) {
                    logger.warn("Replication slot '$slotName' does not exist")
                    return false
                }

                // Деактивируем слот если активен
                deactivateSlot(conn, slotName)

                val sql = "SELECT pg_drop_replication_slot(?)"
                conn.prepareStatement(sql).use { pstmt ->
                    pstmt.setString(1, slotName)
                    pstmt.executeUpdate()
                    logger.info("Dropped replication slot: $slotName")
                    return true
                }
            } catch (e: SQLException) {
                logger.error("Failed to drop replication slot: ${e.message}", e)
                return false
            }
        }
    }

    /**
     * Создание публикации для logical replication
     */
    private fun createPublication(conn: Connection, publicationName: String) {
        try {
            // Создаём публикацию для всех таблиц
            val sql = "CREATE PUBLICATION $publicationName FOR ALL TABLES"
            conn.createStatement().execute(sql)
            logger.info("Created publication: $publicationName")
        } catch (e: SQLException) {
            // Публикация может уже существовать
            if (e.sqlState == "42710") { // duplicate_object
                logger.debug("Publication already exists")
            } else {
                throw e
            }
        }
    }

    /**
     * Деактивация слота (завершение активного подключения)
     */
    private fun deactivateSlot(conn: Connection, slotName: String) {
        // Завершаем активные replication подключения
        val sql = """
            SELECT pg_terminate_backend(active_pid)
            FROM pg_replication_slots
            WHERE slot_name = ? AND active = true
        """.trimIndent()

        conn.prepareStatement(sql).use { pstmt ->
            pstmt.setString(1, slotName)
            pstmt.executeUpdate()
        }
    }

    /**
     * Получение всех replication слотов
     */
    fun getAllSlots(): List<SlotInfo> {
        val slots = mutableListOf<SlotInfo>()
        dataSource.connection.use { conn ->
            val sql = "SELECT * FROM pg_replication_slots ORDER BY slot_name"
            conn.createStatement().use { stmt ->
                val rs = stmt.executeQuery(sql)
                while (rs.next()) {
                    slots.add(
                        SlotInfo(
                            slotName = rs.getString("slot_name"),
                            plugin = rs.getString("plugin"),
                            slotType = rs.getString("slot_type"),
                            database = rs.getString("database"),
                            active = rs.getBoolean("active"),
                            restartLsn = rs.getString("restart_lsn"),
                            confirmedFlushLsn = rs.getString("confirmed_flush_lsn"),
                            walStatus = rs.getString("wal_status")
                        )
                    )
                }
            }
        }
        return slots
    }
}

/**
 * Информация о replication слоте
 */
data class SlotInfo(
    val slotName: String,
    val plugin: String,
    val slotType: String,
    val database: String,
    val active: Boolean,
    val restartLsn: String,
    val confirmedFlushLsn: String,
    val walStatus: String?
)
