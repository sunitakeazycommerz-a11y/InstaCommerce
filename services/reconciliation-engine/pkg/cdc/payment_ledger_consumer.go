package cdc

import (
	"bytes"
	"context"
	"database/sql"
	"encoding/json"
	"errors"
	"fmt"
	"log/slog"
	"strings"
	"sync"
	"time"

	"github.com/segmentio/kafka-go"
)

// CDCConsumer processes Debezium CDC events for reconciliation.
type CDCConsumer struct {
	kafkaReader    *kafka.Reader
	db             *sql.DB
	config         CDCConsumerConfig
	logger         *slog.Logger
	mu             sync.RWMutex
	dailySnapshots map[string]*DailySnapshot
	metrics        *CDCMetrics
	done           chan struct{}
}

func resolveTopics(config CDCConsumerConfig) []string {
	seen := make(map[string]struct{})
	var topics []string
	addTopic := func(raw string) {
		trimmed := strings.TrimSpace(raw)
		if trimmed == "" {
			return
		}
		if _, exists := seen[trimmed]; exists {
			return
		}
		seen[trimmed] = struct{}{}
		topics = append(topics, trimmed)
	}
	for _, t := range config.CDCTopics {
		addTopic(t)
	}
	if len(topics) == 0 {
		addTopic(config.CDCTopic)
	}
	if len(topics) == 0 {
		addTopic("payments.cdc")
		addTopic("reconciliation.cdc")
	}
	return topics
}

// NewCDCConsumer creates a new CDC consumer instance.
func NewCDCConsumer(_ context.Context, config CDCConsumerConfig, logger *slog.Logger) (*CDCConsumer, error) {
	if logger == nil {
		logger = slog.Default()
	}
	if config.KafkaGroupID == "" {
		config.KafkaGroupID = "reconciliation-cdc-consumer"
	}
	topics := resolveTopics(config)
	config.CDCTopics = topics
	if config.CDCTopic == "" {
		config.CDCTopic = topics[0]
	}
	if config.MinBytes == 0 {
		config.MinBytes = 10 * 1024
	}
	if config.MaxBytes == 0 {
		config.MaxBytes = 10 * 1024 * 1024
	}
	if config.MaxWait == 0 {
		config.MaxWait = 5 * time.Second
	}
	if config.CommitInterval == 0 {
		config.CommitInterval = 10 * time.Second
	}
	if config.BatchSize == 0 {
		config.BatchSize = 500
	}
	if config.BatchTimeout == 0 {
		config.BatchTimeout = 5 * time.Second
	}

	readerCfg := kafka.ReaderConfig{
		Brokers:        config.KafkaBrokers,
		GroupID:        config.KafkaGroupID,
		MinBytes:       config.MinBytes,
		MaxBytes:       config.MaxBytes,
		MaxWait:        config.MaxWait,
		CommitInterval: config.CommitInterval,
		StartOffset:    kafka.LastOffset,
	}
	if len(topics) == 1 {
		readerCfg.Topic = topics[0]
	} else {
		readerCfg.GroupTopics = topics
	}

	reader := kafka.NewReader(readerCfg)
	return &CDCConsumer{
		kafkaReader:    reader,
		db:             config.DB,
		config:         config,
		logger:         logger,
		dailySnapshots: make(map[string]*DailySnapshot),
		metrics:        &CDCMetrics{},
		done:           make(chan struct{}),
	}, nil
}

func (c *CDCConsumer) Start(ctx context.Context) error {
	go c.runConsumerLoop(ctx)
	return nil
}

func (c *CDCConsumer) Stop() error {
	close(c.done)
	if c.kafkaReader != nil {
		return c.kafkaReader.Close()
	}
	return nil
}

func (c *CDCConsumer) runConsumerLoop(ctx context.Context) {
	ticker := time.NewTicker(c.config.BatchTimeout)
	defer ticker.Stop()

	for {
		select {
		case <-ctx.Done():
			c.logger.Info("cdc consumer context cancelled")
			return
		case <-c.done:
			c.logger.Info("cdc consumer stopped")
			return
		default:
		}

		readCtx, cancel := context.WithTimeout(ctx, c.config.MaxWait*2)
		msg, err := c.kafkaReader.FetchMessage(readCtx)
		cancel()

		if err != nil {
			if errors.Is(err, context.Canceled) || errors.Is(err, context.DeadlineExceeded) {
				continue
			}
			c.logger.Error("failed to fetch cdc message", "error", err)
			c.mu.Lock()
			c.metrics.eventsFailed++
			c.mu.Unlock()
			continue
		}

		if err := c.processMessageCtx(ctx, msg); err != nil {
			c.logger.Warn("failed to process cdc message", "error", err, "offset", msg.Offset)
			c.mu.Lock()
			c.metrics.eventsFailed++
			c.mu.Unlock()
			continue
		}

		c.mu.Lock()
		if msg.Offset > c.metrics.lastProcessedLSN {
			c.metrics.lastProcessedLSN = msg.Offset
		}
		c.mu.Unlock()

		if err := c.kafkaReader.CommitMessages(context.Background(), msg); err != nil {
			c.logger.Error("failed to commit offset", "error", err, "offset", msg.Offset)
		}
	}
}

func (c *CDCConsumer) processMessage(msg kafka.Message) error {
	return c.processMessageCtx(context.Background(), msg)
}

func (c *CDCConsumer) processMessageCtx(ctx context.Context, msg kafka.Message) error {
	trimmed := bytes.TrimSpace(msg.Value)
	if len(trimmed) == 0 || bytes.Equal(trimmed, []byte("null")) {
		return nil
	}

	var event DebeziumChangeEvent
	if err := json.Unmarshal(trimmed, &event); err != nil {
		return fmt.Errorf("failed to unmarshal debezium event: %w", err)
	}
	if event.Envelope.Op == "" && event.Envelope.After == nil && event.Envelope.Source.Table == "" {
		if err := json.Unmarshal(trimmed, &event.Envelope); err != nil {
			return fmt.Errorf("failed to unmarshal unwrapped debezium event: %w", err)
		}
	}

	op := strings.ToLower(strings.TrimSpace(event.Envelope.Op))
	after := event.Envelope.After
	switch op {
	case "c", "i", "u", "r":
	default:
		return nil
	}

	tsMs := event.Envelope.Source.TsMs
	if tsMs == 0 {
		tsMs = event.Envelope.TsMs
	}
	lsn := event.Envelope.Source.LSN
	if lsn == 0 {
		lsn = event.Envelope.LSN
	}

	table := strings.ToLower(strings.TrimSpace(event.Envelope.Source.Table))
	if table == "" && strings.Contains(strings.ToLower(msg.Topic), "payment") {
		table = "payments"
	}

	switch table {
	case "reconciliation_runs":
		return c.processRunRecord(after, tsMs)
	case "reconciliation_mismatches":
		return c.processMismatchRecord(after, tsMs)
	case "payments":
		return c.processPaymentRecord(ctx, after, tsMs, lsn)
	default:
		c.logger.Debug("ignoring event from unknown table", "table", table)
		return nil
	}
}

func (c *CDCConsumer) processRunRecord(after map[string]interface{}, _ int64) error {
	entry := &ReconciliationLedgerEntry{}
	if runDate, ok := after["run_date"].(string); ok {
		entry.RunDate = runDate
	}
	if status, ok := after["status"].(string); ok {
		entry.Status = status
	}
	if mismatchCount, ok := after["mismatch_count"].(float64); ok {
		entry.MismatchCount = int(mismatchCount)
	}
	if autoFixedCount, ok := after["auto_fixed_count"].(float64); ok {
		entry.AutoFixedCount = int(autoFixedCount)
	}
	if manualReviewCount, ok := after["manual_review_count"].(float64); ok {
		entry.ManualReviewCount = int(manualReviewCount)
	}
	if entry.RunDate == "" {
		return fmt.Errorf("missing run_date in reconciliation_runs event")
	}

	c.mu.Lock()
	defer c.mu.Unlock()
	c.metrics.eventsReceived++
	c.metrics.eventsProcessed++

	snapshot, exists := c.dailySnapshots[entry.RunDate]
	if !exists {
		snapshot = &DailySnapshot{
			RunDate:      entry.RunDate,
			Transactions: make(map[string]*ReconciliationLedgerEntry),
			Runs:         make([]*ReconciliationLedgerEntry, 0),
		}
		c.dailySnapshots[entry.RunDate] = snapshot
		c.metrics.snapshotsCreated++
	}
	snapshot.Runs = append(snapshot.Runs, entry)
	snapshot.TotalMismatches += entry.MismatchCount
	snapshot.TotalAutoFixed += entry.AutoFixedCount
	snapshot.TotalManualReview += entry.ManualReviewCount
	snapshot.LastUpdate = time.Now()
	return nil
}

func (c *CDCConsumer) processMismatchRecord(after map[string]interface{}, _ int64) error {
	entry := &ReconciliationLedgerEntry{}
	if transactionID, ok := after["transaction_id"].(string); ok {
		entry.TransactionID = transactionID
	}
	if ledgerAmount, ok := after["ledger_amount"].(string); ok {
		entry.LedgerAmount = ledgerAmount
	}
	if pspAmount, ok := after["psp_amount"].(string); ok {
		entry.PSPAmount = pspAmount
	}
	if discrepancyAmount, ok := after["discrepancy_amount"].(string); ok {
		entry.DiscrepancyAmount = discrepancyAmount
	}
	if discrepancyReason, ok := after["discrepancy_reason"].(string); ok {
		entry.DiscrepancyReason = discrepancyReason
	}
	if autoFixed, ok := after["auto_fixed"].(bool); ok {
		entry.AutoFixed = autoFixed
	}
	if manualReviewRequired, ok := after["manual_review_required"].(bool); ok {
		entry.ManualReviewRequired = manualReviewRequired
	}
	if entry.TransactionID == "" {
		return fmt.Errorf("missing transaction_id in reconciliation_mismatches event")
	}

	c.mu.Lock()
	defer c.mu.Unlock()
	c.metrics.eventsReceived++
	c.metrics.eventsProcessed++
	return nil
}

func (c *CDCConsumer) processPaymentRecord(ctx context.Context, after map[string]interface{}, tsMs, lsn int64) error {
	c.mu.Lock()
	c.metrics.eventsReceived++
	c.mu.Unlock()

	entry, err := parsePaymentLedgerRecord(after, tsMs, lsn)
	if err != nil {
		return err
	}
	db := c.db
	if db == nil {
		return errors.New("payment ledger database is not configured")
	}
	if err := upsertPaymentLedger(ctx, db, entry); err != nil {
		return err
	}

	c.mu.Lock()
	c.metrics.eventsProcessed++
	if lsn > c.metrics.lastProcessedLSN {
		c.metrics.lastProcessedLSN = lsn
	}
	c.mu.Unlock()
	return nil
}

func (c *CDCConsumer) AggregateDaily(_ context.Context, runDate time.Time) (*DailySnapshot, error) {
	c.mu.RLock()
	defer c.mu.RUnlock()

	runDateStr := runDate.Format("2006-01-02")
	snapshot, exists := c.dailySnapshots[runDateStr]
	if !exists {
		return nil, fmt.Errorf("no snapshot found for run_date %s", runDateStr)
	}

	result := &DailySnapshot{
		RunDate:           snapshot.RunDate,
		Transactions:      make(map[string]*ReconciliationLedgerEntry),
		Runs:              make([]*ReconciliationLedgerEntry, len(snapshot.Runs)),
		TotalMismatches:   snapshot.TotalMismatches,
		TotalAutoFixed:    snapshot.TotalAutoFixed,
		TotalManualReview: snapshot.TotalManualReview,
		LastUpdate:        snapshot.LastUpdate,
	}
	for k, v := range snapshot.Transactions {
		result.Transactions[k] = v
	}
	copy(result.Runs, snapshot.Runs)
	return result, nil
}

func (c *CDCConsumer) GetMetrics() CDCMetrics {
	c.mu.RLock()
	defer c.mu.RUnlock()
	return *c.metrics
}

func (c *CDCConsumer) PurgeSnapshot(runDate string) {
	c.mu.Lock()
	defer c.mu.Unlock()
	delete(c.dailySnapshots, runDate)
}

func (c *CDCConsumer) GetAllSnapshots() map[string]*DailySnapshot {
	c.mu.RLock()
	defer c.mu.RUnlock()
	result := make(map[string]*DailySnapshot, len(c.dailySnapshots))
	for k, v := range c.dailySnapshots {
		result[k] = v
	}
	return result
}
