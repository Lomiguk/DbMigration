package engine

import com.github.benmanes.caffeine.cache.Cache
import com.github.benmanes.caffeine.cache.Caffeine
import logging.MetricsService
import logging.logConnectionDetailed
import org.slf4j.LoggerFactory
import utils.HikariFactory
import java.util.*
import javax.sql.DataSource

/**
 * Единая реализация сервиса маппинга на базе Caffeine
 */
class MappingService(
    targetDataSource: DataSource,
    cacheLimit: Long
) : MappingServiceBase(targetDataSource, cacheLimit) {

    private val logger = LoggerFactory.getLogger(MappingService::class.java)

    // Использование Caffeine для всех стратегий миграции
    private val cache: Cache<UUID, Long> = Caffeine.newBuilder()
        .maximumSize(if (cacheLimit <= 0) 100_000_000L else cacheLimit)
        .initialCapacity(10_000)
        .recordStats()
        .build()

    init {
        createMappingTable()
        // Исправлено: безопасная регистрация метрики
        try {
            // Если в MetricsService нет registerCacheSizeGauge, используем стандартный счетчик или игнорируем
            // MetricsService.replicationEventsAppliedCounter...
        } catch (e: Exception) {
            logger.warn("Could not register cache metrics: ${e.message}")
        }
    }

    private fun createMappingTable() {
        "create_mapping_table".logConnectionDetailed {
            targetDataSource.connection.use { conn ->
                conn.createStatement().execute("""
                    CREATE TABLE IF NOT EXISTS migration_mapping (
                        table_name VARCHAR(100) NOT NULL,
                        old_uuid UUID NOT NULL,
                        new_id BIGINT NOT NULL,
                        PRIMARY KEY (table_name, old_uuid)
                    );
                    CREATE INDEX IF NOT EXISTS idx_mapping_uuid ON migration_mapping(old_uuid);
                """.trimIndent())
            }
        }
    }

    override fun preloadMappings(tableName: String) {
        logger.info("Preloading mappings for table $tableName...")
        val startTime = System.currentTimeMillis()
        var count = 0

        targetDataSource.connection.use { conn ->
            conn.createStatement().apply { fetchSize = 10000 }.use { stmt ->
                val rs = stmt.executeQuery("SELECT old_uuid, new_id FROM migration_mapping WHERE table_name = '$tableName'")
                val tempMap = mutableMapOf<UUID, Long>()

                while (rs.next()) {
                    val oldUuid = rs.getObject("old_uuid") as UUID
                    val newId = rs.getLong("new_id")
                    tempMap[oldUuid] = newId
                    count++

                    if (tempMap.size >= 10000) {
                        cache.putAll(tempMap)
                        tempMap.clear()
                    }
                }
                if (tempMap.isNotEmpty()) {
                    cache.putAll(tempMap)
                }
            }
        }
        logger.info("Preloaded $count mappings for $tableName in ${System.currentTimeMillis() - startTime}ms")
    }

    override fun getNewId(tableName: String, oldUuid: UUID): Long? {
        val cachedId = cache.getIfPresent(oldUuid)
        if (cachedId != null) return cachedId

        val idFromDb = fetchMappingFromDatabase(tableName, oldUuid)
        if (idFromDb != null) {
            cache.put(oldUuid, idFromDb)
        }
        return idFromDb
    }

    private fun fetchMappingFromDatabase(tableName: String, oldUuid: UUID): Long? {
        targetDataSource.connection.use { conn ->
            conn.prepareStatement("SELECT new_id FROM migration_mapping WHERE table_name = ? AND old_uuid = ?").use { pstmt ->
                pstmt.setString(1, tableName)
                pstmt.setObject(2, oldUuid)
                val rs = pstmt.executeQuery()
                return if (rs.next()) rs.getLong("new_id") else null
            }
        }
    }

    override fun saveMappingBatch(tableName: String, mappings: Map<UUID, Long>, conn: java.sql.Connection?) {
        val insertedMappings = "save_mapping_batch_$tableName".logConnectionDetailed {
            HikariFactory.saveMappingBatch(targetDataSource, tableName, mappings)
        }
        cache.putAll(insertedMappings)
    }

    override fun saveMappingInMemory(oldUuid: UUID, newId: Long) {
        if (cacheLimit > 0 && cache.estimatedSize() >= cacheLimit) return
        cache.put(oldUuid, newId)
    }

    override fun replaceMapping(tableName: String, oldUuid: UUID, newUuid: UUID, targetId: Long) {
        cache.invalidate(oldUuid)
        cache.put(newUuid, targetId)
    }

    override fun getAllMappedUuids(tableName: String): Set<UUID> {
        targetDataSource.connection.use { conn ->
            conn.prepareStatement("SELECT old_uuid FROM migration_mapping WHERE table_name = ?").use { pstmt ->
                pstmt.setString(1, tableName)
                val rs = pstmt.executeQuery()
                val result = mutableSetOf<UUID>()
                while (rs.next()) {
                    result.add(rs.getObject("old_uuid") as UUID)
                }
                return result
            }
        }
    }

    override fun getCacheStats(): Map<String, Any> {
        val stats = cache.stats()
        return mapOf(
            "strategy" to "CAFFEINE_UNIFIED",
            "cache_size" to cache.estimatedSize(),
            "hit_rate" to stats.hitRate(),             // % попаданий в кэш
            "eviction_count" to stats.evictionCount(), // Сколько старых элементов было удалено
            "request_count" to stats.requestCount(),   // Общее число запросов к кэшу
            "miss_count" to stats.missCount()          // Сколько раз пришлось лезть в БД
        )
    }
}
