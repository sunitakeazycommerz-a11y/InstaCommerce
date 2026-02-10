package main

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"log/slog"
	"net/http"
	"os"
	"os/signal"
	"strconv"
	"strings"
	"sync"
	"sync/atomic"
	"syscall"
	"time"

	"github.com/IBM/sarama"
	"github.com/jackc/pgx/v5"
	"github.com/jackc/pgx/v5/pgxpool"
	"go.opentelemetry.io/otel"
	"go.opentelemetry.io/otel/attribute"
	"go.opentelemetry.io/otel/exporters/otlp/otlpmetric/otlpmetrichttp"
	"go.opentelemetry.io/otel/exporters/otlp/otlptrace/otlptracehttp"
	"go.opentelemetry.io/otel/metric"
	"go.opentelemetry.io/otel/propagation"
	"go.opentelemetry.io/otel/sdk/resource"
	sdkmetric "go.opentelemetry.io/otel/sdk/metric"
	sdktrace "go.opentelemetry.io/otel/sdk/trace"
	"go.opentelemetry.io/otel/semconv/v1.26.0"
	"go.opentelemetry.io/otel/trace"
)

const (
	defaultPort          = "8103"
	defaultPollInterval  = 1 * time.Second
	defaultBatchSize     = 100
	defaultShutdownGrace = 20 * time.Second
	defaultReadyTimeout  = 2 * time.Second
)

type Config struct {
	Port            string
	DatabaseURL     string
	PollInterval    time.Duration
	BatchSize       int
	OutboxTable     string
	KafkaBrokers    []string
	KafkaTopic      string
	KafkaClientID   string
	ShutdownTimeout time.Duration
	ReadyTimeout    time.Duration
	OTelServiceName string
	OTLPEndpoint    string
	LogLevel        slog.Level
}

type telemetry struct {
	tracer   trace.Tracer
	meter    metric.Meter
	shutdown func(context.Context) error
}

type relayMetrics struct {
	relayed  metric.Int64Counter
	failures metric.Int64Counter
	lag      metric.Float64Histogram
}

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
}

type outboxEvent struct {
	ID            string
	AggregateType string
	AggregateID   string
	EventType     string
	Payload       []byte
	CreatedAt     time.Time
}

type errorResponse struct {
	Error string `json:"error"`
}

type statusResponse struct {
	Status string `json:"status"`
}

func main() {
	cfg, err := loadConfig()
	if err != nil {
		slog.Error("failed to load config", "error", err)
		os.Exit(1)
	}

	logger := slog.New(slog.NewJSONHandler(os.Stdout, &slog.HandlerOptions{Level: cfg.LogLevel}))
	slog.SetDefault(logger)

	ctx := context.Background()
	telemetry, err := setupTelemetry(ctx, cfg, logger)
	if err != nil {
		logger.Error("failed to set up telemetry", "error", err)
		os.Exit(1)
	}

	dbPool, err := pgxpool.New(ctx, cfg.DatabaseURL)
	if err != nil {
		logger.Error("failed to connect to postgres", "error", err)
		os.Exit(1)
	}
	defer dbPool.Close()

	if err := dbPool.Ping(ctx); err != nil {
		logger.Error("postgres ping failed", "error", err)
		os.Exit(1)
	}

	producer, kafkaConfig, err := newKafkaProducer(cfg)
	if err != nil {
		logger.Error("failed to create kafka producer", "error", err)
		os.Exit(1)
	}
	defer func() {
		if err := producer.Close(); err != nil {
			logger.Error("failed to close kafka producer", "error", err)
		}
	}()

	metrics, err := newRelayMetrics(telemetry.meter)
	if err != nil {
		logger.Error("failed to initialize metrics", "error", err)
		os.Exit(1)
	}

	service, err := newRelayService(cfg, logger, dbPool, producer, kafkaConfig, telemetry.tracer, metrics)
	if err != nil {
		logger.Error("failed to initialize relay service", "error", err)
		os.Exit(1)
	}

	mux := http.NewServeMux()
	mux.HandleFunc("/health", service.handleHealth)
	mux.HandleFunc("/health/live", service.handleHealth)
	mux.HandleFunc("/ready", service.handleReady)
	mux.HandleFunc("/health/ready", service.handleReady)

	server := &http.Server{
		Addr:              ":" + cfg.Port,
		Handler:           mux,
		ReadHeaderTimeout: 5 * time.Second,
		ReadTimeout:       15 * time.Second,
		WriteTimeout:      15 * time.Second,
		IdleTimeout:       60 * time.Second,
	}

	errCh := make(chan error, 1)
	go func() {
		logger.Info("http server listening", "addr", server.Addr)
		if err := server.ListenAndServe(); err != nil && !errors.Is(err, http.ErrServerClosed) {
			errCh <- err
		}
		close(errCh)
	}()

	pollCtx, pollCancel := context.WithCancel(ctx)
	var wg sync.WaitGroup
	wg.Add(1)
	go func() {
		defer wg.Done()
		service.pollLoop(pollCtx)
	}()

	quit := make(chan os.Signal, 1)
	signal.Notify(quit, syscall.SIGINT, syscall.SIGTERM)

	select {
	case sig := <-quit:
		logger.Info("received shutdown signal", "signal", sig.String())
	case err := <-errCh:
		if err != nil {
			logger.Error("http server error", "error", err)
		}
	}

	service.shuttingDown.Store(true)
	pollCancel()

	shutdownCtx, shutdownCancel := context.WithTimeout(context.Background(), cfg.ShutdownTimeout)
	defer shutdownCancel()

	if err := server.Shutdown(shutdownCtx); err != nil {
		logger.Error("http server shutdown failed", "error", err)
	}

	drainDone := make(chan struct{})
	go func() {
		wg.Wait()
		close(drainDone)
	}()

	select {
	case <-drainDone:
		logger.Info("relay worker drained")
	case <-shutdownCtx.Done():
		logger.Warn("relay worker drain timed out")
	}

	if err := telemetry.shutdown(shutdownCtx); err != nil {
		logger.Error("telemetry shutdown failed", "error", err)
	}

	logger.Info("shutdown complete")
}

func loadConfig() (Config, error) {
	port := envOrDefault("PORT", envOrDefault("SERVER_PORT", defaultPort))
	databaseURL := envOrDefault("DATABASE_URL", envOrDefault("POSTGRES_DSN", ""))
	if databaseURL == "" {
		return Config{}, errors.New("DATABASE_URL or POSTGRES_DSN is required")
	}

	pollInterval, err := durationEnv("OUTBOX_POLL_INTERVAL", defaultPollInterval)
	if err != nil {
		return Config{}, err
	}

	batchSize, err := intEnv("OUTBOX_BATCH_SIZE", defaultBatchSize)
	if err != nil {
		return Config{}, err
	}
	if batchSize <= 0 {
		return Config{}, errors.New("OUTBOX_BATCH_SIZE must be greater than 0")
	}

	outboxTable := envOrDefault("OUTBOX_TABLE", "outbox_events")
	if !isSafeIdentifier(outboxTable) {
		return Config{}, fmt.Errorf("OUTBOX_TABLE %q contains invalid characters", outboxTable)
	}

	brokerList := envOrDefault("KAFKA_BROKERS", "")
	if brokerList == "" {
		return Config{}, errors.New("KAFKA_BROKERS is required")
	}
	brokers := splitAndTrim(brokerList)
	if len(brokers) == 0 {
		return Config{}, errors.New("KAFKA_BROKERS must include at least one broker")
	}

	shutdownTimeout, err := durationEnv("SHUTDOWN_TIMEOUT", defaultShutdownGrace)
	if err != nil {
		return Config{}, err
	}

	readyTimeout, err := durationEnv("READY_TIMEOUT", defaultReadyTimeout)
	if err != nil {
		return Config{}, err
	}

	logLevel := parseLogLevel(envOrDefault("LOG_LEVEL", "info"))

	return Config{
		Port:            port,
		DatabaseURL:     databaseURL,
		PollInterval:    pollInterval,
		BatchSize:       batchSize,
		OutboxTable:     outboxTable,
		KafkaBrokers:    brokers,
		KafkaTopic:      envOrDefault("OUTBOX_TOPIC", ""),
		KafkaClientID:   envOrDefault("KAFKA_CLIENT_ID", "outbox-relay-service"),
		ShutdownTimeout: shutdownTimeout,
		ReadyTimeout:    readyTimeout,
		OTelServiceName: envOrDefault("OTEL_SERVICE_NAME", "outbox-relay-service"),
		OTLPEndpoint:    envOrDefault("OTEL_EXPORTER_OTLP_ENDPOINT", ""),
		LogLevel:        logLevel,
	}, nil
}

func setupTelemetry(ctx context.Context, cfg Config, logger *slog.Logger) (*telemetry, error) {
	res, err := resource.New(ctx, resource.WithAttributes(semconv.ServiceName(cfg.OTelServiceName)))
	if err != nil {
		logger.Warn("failed to create otel resource", "error", err)
	}
	if res == nil {
		res = resource.Empty()
	}

	traceProvider := newTraceProvider(ctx, cfg, logger, res)
	metricProvider := newMetricProvider(ctx, cfg, logger, res)

	otel.SetTracerProvider(traceProvider)
	otel.SetMeterProvider(metricProvider)
	otel.SetTextMapPropagator(propagation.NewCompositeTextMapPropagator(propagation.TraceContext{}, propagation.Baggage{}))

	shutdown := func(ctx context.Context) error {
		var shutdownErr error
		if err := traceProvider.Shutdown(ctx); err != nil && shutdownErr == nil {
			shutdownErr = err
		}
		if err := metricProvider.Shutdown(ctx); err != nil && shutdownErr == nil {
			shutdownErr = err
		}
		return shutdownErr
	}

	return &telemetry{
		tracer:   otel.Tracer("outbox-relay"),
		meter:    otel.Meter("outbox-relay"),
		shutdown: shutdown,
	}, nil
}

func newTraceProvider(ctx context.Context, cfg Config, logger *slog.Logger, res *resource.Resource) *sdktrace.TracerProvider {
	if cfg.OTLPEndpoint == "" {
		return sdktrace.NewTracerProvider(sdktrace.WithResource(res))
	}

	opts := otlpHTTPEndpointOptions(cfg.OTLPEndpoint)
	traceExporter, err := otlptracehttp.New(ctx, opts...)
	if err != nil {
		logger.Warn("failed to create otel trace exporter, using noop exporter", "error", err)
		return sdktrace.NewTracerProvider(sdktrace.WithResource(res))
	}

	return sdktrace.NewTracerProvider(
		sdktrace.WithResource(res),
		sdktrace.WithBatcher(traceExporter),
	)
}

func newMetricProvider(ctx context.Context, cfg Config, logger *slog.Logger, res *resource.Resource) *sdkmetric.MeterProvider {
	if cfg.OTLPEndpoint == "" {
		return sdkmetric.NewMeterProvider(sdkmetric.WithResource(res))
	}

	opts := otlpMetricHTTPEndpointOptions(cfg.OTLPEndpoint)
	metricExporter, err := otlpmetrichttp.New(ctx, opts...)
	if err != nil {
		logger.Warn("failed to create otel metric exporter, using noop exporter", "error", err)
		return sdkmetric.NewMeterProvider(sdkmetric.WithResource(res))
	}

	reader := sdkmetric.NewPeriodicReader(metricExporter, sdkmetric.WithInterval(15*time.Second))
	return sdkmetric.NewMeterProvider(
		sdkmetric.WithResource(res),
		sdkmetric.WithReader(reader),
	)
}

func otlpHTTPEndpointOptions(endpoint string) []otlptracehttp.Option {
	opts := []otlptracehttp.Option{}
	if strings.HasPrefix(endpoint, "https://") {
		return append(opts, otlptracehttp.WithEndpointURL(endpoint))
	}
	if strings.HasPrefix(endpoint, "http://") {
		return append(opts, otlptracehttp.WithEndpointURL(endpoint), otlptracehttp.WithInsecure())
	}
	return append(opts, otlptracehttp.WithEndpoint(endpoint), otlptracehttp.WithInsecure())
}

func otlpMetricHTTPEndpointOptions(endpoint string) []otlpmetrichttp.Option {
	opts := []otlpmetrichttp.Option{}
	if strings.HasPrefix(endpoint, "https://") {
		return append(opts, otlpmetrichttp.WithEndpointURL(endpoint))
	}
	if strings.HasPrefix(endpoint, "http://") {
		return append(opts, otlpmetrichttp.WithEndpointURL(endpoint), otlpmetrichttp.WithInsecure())
	}
	return append(opts, otlpmetrichttp.WithEndpoint(endpoint), otlpmetrichttp.WithInsecure())
}

func newRelayMetrics(meter metric.Meter) (relayMetrics, error) {
	relayed, err := meter.Int64Counter(
		"outbox.relay.count",
		metric.WithDescription("Number of outbox events successfully relayed."),
	)
	if err != nil {
		return relayMetrics{}, err
	}
	failures, err := meter.Int64Counter(
		"outbox.relay.failures",
		metric.WithDescription("Number of outbox relay failures."),
	)
	if err != nil {
		return relayMetrics{}, err
	}
	lag, err := meter.Float64Histogram(
		"outbox.relay.lag.seconds",
		metric.WithDescription("Lag between outbox event creation and relay."),
		metric.WithUnit("s"),
	)
	if err != nil {
		return relayMetrics{}, err
	}
	return relayMetrics{relayed: relayed, failures: failures, lag: lag}, nil
}

func newRelayService(cfg Config, logger *slog.Logger, db *pgxpool.Pool, producer sarama.SyncProducer, kafkaConfig *sarama.Config, tracer trace.Tracer, metrics relayMetrics) (*relayService, error) {
	table := pgx.Identifier{cfg.OutboxTable}.Sanitize()
	selectSQL := fmt.Sprintf(`SELECT id::text, aggregate_type, aggregate_id, event_type, payload, created_at
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
	}, nil
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
			_ = tx.Rollback(ctx)
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
		if err := rows.Scan(&evt.ID, &evt.AggregateType, &evt.AggregateID, &evt.EventType, &evt.Payload, &evt.CreatedAt); err != nil {
			span.RecordError(err)
			return s.fail(ctx, err)
		}
		events = append(events, evt)
	}
	if err := rows.Err(); err != nil {
		span.RecordError(err)
		return s.fail(ctx, err)
	}

	if len(events) == 0 {
		return nil
	}

	var sendErr error
	for _, evt := range events {
		if ctx.Err() != nil {
			sendErr = ctx.Err()
			break
		}

		topic := s.cfg.KafkaTopic
		if topic == "" {
			topic = evt.AggregateType
		}

		msgCtx, msgSpan := s.tracer.Start(ctx, "outbox.relay.publish",
			trace.WithAttributes(
				attribute.String("event.id", evt.ID),
				attribute.String("event.type", evt.EventType),
				attribute.String("aggregate.type", evt.AggregateType),
				attribute.String("aggregate.id", evt.AggregateID),
				attribute.String("kafka.topic", topic),
			),
		)

		message := &sarama.ProducerMessage{
			Topic: topic,
			Key:   sarama.StringEncoder(evt.AggregateID),
			Value: sarama.ByteEncoder(evt.Payload),
			Headers: []sarama.RecordHeader{
				{Key: []byte("event_id"), Value: []byte(evt.ID)},
				{Key: []byte("event_type"), Value: []byte(evt.EventType)},
				{Key: []byte("aggregate_type"), Value: []byte(evt.AggregateType)},
			},
		}

		if _, _, err := s.producer.SendMessage(message); err != nil {
			s.metrics.failures.Add(msgCtx, 1)
			msgSpan.RecordError(err)
			msgSpan.End()
			sendErr = err
			s.logger.Error("failed to publish message", "error", err, "event_id", evt.ID, "topic", topic)
			break
		}

		lag := time.Since(evt.CreatedAt).Seconds()
		s.metrics.relayed.Add(msgCtx, 1)
		s.metrics.lag.Record(msgCtx, lag)
		msgSpan.End()

		if _, err := tx.Exec(ctx, s.updateSQL, evt.ID); err != nil {
			span.RecordError(err)
			return s.fail(ctx, err)
		}

		s.logger.Debug("relayed outbox event", "event_id", evt.ID, "topic", topic, "lag_seconds", lag)
	}

	if err := tx.Commit(ctx); err != nil {
		span.RecordError(err)
		return s.fail(ctx, err)
	}
	committed = true

	if sendErr != nil {
		return sendErr
	}
	return nil
}

func (s *relayService) fail(ctx context.Context, err error) error {
	if err != nil && !errors.Is(err, context.Canceled) {
		s.metrics.failures.Add(ctx, 1)
	}
	return err
}

func (s *relayService) handleHealth(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodGet && r.Method != http.MethodHead {
		w.Header().Set("Allow", "GET, HEAD")
		writeError(w, http.StatusMethodNotAllowed, "method not allowed")
		return
	}
	writeJSON(w, http.StatusOK, statusResponse{Status: "ok"})
}

func (s *relayService) handleReady(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodGet && r.Method != http.MethodHead {
		w.Header().Set("Allow", "GET, HEAD")
		writeError(w, http.StatusMethodNotAllowed, "method not allowed")
		return
	}
	if s.shuttingDown.Load() {
		writeError(w, http.StatusServiceUnavailable, "shutting down")
		return
	}

	ctx, cancel := context.WithTimeout(r.Context(), s.cfg.ReadyTimeout)
	defer cancel()

	if err := s.db.Ping(ctx); err != nil {
		writeError(w, http.StatusServiceUnavailable, "database unavailable")
		return
	}

	if err := s.checkKafka(ctx); err != nil {
		writeError(w, http.StatusServiceUnavailable, "kafka unavailable")
		return
	}

	writeJSON(w, http.StatusOK, statusResponse{Status: "ready"})
}

func (s *relayService) checkKafka(ctx context.Context) error {
	result := make(chan error, 1)
	go func() {
		client, err := sarama.NewClient(s.cfg.KafkaBrokers, s.kafkaConfig)
		if err != nil {
			result <- err
			return
		}
		defer client.Close()
		_, err = client.Controller()
		result <- err
	}()

	select {
	case <-ctx.Done():
		return ctx.Err()
	case err := <-result:
		return err
	}
}

func newKafkaProducer(cfg Config) (sarama.SyncProducer, *sarama.Config, error) {
	timeout := cfg.ReadyTimeout
	if timeout < 5*time.Second {
		timeout = 5 * time.Second
	}

	kafkaConfig := sarama.NewConfig()
	kafkaConfig.ClientID = cfg.KafkaClientID
	kafkaConfig.Version = sarama.V2_5_0_0
	kafkaConfig.Net.MaxOpenRequests = 1
	kafkaConfig.Net.DialTimeout = timeout
	kafkaConfig.Net.ReadTimeout = timeout
	kafkaConfig.Net.WriteTimeout = timeout
	kafkaConfig.Producer.RequiredAcks = sarama.WaitForAll
	kafkaConfig.Producer.Retry.Max = 10
	kafkaConfig.Producer.Return.Successes = true
	kafkaConfig.Producer.Idempotent = true

	producer, err := sarama.NewSyncProducer(cfg.KafkaBrokers, kafkaConfig)
	if err != nil {
		return nil, nil, err
	}
	return producer, kafkaConfig, nil
}

func envOrDefault(key, fallback string) string {
	if value := strings.TrimSpace(os.Getenv(key)); value != "" {
		return value
	}
	return fallback
}

func intEnv(key string, fallback int) (int, error) {
	raw := strings.TrimSpace(os.Getenv(key))
	if raw == "" {
		return fallback, nil
	}
	val, err := strconv.Atoi(raw)
	if err != nil {
		return 0, fmt.Errorf("%s must be an integer", key)
	}
	return val, nil
}

func durationEnv(key string, fallback time.Duration) (time.Duration, error) {
	raw := strings.TrimSpace(os.Getenv(key))
	if raw == "" {
		return fallback, nil
	}
	val, err := time.ParseDuration(raw)
	if err != nil {
		return 0, fmt.Errorf("%s must be a duration", key)
	}
	return val, nil
}

func parseLogLevel(raw string) slog.Level {
	switch strings.ToLower(strings.TrimSpace(raw)) {
	case "debug":
		return slog.LevelDebug
	case "warn", "warning":
		return slog.LevelWarn
	case "error":
		return slog.LevelError
	default:
		return slog.LevelInfo
	}
}

func splitAndTrim(raw string) []string {
	parts := strings.Split(raw, ",")
	out := make([]string, 0, len(parts))
	for _, part := range parts {
		if trimmed := strings.TrimSpace(part); trimmed != "" {
			out = append(out, trimmed)
		}
	}
	return out
}

func isSafeIdentifier(value string) bool {
	if value == "" {
		return false
	}
	for _, r := range value {
		switch {
		case r >= 'a' && r <= 'z':
		case r >= 'A' && r <= 'Z':
		case r >= '0' && r <= '9':
		case r == '_':
		default:
			return false
		}
	}
	return true
}

func writeJSON(w http.ResponseWriter, status int, payload any) {
	w.Header().Set("Content-Type", "application/json")
	w.WriteHeader(status)
	encoder := json.NewEncoder(w)
	encoder.SetEscapeHTML(false)
	_ = encoder.Encode(payload)
}

func writeError(w http.ResponseWriter, status int, message string) {
	writeJSON(w, status, errorResponse{Error: message})
}
