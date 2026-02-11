package handler

import (
	"context"
	"encoding/json"
	"fmt"
	"log/slog"
	"sync"
	"time"
)

// KafkaProducer abstracts Kafka message publishing so the batcher can be
// tested without a live broker.
type KafkaProducer interface {
	// PublishBatch sends a batch of keyed messages to the given topic.
	// Implementations must respect ctx cancellation.
	PublishBatch(ctx context.Context, topic string, messages []KafkaMessage) error
}

// KafkaMessage is a key-value pair destined for Kafka.
type KafkaMessage struct {
	Key   []byte
	Value []byte
}

// LocationBatcher accumulates GPS pings and flushes them to Kafka either when
// maxSize messages are buffered or when maxWait elapses — whichever comes
// first. The default configuration targets 50 messages / 100 ms for
// low-latency delivery tracking.
type LocationBatcher struct {
	mu       sync.Mutex
	buffer   []LocationUpdate
	maxSize  int
	maxWait  time.Duration
	producer KafkaProducer
	topic    string
	logger   *slog.Logger
	notify   chan struct{} // signals that buffer may be full
}

// NewLocationBatcher creates a LocationBatcher. The batcher does not start
// flushing until Start is called.
func NewLocationBatcher(
	producer KafkaProducer,
	topic string,
	maxSize int,
	maxWait time.Duration,
	logger *slog.Logger,
) (*LocationBatcher, error) {
	if producer == nil {
		return nil, fmt.Errorf("batcher: KafkaProducer must not be nil")
	}
	if topic == "" {
		return nil, fmt.Errorf("batcher: topic must not be empty")
	}
	if maxSize <= 0 {
		maxSize = 50
	}
	if maxWait <= 0 {
		maxWait = 100 * time.Millisecond
	}
	if logger == nil {
		return nil, fmt.Errorf("batcher: logger must not be nil")
	}
	return &LocationBatcher{
		buffer:   make([]LocationUpdate, 0, maxSize),
		maxSize:  maxSize,
		maxWait:  maxWait,
		producer: producer,
		topic:    topic,
		logger:   logger,
		notify:   make(chan struct{}, 1),
	}, nil
}

// Add appends an update to the internal buffer. If the buffer reaches maxSize
// the flush loop is notified immediately. Add is safe for concurrent use.
func (b *LocationBatcher) Add(update LocationUpdate) {
	b.mu.Lock()
	b.buffer = append(b.buffer, update)
	shouldNotify := len(b.buffer) >= b.maxSize
	b.mu.Unlock()

	if shouldNotify {
		select {
		case b.notify <- struct{}{}:
		default:
		}
	}
}

// Start runs the flush loop until ctx is cancelled. After cancellation it
// performs a final drain of any buffered updates. Start is blocking; call it in
// a separate goroutine.
func (b *LocationBatcher) Start(ctx context.Context) {
	ticker := time.NewTicker(b.maxWait)
	defer ticker.Stop()

	for {
		select {
		case <-ctx.Done():
			b.flush(context.Background()) // best-effort final flush
			return
		case <-ticker.C:
			b.flush(ctx)
		case <-b.notify:
			b.flush(ctx)
		}
	}
}

// flush drains the buffer and publishes to Kafka.
func (b *LocationBatcher) flush(ctx context.Context) {
	b.mu.Lock()
	if len(b.buffer) == 0 {
		b.mu.Unlock()
		return
	}
	batch := b.buffer
	b.buffer = make([]LocationUpdate, 0, b.maxSize)
	b.mu.Unlock()

	messages := make([]KafkaMessage, 0, len(batch))
	for i := range batch {
		payload, err := json.Marshal(&batch[i])
		if err != nil {
			b.logger.Error("failed to marshal location update",
				"rider_id", batch[i].RiderID, "error", err)
			continue
		}
		messages = append(messages, KafkaMessage{
			Key:   []byte(batch[i].RiderID),
			Value: payload,
		})
	}
	if len(messages) == 0 {
		return
	}

	if err := b.producer.PublishBatch(ctx, b.topic, messages); err != nil {
		b.logger.Error("kafka batch publish failed",
			"count", len(messages), "error", err)
	}
}
