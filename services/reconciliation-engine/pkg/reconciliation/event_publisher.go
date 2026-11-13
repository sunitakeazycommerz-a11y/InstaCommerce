package reconciliation

import (
	"context"
	"crypto/rand"
	"encoding/hex"
	"encoding/json"
	"fmt"
	"log/slog"
	"time"

	"github.com/instacommerce/go-shared/pkg/correlation"
	"github.com/segmentio/kafka-go"
)

const (
	defaultAggregateType = "ReconciliationRun"
	defaultSchemaVersion = "1.0.0"
	defaultSourceService = "reconciliation-engine"
)

type kafkaMessageWriter interface {
	WriteMessages(ctx context.Context, msgs ...kafka.Message) error
	Close() error
}

type EventEnvelope struct {
	EventID       string    `json:"eventId"`
	EventType     string    `json:"eventType"`
	AggregateID   string    `json:"aggregateId"`
	AggregateType string    `json:"aggregateType"`
	SchemaVersion string    `json:"schemaVersion"`
	SourceService string    `json:"sourceService"`
	CorrelationID string    `json:"correlationId"`
	Timestamp     time.Time `json:"timestamp"`
	Payload       any       `json:"payload"`
}

type EventPublisher struct {
	writer kafkaMessageWriter
	logger *slog.Logger
	topic  string
}

func NewEventPublisher(brokers []string, topic string, logger *slog.Logger) *EventPublisher {
	if logger == nil {
		logger = slog.Default()
	}
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
	return newEventPublisherWithWriter(writer, topic, logger)
}

func newEventPublisherWithWriter(writer kafkaMessageWriter, topic string, logger *slog.Logger) *EventPublisher {
	if logger == nil {
		logger = slog.Default()
	}
	return &EventPublisher{writer: writer, logger: logger, topic: topic}
}

func (p *EventPublisher) newEnvelope(ctx context.Context, runID int64, eventType string, payload any) EventEnvelope {
	aggregateID := fmt.Sprintf("run-%d", runID)
	correlationID := correlation.FromContext(ctx)
	if correlationID == "" {
		correlationID = aggregateID
	}
	return EventEnvelope{
		EventID:       newID("evt"),
		EventType:     eventType,
		AggregateID:   aggregateID,
		AggregateType: defaultAggregateType,
		SchemaVersion: defaultSchemaVersion,
		SourceService: defaultSourceService,
		CorrelationID: correlationID,
		Timestamp:     time.Now().UTC(),
		Payload:       payload,
	}
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

	envelope := p.newEnvelope(ctx, runID, "ReconciliationMismatchFound", payload)
	return p.publish(ctx, envelope, mismatch.TransactionID)
}

func (p *EventPublisher) PublishDiscrepancyAlert(ctx context.Context, runID int64, mismatch MismatchDetail, discrepancyCents int64) error {
	if p.writer == nil {
		return nil
	}

	payload := map[string]interface{}{
		"run_id":            runID,
		"transaction_id":    mismatch.TransactionID,
		"discrepancy_cents": discrepancyCents,
		"ledger_amount":     mismatch.LedgerAmount,
		"psp_amount":        mismatch.PSPAmount,
		"reason":            mismatch.Reason,
		"freeze_payouts":    true,
		"alert_level":       "CRITICAL",
	}

	envelope := p.newEnvelope(ctx, runID, "ReconciliationDiscrepancyAlert", payload)
	return p.publish(ctx, envelope, mismatch.TransactionID)
}

func (p *EventPublisher) PublishCompletionEvent(ctx context.Context, runID int64, totalMismatches, autoFixed, manualReview int) error {
	if p.writer == nil {
		return nil
	}

	payload := map[string]interface{}{
		"total_mismatches":    totalMismatches,
		"auto_fixed_count":    autoFixed,
		"manual_review_count": manualReview,
		"completion_time":     time.Now().UTC().Unix(),
	}

	envelope := p.newEnvelope(ctx, runID, "ReconciliationCompleted", payload)
	return p.publish(ctx, envelope, envelope.AggregateID)
}

func (p *EventPublisher) publish(ctx context.Context, envelope EventEnvelope, key string) error {
	if p.writer == nil {
		return nil
	}
	logger := p.logger
	if logger == nil {
		logger = slog.Default()
	}

	payload, err := json.Marshal(envelope)
	if err != nil {
		logger.Error("failed to marshal event", "error", err)
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
		logger.Error("failed to publish event", "error", err, "event_type", envelope.EventType)
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
