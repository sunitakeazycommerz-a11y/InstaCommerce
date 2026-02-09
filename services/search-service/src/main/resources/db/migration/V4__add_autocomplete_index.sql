CREATE INDEX IF NOT EXISTS idx_search_documents_name_pattern
    ON search_documents (name text_pattern_ops);
