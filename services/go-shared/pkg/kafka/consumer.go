package kafka

import (
	"context"
	"errors"
	"fmt"
	"log/slog"
	"time"

	kafkago "github.com/segmentio/kafka-go"
)

// ConsumerConfig holds all tunables for the Kafka consumer group.
type ConsumerConfig struct {
	// Brokers is the list of Kafka broker addresses (host:port).
	Brokers []string
	// GroupID is the consumer group identifier.
	GroupID string
	// Topics to subscribe to.
	Topics []string
	// MinBytes is the minimum batch size the broker should return (default 1 KB).
	MinBytes int
	// MaxBytes is the maximum batch size the broker should return (default 10 MB).
	MaxBytes int
	// MaxWait is the maximum time the broker waits before returning a batch.
	MaxWait time.Duration
}

// Message is a simplified representation of a consumed Kafka message.
type Message struct {
	Topic     string
	Partition int
	Offset    int64
	Key       []byte
	Value     []byte
	Headers   map[string]string
	Timestamp time.Time
}

// MessageHandler is the callback invoked for each consumed message.
// Returning a non-nil error prevents the message offset from being committed,
// causing redelivery on the next poll.
type MessageHandler func(ctx context.Context, msg Message) error

// Consumer manages a Kafka consumer group with automatic offset commits and
// graceful shutdown. It processes messages sequentially per partition within
// a single goroutine, which keeps the handler free from concurrency concerns.
type Consumer struct {
	reader  *kafkago.Reader
	handler MessageHandler
	logger  *slog.Logger
}

// NewConsumer creates a Kafka consumer group reader. Call Start to begin
// consuming messages and Close to shut down gracefully.
func NewConsumer(cfg ConsumerConfig, handler MessageHandler, logger *slog.Logger) (*Consumer, error) {
	if len(cfg.Brokers) == 0 {
		return nil, fmt.Errorf("kafka consumer: at least one broker address is required")
	}
	if cfg.GroupID == "" {
		return nil, fmt.Errorf("kafka consumer: GroupID is required")
	}
	if len(cfg.Topics) == 0 {
		return nil, fmt.Errorf("kafka consumer: at least one topic is required")
	}
	if handler == nil {
		return nil, fmt.Errorf("kafka consumer: handler must not be nil")
	}

	if cfg.MinBytes == 0 {
		cfg.MinBytes = 1 << 10 // 1 KB
	}
	if cfg.MaxBytes == 0 {
		cfg.MaxBytes = 10 << 20 // 10 MB
	}
	if cfg.MaxWait == 0 {
		cfg.MaxWait = 3 * time.Second
	}

	reader := kafkago.NewReader(kafkago.ReaderConfig{
		Brokers:        cfg.Brokers,
		GroupID:        cfg.GroupID,
		GroupTopics:    cfg.Topics,
		MinBytes:       cfg.MinBytes,
		MaxBytes:       cfg.MaxBytes,
		MaxWait:        cfg.MaxWait,
		CommitInterval: time.Second,
		StartOffset:    kafkago.LastOffset,
	})

	logger.Info("kafka consumer initialised",
		slog.String("group_id", cfg.GroupID),
		slog.Any("topics", cfg.Topics),
		slog.Any("brokers", cfg.Brokers),
	)

	return &Consumer{reader: reader, handler: handler, logger: logger}, nil
}

// Start blocks while consuming messages until ctx is cancelled. On context
// cancellation it returns nil; all other errors are propagated to the caller.
func (c *Consumer) Start(ctx context.Context) error {
	c.logger.Info("kafka consumer starting")

	for {
		msg, err := c.reader.FetchMessage(ctx)
		if err != nil {
			if errors.Is(err, context.Canceled) || errors.Is(err, context.DeadlineExceeded) {
				c.logger.Info("kafka consumer stopping: context done")
				return nil
			}
			c.logger.Error("error fetching message", slog.String("error", err.Error()))
			return fmt.Errorf("kafka consumer fetch: %w", err)
		}

		m := Message{
			Topic:     msg.Topic,
			Partition: msg.Partition,
			Offset:    msg.Offset,
			Key:       msg.Key,
			Value:     msg.Value,
			Headers:   headersToMap(msg.Headers),
			Timestamp: msg.Time,
		}

		if err := c.handler(ctx, m); err != nil {
			c.logger.Error("message handler failed",
				slog.String("topic", m.Topic),
				slog.Int("partition", m.Partition),
				slog.Int64("offset", m.Offset),
				slog.String("error", err.Error()),
			)
			// Do not commit; the message will be redelivered.
			continue
		}

		if err := c.reader.CommitMessages(ctx, msg); err != nil {
			c.logger.Error("failed to commit offset",
				slog.String("topic", m.Topic),
				slog.Int("partition", m.Partition),
				slog.Int64("offset", m.Offset),
				slog.String("error", err.Error()),
			)
			return fmt.Errorf("kafka consumer commit: %w", err)
		}

		c.logger.Debug("message processed",
			slog.String("topic", m.Topic),
			slog.Int("partition", m.Partition),
			slog.Int64("offset", m.Offset),
		)
	}
}

// Close shuts down the consumer group reader, triggering a rebalance.
func (c *Consumer) Close() error {
	c.logger.Info("closing kafka consumer")
	if err := c.reader.Close(); err != nil {
		return fmt.Errorf("closing kafka consumer: %w", err)
	}
	return nil
}

// headersToMap converts kafka-go headers to a simple string map.
func headersToMap(headers []kafkago.Header) map[string]string {
	if len(headers) == 0 {
		return nil
	}
	m := make(map[string]string, len(headers))
	for _, h := range headers {
		m[h.Key] = string(h.Value)
	}
	return m
}
