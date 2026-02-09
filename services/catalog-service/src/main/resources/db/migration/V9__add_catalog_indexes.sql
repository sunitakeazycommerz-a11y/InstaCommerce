CREATE INDEX IF NOT EXISTS idx_products_brand ON products (brand) WHERE brand IS NOT NULL;
CREATE INDEX IF NOT EXISTS idx_products_price ON products (base_price_cents) WHERE is_active = true;
CREATE INDEX IF NOT EXISTS idx_products_cat_active ON products (category_id, is_active) WHERE is_active = true;

CREATE INDEX IF NOT EXISTS idx_coupon_usages_user ON coupon_usages (user_id);
CREATE INDEX IF NOT EXISTS idx_coupon_usages_coupon_user ON coupon_usages (coupon_id, user_id);

CREATE INDEX IF NOT EXISTS idx_outbox_created ON outbox_events (created_at) WHERE sent = true;
