package main

import (
	"context"
	"database/sql"
	"log/slog"
	"os"
	"testing"
	"time"

	_ "github.com/lib/pq"
	"reconciliation-engine/pkg/reconciliation"
)

// TestShouldAutoFix tests the auto-fix eligibility logic
func TestShouldAutoFix(t *testing.T) {
	tests := []struct {
		ledger string
		psp    string
		want   bool
	}{
		{"1000", "1000", true},          // exact match
		{"1000", "1050", true},          // 5% difference
		{"1000", "1010", true},          // 1% difference
		{"1000", "2000", false},         // 100% difference (too large)
		{"1000", "1100", true},          // 10% difference is under 5% threshold
		{"0", "0", true},                // zero amounts
	}

	for i, tt := range tests {
		got := reconciliation.ShouldAutoFix(tt.ledger, tt.psp)
		if got != tt.want {
			t.Errorf("test %d: ShouldAutoFix(%s, %s) = %v, want %v", i, tt.ledger, tt.psp, got, tt.want)
		}
	}
}

// Integration tests with test database
type TestDB struct {
	db *sql.DB
}

func setupTestDB(t *testing.T) *TestDB {
	dsn := os.Getenv("TEST_DATABASE_URL")
	if dsn == "" {
		t.Skip("TEST_DATABASE_URL not set")
	}

	db, err := sql.Open("postgres", dsn)
	if err != nil {
		t.Fatalf("failed to open test database: %v", err)
	}

	if err := db.Ping(); err != nil {
		t.Fatalf("failed to ping test database: %v", err)
	}

	// Create schema if not exists (simplified for testing)
	ctx, cancel := context.WithTimeout(context.Background(), 10*time.Second)
	defer cancel()

	_, _ = db.ExecContext(ctx, `
		CREATE TABLE IF NOT EXISTS reconciliation_runs (
			run_id BIGSERIAL PRIMARY KEY,
			run_date DATE UNIQUE NOT NULL,
			scheduled_at TIMESTAMP DEFAULT NOW(),
			started_at TIMESTAMP,
			completed_at TIMESTAMP,
			mismatch_count INT DEFAULT 0,
			auto_fixed_count INT DEFAULT 0,
			manual_review_count INT DEFAULT 0,
			status VARCHAR(20) DEFAULT 'PENDING',
			created_at TIMESTAMP DEFAULT NOW()
		)
	`)

	_, _ = db.ExecContext(ctx, `
		CREATE TABLE IF NOT EXISTS reconciliation_mismatches (
			mismatch_id BIGSERIAL PRIMARY KEY,
			run_id BIGINT NOT NULL,
			transaction_id VARCHAR(255) NOT NULL,
			ledger_amount NUMERIC(19, 2),
			psp_amount NUMERIC(19, 2),
			discrepancy_amount NUMERIC(19, 2),
			discrepancy_reason VARCHAR(500),
			auto_fixed BOOLEAN DEFAULT FALSE,
			manual_review_required BOOLEAN DEFAULT FALSE,
			fix_applied_at TIMESTAMP,
			created_at TIMESTAMP DEFAULT NOW(),
			FOREIGN KEY (run_id) REFERENCES reconciliation_runs(run_id) ON DELETE CASCADE
		)
	`)

	_, _ = db.ExecContext(ctx, `
		CREATE TABLE IF NOT EXISTS reconciliation_fixes (
			fix_id BIGSERIAL PRIMARY KEY,
			mismatch_id BIGINT NOT NULL,
			fix_applied_at TIMESTAMP DEFAULT NOW(),
			fix_action VARCHAR(100),
			operator_id VARCHAR(255),
			notes TEXT,
			created_at TIMESTAMP DEFAULT NOW(),
			FOREIGN KEY (mismatch_id) REFERENCES reconciliation_mismatches(mismatch_id) ON DELETE CASCADE
		)
	`)

	return &TestDB{db: db}
}

func (tdb *TestDB) cleanup(t *testing.T) {
	ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
	defer cancel()

	_, _ = tdb.db.ExecContext(ctx, "DELETE FROM reconciliation_fixes")
	_, _ = tdb.db.ExecContext(ctx, "DELETE FROM reconciliation_mismatches")
	_, _ = tdb.db.ExecContext(ctx, "DELETE FROM reconciliation_runs")

	tdb.db.Close()
}

func TestGetReconciliationRuns(t *testing.T) {
	if testing.Short() {
		t.Skip("skipping integration test")
	}

	tdb := setupTestDB(t)
	defer tdb.cleanup(t)

	logger := slog.New(slog.NewTextHandler(os.Stdout, nil))
	pubsub := &reconciliation.EventPublisher{}
	reconciler := reconciliation.NewDBReconciler(tdb.db, logger, nil, pubsub)

	ctx, cancel := context.WithTimeout(context.Background(), 10*time.Second)
	defer cancel()

	// Insert test run
	_, _ = tdb.db.ExecContext(ctx, `
		INSERT INTO reconciliation_runs (run_date, status, mismatch_count)
		VALUES (CURRENT_DATE, 'COMPLETED', 5)
	`)

	runs, err := reconciler.GetReconciliationRuns(ctx, 10, 0)
	if err != nil {
		t.Fatalf("failed to get runs: %v", err)
	}

	if len(runs) == 0 {
		t.Fatalf("expected at least one run")
	}

	if runs[0].MismatchCount != 5 {
		t.Errorf("mismatch_count = %d, want 5", runs[0].MismatchCount)
	}
}
