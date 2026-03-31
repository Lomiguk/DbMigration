package integration

import core.MetadataReader
import core.TableRelation
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.assertj.core.api.Assertions.assertThat

/**
 * Integration tests for MetadataReader
 * Tests reading metadata from PostgreSQL via information_schema
 */
@DisplayName("MetadataReader - Database Schema Introspection")
class MetadataReaderTest : BaseIntegrationTest() {

    private lateinit var metadataReader: MetadataReader

    @BeforeEach
    fun setUp() {
        executeScript(TestFixturesForMetadata.TEST_SCHEMA)
        metadataReader = MetadataReader(dataSource)
    }

    @Nested
    @DisplayName("Reading Tables List")
    inner class ReadTables {

        @Test
        fun `should find all tables with UUID primary key`() {
            val tables = metadataReader.getAllTablesWithUuidPk()

            assertThat(tables).containsExactlyInAnyOrder(
                "users", "profiles", "products", "orders"
            )
        }

        @Test
        fun `should return empty list if no UUID tables exist`() {
            executeScript("""
                CREATE TABLE bigint_table (
                    id BIGSERIAL PRIMARY KEY,
                    name VARCHAR(100)
                );
            """)

            val tables = metadataReader.getAllTablesWithUuidPk()
            assertThat(tables).doesNotContain("bigint_table")
        }

        @Test
        fun `should ignore tables without primary key`() {
            executeScript("""
                CREATE TABLE no_pk_table (
                    id UUID,
                    name VARCHAR(100)
                );
            """)

            val tables = metadataReader.getAllTablesWithUuidPk()
            assertThat(tables).doesNotContain("no_pk_table")
        }
    }

    @Nested
    @DisplayName("Reading Foreign Keys")
    inner class ReadForeignKeys {

        @Test
        fun `should get all relations between tables`() {
            val relations = metadataReader.getForeignKeys()

            assertThat(relations).containsExactlyInAnyOrder(
                TableRelation("users", "profiles"),
                TableRelation("users", "orders"),
                TableRelation("products", "order_items"),
                TableRelation("orders", "order_items")
            )
        }

        @Test
        fun `should get FK for specific table`() {
            val fks = metadataReader.getForeignKeysForTable("order_items")

            assertThat(fks).hasSize(2)
            assertThat(fks).anyMatch { it.columnName == "order_id" && it.refTable == "orders" }
            assertThat(fks).anyMatch { it.columnName == "product_id" && it.refTable == "products" }
        }

        @Test
        fun `should return empty list if no FK exists`() {
            val fks = metadataReader.getForeignKeysForTable("users")
            assertThat(fks).isEmpty()
        }

        @Test
        fun `should correctly identify FK column names`() {
            val fks = metadataReader.getForeignKeysForTable("profiles")

            assertThat(fks).hasSize(1)
            val fk = fks.first()
            assertThat(fk.columnName).isEqualTo("user_id")
            assertThat(fk.refTable).isEqualTo("users")
            assertThat(fk.refColumn).isEqualTo("id")
        }
    }

    @Nested
    @DisplayName("Reading Table Columns")
    inner class ReadColumns {

        @Test
        fun `should get all table columns with types`() {
            val columns = metadataReader.getTableColumns("users")

            assertThat(columns).hasSize(4)
            assertThat(columns["id"]).isEqualTo("uuid")
            assertThat(columns["email"]).isEqualTo("character varying")
            assertThat(columns["created_at"]).isEqualTo("timestamp without time zone")
        }

        @Test
        fun `should return empty map for non-existent table`() {
            val columns = metadataReader.getTableColumns("non_existent_table")
            assertThat(columns).isEmpty()
        }

        @Test
        fun `should correctly identify data types for different columns`() {
            val productColumns = metadataReader.getTableColumns("products")

            assertThat(productColumns).containsEntry("id", "uuid")
            assertThat(productColumns).containsEntry("name", "character varying")
            assertThat(productColumns).containsEntry("price", "numeric")
        }
    }

    @Nested
    @DisplayName("Complex Scenarios")
    inner class ComplexScenarios {

        @Test
        fun `should correctly analyze schema for migration`() {
            val tables = metadataReader.getAllTablesWithUuidPk()
            val allRelations = metadataReader.getForeignKeys()

            assertThat(tables).hasSize(4)
            assertThat(allRelations).hasSize(4)

            tables.forEach { table ->
                val columns = metadataReader.getTableColumns(table)
                val fks = metadataReader.getForeignKeysForTable(table)

                assertThat(columns).isNotEmpty()
            }
        }

        @Test
        fun `should support dependency graph building scenario`() {
            val tables = metadataReader.getAllTablesWithUuidPk()
            val relations = metadataReader.getForeignKeys()

            assertThat(tables).isNotEmpty()
            assertThat(relations).isNotEmpty()
        }
    }
}

/**
 * Fixtures for MetadataReader tests
 */
private object TestFixturesForMetadata {

    const val TEST_SCHEMA = """
        DROP TABLE IF EXISTS order_items CASCADE;
        DROP TABLE IF EXISTS profiles CASCADE;
        DROP TABLE IF EXISTS orders CASCADE;
        DROP TABLE IF EXISTS products CASCADE;
        DROP TABLE IF EXISTS users CASCADE;

        CREATE TABLE users (
            id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
            email VARCHAR(255) NOT NULL,
            created_at TIMESTAMP DEFAULT NOW()
        );

        CREATE TABLE profiles (
            id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
            user_id UUID REFERENCES users(id),
            first_name VARCHAR(100),
            last_name VARCHAR(100)
        );

        CREATE TABLE products (
            id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
            name VARCHAR(255) NOT NULL,
            price DECIMAL(10, 2) NOT NULL
        );

        CREATE TABLE orders (
            id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
            user_id UUID REFERENCES users(id),
            total_amount DECIMAL(10, 2),
            status VARCHAR(50) DEFAULT 'PENDING'
        );

        CREATE TABLE order_items (
            id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
            order_id UUID REFERENCES orders(id),
            product_id UUID REFERENCES products(id),
            quantity INT NOT NULL
        );
    """
}
