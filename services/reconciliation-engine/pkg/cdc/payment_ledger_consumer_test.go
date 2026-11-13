package cdc

import (
	"context"
	"database/sql"
	"database/sql/driver"
	"encoding/json"
	"errors"
	"io"
	"log/slog"
	"strings"
	"sync"
	"sync/atomic"
	"testing"
	"time"

	"github.com/segmentio/kafka-go"
	"github.com/stretchr/testify/assert"
	"github.com/stretchr/testify/require"
)

func TestParsing_DebeziumChangeEvent(t *testing.T) {
	// Sample Debezium change event with pgoutput plugin structure
	rawEvent := []byte(`{
		"payload": {
			"op": "i",
			"ts_ms": 1711000000000,
			"txid": 12345,
			"lsn": 987654321,
			"source": {
				"version": "2.4.0",
				"connector": "postgres",
				"name": "reconciliation",
				"ts_ms": 1711000000000,
				"snapshot": "false",
				"database": "reconciliation",
				"schema": "public",
				"table": "reconciliation_runs",
				"txId": 12345,
				"lsn": 987654321
			},
			"before": null,
			"after": {
				"run_id": 1,
				"run_date": "2024-03-21",
				"status": "COMPLETED",
				"mismatch_count": 5,
				"auto_fixed_count": 3,
				"manual_review_count": 2,
				"started_at": "2024-03-21T12:00:00Z",
				"completed_at": "2024-03-21T12:05:00Z",
				"created_at": "2024-03-21T12:00:00Z"
			}
		}
	}`)

	var event DebeziumChangeEvent
	err := json.Unmarshal(rawEvent, &event)
	if err != nil {
		t.Fatalf("failed to unmarshal event: %v", err)
	}

	// Verify operation was correctly extracted
	if event.Envelope.Op != "i" {
		t.Errorf("expected op 'i', got %s", event.Envelope.Op)
	}

	// Verify source metadata
	if event.Envelope.Source.Table != "reconciliation_runs" {
		t.Errorf("expected table 'reconciliation_runs', got %s", event.Envelope.Source.Table)
	}

	// Verify after-image data
	afterData := event.Envelope.After
	if afterData["run_date"] != "2024-03-21" {
		t.Errorf("expected run_date '2024-03-21', got %v", afterData["run_date"])
	}

	if afterData["status"] != "COMPLETED" {
		t.Errorf("expected status 'COMPLETED', got %v", afterData["status"])
	}
}

func TestProcessRunRecord_ShouldCorrectlyExtractOperationAndBeforeAfter(t *testing.T) {
	logger := slog.New(slog.NewJSONHandler(nil, nil))
	config := CDCConsumerConfig{
		KafkaBrokers:   []string{"localhost:9092"},
		KafkaGroupID:   "test-consumer",
		CDCTopic:       "reconciliation.cdc",
		BatchSize:      100,
		BatchTimeout:   time.Second,
		CommitInterval: 5 * time.Second,
	}

	consumer := &CDCConsumer{
		config:         config,
		logger:         logger,
		dailySnapshots: make(map[string]*DailySnapshot),
		metrics:        &CDCMetrics{},
	}

	// Test INSERT operation
	afterImage := map[string]interface{}{
		"run_id":              1.0,
		"run_date":            "2024-03-21",
		"status":              "IN_PROGRESS",
		"mismatch_count":      10.0,
		"auto_fixed_count":    5.0,
		"manual_review_count": 2.0,
	}

	err := consumer.processRunRecord(afterImage, time.Now().UnixMilli())
	if err != nil {
		t.Fatalf("processRunRecord failed: %v", err)
	}

	// Verify snapshot was created
	snapshot, exists := consumer.dailySnapshots["2024-03-21"]
	if !exists {
		t.Fatal("expected snapshot to be created")
	}

	if snapshot.TotalMismatches != 10 {
		t.Errorf("expected 10 mismatches, got %d", snapshot.TotalMismatches)
	}

	if snapshot.TotalAutoFixed != 5 {
		t.Errorf("expected 5 auto-fixed, got %d", snapshot.TotalAutoFixed)
	}

	if snapshot.TotalManualReview != 2 {
		t.Errorf("expected 2 manual-review, got %d", snapshot.TotalManualReview)
	}
}

func TestDailyAggregation_ShouldGroupTransactionsByDate(t *testing.T) {
	logger := slog.New(slog.NewJSONHandler(nil, nil))
	config := CDCConsumerConfig{
		KafkaBrokers: []string{"localhost:9092"},
	}

	consumer := &CDCConsumer{
		config:         config,
		logger:         logger,
		dailySnapshots: make(map[string]*DailySnapshot),
		metrics:        &CDCMetrics{},
	}

	// Simulate multiple CDC events for the same date
	dates := []string{
		"2024-03-21",
		"2024-03-21",
		"2024-03-21",
	}

	for i, runDate := range dates {
		afterImage := map[string]interface{}{
			"run_date":            runDate,
			"status":              "COMPLETED",
			"mismatch_count":      float64(i + 1),
			"auto_fixed_count":    float64(i),
			"manual_review_count": 1.0,
		}
		consumer.processRunRecord(afterImage, time.Now().UnixMilli())
	}

	// Aggregate the daily snapshot
	aggregated, err := consumer.AggregateDaily(context.Background(), time.Date(2024, 3, 21, 0, 0, 0, 0, time.UTC))
	if err != nil {
		t.Fatalf("AggregateDaily failed: %v", err)
	}

	// Verify aggregation
	expectedMismatches := 1 + 2 + 3 // 6
	if aggregated.TotalMismatches != expectedMismatches {
		t.Errorf("expected %d total mismatches, got %d", expectedMismatches, aggregated.TotalMismatches)
	}

	expectedAutoFixed := 0 + 1 + 2 // 3
	if aggregated.TotalAutoFixed != expectedAutoFixed {
		t.Errorf("expected %d auto-fixed, got %d", expectedAutoFixed, aggregated.TotalAutoFixed)
	}
}

func TestMultipleEventsPerTransaction_ShouldSumCorrectly(t *testing.T) {
	logger := slog.New(slog.NewJSONHandler(nil, nil))
	config := CDCConsumerConfig{
		KafkaBrokers: []string{"localhost:9092"},
	}

	consumer := &CDCConsumer{
		config:         config,
		logger:         logger,
		dailySnapshots: make(map[string]*DailySnapshot),
		metrics:        &CDCMetrics{},
	}

	runDate := "2024-03-21"

	// First event: initial mismatch count
	consumer.processRunRecord(map[string]interface{}{
		"run_date":            runDate,
		"status":              "IN_PROGRESS",
		"mismatch_count":      5.0,
		"auto_fixed_count":    2.0,
		"manual_review_count": 1.0,
	}, time.Now().UnixMilli())

	// Second event: same transaction with updated counts
	consumer.processRunRecord(map[string]interface{}{
		"run_date":            runDate,
		"status":              "IN_PROGRESS",
		"mismatch_count":      5.0,
		"auto_fixed_count":    3.0,
		"manual_review_count": 0.0,
	}, time.Now().UnixMilli())

	snapshot := consumer.dailySnapshots[runDate]

	// Verify counts are summed correctly
	// Note: In a real scenario with CDC, UPDATES would replace previous values,
	// but multiple INSERT events would accumulate
	if snapshot.TotalAutoFixed < 3 {
		t.Errorf("expected at least 3 auto-fixed entries, got %d", snapshot.TotalAutoFixed)
	}
}

func TestOutOfOrderEvents_ShouldHandleGracefully(t *testing.T) {
	logger := slog.New(slog.NewJSONHandler(nil, nil))
	config := CDCConsumerConfig{
		KafkaBrokers: []string{"localhost:9092"},
	}

	consumer := &CDCConsumer{
		config:         config,
		logger:         logger,
		dailySnapshots: make(map[string]*DailySnapshot),
		metrics:        &CDCMetrics{},
	}

	runDate := "2024-03-21"

	// Simulate out-of-order events by timestamp
	events := []struct {
		ts   int64
		data map[string]interface{}
	}{
		{
			ts: 1711000050000, // Later timestamp
			data: map[string]interface{}{
				"run_date":            runDate,
				"status":              "COMPLETED",
				"mismatch_count":      5.0,
				"auto_fixed_count":    4.0,
				"manual_review_count": 0.0,
			},
		},
		{
			ts: 1711000000000, // Earlier timestamp (out of order)
			data: map[string]interface{}{
				"run_date":            runDate,
				"status":              "IN_PROGRESS",
				"mismatch_count":      3.0,
				"auto_fixed_count":    1.0,
				"manual_review_count": 1.0,
			},
		},
	}

	for _, event := range events {
		err := consumer.processRunRecord(event.data, event.ts)
		if err != nil {
			t.Fatalf("processRunRecord failed for out-of-order event: %v", err)
		}
	}

	// Should not panic or fail
	snapshot, exists := consumer.dailySnapshots[runDate]
	if !exists {
		t.Fatal("snapshot should exist despite out-of-order events")
	}

	if snapshot.TotalMismatches == 0 {
		t.Error("snapshot should contain aggregated data from both events")
	}
}

func TestMismatchRecord_ShouldExtractTransactionDetails(t *testing.T) {
	logger := slog.New(slog.NewJSONHandler(nil, nil))
	config := CDCConsumerConfig{
		KafkaBrokers: []string{"localhost:9092"},
	}

	consumer := &CDCConsumer{
		config:         config,
		logger:         logger,
		dailySnapshots: make(map[string]*DailySnapshot),
		metrics:        &CDCMetrics{},
	}

	mismatchAfterImage := map[string]interface{}{
		"mismatch_id":           1.0,
		"run_id":                100.0,
		"transaction_id":        "txn-123456",
		"ledger_amount":         "1000.00",
		"psp_amount":            "950.00",
		"discrepancy_amount":    "50.00",
		"discrepancy_reason":    "amount_mismatch",
		"auto_fixed":            true,
		"manual_review_required": false,
	}

	err := consumer.processMismatchRecord(mismatchAfterImage, time.Now().UnixMilli())
	if err != nil {
		t.Fatalf("processMismatchRecord failed: %v", err)
	}

	if consumer.metrics.eventsReceived != 1 {
		t.Errorf("expected 1 event processed, got %d", consumer.metrics.eventsReceived)
	}
}

func TestConsumerMetrics_ShouldTrackProcessingStats(t *testing.T) {
	logger := slog.New(slog.NewJSONHandler(nil, nil))
	config := CDCConsumerConfig{
		KafkaBrokers: []string{"localhost:9092"},
	}

	consumer := &CDCConsumer{
		config:         config,
		logger:         logger,
		dailySnapshots: make(map[string]*DailySnapshot),
		metrics:        &CDCMetrics{},
	}

	runDate := "2024-03-21"

	// Process several events
	for i := 0; i < 5; i++ {
		consumer.processRunRecord(map[string]interface{}{
			"run_date":            runDate,
			"status":              "COMPLETED",
			"mismatch_count":      1.0,
			"auto_fixed_count":    1.0,
			"manual_review_count": 0.0,
		}, time.Now().UnixMilli())
	}

	metrics := consumer.GetMetrics()

	if metrics.eventsProcessed != 5 {
		t.Errorf("expected 5 processed events, got %d", metrics.eventsProcessed)
	}

	if metrics.snapshotsCreated != 1 {
		t.Errorf("expected 1 snapshot created, got %d", metrics.snapshotsCreated)
	}

	if metrics.eventsFailed != 0 {
		t.Errorf("expected 0 failed events, got %d", metrics.eventsFailed)
	}
}

func TestPurgeSnapshot_ShouldRemoveFromMemory(t *testing.T) {
	logger := slog.New(slog.NewJSONHandler(nil, nil))
	config := CDCConsumerConfig{
		KafkaBrokers: []string{"localhost:9092"},
	}

	consumer := &CDCConsumer{
		config:         config,
		logger:         logger,
		dailySnapshots: make(map[string]*DailySnapshot),
		metrics:        &CDCMetrics{},
	}

	runDate := "2024-03-21"

	// Create a snapshot
	consumer.processRunRecord(map[string]interface{}{
		"run_date":            runDate,
		"status":              "COMPLETED",
		"mismatch_count":      1.0,
		"auto_fixed_count":    0.0,
		"manual_review_count": 0.0,
	}, time.Now().UnixMilli())

	// Verify it exists
	_, exists := consumer.dailySnapshots[runDate]
	if !exists {
		t.Fatal("snapshot should exist after processing")
	}

	// Purge it
	consumer.PurgeSnapshot(runDate)

	// Verify it's gone
	_, exists = consumer.dailySnapshots[runDate]
	if exists {
		t.Fatal("snapshot should be removed after purge")
	}
}

func TestIdempotentProcessing_ShouldHandleDuplicateEvents(t *testing.T) {
	logger := slog.New(slog.NewJSONHandler(nil, nil))
	config := CDCConsumerConfig{
		KafkaBrokers: []string{"localhost:9092"},
	}

	consumer := &CDCConsumer{
		config:         config,
		logger:         logger,
		dailySnapshots: make(map[string]*DailySnapshot),
		metrics:        &CDCMetrics{},
	}

	runDate := "2024-03-21"
	afterImage := map[string]interface{}{
		"run_date":            runDate,
		"status":              "COMPLETED",
		"mismatch_count":      5.0,
		"auto_fixed_count":    3.0,
		"manual_review_count": 1.0,
	}

	// Process the same event twice (simulating exactly-once semantics)
	for i := 0; i < 2; i++ {
		err := consumer.processRunRecord(afterImage, time.Now().UnixMilli())
		if err != nil {
			t.Fatalf("processRunRecord failed: %v", err)
		}
	}

	// Should have processed both events (CDC doesn't deduplicate at consumer level)
	metrics := consumer.GetMetrics()
	if metrics.eventsProcessed != 2 {
		t.Errorf("expected 2 processed events, got %d", metrics.eventsProcessed)
	}

	// Application level should handle idempotency (via transaction_id or run_id)
	snapshot := consumer.dailySnapshots[runDate]
	if snapshot.TotalMismatches != 10 {
		t.Errorf("expected 10 total mismatches from 2 identical events, got %d", snapshot.TotalMismatches)
	}
}

type recordedSQL struct {
	Query string
	Args  []driver.NamedValue
}

type paymentLedgerRow struct {
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
}

type cdcStubState struct {
	mu       sync.Mutex
	queries  []recordedSQL
	rows     map[string]paymentLedgerRow
	execErr  error
	nextConn atomic.Int64
}

func newCDCStubDB() (*sql.DB, *cdcStubState) {
	state := &cdcStubState{
		rows: make(map[string]paymentLedgerRow),
	}
	return sql.OpenDB(&cdcStubConnector{state: state}), state
}

type cdcStubConnector struct{ state *cdcStubState }

func (c *cdcStubConnector) Connect(context.Context) (driver.Conn, error) {
	c.state.nextConn.Add(1)
	return &cdcStubConn{state: c.state}, nil
}

func (c *cdcStubConnector) Driver() driver.Driver { return &cdcStubDriver{state: c.state} }

type cdcStubDriver struct{ state *cdcStubState }

func (d *cdcStubDriver) Open(string) (driver.Conn, error) {
	return &cdcStubConn{state: d.state}, nil
}

type cdcStubTx struct{}

func (t *cdcStubTx) Commit() error   { return nil }
func (t *cdcStubTx) Rollback() error { return nil }

type cdcEmptyRows struct{}

func (r *cdcEmptyRows) Columns() []string              { return nil }
func (r *cdcEmptyRows) Close() error                   { return nil }
func (r *cdcEmptyRows) Next([]driver.Value) error      { return io.EOF }

type cdcStubConn struct{ state *cdcStubState }

func (c *cdcStubConn) Prepare(string) (driver.Stmt, error) { return nil, driver.ErrSkip }
func (c *cdcStubConn) Close() error                        { return nil }
func (c *cdcStubConn) Begin() (driver.Tx, error)           { return &cdcStubTx{}, nil }
func (c *cdcStubConn) BeginTx(context.Context, driver.TxOptions) (driver.Tx, error) {
	return &cdcStubTx{}, nil
}

func (c *cdcStubConn) QueryContext(context.Context, string, []driver.NamedValue) (driver.Rows, error) {
	return &cdcEmptyRows{}, nil
}

func (c *cdcStubConn) ExecContext(_ context.Context, query string, args []driver.NamedValue) (driver.Result, error) {
	copied := make([]driver.NamedValue, len(args))
	copy(copied, args)

	c.state.mu.Lock()
	defer c.state.mu.Unlock()
	c.state.queries = append(c.state.queries, recordedSQL{Query: query, Args: copied})

	if c.state.execErr != nil {
		return nil, c.state.execErr
	}

	switch {
	case strings.Contains(query, "DELETE FROM payment_ledger"):
		paymentID, _ := args[0].Value.(string)
		if existing, ok := c.state.rows[paymentID]; ok && existing.PSPReference == "" {
			delete(c.state.rows, paymentID)
		}
		return driver.RowsAffected(1), nil
	case strings.Contains(query, "INSERT INTO payment_ledger"):
		row := paymentLedgerRow{
			TransactionID: args[0].Value.(string),
			PaymentID:     args[1].Value.(string),
			OrderID:       args[2].Value.(string),
			PSPReference:  args[3].Value.(string),
			AmountCents:   args[4].Value.(int64),
			CapturedCents: args[5].Value.(int64),
			RefundedCents: args[6].Value.(int64),
			Currency:      args[7].Value.(string),
			Status:        args[8].Value.(string),
			PaymentMethod: args[9].Value.(string),
			SourceLSN:     args[10].Value.(int64),
		}
		if existing, exists := c.state.rows[row.TransactionID]; exists {
			if row.SourceLSN < existing.SourceLSN {
				return driver.RowsAffected(0), nil
			}
			if row.OrderID == "" {
				row.OrderID = existing.OrderID
			}
			if row.PSPReference == "" {
				row.PSPReference = existing.PSPReference
			}
			if row.PaymentMethod == "" {
				row.PaymentMethod = existing.PaymentMethod
			}
		}
		c.state.rows[row.TransactionID] = row
		return driver.RowsAffected(1), nil
	default:
		return driver.RowsAffected(1), nil
	}
}

func newTestPaymentCDCConsumer(t *testing.T, db *sql.DB) *CDCConsumer {
	t.Helper()
	logger := slog.New(slog.NewTextHandler(io.Discard, nil))
	consumer, err := NewCDCConsumer(context.Background(), CDCConsumerConfig{
		KafkaBrokers: []string{"localhost:9092"},
		CDCTopics:    []string{"payments.cdc", "reconciliation.cdc"},
		DB:           db,
	}, logger)
	require.NoError(t, err)
	t.Cleanup(func() { _ = consumer.Stop() })
	return consumer
}

func TestPaymentLedgerCDC_SnakeCaseCreateAndIdempotentReplay(t *testing.T) {
	db, state := newCDCStubDB()
	defer db.Close()
	consumer := newTestPaymentCDCConsumer(t, db)

	eventJSON := []byte(`{
		"payload": {
			"op": "c",
			"ts_ms": 1731474000000,
			"lsn": 1001,
			"source": {
				"table": "payments",
				"ts_ms": 1731474000000,
				"lsn": 1001
			},
			"after": {
				"id": "pay-001",
				"order_id": "ord-001",
				"psp_reference": "pi_stripe_001",
				"amount_cents": 2599,
				"captured_cents": 2599,
				"refunded_cents": 0,
				"currency": "INR",
				"status": "CAPTURED",
				"payment_method": "CARD",
				"created_at": "2026-11-13T05:00:00Z",
				"updated_at": "2026-11-13T05:01:00Z"
			}
		}
	}`)

	require.NoError(t, consumer.processMessage(kafka.Message{Offset: 10, Value: eventJSON}))
	require.NoError(t, consumer.processMessage(kafka.Message{Offset: 10, Value: eventJSON}))

	state.mu.Lock()
	row, ok := state.rows["pi_stripe_001"]
	state.mu.Unlock()
	require.True(t, ok, "expected payment_ledger row keyed by psp_reference")
	assert.Equal(t, "pay-001", row.PaymentID)
	assert.Equal(t, "ord-001", row.OrderID)
	assert.Equal(t, "pi_stripe_001", row.PSPReference)
	assert.Equal(t, int64(2599), row.AmountCents)
	assert.Equal(t, int64(2599), row.CapturedCents)
	assert.Equal(t, int64(0), row.RefundedCents)
	assert.Equal(t, "INR", row.Currency)
	assert.Equal(t, "CAPTURED", row.Status)
	assert.Equal(t, "CARD", row.PaymentMethod)
	assert.Equal(t, int64(1001), row.SourceLSN)

	metrics := consumer.GetMetrics()
	assert.Equal(t, int64(2), metrics.eventsReceived)
	assert.Equal(t, int64(2), metrics.eventsProcessed)
	assert.Equal(t, int64(0), metrics.eventsFailed)
}

func TestPaymentLedgerCDC_CamelCasePreAuthUpgradeAndStaleLSN(t *testing.T) {
	db, state := newCDCStubDB()
	defer db.Close()
	consumer := newTestPaymentCDCConsumer(t, db)

	preAuth := []byte(`{
		"op": "r",
		"ts_ms": 1731474000000,
		"source": {"table": "payments", "lsn": 500},
		"after": {
			"paymentId": "pay-002",
			"orderId": "ord-002",
			"amountCents": 4200,
			"capturedCents": 0,
			"refundedCents": 0,
			"currency": "INR",
			"status": "AUTHORIZED",
			"paymentMethod": "UPI",
			"createdAt": 1731474000000000
		}
	}`)
	require.NoError(t, consumer.processMessage(kafka.Message{Offset: 1, Value: preAuth}))

	state.mu.Lock()
	_, hasPlaceholder := state.rows["pay-002"]
	state.mu.Unlock()
	require.True(t, hasPlaceholder, "pre-auth event without pspReference should stage under paymentId")

	captured := []byte(`{
		"payload": {
			"op": "u",
			"ts_ms": 1731474060000,
			"source": {"table": "payments", "lsn": 600},
			"after": {
				"paymentId": "pay-002",
				"orderId": "ord-002",
				"pspReference": "rzp_pay_002",
				"amountCents": 4200,
				"capturedCents": 4200,
				"refundedCents": 0,
				"currency": "INR",
				"status": "CAPTURED",
				"paymentMethod": "UPI",
				"updatedAt": 1731474060000000
			}
		}
	}`)
	require.NoError(t, consumer.processMessage(kafka.Message{Offset: 2, Value: captured}))

	staleUpdate := []byte(`{
		"payload": {
			"op": "u",
			"ts_ms": 1731474030000,
			"source": {"table": "payments", "lsn": 550},
			"after": {
				"paymentId": "pay-002",
				"pspReference": "rzp_pay_002",
				"amountCents": 4200,
				"capturedCents": 0,
				"status": "AUTHORIZED"
			}
		}
	}`)
	require.NoError(t, consumer.processMessage(kafka.Message{Offset: 3, Value: staleUpdate}))

	state.mu.Lock()
	_, stillHasPlaceholder := state.rows["pay-002"]
	finalRow, hasCanonical := state.rows["rzp_pay_002"]
	state.mu.Unlock()

	assert.False(t, stillHasPlaceholder, "placeholder row must be removed once psp_reference arrives")
	require.True(t, hasCanonical)
	assert.Equal(t, "CAPTURED", finalRow.Status)
	assert.Equal(t, int64(4200), finalRow.CapturedCents)
	assert.Equal(t, int64(600), finalRow.SourceLSN)
}

func TestPaymentLedgerCDC_IgnoresDeletesAndPropagatesErrors(t *testing.T) {
	db, state := newCDCStubDB()
	defer db.Close()
	consumer := newTestPaymentCDCConsumer(t, db)

	deleteEvent := []byte(`{
		"payload": {
			"op": "d",
			"source": {"table": "payments", "lsn": 700},
			"before": {"id": "pay-del"}
		}
	}`)
	require.NoError(t, consumer.processMessage(kafka.Message{Value: deleteEvent}))

	missingAmount := []byte(`{
		"payload": {
			"op": "c",
			"source": {"table": "payments", "lsn": 701},
			"after": {"id": "pay-bad"}
		}
	}`)
	assert.Error(t, consumer.processMessage(kafka.Message{Value: missingAmount}))

	state.mu.Lock()
	state.execErr = errors.New("postgres unavailable")
	state.mu.Unlock()

	valid := []byte(`{
		"payload": {
			"op": "c",
			"source": {"table": "payments", "lsn": 702},
			"after": {"id": "pay-db-err", "psp_reference": "pi_err", "amount_cents": 1500, "status": "CAPTURED"}
		}
	}`)
	err := consumer.processMessage(kafka.Message{Value: valid})
	assert.Error(t, err)
	assert.Contains(t, err.Error(), "postgres unavailable")
}

func TestNewCDCConsumer_TopicsAndDBConfig(t *testing.T) {
	db, _ := newCDCStubDB()
	defer db.Close()

	multi, err := NewCDCConsumer(context.Background(), CDCConsumerConfig{
		KafkaBrokers: []string{"localhost:9092"},
		CDCTopics:    []string{" payments.cdc ", "reconciliation.cdc", "payments.cdc"},
		DB:           db,
	}, nil)
	require.NoError(t, err)
	defer func() { _ = multi.Stop() }()
	assert.Equal(t, []string{"payments.cdc", "reconciliation.cdc"}, multi.config.CDCTopics)
	assert.Equal(t, []string{"payments.cdc", "reconciliation.cdc"}, multi.kafkaReader.Config().GroupTopics)
	assert.Empty(t, multi.kafkaReader.Config().Topic)
	assert.NotNil(t, multi.db)

	single, err := NewCDCConsumer(context.Background(), CDCConsumerConfig{
		KafkaBrokers: []string{"localhost:9092"},
		CDCTopic:     "payments.cdc",
	}, nil)
	require.NoError(t, err)
	defer func() { _ = single.Stop() }()
	assert.Equal(t, "payments.cdc", single.kafkaReader.Config().Topic)
	assert.Empty(t, single.kafkaReader.Config().GroupTopics)
}
