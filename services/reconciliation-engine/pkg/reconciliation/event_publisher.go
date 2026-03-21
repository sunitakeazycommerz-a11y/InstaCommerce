package reconciliation

import (
	"context"
	"crypto/rand"
	"encoding/hex"
	"encoding/json"
	"fmt"
	"log/slog"
	"time"

	"github.com/segmentio/kafka-go"
)

type EventEnvelope struct {
	EventID       string    `json:"event_id"`
	EventType     string    `json:"event_type"`
	AggregateID   string    `json:"aggregate_id"`
	SchemaVersion string    `json:"schema_version"`
	SourceService string    `json:"source_service"`
	CorrelationID string    `json:"correlation_id"`
	Timestamp     time.Time `json:"timestamp"`
	Payload       any       `json:"payload"`
}

type EventPublisher struct {
	writer *kafka.Writer
	logger *slog.Logger
	topic  string
}

func NewEventPublisher(brokers []string, topic string, logger *slog.Logger) *EventPublisher {
	if len(brokers) == 0 {
		logger.Warn("Kafka not configured; events will not be published")
		return &EventPublisher{logger: logger, topic: topic}
	}

	writer := &kafka.Writer{
		Addr:         kafka.TCP(brokers...),
		Topic:        topic,
		Balancer:     &kafka.LeastBytes{},
		RequiredAcks: kafka.RequireAll,
		Async:        false,
	}

	logger.Info("Kafka event publisher configured", "brokers", brokers, "topic", topic)
	return &EventPublisher{writer: writer, logger: logger, topic: topic}
}

func (p *EventPublisher) PublishMismatchEvent(ctx context.Context, runID int64, mismatch MismatchDetail) error {
	if p.writer == nil {
		return nil
	}

	payload := map[string]interface{}{
		"transaction_id": mismatch.TransactionID,
		"ledger_amount":  mismatch.LedgerAmount,
		"psp_amount":     mismatch.PSPAmount,
		"reason":         mismatch.Reason,
		"auto_fixed":     mismatch.AutoFixed,
	}

	envelope := EventEnvelope{
		EventID:       newID("evt"),
		EventType:     "ReconciliationMismatchFound",
		AggregateID:   fmt.Sprintf("run-%d", runID),
		SchemaVersion: "1.0",
		SourceService: "reconciliation-engine",
		CorrelationID: fmt.Sprintf("run-%d", runID),
		Timestamp:     time.Now().UTC(),
		Payload:       payload,
	}

	return p.publish(ctx, envelope, mismatch.TransactionID)
}

func (p *EventPublisher) PublishCompletionEvent(ctx context.Context, runID int64, totalMismatches, autoFixed, manualReview int) error {
	if p.writer == nil {
		return nil
	}

	payload := map[string]interface{}{
		"total_mismatches":   totalMismatches,
		"auto_fixed_count":   autoFixed,
		"manual_review_count": manualReview,
		"completion_time":    time.Now().UTC().Unix(),
	}

	envelope := EventEnvelope{
		EventID:       newID("evt"),
		EventType:     "ReconciliationCompleted",
		AggregateID:   fmt.Sprintf("run-%d", runID),
		SchemaVersion: "1.0",
		SourceService: "reconciliation-engine",
		CorrelationID: fmt.Sprintf("run-%d", runID),
		Timestamp:     time.Now().UTC(),
		Payload:       payload,
	}

	return p.publish(ctx, envelope, fmt.Sprintf("run-%d", runID))
}

func (p *EventPublisher) publish(ctx context.Context, envelope EventEnvelope, key string) error {
	if p.writer == nil {
		return nil
	}

	payload, err := json.Marshal(envelope)
	if err != nil {
		p.logger.Error("failed to marshal event", "error", err)
		return err
	}

	publishCtx, cancel := context.WithTimeout(ctx, 5*time.Second)
	defer cancel()

	err = p.writer.WriteMessages(publishCtx, kafka.Message{
		Key:   []byte(key),
		Value: payload,
		Time:  envelope.Timestamp,
	})

	if err != nil {
		p.logger.Error("failed to publish event", "error", err, "event_type", envelope.EventType)
	}
	return err
}

func (p *EventPublisher) Close() error {
	if p.writer == nil {
		return nil
	}
	return p.writer.Close()
}

func newID(prefix string) string {
	random := make([]byte, 6)
	if _, err := rand.Read(random); err != nil {
		return fmt.Sprintf("%s-%d", prefix, time.Now().UnixNano())
	}
	return fmt.Sprintf("%s-%d-%s", prefix, time.Now().UnixNano(), hex.EncodeToString(random))
}
