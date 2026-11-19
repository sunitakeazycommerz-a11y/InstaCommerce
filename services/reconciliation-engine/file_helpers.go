package main

import (
	"context"
	"crypto/rand"
	"encoding/hex"
	"encoding/json"
	"fmt"
	"log/slog"
	"os"
	"sort"
	"strings"
	"sync"
	"time"

	"github.com/instacommerce/reconciliation-engine/pkg/reconciliation"
	"github.com/robfig/cron/v3"
)

func findMismatches(psp []Transaction, ledger []LedgerEntry) []Mismatch {
	now := time.Now().UTC()
	ledgerByID := make(map[string]LedgerEntry, len(ledger))
	for _, entry := range ledger {
		ledgerByID[entry.ID] = entry
	}
	pspByID := make(map[string]Transaction, len(psp))
	mismatches := make([]Mismatch, 0)
	for _, txn := range psp {
		pspByID[txn.ID] = txn
		entry, ok := ledgerByID[txn.ID]
		if !ok {
			copyTxn := txn
			mismatches = append(mismatches, Mismatch{
				TransactionID: txn.ID, Type: "missing_ledger_entry", Reason: "ledger entry missing",
				Severity:  reconciliation.ClassifySeverity(abs64(txn.AmountCents), "missing_ledger_entry", 0),
				AgeBucket: reconciliation.AgeBucketFresh, Fixable: true, PSP: &copyTxn,
			})
			continue
		}
		var age time.Duration
		if !entry.UpdatedAt.IsZero() && now.After(entry.UpdatedAt) {
			age = now.Sub(entry.UpdatedAt)
		}
		if entry.Currency != txn.Currency {
			copyTxn, copyEntry := txn, entry
			mismatches = append(mismatches, Mismatch{
				TransactionID: txn.ID, Type: "currency_mismatch", Reason: "currency mismatch",
				Severity:  reconciliation.ClassifySeverity(abs64(txn.AmountCents), "currency_mismatch", age),
				AgeBucket: reconciliation.ClassifyAgeBucket(age), PSP: &copyTxn, Ledger: &copyEntry,
			})
			continue
		}
		if entry.AmountCents != txn.AmountCents {
			copyTxn, copyEntry := txn, entry
			diff := abs64(entry.AmountCents - txn.AmountCents)
			mismatches = append(mismatches, Mismatch{
				TransactionID: txn.ID, Type: "amount_mismatch", Reason: "amount mismatch",
				Severity:  reconciliation.ClassifySeverity(diff, "amount_mismatch", age),
				AgeBucket: reconciliation.ClassifyAgeBucket(age), Fixable: true, PSP: &copyTxn, Ledger: &copyEntry,
			})
		}
	}
	for _, entry := range ledger {
		if _, ok := pspByID[entry.ID]; ok {
			continue
		}
		var age time.Duration
		if !entry.UpdatedAt.IsZero() && now.After(entry.UpdatedAt) {
			age = now.Sub(entry.UpdatedAt)
		}
		copyEntry := entry
		mismatches = append(mismatches, Mismatch{
			TransactionID: entry.ID, Type: "missing_psp_export", Reason: "missing PSP export record",
			Severity:  reconciliation.ClassifySeverity(abs64(entry.AmountCents), "missing_psp_export", age),
			AgeBucket: reconciliation.ClassifyAgeBucket(age), Ledger: &copyEntry,
		})
	}
	sort.Slice(mismatches, func(i, j int) bool {
		if mismatches[i].Type != mismatches[j].Type {
			return mismatches[i].Type < mismatches[j].Type
		}
		return mismatches[i].TransactionID < mismatches[j].TransactionID
	})
	return mismatches
}

func abs64(v int64) int64 {
	if v < 0 {
		return -v
	}
	return v
}

func (m Mismatch) FixID() string { return fmt.Sprintf("%s:%s", m.Type, m.TransactionID) }

func newMismatchEvent(runID string, m Mismatch) ReconciliationEvent {
	return ReconciliationEvent{EventID: newID("event"), RunID: runID, EventType: "mismatch", OccurredAt: time.Now().UTC(), TransactionID: m.TransactionID, MismatchType: m.Type, Reason: m.Reason, Severity: m.Severity, AgeBucket: m.AgeBucket}
}

func newFixEvent(runID string, m Mismatch) ReconciliationEvent {
	return ReconciliationEvent{EventID: newID("event"), RunID: runID, EventType: "fixed", OccurredAt: time.Now().UTC(), TransactionID: m.TransactionID, MismatchType: m.Type, Reason: m.Reason, Severity: m.Severity, AgeBucket: m.AgeBucket, FixApplied: true}
}

func newManualReviewEvent(runID string, m Mismatch) ReconciliationEvent {
	return ReconciliationEvent{EventID: newID("event"), RunID: runID, EventType: "manual_review", OccurredAt: time.Now().UTC(), TransactionID: m.TransactionID, MismatchType: m.Type, Reason: m.Reason, Severity: m.Severity, AgeBucket: m.AgeBucket}
}

func newSummaryEvent(runID string, counts ReconciliationCounts) ReconciliationEvent {
	return ReconciliationEvent{EventID: newID("event"), RunID: runID, EventType: "summary", OccurredAt: time.Now().UTC(), Counts: &counts}
}

func StartScheduler(ctx context.Context, schedule string, runOnStart bool, _ *slog.Logger, run func(context.Context)) (*Scheduler, error) {
	schedule = strings.TrimSpace(schedule)
	if schedule == "" {
		schedule = defaultSchedule
	}
	scheduleCtx, cancel := context.WithCancel(ctx)
	var wg sync.WaitGroup
	trigger := func() {
		wg.Add(1)
		go func() {
			defer wg.Done()
			run(scheduleCtx)
		}()
	}
	if runOnStart {
		trigger()
	}
	if interval, err := time.ParseDuration(schedule); err == nil {
		ticker := time.NewTicker(interval)
		wg.Add(1)
		go func() {
			defer wg.Done()
			for {
				select {
				case <-scheduleCtx.Done():
					return
				case <-ticker.C:
					trigger()
				}
			}
		}()
		return &Scheduler{stop: func(stopCtx context.Context) error { cancel(); ticker.Stop(); return waitForShutdown(stopCtx, &wg) }}, nil
	}
	parser := cron.NewParser(cron.Minute | cron.Hour | cron.Dom | cron.Month | cron.Dow | cron.Descriptor)
	cronScheduler := cron.New(cron.WithParser(parser))
	if _, err := cronScheduler.AddFunc(schedule, trigger); err != nil {
		cancel()
		return nil, err
	}
	cronScheduler.Start()
	return &Scheduler{stop: func(stopCtx context.Context) error {
		cancel()
		cronScheduler.Stop()
		return waitForShutdown(stopCtx, &wg)
	}}, nil
}

func waitForShutdown(ctx context.Context, wg *sync.WaitGroup) error {
	done := make(chan struct{})
	go func() { wg.Wait(); close(done) }()
	select {
	case <-done:
		return nil
	case <-ctx.Done():
		return ctx.Err()
	}
}

func readJSONFile(path string, out any) error {
	payload, err := os.ReadFile(path)
	if err != nil {
		return err
	}
	if len(strings.TrimSpace(string(payload))) == 0 {
		return nil
	}
	return json.Unmarshal(payload, out)
}

func newID(prefix string) string {
	random := make([]byte, 6)
	if _, err := rand.Read(random); err != nil {
		return fmt.Sprintf("%s-%d", prefix, time.Now().UnixNano())
	}
	return fmt.Sprintf("%s-%d-%s", prefix, time.Now().UnixNano(), hex.EncodeToString(random))
}
