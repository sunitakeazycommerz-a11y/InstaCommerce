CREATE EXTENSION IF NOT EXISTS "pgcrypto";

CREATE TABLE stock_items (
    id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    product_id  UUID         NOT NULL,
    store_id    VARCHAR(50)  NOT NULL,
    on_hand     INT          NOT NULL DEFAULT 0,
    reserved    INT          NOT NULL DEFAULT 0,
    updated_at  TIMESTAMPTZ  NOT NULL DEFAULT now(),
    version     BIGINT       NOT NULL DEFAULT 0,
    CONSTRAINT uq_stock_product_store UNIQUE (product_id, store_id),
    CONSTRAINT chk_on_hand_non_negative CHECK (on_hand >= 0),
    CONSTRAINT chk_reserved_non_negative CHECK (reserved >= 0),
    CONSTRAINT chk_reserved_le_on_hand CHECK (reserved <= on_hand)
);

CREATE INDEX idx_stock_store ON stock_items (store_id);
CREATE INDEX idx_stock_product ON stock_items (product_id);
