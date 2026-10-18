package reconciliation

import (
	"testing"
	"time"

	"github.com/stretchr/testify/assert"
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
	assert.True(t, m.AutoFixed, "Missing ledger entry should be marked auto-fixable")
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
