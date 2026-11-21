package reconciliation

import (
	"fmt"
	"math"
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

// WithinRoundingTolerance returns true when ledger and PSP amounts are both valid,
// share the same sign, and differ by at most toleranceCents minor units.
func WithinRoundingTolerance(ledgerStr, pspStr string, toleranceCents int64) bool {
	if toleranceCents < 0 {
		return false
	}
	ledgerCents, okL := parseAmountCentsStrict(ledgerStr)
	pspCents, okP := parseAmountCentsStrict(pspStr)
	if !okL || !okP || !sameSignOrBothZero(ledgerCents, pspCents) {
		return false
	}
	return absDiffUint64(ledgerCents, pspCents) <= uint64(toleranceCents)
}

// ShouldAutoFix permits automatic adjustment only when the discrepancy is both
// within 5% of the base amount and at most MaxAutoFixDiffCents (100 cents).
func ShouldAutoFix(ledgerAmountStr, pspAmountStr string) bool {
	ledgerCents, okL := parseAmountCentsStrict(ledgerAmountStr)
	pspCents, okP := parseAmountCentsStrict(pspAmountStr)
	if !okL || !okP || !sameSignOrBothZero(ledgerCents, pspCents) {
		return false
	}
	diff := absDiffUint64(ledgerCents, pspCents)
	if diff == 0 {
		return true
	}
	if diff > uint64(MaxAutoFixDiffCents) {
		return false
	}
	base := absDiffUint64(ledgerCents, 0)
	if base == 0 {
		return false
	}
	if base >= 2000 {
		return true
	}
	return diff*100 <= base*5
}

func sameSignOrBothZero(a, b int64) bool {
	if (a == 0) != (b == 0) {
		return false
	}
	return (a < 0) == (b < 0)
}

func absDiffUint64(a, b int64) uint64 {
	if a >= b {
		return uint64(a) - uint64(b)
	}
	return uint64(b) - uint64(a)
}

func parseAmountCentsStrict(raw string) (int64, bool) {
	s := strings.TrimSpace(raw)
	if s == "" {
		return 0, false
	}
	neg := false
	switch s[0] {
	case '-':
		neg = true
		s = s[1:]
	case '+':
		s = s[1:]
	}
	if s == "" {
		return 0, false
	}

	var total uint64
	dotIdx := strings.IndexByte(s, '.')
	if dotIdx < 0 {
		for i := 0; i < len(s); i++ {
			if s[i] < '0' || s[i] > '9' {
				return 0, false
			}
		}
		v, err := strconv.ParseUint(s, 10, 64)
		if err != nil {
			return 0, false
		}
		total = v
	} else {
		wholeStr := s[:dotIdx]
		fracStr := s[dotIdx+1:]
		if strings.IndexByte(fracStr, '.') >= 0 || (wholeStr == "" && fracStr == "") {
			return 0, false
		}
		for i := 0; i < len(wholeStr); i++ {
			if wholeStr[i] < '0' || wholeStr[i] > '9' {
				return 0, false
			}
		}
		for i := 0; i < len(fracStr); i++ {
			if fracStr[i] < '0' || fracStr[i] > '9' {
				return 0, false
			}
		}
		fracTrimmed := strings.TrimRight(fracStr, "0")
		if len(fracTrimmed) > 2 {
			return 0, false
		}
		var whole uint64
		if wholeStr != "" {
			var err error
			whole, err = strconv.ParseUint(wholeStr, 10, 64)
			if err != nil || whole > (math.MaxInt64/100) {
				return 0, false
			}
		}
		var centsPart uint64
		if len(fracTrimmed) > 0 {
			padded := fracTrimmed
			if len(padded) == 1 {
				padded += "0"
			}
			v, err := strconv.ParseUint(padded, 10, 64)
			if err != nil {
				return 0, false
			}
			centsPart = v
		}
		if whole*100 > math.MaxUint64-centsPart {
			return 0, false
		}
		total = whole*100 + centsPart
	}

	if neg {
		if total > uint64(math.MaxInt64)+1 {
			return 0, false
		}
		if total == uint64(math.MaxInt64)+1 {
			return math.MinInt64, true
		}
		return -int64(total), true
	}
	if total > math.MaxInt64 {
		return 0, false
	}
	return int64(total), true
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
	cents, ok := parseAmountCentsStrict(raw)
	if !ok {
		return 0
	}
	return cents
}

func formatCentsAsDecimal(raw string) string {
	trimmed := strings.TrimSpace(raw)
	if trimmed == "" {
		return "0.00"
	}
	cents, ok := parseAmountCentsStrict(trimmed)
	if !ok {
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
