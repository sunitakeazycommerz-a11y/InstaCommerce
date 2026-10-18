-- Add index on lower(name) text_pattern_ops to accelerate case-insensitive prefix searches for autocomplete
CREATE INDEX IF NOT EXISTS idx_search_documents_lower_name
    ON search_documents (lower(name) text_pattern_ops);
