CREATE TABLE orders (
                        id              BIGSERIAL PRIMARY KEY,
                        customer_id     VARCHAR(255) NOT NULL,
                        status          VARCHAR(50)  NOT NULL DEFAULT 'PENDING',
                        total_amount    NUMERIC(12,2),
                        created_at      TIMESTAMP NOT NULL DEFAULT NOW(),
                        updated_at      TIMESTAMP NOT NULL DEFAULT NOW()
);
CREATE INDEX idx_orders_customer ON orders(customer_id);
CREATE INDEX idx_orders_status ON orders(status);