package core

data class MigrationScope(
    val tableIdentityInfo: List<TableIdentityInfo>,
    val eligibleTables: List<String>,
    val relations: List<TableRelation>,
    val dependencyAnalysis: DependencyAnalysis
) {
    val migrationOrder: List<String>
        get() = dependencyAnalysis.migrationOrder

    val skippedTables: List<TableIdentityInfo>
        get() = tableIdentityInfo.filterNot { it.eligibleForUuidMigration }
}

object MigrationScopePlanner {
    fun analyze(reader: MetadataReader): MigrationScope {
        val tableIdentityInfo = reader.getTableIdentityInfo()
        val eligibleTables = tableIdentityInfo
            .filter { it.eligibleForUuidMigration }
            .map { it.tableName }
        val relations = reader.getForeignKeys()
        val resolver = DependencyResolver()
        resolver.buildGraph(eligibleTables, relations)

        return MigrationScope(
            tableIdentityInfo = tableIdentityInfo,
            eligibleTables = eligibleTables,
            relations = relations,
            dependencyAnalysis = resolver.analyze()
        )
    }
}
