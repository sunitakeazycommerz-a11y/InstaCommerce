-- Wave 36 Track A: Reconciliation Database Schema
-- Authoritative PostgreSQL schema for reconciliation engine (replaces file-based state)

-- Reconciliation runs (daily batches)
CREATE TABLE reconciliation_runs (
    run_id BIGSERIAL PRIMARY KEY,
    run_date DATE NOT NULL UNIQUE,
    scheduled_at TIMESTAMP NOT NULL DEFAULT NOW(),
    started_at TIMESTAMP,
    completed_at TIMESTAMP,
    mismatch_count INT DEFAULT 0,
    auto_fixed_count INT DEFAULT 0,
    manual_review_count INT DEFAULT 0,
    status VARCHAR(20) NOT NULL DEFAULT 'PENDING' CHECK (status IN ('PENDING', 'IN_PROGRESS', 'COMPLETED', 'FAILED')),
    created_at TIMESTAMP NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_reconciliation_runs_date ON reconciliation_runs(run_date DESC);
CREATE INDEX idx_reconciliation_runs_status ON reconciliation_runs(status);

-- Actual mismatches found
CREATE TABLE reconciliation_mismatches (
    mismatch_id BIGSERIAL PRIMARY KEY,
    run_id BIGINT NOT NULL REFERENCES reconciliation_runs(run_id) ON DELETE CASCADE,
    transaction_id VARCHAR(255) NOT NULL,
    ledger_amount NUMERIC(19, 2) NOT NULL,
    psp_amount NUMERIC(19, 2) NOT NULL,
    discrepancy_amount NUMERIC(19, 2) NOT NULL GENERATED ALWAYS AS (ledger_amount - psp_amount) STORED,
    discrepancy_reason VARCHAR(500),
    auto_fixed BOOLEAN DEFAULT FALSE,
    manual_review_required BOOLEAN DEFAULT FALSE,
    fix_applied_at TIMESTAMP,
    created_at TIMESTAMP NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_mismatches_run_id ON reconciliation_mismatches(run_id);
CREATE INDEX idx_mismatches_transaction_id ON reconciliation_mismatches(transaction_id);
CREATE INDEX idx_mismatches_manual_review ON reconciliation_mismatches(manual_review_required) WHERE manual_review_required = TRUE;

-- Audit trail for fixes applied
CREATE TABLE reconciliation_fixes (
    fix_id BIGSERIAL PRIMARY KEY,
    mismatch_id BIGINT NOT NULL REFERENCES reconciliation_mismatches(mismatch_id) ON DELETE CASCADE,
    fix_applied_at TIMESTAMP NOT NULL DEFAULT NOW(),
    fix_action VARCHAR(100) NOT NULL,  -- 'AUTO_REVERSE', 'AUTO_REFUND', 'MANUAL_OVERRIDE'
    operator_id VARCHAR(255),  -- user ID if manual
    notes TEXT,
    created_at TIMESTAMP NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_fixes_mismatch_id ON reconciliation_fixes(mismatch_id);
