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
	"sync/atomic"
	"syscall"
	"time"

	"github.com/gorilla/websocket"
	"github.com/instacommerce/go-shared/pkg/auth"
	"github.com/instacommerce/go-shared/pkg/correlation"
	"github.com/instacommerce/go-shared/pkg/health"
	"github.com/instacommerce/location-ingestion-service/handler"
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
	semconv "go.opentelemetry.io/otel/semconv/v1.25.0"
	"go.opentelemetry.io/otel/trace"
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
	defaultRedisTTL          = 10 * time.Minute
	defaultMaxBodyBytes      = 1 << 20
	defaultWebSocketRead     = 1 << 20
	defaultShutdownTimeout   = 15 * time.Second
	defaultProcessTimeout    = 3 * time.Second
)

// ErrOutOfOrderTimestamp is returned when an incoming GPS update has a timestamp <= stored timestamp.
var ErrOutOfOrderTimestamp = errors.New("out of order timestamp")

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
	idx := handler.LatLngToH3Res8(lat, lng)
	if h.resolution > 0 && h.resolution != 8 {
		return fmt.Sprintf("res%d:%s", h.resolution, idx)
	}
	return idx
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

type kafkaWriterProducer struct {
	writer *kafka.Writer
}

func (p *kafkaWriterProducer) PublishBatch(ctx context.Context, topic string, messages []handler.KafkaMessage) error {
	if p == nil || p.writer == nil {
		return errors.New("kafka disabled")
	}
	kafkaMsgs := make([]kafka.Message, 0, len(messages))
	for _, m := range messages {
		kafkaMsgs = append(kafkaMsgs, kafka.Message{
			Key:   m.Key,
			Value: m.Value,
			Time:  time.Now().UTC(),
		})
	}
	if len(kafkaMsgs) == 0 {
		return nil
	}
	return p.writer.WriteMessages(ctx, kafkaMsgs...)
}

type IngestService struct {
	cfg          Config
	logger       *slog.Logger
	metrics      *Metrics
	redis        *redis.Client
	batcher      *KafkaBatcher
	tracer       trace.Tracer
	h3           H3Stub
	rateLimiter  *handler.ConnectionRateLimiter
	geofencer    *handler.GeofenceChecker
	geofencePub  handler.GeofencePublisher
	wsWG         sync.WaitGroup
	wsMu         sync.Mutex
	wsConns      map[*websocket.Conn]struct{}
	shuttingDown atomic.Bool
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
	var geofenceWriter *kafka.Writer
	var geofencePub handler.GeofencePublisher
	if len(cfg.KafkaBrokers) > 0 {
		kafkaWriter = &kafka.Writer{
			Addr:                   kafka.TCP(cfg.KafkaBrokers...),
			Topic:                  cfg.KafkaTopic,
			Balancer:               &kafka.Hash{},
			RequiredAcks:           kafka.RequireAll,
			AllowAutoTopicCreation: true,
			WriteTimeout:           cfg.KafkaWriteTimeout,
		}
		geofenceWriter = &kafka.Writer{
			Addr:                   kafka.TCP(cfg.KafkaBrokers...),
			Topic:                  handler.DefaultGeofenceTopic,
			Balancer:               &kafka.Hash{},
			RequiredAcks:           kafka.RequireAll,
			AllowAutoTopicCreation: true,
			WriteTimeout:           cfg.KafkaWriteTimeout,
		}
		geofencePub = handler.NewKafkaGeofencePublisher(
			&kafkaWriterProducer{writer: geofenceWriter},
			handler.DefaultGeofenceTopic,
			logger,
		)
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

	h3Res := cfg.H3Resolution
	if h3Res <= 0 {
		h3Res = 8
	}
	geofencer := handler.NewGeofenceChecker(h3Res)

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
		rateLimiter: handler.NewConnectionRateLimiter(100.0, 200.0, 20000),
		geofencer:   geofencer,
		geofencePub: geofencePub,
		wsConns:     make(map[*websocket.Conn]struct{}),
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

	healthHandler := health.NewHandler(
		health.Check{
			Name: "redis",
			Check: func(ctx context.Context) error {
				if service.redis == nil {
					return errors.New("redis disabled")
				}
				return service.redis.Ping(ctx).Err()
			},
		},
		health.Check{
			Name: "kafka",
			Check: func(ctx context.Context) error {
				if service.batcher.writer == nil {
					return errors.New("kafka disabled")
				}
				return checkKafka(ctx, service.cfg.KafkaBrokers)
			},
		},
	)
	healthHandler.SetReady(true)

	mux := newHTTPMux(service, healthHandler, logger)

	wrappedHandler := otelhttp.NewHandler(correlation.Middleware(mux), "http.server")

	server := &http.Server{
		Addr:              ":" + cfg.Port,
		Handler:           wrappedHandler,
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
	signal.Stop(quit)

	shutdownServer(server, healthHandler, cfg.ShutdownTimeout, logger)

	// Close active WebSocket connections and wait for their goroutines BEFORE
	// draining the Kafka batcher or closing Kafka/Redis clients.
	service.CloseWebSockets()

	cancel()
	wg.Wait()

	if kafkaWriter != nil {
		_ = kafkaWriter.Close()
	}
	if geofenceWriter != nil {
		_ = geofenceWriter.Close()
	}
	_ = redisClient.Close()

	logger.Info("service stopped")
}

func newHTTPMux(service *IngestService, healthHandler *health.Handler, logger *slog.Logger) *http.ServeMux {
	mux := http.NewServeMux()
	mux.Handle("/metrics", promhttp.Handler())
	healthHandler.RegisterRoutes(mux)
	mux.HandleFunc("/ready", healthHandler.ReadyHandler())

	authMiddleware := auth.NewInternalAuthMiddleware(logger)
	mux.Handle("/ingest", authMiddleware.Wrap(http.HandlerFunc(service.handleHTTPIngest)))
	mux.Handle("/ingest/location", authMiddleware.Wrap(http.HandlerFunc(service.handleHTTPIngest)))
	mux.Handle("/ingest/batch", authMiddleware.Wrap(http.HandlerFunc(service.handleBatchIngest)))
	mux.Handle("/batch", authMiddleware.Wrap(http.HandlerFunc(service.handleBatchIngest)))
	mux.Handle("/ingest/ws", authMiddleware.Wrap(http.HandlerFunc(service.handleWSIngest)))
	return mux
}

func loadConfig() Config {
	port := envOrDefault("PORT", "")
	if port == "" {
		port = envOrDefault("SERVER_PORT", defaultPort)
	}
	kafkaBatchSize := atLeast(envOrDefaultInt("KAFKA_BATCH_SIZE", defaultKafkaBatchSize), 1)
	kafkaBuffer := atLeast(envOrDefaultInt("KAFKA_BUFFER", defaultKafkaBuffer), 1)
	redisTTL := envOrDefaultDuration("REDIS_TTL", defaultRedisTTL)
	if redisTTL <= 0 {
		redisTTL = defaultRedisTTL
	}
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
		RedisTTL:          redisTTL,
		MaxBodyBytes:      int64(envOrDefaultInt("MAX_BODY_BYTES", defaultMaxBodyBytes)),
		WebSocketReadMax:  int64(envOrDefaultInt("WS_READ_LIMIT", defaultWebSocketRead)),
		ShutdownTimeout:   envOrDefaultDuration("SHUTDOWN_TIMEOUT", defaultShutdownTimeout),
		ProcessTimeout:    envOrDefaultDuration("PROCESS_TIMEOUT", defaultProcessTimeout),
		H3Resolution:      envOrDefaultInt("H3_RESOLUTION", 0),
		LogLevel:          envOrDefaultLogLevel("LOG_LEVEL", slog.LevelInfo),
	}
}

func newLogger(cfg Config) *slog.Logger {
	h := slog.NewJSONHandler(os.Stdout, &slog.HandlerOptions{Level: cfg.LogLevel})
	logger := slog.New(h).With("service", cfg.ServiceName)
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

	res, resErr := resource.Merge(
		resource.Default(),
		resource.NewSchemaless(semconv.ServiceName(cfg.ServiceName)),
	)
	if resErr != nil {
		res = resource.NewSchemaless(semconv.ServiceName(cfg.ServiceName))
	}

	exporter, err := otlptracegrpc.New(ctx)
	if err != nil {
		logger.Warn("otel exporter init failed, tracing disabled", "error", err)
		tp := sdktrace.NewTracerProvider(sdktrace.WithResource(res))
		otel.SetTracerProvider(tp)
		return tp
	}
	tp := sdktrace.NewTracerProvider(
		sdktrace.WithBatcher(exporter),
		sdktrace.WithResource(res),
	)
	otel.SetTracerProvider(tp)
	return tp
}

func (s *IngestService) recordDrop(source, reason string) {
	if s != nil && s.metrics != nil && s.metrics.dropTotal != nil {
		s.metrics.dropTotal.WithLabelValues(source, reason).Inc()
	}
}

func (s *IngestService) recordIngest(source string, dur time.Duration) {
	if s != nil && s.metrics != nil {
		if s.metrics.ingestTotal != nil {
			s.metrics.ingestTotal.WithLabelValues(source).Inc()
		}
		if s.metrics.latency != nil {
			s.metrics.latency.WithLabelValues(source).Observe(dur.Seconds())
		}
	}
}

func (s *IngestService) registerWSConn(conn *websocket.Conn) bool {
	s.wsMu.Lock()
	defer s.wsMu.Unlock()
	if s.shuttingDown.Load() {
		return false
	}
	if s.wsConns == nil {
		s.wsConns = make(map[*websocket.Conn]struct{})
	}
	s.wsConns[conn] = struct{}{}
	return true
}

func (s *IngestService) unregisterWSConn(conn *websocket.Conn) {
	s.wsMu.Lock()
	delete(s.wsConns, conn)
	s.wsMu.Unlock()
}

// CloseWebSockets closes all active WebSocket connections and blocks until all
// WebSocket handler goroutines have exited.
func (s *IngestService) CloseWebSockets() {
	s.shuttingDown.Store(true)
	s.wsMu.Lock()
	conns := make([]*websocket.Conn, 0, len(s.wsConns))
	for conn := range s.wsConns {
		conns = append(conns, conn)
	}
	s.wsMu.Unlock()

	for _, conn := range conns {
		_ = conn.WriteControl(
			websocket.CloseMessage,
			websocket.FormatCloseMessage(websocket.CloseGoingAway, "server shutting down"),
			time.Now().Add(time.Second),
		)
		_ = conn.Close()
	}
	s.wsWG.Wait()
}

func (s *IngestService) handleHTTPIngest(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodPost {
		w.Header().Set("Allow", "POST")
		writeError(w, http.StatusMethodNotAllowed, "method not allowed")
		return
	}

	maxBytes := s.cfg.MaxBodyBytes
	if maxBytes <= 0 {
		maxBytes = defaultMaxBodyBytes
	}
	r.Body = http.MaxBytesReader(w, r.Body, maxBytes)
	decoder := json.NewDecoder(r.Body)
	decoder.DisallowUnknownFields()

	var input LocationInput
	if err := decoder.Decode(&input); err != nil {
		s.recordDrop("http", "decode")
		var maxBytesErr *http.MaxBytesError
		if errors.As(err, &maxBytesErr) {
			writeError(w, http.StatusRequestEntityTooLarge, "request body too large")
			return
		}
		writeError(w, http.StatusBadRequest, "invalid JSON payload")
		return
	}
	if err := decoder.Decode(&struct{}{}); err != io.EOF {
		s.recordDrop("http", "decode")
		writeError(w, http.StatusBadRequest, "payload must contain a single JSON object")
		return
	}

	if err := s.processInput(r.Context(), input, "http"); err != nil {
		if errors.Is(err, ErrOutOfOrderTimestamp) || errors.Is(err, handler.ErrOutOfOrderPing) {
			writeJSON(w, http.StatusOK, map[string]string{
				"status": "ignored",
				"reason": "out_of_order_timestamp",
			})
			return
		}
		status := http.StatusServiceUnavailable
		if errors.Is(err, errInvalidPayload) {
			status = http.StatusBadRequest
		}
		writeError(w, status, err.Error())
		return
	}

	writeJSON(w, http.StatusAccepted, map[string]string{"status": "accepted"})
}

func (s *IngestService) handleBatchIngest(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodPost {
		w.Header().Set("Allow", "POST")
		writeError(w, http.StatusMethodNotAllowed, "method not allowed")
		return
	}

	maxBytes := s.cfg.MaxBodyBytes
	if maxBytes <= 0 {
		maxBytes = defaultMaxBodyBytes
	}
	r.Body = http.MaxBytesReader(w, r.Body, maxBytes)
	body, err := io.ReadAll(r.Body)
	if err != nil {
		s.recordDrop("http", "decode")
		var maxBytesErr *http.MaxBytesError
		if errors.As(err, &maxBytesErr) {
			writeError(w, http.StatusRequestEntityTooLarge, "request body too large")
			return
		}
		writeError(w, http.StatusBadRequest, "failed to read request body")
		return
	}

	var inputs []LocationInput
	trimmed := bytes.TrimSpace(body)
	if len(trimmed) > 0 && trimmed[0] == '[' {
		if err := json.Unmarshal(trimmed, &inputs); err != nil {
			s.recordDrop("http", "decode")
			writeError(w, http.StatusBadRequest, "invalid JSON array payload")
			return
		}
	} else {
		var wrapper struct {
			Updates []LocationInput `json:"updates"`
		}
		if err := json.Unmarshal(trimmed, &wrapper); err != nil {
			s.recordDrop("http", "decode")
			writeError(w, http.StatusBadRequest, "invalid JSON batch payload")
			return
		}
		inputs = wrapper.Updates
	}

	if len(inputs) == 0 {
		writeError(w, http.StatusBadRequest, "batch must contain at least one update")
		return
	}

	accepted := 0
	ignored := 0
	failed := 0
	for _, input := range inputs {
		if err := s.processInput(r.Context(), input, "http"); err != nil {
			if errors.Is(err, ErrOutOfOrderTimestamp) || errors.Is(err, handler.ErrOutOfOrderPing) {
				ignored++
				continue
			}
			failed++
			continue
		}
		accepted++
	}

	writeJSON(w, http.StatusAccepted, map[string]any{
		"status":   "accepted",
		"accepted": accepted,
		"ignored":  ignored,
		"failed":   failed,
	})
}

func (s *IngestService) handleWSIngest(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodGet {
		w.Header().Set("Allow", "GET")
		writeError(w, http.StatusMethodNotAllowed, "method not allowed")
		return
	}
	if s.shuttingDown.Load() {
		writeError(w, http.StatusServiceUnavailable, "service shutting down")
		return
	}

	if s.rateLimiter != nil && !s.rateLimiter.Allow() {
		s.recordDrop("websocket", "rate_limit")
		w.Header().Set("Retry-After", "5")
		w.Header().Set("Content-Type", "application/json")
		w.WriteHeader(http.StatusTooManyRequests)
		_ = json.NewEncoder(w).Encode(errorResponse{Error: "connection rate limit exceeded; apply exponential backoff"})
		return
	}
	if s.rateLimiter != nil {
		defer s.rateLimiter.ReleaseConn()
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
		if s.logger != nil {
			s.logger.Error("websocket upgrade failed", "error", err)
		}
		return
	}
	if !s.registerWSConn(conn) {
		_ = conn.Close()
		return
	}

	s.wsWG.Add(1)
	defer s.wsWG.Done()
	defer s.unregisterWSConn(conn)
	defer conn.Close()

	s.serveWSConn(r.Context(), conn)
}

func (s *IngestService) serveWSConn(ctx context.Context, conn *websocket.Conn) {
	readMax := s.cfg.WebSocketReadMax
	if readMax <= 0 {
		readMax = defaultWebSocketRead
	}
	conn.SetReadLimit(readMax)
	_ = conn.SetReadDeadline(time.Now().Add(60 * time.Second))
	conn.SetPongHandler(func(string) error {
		_ = conn.SetReadDeadline(time.Now().Add(60 * time.Second))
		return nil
	})

	pingDone := make(chan struct{})
	var pingWG sync.WaitGroup
	pingWG.Add(1)
	go func() {
		defer pingWG.Done()
		ticker := time.NewTicker(25 * time.Second)
		defer ticker.Stop()
		for {
			select {
			case <-pingDone:
				return
			case <-ticker.C:
				_ = conn.SetWriteDeadline(time.Now().Add(10 * time.Second))
				if err := conn.WriteMessage(websocket.PingMessage, nil); err != nil {
					return
				}
			}
		}
	}()
	defer func() {
		close(pingDone)
		pingWG.Wait()
	}()

	for {
		select {
		case <-ctx.Done():
			return
		default:
		}
		if s.shuttingDown.Load() {
			return
		}

		messageType, payload, err := conn.ReadMessage()
		if err != nil {
			if websocket.IsCloseError(err, websocket.CloseNormalClosure, websocket.CloseGoingAway) {
				return
			}
			if !s.shuttingDown.Load() && s.logger != nil {
				s.logger.Warn("websocket read failed", "error", err)
			}
			return
		}
		if messageType != websocket.TextMessage && messageType != websocket.BinaryMessage {
			continue
		}

		var input LocationInput
		decoder := json.NewDecoder(bytes.NewReader(payload))
		decoder.DisallowUnknownFields()
		if err := decoder.Decode(&input); err != nil {
			s.recordDrop("websocket", "decode")
			continue
		}
		if err := decoder.Decode(&struct{}{}); err != io.EOF {
			s.recordDrop("websocket", "decode")
			continue
		}

		if err := s.processInput(ctx, input, "websocket"); err != nil {
			if !errors.Is(err, errInvalidPayload) &&
				!errors.Is(err, ErrOutOfOrderTimestamp) &&
				!errors.Is(err, handler.ErrOutOfOrderPing) &&
				s.logger != nil {
				s.logger.Warn("websocket ingestion failed", "error", err)
			}
		}
	}
}

var errInvalidPayload = errors.New("invalid payload")

func (s *IngestService) processInput(ctx context.Context, input LocationInput, source string) error {
	start := time.Now()
	update, err := normalizeLocation(input)
	if err != nil {
		s.recordDrop(source, "validation")
		return err
	}

	timeout := s.cfg.ProcessTimeout
	if timeout <= 0 {
		timeout = defaultProcessTimeout
	}
	processCtx, cancel := context.WithTimeout(ctx, timeout)
	defer cancel()

	if s.h3.enabled {
		update.H3Index = s.h3.Index(update.Lat, update.Lng)
	}
	update.Source = source

	if s.geofencer != nil {
		speedKmh := 0.0
		if update.Speed != nil {
			speedKmh = *update.Speed
		}
		headingDeg := 0.0
		if update.Heading != nil {
			headingDeg = *update.Heading
		}
		accuracyM := 0.0
		if update.Accuracy != nil {
			accuracyM = *update.Accuracy
		}
		handlerUpdate := handler.LocationUpdate{
			RiderID:   update.RiderID,
			Lat:       update.Lat,
			Lng:       update.Lng,
			Speed:     speedKmh,
			Heading:   headingDeg,
			Accuracy:  accuracyM,
			Timestamp: update.Timestamp,
			H3Index:   update.H3Index,
		}
		events := s.geofencer.Check(handlerUpdate)
		if len(events) > 0 {
			update.Geofence = true
			if s.geofencePub != nil {
				for _, ev := range events {
					env := ev.ToEnvelope(update.Lat, update.Lng)
					env.CorrelationID = correlation.FromContext(processCtx)
					if pubErr := s.geofencePub.PublishGeofenceEvent(processCtx, env); pubErr != nil && s.logger != nil {
						s.logger.Warn("failed to publish geofence event",
							"rider_id", update.RiderID, "event_type", ev.EventType, "error", pubErr)
					}
				}
			}
		}
	}

	if s.tracer != nil {
		var span trace.Span
		processCtx, span = s.tracer.Start(processCtx, "ingest.process")
		defer span.End()
	}

	if s.redis != nil {
		if err := s.storeLatest(processCtx, update); err != nil {
			if errors.Is(err, ErrOutOfOrderTimestamp) {
				s.recordDrop(source, "out_of_order")
				return err
			}
			s.recordDrop(source, "redis")
			trace.SpanFromContext(processCtx).RecordError(err)
			if s.logger != nil {
				s.logger.Error("redis store failed", "error", err, "rider_id", update.RiderID)
			}
			return fmt.Errorf("redis store failed: %w", err)
		}
	}

	if s.batcher != nil {
		if err := s.batcher.Enqueue(update); err != nil {
			s.recordDrop(source, "enqueue")
			trace.SpanFromContext(processCtx).RecordError(err)
			if s.logger != nil {
				s.logger.Error("kafka enqueue failed", "error", err, "rider_id", update.RiderID)
			}
			return fmt.Errorf("kafka enqueue failed: %w", err)
		}
	}

	s.recordIngest(source, time.Since(start))
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
	if input.Accuracy != nil && *input.Accuracy > 50.0 {
		return LocationUpdate{}, fmt.Errorf("%w: accuracy exceeds 50m limit (poor GPS fix)", errInvalidPayload)
	}

	var ts time.Time
	now := time.Now().UTC()
	if input.Timestamp == nil || *input.Timestamp <= 0 {
		ts = now
	} else {
		ts = time.UnixMilli(*input.Timestamp).UTC()
		if ts.After(now.Add(1 * time.Minute)) {
			return LocationUpdate{}, fmt.Errorf("%w: timestamp too far in the future", errInvalidPayload)
		}
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
	key := s.cfg.RedisKeyPrefix + update.RiderID

	existingMs, err := s.redis.HGet(ctx, key, "timestamp_ms").Int64()
	if err == nil && existingMs > 0 {
		if update.Timestamp.UnixMilli() <= existingMs {
			return fmt.Errorf("%w: %d <= %d", ErrOutOfOrderTimestamp, update.Timestamp.UnixMilli(), existingMs)
		}
	}

	var oldH3 string
	if update.H3Index != "" {
		oldH3, _ = s.redis.HGet(ctx, key, "h3_index").Result()
	}

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

	ttl := s.cfg.RedisTTL
	if ttl <= 0 {
		ttl = defaultRedisTTL
	}

	pipe := s.redis.Pipeline()
	pipe.HSet(ctx, key, fields)
	pipe.Expire(ctx, key, ttl)
	if update.H3Index != "" {
		if oldH3 != "" && oldH3 != update.H3Index {
			pipe.SRem(ctx, "riders:h3:"+oldH3, update.RiderID)
		}
		pipe.SAdd(ctx, "riders:h3:"+update.H3Index, update.RiderID)
		pipe.Expire(ctx, "riders:h3:"+update.H3Index, ttl*2)
	}
	_, err = pipe.Exec(ctx)
	return err
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
		var err error
		for attempt := 0; attempt < 3; attempt++ {
			err = b.writeBatch(ctx, batch)
			if err == nil {
				break
			}
			if attempt < 2 {
				select {
				case <-ctx.Done():
					// Best-effort bounded retry on final drain using short sleep.
					time.Sleep(50 * time.Millisecond)
				case <-time.After(time.Duration(50*(attempt+1)) * time.Millisecond):
				}
			}
		}
		if err != nil {
			if b.logger != nil {
				b.logger.Warn("kafka batch write failed after retries", "error", err, "count", len(batch))
			}
			if b.metrics != nil && b.metrics.dropTotal != nil {
				b.metrics.dropTotal.WithLabelValues("kafka", "write").Add(float64(len(batch)))
			}
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
			if b.metrics != nil && b.metrics.dropTotal != nil {
				b.metrics.dropTotal.WithLabelValues("kafka", "marshal").Inc()
			}
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

	writeCtx, cancel := context.WithTimeout(context.WithoutCancel(ctx), b.writeTimeout)
	defer cancel()
	return b.writer.WriteMessages(writeCtx, messages...)
}

func shutdownServer(server *http.Server, healthHandler *health.Handler, timeout time.Duration, logger *slog.Logger) {
	health.ShutdownServer(server, healthHandler, timeout, logger)
}

const kafkaProbeBrokerTimeout = 2 * time.Second

func checkKafka(ctx context.Context, brokers []string) error {
	return health.CheckTCPBrokers(ctx, brokers, kafkaProbeBrokerTimeout)
}

func checkKafkaWithDial(ctx context.Context, brokers []string, dial func(context.Context, string) error) error {
	return health.CheckTCPBrokersWithDial(ctx, brokers, kafkaProbeBrokerTimeout, dial)
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
