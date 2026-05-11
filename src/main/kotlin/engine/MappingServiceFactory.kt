package engine

import org.slf4j.LoggerFactory
import java.util.*
import javax.sql.DataSource

/**
 * Фабрика для создания сервиса маппинга
 */
object MappingServiceFactory {
    private val logger = LoggerFactory.getLogger(MappingServiceFactory::class.java)

    fun create(
        targetDataSource: DataSource,
        strategy: MappingStrategy = MappingStrategy.EAGER,
        cacheLimit: Int = 10_000_000
    ): MappingServiceBase {
        logger.info("Creating mapping service with strategy: $strategy (cache limit: $cacheLimit)")
        return MappingService(targetDataSource, cacheLimit.toLong())
    }
}

/**
 * Базовый класс для всех сервисов маппинга
 */
abstract class MappingServiceBase(
    protected val targetDataSource: DataSource,
    protected val cacheLimit: Long
) {
    abstract fun getNewId(tableName: String, oldUuid: UUID): Long?
    abstract fun saveMappingInMemory(oldUuid: UUID, newId: Long)
    abstract fun saveMappingBatch(tableName: String, mappings: Map<UUID, Long>, conn: java.sql.Connection? = null)
    abstract fun replaceMapping(tableName: String, oldUuid: UUID, newUuid: UUID, targetId: Long)
    abstract fun getAllMappedUuids(tableName: String): Set<UUID>

    // Загрузка одной таблицы
    open fun preloadMappings(tableName: String) {}

    // ДОБАВЛЕНО: Загрузка списка таблиц (решает ошибку Unresolved reference)
    open fun preloadAllMappings(tableNames: List<String>) {
        tableNames.forEach { preloadMappings(it) }
    }

    open fun getCacheStats(): Map<String, Any> = emptyMap()
}