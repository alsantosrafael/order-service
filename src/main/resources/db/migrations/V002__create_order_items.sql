CREATE TABLE order_items (
                             id          BIGSERIAL PRIMARY KEY,
                             order_id    BIGINT NOT NULL REFERENCES orders(id) ON DELETE CASCADE,
                             product_id  VARCHAR(255) NOT NULL,
                             quantity    INT NOT NULL CHECK (quantity > 0),
                             unit_price  NUMERIC(12,2) NOT NULL
);
CREATE INDEX idx_items_order ON order_items(order_id);