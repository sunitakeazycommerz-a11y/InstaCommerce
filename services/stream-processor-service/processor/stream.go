package processor

import (
	"context"
	"errors"
	"fmt"
	"log/slog"
	"sync"
	"sync/atomic"
	"time"

	"github.com/instacommerce/stream-processor-service/dedup"
	"github.com/segmentio/kafka-go"
)

// StreamConfig holds configuration for bounded channel stream processing with backpressure.
type StreamConfig struct {
	BufferSize      int
	HighWatermark   int
	LowWatermark    int
	WorkerCount     int
	PauseCheckWait  time.Duration
	AllowedLateness time.Duration
	MaxFutureSkew   time.Duration
	DropLateEvents  bool
	DLQHandler      func(context.Context, kafka.Message, error) error
}

// DefaultStreamConfig returns production defaults: 1000 buffer, pause at 80%, resume at 30%, 10 workers.
func DefaultStreamConfig() StreamConfig {
	return StreamConfig{
		BufferSize:      1000,
		HighWatermark:   800,
		LowWatermark:    300,
		WorkerCount:     10,
		PauseCheckWait:  10 * time.Millisecond,
		AllowedLateness: DefaultAllowedLateness,
		MaxFutureSkew:   DefaultMaxFutureSkew,
	}
}

// ExtractMessageEventID extracts event_id / eventId / id from a Kafka message JSON envelope.
func ExtractMessageEventID(msg kafka.Message) string {
	return dedup.ExtractEventID(msg.Value)
}

// StreamDispatcher orchestrates bounded message queuing, worker concurrency, backpressure, watermarks, and monotonic per-partition offset commits.
type StreamDispatcher struct {
	cfg         StreamConfig
	ch          chan queuedMessage
	logger      *slog.Logger
	paused      atomic.Bool
	pauseCount  atomic.Int64
	resumeCount atomic.Int64
	lateCount   atomic.Int64
	handler     func(context.Context, kafka.Message) error
	committer   func(context.Context, kafka.Message) error
	tracker     *offsetTracker
	watermarks  *WatermarkTracker
	stopOnce    sync.Once
	wg          sync.WaitGroup
}

type queuedMessage struct {
	msg   kafka.Message
	entry *trackedOffset
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
	if cfg.AllowedLateness <= 0 {
		cfg.AllowedLateness = DefaultAllowedLateness
	}
	if cfg.MaxFutureSkew <= 0 {
		cfg.MaxFutureSkew = DefaultMaxFutureSkew
	}
	if logger == nil {
		logger = slog.Default()
	}

	return &StreamDispatcher{
		cfg:        cfg,
		ch:         make(chan queuedMessage, cfg.BufferSize),
		logger:     logger,
		handler:    handler,
		committer:  committer,
		tracker:    newOffsetTracker(),
		watermarks: NewWatermarkTrackerWithSkew(cfg.AllowedLateness, cfg.MaxFutureSkew),
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
	sd.stopOnce.Do(func() {
		close(sd.ch)
	})
	sd.wg.Wait()
}

func (sd *StreamDispatcher) worker(ctx context.Context, _ int) {
	defer sd.wg.Done()
	for qm := range sd.ch {
		workCtx := ctx
		var cancel context.CancelFunc
		if workCtx.Err() != nil {
			workCtx, cancel = context.WithTimeout(context.Background(), 5*time.Second)
		}
		sd.processWithRecovery(workCtx, qm)
		if cancel != nil {
			cancel()
		}
	}
}

func (sd *StreamDispatcher) processWithRecovery(ctx context.Context, qm queuedMessage) {
	succeeded := sd.runHandlerSafely(ctx, qm.msg)
	_ = sd.tracker.resolve(ctx, qm.msg, qm.entry, succeeded, sd.commit)
}

func (sd *StreamDispatcher) runHandlerSafely(ctx context.Context, msg kafka.Message) (succeeded bool) {
	var handlerErr error
	defer func() {
		if r := recover(); r != nil {
			handlerErr = fmt.Errorf("panic: %v", r)
			sd.logger.Error("recovered from panic in stream worker",
				slog.Any("panic", r),
				slog.String("topic", msg.Topic),
				slog.Int("partition", msg.Partition),
				slog.Int64("offset", msg.Offset),
			)
			if sd.cfg.DLQHandler != nil {
				if dlqErr := sd.cfg.DLQHandler(ctx, msg, handlerErr); dlqErr == nil {
					succeeded = true
					return
				}
			}
			succeeded = false
		}
	}()

	if evTime := ExtractMessageEventTime(msg); !evTime.IsZero() && sd.watermarks != nil {
		decision := sd.watermarks.ObserveWithAction(streamPartitionKey(msg), evTime, sd.cfg.DropLateEvents)
		if decision.FutureSkewed {
			sd.logger.Warn("ignored future-skewed event timestamp for watermark",
				slog.String("topic", msg.Topic),
				slog.Int("partition", msg.Partition),
				slog.Int64("offset", msg.Offset),
				slog.Time("event_time", decision.EventTime),
				slog.Time("watermark", decision.Watermark),
			)
		} else if decision.Late {
			sd.lateCount.Add(1)
			sd.logger.Warn("late event observed behind stream watermark",
				slog.String("topic", msg.Topic),
				slog.Int("partition", msg.Partition),
				slog.Int64("offset", msg.Offset),
				slog.Time("event_time", decision.EventTime),
				slog.Time("watermark", decision.Watermark),
				slog.Duration("lateness", decision.Lateness),
				slog.Bool("dropped", sd.cfg.DropLateEvents),
			)
			if sd.cfg.DropLateEvents {
				return true
			}
		}
	}

	if err := sd.handler(ctx, msg); err != nil {
		handlerErr = err
		sd.logger.Error("stream handler error",
			slog.String("topic", msg.Topic),
			slog.Int("partition", msg.Partition),
			slog.Int64("offset", msg.Offset),
			slog.String("error", err.Error()),
		)
		if sd.cfg.DLQHandler != nil {
			if dlqErr := sd.cfg.DLQHandler(ctx, msg, err); dlqErr == nil {
				return true
			}
		}
		return false
	}
	return true
}

// AckFailedOffset marks a previously failed offset as handled so partition offset commits can advance past it.
func (sd *StreamDispatcher) AckFailedOffset(ctx context.Context, msg kafka.Message) error {
	return sd.tracker.ackFailed(ctx, msg, sd.commit)
}

func (sd *StreamDispatcher) commit(ctx context.Context, msg kafka.Message) error {
	if sd.committer == nil {
		return nil
	}
	err := sd.committer(ctx, msg)
	if err != nil && !errors.Is(err, context.Canceled) {
		sd.logger.Error("commit error in stream worker",
			slog.String("topic", msg.Topic),
			slog.Int("partition", msg.Partition),
			slog.Int64("offset", msg.Offset),
			slog.String("error", err.Error()),
		)
	}
	return err
}

// Skip records a skipped duplicate message in partition order and commits it once all prior dispatched offsets complete.
func (sd *StreamDispatcher) Skip(ctx context.Context, msg kafka.Message) error {
	return sd.tracker.skip(ctx, msg, sd.commit)
}

// Submit enqueues a message, applying backpressure if buffer utilization reaches HighWatermark (80%).
func (sd *StreamDispatcher) Submit(ctx context.Context, msg kafka.Message) error {
	var ticker *time.Ticker
	defer func() {
		if ticker != nil {
			ticker.Stop()
		}
	}()

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
				if ticker == nil {
					ticker = time.NewTicker(sd.cfg.PauseCheckWait)
				}
				select {
				case <-ctx.Done():
					return ctx.Err()
				case <-ticker.C:
					continue
				}
			}
		}

		if err := ctx.Err(); err != nil {
			return err
		}

		entry := sd.tracker.enqueue(msg)
		select {
		case <-ctx.Done():
			sd.tracker.cancel(msg, entry)
			return ctx.Err()
		case sd.ch <- queuedMessage{msg: msg, entry: entry}:
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

// LateEventCount returns total events observed behind the stream watermark.
func (sd *StreamDispatcher) LateEventCount() int64 {
	return sd.lateCount.Load()
}

// WatermarkTracker exposes the underlying event-time watermark tracker.
func (sd *StreamDispatcher) WatermarkTracker() *WatermarkTracker {
	return sd.watermarks
}

// QueueDepth returns the current number of buffered messages.
func (sd *StreamDispatcher) QueueDepth() int {
	return len(sd.ch)
}
