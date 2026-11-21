package main

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"log/slog"
	"os"
	"path/filepath"
	"strings"
	"sync"
	"sync/atomic"
	"time"

	"github.com/segmentio/kafka-go"
	"go.opentelemetry.io/otel"
	"go.opentelemetry.io/otel/attribute"
	"go.opentelemetry.io/otel/codes"
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

type fileKafkaWriter interface {
	WriteMessages(ctx context.Context, msgs ...kafka.Message) error
	Close() error
}

type KafkaPublisher struct {
	enabled bool
	writer  fileKafkaWriter
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
	if s == nil || strings.TrimSpace(s.path) == "" {
		return nil, errors.New("psp export path is required")
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

func (s *LedgerStore) Get(id string) (LedgerEntry, bool) {
	s.mu.Lock()
	defer s.mu.Unlock()
	entry, ok := s.entries[id]
	return entry, ok
}

func (s *LedgerStore) Upsert(entry LedgerEntry) {
	s.mu.Lock()
	defer s.mu.Unlock()
	s.entries[entry.ID] = entry
}

func (s *LedgerStore) Restore(id string, entry LedgerEntry, existed bool) {
	s.mu.Lock()
	defer s.mu.Unlock()
	if existed {
		s.entries[id] = entry
	} else {
		delete(s.entries, id)
	}
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
	if r.path != "" {
		records := make([]FixRecord, 0, len(r.records)+1)
		for _, rec := range r.records {
			records = append(records, rec)
		}
		records = append(records, record)
		payload, err := json.MarshalIndent(records, "", "  ")
		if err != nil {
			return err
		}
		if dir := filepath.Dir(r.path); dir != "." {
			if err := os.MkdirAll(dir, 0o755); err != nil {
				return err
			}
		}
		if err := os.WriteFile(r.path, payload, 0o644); err != nil {
			return err
		}
	}
	r.records[record.ID] = record
	return nil
}

func (p *KafkaPublisher) Publish(ctx context.Context, event ReconciliationEvent) error {
	if p == nil || !p.enabled || p.writer == nil {
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
	if p == nil || !p.enabled || p.writer == nil {
		return nil
	}
	return p.writer.Close()
}

func (r *Reconciler) Run(ctx context.Context) {
	_, _ = r.runOnce(ctx)
}

func (r *Reconciler) runOnce(ctx context.Context) (ReconciliationCounts, error) {
	if !r.running.CompareAndSwap(false, true) {
		return ReconciliationCounts{}, errors.New("reconciliation run already in progress")
	}
	defer r.running.Store(false)

	tracer := r.tracer
	if tracer == nil {
		tracer = otel.Tracer("reconciliation-file")
	}
	logger := r.logger
	if logger == nil {
		logger = slog.Default()
	}

	runID := newID("run")
	runCtx, cancel := context.WithTimeout(ctx, r.timeout)
	defer cancel()
	runCtx, span := tracer.Start(runCtx, "reconciliation.run", trace.WithAttributes(attribute.String("run_id", runID)))
	defer span.End()

	pspTransactions, err := r.pspSource.Load(runCtx)
	if err != nil {
		span.RecordError(err)
		span.SetStatus(codes.Error, err.Error())
		loggerWithContext(runCtx, logger).Error("failed to load PSP transactions", "error", err, "run_id", runID)
		return ReconciliationCounts{}, err
	}
	ledgerEntries, err := r.ledgerStore.Load(runCtx)
	if err != nil {
		span.RecordError(err)
		span.SetStatus(codes.Error, err.Error())
		loggerWithContext(runCtx, logger).Error("failed to load ledger entries", "error", err, "run_id", runID)
		return ReconciliationCounts{}, err
	}
	mismatches := findMismatches(pspTransactions, ledgerEntries)

	counts := ReconciliationCounts{}
	var runErrs []error
	logPublishErr := func(eventType, txnID string, pubErr error) {
		span.RecordError(pubErr)
		loggerWithContext(runCtx, logger).Error("failed to publish reconciliation event", "event_type", eventType, "transaction_id", txnID, "run_id", runID, "error", pubErr)
		runErrs = append(runErrs, fmt.Errorf("publish %s (%s): %w", eventType, txnID, pubErr))
	}

	for i := range mismatches {
		mismatch := mismatches[i]
		counts.Mismatches++
		if r.metrics != nil {
			r.metrics.RecordMismatch()
		}
		if err := r.publisher.Publish(runCtx, newReconciliationEvent(runID, "mismatch", &mismatch, false, nil)); err != nil {
			logPublishErr("mismatch", mismatch.TransactionID, err)
		}
		if mismatch.Fixable {
			applied, err := r.applyFix(runCtx, mismatch)
			if err != nil {
				span.RecordError(err)
				loggerWithContext(runCtx, logger).Error("failed to apply fix", "transaction_id", mismatch.TransactionID, "mismatch_type", mismatch.Type, "run_id", runID, "error", err)
				runErrs = append(runErrs, err)
				continue
			}
			if applied {
				counts.Fixed++
				if r.metrics != nil {
					r.metrics.RecordFixed()
				}
				if err := r.publisher.Publish(runCtx, newReconciliationEvent(runID, "fixed", &mismatch, true, nil)); err != nil {
					logPublishErr("fixed", mismatch.TransactionID, err)
				}
			}
		} else {
			counts.ManualReview++
			if r.metrics != nil {
				r.metrics.RecordManualReview()
			}
			if err := r.publisher.Publish(runCtx, newReconciliationEvent(runID, "manual_review", &mismatch, false, nil)); err != nil {
				logPublishErr("manual_review", mismatch.TransactionID, err)
			}
		}
	}
	if err := r.publisher.Publish(runCtx, newReconciliationEvent(runID, "summary", nil, false, &counts)); err != nil {
		logPublishErr("summary", "", err)
	}
	joined := errors.Join(runErrs...)
	if joined != nil {
		span.SetStatus(codes.Error, joined.Error())
	}
	return counts, joined
}

func (r *Reconciler) applyFix(ctx context.Context, mismatch Mismatch) (bool, error) {
	fixID := mismatch.FixID()
	if r.fixRegistry.Exists(fixID) {
		return false, nil
	}
	now := time.Now().UTC()
	var entry LedgerEntry
	switch mismatch.Type {
	case "missing_ledger_entry":
		if mismatch.PSP == nil {
			return false, errors.New("missing PSP data")
		}
		entry = LedgerEntry{
			ID: mismatch.PSP.ID, AmountCents: mismatch.PSP.AmountCents,
			Currency: mismatch.PSP.Currency, Type: "payment", Source: "psp_export", UpdatedAt: now,
		}
	case "amount_mismatch":
		if mismatch.PSP == nil || mismatch.Ledger == nil {
			return false, errors.New("missing ledger data")
		}
		entry = *mismatch.Ledger
		entry.AmountCents = mismatch.PSP.AmountCents
		entry.Source = "reconciliation_adjustment"
		entry.UpdatedAt = now
	default:
		return false, nil
	}

	prev, hadPrev := r.ledgerStore.Get(entry.ID)
	r.ledgerStore.Upsert(entry)
	if err := r.ledgerStore.Persist(ctx); err != nil {
		r.ledgerStore.Restore(entry.ID, prev, hadPrev)
		return false, fmt.Errorf("persist ledger update for %s: %w", entry.ID, err)
	}
	if err := r.fixRegistry.Record(FixRecord{ID: fixID, AppliedAt: now, MismatchType: mismatch.Type, TransactionID: mismatch.TransactionID}); err != nil {
		r.ledgerStore.Restore(entry.ID, prev, hadPrev)
		if rollbackErr := r.ledgerStore.Persist(ctx); rollbackErr != nil {
			return false, fmt.Errorf("record fix %s: %w (rollback persist failed: %v)", fixID, err, rollbackErr)
		}
		return false, fmt.Errorf("record fix %s: %w", fixID, err)
	}
	return true, nil
}
