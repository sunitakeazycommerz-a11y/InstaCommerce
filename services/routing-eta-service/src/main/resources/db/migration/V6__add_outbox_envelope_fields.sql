-- Wave 24 Track A: Add standard event envelope fields to outbox_events
-- per contracts/README.md envelope specification.

ALTER TABLE outbox_events ADD COLUMN event_id UUID DEFAULT gen_random_uuid();
UPDATE outbox_events SET event_id = gen_random_uuid() WHERE event_id IS NULL;
ALTER TABLE outbox_events ALTER COLUMN event_id SET NOT NULL;

ALTER TABLE outbox_events ADD COLUMN schema_version VARCHAR(20) NOT NULL DEFAULT 'v1';
ALTER TABLE outbox_events ADD COLUMN source_service VARCHAR(100) NOT NULL DEFAULT 'routing-eta-service';
ALTER TABLE outbox_events ADD COLUMN correlation_id VARCHAR(255);
ALTER TABLE outbox_events ADD COLUMN event_timestamp TIMESTAMPTZ NOT NULL DEFAULT NOW();

CREATE INDEX idx_outbox_events_correlation ON outbox_events(correlation_id);
