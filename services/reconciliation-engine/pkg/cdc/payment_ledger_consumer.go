package cdc

import (
	"context"
	"database/sql"
	"encoding/json"
	"errors"
	"fmt"
	"log/slog"
	"strconv"
	"strings"
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
	KafkaBrokers   []string
	KafkaGroupID   string
	CDCTopic       string
	CDCTopics      []string
	DB             *sql.DB
	MinBytes       int
	MaxBytes       int
	MaxWait        time.Duration
	CommitInterval time.Duration
	BatchSize      int
	BatchTimeout   time.Duration
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
	db             *sql.DB
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

// NewCDCConsumer creates a new CDC consumer instance
func NewCDCConsumer(ctx context.Context, config CDCConsumerConfig, logger *slog.Logger) (*CDCConsumer, error) {
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

	readerCfg := kafka.ReaderConfig{
		Brokers:        config.KafkaBrokers,
		GroupID:        config.KafkaGroupID,
		MinBytes:       config.MinBytes,
		MaxBytes:       config.MaxBytes,
		MaxWait:        config.MaxWait,
		CommitInterval: config.CommitInterval,
		StartOffset:    kafka.LastOffset, // Start from latest; can be changed to FirstOffset for full recovery
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
			c.mu.Lock()
			c.metrics.eventsFailed++
			c.mu.Unlock()
			continue
		}

		// Parse and process the CDC event; do not commit offsets when ingestion fails.
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

		// Commit the message offset
		if err := c.kafkaReader.CommitMessages(context.Background(), msg); err != nil {
			c.logger.Error("failed to commit offset", "error", err, "offset", msg.Offset)
		}
	}
}

// processMessage parses a Kafka message containing a Debezium change event
// and updates the daily snapshot or payment ledger.
func (c *CDCConsumer) processMessage(msg kafka.Message) error {
	return c.processMessageCtx(context.Background(), msg)
}

func (c *CDCConsumer) processMessageCtx(ctx context.Context, msg kafka.Message) error {
	var event DebeziumChangeEvent
	if err := json.Unmarshal(msg.Value, &event); err != nil {
		return fmt.Errorf("failed to unmarshal debezium event: %w", err)
	}
	if event.Envelope.Op == "" && event.Envelope.After == nil && event.Envelope.Source.Table == "" {
		if err := json.Unmarshal(msg.Value, &event.Envelope); err != nil {
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

	c.metrics.eventsReceived++
	c.metrics.eventsProcessed++

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

	c.metrics.eventsReceived++
	c.metrics.eventsProcessed++

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

type paymentLedgerRecord struct {
	TransactionID string
	PaymentID     string
	OrderID       string
	PSPReference  string
	AmountCents   int64
	CapturedCents int64
	RefundedCents int64
	Currency      string
	Status        string
	PaymentMethod string
	SourceLSN     int64
	CreatedAt     time.Time
	UpdatedAt     time.Time
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

func parsePaymentLedgerRecord(after map[string]interface{}, tsMs, lsn int64) (paymentLedgerRecord, error) {
	if after == nil {
		return paymentLedgerRecord{}, errors.New("missing after image in payments event")
	}

	paymentID := extractString(after, "payment_id", "paymentId", "id")
	orderID := extractString(after, "order_id", "orderId")
	pspRef := extractString(after, "psp_reference", "pspReference")
	txnID := extractString(after, "transaction_id", "transactionId")
	if txnID == "" {
		txnID = pspRef
	}
	if txnID == "" {
		txnID = paymentID
	}
	if paymentID == "" {
		paymentID = txnID
	}
	if txnID == "" || paymentID == "" {
		return paymentLedgerRecord{}, errors.New("missing payment identifier in payments CDC event")
	}

	amountCents, ok := extractInt64(after, "amount_cents", "amountCents", "amount")
	if !ok {
		return paymentLedgerRecord{}, fmt.Errorf("missing or invalid amount_cents for payment %s", paymentID)
	}
	capturedCents, _ := extractInt64(after, "captured_cents", "capturedCents")
	refundedCents, _ := extractInt64(after, "refunded_cents", "refundedCents")

	currency := extractString(after, "currency")
	if currency == "" {
		currency = "INR"
	}
	status := extractString(after, "status")
	if status == "" {
		status = "UNKNOWN"
	}
	paymentMethod := extractString(after, "payment_method", "paymentMethod")

	createdAt := extractTimestamp(after, tsMs, "created_at", "createdAt")
	updatedAt := extractTimestamp(after, tsMs, "updated_at", "updatedAt")

	return paymentLedgerRecord{
		TransactionID: txnID,
		PaymentID:     paymentID,
		OrderID:       orderID,
		PSPReference:  pspRef,
		AmountCents:   amountCents,
		CapturedCents: capturedCents,
		RefundedCents: refundedCents,
		Currency:      currency,
		Status:        status,
		PaymentMethod: paymentMethod,
		SourceLSN:     lsn,
		CreatedAt:     createdAt,
		UpdatedAt:     updatedAt,
	}, nil
}

func upsertPaymentLedger(ctx context.Context, db *sql.DB, entry paymentLedgerRecord) error {
	tx, err := db.BeginTx(ctx, nil)
	if err != nil {
		return fmt.Errorf("begin payment ledger tx: %w", err)
	}
	defer func() { _ = tx.Rollback() }()

	// Promote pre-authorization placeholder rows staged under payment_id once psp_reference arrives.
	if entry.TransactionID != entry.PaymentID && entry.PSPReference != "" {
		if _, err := tx.ExecContext(ctx,
			`DELETE FROM payment_ledger
			WHERE transaction_id = $1
			  AND payment_id = $1
			  AND (psp_reference IS NULL OR psp_reference = '')`,
			entry.PaymentID,
		); err != nil {
			return fmt.Errorf("remove placeholder payment ledger row: %w", err)
		}
	}

	_, err = tx.ExecContext(ctx,
		`INSERT INTO payment_ledger (
			transaction_id, payment_id, order_id, psp_reference,
			amount_cents, captured_cents, refunded_cents,
			currency, status, payment_method, source_lsn,
			created_at, updated_at
		) VALUES ($1, $2, $3, $4, $5, $6, $7, $8, $9, $10, $11, $12, $13)
		ON CONFLICT (transaction_id) DO UPDATE SET
			payment_id = EXCLUDED.payment_id,
			order_id = COALESCE(NULLIF(EXCLUDED.order_id, ''), payment_ledger.order_id),
			psp_reference = COALESCE(NULLIF(EXCLUDED.psp_reference, ''), payment_ledger.psp_reference),
			amount_cents = EXCLUDED.amount_cents,
			captured_cents = EXCLUDED.captured_cents,
			refunded_cents = EXCLUDED.refunded_cents,
			currency = EXCLUDED.currency,
			status = EXCLUDED.status,
			payment_method = COALESCE(NULLIF(EXCLUDED.payment_method, ''), payment_ledger.payment_method),
			source_lsn = GREATEST(payment_ledger.source_lsn, EXCLUDED.source_lsn),
			updated_at = GREATEST(payment_ledger.updated_at, EXCLUDED.updated_at)
		WHERE EXCLUDED.source_lsn >= payment_ledger.source_lsn`,
		entry.TransactionID,
		entry.PaymentID,
		entry.OrderID,
		entry.PSPReference,
		entry.AmountCents,
		entry.CapturedCents,
		entry.RefundedCents,
		entry.Currency,
		entry.Status,
		entry.PaymentMethod,
		entry.SourceLSN,
		entry.CreatedAt,
		entry.UpdatedAt,
	)
	if err != nil {
		return fmt.Errorf("upsert payment ledger row %s: %w", entry.TransactionID, err)
	}

	if err := tx.Commit(); err != nil {
		return fmt.Errorf("commit payment ledger tx: %w", err)
	}
	return nil
}

func extractString(m map[string]interface{}, keys ...string) string {
	for _, k := range keys {
		raw, exists := m[k]
		if !exists || raw == nil {
			continue
		}
		if s, ok := raw.(string); ok {
			if trimmed := strings.TrimSpace(s); trimmed != "" {
				return trimmed
			}
		}
	}
	return ""
}

func extractInt64(m map[string]interface{}, keys ...string) (int64, bool) {
	for _, k := range keys {
		raw, exists := m[k]
		if !exists || raw == nil {
			continue
		}
		switch v := raw.(type) {
		case float64:
			if v >= -9223372036854775808.0 && v < 9223372036854775808.0 && v == float64(int64(v)) {
				return int64(v), true
			}
		case int64:
			return v, true
		case int:
			return int64(v), true
		case json.Number:
			if n, err := v.Int64(); err == nil {
				return n, true
			}
		case string:
			if parsed, err := strconv.ParseInt(strings.TrimSpace(v), 10, 64); err == nil {
				return parsed, true
			}
		}
	}
	return 0, false
}

func extractTimestamp(m map[string]interface{}, fallbackTsMs int64, keys ...string) time.Time {
	for _, k := range keys {
		raw, exists := m[k]
		if !exists || raw == nil {
			continue
		}
		switch v := raw.(type) {
		case string:
			s := strings.TrimSpace(v)
			for _, layout := range []string{
				time.RFC3339Nano,
				time.RFC3339,
				"2006-01-02 15:04:05.999999",
				"2006-01-02 15:04:05",
			} {
				if parsed, err := time.Parse(layout, s); err == nil {
					return parsed.UTC()
				}
			}
		case float64:
			if ts, ok := unixTimestampFromNumber(int64(v)); ok {
				return ts
			}
		case int64:
			if ts, ok := unixTimestampFromNumber(v); ok {
				return ts
			}
		case int:
			if ts, ok := unixTimestampFromNumber(int64(v)); ok {
				return ts
			}
		}
	}
	if fallbackTsMs > 0 {
		return time.UnixMilli(fallbackTsMs).UTC()
	}
	return time.Now().UTC()
}

func unixTimestampFromNumber(n int64) (time.Time, bool) {
	if n <= 0 {
		return time.Time{}, false
	}
	switch {
	case n >= 1e14:
		return time.UnixMicro(n).UTC(), true
	case n >= 1e11:
		return time.UnixMilli(n).UTC(), true
	default:
		return time.Unix(n, 0).UTC(), true
	}
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
	c.mu.RLock()
	defer c.mu.RUnlock()
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
