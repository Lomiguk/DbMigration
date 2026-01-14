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
                JOIN information_schema.key_column_usage AS kcu ON tc.constraint_name = kcu.constraint_name 
                JOIN information_schema.constraint_column_usage AS ccu ON ccu.constraint_name = tc.constraint_name 
                WHERE tc.constraint_type = 'FOREIGN KEY' AND tc.table_name = ?
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
                JOIN information_schema.key_column_usage AS kcu ON tc.constraint_name = kcu.constraint_name
                JOIN information_schema.constraint_column_usage AS ccu ON ccu.constraint_name = tc.constraint_name
                WHERE tc.constraint_type = 'FOREIGN KEY';
            """.trimIndent()
            val rs = conn.createStatement().executeQuery(sql)
            while (rs.next()) {
                relations.add(TableRelation(rs.getString("parent_table"), rs.getString("child_table")))
            }
        }
        return relations
    }

    fun getAllTablesWithUuidPk(): List<String> {
        val tables = mutableListOf<String>()
        dataSource.connection.use { conn ->
            val sql = """
                SELECT t.table_name FROM information_schema.tables t
                JOIN information_schema.columns c ON t.table_name = c.table_name
                JOIN information_schema.table_constraints tc ON t.table_name = tc.table_name
                JOIN information_schema.key_column_usage kcu ON tc.constraint_name = kcu.constraint_name
                WHERE tc.constraint_type = 'PRIMARY KEY' AND c.column_name = kcu.column_name
                  AND c.data_type = 'uuid' AND t.table_schema = 'public';
            """.trimIndent()
            val rs = conn.createStatement().executeQuery(sql)
            while (rs.next()) {
                tables.add(rs.getString("table_name"))
            }
        }
        return tables
    }
}