CREATE TABLE cart_items (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    cart_id         UUID            NOT NULL REFERENCES carts(id) ON DELETE CASCADE,
    product_id      UUID            NOT NULL,
    product_name    VARCHAR(255)    NOT NULL,
    unit_price_cents BIGINT         NOT NULL,
    quantity        INT             NOT NULL,
    added_at        TIMESTAMPTZ     NOT NULL DEFAULT now(),
    CONSTRAINT chk_cart_item_qty CHECK (quantity > 0),
    CONSTRAINT uq_cart_product UNIQUE (cart_id, product_id)
);

CREATE INDEX idx_cart_items_cart ON cart_items (cart_id);
