package cdc

import (
	"context"
	"database/sql/driver"
	"errors"
	"io"
	"log/slog"
	"sync"
	"testing"
	"time"

	"github.com/instacommerce/reconciliation-engine/internal/testdb"
	"github.com/segmentio/kafka-go"
	"github.com/stretchr/testify/assert"
	"github.com/stretchr/testify/require"
)

type stubDLTWriter struct {
	mu       sync.Mutex
	messages []kafka.Message
	err      error
}

func (s *stubDLTWriter) WriteMessages(_ context.Context, msgs ...kafka.Message) error {
	s.mu.Lock()
	defer s.mu.Unlock()
	if s.err != nil {
		return s.err
	}
	s.messages = append(s.messages, msgs...)
	return nil
}

func (s *stubDLTWriter) Close() error { return nil }

type scriptedKafkaFetcher struct {
	mu        sync.Mutex
	messages  []kafka.Message
	idx       int
	committed []kafka.Message
	onEnd     func()
}

func (f *scriptedKafkaFetcher) FetchMessage(ctx context.Context) (kafka.Message, error) {
	f.mu.Lock()
	defer f.mu.Unlock()
	if f.idx >= len(f.messages) {
		if f.onEnd != nil {
			f.onEnd()
		}
		return kafka.Message{}, io.EOF
	}
	m := f.messages[f.idx]
	f.idx++
	return m, nil
}

func (f *scriptedKafkaFetcher) CommitMessages(_ context.Context, msgs ...kafka.Message) error {
	f.mu.Lock()
	defer f.mu.Unlock()
	f.committed = append(f.committed, msgs...)
	return nil
}

func TestCDCConsumer_PoisonMessageRoutesToDLTAndCommitsOffset(t *testing.T) {
	dlt := &stubDLTWriter{}
	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()

	fetcher := &scriptedKafkaFetcher{
		messages: []kafka.Message{
			{Topic: "payments.cdc", Partition: 0, Offset: 10, Key: []byte("bad"), Value: []byte(`{not-json`)},
			{Topic: "reconciliation.cdc", Partition: 0, Offset: 11, Key: []byte("ok"), Value: []byte(`{"payload":{"op":"c","source":{"table":"reconciliation_runs"},"after":{"run_date":"2026-11-15","status":"COMPLETED","mismatch_count":0}}}`)},
		},
		onEnd: cancel,
	}

	consumer := &CDCConsumer{
		config:            CDCConsumerConfig{MaxWait: 20 * time.Millisecond, DLTTopic: "reconciliation.events.DLT"},
		dltWriter:         dlt,
		logger:            slog.New(slog.NewTextHandler(io.Discard, nil)),
		dailySnapshots:    make(map[string]*DailySnapshot),
		metrics:           &CDCMetrics{},
		done:              make(chan struct{}),
		retryBackoff:      time.Millisecond,
		maxProcessRetries: 2,
	}

	consumer.runConsumerLoopWithFetcher(ctx, fetcher)

	require.Len(t, dlt.messages, 1)
	assert.Equal(t, []byte(`{not-json`), dlt.messages[0].Value)
	require.Len(t, fetcher.committed, 2)
	assert.Equal(t, int64(10), fetcher.committed[0].Offset)
	assert.Equal(t, int64(11), fetcher.committed[1].Offset)
	assert.Equal(t, int64(1), consumer.metrics.eventsDLQ)
	assert.Equal(t, int64(1), consumer.metrics.eventsFailed)
	assert.Equal(t, int64(1), consumer.metrics.eventsProcessed)
}

func TestCDCConsumer_TransientErrorRetriesThenSucceedsWithoutDLT(t *testing.T) {
	var attempts int
	execErr := errors.New("temporary db lock")
	db, _ := testdb.New(
		func(int64, string, []driver.NamedValue) (driver.Rows, error) {
			return &testdb.StubRows{}, nil
		},
		func(int64, string, []driver.NamedValue) (driver.Result, error) {
			attempts++
			if attempts < 3 {
				return nil, execErr
			}
			return driver.RowsAffected(1), nil
		},
	)
	defer db.Close()

	dlt := &stubDLTWriter{}
	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()

	fetcher := &scriptedKafkaFetcher{
		messages: []kafka.Message{
			{
				Topic:     "payments.cdc",
				Partition: 0,
				Offset:    42,
				Value:     []byte(`{"payload":{"op":"c","source":{"table":"payments","lsn":99},"after":{"id":"pay-1","payment_id":"pay-1","amount_cents":500,"currency":"INR","status":"CAPTURED"}}}`),
			},
		},
		onEnd: cancel,
	}

	consumer := &CDCConsumer{
		config:            CDCConsumerConfig{MaxWait: 20 * time.Millisecond, DLTTopic: "reconciliation.events.DLT"},
		db:                db,
		dltWriter:         dlt,
		logger:            slog.New(slog.NewTextHandler(io.Discard, nil)),
		dailySnapshots:    make(map[string]*DailySnapshot),
		metrics:           &CDCMetrics{},
		done:              make(chan struct{}),
		retryBackoff:      time.Millisecond,
		maxProcessRetries: 3,
	}

	consumer.runConsumerLoopWithFetcher(ctx, fetcher)

	assert.Equal(t, 3, attempts)
	assert.Empty(t, dlt.messages)
	require.Len(t, fetcher.committed, 1)
	assert.Equal(t, int64(42), fetcher.committed[0].Offset)
	assert.Equal(t, int64(0), consumer.metrics.eventsDLQ)
	assert.Equal(t, int64(0), consumer.metrics.eventsFailed)
}

func TestCDCConsumer_HoldsSafeOffsetWhenDLTFailsOrMissing(t *testing.T) {
	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()

	fetcher := &scriptedKafkaFetcher{
		messages: []kafka.Message{
			{Topic: "payments.cdc", Partition: 0, Offset: 100, Value: []byte(`{bad-json`)},
			{Topic: "reconciliation.cdc", Partition: 0, Offset: 101, Value: []byte(`{"payload":{"op":"c","source":{"table":"reconciliation_runs"},"after":{"run_date":"2026-11-16","status":"COMPLETED","mismatch_count":0}}}`)},
		},
		onEnd: cancel,
	}

	consumer := &CDCConsumer{
		config:            CDCConsumerConfig{MaxWait: 20 * time.Millisecond},
		dltWriter:         &stubDLTWriter{err: errors.New("dlt broker down")},
		logger:            slog.New(slog.NewTextHandler(io.Discard, nil)),
		dailySnapshots:    make(map[string]*DailySnapshot),
		metrics:           &CDCMetrics{},
		done:              make(chan struct{}),
		retryBackoff:      time.Millisecond,
		maxProcessRetries: 1,
	}

	consumer.runConsumerLoopWithFetcher(ctx, fetcher)

	assert.Empty(t, fetcher.committed, "must never advance offset past an uncommitted failure when DLT write fails")
	assert.Equal(t, int64(1), consumer.metrics.eventsFailed)
}
