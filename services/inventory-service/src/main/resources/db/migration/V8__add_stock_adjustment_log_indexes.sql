CREATE INDEX idx_adj_product_store ON stock_adjustment_log (product_id, store_id);
CREATE INDEX idx_adj_created_at ON stock_adjustment_log (created_at);
CREATE INDEX idx_adj_reference ON stock_adjustment_log (reference_id) WHERE reference_id IS NOT NULL;
