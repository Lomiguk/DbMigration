import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import core.DependencyResolver
import core.MetadataReader
import tools.TestDataGenerator
import engine.MappingService
import engine.DataMigrator
import sync.ChangeCapture
import tools.ResultCollector
import tools.RunConfiguration

fun main() {
    // Конфигурация параметров прогона для истории тестов
    val testCount = 1_000_000
    val currentConfig = RunConfiguration(
        totalRecords = testCount,
        batchSize = 1000,
        cacheLimit = 500_000,
        syncStrategy = "MEMORY_FILTERED"
    )

    // Настройка пулов соединений (Source: 5431, Target: 5432)
    val sourceDS = HikariDataSource(HikariConfig().apply {
        jdbcUrl = "jdbc:postgresql://localhost:5431/source_db"
        username = "user"
        password = "password"
        maximumPoolSize = 10
    })

    val targetDS = HikariDataSource(HikariConfig().apply {
        jdbcUrl = "jdbc:postgresql://localhost:5432/target_db"
        username = "user"
        password = "password"
        maximumPoolSize = 10
    })

    try {
        val reader = MetadataReader(sourceDS)
        val generator = TestDataGenerator(sourceDS.jdbcUrl)
        val collector = ResultCollector(currentConfig, sourceDS, targetDS)

        println(">>> ШАГ 1: Генерация тестовых данных ($testCount записей)...")
        generator.generateAll(testCount)

        println("\n>>> ШАГ 2: Анализ структуры БД и построение графа...")
        val tables = reader.getAllTablesWithUuidPk()
        val relations = reader.getForeignKeys()
        val resolver = DependencyResolver().apply { buildGraph(tables, relations) }
        val migrationOrder = resolver.getMigrationOrder()

        val mappingService = MappingService(targetDS)
        val migrator = DataMigrator(sourceDS, targetDS, mappingService, reader)

        migrator.createTargetSchema(migrationOrder)
        println("\n>>> ШАГ 3: Запуск первичного переноса данных...")

        migrationOrder.forEach { table ->
            val startTime = System.currentTimeMillis()
            migrator.migrateTable(table)
            val duration = System.currentTimeMillis() - startTime

            // Сбор метрик по каждой таблице для отчета
            collector.collect(table, duration)
        }

        println("\n>>> ШАГ 4: Имитация новых данных в Source (100 записей)...")
        generator.generateAll(100)

        val syncEngine = ChangeCapture(migrator, mappingService)
        val syncStartTime = System.currentTimeMillis()
        syncEngine.syncUpdates(migrationOrder)
        val syncDuration = System.currentTimeMillis() - syncStartTime
        println("Синхронизация завершена за $syncDuration мс.")

        println("\n>>> ШАГ 6: Сохранение результатов и истории...")
        collector.saveToHistory()

        println("\nМИГРАЦИЯ И СИНХРОНИЗАЦИЯ ЗАВЕРШЕНЫ УСПЕШНО")

    } catch (e: Exception) {
        println("\nКРИТИЧЕСКАЯ ОШИБКА: ${e.message}")
        e.printStackTrace()
    } finally {
        sourceDS.close()
        targetDS.close()
    }
}