-- Prevent double-redemption of same coupon for same order (retry safety)
CREATE UNIQUE INDEX IF NOT EXISTS idx_coupon_redemption_order
    ON coupon_redemptions (coupon_id, order_id);

-- Optimistic locking support
ALTER TABLE coupons ADD COLUMN IF NOT EXISTS version BIGINT NOT NULL DEFAULT 0;
