package main

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"log/slog"
	"sync"
	"testing"
	"time"

	"github.com/segmentio/kafka-go"
	"github.com/stretchr/testify/assert"
	"github.com/stretchr/testify/require"

	"github.com/instacommerce/stream-processor-service/processor"
)

type stubDLQWriter struct {
	mu       sync.Mutex
	messages []kafka.Message
	err      error
}

func (s *stubDLQWriter) WriteMessages(_ context.Context, msgs ...kafka.Message) error {
	s.mu.Lock()
	defer s.mu.Unlock()
	if s.err != nil {
		return s.err
	}
	s.messages = append(s.messages, msgs...)
	return nil
}

func TestLoadConfig_PortPrecedence(t *testing.T) {
	t.Setenv("PORT", "")
	t.Setenv("SERVER_PORT", "")
	t.Setenv("HTTP_PORT", "")
	assert.Equal(t, "8108", loadConfig().HTTPPort)

	t.Setenv("HTTP_PORT", "9108")
	assert.Equal(t, "9108", loadConfig().HTTPPort)

	t.Setenv("SERVER_PORT", "8208")
	assert.Equal(t, "8208", loadConfig().HTTPPort)

	t.Setenv("PORT", "8308")
	assert.Equal(t, "8308", loadConfig().HTTPPort)
}

func TestWaitForRetry_CancelsPromptly(t *testing.T) {
	ctx, cancel := context.WithCancel(context.Background())
	cancel()

	start := time.Now()
	ok := waitForRetry(ctx, 5*time.Second)
	assert.False(t, ok)
	assert.Less(t, time.Since(start), 200*time.Millisecond)
}

func TestDLQHandler_RoutesPoisonToDLTAndHoldsTransientOffsets(t *testing.T) {
	logger := slog.New(slog.NewTextHandler(io.Discard, nil))
	dlq := &stubDLQWriter{}

	var commitMu sync.Mutex
	var committed []int64
	commitFn := func(_ context.Context, msg kafka.Message) error {
		commitMu.Lock()
		committed = append(committed, msg.Offset)
		commitMu.Unlock()
		return nil
	}

	cfg := processor.DefaultStreamConfig()
	cfg.BufferSize = 16
	cfg.HighWatermark = 12
	cfg.LowWatermark = 4
	cfg.WorkerCount = 2
	cfg.PauseCheckWait = 2 * time.Millisecond
	cfg.DLQHandler = newDLQHandler(dlq, logger)

	handler := func(_ context.Context, msg kafka.Message) error {
		if msg.Offset == 10 {
			var payload map[string]any
			if err := json.Unmarshal(msg.Value, &payload); err != nil {
				return fmt.Errorf("unmarshal order event: %w", err)
			}
		}
		if msg.Offset == 12 {
			return errors.New("redis connection refused")
		}
		return nil
	}

	disp := processor.NewStreamDispatcher(cfg, logger, handler, commitFn)
	ctx := context.Background()
	disp.Start(ctx)

	require.NoError(t, disp.Submit(ctx, kafka.Message{Topic: "orders.events", Partition: 0, Offset: 10, Value: []byte("{poison")}))
	require.NoError(t, disp.Submit(ctx, kafka.Message{Topic: "orders.events", Partition: 0, Offset: 11, Value: []byte(`{"id":"ok"}`)}))
	disp.Stop()

	require.Len(t, dlq.messages, 1)
	assert.Equal(t, "orders.events.DLT", dlq.messages[0].Topic)
	assert.Equal(t, []byte("{poison"), dlq.messages[0].Value)

	commitMu.Lock()
	require.NotEmpty(t, committed, "poison message routed to DLT must allow offset commit to advance")
	assert.Equal(t, int64(11), committed[len(committed)-1], "poison message routed to DLT must not stall subsequent commits")
	committed = nil
	commitMu.Unlock()

	// Transient error must NOT go to DLT and must hold offset commits.
	disp2 := processor.NewStreamDispatcher(cfg, logger, handler, commitFn)
	disp2.Start(ctx)
	require.NoError(t, disp2.Submit(ctx, kafka.Message{Topic: "orders.events", Partition: 0, Offset: 12, Value: []byte(`{"id":"transient"}`)}))
	disp2.Stop()

	assert.Len(t, dlq.messages, 1)
	commitMu.Lock()
	assert.Empty(t, committed, "transient error must hold offset")
	commitMu.Unlock()
}
