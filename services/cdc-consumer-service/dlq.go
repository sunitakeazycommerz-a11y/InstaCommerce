package main

import (
	"context"
	"errors"
	"fmt"
	"log/slog"
	"strconv"
	"strings"
	"time"

	"cloud.google.com/go/bigquery"
	sharedkafka "github.com/instacommerce/go-shared/pkg/kafka"
	"github.com/segmentio/kafka-go"
	"go.opentelemetry.io/otel"
	"go.opentelemetry.io/otel/attribute"
	"go.opentelemetry.io/otel/codes"
	"go.opentelemetry.io/otel/trace"
)

type batchItem struct {
	reader       *kafka.Reader
	message      kafka.Message
	row          bigquery.ValueSaver
	transformErr error
	readyCommit  bool
}

func markReadyForCommit(items []batchItem) []batchItem {
	marked := make([]batchItem, len(items))
	for i := range items {
		marked[i] = items[i]
		marked[i].readyCommit = true
	}
	return marked
}

func classifyDLQCategory(err error) string {
	switch {
	case errors.Is(err, errMissingPartitionKey):
		return "partition_key_missing"
	case errors.Is(err, errInvalidSchemaVersion):
		return "schema_version_invalid"
	default:
		return "processing_error"
	}
}

func sendToDLQ(
	ctx context.Context,
	writer *kafka.Writer,
	timeout time.Duration,
	metrics Metrics,
	logger *slog.Logger,
	tracer trace.Tracer,
	items []batchItem,
	reason error,
	consumerGroup string,
) error {
	w, topic := unwrapDLQWriter(writer)
	return sendToDLQWithWriter(ctx, w, topic, timeout, metrics, logger, tracer, items, reason, consumerGroup)
}

func sendToDLQWithWriter(
	ctx context.Context,
	writer dlqMessageWriter,
	destinationTopic string,
	timeout time.Duration,
	metrics Metrics,
	logger *slog.Logger,
	tracer trace.Tracer,
	items []batchItem,
	reason error,
	consumerGroup string,
) error {
	if len(items) == 0 {
		return nil
	}
	if writer == nil {
		return errors.New("dlq writer must not be nil")
	}
	if reason == nil {
		reason = errors.New("unknown failure")
	}
	if tracer == nil {
		tracer = otel.Tracer("cdc-consumer")
	}

	dlqReason := sharedkafka.TruncateUTF8(reason.Error(), 512)
	quarantinedAt := time.Now().UTC().Format(time.RFC3339Nano)
	errorCategory := classifyDLQCategory(reason)

	sc := trace.SpanFromContext(ctx).SpanContext()
	traceID, traceparent := "", ""
	if sc.IsValid() {
		traceID = sc.TraceID().String()
		traceparent = fmt.Sprintf("00-%s-%s-%02x", sc.TraceID().String(), sc.SpanID().String(), byte(sc.TraceFlags()))
	}
	spanCtx, span := tracer.Start(ctx, "kafka.dlq.write",
		trace.WithAttributes(
			attribute.String("messaging.system", "kafka"),
			attribute.String("messaging.destination", destinationTopic),
			attribute.Int("dlq.count", len(items)),
			attribute.String("dlq.category", errorCategory),
		),
	)
	defer span.End()

	messages := buildDLQKafkaMessages(items, dlqReason, errorCategory, consumerGroup, quarantinedAt, traceID, traceparent)
	dlqCtx, cancel := context.WithTimeout(spanCtx, timeout)
	defer cancel()
	if err := writer.WriteMessages(dlqCtx, messages...); err != nil {
		span.RecordError(err)
		span.SetStatus(codes.Error, "dlq write failed")
		return fmt.Errorf("dlq write failed: %w", err)
	}
	if metrics.dlqCount != nil {
		metrics.dlqCount.Add(float64(len(items)))
	}
	if logger != nil {
		logger.Warn("sent messages to dlq", "count", len(items), "reason", dlqReason, "category", errorCategory)
	}
	return nil
}

func upsertKafkaHeader(headers []kafka.Header, key string, value []byte) []kafka.Header {
	for i := range headers {
		if strings.EqualFold(headers[i].Key, key) {
			headers[i].Key = key
			headers[i].Value = value
			return headers
		}
	}
	return append(headers, kafka.Header{Key: key, Value: value})
}

func buildDLQKafkaMessages(
	items []batchItem,
	dlqReason, errorCategory, consumerGroup, quarantinedAt, traceID, traceparent string,
) []kafka.Message {
	messages := make([]kafka.Message, 0, len(items))
	now := time.Now()
	for _, item := range items {
		itemReason := dlqReason
		itemCategory := errorCategory
		if item.transformErr != nil {
			itemReason = sharedkafka.TruncateUTF8(item.transformErr.Error(), 512)
			itemCategory = classifyDLQCategory(item.transformErr)
		}
		headers := append([]kafka.Header{}, item.message.Headers...)
		schemaVer := extractHeaderValue(item.message.Headers, "schema_version", "schemaVersion")
		if schemaVer == "" {
			schemaVer = "unknown"
		}
		metaHeaders := []kafka.Header{
			{Key: "dlq_reason", Value: []byte(itemReason)},
			{Key: "error_category", Value: []byte(itemCategory)},
			{Key: "quarantined_at", Value: []byte(quarantinedAt)},
			{Key: "consumer_group", Value: []byte(consumerGroup)},
			{Key: "schema_version", Value: []byte(schemaVer)},
			{Key: "original_topic", Value: []byte(item.message.Topic)},
			{Key: "original_partition", Value: []byte(strconv.Itoa(item.message.Partition))},
			{Key: "original_offset", Value: []byte(strconv.FormatInt(item.message.Offset, 10))},
		}
		if traceID != "" {
			metaHeaders = append(metaHeaders,
				kafka.Header{Key: "trace_id", Value: []byte(traceID)},
				kafka.Header{Key: "traceparent", Value: []byte(traceparent)},
			)
		}
		for _, mh := range metaHeaders {
			headers = upsertKafkaHeader(headers, mh.Key, mh.Value)
		}
		messages = append(messages, kafka.Message{
			Key:     item.message.Key,
			Value:   item.message.Value,
			Headers: headers,
			Time:    now,
		})
	}
	return messages
}

func commitMessages(ctx context.Context, timeout time.Duration, logger *slog.Logger, items []batchItem) error {
	if len(items) == 0 {
		return nil
	}
	byReader := make(map[*kafka.Reader][]kafka.Message)
	for _, item := range items {
		if !item.readyCommit || item.reader == nil {
			continue
		}
		byReader[item.reader] = append(byReader[item.reader], item.message)
	}
	for reader, messages := range byReader {
		commitCtx, cancel := context.WithTimeout(ctx, timeout)
		err := reader.CommitMessages(commitCtx, messages...)
		cancel()
		if err != nil {
			if logger != nil {
				logger.Error("kafka commit failed", "error", err)
			}
			return err
		}
	}
	return nil
}
