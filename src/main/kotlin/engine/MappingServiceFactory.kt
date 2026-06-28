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
        return when (strategy) {
            MappingStrategy.EAGER -> EagerMappingService(targetDataSource)
            MappingStrategy.LAZY -> MappingService(targetDataSource, cacheLimit.toLong())
            MappingStrategy.HYBRID -> HybridMappingService(targetDataSource, cacheLimit.toLong())
        }
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

    open fun getNewIds(tableName: String, oldUuids: Collection<UUID>): Map<UUID, Long> =
        oldUuids
            .distinct()
            .mapNotNull { uuid -> getNewId(tableName, uuid)?.let { uuid to it } }
            .toMap()

    abstract fun saveMappingInMemory(oldUuid: UUID, newId: Long)

    abstract fun saveMappingBatch(tableName: String, mappings: Map<UUID, Long>, conn: java.sql.Connection? = null)

    abstract fun replaceMapping(tableName: String, oldUuid: UUID, newUuid: UUID, targetId: Long)

    open fun getAllMappedUuids(tableName: String): Set<UUID> {
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

    // Загрузка одной таблицы
    open fun preloadMappings(tableName: String) {}

    open fun preloadAllMappings(tableNames: List<String>) {
        tableNames.forEach { preloadMappings(it) }
    }

    open fun configurePinnedTables(tableNames: Set<String>) {}

    open fun getCacheStats(): Map<String, Any> = emptyMap()
}
