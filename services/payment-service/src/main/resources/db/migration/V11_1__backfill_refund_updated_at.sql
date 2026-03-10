-- flyway:executeInTransaction=false
-- Backfill updated_at for existing rows, then enforce NOT NULL.
--
-- Runs outside a transaction so the UPDATE takes only a ROW EXCLUSIVE lock
-- instead of holding the ACCESS EXCLUSIVE lock acquired by ALTER TABLE for
-- the full duration of the backfill.  This keeps the refunds table available
-- for concurrent reads and writes during deployment.

UPDATE refunds SET updated_at = created_at WHERE updated_at IS NULL;

ALTER TABLE refunds ALTER COLUMN updated_at SET NOT NULL;
