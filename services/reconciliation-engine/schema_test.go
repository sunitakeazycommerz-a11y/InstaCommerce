package main

import (
	"context"
	"database/sql"
	"testing"
	"time"

	_ "github.com/lib/pq"
)

// TestReconciliationSchema verifies the database schema is correctly created.
// This test requires a PostgreSQL database to be running.
// Set DATABASE_URL env var or use defaults: postgres://user:password@localhost/test
func TestReconciliationSchema(t *testing.T) {
	if testing.Short() {
		t.Skip("Skipping integration test in short mode")
	}

	db, err := openTestDB()
	if err != nil {
		t.Fatalf("failed to connect to test database: %v", err)
	}
	defer db.Close()

	// Clean up and recreate schema for test
	if err := dropAndCreateSchema(db); err != nil {
		t.Fatalf("failed to setup test schema: %v", err)
	}

	t.Run("reconciliation_runs table exists", func(t *testing.T) {
		testTableExists(t, db, "reconciliation_runs")
	})

	t.Run("reconciliation_mismatches table exists", func(t *testing.T) {
		testTableExists(t, db, "reconciliation_mismatches")
	})

	t.Run("reconciliation_fixes table exists", func(t *testing.T) {
		testTableExists(t, db, "reconciliation_fixes")
	})

	t.Run("reconciliation_fix_registry table exists", func(t *testing.T) {
		testTableExists(t, db, "reconciliation_fix_registry")
	})

	t.Run("reconciliation_outbox table exists", func(t *testing.T) {
		testTableExists(t, db, "reconciliation_outbox")
	})

	t.Run("indexes created", func(t *testing.T) {
		testIndexExists(t, db, "idx_reconciliation_runs_date")
		testIndexExists(t, db, "idx_reconciliation_runs_status")
		testIndexExists(t, db, "idx_mismatches_run_id")
		testIndexExists(t, db, "idx_mismatches_transaction_id")
		testIndexExists(t, db, "idx_mismatches_manual_review")
		testIndexExists(t, db, "idx_fixes_mismatch_id")
		testIndexExists(t, db, "idx_fix_registry_transaction_id")
		testIndexExists(t, db, "idx_fix_registry_applied_at")
		testIndexExists(t, db, "idx_outbox_published")
		testIndexExists(t, db, "idx_outbox_run_id")
		testIndexExists(t, db, "idx_outbox_created_at")
	})

	t.Run("insert into reconciliation_runs", func(t *testing.T) {
		var runID int64
		var runDate, createdAt string
		var status string

		err := db.QueryRow(`
			INSERT INTO reconciliation_runs (run_date, scheduled_at, status)
			VALUES ($1, NOW(), 'PENDING')
			RETURNING run_id, run_date, status, created_at
		`, "2026-03-21").Scan(&runID, &runDate, &status, &createdAt)

		if err != nil {
			t.Fatalf("failed to insert into reconciliation_runs: %v", err)
		}

		if runID == 0 {
			t.Fatal("expected run_id to be generated")
		}
		if status != "PENDING" {
			t.Fatalf("expected status PENDING, got %s", status)
		}
	})

	t.Run("insert into reconciliation_runs with CASCADE delete", func(t *testing.T) {
		var runID int64
		err := db.QueryRow(`
			INSERT INTO reconciliation_runs (run_date, scheduled_at, status)
			VALUES ($1, NOW(), 'COMPLETED')
			RETURNING run_id
		`, "2026-03-22").Scan(&runID)
		if err != nil {
			t.Fatalf("failed to insert reconciliation_runs: %v", err)
		}

		// Insert mismatch
		var mismatchID int64
		err = db.QueryRow(`
			INSERT INTO reconciliation_mismatches
			(run_id, transaction_id, ledger_amount, psp_amount, manual_review_required)
			VALUES ($1, 'txn-001', 100.50, 100.50, false)
			RETURNING mismatch_id
		`, runID).Scan(&mismatchID)
		if err != nil {
			t.Fatalf("failed to insert mismatch: %v", err)
		}

		// Insert fix
		var fixID int64
		err = db.QueryRow(`
			INSERT INTO reconciliation_fixes
			(mismatch_id, fix_action, operator_id, notes)
			VALUES ($1, 'AUTO_REVERSE', 'system', 'test fix')
			RETURNING fix_id
		`, mismatchID).Scan(&fixID)
		if err != nil {
			t.Fatalf("failed to insert fix: %v", err)
		}

		// Delete run should cascade delete mismatches and fixes
		result, err := db.Exec(`DELETE FROM reconciliation_runs WHERE run_id = $1`, runID)
		if err != nil {
			t.Fatalf("failed to delete run: %v", err)
		}

		affected, err := result.RowsAffected()
		if err != nil {
			t.Fatalf("failed to get rows affected: %v", err)
		}
		if affected != 1 {
			t.Fatalf("expected 1 row affected, got %d", affected)
		}

		// Verify mismatch was deleted
		var count int
		err = db.QueryRow(`SELECT COUNT(*) FROM reconciliation_mismatches WHERE mismatch_id = $1`, mismatchID).Scan(&count)
		if err != nil {
			t.Fatalf("failed to count mismatches: %v", err)
		}
		if count != 0 {
			t.Fatal("expected mismatch to be deleted via CASCADE")
		}

		// Verify fix was deleted
		err = db.QueryRow(`SELECT COUNT(*) FROM reconciliation_fixes WHERE fix_id = $1`, fixID).Scan(&count)
		if err != nil {
			t.Fatalf("failed to count fixes: %v", err)
		}
		if count != 0 {
			t.Fatal("expected fix to be deleted via CASCADE")
		}
	})

	t.Run("CHECK constraint on status", func(t *testing.T) {
		// This should fail with CHECK constraint violation
		_, err := db.Exec(`
			INSERT INTO reconciliation_runs (run_date, scheduled_at, status)
			VALUES ('2026-03-23', NOW(), 'INVALID_STATUS')
		`)
		if err == nil {
			t.Fatal("expected CHECK constraint violation for invalid status")
		}
	})

	t.Run("UNIQUE constraint on run_date", func(t *testing.T) {
		// Insert first run
		_, err := db.Exec(`
			INSERT INTO reconciliation_runs (run_date, scheduled_at, status)
			VALUES ('2026-03-24', NOW(), 'PENDING')
		`)
		if err != nil {
			t.Fatalf("failed to insert first run: %v", err)
		}

		// Attempt to insert duplicate run_date
		_, err = db.Exec(`
			INSERT INTO reconciliation_runs (run_date, scheduled_at, status)
			VALUES ('2026-03-24', NOW(), 'PENDING')
		`)
		if err == nil {
			t.Fatal("expected UNIQUE constraint violation for duplicate run_date")
		}
	})

	t.Run("GENERATED ALWAYS AS stored column", func(t *testing.T) {
		var runID int64
		err := db.QueryRow(`
			INSERT INTO reconciliation_runs (run_date, scheduled_at, status)
			VALUES ($1, NOW(), 'COMPLETED')
			RETURNING run_id
		`, "2026-03-25").Scan(&runID)
		if err != nil {
			t.Fatalf("failed to insert reconciliation_runs: %v", err)
		}

		var discrepancy float64
		err = db.QueryRow(`
			INSERT INTO reconciliation_mismatches
			(run_id, transaction_id, ledger_amount, psp_amount)
			VALUES ($1, 'txn-002', 150.75, 100.50)
			RETURNING discrepancy_amount
		`, runID).Scan(&discrepancy)
		if err != nil {
			t.Fatalf("failed to insert mismatch: %v", err)
		}

		expectedDiscrepancy := 50.25
		if discrepancy != expectedDiscrepancy {
			t.Fatalf("expected discrepancy %f, got %f", expectedDiscrepancy, discrepancy)
		}
	})

	t.Run("outbox event publishing", func(t *testing.T) {
		var outboxID int64
		var eventID, runID string
		var eventType string
		var payload string
		var published bool

		err := db.QueryRow(`
			INSERT INTO reconciliation_outbox (event_id, run_id, event_type, payload, published)
			VALUES ($1, $2, $3, $4, false)
			RETURNING id, event_id, run_id, event_type, payload, published
		`, "evt-12345", "run-67890", "mismatch", `{"transaction_id":"txn-001"}`).
			Scan(&outboxID, &eventID, &runID, &eventType, &payload, &published)

		if err != nil {
			t.Fatalf("failed to insert outbox event: %v", err)
		}

		if outboxID == 0 {
			t.Fatal("expected outbox id to be generated")
		}
		if eventID != "evt-12345" {
			t.Fatalf("expected event_id evt-12345, got %s", eventID)
		}
		if !published {
			t.Fatal("expected published to be false")
		}
	})

	t.Run("fix registry idempotency", func(t *testing.T) {
		fixID := "missing_ledger_entry:txn-003"
		mismatchType := "missing_ledger_entry"
		txnID := "txn-003"

		// Insert first fix
		_, err := db.Exec(`
			INSERT INTO reconciliation_fix_registry (fix_id, mismatch_type, transaction_id)
			VALUES ($1, $2, $3)
		`, fixID, mismatchType, txnID)
		if err != nil {
			t.Fatalf("failed to insert fix registry: %v", err)
		}

		// Attempt to insert duplicate (should fail or be ignored depending on impl)
		_, err = db.Exec(`
			INSERT INTO reconciliation_fix_registry (fix_id, mismatch_type, transaction_id)
			VALUES ($1, $2, $3)
		`, fixID, mismatchType, txnID)
		if err == nil {
			t.Fatal("expected PRIMARY KEY constraint violation for duplicate fix_id")
		}

		// Verify fix can be queried
		var retrievedFixID string
		err = db.QueryRow(`
			SELECT fix_id FROM reconciliation_fix_registry WHERE fix_id = $1
		`, fixID).Scan(&retrievedFixID)
		if err != nil {
			t.Fatalf("failed to query fix registry: %v", err)
		}
		if retrievedFixID != fixID {
			t.Fatalf("expected fix_id %s, got %s", fixID, retrievedFixID)
		}
	})
}

// openTestDB opens a connection to the test PostgreSQL database
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

// dropAndCreateSchema drops and recreates the reconciliation schema
func dropAndCreateSchema(db *sql.DB) error {
	ctx, cancel := context.WithTimeout(context.Background(), 10*time.Second)
	defer cancel()

	// Drop tables in reverse dependency order
	tables := []string{
		"reconciliation_fixes",
		"reconciliation_fix_registry",
		"reconciliation_mismatches",
		"reconciliation_outbox",
		"reconciliation_runs",
	}

	for _, table := range tables {
		if _, err := db.ExecContext(ctx, "DROP TABLE IF EXISTS "+table+" CASCADE"); err != nil {
			return err
		}
	}

	// Recreate schema from migrations (simplified version for testing)
	schemaSQL := `
	-- reconciliation_runs
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

	-- reconciliation_mismatches
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

	-- reconciliation_fixes
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

	-- reconciliation_fix_registry
	CREATE TABLE reconciliation_fix_registry (
		fix_id VARCHAR(255) PRIMARY KEY,
		mismatch_type VARCHAR(100) NOT NULL,
		transaction_id VARCHAR(255) NOT NULL,
		applied_at TIMESTAMP NOT NULL DEFAULT NOW(),
		created_at TIMESTAMP NOT NULL DEFAULT NOW()
	);

	CREATE INDEX idx_fix_registry_transaction_id ON reconciliation_fix_registry(transaction_id);
	CREATE INDEX idx_fix_registry_applied_at ON reconciliation_fix_registry(applied_at DESC);

	-- reconciliation_outbox
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
	`

	if _, err := db.ExecContext(ctx, schemaSQL); err != nil {
		return err
	}

	return nil
}

// testTableExists verifies a table exists in the database
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

// testIndexExists verifies an index exists in the database
func testIndexExists(t *testing.T, db *sql.DB, indexName string) {
	var count int
	err := db.QueryRow(`
		SELECT COUNT(*) FROM information_schema.statistics
		WHERE index_name = $1 AND table_schema = 'public'
	`, indexName).Scan(&count)
	if err != nil {
		// PostgreSQL uses pg_indexes instead of information_schema.statistics
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
