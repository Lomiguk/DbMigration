package config

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.parameters.options.*
import com.github.ajalt.clikt.parameters.types.int
import com.github.ajalt.clikt.parameters.types.long
import com.zaxxer.hikari.HikariDataSource
import engine.MappingStrategy
import logging.DetailedConnectionLogger
import logging.MetricsService
import logging.PerformanceLogger
import ui.MigrationUi
import utils.HikariFactory
import java.io.File
import java.nio.file.Paths

/**
 * Конфигурация приложения для миграции БД
 * Поддерживает CLI аргументы и конфигурационные файлы
 */
data class MigrationConfig(
    // Source database
    val sourceHost: String = "localhost",
    val sourcePort: Int = 5431,
    val sourceDatabase: String = "source_db",
    val sourceUser: String = "user",
    val sourcePassword: String = "password",

    // Target database
    val targetHost: String = "localhost",
    val targetPort: Int = 5432,
    val targetDatabase: String = "target_db",
    val targetUser: String = "user",
    val targetPassword: String = "password",

    // Migration settings
    val batchSize: Int = 1000,
    val cacheLimit: Int = 10_000_000,  // Для совместимости
    val mappingStrategy: MappingStrategy = MappingStrategy.EAGER,
    val maxPoolSize: Int = 10,
    val connectionTimeout: Long = 30000,

    // Sync settings
    val syncStrategy: String = "MEMORY_FILTERED",

    // Advanced
    val dryRun: Boolean = false,
    val verbose: Boolean = false,
    val detailedConnectionLogging: Boolean = true,
    val configFile: String? = null
) {
    val sourceJdbcUrl: String
        get() = "jdbc:postgresql://$sourceHost:$sourcePort/$sourceDatabase"

    val targetJdbcUrl: String
        get() = "jdbc:postgresql://$targetHost:$targetPort/$targetDatabase"

    companion object {
        const val DEFAULT_CONFIG_FILE = "migration-config.yaml"
    }
}

/**
 * Базовый класс для CLI команд с общей конфигурацией
 */
abstract class MigrateCommand(private val migrationCommandName: String, help: String) : CliktCommand(name = migrationCommandName, help = help) {

    init {
        // Инициализация Observability stack при запуске любой команды
        MetricsService.init()
        // Register shutdown hook для освобождения порта 8080
        Runtime.getRuntime().addShutdownHook(Thread { MetricsService.shutdown() })
    }

    // Source database options
    protected val sourceHost by option("--source-host", "-sh", help = "Source database host")
        .default("localhost")

    protected val sourcePort by option("--source-port", "-sp", help = "Source database port")
        .int()
        .default(5431)

    protected val sourceDatabase by option("--source-db", "-sd", help = "Source database name")
        .default("source_db")

    protected val sourceUser by option("--source-user", "-su", help = "Source database user")
        .default("user")

    protected val sourcePassword by option("--source-password", "-spw", help = "Source database password", envvar = "SOURCE_DB_PASSWORD")
        .default("password")

    // Target database options
    protected val targetHost by option("--target-host", "-th", help = "Target database host")
        .default("localhost")

    protected val targetPort by option("--target-port", "-tp", help = "Target port")
        .int()
        .default(5432)

    protected val targetDatabase by option("--target-db", "-td", help = "Target database name")
        .default("target_db")

    protected val targetUser by option("--target-user", "-tu", help = "Target database user")
        .default("user")

    protected val targetPassword by option("--target-password", "-tpw", help = "Target database password", envvar = "TARGET_DB_PASSWORD")
        .default("password")

    // Migration options
    protected val batchSize by option("--batch-size", "-b", help = "Batch size for migration")
        .int()
        .default(1000)

    protected val cacheLimit by option("--cache-limit", "-c", help = "In-memory cache limit")
        .int()
        .default(500_000)

    protected val maxPoolSize by option("--max-pool-size", "-m", help = "Maximum connection pool size")
        .int()
        .default(10)

    // Advanced options
    protected val dryRun by option("--dry-run", "-n", help = "Dry run (no actual changes)")
        .flag()

    protected val verbose by option("--verbose", "-v", help = "Verbose output")
        .flag()

    protected val detailedConnectionLogging by option(
        "--detailed-connection-logging",
        help = "Enable detailed connection usage logging: true or false"
    ).convert { it.toBooleanStrict() }
        .default(true)

    protected val mappingStrategy by option("--mapping-strategy", "-ms", help = "Mapping strategy: EAGER, LAZY, HYBRID")
        .default("EAGER")

    protected val configFile by option("--config", "-cfg", help = "Path to configuration file")

    /**
     * Создание пары DataSource (source + target) из конфигурации.
     */
    protected fun createDataSources(config: MigrationConfig): Pair<HikariDataSource, HikariDataSource> {
        return Pair(
            HikariFactory.createDataSource(config.sourceJdbcUrl, config.sourceUser, config.sourcePassword, config.maxPoolSize),
            HikariFactory.createDataSource(config.targetJdbcUrl, config.targetUser, config.targetPassword, config.maxPoolSize)
        )
    }

    /**
     * Вывод информации о подключении и создание DataSource.
     */
    protected fun createDataSourcesWithLog(config: MigrationConfig, ui: MigrationUi): Pair<HikariDataSource, HikariDataSource> {
        ui.printInfo("Source: ${config.sourceJdbcUrl}")
        ui.printInfo("Target: ${config.targetJdbcUrl}")
        return createDataSources(config)
    }

    /**
     * Построение конфигурации из CLI аргументов
     */
    protected fun buildConfig(): MigrationConfig {
        // Если указан config файл, загружаем из него
        val configPath = configFile
        if (configPath != null) {
            return loadConfigFromFile(configPath)
        }

        val strategy = when (mappingStrategy.uppercase()) {
            "LAZY" -> MappingStrategy.LAZY
            "HYBRID" -> MappingStrategy.HYBRID
            else -> MappingStrategy.EAGER
        }

        return MigrationConfig(
            sourceHost = sourceHost,
            sourcePort = sourcePort,
            sourceDatabase = sourceDatabase,
            sourceUser = sourceUser,
            sourcePassword = sourcePassword,

            targetHost = targetHost,
            targetPort = targetPort,
            targetDatabase = targetDatabase,
            targetUser = targetUser,
            targetPassword = targetPassword,

            batchSize = batchSize,
            cacheLimit = cacheLimit,
            mappingStrategy = strategy,
            maxPoolSize = maxPoolSize,

            dryRun = dryRun,
            verbose = verbose,
            detailedConnectionLogging = detailedConnectionLogging
        ).also {
            DetailedConnectionLogger.configure(it.detailedConnectionLogging)
            PerformanceLogger.startRun(migrationCommandName, it.asLogProperties())
        }
    }

    /**
     * Загрузка конфигурации из YAML файла
     */
    private fun loadConfigFromFile(path: String): MigrationConfig {
        val file = File(path)
        if (!file.exists()) {
            throw IllegalArgumentException("Configuration file not found: $path")
        }

        // Простой парсинг YAML (без внешней зависимости)
        val config = mutableMapOf<String, String>()
        file.readLines().forEach { line ->
            val trimmed = line.trim()
            if (trimmed.isNotEmpty() && !trimmed.startsWith("#") && trimmed.contains(":")) {
                val parts = trimmed.split(":", limit = 2)
                if (parts.size == 2) {
                    config[parts[0].trim()] = parts[1].trim().replace("\"", "")
                }
            }
        }

        val strategy = when ((config["mappingStrategy"] ?: mappingStrategy).uppercase()) {
            "LAZY" -> MappingStrategy.LAZY
            "HYBRID" -> MappingStrategy.HYBRID
            else -> MappingStrategy.EAGER
        }

        return MigrationConfig(
            sourceHost = config["sourceHost"] ?: sourceHost,
            sourcePort = config["sourcePort"]?.toInt() ?: sourcePort,
            sourceDatabase = config["sourceDatabase"] ?: sourceDatabase,
            sourceUser = config["sourceUser"] ?: sourceUser,
            sourcePassword = config["sourcePassword"] ?: sourcePassword,

            targetHost = config["targetHost"] ?: targetHost,
            targetPort = config["targetPort"]?.toInt() ?: targetPort,
            targetDatabase = config["targetDatabase"] ?: targetDatabase,
            targetUser = config["targetUser"] ?: targetUser,
            targetPassword = config["targetPassword"] ?: targetPassword,

            batchSize = config["batchSize"]?.toInt() ?: batchSize,
            cacheLimit = config["cacheLimit"]?.toInt() ?: cacheLimit,
            mappingStrategy = strategy,
            maxPoolSize = config["maxPoolSize"]?.toInt() ?: maxPoolSize,

            dryRun = config["dryRun"]?.toBoolean() ?: dryRun,
            verbose = config["verbose"]?.toBoolean() ?: verbose,
            detailedConnectionLogging = config["detailedConnectionLogging"]?.toBoolean() ?: detailedConnectionLogging
        ).also {
            DetailedConnectionLogger.configure(it.detailedConnectionLogging)
            PerformanceLogger.startRun(migrationCommandName, it.asLogProperties() + ("configFile" to path))
        }
    }

    private fun MigrationConfig.asLogProperties(): Map<String, String> =
        mapOf(
            "sourceHost" to sourceHost,
            "sourcePort" to sourcePort.toString(),
            "sourceDatabase" to sourceDatabase,
            "targetHost" to targetHost,
            "targetPort" to targetPort.toString(),
            "targetDatabase" to targetDatabase,
            "batchSize" to batchSize.toString(),
            "cacheLimit" to cacheLimit.toString(),
            "mappingStrategy" to mappingStrategy.name,
            "maxPoolSize" to maxPoolSize.toString(),
            "syncStrategy" to syncStrategy,
            "dryRun" to dryRun.toString(),
            "verbose" to verbose.toString(),
            "detailedConnectionLogging" to detailedConnectionLogging.toString()
        )
}

/**
 * Создание примера конфигурационного файла
 */
fun createSampleConfigFile(path: String = MigrationConfig.DEFAULT_CONFIG_FILE) {
    val configContent = """
# PostgreSQL UUID to BIGINT Migration Configuration
# Generated by DbMigration Tool

# Source Database (UUID schema)
sourceHost: localhost
sourcePort: 5431
sourceDatabase: source_db
sourceUser: user
sourcePassword: password

# Target Database (BIGINT schema)
targetHost: localhost
targetPort: 5432
targetDatabase: target_db
targetUser: user
targetPassword: password

# Migration Settings
batchSize: 1000
cacheLimit: 500000
mappingStrategy: LAZY
maxPoolSize: 10
connectionTimeout: 30000

# Sync Strategy (MEMORY_FILTERED, DB_FILTERED)
syncStrategy: MEMORY_FILTERED

# Advanced
dryRun: false
verbose: true
detailedConnectionLogging: true
""".trimIndent()

    val file = Paths.get(path).toFile()
    file.writeText(configContent)
    println("✓ Configuration file created: ${file.absolutePath}")
}
