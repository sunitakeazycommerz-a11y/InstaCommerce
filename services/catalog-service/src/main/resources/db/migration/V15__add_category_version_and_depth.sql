ALTER TABLE categories ADD COLUMN IF NOT EXISTS version BIGINT NOT NULL DEFAULT 0;
ALTER TABLE categories ADD COLUMN IF NOT EXISTS depth INTEGER NOT NULL DEFAULT 0;

UPDATE categories
SET depth = GREATEST(0, COALESCE(array_length(string_to_array(trim(leading '/' FROM path), '/'), 1), 1) - 1)
WHERE path IS NOT NULL AND path <> '';
