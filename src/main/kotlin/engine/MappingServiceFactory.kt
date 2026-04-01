package engine

import org.slf4j.LoggerFactory
import java.util.*
import java.util.concurrent.ConcurrentHashMap
import javax.sql.DataSource

/**
 * Фабрика для создания сервиса маппинга с выбранной стратегией
 */
object MappingServiceFactory {

    private val logger = LoggerFactory.getLogger(MappingServiceFactory::class.java)

    /**
     * Создание сервиса маппинга с выбранной стратегией
     */
    fun create(
        targetDataSource: DataSource,
        strategy: MappingStrategy = MappingStrategy.EAGER,
        cacheLimit: Int = 10_000_000
    ): MappingServiceBase {
        logger.info("Creating mapping service with strategy: $strategy (cache limit: $cacheLimit)")

        return when (strategy) {
            MappingStrategy.EAGER -> {
                logger.info("Using EAGER strategy: full preload before migration")
                EagerMappingService(targetDataSource, cacheLimit)
            }
            MappingStrategy.LAZY -> {
                logger.info("Using LAZY strategy: on-demand loading")
                LazyMappingService(targetDataSource, cacheLimit)
            }
            MappingStrategy.HYBRID -> {
                logger.info("Using HYBRID strategy: preload dictionaries, lazy for others")
                HybridMappingService(targetDataSource, cacheLimit)
            }
        }
    }
}

/**
 * Базовый класс для всех сервисов маппинга
 */
abstract class MappingServiceBase(
    protected val targetDataSource: DataSource,
    protected val cacheLimit: Int
) {
    /**
     * Получение BIGINT ID по UUID
     */
    abstract fun getNewId(tableName: String, oldUuid: UUID): Long?

    /**
     * Пакетное сохранение маппинга
     */
    abstract fun saveMappingBatch(tableName: String, mappings: Map<UUID, Long>)

    /**
     * Пакетное сохранение маппинга в указанном соединении
     */
    open fun saveMappingBatch(tableName: String, mappings: Map<UUID, Long>, conn: java.sql.Connection) {
        // По умолчанию создаёт новое соединение
        saveMappingBatch(tableName, mappings)
    }

    /**
     * Сохранение в память
     */
    abstract fun saveMappingInMemory(oldUuid: UUID, newId: Long)

    /**
     * Предзагрузка (опционально)
     */
    open fun preloadAllMappings(tables: List<String>) {
        // По умолчанию ничего не делает
    }

    /**
     * Получение всех замаппленных UUID для таблицы
     */
    open fun getAllMappedUuids(tableName: String): Set<UUID> {
        return emptySet()
    }

    /**
     * Статистика
     */
    abstract fun getCacheStats(): Map<String, Any>
}

/**
 * EAGER стратегия: полная предзагрузка
 */
class EagerMappingService(
    targetDataSource: DataSource,
    cacheLimit: Int = 10_000_000
) : MappingServiceBase(targetDataSource, cacheLimit) {

    private val logger = LoggerFactory.getLogger(EagerMappingService::class.java)
    private val cache = ConcurrentHashMap<UUID, Long>()
    private val tableCache = ConcurrentHashMap<String, Map<UUID, Long>>()

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

    override fun preloadAllMappings(tables: List<String>) {
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

    override fun getNewId(tableName: String, oldUuid: UUID): Long? {
        // ТОЛЬКО кэш - никаких соединений!
        cache[oldUuid]?.let { return it }
        tableCache[tableName]?.let { return it[oldUuid] }
        return null
    }

    override fun saveMappingBatch(tableName: String, mappings: Map<UUID, Long>) {
        // Обновляем кэши
        mappings.forEach { (uuid, newId) ->
            if (cache.size < cacheLimit) {
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

    override fun saveMappingInMemory(oldUuid: UUID, newId: Long) {
        if (cache.size < cacheLimit) {
            cache[oldUuid] = newId
        }
    }

    override fun saveMappingBatch(tableName: String, mappings: Map<UUID, Long>, conn: java.sql.Connection) {
        val start = System.currentTimeMillis()
        
        // Обновляем кэши
        mappings.forEach { (uuid, newId) ->
            if (cache.size < cacheLimit) {
                cache[uuid] = newId
            }
        }

        val tableMappings = tableCache.getOrPut(tableName) { mutableMapOf() }
        (tableMappings as? MutableMap)?.putAll(mappings)

        // Сохраняем в БД в текущем соединении - БЕЗ commit!
        // Используем try-with-resources для автоматического закрытия
        conn.prepareStatement(
            "INSERT INTO migration_mapping (table_name, old_uuid, new_id) VALUES (?, ?, ?) ON CONFLICT DO NOTHING"
        ).use { pstmt ->
            mappings.forEach { (uuid, newId) ->
                pstmt.setString(1, tableName)
                pstmt.setObject(2, uuid)
                pstmt.setLong(3, newId)
                pstmt.addBatch()
            }
            pstmt.executeBatch()
        }  // pstmt автоматически закрывается здесь
        
        val duration = System.currentTimeMillis() - start
        if (duration > 100) {  // Логируем только медленные батчи
            println("  [Mapping] Saved ${mappings.size} mappings in ${duration}ms")
        }
    }

    override fun getCacheStats(): Map<String, Any> {
        return mapOf(
            "strategy" to "EAGER",
            "cache_size" to cache.size,
            "cache_limit" to cacheLimit,
            "table_caches" to tableCache.size,
            "memory_usage_mb" to (Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory()) / 1024.0 / 1024.0
        )
    }

    override fun getAllMappedUuids(tableName: String): Set<UUID> {
        return tableCache[tableName]?.keys ?: emptySet()
    }
}

/**
 * LAZY стратегия: ленивая загрузка
 */
class LazyMappingService(
    targetDataSource: DataSource,
    cacheLimit: Int = 1_000_000
) : MappingServiceBase(targetDataSource, cacheLimit) {

    private val logger = LoggerFactory.getLogger(LazyMappingService::class.java)
    private val cache = ConcurrentHashMap<UUID, Long>()

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

    override fun getNewId(tableName: String, oldUuid: UUID): Long? {
        // Проверка кэша
        cache[oldUuid]?.let { return it }

        // Ленивая загрузка из БД
        targetDataSource.connection.use { conn ->
            val pstmt = conn.prepareStatement(
                "SELECT new_id FROM migration_mapping WHERE table_name = ? AND old_uuid = ?"
            )
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

        return null
    }

    override fun saveMappingBatch(tableName: String, mappings: Map<UUID, Long>) {
        mappings.forEach { (uuid, newId) ->
            if (cache.size < cacheLimit) {
                cache[uuid] = newId
            }
        }

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

    override fun saveMappingInMemory(oldUuid: UUID, newId: Long) {
        if (cache.size < cacheLimit) {
            cache[oldUuid] = newId
        }
    }

    override fun getCacheStats(): Map<String, Any> {
        return mapOf(
            "strategy" to "LAZY",
            "cache_size" to cache.size,
            "cache_limit" to cacheLimit,
            "memory_usage_mb" to (Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory()) / 1024.0 / 1024.0
        )
    }

    override fun getAllMappedUuids(tableName: String): Set<UUID> {
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
}

/**
 * HYBRID стратегия: справочники заранее, остальные лениво
 */
class HybridMappingService(
    targetDataSource: DataSource,
    cacheLimit: Int = 5_000_000
) : MappingServiceBase(targetDataSource, cacheLimit) {

    private val logger = LoggerFactory.getLogger(HybridMappingService::class.java)
    private val cache = ConcurrentHashMap<UUID, Long>()
    private val dictionaryTables = listOf("regions", "suppliers", "categories", "customers")

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

    override fun preloadAllMappings(tables: List<String>) {
        // Предзагружаем только справочники
        val dictionaries = tables.intersect(dictionaryTables)
        logger.info("Preloading dictionary tables: $dictionaries")

        dictionaries.forEach { tableName ->
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
            mappings.forEach { cache[it.key] = it.value }
            logger.debug("Loaded ${mappings.size} mappings for $tableName")
        }

        logger.info("Preloaded ${cache.size} dictionary mappings")
    }

    override fun getNewId(tableName: String, oldUuid: UUID): Long? {
        cache[oldUuid]?.let { return it }

        // Для справочников возвращаем null (должны быть предзагружены)
        if (tableName in dictionaryTables) {
            return null
        }

        // Для остальных - ленивая загрузка
        targetDataSource.connection.use { conn ->
            val pstmt = conn.prepareStatement(
                "SELECT new_id FROM migration_mapping WHERE table_name = ? AND old_uuid = ?"
            )
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

        return null
    }

    override fun saveMappingBatch(tableName: String, mappings: Map<UUID, Long>) {
        mappings.forEach { (uuid, newId) ->
            if (cache.size < cacheLimit) {
                cache[uuid] = newId
            }
        }

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

    override fun saveMappingInMemory(oldUuid: UUID, newId: Long) {
        if (cache.size < cacheLimit) {
            cache[oldUuid] = newId
        }
    }

    override fun getCacheStats(): Map<String, Any> {
        return mapOf(
            "strategy" to "HYBRID",
            "cache_size" to cache.size,
            "cache_limit" to cacheLimit,
            "dictionary_tables" to dictionaryTables.size,
            "memory_usage_mb" to (Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory()) / 1024.0 / 1024.0
        )
    }

    override fun getAllMappedUuids(tableName: String): Set<UUID> {
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
}
