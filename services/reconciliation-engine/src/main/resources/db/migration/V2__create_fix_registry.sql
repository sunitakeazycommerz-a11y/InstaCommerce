-- V2: Fix registry table for idempotency tracking
-- Tracks all applied fixes to ensure idempotent reprocessing

CREATE TABLE reconciliation_fix_registry (
    fix_id VARCHAR(255) PRIMARY KEY,
    mismatch_type VARCHAR(100) NOT NULL,
    transaction_id VARCHAR(255) NOT NULL,
    applied_at TIMESTAMP NOT NULL DEFAULT NOW(),
    created_at TIMESTAMP NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_fix_registry_transaction_id ON reconciliation_fix_registry(transaction_id);
CREATE INDEX idx_fix_registry_applied_at ON reconciliation_fix_registry(applied_at DESC);
