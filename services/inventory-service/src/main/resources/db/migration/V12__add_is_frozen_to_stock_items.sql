-- Wave 3 Issue #575: Add is_frozen flag for dark store stock drift and cycle count audits

ALTER TABLE stock_items ADD COLUMN is_frozen BOOLEAN NOT NULL DEFAULT false;

CREATE INDEX idx_stock_items_frozen ON stock_items (store_id, is_frozen);
