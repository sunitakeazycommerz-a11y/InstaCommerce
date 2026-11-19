package reconciliation

import (
	"fmt"
	"math/big"
	"sort"
	"strconv"
	"strings"
	"time"
)

const (
	// MaxAutoFixDiffCents caps automatic discrepancy adjustments at 100 cents.
	MaxAutoFixDiffCents int64 = 100
	// FeeTaxRoundingToleranceCents tolerates up to 2 minor units for fee/tax rounding.
	FeeTaxRoundingToleranceCents int64 = 2

	SeverityCritical = "CRITICAL"
	SeverityHigh     = "HIGH"
	SeverityMedium   = "MEDIUM"
	SeverityLow      = "LOW"

	AgeBucketFresh       = "FRESH"
	AgeBucketAging24H    = "AGING_24H"
	AgeBucketStale48H    = "STALE_48H"
	AgeBucketAged72HPlus = "AGED_72H_PLUS"
)

// WithinRoundingTolerance returns true when ledger and PSP amounts are both valid
// and differ by at most toleranceCents (e.g. minor-unit fee/tax rounding).
func WithinRoundingTolerance(ledgerStr, pspStr string, toleranceCents int64) bool {
	if toleranceCents < 0 {
		return false
	}
	ledgerAmount, okL := parseRational(ledgerStr)
	pspAmount, okP := parseRational(pspStr)
	if !okL || !okP {
		return false
	}
	if ledgerAmount.Sign() == 0 && pspAmount.Sign() != 0 || pspAmount.Sign() == 0 && ledgerAmount.Sign() != 0 {
		return false
	}
	diff := new(big.Rat).Sub(ledgerAmount, pspAmount)
	diff.Abs(diff)
	return diff.Cmp(big.NewRat(toleranceCents, 1)) <= 0
}

// ShouldAutoFix permits automatic adjustment only when the discrepancy is both
// within 5% of the base amount and at most MaxAutoFixDiffCents (100 cents).
func ShouldAutoFix(ledgerAmountStr, pspAmountStr string) bool {
	ledgerAmount, ok := parseRational(ledgerAmountStr)
	if !ok {
		return false
	}
	pspAmount, ok := parseRational(pspAmountStr)
	if !ok {
		return false
	}

	diff := new(big.Rat).Sub(ledgerAmount, pspAmount)
	diff.Abs(diff)
	if diff.Sign() == 0 {
		return true
	}
	if diff.Cmp(big.NewRat(MaxAutoFixDiffCents, 1)) > 0 {
		return false
	}

	base := new(big.Rat).Abs(ledgerAmount)
	if base.Sign() == 0 {
		base = new(big.Rat).Abs(pspAmount)
	}
	if base.Sign() == 0 {
		return false
	}

	threshold := new(big.Rat).Mul(base, big.NewRat(5, 100))
	return diff.Cmp(threshold) <= 0
}

func parseRational(raw string) (*big.Rat, bool) {
	trimmed := strings.TrimSpace(raw)
	if trimmed == "" {
		return nil, false
	}
	return new(big.Rat).SetString(trimmed)
}

func ClassifyAgeBucket(age time.Duration) string {
	switch {
	case age >= 72*time.Hour:
		return AgeBucketAged72HPlus
	case age >= 48*time.Hour:
		return AgeBucketStale48H
	case age >= 24*time.Hour:
		return AgeBucketAging24H
	default:
		return AgeBucketFresh
	}
}

func ClassifySeverity(diffCents int64, reason string, age time.Duration) string {
	if diffCents >= 10000 || reason == "currency_mismatch" || age >= 48*time.Hour {
		return SeverityCritical
	}
	if diffCents >= 1000 || reason == "missing_ledger_entry" || reason == "missing_psp_export" || age >= 24*time.Hour {
		return SeverityHigh
	}
	if reason == "fee_tax_rounding_within_tolerance" && age < 4*time.Hour {
		return SeverityLow
	}
	if diffCents <= FeeTaxRoundingToleranceCents && age < 4*time.Hour {
		return SeverityLow
	}
	return SeverityMedium
}

func severityRank(severity string) int {
	switch severity {
	case SeverityCritical:
		return 0
	case SeverityHigh:
		return 1
	case SeverityMedium:
		return 2
	case SeverityLow:
		return 3
	default:
		return 4
	}
}

func SortMismatchDetails(mismatches []MismatchDetail) {
	sort.Slice(mismatches, func(i, j int) bool {
		ri, rj := severityRank(mismatches[i].Severity), severityRank(mismatches[j].Severity)
		if ri != rj {
			return ri < rj
		}
		if mismatches[i].DiscrepancyCents != mismatches[j].DiscrepancyCents {
			return mismatches[i].DiscrepancyCents > mismatches[j].DiscrepancyCents
		}
		if mismatches[i].Reason != mismatches[j].Reason {
			return mismatches[i].Reason < mismatches[j].Reason
		}
		return mismatches[i].TransactionID < mismatches[j].TransactionID
	})
}

func SortReconciliationMismatches(mismatches []ReconciliationMismatch) {
	sort.Slice(mismatches, func(i, j int) bool {
		ri, rj := severityRank(mismatches[i].Severity), severityRank(mismatches[j].Severity)
		if ri != rj {
			return ri < rj
		}
		if mismatches[i].AgeHours != mismatches[j].AgeHours {
			return mismatches[i].AgeHours > mismatches[j].AgeHours
		}
		return mismatches[i].TransactionID < mismatches[j].TransactionID
	})
}

func parseAmountCents(raw string) int64 {
	s := strings.TrimSpace(raw)
	if s == "" || s == "0.00" || s == "0" {
		return 0
	}
	if !strings.Contains(s, ".") {
		if val, err := strconv.ParseInt(s, 10, 64); err == nil {
			return val
		}
	}
	if rat, ok := new(big.Rat).SetString(s); ok {
		scaled := new(big.Rat).Mul(rat, big.NewRat(100, 1))
		if scaled.IsInt() {
			if num := scaled.Num(); num.IsInt64() {
				return num.Int64()
			}
		}
		f, _ := scaled.Float64()
		return int64(f)
	}
	return 0
}

func formatCentsAsDecimal(raw string) string {
	trimmed := strings.TrimSpace(raw)
	if trimmed == "" {
		return "0.00"
	}
	if strings.Contains(trimmed, ".") {
		if rat, ok := new(big.Rat).SetString(trimmed); ok {
			return rat.FloatString(2)
		}
		return trimmed
	}
	cents, err := strconv.ParseInt(trimmed, 10, 64)
	if err != nil {
		if rat, ok := new(big.Rat).SetString(trimmed); ok {
			return new(big.Rat).Quo(rat, big.NewRat(100, 1)).FloatString(2)
		}
		return trimmed
	}
	sign := ""
	var abs uint64
	if cents < 0 {
		sign = "-"
		abs = uint64(-(cents + 1)) + 1
	} else {
		abs = uint64(cents)
	}
	return fmt.Sprintf("%s%d.%02d", sign, abs/100, abs%100)
}

func calculateDiscrepancyCents(ledgerStr, pspStr string) int64 {
	l := parseAmountCents(ledgerStr)
	p := parseAmountCents(pspStr)
	diff := l - p
	if diff < 0 {
		return -diff
	}
	return diff
}
