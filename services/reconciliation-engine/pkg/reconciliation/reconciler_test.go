package reconciliation

import (
	"context"
	"database/sql/driver"
	"io"
	"log/slog"
	"strings"
	"testing"
	"time"

	"github.com/stretchr/testify/assert"
	"github.com/stretchr/testify/require"
)

func TestBalancedLedgerNoMismatches(t *testing.T) {
	// Balanced ledger: all entries match PSP records exactly
	r := &DBReconciler{}

	ledger := map[string]string{
		"txn-001": "1000",
		"txn-002": "2500",
		"txn-003": "5000",
	}

	psp := map[string]string{
		"txn-001": "1000",
		"txn-002": "2500",
		"txn-003": "5000",
	}

	mismatches := r.detectMismatches(ledger, psp)
	assert.Empty(t, mismatches, "Balanced ledger should produce zero mismatches")
}

func TestMissingLedgerEntry(t *testing.T) {
	// Entry present in PSP export but missing from transactional payment ledger
	r := &DBReconciler{}

	ledger := map[string]string{
		"txn-001": "1000",
	}

	psp := map[string]string{
		"txn-001": "1000",
		"txn-002": "4500",
	}

	mismatches := r.detectMismatches(ledger, psp)
	assert.Len(t, mismatches, 1)

	m := mismatches[0]
	assert.Equal(t, "txn-002", m.TransactionID)
	assert.Equal(t, "4500", m.PSPAmount)
	assert.Equal(t, "missing_ledger_entry", m.Reason)
	assert.False(t, m.AutoFixed, "Missing ledger entry must never be marked auto-fixed")
	assert.True(t, m.ManualReview, "Missing ledger entry must require manual review")
}

func TestMissingPSPEntry(t *testing.T) {
	// Entry present in payment ledger but missing from PSP settlement snapshot
	r := &DBReconciler{}

	ledger := map[string]string{
		"txn-001": "1000",
		"txn-unsettled": "7500",
	}

	psp := map[string]string{
		"txn-001": "1000",
	}

	mismatches := r.detectMismatches(ledger, psp)
	assert.Len(t, mismatches, 1)

	m := mismatches[0]
	assert.Equal(t, "txn-unsettled", m.TransactionID)
	assert.Equal(t, "7500", m.LedgerAmount)
	assert.Equal(t, "missing_psp_export", m.Reason)
	assert.False(t, m.AutoFixed, "Missing PSP entry requires manual review")
}

func TestAmountMismatchAutoFixableSmallDifference(t *testing.T) {
	// Amount difference <= 5% (e.g. minor fee or rounding timing)
	r := &DBReconciler{}

	ledger := map[string]string{
		"txn-001": "1000",
	}

	// 1020 is within 5% of 1000 (diff = 20 <= 50)
	psp := map[string]string{
		"txn-001": "1020",
	}

	mismatches := r.detectMismatches(ledger, psp)
	assert.Len(t, mismatches, 1)

	m := mismatches[0]
	assert.Equal(t, "txn-001", m.TransactionID)
	assert.Equal(t, "amount_mismatch_auto_fixable", m.Reason)
	assert.True(t, m.AutoFixed)
}

func TestAmountMismatchManualReviewLargeDiscrepancy(t *testing.T) {
	// Amount difference > 5% represents a material financial discrepancy
	r := &DBReconciler{}

	ledger := map[string]string{
		"txn-001": "1000",
	}

	// 1200 is 20% difference (diff = 200 > 50)
	psp := map[string]string{
		"txn-001": "1200",
	}

	mismatches := r.detectMismatches(ledger, psp)
	assert.Len(t, mismatches, 1)

	m := mismatches[0]
	assert.Equal(t, "txn-001", m.TransactionID)
	assert.Equal(t, "amount_mismatch_manual_review", m.Reason)
	assert.False(t, m.AutoFixed)
}

func TestShouldAutoFix(t *testing.T) {
	// Exact matches
	assert.True(t, ShouldAutoFix("1000", "1000"))
	assert.True(t, ShouldAutoFix("0", "0"))

	// Small discrepancies within 5% threshold
	assert.True(t, ShouldAutoFix("1000", "1040")) // 4% diff
	assert.True(t, ShouldAutoFix("1000", "960"))  // 4% diff
	assert.True(t, ShouldAutoFix("1000", "1050")) // exactly 5% diff

	// Large discrepancies exceeding 5% threshold
	assert.False(t, ShouldAutoFix("1000", "1060")) // 6% diff
	assert.False(t, ShouldAutoFix("1000", "900"))  // 10% diff
	assert.False(t, ShouldAutoFix("1000", "2000")) // 100% diff

	// Explicit 100-cent absolute cap even when percentage <= 5%
	assert.True(t, ShouldAutoFix("100000", "100100"))
	assert.False(t, ShouldAutoFix("100000", "100101"))
	assert.False(t, ShouldAutoFix("100000", "101000"))
	assert.False(t, ShouldAutoFix("500000", "500500"))

	// Invalid, empty, or one-sided zero inputs must fail closed
	assert.False(t, ShouldAutoFix("", "100"))
	assert.False(t, ShouldAutoFix("100", ""))
	assert.False(t, ShouldAutoFix("", ""))
	assert.False(t, ShouldAutoFix("invalid", "100"))
	assert.False(t, ShouldAutoFix("100", "invalid"))
	assert.False(t, ShouldAutoFix("0", "50"))
	assert.False(t, ShouldAutoFix("50", "0"))
}

func TestReconciliationResultCalculations(t *testing.T) {
	res := ReconciliationResult{
		RunID:             42,
		MismatchCount:     10,
		AutoFixedCount:    7,
		ManualReviewCount: 3,
		Duration:          500 * time.Millisecond,
	}

	assert.Equal(t, int64(42), res.RunID)
	assert.Equal(t, 10, res.MismatchCount)
	assert.Equal(t, res.MismatchCount, res.AutoFixedCount+res.ManualReviewCount)
	assert.Equal(t, 500*time.Millisecond, res.Duration)
}

func TestCalculateDiscrepancyCents(t *testing.T) {
	assert.Equal(t, int64(0), calculateDiscrepancyCents("1000", "1000"))
	assert.Equal(t, int64(50), calculateDiscrepancyCents("1050", "1000"))
	assert.Equal(t, int64(50), calculateDiscrepancyCents("1000", "1050"))
	assert.Equal(t, int64(1000), calculateDiscrepancyCents("1000", "0.00"))
	assert.Equal(t, int64(2500), calculateDiscrepancyCents("0.00", "2500"))
	assert.Equal(t, int64(1000), calculateDiscrepancyCents("1000", ""))
}

func TestProcessMismatch_OmitsDiscrepancyAmount(t *testing.T) {
	db, state := newStubDB(nil, nil)
	defer db.Close()

	r := NewDBReconciler(db, slog.New(slog.NewTextHandler(io.Discard, nil)), nil, nil)
	ctx := context.Background()

	tx, err := db.BeginTx(ctx, nil)
	require.NoError(t, err)
	defer func() { _ = tx.Rollback() }()

	err = r.processMismatch(ctx, tx, 42, MismatchDetail{
		TransactionID: "txn-002",
		LedgerAmount:  "",
		PSPAmount:     "4500",
		Reason:        "missing_ledger_entry",
		AutoFixed:     false,
		ManualReview:  true,
	})
	require.NoError(t, err)

	var insertStmt *recordedStatement
	for i := range state.statements {
		if strings.Contains(state.statements[i].Query, "INSERT INTO reconciliation_mismatches") {
			insertStmt = &state.statements[i]
			break
		}
	}
	require.NotNil(t, insertStmt, "expected INSERT INTO reconciliation_mismatches to be executed")
	assert.NotContains(t, strings.ToLower(insertStmt.Query), "discrepancy_amount")
	require.Len(t, insertStmt.Args, 7)
	assert.Equal(t, int64(42), insertStmt.Args[0].Value)
	assert.Equal(t, "txn-002", insertStmt.Args[1].Value)
	assert.Equal(t, "0.00", insertStmt.Args[2].Value)
	assert.Equal(t, "4500", insertStmt.Args[3].Value)
	assert.Equal(t, "missing_ledger_entry", insertStmt.Args[4].Value)
	assert.Equal(t, false, insertStmt.Args[5].Value)
	assert.Equal(t, true, insertStmt.Args[6].Value)
}

func TestReconcileDailyRun_EnforcesManualReviewAndGeneratedColumn(t *testing.T) {
	queryFn := func(_ int64, query string, _ []driver.NamedValue) (driver.Rows, error) {
		switch {
		case strings.Contains(query, "SELECT run_id FROM reconciliation_runs"):
			return &stubRows{columns: []string{"run_id"}}, nil
		case strings.Contains(query, "INSERT INTO reconciliation_runs"):
			return &stubRows{
				columns: []string{"run_id"},
				data:    [][]driver.Value{{int64(77)}},
			}, nil
		case strings.Contains(query, "FROM payment_ledger"):
			return &stubRows{
				columns: []string{"transaction_id", "amount_cents"},
				data: [][]driver.Value{
					{"txn-match", int64(1000)},
					{"txn-small-diff", int64(100000)},
					{"txn-cap-exceeded", int64(100000)},
					{"txn-missing-psp", int64(5000)},
				},
			}, nil
		case strings.Contains(query, "FROM psp_export_snapshot"):
			return &stubRows{
				columns: []string{"transaction_id", "amount_cents"},
				data: [][]driver.Value{
					{"txn-match", int64(1000)},
					{"txn-small-diff", int64(100100)},
					{"txn-cap-exceeded", int64(100500)},
					{"txn-missing-ledger", int64(4500)},
				},
			}, nil
		default:
			return &stubRows{}, nil
		}
	}

	db, state := newStubDB(queryFn, nil)
	defer db.Close()

	r := NewDBReconciler(db, slog.New(slog.NewTextHandler(io.Discard, nil)), nil, nil)
	res, err := r.ReconcileDailyRun(context.Background(), time.Date(2026, 11, 13, 0, 0, 0, 0, time.UTC))
	require.NoError(t, err)
	assert.Equal(t, int64(77), res.RunID)
	assert.Equal(t, 4, res.MismatchCount)
	assert.Equal(t, 1, res.AutoFixedCount)
	assert.Equal(t, 3, res.ManualReviewCount)

	for _, stmt := range state.recorded() {
		if strings.Contains(stmt.Query, "INSERT INTO reconciliation_mismatches") {
			assert.NotContains(t, strings.ToLower(stmt.Query), "discrepancy_amount")
		}
	}
}
