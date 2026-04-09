package engine

import logging.MetricsService
import logging.logConnectionDetailed
import utils.HikariFactory
import java.util.*
import java.util.concurrent.ConcurrentHashMap
import javax.sql.DataSource

/**
 * Сервис маппинга UUID в BIGINT
 * Критически оптимизирован для минимизации созданий соединений
 */
class MappingService(
    private val targetDataSource: DataSource
) {

    private val cache = ConcurrentHashMap<UUID, Long>()

    // Кэш для batch операций - уменьшает количество обращений к БД
    private val batchCache = ConcurrentHashMap<String, Map<UUID, Long>>()

    init {
        createMappingTable()
        // Регистрируем Gauge для размера кэша
        MetricsService.registerMappingCacheSizeSupplier { cache.size }
    }

    companion object {
        private const val CACHE_SIZE_LIMIT = 500_000
    }

    private fun createMappingTable() {
        "mapping_create_table".logConnectionDetailed {
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
    }

    /**
     * Предзагрузка маппинга для таблицы в кэш
     */
    fun preloadTableMapping(tableName: String) {
        if (batchCache.containsKey(tableName)) return

        "preload_mapping_$tableName".logConnectionDetailed {
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
                    if (cache.size < CACHE_SIZE_LIMIT) {
                        cache[uuid] = id
                    }
                }
            }
            batchCache[tableName] = mappings
        }
    }

    fun getAllMappedUuids(tableName: String): Set<UUID> {
        preloadTableMapping(tableName)
        return batchCache[tableName]?.keys ?: emptySet()
    }

    /**
     * Получение BIGINT ID по UUID
     * Использует только кэш - без соединений!
     */
    fun getNewId(tableName: String, oldUuid: UUID): Long? {
        // Сначала ищем в глобальном кэше (О(1), без БД)
        cache[oldUuid]?.let { return it }

        // Ищем в пакетном кэше текущей транзакции
        val tableMappings = batchCache[tableName]
        if (tableMappings != null && tableMappings.containsKey(oldUuid)) {
            return tableMappings[oldUuid]
        }

        // Ищем в базе данных (если процесс только что запустился)
        targetDataSource.connection.use { conn ->
            val sql = "SELECT new_id FROM migration_mapping WHERE table_name = ? AND old_uuid = ?"
            conn.prepareStatement(sql).use { pstmt ->
                pstmt.setString(1, tableName)
                pstmt.setObject(2, oldUuid)
                val rs = pstmt.executeQuery()
                if (rs.next()) {
                    val newId = rs.getLong("new_id")
                    // Сохраняем в RAM, чтобы в следующий раз отдать мгновенно
                    if (cache.size < CACHE_SIZE_LIMIT) {
                        cache[oldUuid] = newId
                    }
                    return newId
                }
            }
        }

        // Если не нашли даже в БД — значит данных действительно нет
        return null
    }

    /**
     * Пакетное сохранение маппинга
     * Оптимизировано: один запрос на пакет
     */
    fun saveMappingBatch(tableName: String, mappings: Map<UUID, Long>) {
        // Обновляем кэши
        mappings.forEach { (uuid, newId) ->
            if (cache.size < CACHE_SIZE_LIMIT) {
                cache[uuid] = newId
            }
        }

        val tableCache = batchCache.getOrPut(tableName) { mutableMapOf() }
        (tableCache as? MutableMap)?.putAll(mappings)

        // Сохраняем в БД
        "save_mapping_batch_$tableName".logConnectionDetailed {
            HikariFactory.saveMappingBatch(targetDataSource, tableName, mappings)
        }
    }

    fun saveMappingInMemory(oldUuid: UUID, newId: Long) {
        if (cache.size < CACHE_SIZE_LIMIT) {
            cache[oldUuid] = newId
        }
    }

}
