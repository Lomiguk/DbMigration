package integration

import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import java.sql.Connection
import javax.sql.DataSource
import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource

@Testcontainers
abstract class BaseIntegrationTest {

    companion object {
        @Container
        @JvmField
        val sourcePostgres: PostgreSQLContainer<*> = PostgreSQLContainer("postgres:15-alpine")
            .withDatabaseName("source_test_db")
            .withUsername("test")
            .withPassword("test")
            .withCommand("postgres", "-c", "wal_level=logical", "-c", "max_replication_slots=10", "-c", "max_wal_senders=10")

        @Container
        @JvmField
        val targetPostgres: PostgreSQLContainer<*> = PostgreSQLContainer("postgres:15-alpine")
            .withDatabaseName("target_test_db")
            .withUsername("test")
            .withPassword("test")

        private var _sourceDs: HikariDataSource? = null
        private var _targetDs: HikariDataSource? = null

        val globalSourceDataSource: DataSource get() = _sourceDs ?: throw IllegalStateException("Source DS null")
        val globalTargetDataSource: DataSource get() = _targetDs ?: throw IllegalStateException("Target DS null")

        @BeforeAll
        @JvmStatic
        fun setupGlobal() {
            _sourceDs = HikariDataSource(HikariConfig().apply {
                jdbcUrl = sourcePostgres.jdbcUrl
                username = sourcePostgres.username
                password = sourcePostgres.password
                maximumPoolSize = 10
            })
            _targetDs = HikariDataSource(HikariConfig().apply {
                jdbcUrl = targetPostgres.jdbcUrl
                username = targetPostgres.username
                password = targetPostgres.password
                maximumPoolSize = 10
            })
        }

        @AfterAll
        @JvmStatic
        fun teardownGlobal() {
            _sourceDs?.close()
            _targetDs?.close()
        }
    }

    val sourceDataSource: DataSource get() = globalSourceDataSource
    val targetDataSource: DataSource get() = globalTargetDataSource

    val dataSource: DataSource get() = sourceDataSource

    fun getConnection(): Connection = sourceDataSource.connection

    fun countRows(tableName: String): Long {
        sourceDataSource.connection.use { conn ->
            val rs = conn.createStatement().executeQuery("SELECT COUNT(*) FROM $tableName")
            return if (rs.next()) rs.getLong(1) else 0L
        }
    }

    fun countRowsTarget(tableName: String): Long {
        targetDataSource.connection.use { conn ->
            val rs = conn.createStatement().executeQuery("SELECT COUNT(*) FROM $tableName")
            return if (rs.next()) rs.getLong(1) else 0L
        }
    }

    fun tableExists(tableName: String): Boolean {
        sourceDataSource.connection.use { conn ->
            val rs = conn.metaData.getTables(null, "public", tableName, arrayOf("TABLE"))
            return rs.next()
        }
    }

    fun executeScript(script: String) {
        sourceDataSource.connection.use { conn ->
            script.split(";").filter { it.isNotBlank() }.forEach { sql ->
                conn.createStatement().execute(sql.trim())
            }
        }
    }

    fun executeTargetScript(script: String) {
        targetDataSource.connection.use { conn ->
            script.split(";").filter { it.isNotBlank() }.forEach { sql ->
                conn.createStatement().execute(sql.trim())
            }
        }
    }

    fun cleanupTables(vararg tableNames: String) {
        listOf(sourceDataSource, targetDataSource).forEach { ds ->
            ds.connection.use { conn ->
                tableNames.forEach { table ->
                    try {
                        conn.createStatement().execute("TRUNCATE TABLE $table CASCADE")
                    } catch (_: Exception) {
                        // Игнорируем ошибку, если таблица еще не создана
                    }
                }
            }
        }
    }
}
