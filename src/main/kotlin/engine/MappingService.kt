package engine

import com.github.benmanes.caffeine.cache.Cache
import com.github.benmanes.caffeine.cache.Caffeine
import logging.MetricsService
import logging.PerformanceLogger
import logging.logConnectionDetailed
import org.slf4j.LoggerFactory
import utils.HikariFactory
import java.util.concurrent.ConcurrentHashMap
import java.util.*
import javax.sql.DataSource

private data class MappingKey(val tableName: String, val oldUuid: UUID)

private fun recordMappingDbLookup(strategy: String, tableName: String, startedAtMs: Long, found: Boolean) {
    val durationMs = System.currentTimeMillis() - startedAtMs
    MetricsService.recordMappingDbLookup(strategy, tableName, durationMs, found)
    PerformanceLogger.logMappingDbLookup(strategy, tableName, durationMs, found)
}

private fun fetchMappingFromDatabase(
    targetDataSource: DataSource,
    strategy: String,
    tableName: String,
    oldUuid: UUID
): Long? {
    val startedAt = System.currentTimeMillis()
    var found = false

    try {
        targetDataSource.connection.use { conn ->
            conn.prepareStatement("SELECT new_id FROM migration_mapping WHERE table_name = ? AND old_uuid = ?").use { pstmt ->
                pstmt.setString(1, tableName)
                pstmt.setObject(2, oldUuid)
                val rs = pstmt.executeQuery()
                if (rs.next()) {
                    found = true
                    return rs.getLong("new_id")
                }
                return null
            }
        }
    } finally {
        recordMappingDbLookup(strategy, tableName, startedAt, found)
    }
}

private fun loadMappingsFromDatabase(
    targetDataSource: DataSource,
    tableName: String,
    onMapping: (UUID, Long) -> Unit
): Int {
    var count = 0
    targetDataSource.connection.use { conn ->
        conn.prepareStatement("SELECT old_uuid, new_id FROM migration_mapping WHERE table_name = ?").use { pstmt ->
            pstmt.fetchSize = 10000
            pstmt.setString(1, tableName)
            val rs = pstmt.executeQuery()
            while (rs.next()) {
                onMapping(rs.getObject("old_uuid") as UUID, rs.getLong("new_id"))
                count++
            }
        }
    }
    return count
}

/**
 * LAZY strategy: bounded Caffeine cache with database lookup on cache miss.
 */
class MappingService(
    targetDataSource: DataSource,
    cacheLimit: Long
) : MappingServiceBase(targetDataSource, cacheLimit) {

    private val logger = LoggerFactory.getLogger(MappingService::class.java)

    private val cache: Cache<MappingKey, Long> = Caffeine.newBuilder()
        .maximumSize(if (cacheLimit <= 0) 100_000_000L else cacheLimit)
        .initialCapacity(10_000)
        .recordStats()
        .build()

    init {
        createMappingTable()
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
        val tempMap = mutableMapOf<MappingKey, Long>()

        val count = loadMappingsFromDatabase(targetDataSource, tableName) { oldUuid, newId ->
            tempMap[MappingKey(tableName, oldUuid)] = newId
            if (tempMap.size >= 10000) {
                cache.putAll(tempMap)
                tempMap.clear()
            }
        }
        if (tempMap.isNotEmpty()) {
            cache.putAll(tempMap)
        }

        logger.info("Preloaded $count mappings for $tableName in ${System.currentTimeMillis() - startTime}ms")
    }

    override fun getNewId(tableName: String, oldUuid: UUID): Long? {
        cache.getIfPresent(MappingKey("", oldUuid))?.let { return it }

        val key = MappingKey(tableName, oldUuid)
        val cachedId = cache.getIfPresent(key)
        if (cachedId != null) return cachedId

        val idFromDb = fetchMappingFromDatabase(targetDataSource, "LAZY", tableName, oldUuid)
        if (idFromDb != null) {
            cache.put(key, idFromDb)
        }
        return idFromDb
    }

    override fun getNewIds(tableName: String, oldUuids: Collection<UUID>): Map<UUID, Long> {
        val result = mutableMapOf<UUID, Long>()
        val misses = mutableListOf<UUID>()

        oldUuids.distinct().forEach { uuid ->
            cache.getIfPresent(MappingKey("", uuid))?.let {
                result[uuid] = it
                return@forEach
            }

            val key = MappingKey(tableName, uuid)
            val cachedId = cache.getIfPresent(key)
            if (cachedId != null) {
                result[uuid] = cachedId
            } else {
                misses.add(uuid)
            }
        }

        if (misses.isEmpty()) return result

        val startedAt = System.currentTimeMillis()
        var found = false
        try {
            targetDataSource.connection.use { conn ->
                val uuidArray = conn.createArrayOf("uuid", misses.toTypedArray())
                conn.prepareStatement("SELECT old_uuid, new_id FROM migration_mapping WHERE table_name = ? AND old_uuid = ANY(?)").use { pstmt ->
                    pstmt.setString(1, tableName)
                    pstmt.setArray(2, uuidArray)
                    val rs = pstmt.executeQuery()
                    while (rs.next()) {
                        found = true
                        val uuid = rs.getObject("old_uuid") as UUID
                        val newId = rs.getLong("new_id")
                        result[uuid] = newId
                        cache.put(MappingKey(tableName, uuid), newId)
                    }
                }
            }
        } finally {
            recordMappingDbLookup("LAZY", tableName, startedAt, found)
        }

        return result
    }

    override fun saveMappingBatch(tableName: String, mappings: Map<UUID, Long>, conn: java.sql.Connection?) {
        val insertedMappings = "save_mapping_batch_$tableName".logConnectionDetailed {
            if (conn != null) {
                HikariFactory.saveMappingBatchInConnection(conn, tableName, mappings)
            } else {
                HikariFactory.saveMappingBatch(targetDataSource, tableName, mappings)
            }
        }
        cache.putAll(insertedMappings.mapKeys { MappingKey(tableName, it.key) })
    }

    override fun saveMappingInMemory(oldUuid: UUID, newId: Long) {
        if (cacheLimit > 0 && cache.estimatedSize() >= cacheLimit) return
        cache.put(MappingKey("", oldUuid), newId)
    }

    override fun replaceMapping(tableName: String, oldUuid: UUID, newUuid: UUID, targetId: Long) {
        cache.invalidate(MappingKey(tableName, oldUuid))
        cache.put(MappingKey(tableName, newUuid), targetId)
    }

    override fun getCacheStats(): Map<String, Any> {
        cache.cleanUp()
        val stats = cache.stats()
        return mapOf(
            "strategy" to "LAZY",
            "cache_size" to cache.estimatedSize(),
            "lazy_cache_size" to cache.estimatedSize(),
            "pinned_cache_size" to 0L,
            "hit_rate" to stats.hitRate(),             // % попаданий в кэш
            "eviction_count" to stats.evictionCount(), // Сколько старых элементов было удалено
            "request_count" to stats.requestCount(),   // Общее число запросов к кэшу
            "miss_count" to stats.missCount()          // Сколько раз пришлось лезть в БД
        )
    }
}

/**
 * EAGER strategy: preload mappings and serve lookups from memory only.
 * Missing keys are treated as absent, avoiding per-row database round-trips.
 */
class EagerMappingService(
    targetDataSource: DataSource
) : MappingServiceBase(targetDataSource, Long.MAX_VALUE) {

    private val logger = LoggerFactory.getLogger(EagerMappingService::class.java)
    private val cache = ConcurrentHashMap<MappingKey, Long>()

    init {
        createMappingTable()
    }

    private fun createMappingTable() {
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

    override fun preloadMappings(tableName: String) {
        logger.info("EAGER preload mappings for table $tableName...")
        val count = loadMappingsFromDatabase(targetDataSource, tableName) { oldUuid, newId ->
            cache[MappingKey(tableName, oldUuid)] = newId
        }

        logger.info("EAGER preloaded $count mappings for $tableName")
    }

    override fun getNewId(tableName: String, oldUuid: UUID): Long? =
        cache[MappingKey("", oldUuid)] ?: cache[MappingKey(tableName, oldUuid)]

    override fun getNewIds(tableName: String, oldUuids: Collection<UUID>): Map<UUID, Long> =
        oldUuids.distinct().mapNotNull { uuid ->
            (cache[MappingKey("", uuid)] ?: cache[MappingKey(tableName, uuid)])?.let { uuid to it }
        }.toMap()

    override fun saveMappingBatch(tableName: String, mappings: Map<UUID, Long>, conn: java.sql.Connection?) {
        val insertedMappings = if (conn != null) {
            HikariFactory.saveMappingBatchInConnection(conn, tableName, mappings)
        } else {
            HikariFactory.saveMappingBatch(targetDataSource, tableName, mappings)
        }
        insertedMappings.forEach { (uuid, id) -> cache[MappingKey(tableName, uuid)] = id }
    }

    override fun saveMappingInMemory(oldUuid: UUID, newId: Long) {
        cache[MappingKey("", oldUuid)] = newId
    }

    override fun replaceMapping(tableName: String, oldUuid: UUID, newUuid: UUID, targetId: Long) {
        cache.remove(MappingKey(tableName, oldUuid))
        cache[MappingKey(tableName, newUuid)] = targetId
    }

    override fun getCacheStats(): Map<String, Any> =
        mapOf(
            "strategy" to "EAGER",
            "cache_size" to cache.size.toLong(),
            "lazy_cache_size" to 0L,
            "pinned_cache_size" to cache.size.toLong(),
            "hit_rate" to 1.0,
            "eviction_count" to 0L,
            "request_count" to 0L,
            "miss_count" to 0L
        )
}

/**
 * HYBRID strategy: pinned in-memory mappings for selected small tables,
 * bounded Caffeine cache with database lookup for the rest.
 */
class HybridMappingService(
    targetDataSource: DataSource,
    cacheLimit: Long
) : MappingServiceBase(targetDataSource, cacheLimit) {

    private val logger = LoggerFactory.getLogger(HybridMappingService::class.java)
    private val pinnedTables = ConcurrentHashMap.newKeySet<String>()
    private val pinnedCache = ConcurrentHashMap<MappingKey, Long>()
    private val lazyLimit = (cacheLimit - (cacheLimit / 2)).coerceAtLeast(1L)
    private val lazyCache: Cache<MappingKey, Long> = Caffeine.newBuilder()
        .maximumSize(if (cacheLimit <= 0) 100_000_000L else lazyLimit)
        .initialCapacity(10_000)
        .recordStats()
        .build()

    init {
        createMappingTable()
    }

    private fun createMappingTable() {
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

    override fun configurePinnedTables(tableNames: Set<String>) {
        pinnedTables.clear()
        pinnedTables.addAll(tableNames)
        logger.info("HYBRID pinned tables: ${tableNames.joinToString(", ")}")
    }

    override fun preloadMappings(tableName: String) {
        if (tableName !in pinnedTables) return

        logger.info("HYBRID preload pinned mappings for table $tableName...")
        val count = loadMappingsFromDatabase(targetDataSource, tableName) { oldUuid, newId ->
            pinnedCache[MappingKey(tableName, oldUuid)] = newId
        }
        logger.info("HYBRID preloaded $count pinned mappings for $tableName")
    }

    override fun getNewId(tableName: String, oldUuid: UUID): Long? {
        pinnedCache[MappingKey("", oldUuid)]?.let { return it }
        lazyCache.getIfPresent(MappingKey("", oldUuid))?.let { return it }

        val key = MappingKey(tableName, oldUuid)
        pinnedCache[key]?.let { return it }

        val cachedId = lazyCache.getIfPresent(key)
        if (cachedId != null) return cachedId

        val idFromDb = fetchMappingFromDatabase(targetDataSource, "HYBRID", tableName, oldUuid)
        if (idFromDb != null) {
            if (tableName in pinnedTables) {
                pinnedCache[key] = idFromDb
            } else {
                lazyCache.put(key, idFromDb)
            }
        }
        return idFromDb
    }

    override fun getNewIds(tableName: String, oldUuids: Collection<UUID>): Map<UUID, Long> {
        val result = mutableMapOf<UUID, Long>()
        val misses = mutableListOf<UUID>()
        val isPinned = tableName in pinnedTables

        oldUuids.distinct().forEach { uuid ->
            pinnedCache[MappingKey("", uuid)]?.let {
                result[uuid] = it
                return@forEach
            }
            lazyCache.getIfPresent(MappingKey("", uuid))?.let {
                result[uuid] = it
                return@forEach
            }

            val key = MappingKey(tableName, uuid)
            val cachedId = pinnedCache[key] ?: lazyCache.getIfPresent(key)
            if (cachedId != null) {
                result[uuid] = cachedId
            } else {
                misses.add(uuid)
            }
        }

        if (misses.isEmpty()) return result

        val startedAt = System.currentTimeMillis()
        var found = false
        try {
            targetDataSource.connection.use { conn ->
                val uuidArray = conn.createArrayOf("uuid", misses.toTypedArray())
                conn.prepareStatement("SELECT old_uuid, new_id FROM migration_mapping WHERE table_name = ? AND old_uuid = ANY(?)").use { pstmt ->
                    pstmt.setString(1, tableName)
                    pstmt.setArray(2, uuidArray)
                    val rs = pstmt.executeQuery()
                    while (rs.next()) {
                        found = true
                        val uuid = rs.getObject("old_uuid") as UUID
                        val newId = rs.getLong("new_id")
                        result[uuid] = newId
                        if (isPinned) {
                            pinnedCache[MappingKey(tableName, uuid)] = newId
                        } else {
                            lazyCache.put(MappingKey(tableName, uuid), newId)
                        }
                    }
                }
            }
        } finally {
            recordMappingDbLookup("HYBRID", tableName, startedAt, found)
        }

        return result
    }

    override fun saveMappingBatch(tableName: String, mappings: Map<UUID, Long>, conn: java.sql.Connection?) {
        val insertedMappings = if (conn != null) {
            HikariFactory.saveMappingBatchInConnection(conn, tableName, mappings)
        } else {
            HikariFactory.saveMappingBatch(targetDataSource, tableName, mappings)
        }
        insertedMappings.forEach { (uuid, id) ->
            val key = MappingKey(tableName, uuid)
            if (tableName in pinnedTables) {
                pinnedCache[key] = id
            } else {
                lazyCache.put(key, id)
            }
        }
    }

    override fun saveMappingInMemory(oldUuid: UUID, newId: Long) {
        if (cacheLimit > 0 && lazyCache.estimatedSize() >= lazyLimit) return
        lazyCache.put(MappingKey("", oldUuid), newId)
    }

    override fun replaceMapping(tableName: String, oldUuid: UUID, newUuid: UUID, targetId: Long) {
        val oldKey = MappingKey(tableName, oldUuid)
        val newKey = MappingKey(tableName, newUuid)
        pinnedCache.remove(oldKey)
        lazyCache.invalidate(oldKey)
        if (tableName in pinnedTables) {
            pinnedCache[newKey] = targetId
        } else {
            lazyCache.put(newKey, targetId)
        }
    }

    override fun getCacheStats(): Map<String, Any> {
        lazyCache.cleanUp()
        val stats = lazyCache.stats()
        return mapOf(
            "strategy" to "HYBRID",
            "cache_size" to pinnedCache.size.toLong() + lazyCache.estimatedSize(),
            "lazy_cache_size" to lazyCache.estimatedSize(),
            "pinned_cache_size" to pinnedCache.size.toLong(),
            "hit_rate" to stats.hitRate(),
            "eviction_count" to stats.evictionCount(),
            "request_count" to stats.requestCount(),
            "miss_count" to stats.missCount()
        )
    }
}
