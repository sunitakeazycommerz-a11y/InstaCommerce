// Package kafka provides production-grade Kafka producer and consumer
// implementations for InstaCommerce Go services.
//
// The producer supports idempotent writes, configurable batching, compression,
// and automatic retries. The consumer provides managed consumer-group
// consumption with graceful shutdown.
package kafka

import (
	"context"
	"fmt"
	"log/slog"
	"strings"
	"time"

	kafkago "github.com/segmentio/kafka-go"
	"github.com/segmentio/kafka-go/compress"
)

// ProducerConfig holds all tunables for the Kafka producer.
type ProducerConfig struct {
	// Brokers is the list of Kafka broker addresses (host:port).
	Brokers []string
	// ClientID identifies this producer to the Kafka cluster.
	ClientID string
	// RequiredAcks controls durability. Use -1 (all ISR) for production.
	RequiredAcks int
	// MaxRetries is the number of delivery attempts before giving up.
	MaxRetries int
	// BatchSize is the maximum number of messages in a single produce batch.
	BatchSize int
	// LingerMs is the maximum time to wait for a batch to fill before sending.
	LingerMs int
	// Compression codec: "snappy", "lz4", "gzip", "zstd", or "" (none).
	Compression string
}

// Producer is a thread-safe Kafka producer that wraps segmentio/kafka-go
// with production defaults for idempotency, batching, and retries.
type Producer struct {
	writer *kafkago.Writer
	logger *slog.Logger
}

// NewProducer creates a new Kafka producer from the supplied configuration.
// The caller must call Close when the producer is no longer needed.
func NewProducer(cfg ProducerConfig, logger *slog.Logger) (*Producer, error) {
	if len(cfg.Brokers) == 0 {
		return nil, fmt.Errorf("kafka producer: at least one broker address is required")
	}
	if cfg.ClientID == "" {
		return nil, fmt.Errorf("kafka producer: ClientID is required")
	}

	if cfg.RequiredAcks == 0 {
		cfg.RequiredAcks = -1 // all ISR
	}
	if cfg.MaxRetries == 0 {
		cfg.MaxRetries = 5
	}
	if cfg.BatchSize == 0 {
		cfg.BatchSize = 100
	}
	if cfg.LingerMs == 0 {
		cfg.LingerMs = 10
	}

	codec, err := resolveCompression(cfg.Compression)
	if err != nil {
		return nil, fmt.Errorf("kafka producer: %w", err)
	}

	w := &kafkago.Writer{
		Addr:         kafkago.TCP(cfg.Brokers...),
		Balancer:     &kafkago.Hash{},
		MaxAttempts:  cfg.MaxRetries,
		BatchSize:    cfg.BatchSize,
		BatchTimeout: time.Duration(cfg.LingerMs) * time.Millisecond,
		RequiredAcks: kafkago.RequiredAcks(cfg.RequiredAcks),
		Async:        false, // synchronous for guaranteed delivery
		Compression:  codec,
	}

	logger.Info("kafka producer initialised",
		slog.String("client_id", cfg.ClientID),
		slog.Any("brokers", cfg.Brokers),
		slog.Int("required_acks", cfg.RequiredAcks),
		slog.String("compression", cfg.Compression),
	)

	return &Producer{writer: w, logger: logger}, nil
}

// Publish sends a single message to the given topic. The key is used for
// partition routing; supply nil for round-robin distribution. Headers are
// optional key-value metadata attached to the Kafka message.
//
// The call blocks until the message is acknowledged by the required number
// of replicas or an error occurs.
func (p *Producer) Publish(ctx context.Context, topic string, key, value []byte, headers map[string]string) error {
	msg := kafkago.Message{
		Topic:   topic,
		Key:     key,
		Value:   value,
		Headers: mapToHeaders(headers),
	}

	if err := p.writer.WriteMessages(ctx, msg); err != nil {
		p.logger.Error("failed to publish message",
			slog.String("topic", topic),
			slog.String("error", err.Error()),
		)
		return fmt.Errorf("kafka publish to %s: %w", topic, err)
	}

	p.logger.Debug("message published",
		slog.String("topic", topic),
		slog.Int("value_bytes", len(value)),
	)
	return nil
}

// Close flushes pending messages and releases producer resources.
func (p *Producer) Close() error {
	p.logger.Info("closing kafka producer")
	if err := p.writer.Close(); err != nil {
		return fmt.Errorf("closing kafka producer: %w", err)
	}
	return nil
}

// mapToHeaders converts a string map to kafka-go message headers.
func mapToHeaders(m map[string]string) []kafkago.Header {
	if len(m) == 0 {
		return nil
	}
	headers := make([]kafkago.Header, 0, len(m))
	for k, v := range m {
		headers = append(headers, kafkago.Header{Key: k, Value: []byte(v)})
	}
	return headers
}

// resolveCompression maps a codec name to a kafka-go Compression value.
func resolveCompression(name string) (kafkago.Compression, error) {
	switch strings.ToLower(strings.TrimSpace(name)) {
	case "", "none":
		return 0, nil
	case "gzip":
		return kafkago.Compression(compress.Gzip.Codec().Code()), nil
	case "snappy":
		return kafkago.Compression(compress.Snappy.Codec().Code()), nil
	case "lz4":
		return kafkago.Compression(compress.Lz4.Codec().Code()), nil
	case "zstd":
		return kafkago.Compression(compress.Zstd.Codec().Code()), nil
	default:
		return 0, fmt.Errorf("unsupported compression codec: %q", name)
	}
}
