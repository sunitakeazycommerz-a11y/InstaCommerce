-- Add store_id to search_documents for multi-store filtering.
-- Nullable to remain backward-compatible with existing rows.
ALTER TABLE search_documents ADD COLUMN IF NOT EXISTS store_id UUID;

CREATE INDEX idx_search_documents_store_id ON search_documents (store_id);
