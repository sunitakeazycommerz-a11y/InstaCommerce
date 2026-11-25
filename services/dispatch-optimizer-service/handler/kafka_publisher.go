package handler

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"log/slog"
	"os"
	"strings"
	"time"

	"github.com/instacommerce/go-shared/pkg/config"
	"github.com/instacommerce/go-shared/pkg/correlation"
	"github.com/segmentio/kafka-go"
)

type kafkaMessageWriter interface {
	WriteMessages(ctx context.Context, msgs ...kafka.Message) error
	Close() error
}

// KafkaEventPublisher publishes dispatch domain events to Kafka.
type KafkaEventPublisher struct {
	writer kafkaMessageWriter
	logger *slog.Logger
}

// NewKafkaEventPublisher constructs a Kafka-backed EventPublisher for the given broker addresses.
func NewKafkaEventPublisher(brokers []string, logger *slog.Logger) (*KafkaEventPublisher, error) {
	cleaned := make([]string, 0, len(brokers))
	for _, b := range brokers {
		if trimmed := strings.TrimSpace(b); trimmed != "" {
			cleaned = append(cleaned, trimmed)
		}
	}
	if len(cleaned) == 0 {
		return nil, errors.New("kafka brokers must not be empty")
	}
	writer := &kafka.Writer{
		Addr:         kafka.TCP(cleaned...),
		Balancer:     &kafka.LeastBytes{},
		RequiredAcks: kafka.RequireAll,
		BatchTimeout: 10 * time.Millisecond,
	}
	return &KafkaEventPublisher{
		writer: writer,
		logger: logger,
	}, nil
}

// Publish serializes payload as JSON and writes it to the specified Kafka topic.
func (p *KafkaEventPublisher) Publish(ctx context.Context, topic string, key string, payload any) error {
	if p == nil || p.writer == nil {
		return errors.New("kafka publisher is not initialized")
	}
	if strings.TrimSpace(topic) == "" {
		return errors.New("kafka topic must not be empty")
	}
	data, err := json.Marshal(payload)
	if err != nil {
		return fmt.Errorf("marshal event payload: %w", err)
	}
	var headers []kafka.Header
	if corrID := correlation.FromContext(ctx); corrID != "" {
		headers = append(headers, kafka.Header{Key: "correlation_id", Value: []byte(corrID)})
	}
	msg := kafka.Message{
		Topic:   topic,
		Key:     []byte(key),
		Value:   data,
		Headers: headers,
		Time:    time.Now().UTC(),
	}
	if err := p.writer.WriteMessages(ctx, msg); err != nil {
		return fmt.Errorf("write kafka message to %s: %w", topic, err)
	}
	return nil
}

// Close releases underlying Kafka writer resources.
func (p *KafkaEventPublisher) Close() error {
	if p == nil || p.writer == nil {
		return nil
	}
	return p.writer.Close()
}

// NewEventPublisherFromEnv configures a KafkaEventPublisher when KAFKA_BROKERS is set,
// falling back to NoopEventPublisher for local runs without a broker.
func NewEventPublisherFromEnv(logger *slog.Logger) (EventPublisher, []string, func() error) {
	brokers := config.SplitCSV(os.Getenv("KAFKA_BROKERS"))
	if len(brokers) == 0 {
		if logger != nil {
			logger.Info("KAFKA_BROKERS unset; using noop event publisher")
		}
		return &NoopEventPublisher{Logger: logger}, nil, func() error { return nil }
	}
	pub, err := NewKafkaEventPublisher(brokers, logger)
	if err != nil {
		if logger != nil {
			logger.Warn("invalid KAFKA_BROKERS; falling back to noop event publisher", "error", err)
		}
		return &NoopEventPublisher{Logger: logger}, nil, func() error { return nil }
	}
	return pub, brokers, pub.Close
}
