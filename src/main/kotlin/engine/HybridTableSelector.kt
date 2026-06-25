package engine

import javax.sql.DataSource

object HybridTableSelector {
    fun selectPinnedTables(ds: DataSource, tables: List<String>, cacheLimit: Int): Set<String> {
        val budget = (cacheLimit / 2).coerceAtLeast(1)
        var used = 0L

        return tables
            .map { table -> table to getRowCount(ds, table) }
            .sortedBy { (_, rows) -> rows }
            .filter { (_, rows) ->
                val fits = rows > 0 && used + rows <= budget
                if (fits) used += rows
                fits
            }
            .map { (table, _) -> table }
            .toSet()
    }

    private fun getRowCount(ds: DataSource, tableName: String): Long {
        ds.connection.use { conn ->
            val rs = conn.createStatement().executeQuery("SELECT COUNT(*) FROM $tableName")
            return if (rs.next()) rs.getLong(1) else 0L
        }
    }
}
