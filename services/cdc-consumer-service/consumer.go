package main

import (
	"context"
	"errors"
	"fmt"
	"log/slog"
	"math/rand/v2"
	"net"
	"net/http"
	"strconv"
	"time"

	"cloud.google.com/go/bigquery"
	"github.com/segmentio/kafka-go"
	"go.opentelemetry.io/otel"
	"go.opentelemetry.io/otel/attribute"
	"go.opentelemetry.io/otel/codes"
	"go.opentelemetry.io/otel/trace"
	"google.golang.org/api/googleapi"
)

func runConsumer(
	ctx context.Context,
	reader *kafka.Reader,
	batchCh chan<- batchItem,
	_ *kafka.Writer,
	_ Config,
	metrics Metrics,
	_ *slog.Logger,
	tracer trace.Tracer,
	schemaHandler *SchemaEvolutionHandler,
	reportErr func(error),
) {
	for {
		msg, err := reader.FetchMessage(ctx)
		if err != nil {
			if errors.Is(err, context.Canceled) || errors.Is(err, context.DeadlineExceeded) {
				return
			}
			reportErr(fmt.Errorf("kafka fetch failed: %w", err))
			return
		}

		if msg.HighWaterMark > 0 {
			lag := msg.HighWaterMark - msg.Offset - 1
			if lag < 0 {
				lag = 0
			}
			metrics.consumerLag.WithLabelValues(msg.Topic, strconv.Itoa(msg.Partition)).Set(float64(lag))
		}

		parentCtx := otel.GetTextMapPropagator().Extract(ctx, kafkaHeaderCarrier(msg.Headers))
		msgCtx, span := tracer.Start(parentCtx, "kafka.consume",
			trace.WithAttributes(
				attribute.String("messaging.system", "kafka"),
				attribute.String("messaging.destination", msg.Topic),
				attribute.Int("messaging.kafka.partition", msg.Partition),
				attribute.Int64("messaging.kafka.offset", msg.Offset),
			),
		)

		if schemaHandler != nil && !isTombstonePayload(msg.Value) {
			if cdcFields, err := ExtractCDCFields(msg.Value); err == nil && len(cdcFields) > 0 {
				_ = schemaHandler.DetectAndEvolveSchema(msgCtx, cdcFields)
			}
		}

		row, err := transformMessage(msg)
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
	ticker := time.NewTicker(cfg.BatchTimeout)
	defer ticker.Stop()

	items := make([]batchItem, 0, cfg.BatchSize)
	for {
		select {
		case item, ok := <-batchCh:
			if !ok {
				return flushBatch(items, inserter, dlqWriter, cfg, metrics, logger, tracer)
			}
			items = append(items, item)
			if len(items) >= cfg.BatchSize {
				if err := flushBatch(items, inserter, dlqWriter, cfg, metrics, logger, tracer); err != nil {
					return err
				}
				items = items[:0]
			}
		case <-ticker.C:
			if len(items) == 0 {
				continue
			}
			if err := flushBatch(items, inserter, dlqWriter, cfg, metrics, logger, tracer); err != nil {
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
	if len(items) == 0 {
		return nil
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
		if dlqErr := sendToDLQ(ctx, dlqWriter, cfg.DLQWriteTimeout, metrics, logger, tracer, transformFailedItems, firstTransformErr, cfg.KafkaGroupID); dlqErr != nil {
			return dlqErr
		}
	}

	if len(rows) > 0 {
		start := time.Now()
		failedIndices, err := insertWithRetry(ctx, inserter, rows, cfg, logger)
		metrics.batchLatency.Observe(time.Since(start).Seconds())

		if err != nil {
			span.RecordError(err)
			span.SetStatus(codes.Error, "bigquery insert failed")
			if dlqErr := sendToDLQ(ctx, dlqWriter, cfg.DLQWriteTimeout, metrics, logger, tracer, validItems, err, cfg.KafkaGroupID); dlqErr != nil {
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
			if dlqErr := sendToDLQ(ctx, dlqWriter, cfg.DLQWriteTimeout, metrics, logger, tracer, failedItems, errors.New("bigquery row insert failed"), cfg.KafkaGroupID); dlqErr != nil {
				return dlqErr
			}
		}
	}

	readyItems := markReadyForCommit(items)
	return commitMessages(ctx, cfg.KafkaCommitTimeout, logger, readyItems)
}

func insertWithRetry(ctx context.Context, inserter *bigquery.Inserter, rows []bigquery.ValueSaver, cfg Config, logger *slog.Logger) ([]int, error) {
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
			logger.Warn("bigquery row errors", "count", len(rowIndexes))
			return rowIndexes, nil
		}

		if attempt >= cfg.BQMaxRetries || !isRetryable(err) {
			return nil, err
		}
		sleep := backoff + jitter(backoff)
		if sleep > cfg.BQMaxBackoff {
			sleep = cfg.BQMaxBackoff
		}
		logger.Warn("bigquery insert retrying", "attempt", attempt+1, "backoff", sleep, "error", err)
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
