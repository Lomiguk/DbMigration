package integration

import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import java.sql.Connection
import java.sql.DriverManager
import javax.sql.DataSource
import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource

/**
 * Базовый класс для интеграционных тестов с PostgreSQL через Testcontainers.
 * Поднимает реальный контейнер с БД для каждого тестового класса.
 */
@Testcontainers
abstract class BaseIntegrationTest {

    companion object {
        @Container
        val postgresContainer: PostgreSQLContainer<*> = PostgreSQLContainer("postgres:15-alpine")
            .withDatabaseName("test_db")
            .withUsername("test")
            .withPassword("test")
            .withReuse(true)

        private var _dataSource: HikariDataSource? = null

        val dataSource: DataSource
            get() = _dataSource ?: throw IllegalStateException("DataSource не инициализирован. Вызовите setup() перед тестом.")

        @BeforeAll
        @JvmStatic
        fun setupGlobal() {
            _dataSource = HikariDataSource(HikariConfig().apply {
                jdbcUrl = postgresContainer.jdbcUrl
                username = postgresContainer.username
                password = postgresContainer.password
                maximumPoolSize = 5
                minimumIdle = 1
                connectionTimeout = 10000
            })
        }

        @AfterAll
        @JvmStatic
        fun teardownGlobal() {
            _dataSource?.close()
        }
    }

    /**
     * Получение JDBC соединения для тестов
     */
    fun getConnection(): Connection {
        return dataSource.connection
    }

    /**
     * Выполнение SQL скрипта в БД
     */
    fun executeScript(script: String) {
        getConnection().use { conn ->
            val statements = script.split(";").filter { it.isNotBlank() }
            statements.forEach { sql ->
                conn.createStatement().execute(sql.trim())
            }
        }
    }

    /**
     * Очистка всех таблиц после теста
     */
    fun cleanupTables(vararg tableNames: String) {
        getConnection().use { conn ->
            tableNames.forEach { table ->
                try {
                    conn.createStatement().execute("TRUNCATE TABLE $table CASCADE")
                } catch (e: Exception) {
                    // Таблица может не существовать
                }
            }
        }
    }

    /**
     * Подсчет строк в таблице
     */
    fun countRows(tableName: String): Long {
        getConnection().use { conn ->
            val rs = conn.createStatement().executeQuery("SELECT COUNT(*) FROM $tableName")
            return if (rs.next()) rs.getLong(1) else 0L
        }
    }

    /**
     * Проверка существования таблицы
     */
    fun tableExists(tableName: String): Boolean {
        getConnection().use { conn ->
            val rs = conn.metaData.getTables(null, "public", tableName, arrayOf("TABLE"))
            return rs.next()
        }
    }
}
