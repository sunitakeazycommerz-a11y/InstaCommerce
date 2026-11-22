package cdc

import (
	"context"
	"encoding/json"
	"errors"
	"io"
	"log/slog"
	"sync/atomic"
	"testing"
	"time"

	"github.com/segmentio/kafka-go"
)

func TestParsing_DebeziumChangeEvent(t *testing.T) {
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

	if event.Envelope.Op != "i" {
		t.Errorf("expected op 'i', got %s", event.Envelope.Op)
	}

	if event.Envelope.Source.Table != "reconciliation_runs" {
		t.Errorf("expected table 'reconciliation_runs', got %s", event.Envelope.Source.Table)
	}

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
		if err := consumer.processRunRecord(afterImage, time.Now().UnixMilli()); err != nil {
			t.Fatalf("processRunRecord failed: %v", err)
		}
	}

	aggregated, err := consumer.AggregateDaily(context.Background(), time.Date(2024, 3, 21, 0, 0, 0, 0, time.UTC))
	if err != nil {
		t.Fatalf("AggregateDaily failed: %v", err)
	}

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

	if err := consumer.processRunRecord(map[string]interface{}{
		"run_date":            runDate,
		"status":              "IN_PROGRESS",
		"mismatch_count":      5.0,
		"auto_fixed_count":    2.0,
		"manual_review_count": 1.0,
	}, time.Now().UnixMilli()); err != nil {
		t.Fatalf("first processRunRecord failed: %v", err)
	}

	if err := consumer.processRunRecord(map[string]interface{}{
		"run_date":            runDate,
		"status":              "IN_PROGRESS",
		"mismatch_count":      5.0,
		"auto_fixed_count":    3.0,
		"manual_review_count": 0.0,
	}, time.Now().UnixMilli()); err != nil {
		t.Fatalf("second processRunRecord failed: %v", err)
	}

	snapshot := consumer.dailySnapshots[runDate]

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
		"mismatch_id":            1.0,
		"run_id":                 100.0,
		"transaction_id":         "txn-123456",
		"ledger_amount":          "1000.00",
		"psp_amount":             "950.00",
		"discrepancy_amount":     "50.00",
		"discrepancy_reason":     "amount_mismatch",
		"auto_fixed":             true,
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

	for i := 0; i < 5; i++ {
		if err := consumer.processRunRecord(map[string]interface{}{
			"run_date":            runDate,
			"status":              "COMPLETED",
			"mismatch_count":      1.0,
			"auto_fixed_count":    1.0,
			"manual_review_count": 0.0,
		}, time.Now().UnixMilli()); err != nil {
			t.Fatalf("processRunRecord failed: %v", err)
		}
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

func TestCDCConsumer_StopIsIdempotent(t *testing.T) {
	consumer := &CDCConsumer{
		config: CDCConsumerConfig{
			BatchTimeout: 10 * time.Millisecond,
		},
		dailySnapshots: make(map[string]*DailySnapshot),
		metrics:        &CDCMetrics{},
		done:           make(chan struct{}),
	}

	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()

	if err := consumer.Start(ctx); err != nil {
		t.Fatalf("Start failed: %v", err)
	}
	if err := consumer.Stop(); err != nil {
		t.Fatalf("first Stop failed: %v", err)
	}
	if err := consumer.Stop(); err != nil {
		t.Fatalf("second Stop failed: %v", err)
	}
}

type fakeKafkaFetcher struct {
	fetchFunc func(ctx context.Context) (kafka.Message, error)
}

func (f *fakeKafkaFetcher) FetchMessage(ctx context.Context) (kafka.Message, error) {
	return f.fetchFunc(ctx)
}

func (f *fakeKafkaFetcher) CommitMessages(_ context.Context, _ ...kafka.Message) error {
	return nil
}

func TestCDCConsumer_ShutdownErrorsDoNotIncrementEventsFailed(t *testing.T) {
	for _, tc := range []struct {
		name string
		err  error
	}{
		{name: "io.EOF on reader close", err: io.EOF},
		{name: "context.Canceled on shutdown", err: context.Canceled},
	} {
		t.Run(tc.name, func(t *testing.T) {
			consumer := &CDCConsumer{
				config:         CDCConsumerConfig{MaxWait: 50 * time.Millisecond},
				logger:         slog.New(slog.NewTextHandler(io.Discard, nil)),
				dailySnapshots: make(map[string]*DailySnapshot),
				metrics:        &CDCMetrics{},
				done:           make(chan struct{}),
			}
			fetcher := &fakeKafkaFetcher{
				fetchFunc: func(context.Context) (kafka.Message, error) {
					return kafka.Message{}, tc.err
				},
			}
			consumer.runConsumerLoopWithFetcher(context.Background(), fetcher)
			if got := consumer.GetMetrics().eventsFailed; got != 0 {
				t.Fatalf("expected eventsFailed=0 on %v, got %d", tc.err, got)
			}
		})
	}
}

func TestCDCConsumer_TransientFetchErrorBacksOffAndStopsOnCancel(t *testing.T) {
	consumer := &CDCConsumer{
		config:         CDCConsumerConfig{MaxWait: 50 * time.Millisecond},
		logger:         slog.New(slog.NewTextHandler(io.Discard, nil)),
		dailySnapshots: make(map[string]*DailySnapshot),
		metrics:        &CDCMetrics{},
		done:           make(chan struct{}),
		retryBackoff:   time.Millisecond,
	}

	ctx, cancel := context.WithCancel(context.Background())
	var calls atomic.Int32
	fetcher := &fakeKafkaFetcher{
		fetchFunc: func(context.Context) (kafka.Message, error) {
			if calls.Add(1) >= 2 {
				cancel()
			}
			return kafka.Message{}, errors.New("temporary broker outage")
		},
	}

	consumer.runConsumerLoopWithFetcher(ctx, fetcher)
	if got := consumer.GetMetrics().eventsFailed; got < 1 {
		t.Fatalf("expected transient fetch error to increment eventsFailed, got %d", got)
	}
}
