package handler

import (
	"context"
	"fmt"
	"time"

	sharedkafka "github.com/instacommerce/go-shared/pkg/kafka"
	"github.com/segmentio/kafka-go"
	"go.opentelemetry.io/otel"
	"go.opentelemetry.io/otel/attribute"
	"go.opentelemetry.io/otel/trace"
)

// KafkaProducer abstracts Kafka message publishing.
type KafkaProducer interface {
	Publish(ctx context.Context, topic string, key []byte, value []byte) error
}

// HeaderKafkaProducer carries Kafka headers and event metadata.
type HeaderKafkaProducer interface {
	KafkaProducer
	PublishMessage(ctx context.Context, topic string, msg kafka.Message) error
}

// MessageWriter abstracts kafka.Writer for production publishing and unit testing.
type MessageWriter interface {
	WriteMessages(ctx context.Context, msgs ...kafka.Message) error
	Close() error
}

// KafkaWriterProducer adapts MessageWriter to KafkaProducer and HeaderKafkaProducer.
type KafkaWriterProducer struct {
	writer MessageWriter
}

func NewKafkaWriterProducer(writer MessageWriter) *KafkaWriterProducer {
	return &KafkaWriterProducer{writer: writer}
}

func (p *KafkaWriterProducer) Publish(ctx context.Context, topic string, key []byte, value []byte) error {
	return p.PublishMessage(ctx, topic, kafka.Message{
		Key:   key,
		Value: value,
		Time:  time.Now().UTC(),
	})
}

func (p *KafkaWriterProducer) PublishMessage(ctx context.Context, topic string, msg kafka.Message) error {
	if p == nil || p.writer == nil {
		return ErrProducerNotConfigured
	}
	if kw, ok := p.writer.(*kafka.Writer); ok && kw.Topic != "" {
		if topic != "" && kw.Topic != topic {
			return fmt.Errorf("handler: writer topic %q does not match message topic %q", kw.Topic, topic)
		}
	} else if msg.Topic == "" && topic != "" {
		msg.Topic = topic
	}
	return p.writer.WriteMessages(ctx, msg)
}

// UnconfiguredKafkaProducer returns ErrProducerNotConfigured so endpoints return HTTP 503.
type UnconfiguredKafkaProducer struct{}

func (UnconfiguredKafkaProducer) Publish(_ context.Context, _ string, _ []byte, _ []byte) error {
	return ErrProducerNotConfigured
}

func (h *WebhookHandler) publishEvent(ctx context.Context, event WebhookEvent, encoded []byte) error {
	kafkaKey := event.PaymentID
	if kafkaKey == "" {
		kafkaKey = event.ID
	}

	timeout := h.publishTimeout
	if timeout <= 0 {
		timeout = defaultPublishTimeout
	}
	publishCtx, cancel := context.WithTimeout(ctx, timeout)
	defer cancel()

	if h.tracer != nil {
		var span trace.Span
		publishCtx, span = h.tracer.Start(publishCtx, "kafka.publish", trace.WithAttributes(
			attribute.String("messaging.system", "kafka"),
			attribute.String("messaging.destination", h.topic),
			attribute.String("messaging.operation", "publish"),
			attribute.String("event.id", event.ID),
			attribute.String("event.type", event.EventType),
		))
		defer span.End()
	}

	if headerProd, ok := h.producer.(HeaderKafkaProducer); ok {
		headers := []kafka.Header{
			{Key: "event_id", Value: []byte(event.ID)},
			{Key: "event_type", Value: []byte(event.EventType)},
		}
		otel.GetTextMapPropagator().Inject(publishCtx, sharedkafka.NewHeaderCarrier(&headers))
		return headerProd.PublishMessage(publishCtx, h.topic, kafka.Message{
			Key:     []byte(kafkaKey),
			Value:   encoded,
			Time:    event.ReceivedAt,
			Headers: headers,
		})
	}

	return h.producer.Publish(publishCtx, h.topic, []byte(kafkaKey), encoded)
}
