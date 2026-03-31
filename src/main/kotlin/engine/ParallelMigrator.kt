package engine

import core.MetadataReader
import core.TableRelation
import kotlinx.coroutines.*
import org.slf4j.LoggerFactory
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import javax.sql.DataSource

/**
 * Параллельный мигратор для независимых таблиц
 * Использует корутины для конкурентной обработки
 */
class ParallelMigrator(
    private val sourceDataSource: DataSource,
    private val targetDataSource: DataSource,
    private val mappingService: MappingService,
    private val metadataReader: MetadataReader,
    private val maxParallelTables: Int = 4
) {
    private val logger = LoggerFactory.getLogger(ParallelMigrator::class.java)
    private val coroutineScope = CoroutineScope(
        Dispatchers.IO + SupervisorJob()
    )

    /**
     * Статистика параллельной миграции
     */
    data class ParallelMigrationStats(
        val totalTables: Int,
        val completedTables: Int,
        val failedTables: Int,
        val totalRows: Long,
        val totalDuration: Long,
        val averageRowsPerSec: Double,
        val tableStats: List<TableMigrationStats>
    )

    data class TableMigrationStats(
        val tableName: String,
        val rows: Long,
        val duration: Long,
        val success: Boolean,
        val errorMessage: String? = null
    )

    /**
     * Параллельная миграция таблиц с учётом зависимостей
     */
    fun migrateParallel(tables: List<String>, relations: List<TableRelation>): ParallelMigrationStats {
        logger.info("Starting parallel migration with $maxParallelTables concurrent tables")

        val startTime = System.currentTimeMillis()
        val completedCount = AtomicInteger(0)
        val failedCount = AtomicInteger(0)
        val totalRows = ConcurrentHashMap<String, Long>()
        val tableStats = ConcurrentHashMap<String, TableMigrationStats>()

        // Строим граф зависимостей для определения уровней параллелизма
        val dependencyGraph = buildDependencyGraph(tables, relations)
        val levels = topologicalLevels(tables, dependencyGraph)

        logger.info("Migration plan: ${levels.size} levels")
        levels.forEachIndexed { index, level ->
            logger.info("  Level $index: ${level.joinToString(", ")}")
        }

        // Мигрируем уровень за уровнем
        levels.forEach { level ->
            logger.info("Migrating level with ${level.size} tables in parallel")

            runBlocking {
                val jobs = level.map { tableName ->
                    async {
                        val tableStart = System.currentTimeMillis()
                        try {
                            migrateTable(tableName)
                            val duration = System.currentTimeMillis() - tableStart
                            val rows = getRowCount(tableName)
                            totalRows[tableName] = rows
                            tableStats[tableName] = TableMigrationStats(
                                tableName = tableName,
                                rows = rows,
                                duration = duration,
                                success = true
                            )
                            completedCount.incrementAndGet()
                            logger.info("Completed $tableName: $rows rows in ${duration}ms")
                        } catch (e: Exception) {
                            val duration = System.currentTimeMillis() - tableStart
                            tableStats[tableName] = TableMigrationStats(
                                tableName = tableName,
                                rows = 0,
                                duration = duration,
                                success = false,
                                errorMessage = e.message
                            )
                            failedCount.incrementAndGet()
                            logger.error("Failed $tableName: ${e.message}", e)
                        }
                    }
                }

                // Ждём завершения всех таблиц на уровне
                jobs.awaitAll()
            }
        }

        val totalDuration = System.currentTimeMillis() - startTime
        val allRows = totalRows.values.sum()

        return ParallelMigrationStats(
            totalTables = tables.size,
            completedTables = completedCount.get(),
            failedTables = failedCount.get(),
            totalRows = allRows,
            totalDuration = totalDuration,
            averageRowsPerSec = if (totalDuration > 0) allRows * 1000.0 / totalDuration else 0.0,
            tableStats = tableStats.values.toList()
        )
    }

    /**
     * Миграция одной таблицы
     */
    private fun migrateTable(tableName: String) {
        val migrator = DataMigrator(
            sourceDataSource,
            targetDataSource,
            mappingService,
            metadataReader
        )
        migrator.migrateTable(tableName)
    }

    /**
     * Получение количества строк в таблице
     */
    private fun getRowCount(tableName: String): Long {
        targetDataSource.connection.use { conn ->
            val rs = conn.createStatement().executeQuery("SELECT COUNT(*) FROM $tableName")
            return if (rs.next()) rs.getLong(1) else 0L
        }
    }

    /**
     * Построение графа зависимостей
     */
    private fun buildDependencyGraph(
        tables: List<String>,
        relations: List<TableRelation>
    ): Map<String, Set<String>> {
        val graph = mutableMapOf<String, MutableSet<String>>()

        // Инициализируем все таблицы
        tables.forEach { table ->
            graph[table] = mutableSetOf()
        }

        // Добавляем зависимости (родитель → потомок)
        relations.forEach { relation ->
            if (graph.containsKey(relation.parentTable) && graph.containsKey(relation.childTable)) {
                graph[relation.childTable]!!.add(relation.parentTable)
            }
        }

        return graph
    }

    /**
     * Определение уровней параллелизма через модифицированный алгоритм Кана
     */
    private fun topologicalLevels(
        tables: List<String>,
        graph: Map<String, Set<String>>
    ): List<List<String>> {
        val levels = mutableListOf<List<String>>()
        val inDegree = mutableMapOf<String, Int>()
        val remaining = tables.toMutableSet()

        // Вычисляем степени вершин
        tables.forEach { table ->
            inDegree[table] = graph[table]?.size ?: 0
        }

        while (remaining.isNotEmpty()) {
            // Находим все вершины с нулевой степенью (нет зависимостей)
            val currentLevel = remaining.filter { table ->
                inDegree[table] == 0
            }

            if (currentLevel.isEmpty()) {
                throw IllegalStateException("Circular dependency detected in tables: $remaining")
            }

            levels.add(currentLevel)

            // Удаляем вершины текущего уровня и обновляем степени
            currentLevel.forEach { table ->
                remaining.remove(table)
                // Уменьшаем степень всех таблиц, которые зависят от текущей
                graph.forEach { (dependent, dependencies) ->
                    if (dependencies.contains(table)) {
                        inDegree[dependent] = (inDegree[dependent] ?: 0) - 1
                    }
                }
            }
        }

        return levels
    }

    /**
     * Остановка мигратора
     */
    fun shutdown() {
        logger.info("Shutting down parallel migrator")
        runBlocking {
            coroutineScope.cancel()
        }
    }
}
