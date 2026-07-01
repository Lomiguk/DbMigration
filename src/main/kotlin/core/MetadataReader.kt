package core

import javax.sql.DataSource

data class TableRelation(
    val parentTable: String,
    val childTable: String
)
data class ForeignKeyColumn(
    val columnName: String,
    val refTable: String,
    val refColumn: String
)
data class TableIdentityInfo(
    val tableName: String,
    val primaryKeyColumns: List<String>,
    val primaryKeyTypes: List<String>,
    val eligibleForUuidMigration: Boolean,
    val skipReason: String?
)

class MetadataReader(private val dataSource: DataSource) {

    /**
     * Получаем список всех колонок таблицы для создания полной схемы
     */
    fun getTableColumns(tableName: String): Map<String, String> {
        val columns = mutableMapOf<String, String>()
        dataSource.connection.use { conn ->
            val rs = conn.metaData.getColumns(null, "public", tableName, null)
            while (rs.next()) {
                columns[rs.getString("COLUMN_NAME")] = rs.getString("TYPE_NAME")
            }
        }
        return columns
    }

    /**
     * Получаем детальную информацию о FK для конкретной таблицы
     */
    fun getForeignKeysForTable(tableName: String): List<ForeignKeyColumn> {
        val fks = mutableListOf<ForeignKeyColumn>()
        dataSource.connection.use { conn ->
            val sql = """
                SELECT 
                    kcu.column_name, 
                    ccu.table_name AS ref_table, 
                    ccu.column_name AS ref_column
                FROM information_schema.table_constraints AS tc 
                JOIN information_schema.key_column_usage AS kcu
                    ON tc.constraint_name = kcu.constraint_name
                    AND tc.constraint_schema = kcu.constraint_schema
                JOIN information_schema.constraint_column_usage AS ccu
                    ON ccu.constraint_name = tc.constraint_name
                    AND ccu.constraint_schema = tc.constraint_schema
                WHERE tc.constraint_type = 'FOREIGN KEY'
                  AND tc.table_schema = 'public'
                  AND tc.table_name = ?
            """.trimIndent()
            val pstmt = conn.prepareStatement(sql)
            pstmt.setString(1, tableName)
            val rs = pstmt.executeQuery()
            while (rs.next()) {
                fks.add(ForeignKeyColumn(
                    rs.getString("column_name"),
                    rs.getString("ref_table"),
                    rs.getString("ref_column")
                ))
            }
        }
        return fks
    }

    fun getForeignKeys(): List<TableRelation> {
        val relations = mutableListOf<TableRelation>()
        dataSource.connection.use { conn ->
            val sql = """
                SELECT ccu.table_name AS parent_table, tc.table_name AS child_table
                FROM information_schema.table_constraints AS tc
                JOIN information_schema.key_column_usage AS kcu
                    ON tc.constraint_name = kcu.constraint_name
                    AND tc.constraint_schema = kcu.constraint_schema
                JOIN information_schema.constraint_column_usage AS ccu
                    ON ccu.constraint_name = tc.constraint_name
                    AND ccu.constraint_schema = tc.constraint_schema
                WHERE tc.constraint_type = 'FOREIGN KEY'
                  AND tc.table_schema = 'public'
                ORDER BY parent_table, child_table;
            """.trimIndent()
            val rs = conn.createStatement().executeQuery(sql)
            while (rs.next()) {
                relations.add(TableRelation(rs.getString("parent_table"), rs.getString("child_table")))
            }
        }
        return relations
    }

    fun getAllTablesWithUuidPk(): List<String> {
        return getTableIdentityInfo()
            .filter { it.eligibleForUuidMigration }
            .map { it.tableName }
    }

    fun getTableIdentityInfo(): List<TableIdentityInfo> {
        val rows = linkedMapOf<String, MutableList<Pair<String, String>>>()
        dataSource.connection.use { conn ->
            val sql = """
                SELECT
                    t.table_name,
                    kcu.column_name,
                    c.data_type
                FROM information_schema.tables t
                LEFT JOIN information_schema.table_constraints tc
                    ON tc.table_schema = t.table_schema
                    AND tc.table_name = t.table_name
                    AND tc.constraint_type = 'PRIMARY KEY'
                LEFT JOIN information_schema.key_column_usage kcu
                    ON kcu.constraint_schema = tc.constraint_schema
                    AND kcu.constraint_name = tc.constraint_name
                    AND kcu.table_schema = tc.table_schema
                    AND kcu.table_name = tc.table_name
                LEFT JOIN information_schema.columns c
                    ON c.table_schema = t.table_schema
                    AND c.table_name = t.table_name
                    AND c.column_name = kcu.column_name
                WHERE t.table_schema = 'public'
                  AND t.table_type = 'BASE TABLE'
                ORDER BY t.table_name, kcu.ordinal_position;
            """.trimIndent()
            val rs = conn.createStatement().executeQuery(sql)
            while (rs.next()) {
                val tableName = rs.getString("table_name")
                val columnName = rs.getString("column_name")
                val dataType = rs.getString("data_type")
                val keys = rows.getOrPut(tableName) { mutableListOf() }
                if (columnName != null && dataType != null) {
                    keys.add(columnName to dataType)
                }
            }
        }

        return rows.map { (tableName, keyColumns) ->
            val columns = keyColumns.map { it.first }
            val types = keyColumns.map { it.second }
            val skipReason = when {
                columns.isEmpty() -> "NO_PRIMARY_KEY"
                columns.size > 1 -> "COMPOSITE_PRIMARY_KEY"
                columns.first() != "id" -> "PRIMARY_KEY_NOT_ID"
                !types.first().equals("uuid", ignoreCase = true) -> "NON_UUID_PRIMARY_KEY"
                else -> null
            }

            TableIdentityInfo(
                tableName = tableName,
                primaryKeyColumns = columns,
                primaryKeyTypes = types,
                eligibleForUuidMigration = skipReason == null,
                skipReason = skipReason
            )
        }
    }
}
