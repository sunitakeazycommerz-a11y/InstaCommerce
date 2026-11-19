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

func TestRoundingToleranceAndDeterministicSorting(t *testing.T) {
	r := &DBReconciler{}

	ledger := map[string]string{
		"txn-rounding": "1000",
		"txn-large":    "10000",
		"txn-missing":  "2500",
		"txn-critical": "50000",
	}
	psp := map[string]string{
		"txn-rounding": "1002", // +2 minor units within fee/tax rounding window
		"txn-large":    "15000",
		"txn-critical": "20000",
	}

	mismatches := r.detectMismatches(ledger, psp)
	require.Len(t, mismatches, 4)

	// Sorted deterministically: CRITICAL desc diff -> HIGH desc diff -> LOW.
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
