package tools

import java.sql.Connection
import java.sql.DriverManager
import java.util.*

class TestDataGenerator(private val url: String) {
    private val user = "user"
    private val password = "password"

    fun generateAll(count: Int) {
        DriverManager.getConnection("$url?rewriteBatchedInserts=true", user, password).use { conn ->
            conn.autoCommit = false
            println("Начало генерации данных...")

            // 1. Родительские справочники
            val regionIds = insertBatch(conn, "regions", listOf("name"), count) { listOf("Region_$it") }
            val categoryIds = insertBatch(conn, "categories", listOf("title"), count) { listOf("Category_$it") }
            val supplierIds = insertBatch(conn, "suppliers", listOf("name"), count) { listOf("Supplier_$it") }

            // ДОБАВЛЕНО: Генерация клиентов, так как orders ссылается именно на них
            val customerIds = insertBatch(conn, "customers", listOf("name"), count) { listOf("Customer_$it") }

            // 2. Уровень 1
            insertBatch(conn, "users", listOf("email", "region_id"), count) {
                listOf("user$it@example.com", regionIds.random())
            }
            val productIds = insertBatch(conn, "products", listOf("category_id", "supplier_id", "name", "price"), count) {
                listOf(categoryIds.random(), supplierIds.random(), "Product_$it", it * 10.5)
            }

            // 3. Уровень 2 (ИСПРАВЛЕНО: привязываем заказы к клиентам, а не к юзерам)
            val orderIds = insertBatch(conn, "orders", listOf("customer_id"), count) {
                listOf(customerIds.random()) // Раньше здесь было userIds.random()
            }

            insertBatch(conn, "order_items", listOf("order_id", "product_id", "qty"), count) {
                listOf(orderIds.random(), productIds.random(), (1..10).random())
            }

            conn.commit()
            println("Успешно сгенерировано $count записей для связанных таблиц.")
        }
    }

    private fun insertBatch(
        conn: Connection,
        table: String,
        cols: List<String>,
        count: Int,
        rowProvider: (Int) -> List<Any>
    ): List<UUID> {
        val ids = mutableListOf<UUID>()
        val placeholders = cols.joinToString(", ") { "?" }
        val sql = "INSERT INTO $table (id, ${cols.joinToString(", ")}) VALUES (?, $placeholders)"
        val pstmt = conn.prepareStatement(sql)

        for (i in 1..count) {
            val id = UUID.randomUUID()
            ids.add(id)
            pstmt.setObject(1, id)
            rowProvider(i).forEachIndexed { index, value ->
                pstmt.setObject(index + 2, value)
            }
            pstmt.addBatch()

            if (i % 5000 == 0) pstmt.executeBatch()
        }
        pstmt.executeBatch()
        return ids
    }
}