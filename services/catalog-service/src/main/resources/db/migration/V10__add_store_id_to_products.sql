-- Add store_id to products for multi-store catalog support.
-- Nullable to remain backward-compatible with existing rows.
ALTER TABLE products ADD COLUMN IF NOT EXISTS store_id UUID;

CREATE INDEX idx_products_store_id ON products (store_id);
