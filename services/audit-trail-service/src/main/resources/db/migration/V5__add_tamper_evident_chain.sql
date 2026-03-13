-- Tamper-evident hash chain for audit events.
-- Each row stores SHA-256(canonical_content || previous_hash),
-- forming a forward-linked chain where alteration of any row
-- invalidates all subsequent hashes.

ALTER TABLE audit_events ADD COLUMN sequence_number BIGINT;
ALTER TABLE audit_events ADD COLUMN event_hash VARCHAR(64);
ALTER TABLE audit_events ADD COLUMN previous_hash VARCHAR(64);

CREATE SEQUENCE audit_chain_seq START WITH 1 INCREMENT BY 1 NO CYCLE;

CREATE INDEX idx_audit_chain_seq ON audit_events (sequence_number)
    WHERE sequence_number IS NOT NULL;
