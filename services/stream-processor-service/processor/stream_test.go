package processor

import (
	"context"
	"errors"
	"fmt"
	"sync"
	"sync/atomic"
	"testing"
	"time"

	"github.com/segmentio/kafka-go"
	"github.com/stretchr/testify/assert"
	"github.com/stretchr/testify/require"
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

	// Send first message and ensure the worker has started processing and is blocked
	err := dispatcher.Submit(ctx, kafka.Message{
		Topic:  "test.topic",
		Offset: 0,
		Value:  []byte("msg-0"),
	})
	assert.NoError(t, err)

	for processedCount.Load() < 1 {
		time.Sleep(time.Millisecond)
	}

	// Send remaining 7 messages to fill queue up to high watermark (7)
	for i := 1; i < 8; i++ {
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

type commitRecorder struct {
	mu      sync.Mutex
	commits []kafka.Message
}

func (r *commitRecorder) commit(_ context.Context, msg kafka.Message) error {
	r.mu.Lock()
	defer r.mu.Unlock()
	r.commits = append(r.commits, msg)
	return nil
}

func (r *commitRecorder) offsetsFor(topic string, partition int) []int64 {
	r.mu.Lock()
	defer r.mu.Unlock()
	var out []int64
	for _, m := range r.commits {
		if m.Topic == topic && m.Partition == partition {
			out = append(out, m.Offset)
		}
	}
	return out
}

func TestStreamDispatcherMonotonicOffsetCommitWhenEarlierStalls(t *testing.T) {
	cfg := StreamConfig{
		BufferSize:     16,
		HighWatermark:  12,
		LowWatermark:   4,
		WorkerCount:    4,
		PauseCheckWait: 2 * time.Millisecond,
	}

	started100 := make(chan struct{})
	release100 := make(chan struct{})
	done101 := make(chan struct{})

	handler := func(_ context.Context, msg kafka.Message) error {
		if msg.Partition == 0 && msg.Offset == 100 {
			close(started100)
			<-release100
		}
		if msg.Partition == 0 && msg.Offset == 101 {
			close(done101)
		}
		return nil
	}

	rec := &commitRecorder{}
	dispatcher := NewStreamDispatcher(cfg, nil, handler, rec.commit)
	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()
	dispatcher.Start(ctx)

	require.NoError(t, dispatcher.Submit(ctx, kafka.Message{Topic: "orders.events", Partition: 0, Offset: 100}))
	<-started100
	require.NoError(t, dispatcher.Submit(ctx, kafka.Message{Topic: "orders.events", Partition: 0, Offset: 101}))

	select {
	case <-done101:
	case <-time.After(2 * time.Second):
		t.Fatal("timed out waiting for offset 101 handler to complete")
	}

	assert.Empty(t, rec.offsetsFor("orders.events", 0), "offset 101 must not commit while offset 100 is unresolved")

	close(release100)
	dispatcher.Stop()

	offsets := rec.offsetsFor("orders.events", 0)
	require.NotEmpty(t, offsets, "resolved contiguous offsets must commit after offset 100 finishes")
	assert.Equal(t, int64(101), offsets[len(offsets)-1], "highest committed offset must advance monotonically to 101")
	for i := 1; i < len(offsets); i++ {
		assert.Greater(t, offsets[i], offsets[i-1], "committed offsets must be strictly increasing")
	}
}

func TestStreamDispatcherDoesNotCommitPastFailedOrPanickedOffset(t *testing.T) {
	tests := []struct {
		name         string
		failOffset   int64
		triggerPanic bool
	}{
		{name: "handler error blocks later offset commit", failOffset: 100},
		{name: "handler panic blocks later offset commit", failOffset: 100, triggerPanic: true},
	}

	for _, tc := range tests {
		t.Run(tc.name, func(t *testing.T) {
			cfg := StreamConfig{
				BufferSize:     16,
				HighWatermark:  12,
				LowWatermark:   4,
				WorkerCount:    4,
				PauseCheckWait: 2 * time.Millisecond,
			}

			started99 := make(chan struct{})
			release99 := make(chan struct{})
			finished100 := make(chan struct{})
			finished101 := make(chan struct{})

			handler := func(_ context.Context, msg kafka.Message) error {
				if msg.Partition == 0 {
					switch msg.Offset {
					case 99:
						close(started99)
						<-release99
					case tc.failOffset:
						defer close(finished100)
						if tc.triggerPanic {
							panic("boom")
						}
						return errors.New("permanent processing error")
					case 101:
						defer close(finished101)
					}
				}
				return nil
			}

			rec := &commitRecorder{}
			dispatcher := NewStreamDispatcher(cfg, nil, handler, rec.commit)
			ctx, cancel := context.WithCancel(context.Background())
			defer cancel()
			dispatcher.Start(ctx)

			require.NoError(t, dispatcher.Submit(ctx, kafka.Message{Topic: "orders.events", Partition: 0, Offset: 99}))
			<-started99
			require.NoError(t, dispatcher.Submit(ctx, kafka.Message{Topic: "orders.events", Partition: 0, Offset: 100}))
			require.NoError(t, dispatcher.Submit(ctx, kafka.Message{Topic: "orders.events", Partition: 0, Offset: 101}))
			require.NoError(t, dispatcher.Submit(ctx, kafka.Message{Topic: "orders.events", Partition: 1, Offset: 100}))

			<-finished100
			<-finished101
			assert.Empty(t, rec.offsetsFor("orders.events", 0), "no commit on partition 0 while offset 99 is still pending")

			close(release99)
			dispatcher.Stop()

			assert.Equal(t, []int64{99}, rec.offsetsFor("orders.events", 0), "partition 0 must commit 99 and never advance past failed offset 100")
			assert.Equal(t, []int64{100}, rec.offsetsFor("orders.events", 1), "partition 1 commits must stay independent from partition 0 failure")
		})
	}
}

func TestStreamDispatcherSkipRespectsInFlightAndFailedOffsets(t *testing.T) {
	cfg := StreamConfig{
		BufferSize:     16,
		HighWatermark:  12,
		LowWatermark:   4,
		WorkerCount:    2,
		PauseCheckWait: 2 * time.Millisecond,
	}

	started100 := make(chan struct{})
	release100 := make(chan struct{})
	handler := func(_ context.Context, msg kafka.Message) error {
		if msg.Offset == 100 {
			close(started100)
			<-release100
		}
		if msg.Offset == 102 {
			return errors.New("offset 102 failed")
		}
		return nil
	}

	rec := &commitRecorder{}
	dispatcher := NewStreamDispatcher(cfg, nil, handler, rec.commit)
	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()
	dispatcher.Start(ctx)

	require.NoError(t, dispatcher.Submit(ctx, kafka.Message{Topic: "orders.events", Partition: 0, Offset: 100}))
	<-started100
	require.NoError(t, dispatcher.Skip(ctx, kafka.Message{Topic: "orders.events", Partition: 0, Offset: 101}))
	assert.Empty(t, rec.offsetsFor("orders.events", 0), "skipped offset 101 must wait for in-flight offset 100")

	close(release100)
	require.Eventually(t, func() bool {
		offsets := rec.offsetsFor("orders.events", 0)
		return len(offsets) > 0 && offsets[len(offsets)-1] == 101
	}, time.Second, 5*time.Millisecond, "completing offset 100 must advance commit through skipped offset 101")

	require.NoError(t, dispatcher.Submit(ctx, kafka.Message{Topic: "orders.events", Partition: 0, Offset: 102}))
	require.NoError(t, dispatcher.Skip(ctx, kafka.Message{Topic: "orders.events", Partition: 0, Offset: 103}))
	dispatcher.Stop()

	offsets := rec.offsetsFor("orders.events", 0)
	assert.Equal(t, []int64{101}, offsets, "skipped offset 103 must not commit past failed offset 102")
}

func TestStreamDispatcherConcurrentOutOfOrderCompletionIsMonotonic(t *testing.T) {
	cfg := StreamConfig{
		BufferSize:     64,
		HighWatermark:  48,
		LowWatermark:   16,
		WorkerCount:    8,
		PauseCheckWait: 2 * time.Millisecond,
	}

	handler := func(_ context.Context, msg kafka.Message) error {
		if msg.Offset%3 == 0 {
			time.Sleep(3 * time.Millisecond)
		}
		return nil
	}

	rec := &commitRecorder{}
	dispatcher := NewStreamDispatcher(cfg, nil, handler, rec.commit)
	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()
	dispatcher.Start(ctx)

	const total = 100
	for p := 0; p < 3; p++ {
		for off := int64(0); off < total; off++ {
			require.NoError(t, dispatcher.Submit(ctx, kafka.Message{
				Topic:     "orders.events",
				Partition: p,
				Offset:    off,
			}))
		}
	}

	dispatcher.Stop()

	for p := 0; p < 3; p++ {
		offsets := rec.offsetsFor("orders.events", p)
		require.NotEmpty(t, offsets, "partition %d must have committed offsets", p)
		assert.Equal(t, int64(total-1), offsets[len(offsets)-1], "partition %d must commit up to final offset", p)
		for i := 1; i < len(offsets); i++ {
			assert.Greater(t, offsets[i], offsets[i-1], "partition %d commits must be strictly monotonic", p)
		}
	}
}
