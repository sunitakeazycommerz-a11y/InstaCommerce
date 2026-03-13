ALTER TABLE orders ADD COLUMN quote_id UUID;
CREATE INDEX idx_orders_quote_id ON orders (quote_id);
