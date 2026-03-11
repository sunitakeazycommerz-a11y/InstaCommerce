-- Wave 16 Lane C: Add standard event envelope fields to outbox_events
-- per contracts/README.md envelope specification.

-- Step 1: Add nullable column (metadata-only, no table rewrite)
ALTER TABLE outbox_events ADD COLUMN event_id UUID DEFAULT gen_random_uuid();

-- Step 2: Backfill existing rows
UPDATE outbox_events SET event_id = gen_random_uuid() WHERE event_id IS NULL;

-- Step 3: Add NOT NULL constraint
ALTER TABLE outbox_events ALTER COLUMN event_id SET NOT NULL;
ALTER TABLE outbox_events ADD COLUMN schema_version VARCHAR(20) NOT NULL DEFAULT '1.0';
ALTER TABLE outbox_events ADD COLUMN source_service VARCHAR(100) NOT NULL DEFAULT 'payment-service';
ALTER TABLE outbox_events ADD COLUMN correlation_id VARCHAR(255);
ALTER TABLE outbox_events ADD COLUMN event_timestamp TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW();

CREATE INDEX idx_outbox_events_correlation_id ON outbox_events(correlation_id);
