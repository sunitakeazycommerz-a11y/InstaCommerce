package handler

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"log/slog"
	"strings"
	"sync"
	"time"

	"github.com/segmentio/kafka-go"
)

var (
	// ErrKafkaDisabled indicates no Kafka producer is configured on the batcher.
	ErrKafkaDisabled = errors.New("kafka disabled")
	// ErrKafkaBufferFull indicates the batcher's bounded in-memory buffer is at capacity.
	ErrKafkaBufferFull = errors.New("kafka buffer full")
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

// KafkaWriterProducer adapts a *kafka.Writer to KafkaProducer.
type KafkaWriterProducer struct {
	writer *kafka.Writer
}

// NewKafkaWriterProducer wraps a *kafka.Writer as a KafkaProducer.
func NewKafkaWriterProducer(writer *kafka.Writer) *KafkaWriterProducer {
	return &KafkaWriterProducer{writer: writer}
}

// PublishBatch writes keyed messages using the underlying *kafka.Writer.
func (p *KafkaWriterProducer) PublishBatch(ctx context.Context, topic string, messages []KafkaMessage) error {
	if p == nil || p.writer == nil {
		return ErrKafkaDisabled
	}
	topic = strings.TrimSpace(topic)
	if p.writer.Topic != "" && topic != "" && p.writer.Topic != topic {
		return fmt.Errorf("kafka writer topic %q does not match target topic %q", p.writer.Topic, topic)
	}
	if len(messages) == 0 {
		return nil
	}
	msgTopic := ""
	if p.writer.Topic == "" {
		msgTopic = topic
	}
	kafkaMsgs := make([]kafka.Message, 0, len(messages))
	now := time.Now().UTC()
	for _, m := range messages {
		kafkaMsgs = append(kafkaMsgs, kafka.Message{
			Topic: msgTopic,
			Key:   m.Key,
			Value: m.Value,
			Time:  now,
		})
	}
	return p.writer.WriteMessages(ctx, kafkaMsgs...)
}

// LocationEvent is the canonical JSON payload emitted to the rider.location.updates topic.
type LocationEvent struct {
	RiderID        string   `json:"rider_id"`
	Lat            float64  `json:"lat"`
	Lng            float64  `json:"lng"`
	TimestampMS    int64    `json:"timestamp_ms"`
	Speed          *float64 `json:"speed,omitempty"`
	Heading        *float64 `json:"heading,omitempty"`
	Accuracy       *float64 `json:"accuracy,omitempty"`
	BatteryPct     *float64 `json:"battery_pct,omitempty"`
	H3Index        string   `json:"h3_index,omitempty"`
	GeofenceHit    bool     `json:"geofence_hit,omitempty"`
	LowBattery     bool     `json:"low_battery,omitempty"`
	WeakAccuracy   bool     `json:"weak_accuracy,omitempty"`
	TelemetryFlags []string `json:"telemetry_flags,omitempty"`
	Source         string   `json:"source,omitempty"`
	IngestedAt     string   `json:"ingested_at"`
}

// LocationBatcherOption configures optional behavior on LocationBatcher.
type LocationBatcherOption func(*LocationBatcher)

// WithBatchWriteTimeout sets the timeout for each PublishBatch attempt.
func WithBatchWriteTimeout(d time.Duration) LocationBatcherOption {
	return func(b *LocationBatcher) {
		if d > 0 {
			b.writeTimeout = d
		}
	}
}

// WithBatchBufferCapacity caps the number of buffered updates accepted by Enqueue.
func WithBatchBufferCapacity(capacity int) LocationBatcherOption {
	return func(b *LocationBatcher) {
		if capacity > 0 {
			b.maxBuffer = capacity
		}
	}
}

// WithBatchRetries sets how many times a failed PublishBatch is retried.
func WithBatchRetries(retries int) LocationBatcherOption {
	return func(b *LocationBatcher) {
		if retries > 0 {
			b.maxRetries = retries
		}
	}
}

// WithBatchDropHook registers a callback invoked when messages are dropped during flush.
func WithBatchDropHook(fn func(reason string, count int)) LocationBatcherOption {
	return func(b *LocationBatcher) {
		b.onDrop = fn
	}
}

// LocationBatcher accumulates GPS pings and flushes them to Kafka either when
// maxSize messages are buffered or when maxWait elapses — whichever comes
// first.
type LocationBatcher struct {
	mu           sync.Mutex
	buffer       []LocationUpdate
	maxSize      int
	maxBuffer    int
	maxWait      time.Duration
	writeTimeout time.Duration
	maxRetries   int
	producer     KafkaProducer
	topic        string
	logger       *slog.Logger
	onDrop       func(reason string, count int)
	notify       chan struct{} // signals that buffer may be full
}

// NewLocationBatcher creates a LocationBatcher. The batcher does not start
// flushing until Start is called.
func NewLocationBatcher(
	producer KafkaProducer,
	topic string,
	maxSize int,
	maxWait time.Duration,
	logger *slog.Logger,
	opts ...LocationBatcherOption,
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
	b := &LocationBatcher{
		buffer:       make([]LocationUpdate, 0, maxSize),
		maxSize:      maxSize,
		maxWait:      maxWait,
		writeTimeout: 5 * time.Second,
		maxRetries:   3,
		producer:     producer,
		topic:        topic,
		logger:       logger,
		notify:       make(chan struct{}, 1),
	}
	for _, opt := range opts {
		opt(b)
	}
	return b, nil
}

// Enqueue appends an update to the internal buffer, returning ErrKafkaBufferFull
// if a bounded buffer capacity is configured and exceeded.
func (b *LocationBatcher) Enqueue(update LocationUpdate) error {
	if b == nil || b.producer == nil {
		return ErrKafkaDisabled
	}
	if wp, ok := b.producer.(*KafkaWriterProducer); ok && (wp == nil || wp.writer == nil) {
		return ErrKafkaDisabled
	}

	b.mu.Lock()
	if b.maxBuffer > 0 && len(b.buffer) >= b.maxBuffer {
		b.mu.Unlock()
		return ErrKafkaBufferFull
	}
	b.buffer = append(b.buffer, update)
	shouldNotify := len(b.buffer) >= b.maxSize
	b.mu.Unlock()

	if shouldNotify {
		select {
		case b.notify <- struct{}{}:
		default:
		}
	}
	return nil
}

// Add appends an update to the internal buffer. If the buffer reaches maxSize
// the flush loop is notified immediately. Add is safe for concurrent use.
func (b *LocationBatcher) Add(update LocationUpdate) {
	_ = b.Enqueue(update)
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
			for {
				if !b.flush(context.Background()) {
					return
				}
			}
		case <-ticker.C:
			b.flush(ctx)
		case <-b.notify:
			b.flush(ctx)
		}
	}
}

// flush drains up to maxSize updates from the buffer and publishes them to Kafka.
// Returns true if more updates remain in the buffer.
func (b *LocationBatcher) flush(ctx context.Context) bool {
	b.mu.Lock()
	if len(b.buffer) == 0 {
		b.mu.Unlock()
		return false
	}
	n := len(b.buffer)
	if n > b.maxSize {
		n = b.maxSize
	}
	batch := make([]LocationUpdate, n)
	copy(batch, b.buffer[:n])
	remaining := len(b.buffer) - n
	if remaining > 0 {
		copy(b.buffer, b.buffer[n:])
		b.buffer = b.buffer[:remaining]
	} else {
		b.buffer = b.buffer[:0]
	}
	b.mu.Unlock()

	messages := make([]KafkaMessage, 0, len(batch))
	ingestedAt := time.Now().UTC().Format(time.RFC3339Nano)
	for i := range batch {
		ev := toLocationEvent(batch[i], ingestedAt)
		payload, err := json.Marshal(ev)
		if err != nil {
			b.logger.Error("failed to marshal location update",
				"rider_id", batch[i].RiderID, "error", err)
			if b.onDrop != nil {
				b.onDrop("marshal", 1)
			}
			continue
		}
		messages = append(messages, KafkaMessage{
			Key:   []byte(batch[i].RiderID),
			Value: payload,
		})
	}
	if len(messages) == 0 {
		return remaining > 0
	}

	attempts := b.maxRetries
	if attempts <= 0 {
		attempts = 1
	}
	var err error
	for attempt := 0; attempt < attempts; attempt++ {
		writeCtx := context.WithoutCancel(ctx)
		var cancel context.CancelFunc
		if b.writeTimeout > 0 {
			writeCtx, cancel = context.WithTimeout(writeCtx, b.writeTimeout)
		}
		err = b.producer.PublishBatch(writeCtx, b.topic, messages)
		if cancel != nil {
			cancel()
		}
		if err == nil {
			break
		}
		if attempt < attempts-1 {
			select {
			case <-ctx.Done():
				time.Sleep(50 * time.Millisecond)
			case <-time.After(time.Duration(50*(attempt+1)) * time.Millisecond):
			}
		}
	}
	if err != nil {
		b.logger.Error("kafka batch publish failed",
			"count", len(messages), "error", err)
		if b.onDrop != nil {
			b.onDrop("write", len(messages))
		}
	}
	return remaining > 0
}

func toLocationEvent(u LocationUpdate, ingestedAt string) LocationEvent {
	ev := LocationEvent{
		RiderID:        u.RiderID,
		Lat:            u.Lat,
		Lng:            u.Lng,
		TimestampMS:    u.Timestamp.UnixMilli(),
		H3Index:        u.H3Index,
		GeofenceHit:    u.Geofence,
		LowBattery:     u.LowBattery,
		WeakAccuracy:   u.WeakAccuracy,
		TelemetryFlags: u.TelemetryFlags,
		Source:         u.Source,
		IngestedAt:     ingestedAt,
	}
	if u.HasSpeed || u.Speed != 0 {
		v := u.Speed
		ev.Speed = &v
	}
	if u.HasHeading || u.Heading != 0 {
		v := u.Heading
		ev.Heading = &v
	}
	if u.HasAccuracy || u.Accuracy != 0 {
		v := u.Accuracy
		ev.Accuracy = &v
	}
	if u.HasBatteryPct || u.BatteryPct != 0 {
		v := u.BatteryPct
		ev.BatteryPct = &v
	}
	return ev
}
