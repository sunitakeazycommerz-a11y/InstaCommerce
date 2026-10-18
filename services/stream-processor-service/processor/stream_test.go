package processor

import (
	"context"
	"fmt"
	"sync/atomic"
	"testing"
	"time"

	"github.com/segmentio/kafka-go"
	"github.com/stretchr/testify/assert"
)

func TestStreamDispatcherBackpressure80And30Percent(t *testing.T) {
	// Buffer size 10, high watermark 7 (7 queued + 1 in worker = 80% total in-flight), low watermark 3 (30%)
	cfg := StreamConfig{
		BufferSize:     10,
		HighWatermark:  7,
		LowWatermark:   3,
		WorkerCount:    1,
		PauseCheckWait: 5 * time.Millisecond,
	}

	var processedCount atomic.Int64
	blockWorker := make(chan struct{})

	handler := func(ctx context.Context, msg kafka.Message) error {
		// First message waits until signal
		if processedCount.Add(1) == 1 {
			<-blockWorker
		}
		return nil
	}

	dispatcher := NewStreamDispatcher(cfg, nil, handler, nil)
	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()

	dispatcher.Start(ctx)

	// Send 8 messages (1 in blocked worker, 7 in queue reaching high watermark)
	for i := 0; i < 8; i++ {
		err := dispatcher.Submit(ctx, kafka.Message{
			Topic:  "test.topic",
			Offset: int64(i),
			Value:  []byte(fmt.Sprintf("msg-%d", i)),
		})
		assert.NoError(t, err)
	}

	// Verify backpressure is active
	assert.True(t, dispatcher.IsPaused(), "Dispatcher should be paused at 80% watermark")
	assert.GreaterOrEqual(t, dispatcher.PauseCount(), int64(1))

	// Release worker to drain messages
	close(blockWorker)

	// Submit one more message; this will block until queue drains to <= 3 (30%)
	doneCh := make(chan error, 1)
	go func() {
		doneCh <- dispatcher.Submit(ctx, kafka.Message{
			Topic:  "test.topic",
			Offset: 99,
			Value:  []byte("msg-99"),
		})
	}()

	select {
	case err := <-doneCh:
		assert.NoError(t, err)
	case <-time.After(2 * time.Second):
		t.Fatal("timed out waiting for dispatcher to drain below 30% and accept new message")
	}

	assert.False(t, dispatcher.IsPaused(), "Dispatcher should resume after draining below 30%")
	assert.GreaterOrEqual(t, dispatcher.ResumeCount(), int64(1))

	dispatcher.Stop()
}

func TestStreamDispatcherRecoversFromWorkerPanic(t *testing.T) {
	cfg := StreamConfig{
		BufferSize:     10,
		HighWatermark:  8,
		LowWatermark:   3,
		WorkerCount:    1,
		PauseCheckWait: 5 * time.Millisecond,
	}

	var processed atomic.Int64
	handler := func(ctx context.Context, msg kafka.Message) error {
		count := processed.Add(1)
		if count == 1 {
			panic("simulated bursty payload panic!")
		}
		return nil
	}

	dispatcher := NewStreamDispatcher(cfg, nil, handler, nil)
	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()

	dispatcher.Start(ctx)

	// Message 1 panics, Message 2 should still be processed by worker
	err := dispatcher.Submit(ctx, kafka.Message{Topic: "test", Offset: 1})
	assert.NoError(t, err)

	err = dispatcher.Submit(ctx, kafka.Message{Topic: "test", Offset: 2})
	assert.NoError(t, err)

	time.Sleep(50 * time.Millisecond)
	dispatcher.Stop()

	assert.Equal(t, int64(2), processed.Load(), "Worker should survive panic and process subsequent messages")
}
