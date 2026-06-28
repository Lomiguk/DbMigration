package utils

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import io.micrometer.core.instrument.MeterRegistry
import logging.MetricsService
import org.postgresql.copy.CopyManager
import org.postgresql.core.BaseConnection
import java.io.StringReader
import java.sql.Connection
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

        ds.connection.use { conn ->
            conn.autoCommit = false
            val saved = saveMappingBatchInConnection(conn, tableName, mappings)
            conn.commit()
            return saved
        }
    }

    /**
     * Batch-сохранение маппингов в существующем соединении (без commit).
     * Вызывающий сам управляет транзакцией.
     */
    fun saveMappingBatchInConnection(
        conn: Connection,
        tableName: String,
        mappings: Map<UUID, Long>
    ): Map<UUID, Long> {

        if (mappings.isEmpty()) return emptyMap()

        val baseConnection = try {
            conn.unwrap(BaseConnection::class.java)
        } catch (_: Exception) {
            null
        }
        if (baseConnection == null) {
            return saveMappingBatchWithJdbc(conn, tableName, mappings)
        }

        val mappingEntries = mappings.entries.toList()
        conn.createStatement().use { stmt ->
            stmt.execute(
                """
                CREATE TEMP TABLE IF NOT EXISTS tmp_migration_mapping (
                    table_name VARCHAR(100) NOT NULL,
                    old_uuid UUID NOT NULL,
                    new_id BIGINT NOT NULL
                ) ON COMMIT DROP
                """.trimIndent()
            )
            stmt.execute("TRUNCATE tmp_migration_mapping")
        }

        val csv = StringBuilder(mappingEntries.size * 64)
        mappingEntries.forEach { (uuid, newId) ->
            csv.append(csvValue(tableName)).append(',')
                .append(uuid).append(',')
                .append(newId).append('\n')
        }

        val copyManager = CopyManager(baseConnection)
        copyManager.copyIn(
            "COPY tmp_migration_mapping (table_name, old_uuid, new_id) FROM STDIN WITH (FORMAT csv, NULL '\\N')",
            StringReader(csv.toString())
        )

        conn.createStatement().use { stmt ->
            stmt.executeQuery(
                """
                INSERT INTO migration_mapping (table_name, old_uuid, new_id)
                SELECT table_name, old_uuid, new_id
                FROM tmp_migration_mapping
                ON CONFLICT DO NOTHING
                RETURNING old_uuid, new_id
                """.trimIndent()
            ).use { rs ->
                val inserted = linkedMapOf<UUID, Long>()
                while (rs.next()) {
                    inserted[rs.getObject("old_uuid") as UUID] = rs.getLong("new_id")
                }
                return inserted
            }
        }
    }

    private fun saveMappingBatchWithJdbc(
        conn: Connection,
        tableName: String,
        mappings: Map<UUID, Long>
    ): Map<UUID, Long> {
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
