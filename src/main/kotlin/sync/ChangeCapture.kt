package sync

import engine.DataMigrator
import engine.MappingServiceBase
import logging.MetricsService

class ChangeCapture(
    private val migrator: DataMigrator,
    private val mappingService: MappingServiceBase
) {
    fun syncUpdates(tables: List<String>) {
        println("\n>>> Шаг 6: Оптимизированная синхронизация (Memory-Filtered Delta Sync)...")
        tables.forEach { tableName ->
            val start = System.currentTimeMillis()

            // Замер длительности фильтрации через Micrometer Timer
            val filterTimer = MetricsService.getMigrationBatchTimer(tableName, "delta_filter")
            val existingIds = filterTimer.recordCallable {
                mappingService.getAllMappedUuids(tableName)
            }!!

            // Запускаем миграцию, передавая этот набор для фильтрации в памяти
            migrator.migrateTable(tableName, existingIds)

            val duration = System.currentTimeMillis() - start

            println("Синхронизация $tableName завершена за $duration мс (пропущено ${existingIds.size} строк).")
        }
    }
}