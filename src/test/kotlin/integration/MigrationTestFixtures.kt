package integration

/**
 * Fixtures for migration tests
 */
object MigrationTestFixtures {

    const val SOURCE_SCHEMA_UUID = """
        CREATE TABLE IF NOT EXISTS users (
            id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
            email VARCHAR(255) NOT NULL,
            created_at TIMESTAMP DEFAULT NOW()
        );

        CREATE TABLE IF NOT EXISTS products (
            id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
            name VARCHAR(255) NOT NULL,
            price DECIMAL(10, 2) NOT NULL
        );

        CREATE TABLE IF NOT EXISTS profiles (
            id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
            user_id UUID REFERENCES users(id),
            first_name VARCHAR(100),
            last_name VARCHAR(100)
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

    const val TARGET_SCHEMA_BIGINT = """
        CREATE TABLE IF NOT EXISTS users (
            id BIGSERIAL PRIMARY KEY,
            email VARCHAR(255) NOT NULL,
            created_at TIMESTAMP DEFAULT NOW()
        );

        CREATE TABLE IF NOT EXISTS products (
            id BIGSERIAL PRIMARY KEY,
            name VARCHAR(255) NOT NULL,
            price DECIMAL(10, 2) NOT NULL
        );

        CREATE TABLE IF NOT EXISTS profiles (
            id BIGSERIAL PRIMARY KEY,
            user_id BIGINT REFERENCES users(id),
            first_name VARCHAR(100),
            last_name VARCHAR(100)
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
