package unit

import core.TableRelation
import java.util.*

/**
 * Фикстуры и утилиты для unit-тестов
 */
object TestFixtures {

    /**
     * Тестовые данные для проверки топологической сортировки
     * Имитирует структуру БД из README:
     * regions → users → profiles, user_settings, audit_logs, product_reviews, support_tickets
     * categories → products → order_items, product_reviews, warehouse_stocks
     * suppliers → products
     * customers → orders → order_items, shipments, order_coupons
     * discount_coupons → order_coupons
     * regions → marketing_campaigns → campaign_stats
     */
    fun createSampleTables(): List<String> = listOf(
        "regions", "users", "profiles", "user_settings", "audit_logs",
        "product_reviews", "support_tickets", "ticket_messages",
        "categories", "suppliers", "products", "warehouse_stocks",
        "customers", "orders", "order_items", "shipments",
        "discount_coupons", "order_coupons", "marketing_campaigns", "campaign_stats"
    )

    /**
     * Тестовые связи между таблицами (Foreign Keys)
     */
    fun createSampleRelations(): List<TableRelation> = listOf(
        // Users domain
        TableRelation("regions", "users"),
        TableRelation("users", "profiles"),
        TableRelation("users", "user_settings"),
        TableRelation("users", "audit_logs"),
        TableRelation("users", "product_reviews"),
        TableRelation("users", "support_tickets"),
        TableRelation("support_tickets", "ticket_messages"),

        // Warehouse domain
        TableRelation("categories", "products"),
        TableRelation("suppliers", "products"),
        TableRelation("products", "order_items"),
        TableRelation("products", "product_reviews"),
        TableRelation("products", "warehouse_stocks"),

        // Sales domain
        TableRelation("customers", "orders"),
        TableRelation("orders", "order_items"),
        TableRelation("orders", "shipments"),
        TableRelation("orders", "order_coupons"),
        TableRelation("discount_coupons", "order_coupons"),

        // Analytics domain
        TableRelation("regions", "marketing_campaigns"),
        TableRelation("marketing_campaigns", "campaign_stats")
    )

    /**
     * Генерация тестовых UUID
     */
    fun generateTestUuids(count: Int): List<UUID> = List(count) { UUID.randomUUID() }

    /**
     * Тестовые данные для маппинга
     */
    data class MappingTestData(
        val uuid: UUID,
        val expectedId: Long,
        val tableName: String = "test_table"
    )

    fun createMappingTestData(count: Int): List<MappingTestData> {
        return List(count) { i ->
            MappingTestData(
                uuid = UUID.randomUUID(),
                expectedId = (i + 1).toLong()
            )
        }
    }

    /**
     * SQL скрипт для создания тестовой схемы с UUID
     */
    const val TEST_SCHEMA_UUID = """
        CREATE TABLE IF NOT EXISTS regions (
            id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
            name VARCHAR(100) NOT NULL
        );

        CREATE TABLE IF NOT EXISTS users (
            id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
            region_id UUID REFERENCES regions(id),
            email VARCHAR(255) NOT NULL,
            created_at TIMESTAMP DEFAULT NOW()
        );

        CREATE TABLE IF NOT EXISTS profiles (
            id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
            user_id UUID REFERENCES users(id),
            first_name VARCHAR(100),
            last_name VARCHAR(100)
        );

        CREATE TABLE IF NOT EXISTS products (
            id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
            name VARCHAR(255) NOT NULL,
            price DECIMAL(10, 2) NOT NULL
        );

        CREATE TABLE IF NOT EXISTS orders (
            id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
            user_id UUID REFERENCES users(id),
            total_amount DECIMAL(10, 2),
            status VARCHAR(50) DEFAULT 'PENDING'
        );

        CREATE TABLE IF NOT EXISTS order_items (
            id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
            order_id UUID REFERENCES orders(id),
            product_id UUID REFERENCES products(id),
            quantity INT NOT NULL
        );
    """

    /**
     * SQL скрипт для создания тестовой схемы с BIGINT
     */
    const val TEST_SCHEMA_BIGINT = """
        CREATE TABLE IF NOT EXISTS regions (
            id BIGSERIAL PRIMARY KEY,
            name VARCHAR(100) NOT NULL
        );

        CREATE TABLE IF NOT EXISTS users (
            id BIGSERIAL PRIMARY KEY,
            region_id BIGINT REFERENCES regions(id),
            email VARCHAR(255) NOT NULL,
            created_at TIMESTAMP DEFAULT NOW()
        );

        CREATE TABLE IF NOT EXISTS profiles (
            id BIGSERIAL PRIMARY KEY,
            user_id BIGINT REFERENCES users(id),
            first_name VARCHAR(100),
            last_name VARCHAR(100)
        );

        CREATE TABLE IF NOT EXISTS products (
            id BIGSERIAL PRIMARY KEY,
            name VARCHAR(255) NOT NULL,
            price DECIMAL(10, 2) NOT NULL
        );

        CREATE TABLE IF NOT EXISTS orders (
            id BIGSERIAL PRIMARY KEY,
            user_id BIGINT REFERENCES users(id),
            total_amount DECIMAL(10, 2),
            status VARCHAR(50) DEFAULT 'PENDING'
        );

        CREATE TABLE IF NOT EXISTS order_items (
            id BIGSERIAL PRIMARY KEY,
            order_id BIGINT REFERENCES orders(id),
            product_id BIGINT REFERENCES products(id),
            quantity INT NOT NULL
        );
    """
}
