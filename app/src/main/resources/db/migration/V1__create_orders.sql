CREATE TABLE orders (
    id              BIGSERIAL PRIMARY KEY,
    customer_email  VARCHAR(255) NOT NULL,
    items           VARCHAR(2000) NOT NULL,
    price           NUMERIC(12,2) NOT NULL,
    created_at      TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_orders_created_at ON orders (created_at DESC);
