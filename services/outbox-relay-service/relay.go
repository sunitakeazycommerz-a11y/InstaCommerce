package main

import (
	"context"
	"errors"
	"fmt"
	"log/slog"
	"sync"
	"sync/atomic"
	"time"

	"github.com/IBM/sarama"
	"github.com/jackc/pgx/v5"
	"github.com/jackc/pgx/v5/pgxpool"
	"go.opentelemetry.io/otel/attribute"
	"go.opentelemetry.io/otel/trace"
)

type relayService struct {
	cfg          Config
	logger       *slog.Logger
	db           *pgxpool.Pool
	producer     sarama.SyncProducer
	kafkaConfig  *sarama.Config
	tracer       trace.Tracer
	metrics      relayMetrics
	selectSQL    string
	updateSQL    string
	shuttingDown atomic.Bool
	retryMu      sync.Mutex
	retryCounts  map[string]int
}

func newRelayService(cfg Config, logger *slog.Logger, db *pgxpool.Pool, producer sarama.SyncProducer, kafkaConfig *sarama.Config, tracer trace.Tracer, metrics relayMetrics) (*relayService, error) {
	table := pgx.Identifier{cfg.OutboxTable}.Sanitize()
	selectSQL := fmt.Sprintf(`SELECT id::text, aggregate_type, aggregate_id, event_type, payload, created_at,
    COALESCE(source_service, '') AS source_service,
    COALESCE(correlation_id, '') AS correlation_id,
    COALESCE(schema_version, 'v1') AS schema_version
FROM %s
WHERE sent = false
ORDER BY created_at
LIMIT $1
FOR UPDATE SKIP LOCKED`, table)
	updateSQL := fmt.Sprintf(`UPDATE %s SET sent = true WHERE id = $1`, table)

	return &relayService{
		cfg:         cfg,
		logger:      logger,
		db:          db,
		producer:    producer,
		kafkaConfig: kafkaConfig,
		tracer:      tracer,
		metrics:     metrics,
		selectSQL:   selectSQL,
		updateSQL:   updateSQL,
		retryCounts: make(map[string]int),
	}, nil
}

func (s *relayService) maxPublishRetries() int {
	if s.cfg.MaxRetries > 0 {
		return s.cfg.MaxRetries
	}
	return defaultMaxRetries
}

func (s *relayService) recordRetryAttempt(eventID string) int {
	s.retryMu.Lock()
	defer s.retryMu.Unlock()
	if s.retryCounts == nil {
		s.retryCounts = make(map[string]int)
	}
	s.retryCounts[eventID]++
	return s.retryCounts[eventID]
}

func (s *relayService) clearRetryAttempt(eventID string) {
	s.retryMu.Lock()
	defer s.retryMu.Unlock()
	if s.retryCounts != nil {
		delete(s.retryCounts, eventID)
	}
}

func (s *relayService) pollLoop(ctx context.Context) {
	ticker := time.NewTicker(s.cfg.PollInterval)
	defer ticker.Stop()

	for {
		if err := s.relayBatch(ctx); err != nil && !errors.Is(err, context.Canceled) {
			s.logger.Error("relay batch failed", "error", err)
		}

		select {
		case <-ctx.Done():
			return
		case <-ticker.C:
		}
	}
}

func (s *relayService) relayBatch(ctx context.Context) error {
	if ctx.Err() != nil {
		return ctx.Err()
	}

	ctx, span := s.tracer.Start(ctx, "outbox.relay.batch",
		trace.WithAttributes(
			attribute.Int("outbox.batch_size", s.cfg.BatchSize),
			attribute.String("outbox.table", s.cfg.OutboxTable),
		),
	)
	defer span.End()

	tx, err := s.db.Begin(ctx)
	if err != nil {
		span.RecordError(err)
		return s.fail(ctx, err)
	}
	committed := false
	defer func() {
		if !committed {
			rollbackCtx, rollbackCancel := context.WithTimeout(context.Background(), 5*time.Second)
			defer rollbackCancel()
			_ = tx.Rollback(rollbackCtx)
		}
	}()

	rows, err := tx.Query(ctx, s.selectSQL, s.cfg.BatchSize)
	if err != nil {
		span.RecordError(err)
		return s.fail(ctx, err)
	}
	defer rows.Close()

	events := make([]outboxEvent, 0, s.cfg.BatchSize)
	for rows.Next() {
		var evt outboxEvent
		if err := rows.Scan(&evt.ID, &evt.AggregateType, &evt.AggregateID, &evt.EventType, &evt.Payload, &evt.CreatedAt, &evt.SourceService, &evt.CorrelationID, &evt.SchemaVersion); err != nil {
			span.RecordError(err)
			return s.fail(ctx, err)
		}
		events = append(events, evt)
	}
	if err := rows.Err(); err != nil {
		span.RecordError(err)
		return s.fail(ctx, err)
	}

	commitCtx, commitCancel := context.WithTimeout(context.Background(), 10*time.Second)
	defer commitCancel()

	var sendErr error
	for _, evt := range events {
		if ctx.Err() != nil {
			sendErr = ctx.Err()
			break
		}

		topic := resolveKafkaTopic(s.cfg.KafkaTopic, evt.AggregateType)
		msgCtx, msgSpan := s.tracer.Start(ctx, "outbox.relay.publish",
			trace.WithAttributes(
				attribute.String("event.id", evt.ID),
				attribute.String("event.type", evt.EventType),
				attribute.String("aggregate.type", evt.AggregateType),
				attribute.String("aggregate.id", evt.AggregateID),
				attribute.String("kafka.topic", topic),
			),
		)

		traceID := populateEventTrace(&evt, msgSpan)
		partitionKey, schemaVersion, valErr := validateOutboxEvent(evt)
		if valErr != nil {
			s.metrics.failures.Add(msgCtx, 1)
			msgSpan.RecordError(valErr)
			if dlqErr := s.quarantineEvent(msgCtx, evt, topic, traceID, partitionKey, schemaVersion, 1, valErr); dlqErr != nil {
				msgSpan.End()
				sendErr = valErr
				break
			}
			msgSpan.End()
			if _, err := tx.Exec(commitCtx, s.updateSQL, evt.ID); err != nil {
				span.RecordError(err)
				return s.fail(ctx, err)
			}
			continue
		}

		evt.SchemaVersion = schemaVersion
		message := &sarama.ProducerMessage{
			Topic: topic,
			Key:   sarama.StringEncoder(partitionKey),
			Value: sarama.ByteEncoder(buildEventMessage(evt)),
			Headers: buildSaramaHeaders(evt, traceID,
				sarama.RecordHeader{Key: []byte("schema_version"), Value: []byte(schemaVersion)},
			),
		}

		if _, _, err := s.producer.SendMessage(message); err != nil {
			s.metrics.failures.Add(msgCtx, 1)
			msgSpan.RecordError(err)
			attempts := s.recordRetryAttempt(evt.ID)
			maxRetries := s.maxPublishRetries()
			s.logger.Error("failed to publish message",
				"error", err, "event_id", evt.ID, "topic", topic, "attempt", attempts, "max_retries", maxRetries,
			)
			if attempts < maxRetries {
				msgSpan.End()
				sendErr = err
				break
			}
			if dlqErr := s.quarantineEvent(msgCtx, evt, topic, traceID, partitionKey, schemaVersion, attempts, err); dlqErr != nil {
				msgSpan.End()
				sendErr = err
				break
			}
			s.clearRetryAttempt(evt.ID)
			msgSpan.End()
			if _, err := tx.Exec(commitCtx, s.updateSQL, evt.ID); err != nil {
				span.RecordError(err)
				return s.fail(ctx, err)
			}
			continue
		}

		s.clearRetryAttempt(evt.ID)
		lag := time.Since(evt.CreatedAt).Seconds()
		s.metrics.relayed.Add(msgCtx, 1)
		s.metrics.lag.Record(msgCtx, lag)
		msgSpan.End()

		if _, err := tx.Exec(commitCtx, s.updateSQL, evt.ID); err != nil {
			span.RecordError(err)
			return s.fail(ctx, err)
		}
		s.logger.Debug("relayed outbox event", "event_id", evt.ID, "topic", topic, "lag_seconds", lag)
	}

	if err := tx.Commit(commitCtx); err != nil {
		span.RecordError(err)
		return s.fail(ctx, err)
	}
	committed = true
	return sendErr
}

func populateEventTrace(evt *outboxEvent, span trace.Span) string {
	if !span.SpanContext().IsValid() {
		return ""
	}
	traceID := span.SpanContext().TraceID().String()
	if evt.Traceparent == "" {
		evt.Traceparent = fmt.Sprintf("00-%s-%s-%02x",
			span.SpanContext().TraceID().String(),
			span.SpanContext().SpanID().String(),
			byte(span.SpanContext().TraceFlags()),
		)
	}
	return traceID
}

func (s *relayService) quarantineEvent(
	ctx context.Context,
	evt outboxEvent,
	topic, traceID, partitionKey, schemaVersion string,
	attempts int,
	cause error,
) error {
	now := time.Now().UTC()
	dlqKey := partitionKey
	if dlqKey == "" {
		dlqKey = evt.ID
	}
	dlqMessage := &sarama.ProducerMessage{
		Topic:   s.cfg.DLQTopic,
		Key:     sarama.StringEncoder(dlqKey),
		Value:   sarama.ByteEncoder(buildEnrichedDLQMessage(evt, topic, partitionKey, attempts, cause, now)),
		Headers: buildDLQHeaders(evt, topic, traceID, partitionKey, schemaVersion, attempts, cause, now),
	}
	if _, _, dlqErr := s.producer.SendMessage(dlqMessage); dlqErr != nil {
		s.logger.Error("failed to publish to DLQ, pipeline stalled",
			"error", dlqErr, "event_id", evt.ID, "dlq_topic", s.cfg.DLQTopic)
		return dlqErr
	}
	s.metrics.dlq.Add(ctx, 1)
	s.logger.Warn("event sent to DLQ", "event_id", evt.ID, "topic", topic, "dlq_topic", s.cfg.DLQTopic, "category", classifyRelayError(cause))
	return nil
}

func (s *relayService) fail(ctx context.Context, err error) error {
	if err != nil && !errors.Is(err, context.Canceled) {
		s.metrics.failures.Add(ctx, 1)
	}
	return err
}
