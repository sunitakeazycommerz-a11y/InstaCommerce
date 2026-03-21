-- V3: Outbox table for reliable reconciliation event publishing
-- Implements the outbox + CDC pattern for exactly-once event delivery

CREATE TABLE reconciliation_outbox (
    id BIGSERIAL PRIMARY KEY,
    event_id VARCHAR(255) NOT NULL UNIQUE,
    run_id VARCHAR(255) NOT NULL,
    event_type VARCHAR(50) NOT NULL,
    payload JSONB NOT NULL,
    published BOOLEAN DEFAULT FALSE,
    published_at TIMESTAMP,
    created_at TIMESTAMP NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_outbox_published ON reconciliation_outbox(published);
CREATE INDEX idx_outbox_run_id ON reconciliation_outbox(run_id);
CREATE INDEX idx_outbox_created_at ON reconciliation_outbox(created_at);
