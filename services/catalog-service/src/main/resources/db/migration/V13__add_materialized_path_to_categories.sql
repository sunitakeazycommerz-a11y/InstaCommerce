-- Add materialized path column to categories for fast hierarchical taxonomy sub-tree querying
ALTER TABLE categories ADD COLUMN IF NOT EXISTS path VARCHAR(255) NOT NULL DEFAULT '';

-- Backfill paths for existing root categories
UPDATE categories SET path = '/' || slug WHERE parent_id IS NULL AND (path IS NULL OR path = '');

-- Backfill paths for existing 1st level sub-categories
UPDATE categories c
SET path = p.path || '/' || c.slug
FROM categories p
WHERE c.parent_id = p.id AND (c.path IS NULL OR c.path = '');

-- Btree prefix pattern matching index for fast range scans (path LIKE '/groceries/%')
CREATE INDEX IF NOT EXISTS idx_categories_path ON categories (path varchar_pattern_ops);
