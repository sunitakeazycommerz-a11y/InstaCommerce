package main

import (
	"context"
	"encoding/json"
	"errors"
	"log/slog"
	"os"
	"path/filepath"
	"sync"
	"sync/atomic"
	"time"

	"github.com/segmentio/kafka-go"
	"go.opentelemetry.io/otel/attribute"
	"go.opentelemetry.io/otel/trace"
)

type Transaction struct {
	ID          string `json:"id"`
	AmountCents int64  `json:"amount_cents"`
	Currency    string `json:"currency"`
	Status      string `json:"status,omitempty"`
}

type LedgerEntry struct {
	ID          string    `json:"id"`
	AmountCents int64     `json:"amount_cents"`
	Currency    string    `json:"currency"`
	Type        string    `json:"type"`
	Source      string    `json:"source,omitempty"`
	UpdatedAt   time.Time `json:"updated_at"`
}

type Mismatch struct {
	TransactionID string
	Type          string
	Reason        string
	Severity      string
	AgeBucket     string
	Fixable       bool
	PSP           *Transaction
	Ledger        *LedgerEntry
}

type ReconciliationCounts struct {
	Mismatches   int
	Fixed        int
	ManualReview int
}

type ReconciliationEvent struct {
	EventID       string                `json:"event_id"`
	RunID         string                `json:"run_id"`
	EventType     string                `json:"event_type"`
	OccurredAt    time.Time             `json:"occurred_at"`
	TransactionID string                `json:"transaction_id,omitempty"`
	MismatchType  string                `json:"mismatch_type,omitempty"`
	Reason        string                `json:"reason,omitempty"`
	Severity      string                `json:"severity,omitempty"`
	AgeBucket     string                `json:"age_bucket,omitempty"`
	FixApplied    bool                  `json:"fix_applied,omitempty"`
	Counts        *ReconciliationCounts `json:"counts,omitempty"`
}

type PSPSource struct {
	path   string
	logger *slog.Logger
}

type LedgerStore struct {
	mu         sync.Mutex
	entries    map[string]LedgerEntry
	outputPath string
	logger     *slog.Logger
}

type FixRecord struct {
	ID            string    `json:"id"`
	AppliedAt     time.Time `json:"applied_at"`
	MismatchType  string    `json:"mismatch_type"`
	TransactionID string    `json:"transaction_id"`
}

type FixRegistry struct {
	mu      sync.Mutex
	records map[string]FixRecord
	path    string
	logger  *slog.Logger
}

type KafkaPublisher struct {
	enabled bool
	writer  *kafka.Writer
	logger  *slog.Logger
}

type Reconciler struct {
	logger      *slog.Logger
	tracer      trace.Tracer
	metrics     *Metrics
	pspSource   *PSPSource
	ledgerStore *LedgerStore
	fixRegistry *FixRegistry
	publisher   *KafkaPublisher
	timeout     time.Duration
	running     atomic.Bool
}

type Scheduler struct {
	stop func(context.Context) error
}

func (s *Scheduler) Stop(ctx context.Context) error {
	if s == nil || s.stop == nil {
		return nil
	}
	return s.stop(ctx)
}

func (s *PSPSource) Load(_ context.Context) ([]Transaction, error) {
	if s.path == "" {
		return []Transaction{
			{ID: "psp-1001", AmountCents: 1050, Currency: "USD", Status: "captured"},
			{ID: "psp-1002", AmountCents: 500, Currency: "USD", Status: "captured"},
		}, nil
	}
	var transactions []Transaction
	if err := readJSONFile(s.path, &transactions); err != nil {
		return nil, err
	}
	return transactions, nil
}

func (s *LedgerStore) Load(_ context.Context) ([]LedgerEntry, error) {
	s.mu.Lock()
	defer s.mu.Unlock()
	entries := make([]LedgerEntry, 0, len(s.entries))
	for _, entry := range s.entries {
		entries = append(entries, entry)
	}
	return entries, nil
}

func (s *LedgerStore) Upsert(entry LedgerEntry) {
	s.mu.Lock()
	defer s.mu.Unlock()
	s.entries[entry.ID] = entry
}

func (s *LedgerStore) Persist(_ context.Context) error {
	if s.outputPath == "" {
		return nil
	}
	s.mu.Lock()
	entries := make([]LedgerEntry, 0, len(s.entries))
	for _, entry := range s.entries {
		entries = append(entries, entry)
	}
	s.mu.Unlock()
	payload, err := json.MarshalIndent(entries, "", "  ")
	if err != nil {
		return err
	}
	if dir := filepath.Dir(s.outputPath); dir != "." {
		if err := os.MkdirAll(dir, 0o755); err != nil {
			return err
		}
	}
	return os.WriteFile(s.outputPath, payload, 0o644)
}

func (r *FixRegistry) Exists(id string) bool {
	r.mu.Lock()
	defer r.mu.Unlock()
	_, ok := r.records[id]
	return ok
}

func (r *FixRegistry) Record(record FixRecord) error {
	r.mu.Lock()
	defer r.mu.Unlock()
	if _, exists := r.records[record.ID]; exists {
		return nil
	}
	r.records[record.ID] = record
	if r.path == "" {
		return nil
	}
	records := make([]FixRecord, 0, len(r.records))
	for _, rec := range r.records {
		records = append(records, rec)
	}
	payload, err := json.MarshalIndent(records, "", "  ")
	if err != nil {
		return err
	}
	if dir := filepath.Dir(r.path); dir != "." {
		if err := os.MkdirAll(dir, 0o755); err != nil {
			return err
		}
	}
	return os.WriteFile(r.path, payload, 0o644)
}

func (p *KafkaPublisher) Publish(ctx context.Context, event ReconciliationEvent) error {
	if !p.enabled {
		return nil
	}
	payload, err := json.Marshal(event)
	if err != nil {
		return err
	}
	key := event.EventID
	if event.TransactionID != "" {
		key = event.TransactionID
	}
	publishCtx, cancel := context.WithTimeout(ctx, 5*time.Second)
	defer cancel()
	return p.writer.WriteMessages(publishCtx, kafka.Message{
		Key: []byte(key), Value: payload, Time: event.OccurredAt,
	})
}

func (p *KafkaPublisher) Close() error {
	if !p.enabled {
		return nil
	}
	return p.writer.Close()
}

func (r *Reconciler) Run(ctx context.Context) {
	if !r.running.CompareAndSwap(false, true) {
		return
	}
	defer r.running.Store(false)

	runID := newID("run")
	runCtx, cancel := context.WithTimeout(ctx, r.timeout)
	defer cancel()
	runCtx, span := r.tracer.Start(runCtx, "reconciliation.run", trace.WithAttributes(attribute.String("run_id", runID)))
	defer span.End()

	pspTransactions, err := r.pspSource.Load(runCtx)
	if err != nil {
		return
	}
	ledgerEntries, err := r.ledgerStore.Load(runCtx)
	if err != nil {
		return
	}
	mismatches := findMismatches(pspTransactions, ledgerEntries)

	counts := ReconciliationCounts{}
	ledgerUpdated := false
	for _, mismatch := range mismatches {
		counts.Mismatches++
		r.metrics.RecordMismatch()
		_ = r.publisher.Publish(runCtx, newMismatchEvent(runID, mismatch))
		if mismatch.Fixable {
			applied, err := r.applyFix(runCtx, mismatch)
			if err != nil {
				continue
			}
			if applied {
				counts.Fixed++
				ledgerUpdated = true
				r.metrics.RecordFixed()
				_ = r.publisher.Publish(runCtx, newFixEvent(runID, mismatch))
			}
		} else {
			counts.ManualReview++
			r.metrics.RecordManualReview()
			_ = r.publisher.Publish(runCtx, newManualReviewEvent(runID, mismatch))
		}
	}
	if ledgerUpdated {
		_ = r.ledgerStore.Persist(runCtx)
	}
	_ = r.publisher.Publish(runCtx, newSummaryEvent(runID, counts))
}

func (r *Reconciler) applyFix(_ context.Context, mismatch Mismatch) (bool, error) {
	fixID := mismatch.FixID()
	if r.fixRegistry.Exists(fixID) {
		return false, nil
	}
	now := time.Now().UTC()
	switch mismatch.Type {
	case "missing_ledger_entry":
		if mismatch.PSP == nil {
			return false, errors.New("missing PSP data")
		}
		r.ledgerStore.Upsert(LedgerEntry{
			ID: mismatch.PSP.ID, AmountCents: mismatch.PSP.AmountCents,
			Currency: mismatch.PSP.Currency, Type: "payment", Source: "psp_export", UpdatedAt: now,
		})
	case "amount_mismatch":
		if mismatch.PSP == nil || mismatch.Ledger == nil {
			return false, errors.New("missing ledger data")
		}
		entry := *mismatch.Ledger
		entry.AmountCents = mismatch.PSP.AmountCents
		entry.Source = "reconciliation_adjustment"
		entry.UpdatedAt = now
		r.ledgerStore.Upsert(entry)
	default:
		return false, nil
	}
	_ = r.fixRegistry.Record(FixRecord{ID: fixID, AppliedAt: now, MismatchType: mismatch.Type, TransactionID: mismatch.TransactionID})
	return true, nil
}
