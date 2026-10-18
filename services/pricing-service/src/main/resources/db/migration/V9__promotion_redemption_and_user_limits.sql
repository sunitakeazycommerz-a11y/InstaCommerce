-- Add per-user redemption limit to promotions and track user promotion redemptions
ALTER TABLE promotions ADD COLUMN IF NOT EXISTS max_redemptions_per_user INTEGER DEFAULT 1;

CREATE TABLE IF NOT EXISTS promotion_redemptions (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    promotion_id    UUID         NOT NULL REFERENCES promotions(id),
    user_id         UUID         NOT NULL,
    order_id        UUID,
    discount_cents  BIGINT       NOT NULL DEFAULT 0,
    redeemed_at     TIMESTAMPTZ  NOT NULL DEFAULT now()
);

-- Unique index preventing duplicate redemption for the same order
CREATE UNIQUE INDEX IF NOT EXISTS idx_promo_redemption_user_order
    ON promotion_redemptions (promotion_id, user_id, order_id);

-- Index for fast user redemption limit verification
CREATE INDEX IF NOT EXISTS idx_promo_redemption_user
    ON promotion_redemptions (promotion_id, user_id);
