package main

import (
	"context"
	"database/sql"
	"fmt"
)

func ensureReconciliationSchema(ctx context.Context, db *sql.DB) error {
	if db == nil {
		return nil
	}
	ddl := []string{
		`CREATE TABLE IF NOT EXISTS reconciliation_runs (
			run_id BIGSERIAL PRIMARY KEY,
			run_date DATE NOT NULL UNIQUE,
			scheduled_at TIMESTAMP NOT NULL DEFAULT NOW(),
			started_at TIMESTAMP,
			completed_at TIMESTAMP,
			mismatch_count INT DEFAULT 0,
			auto_fixed_count INT DEFAULT 0,
			manual_review_count INT DEFAULT 0,
			status VARCHAR(20) NOT NULL DEFAULT 'PENDING' CHECK (status IN ('PENDING', 'IN_PROGRESS', 'COMPLETED', 'PARTIAL_FAILED', 'FAILED')),
			created_at TIMESTAMP NOT NULL DEFAULT NOW()
		)`,
		`ALTER TABLE reconciliation_runs DROP CONSTRAINT IF EXISTS reconciliation_runs_status_check`,
		`ALTER TABLE reconciliation_runs ADD CONSTRAINT reconciliation_runs_status_check CHECK (status IN ('PENDING', 'IN_PROGRESS', 'COMPLETED', 'PARTIAL_FAILED', 'FAILED'))`,
		`CREATE INDEX IF NOT EXISTS idx_reconciliation_runs_date ON reconciliation_runs(run_date DESC)`,
		`CREATE INDEX IF NOT EXISTS idx_reconciliation_runs_status ON reconciliation_runs(status)`,
		`CREATE TABLE IF NOT EXISTS reconciliation_mismatches (
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
		)`,
		`CREATE INDEX IF NOT EXISTS idx_mismatches_run_id ON reconciliation_mismatches(run_id)`,
		`CREATE INDEX IF NOT EXISTS idx_mismatches_transaction_id ON reconciliation_mismatches(transaction_id)`,
		`CREATE INDEX IF NOT EXISTS idx_mismatches_manual_review ON reconciliation_mismatches(manual_review_required) WHERE manual_review_required = TRUE`,
		`CREATE TABLE IF NOT EXISTS reconciliation_fixes (
			fix_id BIGSERIAL PRIMARY KEY,
			mismatch_id BIGINT NOT NULL REFERENCES reconciliation_mismatches(mismatch_id) ON DELETE CASCADE,
			fix_applied_at TIMESTAMP NOT NULL DEFAULT NOW(),
			fix_action VARCHAR(100) NOT NULL,
			operator_id VARCHAR(255),
			notes TEXT,
			created_at TIMESTAMP NOT NULL DEFAULT NOW()
		)`,
		`CREATE INDEX IF NOT EXISTS idx_fixes_mismatch_id ON reconciliation_fixes(mismatch_id)`,
		`CREATE TABLE IF NOT EXISTS reconciliation_fix_registry (
			fix_id VARCHAR(255) PRIMARY KEY,
			mismatch_type VARCHAR(100) NOT NULL,
			transaction_id VARCHAR(255) NOT NULL,
			applied_at TIMESTAMP NOT NULL DEFAULT NOW(),
			created_at TIMESTAMP NOT NULL DEFAULT NOW()
		)`,
		`CREATE TABLE IF NOT EXISTS reconciliation_outbox (
			id BIGSERIAL PRIMARY KEY,
			event_id VARCHAR(255) NOT NULL UNIQUE,
			run_id VARCHAR(255) NOT NULL,
			event_type VARCHAR(50) NOT NULL,
			payload JSONB NOT NULL,
			published BOOLEAN DEFAULT FALSE,
			published_at TIMESTAMP,
			created_at TIMESTAMP NOT NULL DEFAULT NOW()
		)`,
		`CREATE TABLE IF NOT EXISTS payment_ledger (
			transaction_id VARCHAR(255) PRIMARY KEY,
			payment_id VARCHAR(255) NOT NULL,
			order_id VARCHAR(255),
			psp_reference VARCHAR(255),
			amount_cents BIGINT NOT NULL,
			captured_cents BIGINT NOT NULL DEFAULT 0,
			refunded_cents BIGINT NOT NULL DEFAULT 0,
			currency VARCHAR(3) NOT NULL DEFAULT 'INR',
			status VARCHAR(50) NOT NULL DEFAULT 'UNKNOWN',
			payment_method VARCHAR(50),
			source_lsn BIGINT NOT NULL DEFAULT 0,
			created_at TIMESTAMP NOT NULL DEFAULT NOW(),
			updated_at TIMESTAMP NOT NULL DEFAULT NOW()
		)`,
		`CREATE INDEX IF NOT EXISTS idx_payment_ledger_created_at ON payment_ledger(created_at DESC)`,
		`CREATE INDEX IF NOT EXISTS idx_payment_ledger_payment_id ON payment_ledger(payment_id)`,
		`CREATE INDEX IF NOT EXISTS idx_payment_ledger_order_id ON payment_ledger(order_id)`,
		`CREATE INDEX IF NOT EXISTS idx_payment_ledger_psp_reference ON payment_ledger(psp_reference)`,
		`CREATE TABLE IF NOT EXISTS psp_export_snapshot (
			id BIGSERIAL PRIMARY KEY,
			transaction_id VARCHAR(255) NOT NULL,
			export_date DATE NOT NULL,
			amount_cents BIGINT NOT NULL,
			currency VARCHAR(3) NOT NULL DEFAULT 'INR',
			status VARCHAR(50) NOT NULL DEFAULT 'SETTLED',
			psp_name VARCHAR(64),
			raw_payload JSONB,
			created_at TIMESTAMP NOT NULL DEFAULT NOW(),
			CONSTRAINT uq_psp_export_snapshot_txn_date UNIQUE (transaction_id, export_date)
		)`,
		`CREATE INDEX IF NOT EXISTS idx_psp_export_snapshot_export_date ON psp_export_snapshot(export_date)`,
	}
	for _, stmt := range ddl {
		if _, err := db.ExecContext(ctx, stmt); err != nil {
			return fmt.Errorf("ensure reconciliation schema failed: %w", err)
		}
	}
	return nil
}
