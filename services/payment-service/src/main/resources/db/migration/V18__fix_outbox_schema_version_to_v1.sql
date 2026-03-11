-- Wave 18 Lane A: Align outbox_events.schema_version with contracts/EventEnvelope.v1.json
-- The V17 migration set the default to '1.0'; the canonical envelope format is 'v1'.

-- Step 1: Backfill existing rows that have the old value
UPDATE outbox_events SET schema_version = 'v1' WHERE schema_version = '1.0';

-- Step 2: Change the column default for new rows
ALTER TABLE outbox_events ALTER COLUMN schema_version SET DEFAULT 'v1';
