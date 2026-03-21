package cdc

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"log/slog"
	"sync"
	"time"

	"github.com/segmentio/kafka-go"
)

// DebeziumChangeEvent represents a Debezium CDC change event structure
// as captured from PostgreSQL logical decoding (pgoutput plugin)
type DebeziumChangeEvent struct {
	// Envelope wrapper structure for Debezium events
	Envelope struct {
		Before    map[string]interface{} `json:"before"`    // Previous row state (for UPDATE/DELETE)
		After     map[string]interface{} `json:"after"`     // Current row state (for INSERT/UPDATE)
		Source    SourceMetadata         `json:"source"`    // Source database metadata
		Op        string                 `json:"op"`        // Operation: i(INSERT), u(UPDATE), d(DELETE), t(TRUNCATE)
		TsMs      int64                  `json:"ts_ms"`     // Timestamp in milliseconds
		Txid      int64                  `json:"txid"`      // PostgreSQL transaction ID
		LSN       int64                  `json:"lsn"`       // PostgreSQL log sequence number
		Xmin      *int64                 `json:"xmin"`      // XMin (for TOAST handling)
		Xmax      *int64                 `json:"xmax"`      // XMax (for TOAST handling)
		XidXvid   *string                `json:"xid_xvid"`  // XID and XVID
		Snapshot  []string               `json:"snapshot"`  // Snapshot isolation
	} `json:"payload"`
}

// SourceMetadata contains PostgreSQL source database metadata
type SourceMetadata struct {
	Version   string `json:"version"`
	Connector string `json:"connector"`
	Name      string `json:"name"`
	TsMs      int64  `json:"ts_ms"`
	Snapshot  string `json:"snapshot"`
	Database  string `json:"database"`
	Schema    string `json:"schema"`
	Table     string `json:"table"`
	TxID      int64  `json:"txId"`
	LSN       int64  `json:"lsn"`
	Xmin      *int64 `json:"xmin"`
	Xmax      *int64 `json:"xmax"`
	XidXvid   *string `json:"xid_xvid"`
}

// ReconciliationLedgerEntry represents the reconciliation database row
// extracted from a Debezium change event
type ReconciliationLedgerEntry struct {
	RunID              int64      `json:"run_id"`
	RunDate            string     `json:"run_date"`
	Status             string     `json:"status"`
	MismatchCount      int        `json:"mismatch_count"`
	AutoFixedCount     int        `json:"auto_fixed_count"`
	ManualReviewCount  int        `json:"manual_review_count"`
	StartedAt          *time.Time `json:"started_at"`
	CompletedAt        *time.Time `json:"completed_at"`
	CreatedAt          time.Time  `json:"created_at"`
	TransactionID      string     `json:"transaction_id,omitempty"`
	LedgerAmount       string     `json:"ledger_amount,omitempty"`
	PSPAmount          string     `json:"psp_amount,omitempty"`
	DiscrepancyAmount  string     `json:"discrepancy_amount,omitempty"`
	DiscrepancyReason  string     `json:"discrepancy_reason,omitempty"`
	AutoFixed          bool       `json:"auto_fixed,omitempty"`
	ManualReviewRequired bool     `json:"manual_review_required,omitempty"`
}

// CDCConsumerConfig holds configuration for the CDC consumer
type CDCConsumerConfig struct {
	KafkaBrokers      []string
	KafkaGroupID      string
	CDCTopic          string
	MinBytes          int
	MaxBytes          int
	MaxWait           time.Duration
	CommitInterval    time.Duration
	BatchSize         int
	BatchTimeout      time.Duration
}

// DailySnapshot aggregates CDC events for a specific run date
type DailySnapshot struct {
	RunDate           string
	Transactions      map[string]*ReconciliationLedgerEntry // Key: transaction_id
	Runs              []*ReconciliationLedgerEntry          // Run-level entries
	TotalMismatches   int
	TotalAutoFixed    int
	TotalManualReview int
	LastUpdate        time.Time
}

// CDCConsumer processes Debezium CDC events for reconciliation
type CDCConsumer struct {
	kafkaReader    *kafka.Reader
	config         CDCConsumerConfig
	logger         *slog.Logger
	mu             sync.RWMutex
	dailySnapshots map[string]*DailySnapshot // Key: run_date
	metrics        *CDCMetrics
	done           chan struct{}
}

// CDCMetrics tracks CDC consumer metrics
type CDCMetrics struct {
	eventsReceived   int64
	eventsProcessed  int64
	eventsFailed     int64
	snapshotsCreated int64
	lastProcessedLSN int64
	consumerLag      int64
}

// NewCDCConsumer creates a new CDC consumer instance
func NewCDCConsumer(ctx context.Context, config CDCConsumerConfig, logger *slog.Logger) (*CDCConsumer, error) {
	if config.KafkaGroupID == "" {
		config.KafkaGroupID = "reconciliation-cdc-consumer"
	}
	if config.CDCTopic == "" {
		config.CDCTopic = "reconciliation.cdc"
	}
	if config.MinBytes == 0 {
		config.MinBytes = 10 * 1024 // 10 KB
	}
	if config.MaxBytes == 0 {
		config.MaxBytes = 10 * 1024 * 1024 // 10 MB
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

	reader := kafka.NewReader(kafka.ReaderConfig{
		Brokers:        config.KafkaBrokers,
		GroupID:        config.KafkaGroupID,
		Topic:          config.CDCTopic,
		MinBytes:       config.MinBytes,
		MaxBytes:       config.MaxBytes,
		MaxWait:        config.MaxWait,
		CommitInterval: config.CommitInterval,
		StartOffset:    kafka.LastOffset, // Start from latest; can be changed to FirstOffset for full recovery
	})

	return &CDCConsumer{
		kafkaReader:    reader,
		config:         config,
		logger:         logger,
		dailySnapshots: make(map[string]*DailySnapshot),
		metrics:        &CDCMetrics{},
		done:           make(chan struct{}),
	}, nil
}

// Start begins consuming CDC events and processing them into daily snapshots
func (c *CDCConsumer) Start(ctx context.Context) error {
	go c.runConsumerLoop(ctx)
	return nil
}

// Stop gracefully shuts down the CDC consumer
func (c *CDCConsumer) Stop() error {
	close(c.done)
	if c.kafkaReader != nil {
		return c.kafkaReader.Close()
	}
	return nil
}

// runConsumerLoop is the main consumer event loop
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

		// Set a read timeout for the Kafka fetch
		readCtx, cancel := context.WithTimeout(ctx, c.config.MaxWait*2)
		msg, err := c.kafkaReader.FetchMessage(readCtx)
		cancel()

		if err != nil {
			if errors.Is(err, context.Canceled) || errors.Is(err, context.DeadlineExceeded) {
				continue
			}
			c.logger.Error("failed to fetch cdc message", "error", err)
			c.metrics.eventsFailed++
			continue
		}

		// Parse and process the CDC event
		if err := c.processMessage(msg); err != nil {
			c.logger.Warn("failed to process cdc message", "error", err, "offset", msg.Offset)
			c.metrics.eventsFailed++
		} else {
			c.metrics.eventsProcessed++
			c.metrics.lastProcessedLSN = msg.Offset
		}

		// Commit the message offset
		if err := c.kafkaReader.CommitMessages(context.Background(), msg); err != nil {
			c.logger.Error("failed to commit offset", "error", err, "offset", msg.Offset)
		}
	}
}

// processMessage parses a Kafka message containing a Debezium change event
// and updates the daily snapshot
func (c *CDCConsumer) processMessage(msg kafka.Message) error {
	var event DebeziumChangeEvent
	if err := json.Unmarshal(msg.Value, &event); err != nil {
		return fmt.Errorf("failed to unmarshal debezium event: %w", err)
	}

	c.metrics.eventsReceived++

	// Extract operation and data
	op := event.Envelope.Op
	after := event.Envelope.After

	// Only process INSERT and UPDATE operations for aggregation
	// DELETE operations are handled separately
	if op != "i" && op != "u" {
		return nil
	}

	// Determine if this is a run or mismatch record based on source table
	table := event.Envelope.Source.Table
	switch table {
	case "reconciliation_runs":
		return c.processRunRecord(after, event.Envelope.Source.TsMs)
	case "reconciliation_mismatches":
		return c.processMismatchRecord(after, event.Envelope.Source.TsMs)
	default:
		c.logger.Debug("ignoring event from unknown table", "table", table)
		return nil
	}
}

// processRunRecord processes a reconciliation_runs table change event
func (c *CDCConsumer) processRunRecord(after map[string]interface{}, tsMs int64) error {
	entry := &ReconciliationLedgerEntry{}

	// Extract fields from after-image (new state)
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

	// Get or create daily snapshot
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

	// Update snapshot with run-level data
	snapshot.Runs = append(snapshot.Runs, entry)
	snapshot.TotalMismatches += entry.MismatchCount
	snapshot.TotalAutoFixed += entry.AutoFixedCount
	snapshot.TotalManualReview += entry.ManualReviewCount
	snapshot.LastUpdate = time.Now()

	c.logger.Debug("processed reconciliation_runs event",
		"run_date", entry.RunDate,
		"status", entry.Status,
		"mismatches", entry.MismatchCount,
	)

	return nil
}

// processMismatchRecord processes a reconciliation_mismatches table change event
func (c *CDCConsumer) processMismatchRecord(after map[string]interface{}, tsMs int64) error {
	entry := &ReconciliationLedgerEntry{}

	// Extract transaction-level mismatch details
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

	// Extract run_id to associate with correct snapshot
	var runID int64
	if rid, ok := after["run_id"].(float64); ok {
		runID = int64(rid)
	}

	if entry.TransactionID == "" {
		return fmt.Errorf("missing transaction_id in reconciliation_mismatches event")
	}

	c.mu.Lock()
	defer c.mu.Unlock()

	// For now, store mismatches in a temporary structure
	// In production, these would be aggregated and used to trigger reconciliation
	// The actual run_date lookup would happen via a query to the run_id

	c.logger.Debug("processed reconciliation_mismatches event",
		"transaction_id", entry.TransactionID,
		"auto_fixed", entry.AutoFixed,
		"run_id", runID,
	)

	return nil
}

// AggregateDaily creates a daily snapshot for a specific run date
// This method is called when the reconciliation job completes for the day
func (c *CDCConsumer) AggregateDaily(ctx context.Context, runDate time.Time) (*DailySnapshot, error) {
	c.mu.RLock()
	defer c.mu.RUnlock()

	runDateStr := runDate.Format("2006-01-02")
	snapshot, exists := c.dailySnapshots[runDateStr]
	if !exists {
		return nil, fmt.Errorf("no snapshot found for run_date %s", runDateStr)
	}

	// Create a copy to avoid locking during return
	result := &DailySnapshot{
		RunDate:           snapshot.RunDate,
		Transactions:      make(map[string]*ReconciliationLedgerEntry),
		Runs:              make([]*ReconciliationLedgerEntry, len(snapshot.Runs)),
		TotalMismatches:   snapshot.TotalMismatches,
		TotalAutoFixed:    snapshot.TotalAutoFixed,
		TotalManualReview: snapshot.TotalManualReview,
		LastUpdate:        snapshot.LastUpdate,
	}

	// Copy transaction data
	for k, v := range snapshot.Transactions {
		result.Transactions[k] = v
	}

	// Copy run data
	copy(result.Runs, snapshot.Runs)

	return result, nil
}

// GetMetrics returns current consumer metrics
func (c *CDCConsumer) GetMetrics() CDCMetrics {
	return *c.metrics
}

// PurgeSnapshot removes a snapshot from memory after processing
// This should be called after the snapshot has been persisted
func (c *CDCConsumer) PurgeSnapshot(runDate string) {
	c.mu.Lock()
	defer c.mu.Unlock()
	delete(c.dailySnapshots, runDate)
	c.logger.Debug("purged snapshot", "run_date", runDate)
}

// GetAllSnapshots returns all accumulated snapshots (for debugging/testing)
func (c *CDCConsumer) GetAllSnapshots() map[string]*DailySnapshot {
	c.mu.RLock()
	defer c.mu.RUnlock()

	result := make(map[string]*DailySnapshot)
	for k, v := range c.dailySnapshots {
		result[k] = v
	}
	return result
}
