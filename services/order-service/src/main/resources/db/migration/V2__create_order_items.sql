CREATE TABLE order_items (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    order_id        UUID    NOT NULL REFERENCES orders(id) ON DELETE CASCADE,
    product_id      UUID    NOT NULL,
    product_name    VARCHAR(255) NOT NULL,
    product_sku     VARCHAR(50)  NOT NULL,
    quantity        INT     NOT NULL,
    unit_price_cents BIGINT NOT NULL,
    line_total_cents BIGINT NOT NULL,
    picked_status   VARCHAR(20) DEFAULT 'PENDING',
    CONSTRAINT chk_qty CHECK (quantity > 0)
);

CREATE INDEX idx_order_items_order ON order_items (order_id);
