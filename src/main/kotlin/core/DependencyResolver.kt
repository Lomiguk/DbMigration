package core

import org.jgrapht.alg.connectivity.KosarajuStrongConnectivityInspector
import org.jgrapht.graph.DefaultEdge
import org.jgrapht.graph.SimpleDirectedGraph
import org.jgrapht.traverse.TopologicalOrderIterator

data class DependencyAnalysis(
    val migrationOrder: List<String>,
    val cyclicComponents: List<Set<String>>,
    val blockedTables: Set<String>,
    val blockedRelations: List<TableRelation>
) {
    val hasCycles: Boolean
        get() = cyclicComponents.isNotEmpty()
}

class DependencyResolver {

    private var graph = SimpleDirectedGraph<String, DefaultEdge>(DefaultEdge::class.java)
    private var tables: List<String> = emptyList()
    private var relations: List<TableRelation> = emptyList()
    private var selfCycleTables: Set<String> = emptySet()

    fun buildGraph(tables: List<String>, relations: List<TableRelation>) {
        graph = SimpleDirectedGraph(DefaultEdge::class.java)
        this.tables = tables.distinct()
        this.relations = relations
        selfCycleTables = relations
            .filter { it.parentTable == it.childTable && it.parentTable in this.tables }
            .map { it.parentTable }
            .toSet()

        this.tables.forEach { graph.addVertex(it) }

        relations.forEach { rel ->
            if (rel.parentTable != rel.childTable &&
                graph.containsVertex(rel.parentTable) &&
                graph.containsVertex(rel.childTable)
            ) {
                graph.addEdge(rel.parentTable, rel.childTable)
            }
        }
    }

    /**
     * Метод возвращает список названий таблиц в таком порядке,
     * что родительская таблица всегда идет перед дочерней.
     */
    fun getMigrationOrder(): List<String> = analyze().migrationOrder

    fun analyze(): DependencyAnalysis {
        val cyclicComponents = findCyclicComponents()
        val cyclicTables = cyclicComponents.flatten().toSet()
        val blockedTables = findBlockedTables(cyclicTables)
        val migratableTables = tables.filterNot { it in blockedTables }.toSet()
        val blockedRelations = relations.filter {
            it.parentTable in blockedTables || it.childTable in blockedTables
        }

        return DependencyAnalysis(
            migrationOrder = topologicalOrder(migratableTables),
            cyclicComponents = cyclicComponents,
            blockedTables = blockedTables,
            blockedRelations = blockedRelations
        )
    }

    private fun findCyclicComponents(): List<Set<String>> {
        val components = KosarajuStrongConnectivityInspector(graph).stronglyConnectedSets()
        val multiTableCycles = components
            .filter { it.size > 1 }
            .map { it.toSortedSet() }

        val selfCycles = selfCycleTables
            .filter { table -> multiTableCycles.none { table in it } }
            .map { sortedSetOf(it) }

        return (multiTableCycles + selfCycles).sortedBy { it.joinToString(",") }
    }

    private fun findBlockedTables(cyclicTables: Set<String>): Set<String> {
        if (cyclicTables.isEmpty()) return emptySet()

        val blocked = linkedSetOf<String>()
        val queue = ArrayDeque<String>()
        cyclicTables.forEach {
            blocked.add(it)
            queue.add(it)
        }

        while (queue.isNotEmpty()) {
            val table = queue.removeFirst()
            graph.outgoingEdgesOf(table).forEach { edge ->
                val child = graph.getEdgeTarget(edge)
                if (blocked.add(child)) {
                    queue.add(child)
                }
            }
        }

        return blocked
    }

    private fun topologicalOrder(migratableTables: Set<String>): List<String> {
        val subgraph = SimpleDirectedGraph<String, DefaultEdge>(DefaultEdge::class.java)
        migratableTables.forEach { subgraph.addVertex(it) }

        relations.forEach { rel ->
            if (rel.parentTable != rel.childTable &&
                rel.parentTable in migratableTables &&
                rel.childTable in migratableTables
            ) {
                subgraph.addEdge(rel.parentTable, rel.childTable)
            }
        }

        val order = mutableListOf<String>()
        val iterator = TopologicalOrderIterator(subgraph)
        while (iterator.hasNext()) {
            order.add(iterator.next())
        }
        return order
    }
}
