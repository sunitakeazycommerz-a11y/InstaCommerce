package main

import (
	"context"
	"database/sql"
	"testing"
	"time"

	_ "github.com/lib/pq"
)

func openTestDB() (*sql.DB, error) {
	dsn := "postgres://postgres:postgres@localhost:5432/instacommerce_test?sslmode=disable"
	db, err := sql.Open("postgres", dsn)
	if err != nil {
		return nil, err
	}

	ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
	defer cancel()

	if err := db.PingContext(ctx); err != nil {
		return nil, err
	}

	return db, nil
}

func dropAndCreateSchema(db *sql.DB) error {
	ctx, cancel := context.WithTimeout(context.Background(), 10*time.Second)
	defer cancel()

	tables := []string{
		"reconciliation_fixes",
		"reconciliation_fix_registry",
		"reconciliation_mismatches",
		"reconciliation_outbox",
		"payment_ledger",
		"psp_export_snapshot",
		"reconciliation_runs",
	}

	for _, table := range tables {
		if _, err := db.ExecContext(ctx, "DROP TABLE IF EXISTS "+table+" CASCADE"); err != nil {
			return err
		}
	}

	schemaSQL := `
	CREATE TABLE reconciliation_runs (
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
	);

	CREATE INDEX idx_reconciliation_runs_date ON reconciliation_runs(run_date DESC);
	CREATE INDEX idx_reconciliation_runs_status ON reconciliation_runs(status);

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

	CREATE TABLE reconciliation_fixes (
		fix_id BIGSERIAL PRIMARY KEY,
		mismatch_id BIGINT NOT NULL REFERENCES reconciliation_mismatches(mismatch_id) ON DELETE CASCADE,
		fix_applied_at TIMESTAMP NOT NULL DEFAULT NOW(),
		fix_action VARCHAR(100) NOT NULL,
		operator_id VARCHAR(255),
		notes TEXT,
		created_at TIMESTAMP NOT NULL DEFAULT NOW()
	);

	CREATE INDEX idx_fixes_mismatch_id ON reconciliation_fixes(mismatch_id);

	CREATE TABLE reconciliation_fix_registry (
		fix_id VARCHAR(255) PRIMARY KEY,
		mismatch_type VARCHAR(100) NOT NULL,
		transaction_id VARCHAR(255) NOT NULL,
		applied_at TIMESTAMP NOT NULL DEFAULT NOW(),
		created_at TIMESTAMP NOT NULL DEFAULT NOW()
	);

	CREATE INDEX idx_fix_registry_transaction_id ON reconciliation_fix_registry(transaction_id);
	CREATE INDEX idx_fix_registry_applied_at ON reconciliation_fix_registry(applied_at DESC);

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

	CREATE TABLE payment_ledger (
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
	);

	CREATE INDEX idx_payment_ledger_created_at ON payment_ledger(created_at DESC);
	CREATE INDEX idx_payment_ledger_payment_id ON payment_ledger(payment_id);
	CREATE INDEX idx_payment_ledger_order_id ON payment_ledger(order_id);
	CREATE INDEX idx_payment_ledger_psp_reference ON payment_ledger(psp_reference);

	CREATE TABLE psp_export_snapshot (
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
	);

	CREATE INDEX idx_psp_export_snapshot_export_date ON psp_export_snapshot(export_date);
	`

	if _, err := db.ExecContext(ctx, schemaSQL); err != nil {
		return err
	}

	return nil
}

func testTableExists(t *testing.T, db *sql.DB, tableName string) {
	var count int
	err := db.QueryRow(`
		SELECT COUNT(*) FROM information_schema.tables
		WHERE table_name = $1 AND table_schema = 'public'
	`, tableName).Scan(&count)
	if err != nil {
		t.Fatalf("failed to check if table %s exists: %v", tableName, err)
	}
	if count != 1 {
		t.Fatalf("table %s does not exist", tableName)
	}
}

func testIndexExists(t *testing.T, db *sql.DB, indexName string) {
	var count int
	err := db.QueryRow(`
		SELECT COUNT(*) FROM information_schema.statistics
		WHERE index_name = $1 AND table_schema = 'public'
	`, indexName).Scan(&count)
	if err != nil {
		err = db.QueryRow(`
			SELECT COUNT(*) FROM pg_indexes
			WHERE indexname = $1 AND schemaname = 'public'
		`, indexName).Scan(&count)
		if err != nil {
			t.Fatalf("failed to check if index %s exists: %v", indexName, err)
		}
	}
	if count == 0 {
		t.Fatalf("index %s does not exist", indexName)
	}
}
