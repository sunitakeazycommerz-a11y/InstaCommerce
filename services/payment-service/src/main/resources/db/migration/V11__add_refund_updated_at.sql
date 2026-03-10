-- Add updated_at to the refunds table, mirroring the column already present on payments.
-- Required by the stale-pending refund recovery job.
--
-- DDL-only: add the nullable column and set its default so new inserts are
-- covered immediately.  The backfill of existing rows and the NOT NULL
-- constraint are applied in V11_1 outside a transaction to avoid holding an
-- ACCESS EXCLUSIVE lock for the duration of the UPDATE.

-- Fast, metadata-only on PG 11+.
ALTER TABLE refunds ADD COLUMN updated_at TIMESTAMPTZ;

-- Default ensures no NULLs from concurrent inserts between this migration
-- and the V11_1 backfill.
ALTER TABLE refunds ALTER COLUMN updated_at SET DEFAULT now();
