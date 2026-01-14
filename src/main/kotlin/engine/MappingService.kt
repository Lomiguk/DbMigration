package engine

import java.util.*
import java.util.concurrent.ConcurrentHashMap
import javax.sql.DataSource

class MappingService(private val targetDataSource: DataSource) {

    private val cache = ConcurrentHashMap<UUID, Long>()
    private val CACHE_SIZE_LIMIT = 500_000

    init {
        createMappingTable()
    }

    private fun createMappingTable() {
        targetDataSource.connection.use { conn ->
            conn.createStatement().execute("""
                CREATE TABLE IF NOT EXISTS migration_mapping (
                    table_name VARCHAR(100),
                    old_uuid UUID,
                    new_id BIGINT,
                    PRIMARY KEY (table_name, old_uuid)
                );
                CREATE INDEX IF NOT EXISTS idx_mapping_uuid ON migration_mapping(old_uuid);
            """.trimIndent())
        }
    }

    fun getAllMappedUuids(tableName: String): Set<UUID> {
        val uuids = mutableSetOf<UUID>()
        targetDataSource.connection.use { conn ->
            val pstmt = conn.prepareStatement(
                "SELECT old_uuid FROM migration_mapping WHERE table_name = ?"
            )
            pstmt.setString(1, tableName)
            val rs = pstmt.executeQuery()
            while (rs.next()) {
                uuids.add(rs.getObject("old_uuid") as UUID)
            }
        }
        return uuids
    }

    fun getNewId(tableName: String, oldUuid: UUID): Long? {
        // 1. Проверка в кэше
        cache[oldUuid]?.let { return it }

        // 2. Поиск в БД целевой системы
        targetDataSource.connection.use { conn ->
            val pstmt = conn.prepareStatement(
                "SELECT new_id FROM migration_mapping WHERE table_name = ? AND old_uuid = ?"
            )
            pstmt.setString(1, tableName)
            pstmt.setObject(2, oldUuid)
            val rs = pstmt.executeQuery()
            if (rs.next()) {
                val id = rs.getLong("new_id")
                if (cache.size < CACHE_SIZE_LIMIT) cache[oldUuid] = id
                return id
            }
        }
        return null
    }

    fun saveMappingBatch(tableName: String, mappings: Map<UUID, Long>) {
        targetDataSource.connection.use { conn ->
            conn.autoCommit = false
            val pstmt = conn.prepareStatement(
                "INSERT INTO migration_mapping (table_name, old_uuid, new_id) VALUES (?, ?, ?) ON CONFLICT DO NOTHING"
            )
            mappings.forEach { (uuid, newId) ->
                pstmt.setString(1, tableName)
                pstmt.setObject(2, uuid)
                pstmt.setLong(3, newId)
                pstmt.addBatch()
            }
            pstmt.executeBatch()
            conn.commit()
        }
    }

    fun saveMappingInMemory(oldUuid: UUID, newId: Long) {
        if (cache.size < CACHE_SIZE_LIMIT) {
            cache[oldUuid] = newId
        }
    }
}