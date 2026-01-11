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
CREATE TABLE profiles
(
    id      UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id UUID REFERENCES users (id),
    bio     TEXT
);
CREATE TABLE user_settings
(
    id      UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id UUID REFERENCES users (id),
    key     TEXT,
    value   TEXT
);

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
CREATE TABLE warehouse_stocks
(
    id         UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    product_id UUID REFERENCES products (id),
    quantity   INT
);

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
CREATE TABLE order_items
(
    id         UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    order_id   UUID REFERENCES orders (id),
    product_id UUID REFERENCES products (id),
    qty        INT
);
CREATE TABLE shipments
(
    id       UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    order_id UUID REFERENCES orders (id),
    address  TEXT
);

-- Блок 4: Аналитика и логи
CREATE TABLE product_reviews
(
    id         UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    product_id UUID REFERENCES products (id),
    user_id    UUID REFERENCES users (id),
    rating     INT
);
CREATE TABLE audit_logs
(
    id      UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id UUID REFERENCES users (id),
    action  TEXT
);
CREATE TABLE discount_coupons
(
    id           UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    code         TEXT,
    discount_pct INT
);
CREATE TABLE order_coupons
(
    id        UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    order_id  UUID REFERENCES orders (id),
    coupon_id UUID REFERENCES discount_coupons (id)
);
CREATE TABLE support_tickets
(
    id      UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id UUID REFERENCES users (id),
    subject TEXT
);
CREATE TABLE ticket_messages
(
    id        UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    ticket_id UUID REFERENCES support_tickets (id),
    body      TEXT
);
CREATE TABLE marketing_campaigns
(
    id        UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    region_id UUID REFERENCES regions (id),
    name      TEXT
);
CREATE TABLE campaign_stats
(
    id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    campaign_id UUID REFERENCES marketing_campaigns (id),
    clicks      INT
);