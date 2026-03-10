-- Additive, nullable columns for webhook audit payload storage.
-- Zero-downtime safe: no locks beyond a brief ALTER TABLE, no NOT NULL constraints.
ALTER TABLE processed_webhook_events
    ADD COLUMN event_type   VARCHAR(255),
    ADD COLUMN raw_payload  JSONB;
