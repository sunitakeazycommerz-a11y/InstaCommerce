-- Migration V6: index on updated_at for abandoned cart queries and sweep jobs
CREATE INDEX IF NOT EXISTS idx_carts_updated_at ON carts (updated_at);
CREATE INDEX IF NOT EXISTS idx_carts_expires_updated ON carts (expires_at, updated_at);
