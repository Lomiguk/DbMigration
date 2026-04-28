package integration

import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import java.sql.Connection
import javax.sql.DataSource
import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource

abstract class BaseIntegrationTest {

    companion object {
        private var _sourceDs: HikariDataSource? = null
        private var _targetDs: HikariDataSource? = null

        val globalSourceDataSource: DataSource get() = _sourceDs ?: throw IllegalStateException("Source DS null")
        val globalTargetDataSource: DataSource get() = _targetDs ?: throw IllegalStateException("Target DS null")

        @BeforeAll
        @JvmStatic
        fun setupGlobal() {
            _sourceDs = HikariDataSource(HikariConfig().apply {
                jdbcUrl = "jdbc:postgresql://localhost:5431/source_db"
                username = "user"
                password = "password"
                maximumPoolSize = 10
            })
            _targetDs = HikariDataSource(HikariConfig().apply {
                jdbcUrl = "jdbc:postgresql://localhost:5432/target_db"
                username = "user"
                password = "password"
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