package reconciliation

import (
	"context"
	"database/sql/driver"
	"errors"
	"io"
	"log/slog"
	"strings"
	"testing"
	"time"

	"github.com/stretchr/testify/assert"
	"github.com/stretchr/testify/require"
)

func TestRoundingToleranceAndDeterministicSorting(t *testing.T) {
	r := &DBReconciler{}

	ledger := map[string]string{
		"txn-rounding": "1000",
		"txn-large":    "10000",
		"txn-missing":  "2500",
		"txn-critical": "50000",
	}
	psp := map[string]string{
		"txn-rounding": "1002",
		"txn-large":    "15000",
		"txn-critical": "20000",
	}

	mismatches := r.detectMismatches(ledger, psp)
	require.Len(t, mismatches, 4)

	assert.Equal(t, "txn-critical", mismatches[0].TransactionID)
	assert.Equal(t, SeverityCritical, mismatches[0].Severity)

	assert.Equal(t, "txn-large", mismatches[1].TransactionID)
	assert.Equal(t, SeverityHigh, mismatches[1].Severity)

	assert.Equal(t, "txn-missing", mismatches[2].TransactionID)
	assert.Equal(t, SeverityHigh, mismatches[2].Severity)

	assert.Equal(t, "txn-rounding", mismatches[3].TransactionID)
	assert.Equal(t, "fee_tax_rounding_within_tolerance", mismatches[3].Reason)
	assert.Equal(t, SeverityLow, mismatches[3].Severity)
	assert.True(t, mismatches[3].AutoFixed)
}

func TestClassifySeverityAndAging(t *testing.T) {
	tests := []struct {
		name         string
		diffCents    int64
		reason       string
		age          time.Duration
		wantSeverity string
		wantBucket   string
	}{
		{"fresh minor rounding", 1, "fee_tax_rounding_within_tolerance", 30 * time.Minute, SeverityLow, AgeBucketFresh},
		{"aging minor diff over 24h escalates", 2, "fee_tax_rounding_within_tolerance", 26 * time.Hour, SeverityHigh, AgeBucketAging24H},
		{"stale over 48h becomes critical", 50, "amount_mismatch_manual_review", 50 * time.Hour, SeverityCritical, AgeBucketStale48H},
		{"over 72h age bucket", 50, "currency_mismatch", 80 * time.Hour, SeverityCritical, AgeBucketAged72HPlus},
	}

	for _, tc := range tests {
		t.Run(tc.name, func(t *testing.T) {
			assert.Equal(t, tc.wantSeverity, ClassifySeverity(tc.diffCents, tc.reason, tc.age))
			assert.Equal(t, tc.wantBucket, ClassifyAgeBucket(tc.age))
		})
	}
}

func TestApplyFix_IdempotentWhenAlreadyFixed(t *testing.T) {
	queryFn := func(_ int64, query string, _ []driver.NamedValue) (driver.Rows, error) {
		if strings.Contains(query, "FROM reconciliation_mismatches") {
			return &stubRows{columns: []string{"run_id", "transaction_id", "psp_amount"}}, nil
		}
		if strings.Contains(query, "FROM reconciliation_fixes") {
			return &stubRows{columns: []string{"one"}, data: [][]driver.Value{{int64(1)}}}, nil
		}
		return &stubRows{}, nil
	}

	db, state := newStubDB(queryFn, nil)
	defer db.Close()

	r := NewDBReconciler(db, slog.New(slog.NewTextHandler(io.Discard, nil)), nil, nil)
	require.NoError(t, r.ApplyFix(context.Background(), 404, "adjust_ledger", nil, nil))

	for _, stmt := range state.recorded() {
		assert.NotContains(t, stmt.Query, "INSERT INTO payment_ledger")
		assert.NotContains(t, stmt.Query, "INSERT INTO reconciliation_fixes")
	}
}

func TestWithinRoundingTolerance_DecimalVsCentsAndSigns(t *testing.T) {
	assert.False(t, WithinRoundingTolerance("1002.00", "1000", 2))
	assert.False(t, WithinRoundingTolerance("10.00", "12.00", 2))
	assert.True(t, WithinRoundingTolerance("10.00", "10.02", 2))
	assert.True(t, WithinRoundingTolerance("1000", "10.02", 2))
	assert.False(t, WithinRoundingTolerance("-1", "1", 2))
	assert.True(t, WithinRoundingTolerance("-1000", "-1002", 2))

	assert.False(t, ShouldAutoFix("1000.00", "1002.00"))
	assert.True(t, ShouldAutoFix("1000.00", "1000.50"))
	assert.False(t, ShouldAutoFix("-1", "1"))
}

func TestApplyFix_LocksRowAndGuardsFixAppliedAt(t *testing.T) {
	t.Run("locks row with FOR UPDATE and guards update", func(t *testing.T) {
		queryFn := func(_ int64, query string, _ []driver.NamedValue) (driver.Rows, error) {
			if strings.Contains(query, "FROM reconciliation_mismatches") {
				assert.Contains(t, query, "FOR UPDATE")
				return &stubRows{
					columns: []string{"run_id", "transaction_id", "psp_amount"},
					data:    [][]driver.Value{{int64(11), "txn-lock-1", "10.50"}},
				}, nil
			}
			return &stubRows{}, nil
		}
		db, state := newStubDB(queryFn, nil)
		defer db.Close()

		r := NewDBReconciler(db, slog.New(slog.NewTextHandler(io.Discard, nil)), nil, nil)
		require.NoError(t, r.ApplyFix(context.Background(), 501, "adjust_ledger", nil, nil))

		var sawGuardedUpdate bool
		for _, stmt := range state.recorded() {
			if strings.Contains(stmt.Query, "UPDATE reconciliation_mismatches") {
				assert.Contains(t, stmt.Query, "fix_applied_at IS NULL")
				sawGuardedUpdate = true
			}
		}
		assert.True(t, sawGuardedUpdate)
	})

	t.Run("returns idempotently when mismatch already has fix_applied_at", func(t *testing.T) {
		queryFn := func(_ int64, query string, _ []driver.NamedValue) (driver.Rows, error) {
			if strings.Contains(query, "fix_applied_at IS NOT NULL") {
				return &stubRows{columns: []string{"one"}, data: [][]driver.Value{{int64(1)}}}, nil
			}
			if strings.Contains(query, "FROM reconciliation_mismatches") {
				return &stubRows{columns: []string{"run_id", "transaction_id", "psp_amount"}}, nil
			}
			if strings.Contains(query, "FROM reconciliation_fixes") {
				return &stubRows{columns: []string{"one"}}, nil
			}
			return &stubRows{}, nil
		}
		db, state := newStubDB(queryFn, nil)
		defer db.Close()

		r := NewDBReconciler(db, slog.New(slog.NewTextHandler(io.Discard, nil)), nil, nil)
		require.NoError(t, r.ApplyFix(context.Background(), 502, "adjust_ledger", nil, nil))
		for _, stmt := range state.recorded() {
			assert.NotContains(t, stmt.Query, "INSERT INTO payment_ledger")
		}
	})

	t.Run("fails when guarded update affects zero rows", func(t *testing.T) {
		queryFn := func(_ int64, query string, _ []driver.NamedValue) (driver.Rows, error) {
			if strings.Contains(query, "FROM reconciliation_mismatches") {
				return &stubRows{
					columns: []string{"run_id", "transaction_id", "psp_amount"},
					data:    [][]driver.Value{{int64(11), "txn-race-1", "10.50"}},
				}, nil
			}
			return &stubRows{}, nil
		}
		execFn := func(_ int64, query string, _ []driver.NamedValue) (driver.Result, error) {
			if strings.Contains(query, "UPDATE reconciliation_mismatches") {
				return driver.RowsAffected(0), nil
			}
			return driver.RowsAffected(1), nil
		}
		db, _ := newStubDB(queryFn, execFn)
		defer db.Close()

		r := NewDBReconciler(db, slog.New(slog.NewTextHandler(io.Discard, nil)), nil, nil)
		err := r.ApplyFix(context.Background(), 503, "adjust_ledger", nil, nil)
		require.Error(t, err)
		assert.Contains(t, err.Error(), "no unfixed row matched")
	})
}

func TestReconcileDailyRun_MarksPartialFailedOnMismatchProcessError(t *testing.T) {
	insertErr := errors.New("insert mismatch failure")
	queryFn := func(_ int64, query string, _ []driver.NamedValue) (driver.Rows, error) {
		switch {
		case strings.Contains(query, "SELECT run_id FROM reconciliation_runs"):
			return &stubRows{columns: []string{"run_id"}}, nil
		case strings.Contains(query, "INSERT INTO reconciliation_runs"):
			return &stubRows{columns: []string{"run_id"}, data: [][]driver.Value{{int64(88)}}}, nil
		case strings.Contains(query, "FROM payment_ledger"):
			return &stubRows{
				columns: []string{"transaction_id", "amount_cents"},
				data: [][]driver.Value{
					{"txn-ok", int64(1000)},
					{"txn-fail", int64(2000)},
				},
			}, nil
		case strings.Contains(query, "FROM psp_export_snapshot"):
			return &stubRows{
				columns: []string{"transaction_id", "amount_cents"},
				data: [][]driver.Value{
					{"txn-ok", int64(1020)},
					{"txn-fail", int64(2500)},
				},
			}, nil
		default:
			return &stubRows{}, nil
		}
	}
	execFn := func(_ int64, query string, args []driver.NamedValue) (driver.Result, error) {
		if strings.Contains(query, "INSERT INTO reconciliation_mismatches") && len(args) > 1 && args[1].Value == "txn-fail" {
			return nil, insertErr
		}
		return driver.RowsAffected(1), nil
	}

	db, state := newStubDB(queryFn, execFn)
	defer db.Close()

	r := NewDBReconciler(db, slog.New(slog.NewTextHandler(io.Discard, nil)), nil, nil)
	res, err := r.ReconcileDailyRun(context.Background(), time.Date(2026, 11, 13, 0, 0, 0, 0, time.UTC))
	require.Error(t, err)
	assert.ErrorIs(t, err, insertErr)
	require.NotNil(t, res)
	assert.Equal(t, 2, res.MismatchCount)
	assert.Equal(t, 1, res.AutoFixedCount)

	var statuses []string
	for _, stmt := range state.recorded() {
		if strings.Contains(stmt.Query, "UPDATE reconciliation_runs SET status = $1") && len(stmt.Args) >= 1 {
			if status, ok := stmt.Args[0].Value.(string); ok {
				statuses = append(statuses, status)
			}
		}
	}
	require.Contains(t, statuses, "PARTIAL_FAILED")
	assert.NotContains(t, statuses, "COMPLETED")
	assert.Equal(t, 1, state.Commits)
}
