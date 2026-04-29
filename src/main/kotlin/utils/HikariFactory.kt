package utils

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import io.micrometer.core.instrument.MeterRegistry
import logging.MetricsService
import java.sql.Statement
import java.util.UUID
import javax.sql.DataSource

object HikariFactory {

    private const val SAVE_MAPPING_SQL =
        "INSERT INTO migration_mapping (table_name, old_uuid, new_id) VALUES (?, ?, ?) ON CONFLICT DO NOTHING"

    private const val LOOKUP_MAPPING_SQL =
        "SELECT new_id FROM migration_mapping WHERE table_name = ? AND old_uuid = ?"

    /**
     * Создаёт пул соединений HikariCP с подключением к Micrometer.
     */
    fun createDataSource(
        jdbcUrl: String,
        user: String,
        password: String,
        maxPoolSize: Int,
    ): HikariDataSource {
        return HikariDataSource(HikariConfig().apply {
            this.jdbcUrl = jdbcUrl
            this.username = user
            this.password = password
            this.maximumPoolSize = maxPoolSize
            this.minimumIdle = 2
            this.connectionTimeout = 30000
            this.validationTimeout = 5000

            this.addDataSourceProperty("stringtype", "unspecified")

            this.metricRegistry = MetricsService.registry
        })
    }

    // ==================== Mapping persistence ====================

    /**
     * Batch-сохранение маппингов UUID → BIGINT в таблицу migration_mapping.
     * Создаёт своё соединение, делает commit и закрывает его.
     */
    fun saveMappingBatch(ds: DataSource, tableName: String, mappings: Map<UUID, Long>): Map<UUID, Long> {
        if (mappings.isEmpty()) return emptyMap()

        val mappingEntries = mappings.entries.toList()
        ds.connection.use { conn ->
            conn.autoCommit = false
            conn.prepareStatement(SAVE_MAPPING_SQL).use { pstmt ->
                mappingEntries.forEach { (uuid, newId) ->
                    pstmt.setString(1, tableName)
                    pstmt.setObject(2, uuid)
                    pstmt.setLong(3, newId)
                    pstmt.addBatch()
                }
                val results = pstmt.executeBatch()
                conn.commit()
                return insertedMappings(mappingEntries, results)
            }
        }
    }

    /**
     * Batch-сохранение маппингов в существующем соединении (без commit).
     * Вызывающий сам управляет транзакцией.
     */
    fun saveMappingBatchInConnection(conn: java.sql.Connection, tableName: String, mappings: Map<UUID, Long>): Map<UUID, Long> {
        if (mappings.isEmpty()) return emptyMap()

        val mappingEntries = mappings.entries.toList()
        conn.prepareStatement(SAVE_MAPPING_SQL).use { pstmt ->
            mappingEntries.forEach { (uuid, newId) ->
                pstmt.setString(1, tableName)
                pstmt.setObject(2, uuid)
                pstmt.setLong(3, newId)
                pstmt.addBatch()
            }
            val results = pstmt.executeBatch()
            return insertedMappings(mappingEntries, results)
        }
    }

    private fun insertedMappings(
        mappingEntries: List<Map.Entry<UUID, Long>>,
        results: IntArray
    ): Map<UUID, Long> {
        return mappingEntries
            .zip(results.asIterable())
            .filter { (_, result) -> result > 0 || result == Statement.SUCCESS_NO_INFO }
            .associate { (entry, _) -> entry.key to entry.value }
    }

    /**
     * Поиск BIGINT ID по UUID из migration_mapping.
     * При нахождении — обновляет переданный кэш.
     *
     * @param ds пул соединений (обычно target)
     * @param tableName имя таблицы
     * @param oldUuid искомый UUID
     * @param cache кэш для обновления при находке
     * @param cacheLimit лимит кэша
     * @return найденный BIGINT ID или null
     */
    fun lookupMapping(
        ds: DataSource,
        tableName: String,
        oldUuid: UUID,
        cache: MutableMap<UUID, Long>,
        cacheLimit: Int
    ): Long? {
        ds.connection.use { conn ->
            conn.prepareStatement(LOOKUP_MAPPING_SQL).use { pstmt ->
                pstmt.setString(1, tableName)
                pstmt.setObject(2, oldUuid)
                val rs = pstmt.executeQuery()
                if (rs.next()) {
                    val id = rs.getLong("new_id")
                    if (cache.size < cacheLimit) {
                        cache[oldUuid] = id
                    }
                    return id
                }
            }
        }
        return null
    }
}
