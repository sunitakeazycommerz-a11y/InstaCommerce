package main

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"log/slog"
	"math/rand/v2"
	"net"
	"net/http"
	"strconv"
	"time"

	"cloud.google.com/go/bigquery"
	sharedkafka "github.com/instacommerce/go-shared/pkg/kafka"
	"github.com/segmentio/kafka-go"
	"go.opentelemetry.io/otel"
	"go.opentelemetry.io/otel/attribute"
	"go.opentelemetry.io/otel/codes"
	"go.opentelemetry.io/otel/trace"
	"google.golang.org/api/googleapi"
)

type bqInserter interface {
	Put(ctx context.Context, src any) error
}

type dlqMessageWriter interface {
	WriteMessages(ctx context.Context, msgs ...kafka.Message) error
}

func unwrapBQInserter(inserter *bigquery.Inserter) bqInserter {
	if inserter == nil {
		return nil
	}
	return inserter
}

func unwrapDLQWriter(writer *kafka.Writer) (dlqMessageWriter, string) {
	if writer == nil {
		return nil, ""
	}
	return writer, writer.Topic
}

type schemaEvolver interface {
	DetectAndEvolveSchema(ctx context.Context, fields map[string]any) error
}

func evolveSchemaIfPresent(
	ctx context.Context,
	msg kafka.Message,
	evolver schemaEvolver,
	metrics Metrics,
	logger *slog.Logger,
	span trace.Span,
) {
	if evolver == nil || isTombstonePayload(msg.Value) {
		return
	}
	cdcFields, extractErr := ExtractCDCFields(msg.Value)
	if extractErr != nil {
		if logger != nil {
			logger.Warn("failed to extract cdc fields for schema evolution",
				"topic", msg.Topic, "partition", msg.Partition, "offset", msg.Offset, "error", extractErr)
		}
		if span != nil {
			span.RecordError(extractErr)
		}
		return
	}
	if len(cdcFields) == 0 {
		return
	}
	if err := evolver.DetectAndEvolveSchema(ctx, cdcFields); err != nil {
		if logger != nil {
			logger.Warn("schema evolution failed",
				"topic", msg.Topic, "partition", msg.Partition, "offset", msg.Offset, "error", err)
		}
		if span != nil {
			span.RecordError(err)
		}
		if metrics.schemaEvolutionErrors != nil {
			metrics.schemaEvolutionErrors.Inc()
		}
	}
}

func evolveSchemaFromMap(
	ctx context.Context,
	msg kafka.Message,
	raw map[string]any,
	evolver schemaEvolver,
	metrics Metrics,
	logger *slog.Logger,
	span trace.Span,
) {
	if evolver == nil || len(raw) == 0 {
		return
	}
	cdcFields := ExtractCDCFieldsFromMap(raw)
	if len(cdcFields) == 0 {
		return
	}
	if err := evolver.DetectAndEvolveSchema(ctx, cdcFields); err != nil {
		if logger != nil {
			logger.Warn("schema evolution failed",
				"topic", msg.Topic, "partition", msg.Partition, "offset", msg.Offset, "error", err)
		}
		if span != nil {
			span.RecordError(err)
		}
		if metrics.schemaEvolutionErrors != nil {
			metrics.schemaEvolutionErrors.Inc()
		}
	}
}

func processMessageForConsumer(
	ctx context.Context,
	msg kafka.Message,
	evolver schemaEvolver,
	metrics Metrics,
	logger *slog.Logger,
	span trace.Span,
) (bigquery.ValueSaver, error) {
	if isTombstonePayload(msg.Value) {
		return transformMessage(msg)
	}
	var payload map[string]any
	if err := json.Unmarshal(msg.Value, &payload); err != nil {
		return nil, err
	}
	evolveSchemaFromMap(ctx, msg, payload, evolver, metrics, logger, span)
	return transformParsedMessage(msg, payload)
}

func runConsumer(
	ctx context.Context,
	reader *kafka.Reader,
	batchCh chan<- batchItem,
	_ *kafka.Writer,
	_ Config,
	metrics Metrics,
	logger *slog.Logger,
	tracer trace.Tracer,
	schemaHandler *SchemaEvolutionHandler,
	reportErr func(error),
) {
	if tracer == nil {
		tracer = otel.Tracer("cdc-consumer")
	}
	var evolver schemaEvolver
	if schemaHandler != nil {
		evolver = schemaHandler
	}
	for {
		msg, err := reader.FetchMessage(ctx)
		if err != nil {
			if errors.Is(err, context.Canceled) || errors.Is(err, context.DeadlineExceeded) {
				return
			}
			reportErr(fmt.Errorf("kafka fetch failed: %w", err))
			return
		}

		if msg.HighWaterMark > 0 && metrics.consumerLag != nil {
			lag := msg.HighWaterMark - msg.Offset - 1
			if lag < 0 {
				lag = 0
			}
			metrics.consumerLag.WithLabelValues(msg.Topic, strconv.Itoa(msg.Partition)).Set(float64(lag))
		}

		parentCtx := otel.GetTextMapPropagator().Extract(ctx, sharedkafka.NewHeaderCarrier(&msg.Headers))
		msgCtx, span := tracer.Start(parentCtx, "kafka.consume",
			trace.WithAttributes(
				attribute.String("messaging.system", "kafka"),
				attribute.String("messaging.destination", msg.Topic),
				attribute.Int("messaging.kafka.partition", msg.Partition),
				attribute.Int64("messaging.kafka.offset", msg.Offset),
			),
		)

		row, err := processMessageForConsumer(msgCtx, msg, evolver, metrics, logger, span)
		if err != nil {
			span.RecordError(err)
			span.SetStatus(codes.Error, "transform failed")
			span.End()
			select {
			case batchCh <- batchItem{reader: reader, message: msg, transformErr: err}:
			case <-ctx.Done():
				return
			}
			continue
		}

		span.End()
		select {
		case batchCh <- batchItem{reader: reader, message: msg, row: row}:
		case <-ctx.Done():
			return
		}
	}
}

func runBatcher(
	batchCh <-chan batchItem,
	inserter *bigquery.Inserter,
	dlqWriter *kafka.Writer,
	cfg Config,
	metrics Metrics,
	logger *slog.Logger,
	tracer trace.Tracer,
) error {
	w, topic := unwrapDLQWriter(dlqWriter)
	return runBatcherWithDeps(batchCh, unwrapBQInserter(inserter), w, topic, cfg, metrics, logger, tracer)
}

func runBatcherWithDeps(
	batchCh <-chan batchItem,
	inserter bqInserter,
	dlqWriter dlqMessageWriter,
	dlqTopic string,
	cfg Config,
	metrics Metrics,
	logger *slog.Logger,
	tracer trace.Tracer,
) error {
	timeout := cfg.BatchTimeout
	if timeout <= 0 {
		timeout = 5 * time.Second
	}
	batchSize := cfg.BatchSize
	if batchSize <= 0 {
		batchSize = 500
	}
	ticker := time.NewTicker(timeout)
	defer ticker.Stop()

	items := make([]batchItem, 0, batchSize)
	for {
		select {
		case item, ok := <-batchCh:
			if !ok {
				return flushBatchWithDeps(items, inserter, dlqWriter, dlqTopic, cfg, metrics, logger, tracer)
			}
			items = append(items, item)
			if len(items) >= batchSize {
				if err := flushBatchWithDeps(items, inserter, dlqWriter, dlqTopic, cfg, metrics, logger, tracer); err != nil {
					return err
				}
				items = items[:0]
			}
		case <-ticker.C:
			if len(items) == 0 {
				continue
			}
			if err := flushBatchWithDeps(items, inserter, dlqWriter, dlqTopic, cfg, metrics, logger, tracer); err != nil {
				return err
			}
			items = items[:0]
		}
	}
}

func flushBatch(
	items []batchItem,
	inserter *bigquery.Inserter,
	dlqWriter *kafka.Writer,
	cfg Config,
	metrics Metrics,
	logger *slog.Logger,
	tracer trace.Tracer,
) error {
	w, topic := unwrapDLQWriter(dlqWriter)
	return flushBatchWithDeps(items, unwrapBQInserter(inserter), w, topic, cfg, metrics, logger, tracer)
}

func flushBatchWithDeps(
	items []batchItem,
	inserter bqInserter,
	dlqWriter dlqMessageWriter,
	dlqTopic string,
	cfg Config,
	metrics Metrics,
	logger *slog.Logger,
	tracer trace.Tracer,
) error {
	if len(items) == 0 {
		return nil
	}
	if tracer == nil {
		tracer = otel.Tracer("cdc-consumer")
	}

	rows := make([]bigquery.ValueSaver, 0, len(items))
	validItems := make([]batchItem, 0, len(items))
	transformFailedItems := make([]batchItem, 0)
	var firstTransformErr error
	for _, item := range items {
		if item.transformErr != nil {
			transformFailedItems = append(transformFailedItems, item)
			if firstTransformErr == nil {
				firstTransformErr = item.transformErr
			}
			continue
		}
		rows = append(rows, item.row)
		validItems = append(validItems, item)
	}

	ctx := context.Background()
	ctx, span := tracer.Start(ctx, "bigquery.batch.insert",
		trace.WithAttributes(
			attribute.Int("batch.size", len(items)),
			attribute.String("bq.dataset", cfg.BQDataset),
			attribute.String("bq.table", cfg.BQTable),
		),
	)
	defer span.End()

	if len(transformFailedItems) > 0 {
		if dlqErr := sendToDLQWithWriter(ctx, dlqWriter, dlqTopic, cfg.DLQWriteTimeout, metrics, logger, tracer, transformFailedItems, firstTransformErr, cfg.KafkaGroupID); dlqErr != nil {
			return dlqErr
		}
	}

	if len(rows) > 0 {
		start := time.Now()
		failedIndices, err := insertWithRetryDeps(ctx, inserter, rows, cfg, logger)
		if metrics.batchLatency != nil {
			metrics.batchLatency.Observe(time.Since(start).Seconds())
		}

		if err != nil {
			span.RecordError(err)
			span.SetStatus(codes.Error, "bigquery insert failed")
			if dlqErr := sendToDLQWithWriter(ctx, dlqWriter, dlqTopic, cfg.DLQWriteTimeout, metrics, logger, tracer, validItems, err, cfg.KafkaGroupID); dlqErr != nil {
				return dlqErr
			}
			readyItems := markReadyForCommit(items)
			return commitMessages(ctx, cfg.KafkaCommitTimeout, logger, readyItems)
		}

		if len(failedIndices) > 0 {
			failedItems := make([]batchItem, 0, len(failedIndices))
			for _, idx := range failedIndices {
				if idx >= 0 && idx < len(validItems) {
					failedItems = append(failedItems, validItems[idx])
				}
			}
			if dlqErr := sendToDLQWithWriter(ctx, dlqWriter, dlqTopic, cfg.DLQWriteTimeout, metrics, logger, tracer, failedItems, errors.New("bigquery row insert failed"), cfg.KafkaGroupID); dlqErr != nil {
				return dlqErr
			}
		}
	}

	readyItems := markReadyForCommit(items)
	return commitMessages(ctx, cfg.KafkaCommitTimeout, logger, readyItems)
}

func insertWithRetry(ctx context.Context, inserter *bigquery.Inserter, rows []bigquery.ValueSaver, cfg Config, logger *slog.Logger) ([]int, error) {
	return insertWithRetryDeps(ctx, unwrapBQInserter(inserter), rows, cfg, logger)
}

func insertWithRetryDeps(ctx context.Context, inserter bqInserter, rows []bigquery.ValueSaver, cfg Config, logger *slog.Logger) ([]int, error) {
	if inserter == nil {
		return nil, errors.New("bigquery inserter must not be nil")
	}
	backoff := cfg.BQBaseBackoff
	for attempt := 0; attempt <= cfg.BQMaxRetries; attempt++ {
		insertCtx, cancel := context.WithTimeout(ctx, cfg.BQInsertTimeout)
		err := inserter.Put(insertCtx, rows)
		cancel()
		if err == nil {
			return nil, nil
		}

		var multiErr bigquery.PutMultiError
		if errors.As(err, &multiErr) {
			rowIndexes := make([]int, 0, len(multiErr))
			for _, rowErr := range multiErr {
				rowIndexes = append(rowIndexes, rowErr.RowIndex)
			}
			if logger != nil {
				logger.Warn("bigquery row errors", "count", len(rowIndexes))
			}
			return rowIndexes, nil
		}

		if attempt >= cfg.BQMaxRetries || !isRetryable(err) {
			return nil, err
		}
		sleep := backoff + jitter(backoff)
		if sleep > cfg.BQMaxBackoff {
			sleep = cfg.BQMaxBackoff
		}
		if logger != nil {
			logger.Warn("bigquery insert retrying", "attempt", attempt+1, "backoff", sleep, "error", err)
		}
		select {
		case <-time.After(sleep):
		case <-ctx.Done():
			return nil, ctx.Err()
		}
		backoff *= 2
		if backoff > cfg.BQMaxBackoff {
			backoff = cfg.BQMaxBackoff
		}
	}
	return nil, errors.New("bigquery insert retries exhausted")
}

func jitter(base time.Duration) time.Duration {
	return time.Duration(rand.Float64() * 0.2 * float64(base))
}

func isRetryable(err error) bool {
	if errors.Is(err, context.DeadlineExceeded) {
		return true
	}
	var apiErr *googleapi.Error
	if errors.As(err, &apiErr) {
		return apiErr.Code == http.StatusTooManyRequests || apiErr.Code >= http.StatusInternalServerError
	}
	var netErr net.Error
	if errors.As(err, &netErr) {
		return netErr.Timeout()
	}
	return false
}
