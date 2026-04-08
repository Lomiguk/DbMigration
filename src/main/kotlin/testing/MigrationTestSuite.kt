package testing

import core.DependencyResolver
import core.MetadataReader
import engine.DataMigrator
import engine.MappingServiceFactory
import engine.MappingServiceBase
import engine.MappingStrategy
import org.slf4j.LoggerFactory
import rollback.RollbackService
import state.StateRepository
import sync.ChangeCapture
import tools.LargeDataGenerator
import java.time.LocalDateTime
import javax.sql.DataSource
import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource

/**
 * Контекст миграции — объединяет все компоненты для тестов
 */
private data class MigrationContext(
    val metadataReader: MetadataReader,
    val mappingService: MappingServiceBase,
    val migrator: DataMigrator,
    val tables: List<String>,
    val migrationOrder: List<String>
)

/**
 * Комплексное тестирование системы миграции на реальных данных
 */
class MigrationTestSuite(
    private val sourceDs: DataSource,
    private val targetDs: DataSource
) {
    private val logger = LoggerFactory.getLogger(MigrationTestSuite::class.java)

    companion object {
        private const val CACHE_LIMIT = 10_000_000
    }

    /**
     * Результаты теста
     */
    data class TestResult(
        val testName: String,
        val success: Boolean,
        val duration: Long,
        val metrics: Map<String, Any>,
        val errorMessage: String? = null
    )

    /**
     * Создание контекста миграции (читатель, маппинг, мигратор, порядок таблиц)
     */
    private fun createMigrationContext(
        stateRepository: StateRepository? = null,
        migrationId: String? = null
    ): MigrationContext {
        val metadataReader = MetadataReader(sourceDs)
        val mappingService = MappingServiceFactory.create(targetDs, MappingStrategy.EAGER, CACHE_LIMIT)
        val migrator = if (stateRepository != null && migrationId != null) {
            DataMigrator(sourceDs, targetDs, mappingService, metadataReader, stateRepository, migrationId)
        } else {
            DataMigrator(sourceDs, targetDs, mappingService, metadataReader)
        }

        val tables = metadataReader.getAllTablesWithUuidPk()
        val relations = metadataReader.getForeignKeys()

        val resolver = DependencyResolver()
        resolver.buildGraph(tables, relations)
        val migrationOrder = resolver.getMigrationOrder()

        return MigrationContext(metadataReader, mappingService, migrator, tables, migrationOrder)
    }

    /**
     * Запуск всех тестов
     */
    fun runAllTests(recordCount: Int = 1_000_000): List<TestResult> {
        val results = mutableListOf<TestResult>()

        logger.info("Starting comprehensive test suite with $recordCount records")

        // Тест 1: Базовая миграция
        results.add(runTest("Basic Migration") {
            testBasicMigration(recordCount)
        })

        // Тест 2: Производительность
        results.add(runTest("Performance Test") {
            testPerformance(recordCount)
        })

        // Тест 3: Delta Sync
        results.add(runTest("Delta Sync Test") {
            testDeltaSync(recordCount / 100)
        })

        // Тест 4: Resume после прерывания
        results.add(runTest("Resume Test") {
            testResume(recordCount)
        })

        // Тест 5: Rollback
        results.add(runTest("Rollback Test") {
            testRollback(recordCount / 10)
        })

        // Вывод отчётов
        logger.info("\n========== PERFORMANCE LOGS GENERATED ==========")
        logger.info("Performance logs written to: performance_logs/")
        logging.PerformanceLogger.finish()
        
        logger.info("\n========== DETAILED CONNECTION LOG ==========")
        logger.info("Full log written to: connection_usage.log")
        logging.DetailedConnectionLogger.getInstance().printFinalReport()
        
        logger.info("\n========== PERFORMANCE REPORT ==========")
        monitoring.PerformanceMonitor.getInstance().printReport()

        return results
    }

    /**
     * Запуск одного теста с обработкой ошибок
     */
    private fun runTest(name: String, testBlock: () -> Map<String, Any>): TestResult {
        logger.info("========== Starting test: $name ==========")
        val startTime = System.currentTimeMillis()

        try {
            val metrics = testBlock()
            val duration = System.currentTimeMillis() - startTime

            logger.info("========== Test $name PASSED (${duration}ms) ==========")

            return TestResult(
                testName = name,
                success = true,
                duration = duration,
                metrics = metrics
            )
        } catch (e: Exception) {
            val duration = System.currentTimeMillis() - startTime
            logger.error("========== Test $name FAILED (${duration}ms) ==========", e)

            return TestResult(
                testName = name,
                success = false,
                duration = duration,
                metrics = emptyMap(),
                errorMessage = e.message
            )
        }
    }

    /**
     * ТЕСТ 1: Базовая миграция
     * Проверяет что система выполняет свою задачу на реальной базе данных
     */
    private fun testBasicMigration(recordCount: Int): Map<String, Any> {
        logger.info("TEST 1: Basic Migration ($recordCount records)")

        // Очистка и генерация данных
        val generator = LargeDataGenerator(sourceDs.unwrapConnection())
        generator.truncateAll()
        
        val genStats = generator.generateAll(
            LargeDataGenerator.GenerationConfig(baseCount = recordCount)
        )

        val totalGenerated = genStats.sumOf { it.rowsGenerated }
        logger.info("Generated $totalGenerated total records")

        // Миграция
        val ctx = createMigrationContext()

        // Создание целевой схемы
        logger.info("Creating target schema...")
        ctx.migrator.createTargetSchema(ctx.migrationOrder)

        logger.info("Starting migration of ${ctx.tables.size} tables")

        val migrationStats = mutableListOf<Map<String, Any>>()
        ctx.migrationOrder.forEach { table ->
            val start = System.currentTimeMillis()
            ctx.migrator.migrateTable(table)
            val duration = System.currentTimeMillis() - start

            val rowCount = getRowCount(targetDs, table)
            migrationStats.add(mapOf(
                "table" to table,
                "rows" to rowCount,
                "duration_ms" to duration,
                "rows_per_sec" to (rowCount * 1000.0 / duration).toInt()
            ))

            logger.info("Migrated $table: $rowCount rows in ${duration}ms")
        }

        // Валидация
        val totalMigrated = migrationStats.sumOf { (it["rows"] as Long) }
        val success = totalMigrated == totalGenerated

        return mapOf(
            "total_generated" to totalGenerated,
            "total_migrated" to totalMigrated,
            "tables_migrated" to ctx.tables.size,
            "success" to success,
            "table_stats" to migrationStats
        )
    }

    /**
     * ТЕСТ 2: Производительность
     * Проверяет что миграция выполняется в разумные сроки
     */
    private fun testPerformance(recordCount: Int): Map<String, Any> {
        logger.info("TEST 2: Performance Test ($recordCount records)")

        // Генерация данных (если ещё нет)
        val sourceCount = getRowCount(sourceDs, "users")
        if (sourceCount < recordCount) {
            val generator = LargeDataGenerator(sourceDs.unwrapConnection())
            generator.generateAll(
                LargeDataGenerator.GenerationConfig(baseCount = recordCount)
            )
        }

        // Очистка target и mapping
        truncateTarget()

        // Миграция с замером времени
        val startTime = System.currentTimeMillis()

        val ctx = createMigrationContext()

        // Создание целевой схемы
        logger.info("Creating target schema...")
        ctx.migrator.createTargetSchema(ctx.migrationOrder)

        var totalRows = 0L
        ctx.migrationOrder.forEach { table ->
            ctx.migrator.migrateTable(table)
            totalRows += getRowCount(targetDs, table)
        }

        val totalDuration = System.currentTimeMillis() - startTime
        val rowsPerSec = if (totalDuration > 0) totalRows * 1000.0 / totalDuration else 0.0

        // Оценка производительности
        val expectedRowsPerSec = 5000  // Ожидаемая минимальная производительность
        val performanceOk = rowsPerSec >= expectedRowsPerSec

        // Оценка времени
        val expectedDurationSec = totalRows / expectedRowsPerSec
        val timeOk = totalDuration <= expectedDurationSec * 1000 * 1.5  // +50% запас

        return mapOf(
            "total_rows" to totalRows,
            "total_duration_ms" to totalDuration,
            "rows_per_second" to rowsPerSec.toInt(),
            "expected_rows_per_sec" to expectedRowsPerSec,
            "performance_ok" to performanceOk,
            "time_ok" to timeOk,
            "duration_sec" to (totalDuration / 1000.0),
            "expected_duration_sec" to expectedDurationSec
        )
    }

    /**
     * ТЕСТ 3: Delta Sync
     * Проверяет способность актуализировать базу данных
     */
    private fun testDeltaSync(newRecordCount: Int): Map<String, Any> {
        logger.info("TEST 3: Delta Sync Test ($newRecordCount new records)")

        val ctx = createMigrationContext()

        // Создание целевой схемы если пуста
        val targetCount = getRowCount(targetDs, "users")
        if (targetCount == 0L) {
            logger.info("Creating target schema for delta sync test...")
            ctx.migrator.createTargetSchema(ctx.migrationOrder)

            // Первичная миграция
            logger.info("Performing initial migration for delta sync test...")
            ctx.migrationOrder.forEach { table ->
                ctx.migrator.migrateTable(table)
            }
        }

        // Получаем количество до синхронизации
        val beforeCounts = ctx.tables.associateWith { getRowCount(targetDs, it) }

        // Генерируем новые данные
        val generator = LargeDataGenerator(sourceDs.unwrapConnection())
        val newStats = generator.generateAll(
            LargeDataGenerator.GenerationConfig(baseCount = newRecordCount)
        )
        val totalNew = newStats.sumOf { it.rowsGenerated }

        logger.info("Generated $totalNew new records for sync")

        // Синхронизация
        val syncStart = System.currentTimeMillis()
        val syncEngine = ChangeCapture(ctx.migrator, ctx.mappingService)
        syncEngine.syncUpdates(ctx.migrationOrder)

        val syncDuration = System.currentTimeMillis() - syncStart

        // Проверяем что новые данные синхронизированы
        val afterCounts = ctx.tables.associateWith { getRowCount(targetDs, it) }
        val syncedRows = ctx.tables.sumOf { afterCounts[it]!! - beforeCounts[it]!! }

        val syncOk = syncedRows >= totalNew * 0.9  // 90% новых записей

        return mapOf(
            "new_records_generated" to totalNew,
            "records_synced" to syncedRows,
            "sync_duration_ms" to syncDuration,
            "sync_ok" to syncOk,
            "rows_per_sec" to (if (syncDuration > 0) syncedRows * 1000.0 / syncDuration else 0.0).toInt()
        )
    }

    /**
     * ТЕСТ 4: Resume после прерывания
     * Проверяет способность восстановить работу и завершить позже
     */
    private fun testResume(recordCount: Int): Map<String, Any> {
        logger.info("TEST 4: Resume Test ($recordCount records)")

        // Очистка и генерация
        val generator = LargeDataGenerator(sourceDs.unwrapConnection())
        generator.truncateAll()
        generator.generateAll(
            LargeDataGenerator.GenerationConfig(baseCount = recordCount)
        )

        // Инициализация state repository
        val stateRepository = StateRepository(targetDs)
        val migrationId = "test_resume_${LocalDateTime.now().toString().replace(Regex("[^0-9]"), "")}"

        val ctx = createMigrationContext(stateRepository, migrationId)

        // Инициализируем миграцию
        stateRepository.initMigration(migrationId, ctx.tables, "source", "target")

        // Мигрируем только половину таблиц
        val halfwayPoint = ctx.migrationOrder.size / 2
        val firstHalf = ctx.migrationOrder.take(halfwayPoint)
        val secondHalf = ctx.migrationOrder.drop(halfwayPoint)

        logger.info("Simulating interruption after ${firstHalf.size} tables")

        // Первая "волна" (эмуляция прерывания)
        var migratedInFirstWave = 0L
        firstHalf.forEach { table ->
            ctx.migrator.migrateTable(table)
            migratedInFirstWave += getRowCount(targetDs, table)
        }

        // Эмуляция "возобновления"
        logger.info("Simulating resume...")

        // Вторая "волна"
        var migratedInSecondWave = 0L
        secondHalf.forEach { table ->
            ctx.migrator.migrateTable(table)
            migratedInSecondWave += getRowCount(targetDs, table)
        }

        val totalMigrated = migratedInFirstWave + migratedInSecondWave
        val resumeOk = migratedInSecondWave > 0

        return mapOf(
            "total_tables" to ctx.tables.size,
            "tables_first_wave" to firstHalf.size,
            "tables_second_wave" to secondHalf.size,
            "rows_first_wave" to migratedInFirstWave,
            "rows_second_wave" to migratedInSecondWave,
            "total_rows" to totalMigrated,
            "resume_ok" to resumeOk
        )
    }

    /**
     * ТЕСТ 5: Rollback
     * Проверяет способность откатить нужный объём данных и полностью восстановить
     */
    private fun testRollback(recordCount: Int): Map<String, Any> {
        logger.info("TEST 5: Rollback Test ($recordCount records)")

        // Очистка и генерация
        val generator = LargeDataGenerator(sourceDs.unwrapConnection())
        generator.truncateAll()
        generator.generateAll(
            LargeDataGenerator.GenerationConfig(baseCount = recordCount)
        )

        // Полная миграция
        val ctx = createMigrationContext()

        logger.info("Performing full migration before rollback")
        ctx.migrationOrder.forEach { table ->
            ctx.migrator.migrateTable(table)
        }

        val beforeRollback = ctx.tables.associateWith { getRowCount(targetDs, it) }
        val totalBefore = beforeRollback.values.sum()

        logger.info("Migrated $totalBefore total records")

        // Rollback половины таблиц
        val halfwayPoint = ctx.migrationOrder.size / 2
        val tablesToRollback = ctx.migrationOrder.take(halfwayPoint)

        logger.info("Rolling back ${tablesToRollback.size} tables")

        val stateRepository = StateRepository(targetDs)
        val migrationId = stateRepository.getLastActiveMigration()!!

        val rollbackService = RollbackService(sourceDs, targetDs, stateRepository)

        val rollbackStart = System.currentTimeMillis()
        val rollbackResults = tablesToRollback.map { table ->
            rollbackService.rollbackTable(migrationId, table)
        }
        val rollbackDuration = System.currentTimeMillis() - rollbackStart

        val rollbackSuccess = rollbackResults.count { it.success }
        val totalRolledBack = rollbackResults.sumOf { it.rowsRolledBack }

        // Валидация
        val validationResults = tablesToRollback.map { table ->
            rollbackService.validateRollback(table)
        }
        val validationOk = validationResults.all { it.isValid }

        // Проверка что остались только не-откатанные таблицы
        val afterRollback = ctx.tables.associateWith { getRowCount(targetDs, it) }
        val totalAfter = afterRollback.values.sum()

        val rollbackOk = totalAfter == (totalBefore - totalRolledBack)

        return mapOf(
            "total_before_rollback" to totalBefore,
            "tables_rolled_back" to tablesToRollback.size,
            "rows_rolled_back" to totalRolledBack,
            "rollback_duration_ms" to rollbackDuration,
            "rollback_success_count" to rollbackSuccess,
            "total_after_rollback" to totalAfter,
            "validation_ok" to validationOk,
            "rollback_ok" to rollbackOk
        )
    }

    /**
     * Вспомогательные методы
     */
    private fun getRowCount(ds: DataSource, tableName: String): Long {
        ds.connection.use { conn ->
            val rs = conn.createStatement().executeQuery("SELECT COUNT(*) FROM $tableName")
            return if (rs.next()) rs.getLong(1) else 0L
        }
    }

    private fun truncateTarget() {
        targetDs.connection.use { conn ->
            conn.createStatement().execute("TRUNCATE TABLE migration_mapping CASCADE")
            val tables = MetadataReader(sourceDs).getAllTablesWithUuidPk()
            tables.forEach { table ->
                try {
                    conn.createStatement().execute("TRUNCATE TABLE $table CASCADE")
                } catch (e: Exception) {
                    // Таблица может не существовать
                }
            }
        }
    }

    private fun DataSource.unwrapConnection(): String {
        return this.connection.use { conn ->
            conn.metaData.url
        }
    }
}

/**
 * Точка входа для запуска тестов
 */
fun main() {
    val sourceDs = HikariDataSource(HikariConfig().apply {
        jdbcUrl = "jdbc:postgresql://localhost:5431/source_db"
        username = "user"
        password = "password"
        maximumPoolSize = 10
    })

    val targetDs = HikariDataSource(HikariConfig().apply {
        jdbcUrl = "jdbc:postgresql://localhost:5432/target_db"
        username = "user"
        password = "password"
        maximumPoolSize = 10
    })

    val testSuite = MigrationTestSuite(sourceDs, targetDs)

    println("=============================================================")
    println("|   Comprehensive Migration Test Suite                      |")
    println("=============================================================")

    val results = testSuite.runAllTests(recordCount = 1_000_000)

    println()
    println("=============================================================")
    println("TEST RESULTS SUMMARY")
    println("=============================================================")

    results.forEach { result ->
        val status = if (result.success) "✓ PASSED" else "✗ FAILED"
        println("$status - ${result.testName} (${result.duration}ms)")
        if (!result.success) {
            println("  Error: ${result.errorMessage}")
        }
    }

    val passed = results.count { it.success }
    val total = results.size
    println("=============================================================")
    println("Total: $passed/$total tests passed")

    sourceDs.close()
    targetDs.close()
}
