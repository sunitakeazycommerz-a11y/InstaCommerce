package processor

import (
	"context"
	"errors"
	"log/slog"
	"sync"
	"sync/atomic"
	"time"

	"github.com/segmentio/kafka-go"
)

// StreamConfig holds configuration for bounded channel stream processing with backpressure.
type StreamConfig struct {
	BufferSize     int           // Total capacity of bounded channel (e.g. 1000)
	HighWatermark  int           // Capacity threshold to pause consumer (e.g. 800 or 80%)
	LowWatermark   int           // Capacity threshold to resume consumer (e.g. 300 or 30%)
	WorkerCount    int           // Number of concurrent workers (e.g. 10)
	PauseCheckWait time.Duration // Interval to check queue depth when paused (e.g. 10ms)
}

// DefaultStreamConfig returns production defaults: 1000 buffer, pause at 80%, resume at 30%, 10 workers.
func DefaultStreamConfig() StreamConfig {
	return StreamConfig{
		BufferSize:     1000,
		HighWatermark:  800,
		LowWatermark:   300,
		WorkerCount:    10,
		PauseCheckWait: 10 * time.Millisecond,
	}
}

// StreamDispatcher orchestrates bounded message queuing, worker concurrency, backpressure, and panic recovery.
type StreamDispatcher struct {
	cfg       StreamConfig
	ch        chan kafka.Message
	logger    *slog.Logger
	paused    atomic.Bool
	pauseCount atomic.Int64
	resumeCount atomic.Int64
	handler   func(context.Context, kafka.Message) error
	committer func(context.Context, kafka.Message) error
	wg        sync.WaitGroup
}

// NewStreamDispatcher creates a new dispatcher with bounded channel and backpressure.
func NewStreamDispatcher(
	cfg StreamConfig,
	logger *slog.Logger,
	handler func(context.Context, kafka.Message) error,
	committer func(context.Context, kafka.Message) error,
) *StreamDispatcher {
	if cfg.BufferSize <= 0 {
		cfg = DefaultStreamConfig()
	}
	if cfg.HighWatermark <= 0 || cfg.HighWatermark > cfg.BufferSize {
		cfg.HighWatermark = int(float64(cfg.BufferSize) * 0.8)
	}
	if cfg.LowWatermark <= 0 || cfg.LowWatermark >= cfg.HighWatermark {
		cfg.LowWatermark = int(float64(cfg.BufferSize) * 0.3)
	}
	if cfg.WorkerCount <= 0 {
		cfg.WorkerCount = 10
	}
	if cfg.PauseCheckWait <= 0 {
		cfg.PauseCheckWait = 10 * time.Millisecond
	}
	if logger == nil {
		logger = slog.Default()
	}

	return &StreamDispatcher{
		cfg:       cfg,
		ch:        make(chan kafka.Message, cfg.BufferSize),
		logger:    logger,
		handler:   handler,
		committer: committer,
	}
}

// Start launches the worker pool.
func (sd *StreamDispatcher) Start(ctx context.Context) {
	for i := 0; i < sd.cfg.WorkerCount; i++ {
		sd.wg.Add(1)
		go sd.worker(ctx, i)
	}
}

// Stop closes the message channel and waits for workers to drain remaining items.
func (sd *StreamDispatcher) Stop() {
	close(sd.ch)
	sd.wg.Wait()
}

func (sd *StreamDispatcher) worker(ctx context.Context, id int) {
	defer sd.wg.Done()
	for {
		select {
		case <-ctx.Done():
			return
		case msg, ok := <-sd.ch:
			if !ok {
				return
			}
			sd.processWithRecovery(ctx, msg)
		}
	}
}

func (sd *StreamDispatcher) processWithRecovery(ctx context.Context, msg kafka.Message) {
	defer func() {
		if r := recover(); r != nil {
			sd.logger.Error("recovered from panic in stream worker",
				slog.Any("panic", r),
				slog.String("topic", msg.Topic),
				slog.Int("partition", msg.Partition),
				slog.Int64("offset", msg.Offset),
			)
		}
	}()

	if err := sd.handler(ctx, msg); err != nil {
		sd.logger.Error("stream handler error",
			slog.String("topic", msg.Topic),
			slog.Int("partition", msg.Partition),
			slog.Int64("offset", msg.Offset),
			slog.String("error", err.Error()),
		)
		return
	}

	if sd.committer != nil {
		if err := sd.committer(ctx, msg); err != nil && !errors.Is(err, context.Canceled) {
			sd.logger.Error("commit error in stream worker",
				slog.String("topic", msg.Topic),
				slog.Int64("offset", msg.Offset),
				slog.String("error", err.Error()),
			)
		}
	}
}

// Submit enqueues a message, applying backpressure if buffer utilization reaches HighWatermark (80%).
// While paused, Submit blocks the calling consumer goroutine until workers drain the buffer below LowWatermark (30%).
func (sd *StreamDispatcher) Submit(ctx context.Context, msg kafka.Message) error {
	for {
		queueLen := len(sd.ch)
		if queueLen >= sd.cfg.HighWatermark {
			if !sd.paused.Swap(true) {
				sd.pauseCount.Add(1)
				sd.logger.Warn("backpressure: channel reached 80% capacity, pausing kafka consumer fetch",
					slog.Int("queue_len", queueLen),
					slog.Int("high_watermark", sd.cfg.HighWatermark),
					slog.Int("buffer_size", sd.cfg.BufferSize),
				)
			}
		}

		if sd.paused.Load() {
			if queueLen <= sd.cfg.LowWatermark {
				sd.paused.Store(false)
				sd.resumeCount.Add(1)
				sd.logger.Info("backpressure: channel drained below 30% capacity, resuming kafka consumer fetch",
					slog.Int("queue_len", queueLen),
					slog.Int("low_watermark", sd.cfg.LowWatermark),
				)
			} else {
				select {
				case <-ctx.Done():
					return ctx.Err()
				case <-time.After(sd.cfg.PauseCheckWait):
					continue
				}
			}
		}

		// Enqueue the message
		select {
		case <-ctx.Done():
			return ctx.Err()
		case sd.ch <- msg:
			if len(sd.ch) >= sd.cfg.HighWatermark {
				if !sd.paused.Swap(true) {
					sd.pauseCount.Add(1)
					sd.logger.Warn("backpressure: channel reached 80% capacity, pausing kafka consumer fetch",
						slog.Int("queue_len", len(sd.ch)),
						slog.Int("high_watermark", sd.cfg.HighWatermark),
						slog.Int("buffer_size", sd.cfg.BufferSize),
					)
				}
			}
			return nil
		}
	}
}

// IsPaused returns true if the dispatcher is currently under backpressure pause.
func (sd *StreamDispatcher) IsPaused() bool {
	return sd.paused.Load()
}

// PauseCount returns total times backpressure paused fetching.
func (sd *StreamDispatcher) PauseCount() int64 {
	return sd.pauseCount.Load()
}

// ResumeCount returns total times consumer was resumed.
func (sd *StreamDispatcher) ResumeCount() int64 {
	return sd.resumeCount.Load()
}

// QueueDepth returns the current number of buffered messages.
func (sd *StreamDispatcher) QueueDepth() int {
	return len(sd.ch)
}
