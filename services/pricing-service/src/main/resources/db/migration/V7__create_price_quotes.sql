CREATE TABLE price_quotes (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id         UUID NOT NULL,
    content_hash    VARCHAR(64) NOT NULL,
    subtotal_cents  BIGINT NOT NULL,
    discount_cents  BIGINT NOT NULL,
    total_cents     BIGINT NOT NULL,
    currency        VARCHAR(3) NOT NULL DEFAULT 'INR',
    coupon_code     VARCHAR(64),
    item_count      INTEGER NOT NULL,
    expires_at      TIMESTAMPTZ NOT NULL,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_price_quotes_user_expires ON price_quotes (user_id, expires_at);
CREATE INDEX idx_price_quotes_expires ON price_quotes (expires_at);
