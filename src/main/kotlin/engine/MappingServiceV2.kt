package engine

import org.slf4j.LoggerFactory
import java.util.*
import java.util.concurrent.ConcurrentHashMap
import javax.sql.DataSource

/**
 * Сервис маппинга UUID в BIGINT
 * Версия 2: Полная предзагрузка всех маппингов перед миграцией
 */
class MappingServiceV2(
    private val targetDataSource: DataSource
) {

    private val logger = LoggerFactory.getLogger(MappingServiceV2::class.java)
    
    // Двухуровневый кэш: все данные загружаются заранее
    private val cache = ConcurrentHashMap<UUID, Long>()
    private val tableCache = ConcurrentHashMap<String, Map<UUID, Long>>()

    init {
        createMappingTable()
    }

    companion object {
        private const val CACHE_SIZE_LIMIT = 10_000_000 // Увеличенный лимит
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

    /**
     * Предзагрузка ВСЕХ маппингов перед началом миграции
     * Критически важно для производительности!
     */
    fun preloadAllMappings(tables: List<String>) {
        logger.info("Preloading all mappings for ${tables.size} tables...")
        val startTime = System.currentTimeMillis()
        
        tables.forEach { tableName ->
            val start = System.currentTimeMillis()
            val mappings = mutableMapOf<UUID, Long>()
            
            targetDataSource.connection.use { conn ->
                val pstmt = conn.prepareStatement(
                    "SELECT old_uuid, new_id FROM migration_mapping WHERE table_name = ?"
                )
                pstmt.setString(1, tableName)
                val rs = pstmt.executeQuery()
                while (rs.next()) {
                    val uuid = rs.getObject("old_uuid") as UUID
                    val id = rs.getLong("new_id")
                    mappings[uuid] = id
                }
            }
            
            tableCache[tableName] = mappings
            mappings.forEach { cache[it.key] = it.value }
            
            logger.debug("Loaded ${mappings.size} mappings for $tableName in ${System.currentTimeMillis() - start}ms")
        }
        
        logger.info("Preloaded ${cache.size} total mappings in ${System.currentTimeMillis() - startTime}ms")
    }

    /**
     * Получение BIGINT ID по UUID
     * ТОЛЬКО из кэша - никаких соединений!
     */
    fun getNewId(tableName: String, oldUuid: UUID): Long? {
        // Проверка в глобальном кэше
        cache[oldUuid]?.let { return it }

        // Проверка в табличном кэше
        tableCache[tableName]?.let { mappings ->
            return mappings[oldUuid]
        }
        
        // Если не нашли — это ошибка (данных ещё нет)
        return null
    }

    /**
     * Пакетное сохранение маппинга
     */
    fun saveMappingBatch(tableName: String, mappings: Map<UUID, Long>) {
        // Обновляем кэши
        mappings.forEach { (uuid, newId) ->
            if (cache.size < CACHE_SIZE_LIMIT) {
                cache[uuid] = newId
            }
        }
        
        val tableMappings = tableCache.getOrPut(tableName) { mutableMapOf() }
        (tableMappings as? MutableMap)?.putAll(mappings)
        
        // Сохраняем в БД
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

    fun getAllMappedUuids(tableName: String): Set<UUID> {
        return tableCache[tableName]?.keys ?: emptySet()
    }
    
    /**
     * Статистика кэша
     */
    fun getCacheStats(): Map<String, Any> {
        return mapOf(
            "cache_size" to cache.size,
            "cache_limit" to CACHE_SIZE_LIMIT,
            "table_caches" to tableCache.size,
            "memory_usage_mb" to (Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory()) / 1024.0 / 1024.0
        )
    }
}
