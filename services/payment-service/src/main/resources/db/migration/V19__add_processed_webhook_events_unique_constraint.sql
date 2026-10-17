-- Additive columns and unique constraint for strict webhook deduplication.
-- Prevents duplicate payment capture / state transitions under PSP network retries.

ALTER TABLE processed_webhook_events
    ADD COLUMN IF NOT EXISTS external_payment_reference VARCHAR(255),
    ADD COLUMN IF NOT EXISTS psp_event_id              VARCHAR(255);

-- Backfill psp_event_id from event_id for existing rows
UPDATE processed_webhook_events
    SET psp_event_id = event_id
    WHERE psp_event_id IS NULL;

-- Strict unique index on external_payment_reference and psp_event_id
CREATE UNIQUE INDEX IF NOT EXISTS uq_processed_webhook_ref_event
    ON processed_webhook_events (external_payment_reference, psp_event_id)
    WHERE external_payment_reference IS NOT NULL AND psp_event_id IS NOT NULL;

CREATE INDEX IF NOT EXISTS idx_processed_webhook_ext_ref
    ON processed_webhook_events (external_payment_reference);
