package backup

import org.slf4j.LoggerFactory
import java.io.File
import java.nio.file.Paths
import javax.sql.DataSource

/**
 * Сервис для создания и восстановления бекапов PostgreSQL
 */
class DatabaseBackupService(
    private val sourceDataSource: DataSource,
    private val backupDir: String = "backups"
) {
    private val logger = LoggerFactory.getLogger(DatabaseBackupService::class.java)

    /**
     * Создание бекапа базы данных
     */
    fun createBackup(backupName: String): BackupInfo {
        logger.info("Creating backup: $backupName")
        val startTime = System.currentTimeMillis()

        val backupDir = File(backupDir).apply { mkdirs() }
        val backupFile = File(backupDir, "$backupName.sql")

        // Получаем параметры подключения из DataSource
        val conn = sourceDataSource.connection
        val url = conn.metaData.url
        val user = System.getenv("PGUSER") ?: "user"
        conn.close()

        // Используем pg_dump для создания бекапа
        val pgDumpCmd = listOf(
            "pg_dump",
            "-h", extractHost(url),
            "-p", extractPort(url),
            "-U", user,
            "-d", extractDatabase(url),
            "-F", "c",  // Custom format (сжатый)
            "-f", backupFile.absolutePath
        )

        logger.info("Executing: ${pgDumpCmd.joinToString(" ")}")

        val process = ProcessBuilder(pgDumpCmd)
            .redirectErrorStream(true)
            .start()

        // Читаем вывод
        process.inputStream.bufferedReader().useLines { lines ->
            lines.forEach { line ->
                if (line.contains("error", ignoreCase = true)) {
                    logger.error("pg_dump error: $line")
                } else {
                    logger.debug("pg_dump: $line")
                }
            }
        }

        val exitCode = process.waitFor()
        val duration = System.currentTimeMillis() - startTime

        if (exitCode != 0) {
            throw RuntimeException("pg_dump failed with exit code $exitCode")
        }

        val backupSize = backupFile.length()
        logger.info("Backup created: ${backupFile.absolutePath} (${formatSize(backupSize)}) in ${duration / 1000.0}s")

        return BackupInfo(
            name = backupName,
            file = backupFile,
            sizeBytes = backupSize,
            createdAt = System.currentTimeMillis(),
            durationMs = duration
        )
    }

    /**
     * Восстановление из бекапа
     */
    fun restoreFromBackup(backupName: String) {
        logger.info("Restoring from backup: $backupName")
        val startTime = System.currentTimeMillis()

        val backupFile = File(backupDir, "$backupName.sql")
        if (!backupFile.exists()) {
            throw RuntimeException("Backup file not found: ${backupFile.absolutePath}")
        }

        // Получаем параметры подключения
        val conn = sourceDataSource.connection
        val url = conn.metaData.url
        val user = System.getenv("PGUSER") ?: "user"
        conn.close()

        // Сначала очистим базу
        logger.info("Dropping all tables...")
        dropAllTables()

        // Восстанавливаем из бекапа
        val pgRestoreCmd = listOf(
            "pg_restore",
            "-h", extractHost(url),
            "-p", extractPort(url),
            "-U", user,
            "-d", extractDatabase(url),
            "--no-owner",
            "--no-privileges",
            backupFile.absolutePath
        )

        logger.info("Executing: ${pgRestoreCmd.joinToString(" ")}")

        val process = ProcessBuilder(pgRestoreCmd)
            .redirectErrorStream(true)
            .start()

        // Читаем вывод
        process.inputStream.bufferedReader().useLines { lines ->
            lines.forEach { line ->
                if (line.contains("error", ignoreCase = true)) {
                    logger.error("pg_restore error: $line")
                } else {
                    logger.debug("pg_restore: $line")
                }
            }
        }

        val exitCode = process.waitFor()
        val duration = System.currentTimeMillis() - startTime

        if (exitCode != 0) {
            throw RuntimeException("pg_restore failed with exit code $exitCode")
        }

        logger.info("Restore completed in ${duration / 1000.0}s")
    }

    /**
     * Очистка всех таблиц в базе
     */
    private fun dropAllTables() {
        sourceDataSource.connection.use { conn ->
            // Получаем список всех таблиц
            val tables = mutableListOf<String>()
            val rs = conn.metaData.getTables(null, "public", null, arrayOf("TABLE"))
            while (rs.next()) {
                val tableName = rs.getString("TABLE_NAME")
                if (!tableName.startsWith("migration_")) {  // Не удаляем служебные таблицы
                    tables.add(tableName)
                }
            }

            // Отключаем FK проверки
            conn.createStatement().execute("SET CONSTRAINTS ALL DEFERRED")

            // Очищаем таблицы в обратном порядке (чтобы учесть FK)
            tables.asReversed().forEach { table ->
                try {
                    conn.createStatement().execute("TRUNCATE TABLE $table CASCADE")
                    logger.debug("Truncated table: $table")
                } catch (e: Exception) {
                    logger.warn("Failed to truncate $table: ${e.message}")
                }
            }
        }
    }

    /**
     * Список доступных бекапов
     */
    fun listBackups(): List<BackupInfo> {
        val backupDir = File(backupDir)
        if (!backupDir.exists()) return emptyList()

        return backupDir.listFiles { file ->
            file.extension == "sql"
        }?.map { file ->
            BackupInfo(
                name = file.nameWithoutExtension,
                file = file,
                sizeBytes = file.length(),
                createdAt = file.lastModified(),
                durationMs = 0
            )
        }?.sortedByDescending { it.createdAt } ?: emptyList()
    }

    /**
     * Удаление бекапа
     */
    fun deleteBackup(backupName: String) {
        val backupFile = File(backupDir, "$backupName.sql")
        if (backupFile.exists()) {
            backupFile.delete()
            logger.info("Deleted backup: $backupName")
        }
    }

    // Вспомогательные методы для парсинга JDBC URL
    private fun extractHost(jdbcUrl: String): String {
        val regex = Regex("jdbc:postgresql://([^:/]+)")
        return regex.find(jdbcUrl)?.groupValues?.get(1) ?: "localhost"
    }

    private fun extractPort(jdbcUrl: String): String {
        val regex = Regex("jdbc:postgresql://[^:]+:(\\d+)")
        return regex.find(jdbcUrl)?.groupValues?.get(1) ?: "5432"
    }

    private fun extractDatabase(jdbcUrl: String): String {
        val regex = Regex("jdbc:postgresql://[^/]+/([^?]+)")
        return regex.find(jdbcUrl)?.groupValues?.get(1) ?: "source_db"
    }

    private fun formatSize(bytes: Long): String {
        return when {
            bytes < 1024 -> "$bytes B"
            bytes < 1024 * 1024 -> "${bytes / 1024} KB"
            bytes < 1024 * 1024 * 1024 -> "${bytes / (1024 * 1024)} MB"
            else -> "${bytes / (1024 * 1024 * 1024)} GB"
        }
    }
}

/**
 * Информация о бекапе
 */
data class BackupInfo(
    val name: String,
    val file: File,
    val sizeBytes: Long,
    val createdAt: Long,
    val durationMs: Long
) {
    val createdAtFormatted: String
        get() = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(java.util.Date(createdAt))

    val sizeFormatted: String
        get() = when {
            sizeBytes < 1024 -> "$sizeBytes B"
            sizeBytes < 1024 * 1024 -> "${sizeBytes / 1024} KB"
            sizeBytes < 1024 * 1024 * 1024 -> "${sizeBytes / (1024 * 1024)} MB"
            else -> "${sizeBytes / (1024 * 1024 * 1024)} GB"
        }
}
