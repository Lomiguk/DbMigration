package sync

import engine.DataMigrator
import engine.MappingService

class ChangeCapture(
    private val migrator: DataMigrator,
    private val mappingService: MappingService
) {
    fun syncUpdates(tables: List<String>) {
        println("\n>>> Шаг 6: Оптимизированная синхронизация (Memory-Filtered Delta Sync)...")
        tables.forEach { tableName ->
            val start = System.currentTimeMillis()

            // Быстро получаем список уже существующих UUID из таблицы маппинга
            val existingIds = mappingService.getAllMappedUuids(tableName)

            // Запускаем миграцию, передавая этот набор для фильтрации в памяти
            migrator.migrateTable(tableName, existingIds)

            val duration = System.currentTimeMillis() - start

            println("Синхронизация $tableName завершена за $duration мс (пропущено ${existingIds.size} строк).")
        }
    }
}