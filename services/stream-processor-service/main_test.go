package main

import (
	"context"
	"errors"
	"io"
	"log/slog"
	"sync"
	"sync/atomic"
	"testing"
	"time"

	"github.com/redis/go-redis/v9"
	"github.com/segmentio/kafka-go"
	"github.com/stretchr/testify/assert"
	"github.com/stretchr/testify/require"

	"github.com/instacommerce/stream-processor-service/dedup"
	"github.com/instacommerce/stream-processor-service/processor"
)

type threadSafeRedisStub struct {
	mu   sync.Mutex
	data map[string]interface{}
}

func newThreadSafeRedisStub() *threadSafeRedisStub {
	return &threadSafeRedisStub{data: make(map[string]interface{})}
}

func (m *threadSafeRedisStub) SetNX(ctx context.Context, key string, value interface{}, expiration time.Duration) *redis.BoolCmd {
	m.mu.Lock()
	defer m.mu.Unlock()
	cmd := redis.NewBoolCmd(ctx, nil)
	if _, exists := m.data[key]; !exists {
		m.data[key] = value
		cmd.SetVal(true)
	} else {
		cmd.SetVal(false)
	}
	return cmd
}

func (m *threadSafeRedisStub) Exists(ctx context.Context, keys ...string) *redis.IntCmd {
	m.mu.Lock()
	defer m.mu.Unlock()
	cmd := redis.NewIntCmd(ctx, nil)
	var count int64
	for _, key := range keys {
		if _, exists := m.data[key]; exists {
			count++
		}
	}
	cmd.SetVal(count)
	return cmd
}

func TestFailedAndPanickedOffsetsReprocessedOnReplayAfterRestart(t *testing.T) {
	ctx := context.Background()
	redisStore := newThreadSafeRedisStub()
	logger := slog.New(slog.NewTextHandler(io.Discard, nil))

	var committedMu sync.Mutex
	var committed []int64
	commitFn := func(_ context.Context, msg kafka.Message) error {
		committedMu.Lock()
		committed = append(committed, msg.Offset)
		committedMu.Unlock()
		return nil
	}

	var callsOffset1 atomic.Int32
	var callsOffset2 atomic.Int32
	shouldFail := atomic.Bool{}
	shouldFail.Store(true)

	handler := func(_ context.Context, msg kafka.Message) error {
		switch msg.Offset {
		case 1:
			callsOffset1.Add(1)
			if shouldFail.Load() {
				return errors.New("transient failure")
			}
		case 2:
			callsOffset2.Add(1)
			if shouldFail.Load() {
				panic("transient panic")
			}
		}
		return nil
	}

	cfg := processor.StreamConfig{
		BufferSize:     16,
		HighWatermark:  12,
		LowWatermark:   4,
		WorkerCount:    2,
		PauseCheckWait: 2 * time.Millisecond,
	}

	deduper1 := dedup.NewChecker(redisStore, logger).WithBloomFilter(dedup.NewBloomFilter(100, 0.01))
	disp1 := processor.NewStreamDispatcher(cfg, logger, wrapHandlerWithDedup(deduper1, logger, handler), commitFn)
	disp1.Start(ctx)
	require.NoError(t, dispatchMessage(ctx, logger, deduper1, disp1, kafka.Message{Topic: "orders.events", Partition: 0, Offset: 1}))
	require.NoError(t, dispatchMessage(ctx, logger, deduper1, disp1, kafka.Message{Topic: "orders.events", Partition: 0, Offset: 2}))
	disp1.Stop()

	committedMu.Lock()
	assert.Empty(t, committed, "failed and panicked offsets must not commit")
	committedMu.Unlock()

	shouldFail.Store(false)
	deduper2 := dedup.NewChecker(redisStore, logger).WithBloomFilter(dedup.NewBloomFilter(100, 0.01))
	disp2 := processor.NewStreamDispatcher(cfg, logger, wrapHandlerWithDedup(deduper2, logger, handler), commitFn)
	disp2.Start(ctx)
	require.NoError(t, dispatchMessage(ctx, logger, deduper2, disp2, kafka.Message{Topic: "orders.events", Partition: 0, Offset: 1}))
	require.NoError(t, dispatchMessage(ctx, logger, deduper2, disp2, kafka.Message{Topic: "orders.events", Partition: 0, Offset: 2}))
	disp2.Stop()

	assert.Equal(t, int32(2), callsOffset1.Load(), "failed offset must not be skipped on replay after restart")
	assert.Equal(t, int32(2), callsOffset2.Load(), "panicked offset must not be skipped on replay after restart")

	disp3 := processor.NewStreamDispatcher(cfg, logger, wrapHandlerWithDedup(deduper2, logger, handler), commitFn)
	disp3.Start(ctx)
	require.NoError(t, dispatchMessage(ctx, logger, deduper2, disp3, kafka.Message{Topic: "orders.events", Partition: 0, Offset: 1}))
	require.NoError(t, dispatchMessage(ctx, logger, deduper2, disp3, kafka.Message{Topic: "orders.events", Partition: 0, Offset: 2}))
	disp3.Stop()

	assert.Equal(t, int32(2), callsOffset1.Load(), "succeeded offset must be deduplicated on subsequent replay")
	assert.Equal(t, int32(2), callsOffset2.Load(), "succeeded offset must be deduplicated on subsequent replay")

	committedMu.Lock()
	assert.Equal(t, []int64{1, 2, 1, 2}, committed)
	committedMu.Unlock()
}
