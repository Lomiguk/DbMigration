package core

import org.jgrapht.graph.DefaultEdge
import org.jgrapht.graph.SimpleDirectedGraph
import org.jgrapht.traverse.TopologicalOrderIterator

class DependencyResolver {
    private val graph = SimpleDirectedGraph<String, DefaultEdge>(DefaultEdge::class.java)

    fun buildGraph(tables: List<String>, relations: List<TableRelation>) {
        // Добавляем все таблицы как вершины
        tables.forEach { graph.addVertex(it) }

        // Добавляем связи как ребра (от родителя к потомку)
        relations.forEach { rel ->
            if (graph.containsVertex(rel.parentTable) && graph.containsVertex(rel.childTable)) {
                graph.addEdge(rel.parentTable, rel.childTable)
            }
        }
    }

    /**
     * Метод возвращает список названий таблиц (List<String>) в таком порядке,
     * что родительская таблица всегда идет перед дочерней.
     */
    fun getMigrationOrder(): List<String> {
        val order = mutableListOf<String>()
        val iterator = TopologicalOrderIterator(graph)
        while (iterator.hasNext()) {
            order.add(iterator.next())
        }
        return order
    }
}