package unit

import core.DependencyResolver
import core.TableRelation
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.assertj.core.api.Assertions.assertThat
import unit.TestFixtures.createSampleRelations
import unit.TestFixtures.createSampleTables

/**
 * Unit tests for DependencyResolver component
 * Verifies correct dependency graph construction and topological sorting
 */
@DisplayName("DependencyResolver - Topological Table Sorting")
class DependencyResolverTest {

    private lateinit var resolver: DependencyResolver
    private lateinit var tables: List<String>
    private lateinit var relations: List<TableRelation>

    @BeforeEach
    fun setUp() {
        resolver = DependencyResolver()
        tables = createSampleTables()
        relations = createSampleRelations()
    }

    @Nested
    @DisplayName("Graph Construction")
    inner class GraphConstruction {

        @Test
        fun `should correctly build graph from tables and relations list`() {
            // Arrange & Act
            resolver.buildGraph(tables, relations)

            // Assert - graph built without exceptions
            val order = resolver.getMigrationOrder()

            // All tables should be in result
            assertThat(order).containsExactlyInAnyOrderElementsOf(tables)
        }

        @Test
        fun `should ignore relations for non-existent tables`() {
            // Arrange
            val invalidRelations = listOf(
                TableRelation("non_existent_parent", "users"),
                TableRelation("regions", "non_existent_child")
            )

            // Act
            resolver.buildGraph(tables, invalidRelations)
            val order = resolver.getMigrationOrder()

            // Assert - graph built only with valid vertices
            assertThat(order).containsExactlyInAnyOrderElementsOf(tables)
        }

        @Test
        fun `should work with empty relations list`() {
            // Arrange
            resolver.buildGraph(tables, emptyList())

            // Act
            val order = resolver.getMigrationOrder()

            // Assert - all tables in insertion order (no dependencies)
            assertThat(order).containsExactlyInAnyOrderElementsOf(tables)
        }

        @Test
        fun `should work with empty tables list`() {
            // Arrange
            resolver.buildGraph(emptyList(), emptyList())

            // Act
            val order = resolver.getMigrationOrder()

            // Assert - empty result
            assertThat(order).isEmpty()
        }
    }

    @Nested
    @DisplayName("Topological Migration Order")
    inner class TopologicalOrder {

        @Test
        fun `should guarantee parent tables come before child tables`() {
            // Arrange
            resolver.buildGraph(tables, relations)

            // Act
            val order = resolver.getMigrationOrder()

            // Assert
            relations.forEach { relation ->
                val parentIndex = order.indexOf(relation.parentTable)
                val childIndex = order.indexOf(relation.childTable)

                assertThat(parentIndex)
                    .withFailMessage(
                        "Table '${relation.parentTable}' should come before '${relation.childTable}'"
                    )
                    .isLessThan(childIndex)
            }
        }

        @Test
        fun `should correctly sort complex users domain hierarchy`() {
            // Arrange - users domain relations
            val userDomainRelations = listOf(
                TableRelation("regions", "users"),
                TableRelation("users", "profiles"),
                TableRelation("users", "user_settings"),
                TableRelation("users", "audit_logs"),
                TableRelation("users", "support_tickets"),
                TableRelation("support_tickets", "ticket_messages")
            )

            val userTables = listOf("regions", "users", "profiles", "user_settings", "audit_logs", "support_tickets", "ticket_messages")
            resolver.buildGraph(userTables, userDomainRelations)

            // Act
            val order = resolver.getMigrationOrder()

            // Assert
            val regionsIndex = order.indexOf("regions")
            val usersIndex = order.indexOf("users")

            assertThat(regionsIndex).isLessThan(usersIndex)

            listOf("profiles", "user_settings", "audit_logs", "support_tickets").forEach { table ->
                assertThat(usersIndex).isLessThan(order.indexOf(table))
            }

            assertThat(order.indexOf("support_tickets"))
                .isLessThan(order.indexOf("ticket_messages"))
        }

        @Test
        fun `should correctly handle multiple dependencies (products)`() {
            // Arrange - products depends on categories and suppliers
            val productRelations = listOf(
                TableRelation("categories", "products"),
                TableRelation("suppliers", "products"),
                TableRelation("products", "order_items")
            )

            val productTables = listOf("categories", "suppliers", "products", "order_items")
            resolver.buildGraph(productTables, productRelations)

            // Act
            val order = resolver.getMigrationOrder()

            // Assert
            val productsIndex = order.indexOf("products")
            assertThat(order.indexOf("categories")).isLessThan(productsIndex)
            assertThat(order.indexOf("suppliers")).isLessThan(productsIndex)

            assertThat(productsIndex).isLessThan(order.indexOf("order_items"))
        }

        @Test
        fun `should correctly handle diamond dependency (order_coupons)`() {
            // Arrange - order_coupons depends on orders and discount_coupons
            val diamondRelations = listOf(
                TableRelation("customers", "orders"),
                TableRelation("orders", "order_coupons"),
                TableRelation("discount_coupons", "order_coupons")
            )

            val diamondTables = listOf("customers", "orders", "discount_coupons", "order_coupons")
            resolver.buildGraph(diamondTables, diamondRelations)

            // Act
            val order = resolver.getMigrationOrder()

            // Assert
            val orderCouponsIndex = order.indexOf("order_coupons")
            assertThat(order.indexOf("orders")).isLessThan(orderCouponsIndex)
            assertThat(order.indexOf("discount_coupons")).isLessThan(orderCouponsIndex)

            assertThat(order.indexOf("customers")).isLessThan(order.indexOf("orders"))
        }
    }

    @Nested
    @DisplayName("Result Completeness")
    inner class Completeness {

        @Test
        fun `should include all tables in sorting result`() {
            // Arrange
            resolver.buildGraph(tables, relations)

            // Act
            val order = resolver.getMigrationOrder()

            // Assert
            assertThat(order)
                .hasSize(tables.size)
                .containsExactlyInAnyOrderElementsOf(tables)
        }

        @Test
        fun `should not contain duplicates in result`() {
            // Arrange
            resolver.buildGraph(tables, relations)

            // Act
            val order = resolver.getMigrationOrder()

            // Assert
            assertThat(order)
                .doesNotHaveDuplicates()
                .hasSize(tables.size)
        }
    }

    @Nested
    @DisplayName("Real-world Project Scenarios")
    inner class RealWorldScenarios {

        @Test
        fun `should correctly sort all 20 project tables`() {
            // Arrange - full schema from README
            val fullTables = createSampleTables()
            val fullRelations = createSampleRelations()

            resolver.buildGraph(fullTables, fullRelations)

            // Act
            val order = resolver.getMigrationOrder()

            // Assert
            assertThat(order).hasSize(20)

            val assertIndex = { tableName: String -> order.indexOf(tableName) }

            assertThat(assertIndex("regions")).isLessThan(5)
            assertThat(assertIndex("regions")).isLessThan(assertIndex("users"))
            assertThat(assertIndex("categories")).isLessThan(assertIndex("products"))
            assertThat(assertIndex("suppliers")).isLessThan(assertIndex("products"))
            assertThat(assertIndex("order_items")).isGreaterThan(assertIndex("orders"))
            assertThat(assertIndex("order_items")).isGreaterThan(assertIndex("products"))
        }
    }
}
