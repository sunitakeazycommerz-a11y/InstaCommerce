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

// StreamDispatcher orchestrates bounded message queuing, worker concurrency, backpressure, and monotonic per-partition offset commits.
type StreamDispatcher struct {
	cfg         StreamConfig
	ch          chan queuedMessage
	logger      *slog.Logger
	paused      atomic.Bool
	pauseCount  atomic.Int64
	resumeCount atomic.Int64
	handler     func(context.Context, kafka.Message) error
	committer   func(context.Context, kafka.Message) error
	tracker     *offsetTracker
	wg          sync.WaitGroup
}

type queuedMessage struct {
	msg   kafka.Message
	entry *trackedOffset
}

type topicPartition struct {
	topic     string
	partition int
}

type offsetStatus uint8

const (
	offsetPending offsetStatus = iota
	offsetCompleted
	offsetFailed
)

type trackedOffset struct {
	msg       kafka.Message
	status    offsetStatus
	discarded bool
}

type partitionTracker struct {
	mu            sync.Mutex
	queue         []*trackedOffset
	failedSeen    bool
	readyToCommit *kafka.Message
	committing    bool
}

type offsetTracker struct {
	mu         sync.Mutex
	partitions map[topicPartition]*partitionTracker
}

func newOffsetTracker() *offsetTracker {
	return &offsetTracker{
		partitions: make(map[topicPartition]*partitionTracker),
	}
}

func (ot *offsetTracker) partition(msg kafka.Message) *partitionTracker {
	key := topicPartition{topic: msg.Topic, partition: msg.Partition}
	ot.mu.Lock()
	pt, ok := ot.partitions[key]
	if !ok {
		pt = &partitionTracker{}
		ot.partitions[key] = pt
	}
	ot.mu.Unlock()
	return pt
}

func (ot *offsetTracker) enqueue(msg kafka.Message) *trackedOffset {
	pt := ot.partition(msg)
	pt.mu.Lock()
	defer pt.mu.Unlock()
	if pt.failedSeen {
		return nil
	}
	entry := &trackedOffset{msg: msg, status: offsetPending}
	pt.queue = append(pt.queue, entry)
	return entry
}

func (ot *offsetTracker) cancel(msg kafka.Message, entry *trackedOffset) {
	if entry == nil {
		return
	}
	pt := ot.partition(msg)
	pt.mu.Lock()
	defer pt.mu.Unlock()
	if entry.discarded {
		return
	}
	for i := len(pt.queue) - 1; i >= 0; i-- {
		if pt.queue[i] == entry {
			copy(pt.queue[i:], pt.queue[i+1:])
			pt.queue[len(pt.queue)-1] = nil
			pt.queue = pt.queue[:len(pt.queue)-1]
			entry.discarded = true
			return
		}
	}
}

func (ot *offsetTracker) resolve(
	ctx context.Context,
	msg kafka.Message,
	entry *trackedOffset,
	succeeded bool,
	commitFn func(context.Context, kafka.Message) error,
) error {
	if entry == nil {
		return nil
	}
	pt := ot.partition(msg)
	pt.mu.Lock()
	if entry.discarded {
		pt.mu.Unlock()
		return nil
	}
	if !succeeded {
		entry.status = offsetFailed
		pt.failedSeen = true
		for i, item := range pt.queue {
			if item == entry {
				for j := i + 1; j < len(pt.queue); j++ {
					pt.queue[j].discarded = true
					pt.queue[j] = nil
				}
				pt.queue = pt.queue[:i+1]
				break
			}
		}
		pt.mu.Unlock()
		return nil
	}
	entry.status = offsetCompleted
	return pt.commitReady(ctx, commitFn)
}

func (ot *offsetTracker) skip(
	ctx context.Context,
	msg kafka.Message,
	commitFn func(context.Context, kafka.Message) error,
) error {
	pt := ot.partition(msg)
	pt.mu.Lock()
	if pt.failedSeen {
		pt.mu.Unlock()
		return nil
	}
	entry := &trackedOffset{msg: msg, status: offsetCompleted}
	pt.queue = append(pt.queue, entry)
	return pt.commitReady(ctx, commitFn)
}

func (pt *partitionTracker) commitReady(
	ctx context.Context,
	commitFn func(context.Context, kafka.Message) error,
) error {
	if !pt.advanceAndCheckFlush(commitFn != nil) {
		return nil
	}
	return pt.flushCommits(ctx, commitFn)
}

// advanceAndCheckFlush expects pt.mu held on entry and always unlocks it before returning.
func (pt *partitionTracker) advanceAndCheckFlush(hasCommitter bool) bool {
	defer pt.mu.Unlock()
	pt.advanceLocked()
	if !hasCommitter {
		pt.readyToCommit = nil
		return false
	}
	if pt.readyToCommit == nil || pt.committing {
		return false
	}
	pt.committing = true
	return true
}

func (pt *partitionTracker) advanceLocked() {
	popped := 0
	for popped < len(pt.queue) && pt.queue[popped].status == offsetCompleted {
		msg := pt.queue[popped].msg
		pt.readyToCommit = &msg
		pt.queue[popped] = nil
		popped++
	}
	if popped == 0 {
		return
	}
	remaining := len(pt.queue) - popped
	if remaining == 0 {
		if cap(pt.queue) > 1024 {
			pt.queue = nil
		} else {
			pt.queue = pt.queue[:0]
		}
		return
	}
	copy(pt.queue, pt.queue[popped:])
	clear(pt.queue[remaining:])
	pt.queue = pt.queue[:remaining]
}

func (pt *partitionTracker) takeReadyCommit() (kafka.Message, bool) {
	pt.mu.Lock()
	defer pt.mu.Unlock()
	if pt.readyToCommit == nil {
		pt.committing = false
		return kafka.Message{}, false
	}
	msg := *pt.readyToCommit
	pt.readyToCommit = nil
	return msg, true
}

func (pt *partitionTracker) flushCommits(ctx context.Context, commitFn func(context.Context, kafka.Message) error) error {
	completed := false
	defer func() {
		if !completed {
			pt.mu.Lock()
			pt.committing = false
			pt.mu.Unlock()
		}
	}()

	var lastErr error
	for {
		msg, ok := pt.takeReadyCommit()
		if !ok {
			completed = true
			return lastErr
		}
		lastErr = commitFn(ctx, msg)
	}
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
		ch:        make(chan queuedMessage, cfg.BufferSize),
		logger:    logger,
		handler:   handler,
		committer: committer,
		tracker:   newOffsetTracker(),
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
		case qm, ok := <-sd.ch:
			if !ok {
				return
			}
			sd.processWithRecovery(ctx, qm)
		}
	}
}

func (sd *StreamDispatcher) processWithRecovery(ctx context.Context, qm queuedMessage) {
	succeeded := sd.runHandlerSafely(ctx, qm.msg)
	_ = sd.tracker.resolve(ctx, qm.msg, qm.entry, succeeded, sd.commit)
}

func (sd *StreamDispatcher) runHandlerSafely(ctx context.Context, msg kafka.Message) (succeeded bool) {
	defer func() {
		if r := recover(); r != nil {
			succeeded = false
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
		return false
	}
	return true
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

// Skip records a skipped duplicate message in partition order and commits it once all prior dispatched offsets on that partition complete.
func (sd *StreamDispatcher) Skip(ctx context.Context, msg kafka.Message) error {
	return sd.tracker.skip(ctx, msg, sd.commit)
}

// Submit enqueues a message, applying backpressure if buffer utilization reaches HighWatermark (80%).
// While paused, Submit blocks the calling consumer goroutine until workers drain the buffer below LowWatermark (30%).
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

		// Register in partition order before the message becomes visible to workers.
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

// QueueDepth returns the current number of buffered messages.
func (sd *StreamDispatcher) QueueDepth() int {
	return len(sd.ch)
}
