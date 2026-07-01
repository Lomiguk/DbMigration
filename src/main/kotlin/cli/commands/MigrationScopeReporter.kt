package cli.commands

import com.github.ajalt.mordant.terminal.Terminal
import core.MigrationScope
import logging.PerformanceLogger
import ui.MigrationUi

internal object MigrationScopeReporter {
    fun report(scope: MigrationScope, ui: MigrationUi, terminal: Terminal) {
        PerformanceLogger.logSchemaInventory(scope.tableIdentityInfo)
        PerformanceLogger.logDependencyAnalysis(scope.dependencyAnalysis)

        ui.printSuccess("Всего public-таблиц: ${scope.tableIdentityInfo.size}")
        ui.printSuccess("Таблиц с UUID PK id: ${scope.eligibleTables.size}")
        ui.printSuccess("Таблиц в DAG-порядке миграции: ${scope.migrationOrder.size}")

        if (scope.skippedTables.isNotEmpty()) {
            val grouped = scope.skippedTables.groupingBy { it.skipReason ?: "UNKNOWN" }.eachCount()
            ui.printWarning("Пропущены таблицы вне UUID→BIGINT миграции: ${scope.skippedTables.size}")
            grouped.toSortedMap().forEach { (reason, count) ->
                terminal.println("  - $reason: $count")
            }
        }

        if (scope.dependencyAnalysis.hasCycles) {
            ui.printWarning("Найдены циклы зависимостей: ${scope.dependencyAnalysis.cyclicComponents.size}")
            scope.dependencyAnalysis.cyclicComponents.forEachIndexed { index, component ->
                terminal.println("  ${index + 1}. ${component.joinToString(" <-> ")}")
            }
            ui.printWarning(
                "Таблицы в циклах и зависящие от них исключены из обычного DAG-прохода: " +
                    scope.dependencyAnalysis.blockedTables.joinToString(", ")
            )
        }
    }
}
