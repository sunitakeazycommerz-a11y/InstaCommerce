package cdc

import (
	"context"
	"database/sql"
	"database/sql/driver"
	"errors"
	"io"
	"log/slog"
	"strings"
	"sync"
	"testing"
	"time"

	"github.com/instacommerce/reconciliation-engine/internal/testdb"
	"github.com/segmentio/kafka-go"
	"github.com/stretchr/testify/assert"
	"github.com/stretchr/testify/require"
)

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
	mu      sync.Mutex
	rows    map[string]paymentLedgerRow
	execErr error
}

func newCDCStubDB() (*sql.DB, *cdcStubState) {
	state := &cdcStubState{rows: make(map[string]paymentLedgerRow)}
	db, _ := testdb.New(nil, func(_ int64, query string, args []driver.NamedValue) (driver.Result, error) {
		state.mu.Lock()
		defer state.mu.Unlock()

		if state.execErr != nil {
			return nil, state.execErr
		}

		switch {
		case strings.Contains(query, "DELETE FROM payment_ledger"):
			paymentID, _ := args[0].Value.(string)
			if existing, ok := state.rows[paymentID]; ok && existing.PSPReference == "" {
				delete(state.rows, paymentID)
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
			if existing, exists := state.rows[row.TransactionID]; exists {
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
			state.rows[row.TransactionID] = row
			return driver.RowsAffected(1), nil
		default:
			return driver.RowsAffected(1), nil
		}
	})
	return db, state
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
			"source": {"table": "payments", "ts_ms": 1731474000000, "lsn": 1001},
			"after": {
				"id": "pay-001", "order_id": "ord-001", "psp_reference": "pi_stripe_001",
				"amount_cents": 2599, "captured_cents": 2599, "refunded_cents": 0,
				"currency": "INR", "status": "CAPTURED", "payment_method": "CARD",
				"created_at": "2026-11-13T05:00:00Z", "updated_at": "2026-11-13T05:01:00Z"
			}
		}
	}`)

	require.NoError(t, consumer.processMessage(kafka.Message{Offset: 10, Value: eventJSON}))
	require.NoError(t, consumer.processMessage(kafka.Message{Offset: 10, Value: eventJSON}))

	state.mu.Lock()
	row, ok := state.rows["pi_stripe_001"]
	state.mu.Unlock()
	require.True(t, ok)
	assert.Equal(t, "pay-001", row.PaymentID)
	assert.Equal(t, int64(2599), row.AmountCents)
	assert.Equal(t, int64(1001), row.SourceLSN)

	metrics := consumer.GetMetrics()
	assert.Equal(t, int64(2), metrics.eventsReceived)
	assert.Equal(t, int64(2), metrics.eventsProcessed)
}

func TestPaymentLedgerCDC_CamelCasePreAuthUpgradeAndStaleLSN(t *testing.T) {
	db, state := newCDCStubDB()
	defer db.Close()
	consumer := newTestPaymentCDCConsumer(t, db)

	preAuth := []byte(`{
		"op": "r", "ts_ms": 1731474000000, "source": {"table": "payments", "lsn": 500},
		"after": {
			"paymentId": "pay-002", "orderId": "ord-002", "amountCents": 4200,
			"currency": "INR", "status": "AUTHORIZED", "paymentMethod": "UPI", "createdAt": 1731474000000000
		}
	}`)
	require.NoError(t, consumer.processMessage(kafka.Message{Offset: 1, Value: preAuth}))

	captured := []byte(`{
		"payload": {
			"op": "u", "ts_ms": 1731474060000, "source": {"table": "payments", "lsn": 600},
			"after": {
				"paymentId": "pay-002", "orderId": "ord-002", "pspReference": "rzp_pay_002",
				"amountCents": 4200, "capturedCents": 4200, "currency": "INR", "status": "CAPTURED",
				"paymentMethod": "UPI", "updatedAt": 1731474060000000
			}
		}
	}`)
	require.NoError(t, consumer.processMessage(kafka.Message{Offset: 2, Value: captured}))

	staleUpdate := []byte(`{
		"payload": {
			"op": "u", "ts_ms": 1731474030000, "source": {"table": "payments", "lsn": 550},
			"after": {"paymentId": "pay-002", "pspReference": "rzp_pay_002", "amountCents": 4200, "status": "AUTHORIZED"}
		}
	}`)
	require.NoError(t, consumer.processMessage(kafka.Message{Offset: 3, Value: staleUpdate}))

	state.mu.Lock()
	_, stillHasPlaceholder := state.rows["pay-002"]
	finalRow, hasCanonical := state.rows["rzp_pay_002"]
	state.mu.Unlock()

	assert.False(t, stillHasPlaceholder)
	require.True(t, hasCanonical)
	assert.Equal(t, "CAPTURED", finalRow.Status)
	assert.Equal(t, int64(600), finalRow.SourceLSN)
}

func TestPaymentLedgerCDC_TombstonesDeletesAndErrors(t *testing.T) {
	db, state := newCDCStubDB()
	defer db.Close()
	consumer := newTestPaymentCDCConsumer(t, db)

	require.NoError(t, consumer.processMessage(kafka.Message{Key: []byte("k1"), Value: nil}))
	require.NoError(t, consumer.processMessage(kafka.Message{Key: []byte("k1"), Value: []byte("null")}))

	deleteEvent := []byte(`{"payload":{"op":"d","source":{"table":"payments","lsn":700},"before":{"id":"pay-del"}}}`)
	require.NoError(t, consumer.processMessage(kafka.Message{Value: deleteEvent}))

	missingAmount := []byte(`{"payload":{"op":"c","source":{"table":"payments","lsn":701},"after":{"id":"pay-bad"}}}`)
	assert.Error(t, consumer.processMessage(kafka.Message{Value: missingAmount}))

	state.mu.Lock()
	state.execErr = errors.New("postgres unavailable")
	state.mu.Unlock()

	valid := []byte(`{"payload":{"op":"c","source":{"table":"payments","lsn":702},"after":{"id":"pay-db-err","psp_reference":"pi_err","amount_cents":1500,"status":"CAPTURED"}}}`)
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

	single, err := NewCDCConsumer(context.Background(), CDCConsumerConfig{
		KafkaBrokers: []string{"localhost:9092"},
		CDCTopic:     "payments.cdc",
	}, nil)
	require.NoError(t, err)
	defer func() { _ = single.Stop() }()
	assert.Equal(t, "payments.cdc", single.kafkaReader.Config().Topic)
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
	require.NoError(t, consumer.processRunRecord(map[string]interface{}{
		"run_date":            runDate,
		"status":              "COMPLETED",
		"mismatch_count":      1.0,
		"auto_fixed_count":    0.0,
		"manual_review_count": 0.0,
	}, time.Now().UnixMilli()))

	_, exists := consumer.dailySnapshots[runDate]
	if !exists {
		t.Fatal("snapshot should exist after processing")
	}

	consumer.PurgeSnapshot(runDate)
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

	for i := 0; i < 2; i++ {
		err := consumer.processRunRecord(afterImage, time.Now().UnixMilli())
		if err != nil {
			t.Fatalf("processRunRecord failed: %v", err)
		}
	}

	metrics := consumer.GetMetrics()
	if metrics.eventsProcessed != 2 {
		t.Errorf("expected 2 processed events, got %d", metrics.eventsProcessed)
	}

	snapshot := consumer.dailySnapshots[runDate]
	if snapshot.TotalMismatches != 10 {
		t.Errorf("expected 10 total mismatches from 2 identical events, got %d", snapshot.TotalMismatches)
	}
}
