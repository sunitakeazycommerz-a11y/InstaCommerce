package main

import (
	"bytes"
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"log/slog"
	"net/http"
	"os"
	"os/signal"
	"strconv"
	"strings"
	"sync"
	"syscall"
	"time"

	"github.com/gorilla/websocket"
	"github.com/prometheus/client_golang/prometheus"
	"github.com/prometheus/client_golang/prometheus/promhttp"
	"github.com/redis/go-redis/v9"
	"github.com/segmentio/kafka-go"
	"go.opentelemetry.io/contrib/instrumentation/net/http/otelhttp"
	"go.opentelemetry.io/otel"
	"go.opentelemetry.io/otel/exporters/otlp/otlptrace/otlptracegrpc"
	"go.opentelemetry.io/otel/propagation"
	"go.opentelemetry.io/otel/sdk/resource"
	sdktrace "go.opentelemetry.io/otel/sdk/trace"
	"go.opentelemetry.io/otel/trace"
	semconv "go.opentelemetry.io/otel/semconv/v1.25.0"
)

const (
	defaultPort              = "8105"
	defaultKafkaTopic        = "rider.location.updates"
	defaultKafkaBatchSize    = 200
	defaultKafkaBatchTimeout = 1 * time.Second
	defaultKafkaWriteTimeout = 5 * time.Second
	defaultKafkaBuffer       = 2000
	defaultRedisAddr         = "localhost:6379"
	defaultRedisKeyPrefix    = "rider:location:"
	defaultMaxBodyBytes      = 1 << 20
	defaultWebSocketRead     = 1 << 20
	defaultShutdownTimeout   = 15 * time.Second
	defaultProcessTimeout    = 3 * time.Second
)

type Config struct {
	Port              string
	ServiceName       string
	KafkaBrokers      []string
	KafkaTopic        string
	KafkaBatchSize    int
	KafkaBatchTimeout time.Duration
	KafkaWriteTimeout time.Duration
	KafkaBuffer       int
	RedisAddr         string
	RedisPassword     string
	RedisDB           int
	RedisKeyPrefix    string
	RedisTTL          time.Duration
	MaxBodyBytes      int64
	WebSocketReadMax  int64
	ShutdownTimeout   time.Duration
	ProcessTimeout    time.Duration
	H3Resolution      int
	LogLevel          slog.Level
}

type LocationInput struct {
	RiderID   string   `json:"rider_id"`
	Lat       float64  `json:"lat"`
	Lng       float64  `json:"lng"`
	Timestamp *int64   `json:"timestamp,omitempty"`
	Speed     *float64 `json:"speed,omitempty"`
	Heading   *float64 `json:"heading,omitempty"`
	Accuracy  *float64 `json:"accuracy,omitempty"`
}

type LocationUpdate struct {
	RiderID   string
	Lat       float64
	Lng       float64
	Timestamp time.Time
	Speed     *float64
	Heading   *float64
	Accuracy  *float64
	H3Index   string
	Geofence  bool
	Source    string
}

type LocationEvent struct {
	RiderID     string   `json:"rider_id"`
	Lat         float64  `json:"lat"`
	Lng         float64  `json:"lng"`
	TimestampMS int64    `json:"timestamp_ms"`
	Speed       *float64 `json:"speed,omitempty"`
	Heading     *float64 `json:"heading,omitempty"`
	Accuracy    *float64 `json:"accuracy,omitempty"`
	H3Index     string   `json:"h3_index,omitempty"`
	GeofenceHit bool     `json:"geofence_hit,omitempty"`
	Source      string   `json:"source"`
	IngestedAt  string   `json:"ingested_at"`
}

type errorResponse struct {
	Error string `json:"error"`
}

type Metrics struct {
	ingestTotal *prometheus.CounterVec
	dropTotal   *prometheus.CounterVec
	latency     *prometheus.HistogramVec
}

type H3Stub struct {
	enabled    bool
	resolution int
}

func (h H3Stub) Index(lat, lng float64) string {
	if !h.enabled {
		return ""
	}
	return fmt.Sprintf("h3stub-%d-%.5f-%.5f", h.resolution, lat, lng)
}

func (h H3Stub) GeofenceHit(lat, lng float64) bool {
	if !h.enabled {
		return false
	}
	return false
}

type KafkaBatcher struct {
	writer       *kafka.Writer
	input        chan LocationUpdate
	batchSize    int
	batchTimeout time.Duration
	writeTimeout time.Duration
	logger       *slog.Logger
	metrics      *Metrics
}

type IngestService struct {
	cfg     Config
	logger  *slog.Logger
	metrics *Metrics
	redis   *redis.Client
	batcher *KafkaBatcher
	tracer  trace.Tracer
	h3      H3Stub
}

func main() {
	cfg := loadConfig()
	logger := newLogger(cfg)
	metrics := newMetrics()

	ctx := context.Background()
	tp := initTracing(ctx, cfg, logger)
	defer func() {
		if tp != nil {
			shutdownCtx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
			defer cancel()
			_ = tp.Shutdown(shutdownCtx)
		}
	}()

	redisClient := redis.NewClient(&redis.Options{
		Addr:     cfg.RedisAddr,
		Password: cfg.RedisPassword,
		DB:       cfg.RedisDB,
	})

	var kafkaWriter *kafka.Writer
	if len(cfg.KafkaBrokers) > 0 {
		kafkaWriter = &kafka.Writer{
			Addr:                   kafka.TCP(cfg.KafkaBrokers...),
			Topic:                  cfg.KafkaTopic,
			Balancer:               &kafka.Hash{},
			RequiredAcks:           kafka.RequireAll,
			AllowAutoTopicCreation: true,
			WriteTimeout:           cfg.KafkaWriteTimeout,
		}
	}

	batcher := &KafkaBatcher{
		writer:       kafkaWriter,
		input:        make(chan LocationUpdate, cfg.KafkaBuffer),
		batchSize:    cfg.KafkaBatchSize,
		batchTimeout: cfg.KafkaBatchTimeout,
		writeTimeout: cfg.KafkaWriteTimeout,
		logger:       logger,
		metrics:      metrics,
	}

	service := &IngestService{
		cfg:     cfg,
		logger:  logger,
		metrics: metrics,
		redis:   redisClient,
		batcher: batcher,
		tracer:  otel.Tracer(cfg.ServiceName),
		h3: H3Stub{
			enabled:    cfg.H3Resolution > 0,
			resolution: cfg.H3Resolution,
		},
	}
	if cfg.H3Resolution > 0 {
		logger.Info("h3 geofence stub enabled", "resolution", cfg.H3Resolution)
	}

	backgroundCtx, cancel := context.WithCancel(context.Background())
	var wg sync.WaitGroup
	wg.Add(1)
	go func() {
		defer wg.Done()
		batcher.Run(backgroundCtx)
	}()

	mux := http.NewServeMux()
	mux.Handle("/metrics", promhttp.Handler())
	mux.HandleFunc("/health", handleHealth)
	mux.HandleFunc("/health/live", handleHealth)
	mux.HandleFunc("/health/ready", service.handleReady)
	mux.HandleFunc("/ready", service.handleReady)
	mux.HandleFunc("/ingest/location", service.handleHTTPIngest)
	mux.HandleFunc("/ingest/ws", service.handleWSIngest)

	handler := otelhttp.NewHandler(mux, "http.server")

	server := &http.Server{
		Addr:              ":" + cfg.Port,
		Handler:           handler,
		ReadHeaderTimeout: 5 * time.Second,
		ReadTimeout:       10 * time.Second,
		WriteTimeout:      10 * time.Second,
		IdleTimeout:       60 * time.Second,
	}

	errCh := make(chan error, 1)
	go func() {
		logger.Info("location ingestion service listening", "addr", server.Addr)
		if err := server.ListenAndServe(); err != nil && !errors.Is(err, http.ErrServerClosed) {
			errCh <- err
		}
		close(errCh)
	}()

	quit := make(chan os.Signal, 1)
	signal.Notify(quit, syscall.SIGINT, syscall.SIGTERM)

	select {
	case sig := <-quit:
		logger.Info("shutdown signal received", "signal", sig.String())
	case err := <-errCh:
		if err != nil {
			logger.Error("server error", "error", err)
		}
	}

	shutdownCtx, shutdownCancel := context.WithTimeout(context.Background(), cfg.ShutdownTimeout)
	defer shutdownCancel()
	if err := server.Shutdown(shutdownCtx); err != nil {
		logger.Error("server shutdown failed", "error", err)
	}

	cancel()
	wg.Wait()

	if kafkaWriter != nil {
		_ = kafkaWriter.Close()
	}
	_ = redisClient.Close()

	logger.Info("service stopped")
}

func loadConfig() Config {
	port := envOrDefault("PORT", "")
	if port == "" {
		port = envOrDefault("SERVER_PORT", defaultPort)
	}
	kafkaBatchSize := atLeast(envOrDefaultInt("KAFKA_BATCH_SIZE", defaultKafkaBatchSize), 1)
	kafkaBuffer := atLeast(envOrDefaultInt("KAFKA_BUFFER", defaultKafkaBuffer), 1)
	return Config{
		Port:              port,
		ServiceName:       "location-ingestion-service",
		KafkaBrokers:      splitCSV(os.Getenv("KAFKA_BROKERS")),
		KafkaTopic:        envOrDefault("KAFKA_TOPIC", defaultKafkaTopic),
		KafkaBatchSize:    kafkaBatchSize,
		KafkaBatchTimeout: envOrDefaultDuration("KAFKA_BATCH_TIMEOUT", defaultKafkaBatchTimeout),
		KafkaWriteTimeout: envOrDefaultDuration("KAFKA_WRITE_TIMEOUT", defaultKafkaWriteTimeout),
		KafkaBuffer:       kafkaBuffer,
		RedisAddr:         envOrDefault("REDIS_ADDR", defaultRedisAddr),
		RedisPassword:     os.Getenv("REDIS_PASSWORD"),
		RedisDB:           envOrDefaultInt("REDIS_DB", 0),
		RedisKeyPrefix:    envOrDefault("REDIS_KEY_PREFIX", defaultRedisKeyPrefix),
		RedisTTL:          envOrDefaultDuration("REDIS_TTL", 0),
		MaxBodyBytes:      int64(envOrDefaultInt("MAX_BODY_BYTES", defaultMaxBodyBytes)),
		WebSocketReadMax:  int64(envOrDefaultInt("WS_READ_LIMIT", defaultWebSocketRead)),
		ShutdownTimeout:   envOrDefaultDuration("SHUTDOWN_TIMEOUT", defaultShutdownTimeout),
		ProcessTimeout:    envOrDefaultDuration("PROCESS_TIMEOUT", defaultProcessTimeout),
		H3Resolution:      envOrDefaultInt("H3_RESOLUTION", 0),
		LogLevel:          envOrDefaultLogLevel("LOG_LEVEL", slog.LevelInfo),
	}
}

func newLogger(cfg Config) *slog.Logger {
	handler := slog.NewJSONHandler(os.Stdout, &slog.HandlerOptions{Level: cfg.LogLevel})
	logger := slog.New(handler).With("service", cfg.ServiceName)
	slog.SetDefault(logger)
	return logger
}

func newMetrics() *Metrics {
	metrics := &Metrics{
		ingestTotal: prometheus.NewCounterVec(prometheus.CounterOpts{
			Namespace: "location_ingestion",
			Name:      "ingest_total",
			Help:      "Total number of accepted location updates.",
		}, []string{"source"}),
		dropTotal: prometheus.NewCounterVec(prometheus.CounterOpts{
			Namespace: "location_ingestion",
			Name:      "drop_total",
			Help:      "Total number of dropped location updates.",
		}, []string{"source", "reason"}),
		latency: prometheus.NewHistogramVec(prometheus.HistogramOpts{
			Namespace: "location_ingestion",
			Name:      "latency_seconds",
			Help:      "Latency for processing location updates.",
			Buckets:   prometheus.DefBuckets,
		}, []string{"source"}),
	}
	prometheus.MustRegister(metrics.ingestTotal, metrics.dropTotal, metrics.latency)
	return metrics
}

func initTracing(ctx context.Context, cfg Config, logger *slog.Logger) *sdktrace.TracerProvider {
	otel.SetTextMapPropagator(propagation.NewCompositeTextMapPropagator(
		propagation.TraceContext{},
		propagation.Baggage{},
	))

	exporter, err := otlptracegrpc.New(ctx)
	if err != nil {
		logger.Warn("otel exporter init failed, tracing disabled", "error", err)
		tp := sdktrace.NewTracerProvider(
			sdktrace.WithResource(resource.NewWithAttributes(
				semconv.SchemaURL,
				semconv.ServiceName(cfg.ServiceName),
			)),
		)
		otel.SetTracerProvider(tp)
		return tp
	}
	tp := sdktrace.NewTracerProvider(
		sdktrace.WithBatcher(exporter),
		sdktrace.WithResource(resource.NewWithAttributes(
			semconv.SchemaURL,
			semconv.ServiceName(cfg.ServiceName),
		)),
	)
	otel.SetTracerProvider(tp)
	return tp
}

func (s *IngestService) handleHTTPIngest(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodPost {
		w.Header().Set("Allow", "POST")
		writeError(w, http.StatusMethodNotAllowed, "method not allowed")
		return
	}

	r.Body = http.MaxBytesReader(w, r.Body, s.cfg.MaxBodyBytes)
	decoder := json.NewDecoder(r.Body)
	decoder.DisallowUnknownFields()

	var input LocationInput
	if err := decoder.Decode(&input); err != nil {
		s.metrics.dropTotal.WithLabelValues("http", "decode").Inc()
		writeError(w, http.StatusBadRequest, "invalid JSON payload")
		return
	}
	if err := decoder.Decode(&struct{}{}); err != io.EOF {
		s.metrics.dropTotal.WithLabelValues("http", "decode").Inc()
		writeError(w, http.StatusBadRequest, "payload must contain a single JSON object")
		return
	}

	if err := s.processInput(r.Context(), input, "http"); err != nil {
		status := http.StatusServiceUnavailable
		if errors.Is(err, errInvalidPayload) {
			status = http.StatusBadRequest
		}
		writeError(w, status, err.Error())
		return
	}

	writeJSON(w, http.StatusAccepted, map[string]string{"status": "accepted"})
}

func (s *IngestService) handleWSIngest(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodGet {
		w.Header().Set("Allow", "GET")
		writeError(w, http.StatusMethodNotAllowed, "method not allowed")
		return
	}

	upgrader := websocket.Upgrader{
		ReadBufferSize:  1024,
		WriteBufferSize: 1024,
		CheckOrigin: func(r *http.Request) bool {
			return true
		},
	}

	conn, err := upgrader.Upgrade(w, r, nil)
	if err != nil {
		s.logger.Error("websocket upgrade failed", "error", err)
		return
	}
	defer conn.Close()

	conn.SetReadLimit(s.cfg.WebSocketReadMax)
	ctx := r.Context()

	for {
		select {
		case <-ctx.Done():
			return
		default:
		}

		messageType, payload, err := conn.ReadMessage()
		if err != nil {
			if websocket.IsCloseError(err, websocket.CloseNormalClosure, websocket.CloseGoingAway) {
				return
			}
			s.logger.Warn("websocket read failed", "error", err)
			return
		}
		if messageType != websocket.TextMessage && messageType != websocket.BinaryMessage {
			continue
		}

		var input LocationInput
		decoder := json.NewDecoder(bytes.NewReader(payload))
		decoder.DisallowUnknownFields()
		if err := decoder.Decode(&input); err != nil {
			s.metrics.dropTotal.WithLabelValues("websocket", "decode").Inc()
			continue
		}
		if err := decoder.Decode(&struct{}{}); err != io.EOF {
			s.metrics.dropTotal.WithLabelValues("websocket", "decode").Inc()
			continue
		}

		if err := s.processInput(ctx, input, "websocket"); err != nil {
			if !errors.Is(err, errInvalidPayload) {
				s.logger.Warn("websocket ingestion failed", "error", err)
			}
		}
	}
}

func handleHealth(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodGet && r.Method != http.MethodHead {
		w.Header().Set("Allow", "GET, HEAD")
		writeError(w, http.StatusMethodNotAllowed, "method not allowed")
		return
	}
	writeJSON(w, http.StatusOK, map[string]string{"status": "ok"})
}

func (s *IngestService) handleReady(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodGet && r.Method != http.MethodHead {
		w.Header().Set("Allow", "GET, HEAD")
		writeError(w, http.StatusMethodNotAllowed, "method not allowed")
		return
	}

	checks := make(map[string]string)
	status := http.StatusOK

	ctx, cancel := context.WithTimeout(r.Context(), 2*time.Second)
	defer cancel()

	if s.redis == nil {
		checks["redis"] = "disabled"
		status = http.StatusServiceUnavailable
	} else if err := s.redis.Ping(ctx).Err(); err != nil {
		checks["redis"] = "down"
		status = http.StatusServiceUnavailable
	} else {
		checks["redis"] = "ok"
	}

	if s.batcher.writer == nil {
		checks["kafka"] = "disabled"
		status = http.StatusServiceUnavailable
	} else if err := checkKafka(ctx, s.cfg.KafkaBrokers); err != nil {
		checks["kafka"] = "down"
		status = http.StatusServiceUnavailable
	} else {
		checks["kafka"] = "ok"
	}

	payload := map[string]any{
		"status": statusText(status),
		"checks": checks,
	}
	writeJSON(w, status, payload)
}

var errInvalidPayload = errors.New("invalid payload")

func (s *IngestService) processInput(ctx context.Context, input LocationInput, source string) error {
	start := time.Now()
	update, err := normalizeLocation(input)
	if err != nil {
		s.metrics.dropTotal.WithLabelValues(source, "validation").Inc()
		return err
	}

	processCtx, cancel := context.WithTimeout(ctx, s.cfg.ProcessTimeout)
	defer cancel()

	if s.h3.enabled {
		update.H3Index = s.h3.Index(update.Lat, update.Lng)
		update.Geofence = s.h3.GeofenceHit(update.Lat, update.Lng)
	}
	update.Source = source

	processCtx, span := s.tracer.Start(processCtx, "ingest.process")
	defer span.End()

	if err := s.storeLatest(processCtx, update); err != nil {
		s.metrics.dropTotal.WithLabelValues(source, "redis").Inc()
		span.RecordError(err)
		s.logger.Error("redis store failed", "error", err, "rider_id", update.RiderID)
		return fmt.Errorf("redis store failed")
	}

	if err := s.batcher.Enqueue(update); err != nil {
		s.metrics.dropTotal.WithLabelValues(source, "enqueue").Inc()
		span.RecordError(err)
		s.logger.Error("kafka enqueue failed", "error", err, "rider_id", update.RiderID)
		return fmt.Errorf("kafka enqueue failed")
	}

	s.metrics.ingestTotal.WithLabelValues(source).Inc()
	s.metrics.latency.WithLabelValues(source).Observe(time.Since(start).Seconds())
	return nil
}

func normalizeLocation(input LocationInput) (LocationUpdate, error) {
	if input.RiderID == "" {
		return LocationUpdate{}, fmt.Errorf("%w: rider_id is required", errInvalidPayload)
	}
	if input.Lat < -90 || input.Lat > 90 {
		return LocationUpdate{}, fmt.Errorf("%w: lat out of range", errInvalidPayload)
	}
	if input.Lng < -180 || input.Lng > 180 {
		return LocationUpdate{}, fmt.Errorf("%w: lng out of range", errInvalidPayload)
	}
	if input.Speed != nil && *input.Speed < 0 {
		return LocationUpdate{}, fmt.Errorf("%w: speed must be >= 0", errInvalidPayload)
	}
	if input.Heading != nil && (*input.Heading < 0 || *input.Heading >= 360) {
		return LocationUpdate{}, fmt.Errorf("%w: heading must be between 0 and 360", errInvalidPayload)
	}
	if input.Accuracy != nil && *input.Accuracy < 0 {
		return LocationUpdate{}, fmt.Errorf("%w: accuracy must be >= 0", errInvalidPayload)
	}

	var ts time.Time
	if input.Timestamp == nil || *input.Timestamp <= 0 {
		ts = time.Now().UTC()
	} else {
		ts = time.UnixMilli(*input.Timestamp).UTC()
	}

	return LocationUpdate{
		RiderID:   input.RiderID,
		Lat:       input.Lat,
		Lng:       input.Lng,
		Timestamp: ts,
		Speed:     input.Speed,
		Heading:   input.Heading,
		Accuracy:  input.Accuracy,
	}, nil
}

func (s *IngestService) storeLatest(ctx context.Context, update LocationUpdate) error {
	fields := map[string]any{
		"lat":          update.Lat,
		"lng":          update.Lng,
		"timestamp_ms": update.Timestamp.UnixMilli(),
	}
	if update.Speed != nil {
		fields["speed"] = *update.Speed
	}
	if update.Heading != nil {
		fields["heading"] = *update.Heading
	}
	if update.Accuracy != nil {
		fields["accuracy"] = *update.Accuracy
	}
	if update.H3Index != "" {
		fields["h3_index"] = update.H3Index
	}

	key := s.cfg.RedisKeyPrefix + update.RiderID
	if err := s.redis.HSet(ctx, key, fields).Err(); err != nil {
		return err
	}
	if s.cfg.RedisTTL > 0 {
		if err := s.redis.Expire(ctx, key, s.cfg.RedisTTL).Err(); err != nil {
			return err
		}
	}
	return nil
}

func (b *KafkaBatcher) Enqueue(update LocationUpdate) error {
	if b.writer == nil {
		return errors.New("kafka disabled")
	}
	select {
	case b.input <- update:
		return nil
	default:
		return errors.New("kafka buffer full")
	}
}

func (b *KafkaBatcher) Run(ctx context.Context) {
	ticker := time.NewTicker(b.batchTimeout)
	defer ticker.Stop()

	batch := make([]LocationUpdate, 0, b.batchSize)
	flush := func() {
		if len(batch) == 0 {
			return
		}
		if err := b.writeBatch(ctx, batch); err != nil {
			b.logger.Warn("kafka batch write failed", "error", err, "count", len(batch))
			b.metrics.dropTotal.WithLabelValues("kafka", "write").Add(float64(len(batch)))
		}
		batch = batch[:0]
	}

	for {
		select {
		case <-ctx.Done():
			for {
				select {
				case update := <-b.input:
					batch = append(batch, update)
					if len(batch) >= b.batchSize {
						flush()
					}
				default:
					flush()
					return
				}
			}
		case update := <-b.input:
			batch = append(batch, update)
			if len(batch) >= b.batchSize {
				flush()
			}
		case <-ticker.C:
			flush()
		}
	}
}

func (b *KafkaBatcher) writeBatch(ctx context.Context, updates []LocationUpdate) error {
	if b.writer == nil {
		return errors.New("kafka disabled")
	}

	messages := make([]kafka.Message, 0, len(updates))
	for _, update := range updates {
		event := LocationEvent{
			RiderID:     update.RiderID,
			Lat:         update.Lat,
			Lng:         update.Lng,
			TimestampMS: update.Timestamp.UnixMilli(),
			Speed:       update.Speed,
			Heading:     update.Heading,
			Accuracy:    update.Accuracy,
			H3Index:     update.H3Index,
			GeofenceHit: update.Geofence,
			Source:      update.Source,
			IngestedAt:  time.Now().UTC().Format(time.RFC3339Nano),
		}
		payload, err := json.Marshal(event)
		if err != nil {
			b.metrics.dropTotal.WithLabelValues("kafka", "marshal").Inc()
			continue
		}
		messages = append(messages, kafka.Message{
			Key:   []byte(update.RiderID),
			Value: payload,
			Time:  time.Now().UTC(),
		})
	}
	if len(messages) == 0 {
		return nil
	}

	writeCtx, cancel := context.WithTimeout(ctx, b.writeTimeout)
	defer cancel()
	return b.writer.WriteMessages(writeCtx, messages...)
}

func checkKafka(ctx context.Context, brokers []string) error {
	if len(brokers) == 0 {
		return errors.New("no brokers configured")
	}
	dialer := kafka.Dialer{Timeout: 2 * time.Second}
	conn, err := dialer.DialContext(ctx, "tcp", brokers[0])
	if err != nil {
		return err
	}
	_ = conn.Close()
	return nil
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

func envOrDefault(key, fallback string) string {
	if value := os.Getenv(key); value != "" {
		return value
	}
	return fallback
}

func envOrDefaultInt(key string, fallback int) int {
	if value := os.Getenv(key); value != "" {
		if parsed, err := strconv.Atoi(value); err == nil {
			return parsed
		}
	}
	return fallback
}

func envOrDefaultDuration(key string, fallback time.Duration) time.Duration {
	if value := os.Getenv(key); value != "" {
		if parsed, err := time.ParseDuration(value); err == nil {
			return parsed
		}
	}
	return fallback
}

func envOrDefaultLogLevel(key string, fallback slog.Level) slog.Level {
	value := strings.ToLower(strings.TrimSpace(os.Getenv(key)))
	switch value {
	case "debug":
		return slog.LevelDebug
	case "warn", "warning":
		return slog.LevelWarn
	case "error":
		return slog.LevelError
	case "info":
		return slog.LevelInfo
	default:
		return fallback
	}
}

func atLeast(value, min int) int {
	if value < min {
		return min
	}
	return value
}

func splitCSV(value string) []string {
	if value == "" {
		return nil
	}
	parts := strings.Split(value, ",")
	out := make([]string, 0, len(parts))
	for _, part := range parts {
		trimmed := strings.TrimSpace(part)
		if trimmed != "" {
			out = append(out, trimmed)
		}
	}
	return out
}

func statusText(status int) string {
	if status >= 200 && status < 300 {
		return "ok"
	}
	return "unavailable"
}
