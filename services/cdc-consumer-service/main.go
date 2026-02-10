package main

import (
	"context"
	"encoding/base64"
	"encoding/json"
	"errors"
	"fmt"
	"log/slog"
	"math/rand"
	"net"
	"net/http"
	"net/url"
	"os"
	"os/signal"
	"strconv"
	"strings"
	"sync"
	"sync/atomic"
	"syscall"
	"time"
	"unicode/utf8"

	"cloud.google.com/go/bigquery"
	"github.com/prometheus/client_golang/prometheus"
	"github.com/prometheus/client_golang/prometheus/promhttp"
	"github.com/segmentio/kafka-go"
	"go.opentelemetry.io/otel"
	"go.opentelemetry.io/otel/attribute"
	"go.opentelemetry.io/otel/codes"
	"go.opentelemetry.io/otel/exporters/otlp/otlptrace/otlptracegrpc"
	"go.opentelemetry.io/otel/propagation"
	"go.opentelemetry.io/otel/sdk/resource"
	sdktrace "go.opentelemetry.io/otel/sdk/trace"
	semconv "go.opentelemetry.io/otel/semconv/v1.26.0"
	"go.opentelemetry.io/otel/trace"
	"google.golang.org/api/googleapi"
)

const serviceName = "cdc-consumer-service"

type Config struct {
	Port               string
	LogLevel           slog.Level
	KafkaBrokers       []string
	KafkaGroupID       string
	KafkaTopics        []string
	KafkaDLQTopic      string
	KafkaMinBytes      int
	KafkaMaxBytes      int
	KafkaMaxWait       time.Duration
	KafkaCommitTimeout time.Duration
	BatchSize          int
	BatchTimeout       time.Duration
	BQProject          string
	BQDataset          string
	BQTable            string
	BQInsertTimeout    time.Duration
	BQMaxRetries       int
	BQBaseBackoff      time.Duration
	BQMaxBackoff       time.Duration
	DLQWriteTimeout    time.Duration
}

type Metrics struct {
	consumerLag  *prometheus.GaugeVec
	batchLatency prometheus.Histogram
	dlqCount     prometheus.Counter
}

type readiness struct {
	ready atomic.Bool
}

type batchItem struct {
	reader  *kafka.Reader
	message kafka.Message
	row     bigquery.ValueSaver
}

type bqRow struct {
	values   map[string]bigquery.Value
	insertID string
}

func (r bqRow) Save() (map[string]bigquery.Value, string, error) {
	return r.values, r.insertID, nil
}

type kafkaHeaderCarrier []kafka.Header

func (c kafkaHeaderCarrier) Get(key string) string {
	for _, header := range c {
		if strings.EqualFold(header.Key, key) {
			return string(header.Value)
		}
	}
	return ""
}

func (c kafkaHeaderCarrier) Set(key, value string) {}

func (c kafkaHeaderCarrier) Keys() []string {
	keys := make([]string, 0, len(c))
	for _, header := range c {
		keys = append(keys, header.Key)
	}
	return keys
}

func main() {
	rand.Seed(time.Now().UnixNano())

	cfg, err := loadConfig()
	if err != nil {
		slog.Error("configuration error", "error", err)
		os.Exit(1)
	}

	logger := setupLogger(cfg.LogLevel)
	metrics := initMetrics()

	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()

	tracerProvider, tracer := setupTracing(ctx, logger)
	if tracerProvider != nil {
		defer func() {
			shutdownCtx, shutdownCancel := context.WithTimeout(context.Background(), 10*time.Second)
			defer shutdownCancel()
			if err := tracerProvider.Shutdown(shutdownCtx); err != nil {
				logger.Warn("tracing shutdown failed", "error", err)
			}
		}()
	}

	otel.SetTextMapPropagator(propagation.TraceContext{})

	bqClient, err := bigquery.NewClient(ctx, cfg.BQProject)
	if err != nil {
		logger.Error("failed to create bigquery client", "error", err)
		os.Exit(1)
	}
	defer bqClient.Close()

	inserter := bqClient.Dataset(cfg.BQDataset).Table(cfg.BQTable).Inserter()
	inserter.IgnoreUnknownValues = true
	inserter.SkipInvalidRows = true

	dlqWriter := &kafka.Writer{
		Addr:         kafka.TCP(cfg.KafkaBrokers...),
		Topic:        cfg.KafkaDLQTopic,
		Balancer:     &kafka.LeastBytes{},
		RequiredAcks: kafka.RequireAll,
		Async:        false,
	}
	defer dlqWriter.Close()

	readers := make([]*kafka.Reader, 0, len(cfg.KafkaTopics))
	for _, topic := range cfg.KafkaTopics {
		reader := kafka.NewReader(kafka.ReaderConfig{
			Brokers:        cfg.KafkaBrokers,
			GroupID:        cfg.KafkaGroupID,
			Topic:          topic,
			MinBytes:       cfg.KafkaMinBytes,
			MaxBytes:       cfg.KafkaMaxBytes,
			MaxWait:        cfg.KafkaMaxWait,
			CommitInterval: 0,
		})
		readers = append(readers, reader)
		defer reader.Close()
	}

	ready := &readiness{}
	server := startHTTPServer(cfg.Port, ready, logger)

	errCh := make(chan error, 1)
	reportErr := func(err error) {
		select {
		case errCh <- err:
		default:
		}
	}

	batchCh := make(chan batchItem, cfg.BatchSize*2)
	var consumerWg sync.WaitGroup
	for _, reader := range readers {
		consumerWg.Add(1)
		go func(r *kafka.Reader) {
			defer consumerWg.Done()
			runConsumer(ctx, r, batchCh, dlqWriter, cfg, metrics, logger, tracer, reportErr)
		}(reader)
	}

	var batchWg sync.WaitGroup
	batchWg.Add(1)
	go func() {
		defer batchWg.Done()
		if err := runBatcher(batchCh, inserter, dlqWriter, cfg, metrics, logger, tracer); err != nil {
			reportErr(err)
		}
	}()

	ready.ready.Store(true)
	logger.Info("cdc consumer service started",
		"port", cfg.Port,
		"group_id", cfg.KafkaGroupID,
		"topics", cfg.KafkaTopics,
		"dlq_topic", cfg.KafkaDLQTopic,
		"bq_table", fmt.Sprintf("%s.%s.%s", cfg.BQProject, cfg.BQDataset, cfg.BQTable),
	)

	signalCh := make(chan os.Signal, 1)
	signal.Notify(signalCh, syscall.SIGINT, syscall.SIGTERM)

	select {
	case sig := <-signalCh:
		logger.Info("received shutdown signal", "signal", sig)
	case err := <-errCh:
		if err != nil {
			logger.Error("service error", "error", err)
		}
	}

	ready.ready.Store(false)
	cancel()
	signal.Stop(signalCh)
	close(signalCh)

	consumerWg.Wait()
	close(batchCh)
	batchWg.Wait()

	shutdownCtx, shutdownCancel := context.WithTimeout(context.Background(), 15*time.Second)
	defer shutdownCancel()
	if err := server.Shutdown(shutdownCtx); err != nil {
		logger.Warn("http server shutdown failed", "error", err)
	}

	logger.Info("cdc consumer service stopped")
}

func startHTTPServer(port string, ready *readiness, logger *slog.Logger) *http.Server {
	mux := http.NewServeMux()
	mux.HandleFunc("/health", handleHealth)
	mux.HandleFunc("/health/live", handleHealth)
	mux.HandleFunc("/ready", ready.handleReady)
	mux.HandleFunc("/health/ready", ready.handleReady)
	mux.Handle("/metrics", promhttp.Handler())

	server := &http.Server{
		Addr:              ":" + port,
		Handler:           mux,
		ReadHeaderTimeout: 5 * time.Second,
		ReadTimeout:       15 * time.Second,
		WriteTimeout:      15 * time.Second,
		IdleTimeout:       60 * time.Second,
	}

	go func() {
		logger.Info("http server listening", "addr", server.Addr)
		if err := server.ListenAndServe(); err != nil && !errors.Is(err, http.ErrServerClosed) {
			logger.Error("http server failed", "error", err)
		}
	}()
	return server
}

func handleHealth(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodGet && r.Method != http.MethodHead {
		w.Header().Set("Allow", "GET, HEAD")
		writeJSON(w, http.StatusMethodNotAllowed, map[string]string{"error": "method not allowed"})
		return
	}
	writeJSON(w, http.StatusOK, map[string]string{"status": "ok"})
}

func (r *readiness) handleReady(w http.ResponseWriter, req *http.Request) {
	if req.Method != http.MethodGet && req.Method != http.MethodHead {
		w.Header().Set("Allow", "GET, HEAD")
		writeJSON(w, http.StatusMethodNotAllowed, map[string]string{"error": "method not allowed"})
		return
	}
	if r.ready.Load() {
		writeJSON(w, http.StatusOK, map[string]string{"status": "ready"})
		return
	}
	writeJSON(w, http.StatusServiceUnavailable, map[string]string{"status": "not_ready"})
}

func writeJSON(w http.ResponseWriter, status int, payload any) {
	w.Header().Set("Content-Type", "application/json")
	w.WriteHeader(status)
	encoder := json.NewEncoder(w)
	encoder.SetEscapeHTML(false)
	_ = encoder.Encode(payload)
}

func runConsumer(
	ctx context.Context,
	reader *kafka.Reader,
	batchCh chan<- batchItem,
	dlqWriter *kafka.Writer,
	cfg Config,
	metrics Metrics,
	logger *slog.Logger,
	tracer trace.Tracer,
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

		row, err := transformMessage(msg)
		if err != nil {
			span.RecordError(err)
			span.SetStatus(codes.Error, "transform failed")
			span.End()
			if dlqErr := sendToDLQ(msgCtx, dlqWriter, cfg.DLQWriteTimeout, metrics, logger, tracer, []batchItem{{reader: reader, message: msg}}, err); dlqErr != nil {
				reportErr(dlqErr)
				return
			}
			if commitErr := commitMessages(msgCtx, cfg.KafkaCommitTimeout, logger, []batchItem{{reader: reader, message: msg}}); commitErr != nil {
				reportErr(commitErr)
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
	for _, item := range items {
		rows = append(rows, item.row)
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

	start := time.Now()
	failedIndices, err := insertWithRetry(ctx, inserter, rows, cfg, logger)
	metrics.batchLatency.Observe(time.Since(start).Seconds())

	if err != nil {
		span.RecordError(err)
		span.SetStatus(codes.Error, "bigquery insert failed")
		if dlqErr := sendToDLQ(ctx, dlqWriter, cfg.DLQWriteTimeout, metrics, logger, tracer, items, err); dlqErr != nil {
			return dlqErr
		}
		return commitMessages(ctx, cfg.KafkaCommitTimeout, logger, items)
	}

	if len(failedIndices) > 0 {
		failedItems := make([]batchItem, 0, len(failedIndices))
		for _, idx := range failedIndices {
			if idx >= 0 && idx < len(items) {
				failedItems = append(failedItems, items[idx])
			}
		}
		dlqErr := sendToDLQ(ctx, dlqWriter, cfg.DLQWriteTimeout, metrics, logger, tracer, failedItems, errors.New("bigquery row insert failed"))
		if dlqErr != nil {
			return dlqErr
		}
	}

	return commitMessages(ctx, cfg.KafkaCommitTimeout, logger, items)
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

func sendToDLQ(
	ctx context.Context,
	writer *kafka.Writer,
	timeout time.Duration,
	metrics Metrics,
	logger *slog.Logger,
	tracer trace.Tracer,
	items []batchItem,
	reason error,
) error {
	if len(items) == 0 {
		return nil
	}

	dlqReason := truncate(reason.Error(), 512)
	traceID := trace.SpanFromContext(ctx).SpanContext().TraceID().String()
	spanCtx, span := tracer.Start(ctx, "kafka.dlq.write",
		trace.WithAttributes(
			attribute.String("messaging.system", "kafka"),
			attribute.String("messaging.destination", writer.Topic),
			attribute.Int("dlq.count", len(items)),
		),
	)
	defer span.End()
	messages := make([]kafka.Message, 0, len(items))
	for _, item := range items {
		headers := append([]kafka.Header{}, item.message.Headers...)
		headers = append(headers,
			kafka.Header{Key: "dlq_reason", Value: []byte(dlqReason)},
			kafka.Header{Key: "original_topic", Value: []byte(item.message.Topic)},
			kafka.Header{Key: "original_partition", Value: []byte(strconv.Itoa(item.message.Partition))},
			kafka.Header{Key: "original_offset", Value: []byte(strconv.FormatInt(item.message.Offset, 10))},
		)
		if traceID != "" {
			headers = append(headers, kafka.Header{Key: "trace_id", Value: []byte(traceID)})
		}
		messages = append(messages, kafka.Message{
			Key:     item.message.Key,
			Value:   item.message.Value,
			Headers: headers,
			Time:    time.Now(),
		})
	}

	dlqCtx, cancel := context.WithTimeout(spanCtx, timeout)
	defer cancel()
	if err := writer.WriteMessages(dlqCtx, messages...); err != nil {
		span.RecordError(err)
		span.SetStatus(codes.Error, "dlq write failed")
		return fmt.Errorf("dlq write failed: %w", err)
	}
	metrics.dlqCount.Add(float64(len(items)))
	logger.Warn("sent messages to dlq", "count", len(items), "reason", dlqReason)
	return nil
}

func commitMessages(ctx context.Context, timeout time.Duration, logger *slog.Logger, items []batchItem) error {
	if len(items) == 0 {
		return nil
	}
	byReader := make(map[*kafka.Reader][]kafka.Message)
	for _, item := range items {
		byReader[item.reader] = append(byReader[item.reader], item.message)
	}
	for reader, messages := range byReader {
		commitCtx, cancel := context.WithTimeout(ctx, timeout)
		err := reader.CommitMessages(commitCtx, messages...)
		cancel()
		if err != nil {
			logger.Error("kafka commit failed", "error", err)
			return err
		}
	}
	return nil
}

func transformMessage(msg kafka.Message) (bigquery.ValueSaver, error) {
	var payload map[string]any
	if err := json.Unmarshal(msg.Value, &payload); err != nil {
		return nil, err
	}

	var op string
	var tsValue bigquery.Value
	var beforeValue bigquery.Value
	var afterValue bigquery.Value
	var sourceValue bigquery.Value
	var payloadValue bigquery.Value
	if innerPayload, ok := payload["payload"].(map[string]any); ok {
		if opField, ok := innerPayload["op"].(string); ok {
			op = opField
		}
		tsValue = parseTsMs(innerPayload["ts_ms"])
		beforeValue = mustJSONValue(innerPayload["before"])
		afterValue = mustJSONValue(innerPayload["after"])
		sourceValue = mustJSONValue(innerPayload["source"])
		payloadValue = mustJSONValue(innerPayload)
	} else {
		payloadValue = mustJSONValue(payload["payload"])
	}

	headersValue := mustJSONValue(headersToMap(msg.Headers))
	rawValue := bytesToString(msg.Value)
	keyValue := bytesToString(msg.Key)

	values := map[string]bigquery.Value{
		"topic":           msg.Topic,
		"partition":       msg.Partition,
		"offset":          msg.Offset,
		"key":             keyValue,
		"op":              op,
		"ts_ms":           tsValue,
		"source":          sourceValue,
		"before":          beforeValue,
		"after":           afterValue,
		"payload":         payloadValue,
		"headers":         headersValue,
		"raw":             rawValue,
		"kafka_timestamp": msg.Time,
		"ingested_at":     time.Now(),
	}

	insertID := fmt.Sprintf("%s-%d-%d", msg.Topic, msg.Partition, msg.Offset)
	return bqRow{values: values, insertID: insertID}, nil
}

func mustJSONValue(value any) bigquery.Value {
	if value == nil {
		return nil
	}
	data, err := json.Marshal(value)
	if err != nil {
		return nil
	}
	return string(data)
}

func parseTsMs(value any) bigquery.Value {
	switch v := value.(type) {
	case nil:
		return nil
	case float64:
		return int64(v)
	case int64:
		return v
	case int:
		return int64(v)
	case json.Number:
		ts, err := v.Int64()
		if err != nil {
			return nil
		}
		return ts
	case string:
		ts, err := strconv.ParseInt(v, 10, 64)
		if err != nil {
			return nil
		}
		return ts
	default:
		return nil
	}
}

func headersToMap(headers []kafka.Header) map[string]string {
	if len(headers) == 0 {
		return nil
	}
	result := make(map[string]string, len(headers))
	for _, header := range headers {
		result[header.Key] = bytesToString(header.Value)
	}
	return result
}

func bytesToString(value []byte) string {
	if len(value) == 0 {
		return ""
	}
	if utf8.Valid(value) {
		return string(value)
	}
	return base64.StdEncoding.EncodeToString(value)
}

func jitter(base time.Duration) time.Duration {
	return time.Duration(rand.Float64() * 0.2 * float64(base))
}

func truncate(value string, max int) string {
	if len(value) <= max {
		return value
	}
	return value[:max]
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
		return netErr.Timeout() || netErr.Temporary()
	}
	return false
}

func initMetrics() Metrics {
	consumerLag := prometheus.NewGaugeVec(prometheus.GaugeOpts{
		Name: "cdc_consumer_lag",
		Help: "Kafka consumer lag per topic and partition.",
	}, []string{"topic", "partition"})

	batchLatency := prometheus.NewHistogram(prometheus.HistogramOpts{
		Name:    "cdc_batch_latency_seconds",
		Help:    "Latency for BigQuery batch inserts.",
		Buckets: prometheus.DefBuckets,
	})

	dlqCount := prometheus.NewCounter(prometheus.CounterOpts{
		Name: "cdc_dlq_total",
		Help: "Number of messages sent to the Kafka DLQ.",
	})

	prometheus.MustRegister(consumerLag, batchLatency, dlqCount)
	return Metrics{
		consumerLag:  consumerLag,
		batchLatency: batchLatency,
		dlqCount:     dlqCount,
	}
}

func setupLogger(level slog.Level) *slog.Logger {
	handler := slog.NewJSONHandler(os.Stdout, &slog.HandlerOptions{Level: level})
	logger := slog.New(handler)
	slog.SetDefault(logger)
	return logger
}

func setupTracing(ctx context.Context, logger *slog.Logger) (*sdktrace.TracerProvider, trace.Tracer) {
	var opts []otlptracegrpc.Option
	if endpoint := strings.TrimSpace(os.Getenv("OTEL_EXPORTER_OTLP_ENDPOINT")); endpoint != "" {
		parsed, err := url.Parse(endpoint)
		if err != nil {
			logger.Warn("invalid otlp endpoint", "endpoint", endpoint, "error", err)
		} else {
			target := parsed.Host
			if target == "" {
				target = parsed.Path
			}
			if target != "" {
				opts = append(opts, otlptracegrpc.WithEndpoint(target))
				if parsed.Scheme == "" || parsed.Scheme == "http" {
					opts = append(opts, otlptracegrpc.WithInsecure())
				}
			}
		}
	} else {
		opts = append(opts, otlptracegrpc.WithInsecure())
	}

	exporter, err := otlptracegrpc.New(ctx, opts...)
	if err != nil {
		logger.Warn("failed to initialize otlp exporter", "error", err)
		tracerProvider := sdktrace.NewTracerProvider(
			sdktrace.WithResource(resource.NewWithAttributes(semconv.SchemaURL, semconv.ServiceName(serviceName))),
		)
		otel.SetTracerProvider(tracerProvider)
		return tracerProvider, otel.Tracer(serviceName)
	}

	res, err := resource.New(ctx, resource.WithAttributes(semconv.ServiceName(serviceName)))
	if err != nil {
		logger.Warn("failed to create otel resource", "error", err)
	}

	tracerProvider := sdktrace.NewTracerProvider(
		sdktrace.WithBatcher(exporter),
		sdktrace.WithResource(res),
	)
	otel.SetTracerProvider(tracerProvider)
	return tracerProvider, otel.Tracer(serviceName)
}

func loadConfig() (Config, error) {
	var cfg Config
	var err error

	cfg.Port = getEnv("PORT", getEnv("SERVER_PORT", "8104"))
	cfg.KafkaGroupID = getEnv("KAFKA_GROUP_ID", serviceName)
	cfg.KafkaDLQTopic = getEnv("KAFKA_DLQ_TOPIC", "cdc.dlq")

	if cfg.LogLevel, err = parseLogLevel(getEnv("LOG_LEVEL", "info")); err != nil {
		return cfg, err
	}

	brokers := strings.TrimSpace(os.Getenv("KAFKA_BROKERS"))
	if brokers == "" {
		return cfg, errors.New("KAFKA_BROKERS is required")
	}
	cfg.KafkaBrokers = splitAndTrim(brokers)
	if len(cfg.KafkaBrokers) == 0 {
		return cfg, errors.New("KAFKA_BROKERS must contain at least one broker")
	}

	topics := strings.TrimSpace(os.Getenv("KAFKA_TOPICS"))
	if topics == "" {
		return cfg, errors.New("KAFKA_TOPICS is required")
	}
	cfg.KafkaTopics = splitAndTrim(topics)
	if len(cfg.KafkaTopics) == 0 {
		return cfg, errors.New("KAFKA_TOPICS must contain at least one topic")
	}

	cfg.BQProject = strings.TrimSpace(os.Getenv("BQ_PROJECT"))
	cfg.BQDataset = strings.TrimSpace(os.Getenv("BQ_DATASET"))
	cfg.BQTable = strings.TrimSpace(os.Getenv("BQ_TABLE"))
	if cfg.BQProject == "" || cfg.BQDataset == "" || cfg.BQTable == "" {
		return cfg, errors.New("BQ_PROJECT, BQ_DATASET, and BQ_TABLE are required")
	}

	if cfg.KafkaMinBytes, err = getEnvInt("KAFKA_MIN_BYTES", 10*1024); err != nil {
		return cfg, err
	}
	if cfg.KafkaMaxBytes, err = getEnvInt("KAFKA_MAX_BYTES", 10*1024*1024); err != nil {
		return cfg, err
	}
	if cfg.KafkaMaxWait, err = getEnvDuration("KAFKA_MAX_WAIT", 5*time.Second); err != nil {
		return cfg, err
	}
	if cfg.KafkaCommitTimeout, err = getEnvDuration("KAFKA_COMMIT_TIMEOUT", 10*time.Second); err != nil {
		return cfg, err
	}
	if cfg.BatchSize, err = getEnvInt("BQ_BATCH_SIZE", 500); err != nil {
		return cfg, err
	}
	if cfg.BatchSize <= 0 {
		return cfg, errors.New("BQ_BATCH_SIZE must be greater than 0")
	}
	if cfg.BatchTimeout, err = getEnvDuration("BQ_BATCH_TIMEOUT", 5*time.Second); err != nil {
		return cfg, err
	}
	if cfg.BQInsertTimeout, err = getEnvDuration("BQ_INSERT_TIMEOUT", 30*time.Second); err != nil {
		return cfg, err
	}
	if cfg.BQMaxRetries, err = getEnvInt("BQ_MAX_RETRIES", 5); err != nil {
		return cfg, err
	}
	if cfg.BQBaseBackoff, err = getEnvDuration("BQ_BACKOFF_BASE", time.Second); err != nil {
		return cfg, err
	}
	if cfg.BQMaxBackoff, err = getEnvDuration("BQ_BACKOFF_MAX", 30*time.Second); err != nil {
		return cfg, err
	}
	if cfg.DLQWriteTimeout, err = getEnvDuration("DLQ_WRITE_TIMEOUT", 10*time.Second); err != nil {
		return cfg, err
	}

	return cfg, nil
}

func getEnv(key, fallback string) string {
	if value := strings.TrimSpace(os.Getenv(key)); value != "" {
		return value
	}
	return fallback
}

func parseLogLevel(value string) (slog.Level, error) {
	switch strings.ToLower(value) {
	case "debug":
		return slog.LevelDebug, nil
	case "info":
		return slog.LevelInfo, nil
	case "warn", "warning":
		return slog.LevelWarn, nil
	case "error":
		return slog.LevelError, nil
	default:
		return slog.LevelInfo, fmt.Errorf("invalid log level %q", value)
	}
}

func getEnvDuration(key string, fallback time.Duration) (time.Duration, error) {
	value := strings.TrimSpace(os.Getenv(key))
	if value == "" {
		return fallback, nil
	}
	parsed, err := time.ParseDuration(value)
	if err != nil {
		return 0, fmt.Errorf("invalid duration for %s: %w", key, err)
	}
	return parsed, nil
}

func getEnvInt(key string, fallback int) (int, error) {
	value := strings.TrimSpace(os.Getenv(key))
	if value == "" {
		return fallback, nil
	}
	parsed, err := strconv.Atoi(value)
	if err != nil {
		return 0, fmt.Errorf("invalid int for %s: %w", key, err)
	}
	return parsed, nil
}

func splitAndTrim(value string) []string {
	parts := strings.Split(value, ",")
	result := make([]string, 0, len(parts))
	for _, part := range parts {
		part = strings.TrimSpace(part)
		if part != "" {
			result = append(result, part)
		}
	}
	return result
}
