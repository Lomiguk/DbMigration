package sync

import engine.DataMigrator
import java.util.*
import javax.sql.DataSource

class ChangeCapture(
    private val sourceDataSource: DataSource,
    private val migrator: DataMigrator
) {
    /**
     * Поиск записей в исходной БД, которых еще нет в целевой.
     * Основано на сравнении оригинальных UUID.
     */
    fun syncUpdates(tables: List<String>) {
        println("\n>>> Шаг 6: Синхронизация изменений (Delta Sync)...")
        tables.forEach { tableName ->
            // В реальной системе здесь лучше использовать триггеры или WAL,
            // но для магистерской работы достаточно логики "найди новые UUID"
            migrator.migrateTable(tableName) // Переиспользуем логику миграции
        }
        println("Синхронизация завершена.")
    }
}