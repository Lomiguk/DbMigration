-- Блок 1: Пользователи и доступы
CREATE TABLE regions
(
    id   UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    name TEXT
);
CREATE TABLE users
(
    id        UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    email     TEXT,
    region_id UUID REFERENCES regions (id)
);
CREATE INDEX idx_users_region_id ON users (region_id);
CREATE UNIQUE INDEX idx_users_email ON users (email);

CREATE TABLE profiles
(
    id      UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id UUID REFERENCES users (id),
    bio     TEXT
);
CREATE INDEX idx_profiles_user_id ON profiles (user_id);

CREATE TABLE user_settings
(
    id      UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id UUID REFERENCES users (id),
    key     TEXT,
    value   TEXT
);
CREATE INDEX idx_user_settings_user_id ON user_settings (user_id);

-- Блок 2: Склад и продукты
CREATE TABLE suppliers
(
    id   UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    name TEXT
);
CREATE TABLE categories
(
    id    UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    title TEXT
);
CREATE TABLE products
(
    id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    category_id UUID REFERENCES categories (id),
    supplier_id UUID REFERENCES suppliers (id),
    name        TEXT,
    price       DECIMAL
);
CREATE INDEX idx_products_category_id ON products (category_id);
CREATE INDEX idx_products_supplier_id ON products (supplier_id);

CREATE TABLE warehouse_stocks
(
    id         UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    product_id UUID REFERENCES products (id),
    quantity   INT
);
CREATE INDEX idx_warehouse_stocks_product_id ON warehouse_stocks (product_id);

-- Блок 3: Продажи (самый глубокий граф)
CREATE TABLE customers
(
    id   UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    name TEXT
);
CREATE TABLE orders
(
    id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    customer_id UUID REFERENCES customers (id),
    created_at  TIMESTAMP        DEFAULT now()
);
CREATE INDEX idx_orders_customer_id ON orders (customer_id);
CREATE INDEX idx_orders_created_at ON orders (created_at);

CREATE TABLE order_items
(
    id         UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    order_id   UUID REFERENCES orders (id),
    product_id UUID REFERENCES products (id),
    qty        INT
);
CREATE INDEX idx_order_items_order_id ON order_items (order_id);
CREATE INDEX idx_order_items_product_id ON order_items (product_id);

CREATE TABLE shipments
(
    id       UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    order_id UUID REFERENCES orders (id),
    address  TEXT
);
CREATE INDEX idx_shipments_order_id ON shipments (order_id);

-- Блок 4: Аналитика и логи
CREATE TABLE product_reviews
(
    id         UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    product_id UUID REFERENCES products (id),
    user_id    UUID REFERENCES users (id),
    rating     INT
);
CREATE INDEX idx_product_reviews_product_id ON product_reviews (product_id);
CREATE INDEX idx_product_reviews_user_id ON product_reviews (user_id);

CREATE TABLE audit_logs
(
    id      UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id UUID REFERENCES users (id),
    action  TEXT
);
CREATE INDEX idx_audit_logs_user_id ON audit_logs (user_id);

CREATE TABLE discount_coupons
(
    id           UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    code         TEXT,
    discount_pct INT
);
CREATE UNIQUE INDEX idx_discount_coupons_code ON discount_coupons (code);

CREATE TABLE order_coupons
(
    id        UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    order_id  UUID REFERENCES orders (id),
    coupon_id UUID REFERENCES discount_coupons (id)
);
CREATE INDEX idx_order_coupons_order_id ON order_coupons (order_id);
CREATE INDEX idx_order_coupons_coupon_id ON order_coupons (coupon_id);

CREATE TABLE support_tickets
(
    id      UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id UUID REFERENCES users (id),
    subject TEXT
);
CREATE INDEX idx_support_tickets_user_id ON support_tickets (user_id);

CREATE TABLE ticket_messages
(
    id        UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    ticket_id UUID REFERENCES support_tickets (id),
    body      TEXT
);
CREATE INDEX idx_ticket_messages_ticket_id ON ticket_messages (ticket_id);

CREATE TABLE marketing_campaigns
(
    id        UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    region_id UUID REFERENCES regions (id),
    name      TEXT
);
CREATE INDEX idx_marketing_campaigns_region_id ON marketing_campaigns (region_id);

CREATE TABLE campaign_stats
(
    id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    campaign_id UUID REFERENCES marketing_campaigns (id),
    clicks      INT
);
CREATE INDEX idx_campaign_stats_campaign_id ON campaign_stats (campaign_id);

-- Блок 5: Граничные случаи для анализа схемы
CREATE TABLE cycle_a
(
    id         UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    cycle_b_id UUID,
    name       TEXT
);

CREATE TABLE cycle_b
(
    id         UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    cycle_a_id UUID,
    name       TEXT
);

ALTER TABLE cycle_a
    ADD CONSTRAINT fk_cycle_a_b FOREIGN KEY (cycle_b_id) REFERENCES cycle_b (id);
ALTER TABLE cycle_b
    ADD CONSTRAINT fk_cycle_b_a FOREIGN KEY (cycle_a_id) REFERENCES cycle_a (id);

CREATE INDEX idx_cycle_a_cycle_b_id ON cycle_a (cycle_b_id);
CREATE INDEX idx_cycle_b_cycle_a_id ON cycle_b (cycle_a_id);

CREATE TABLE legacy_integer_audit
(
    id         BIGSERIAL PRIMARY KEY,
    message    TEXT,
    created_at TIMESTAMP DEFAULT now()
);

CREATE TABLE external_reference_codes
(
    code        TEXT PRIMARY KEY,
    description TEXT
);

CREATE TABLE staging_events
(
    event_id TEXT,
    payload  TEXT
);
