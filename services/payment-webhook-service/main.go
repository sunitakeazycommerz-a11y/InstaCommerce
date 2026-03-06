package main

import (
	"context"
	"crypto/hmac"
	"crypto/sha256"
	"encoding/hex"
	"encoding/json"
	"errors"
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

	"github.com/prometheus/client_golang/prometheus"
	"github.com/prometheus/client_golang/prometheus/promhttp"
	"github.com/redis/go-redis/v9"
	"github.com/segmentio/kafka-go"
	"go.opentelemetry.io/contrib/instrumentation/net/http/otelhttp"
	"go.opentelemetry.io/otel"
	"go.opentelemetry.io/otel/attribute"
	"go.opentelemetry.io/otel/exporters/otlp/otlptrace/otlptracehttp"
	"go.opentelemetry.io/otel/propagation"
	"go.opentelemetry.io/otel/sdk/resource"
	sdktrace "go.opentelemetry.io/otel/sdk/trace"
	"go.opentelemetry.io/otel/trace"
	semconv "go.opentelemetry.io/otel/semconv/v1.24.0"
)

const (
	defaultPort               = "8106"
	defaultSignatureHeader    = "Stripe-Signature"
	defaultSignatureTolerance = 300
	defaultMaxBodyBytes       = 1 << 20
	defaultDedupeTTLSeconds   = 86400
	defaultCleanupSeconds     = 60
	defaultPublishQueueSize   = 1000
	defaultPublishTimeoutMS   = 2000
	defaultShutdownSeconds    = 15
)

type Config struct {
	Port                 string
	WebhookSecret        string
	SignatureHeader      string
	SignatureTolerance   time.Duration
	MaxBodyBytes         int64
	DedupeTTL            time.Duration
	DedupeCleanup        time.Duration
	RedisAddr            string
	RedisPassword        string
	RedisDB              int
	RedisTimeout         time.Duration
	KafkaBrokers         []string
	KafkaTopic           string
	PublishQueueSize     int
	PublishTimeout       time.Duration
	ShutdownTimeout      time.Duration
	ReadHeaderTimeout    time.Duration
	ReadTimeout          time.Duration
	WriteTimeout         time.Duration
	IdleTimeout          time.Duration
	RequireKafka         bool
	RequireWebhookSecret bool
}

type metrics struct {
	requestsTotal     *prometheus.CounterVec
	requestDuration   *prometheus.HistogramVec
	signatureFailures prometheus.Counter
	payloadFailures   prometheus.Counter
	dedupeDuplicates  prometheus.Counter
	dedupeErrors      prometheus.Counter
	publishEnqueued   prometheus.Counter
	publishDropped    prometheus.Counter
	publishSuccess    prometheus.Counter
	publishErrors     prometheus.Counter
	queueDepth        prometheus.Gauge
}

type DedupeStore interface {
	CheckAndSet(ctx context.Context, eventID string) (bool, error)
	Remove(ctx context.Context, eventID string) error
	Close() error
}

type inMemoryDedupe struct {
	ttl     time.Duration
	entries map[string]time.Time
	mu      sync.Mutex
	stop    chan struct{}
}

type redisDedupe struct {
	client  *redis.Client
	ttl     time.Duration
	prefix  string
	timeout time.Duration
}

type publishRequest struct {
	message     kafka.Message
	spanContext trace.SpanContext
	eventID     string
	eventType   string
}

type webhookHandler struct {
	cfg       Config
	logger    *slog.Logger
	metrics   *metrics
	dedupe    DedupeStore
	publishCh chan<- publishRequest
	tracer    trace.Tracer
}

type readiness struct {
	secretSet      bool
	kafkaConfigured bool
	redisClient    *redis.Client
	redisTimeout   time.Duration
}

type stripeEvent struct {
	ID   string `json:"id"`
	Type string `json:"type"`
}

type errorResponse struct {
	Error string `json:"error"`
}

type statusResponse struct {
	Status   string `json:"status"`
	EventID  string `json:"event_id,omitempty"`
	Duplicate bool  `json:"duplicate,omitempty"`
}

func main() {
	cfg := loadConfig()
	logger := newLogger()
	metrics := newMetrics()

	shutdownTracer, err := initTracing(cfg, logger)
	if err != nil {
		logger.Error("failed to initialize tracing", "error", err)
	}
	defer func() {
		ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
		defer cancel()
		if err := shutdownTracer(ctx); err != nil {
			logger.Error("failed to shutdown tracer", "error", err)
		}
	}()

	var dedupe DedupeStore
	var redisClient *redis.Client
	if cfg.RedisAddr != "" {
		redisDedupeStore, err := newRedisDedupe(cfg)
		if err != nil {
			logger.Error("failed to initialize redis dedupe", "error", err)
		} else {
			dedupe = redisDedupeStore
			redisClient = redisDedupeStore.client
		}
	}
	if dedupe == nil {
		dedupe = newInMemoryDedupe(cfg.DedupeTTL, cfg.DedupeCleanup)
	}
	defer func() {
		if err := dedupe.Close(); err != nil {
			logger.Error("failed to close dedupe store", "error", err)
		}
	}()

	var writer *kafka.Writer
	if len(cfg.KafkaBrokers) > 0 {
		writer = kafka.NewWriter(kafka.WriterConfig{
			Brokers:      cfg.KafkaBrokers,
			Topic:        cfg.KafkaTopic,
			Balancer:     &kafka.LeastBytes{},
			RequiredAcks: int(kafka.RequireOne),
			Async:        true,
			BatchTimeout: 5 * time.Millisecond,
		})
	}

	var publishCh chan publishRequest
	var publishWg sync.WaitGroup
	if writer != nil {
		publishCh = make(chan publishRequest, cfg.PublishQueueSize)
		publishWg.Add(1)
		go func() {
			defer publishWg.Done()
			runPublisher(writer, publishCh, metrics, logger, otel.Tracer("payment-webhook-service"), cfg.PublishTimeout)
		}()
	}

	ready := readiness{
		secretSet:      cfg.WebhookSecret != "",
		kafkaConfigured: writer != nil,
		redisClient:    redisClient,
		redisTimeout:   cfg.RedisTimeout,
	}

	handler := &webhookHandler{
		cfg:       cfg,
		logger:    logger,
		metrics:   metrics,
		dedupe:    dedupe,
		publishCh: publishCh,
		tracer:    otel.Tracer("payment-webhook-service"),
	}

	mux := http.NewServeMux()
	mux.Handle("/payments/webhook", handler)
	mux.Handle("/metrics", promhttp.Handler())
	mux.HandleFunc("/health", handleHealth)
	mux.HandleFunc("/health/live", handleHealth)
	mux.HandleFunc("/ready", ready.handleReady)
	mux.HandleFunc("/health/ready", ready.handleReady)

	server := &http.Server{
		Addr:              ":" + cfg.Port,
		Handler:           otelhttp.NewHandler(mux, "payment-webhook"),
		ReadHeaderTimeout: cfg.ReadHeaderTimeout,
		ReadTimeout:       cfg.ReadTimeout,
		WriteTimeout:      cfg.WriteTimeout,
		IdleTimeout:       cfg.IdleTimeout,
	}

	errCh := make(chan error, 1)
	go func() {
		logger.Info("payment webhook service listening", "addr", server.Addr)
		if err := server.ListenAndServe(); err != nil && !errors.Is(err, http.ErrServerClosed) {
			errCh <- err
		}
		close(errCh)
	}()

	quit := make(chan os.Signal, 1)
	signal.Notify(quit, syscall.SIGINT, syscall.SIGTERM)

	select {
	case sig := <-quit:
		logger.Info("received signal, shutting down", "signal", sig.String())
	case err := <-errCh:
		if err != nil {
			logger.Error("server error", "error", err)
		}
	}

	shutdownCtx, cancel := context.WithTimeout(context.Background(), cfg.ShutdownTimeout)
	defer cancel()
	if err := server.Shutdown(shutdownCtx); err != nil {
		logger.Error("server shutdown failed", "error", err)
	}

	if publishCh != nil {
		close(publishCh)
		waitWithTimeout(&publishWg, cfg.ShutdownTimeout, logger)
	}

	if writer != nil {
		if err := writer.Close(); err != nil {
			logger.Error("failed to close kafka writer", "error", err)
		}
	}
	logger.Info("shutdown complete")
}

func loadConfig() Config {
	port := envOr("PORT", "")
	if port == "" {
		port = envOr("SERVER_PORT", "")
	}
	if port == "" {
		port = defaultPort
	}
	secret := envOr("WEBHOOK_SECRET", "")
	if secret == "" {
		secret = envOr("STRIPE_WEBHOOK_SECRET", "")
	}
	brokers := strings.FieldsFunc(envOr("KAFKA_BROKERS", ""), func(r rune) bool {
		return r == ',' || r == ' ' || r == ';'
	})
	for i := 0; i < len(brokers); i++ {
		brokers[i] = strings.TrimSpace(brokers[i])
	}
	brokers = filterEmpty(brokers)
	return Config{
		Port:                 port,
		WebhookSecret:        secret,
		SignatureHeader:      envOr("WEBHOOK_SIGNATURE_HEADER", defaultSignatureHeader),
		SignatureTolerance:   time.Duration(envInt("WEBHOOK_TOLERANCE_SECONDS", defaultSignatureTolerance)) * time.Second,
		MaxBodyBytes:         int64(envInt("MAX_BODY_BYTES", defaultMaxBodyBytes)),
		DedupeTTL:            time.Duration(envInt("DEDUPE_TTL_SECONDS", defaultDedupeTTLSeconds)) * time.Second,
		DedupeCleanup:        time.Duration(envInt("DEDUPE_CLEANUP_SECONDS", defaultCleanupSeconds)) * time.Second,
		RedisAddr:            envOr("REDIS_ADDR", ""),
		RedisPassword:        envOr("REDIS_PASSWORD", ""),
		RedisDB:              envInt("REDIS_DB", 0),
		RedisTimeout:         time.Duration(envInt("REDIS_TIMEOUT_MS", 50)) * time.Millisecond,
		KafkaBrokers:         brokers,
		KafkaTopic:           envOr("KAFKA_TOPIC", "payment.webhooks"),
		PublishQueueSize:     envInt("PUBLISH_QUEUE_SIZE", defaultPublishQueueSize),
		PublishTimeout:       time.Duration(envInt("PUBLISH_TIMEOUT_MS", defaultPublishTimeoutMS)) * time.Millisecond,
		ShutdownTimeout:      time.Duration(envInt("SHUTDOWN_TIMEOUT_SECONDS", defaultShutdownSeconds)) * time.Second,
		ReadHeaderTimeout:    5 * time.Second,
		ReadTimeout:          10 * time.Second,
		WriteTimeout:         10 * time.Second,
		IdleTimeout:          60 * time.Second,
		RequireKafka:         true,
		RequireWebhookSecret: true,
	}
}

func newLogger() *slog.Logger {
	level := slog.LevelInfo
	switch strings.ToLower(envOr("LOG_LEVEL", "info")) {
	case "debug":
		level = slog.LevelDebug
	case "warn", "warning":
		level = slog.LevelWarn
	case "error":
		level = slog.LevelError
	}
	return slog.New(slog.NewJSONHandler(os.Stdout, &slog.HandlerOptions{Level: level}))
}

func newMetrics() *metrics {
	requestsTotal := prometheus.NewCounterVec(prometheus.CounterOpts{
		Name: "payment_webhook_requests_total",
		Help: "Total webhook requests",
	}, []string{"endpoint", "status"})
	requestDuration := prometheus.NewHistogramVec(prometheus.HistogramOpts{
		Name:    "payment_webhook_request_duration_seconds",
		Help:    "Webhook request latency",
		Buckets: []float64{0.001, 0.0025, 0.005, 0.01, 0.025, 0.05, 0.1, 0.25, 0.5, 1},
	}, []string{"endpoint"})
	signatureFailures := prometheus.NewCounter(prometheus.CounterOpts{
		Name: "payment_webhook_signature_failures_total",
		Help: "Webhook signature verification failures",
	})
	payloadFailures := prometheus.NewCounter(prometheus.CounterOpts{
		Name: "payment_webhook_payload_failures_total",
		Help: "Webhook payload decoding failures",
	})
	dedupeDuplicates := prometheus.NewCounter(prometheus.CounterOpts{
		Name: "payment_webhook_duplicates_total",
		Help: "Webhook duplicate events",
	})
	dedupeErrors := prometheus.NewCounter(prometheus.CounterOpts{
		Name: "payment_webhook_dedupe_errors_total",
		Help: "Webhook dedupe storage errors",
	})
	publishEnqueued := prometheus.NewCounter(prometheus.CounterOpts{
		Name: "payment_webhook_publish_enqueued_total",
		Help: "Webhook events queued for Kafka",
	})
	publishDropped := prometheus.NewCounter(prometheus.CounterOpts{
		Name: "payment_webhook_publish_dropped_total",
		Help: "Webhook events dropped before Kafka",
	})
	publishSuccess := prometheus.NewCounter(prometheus.CounterOpts{
		Name: "payment_webhook_publish_success_total",
		Help: "Webhook events published to Kafka",
	})
	publishErrors := prometheus.NewCounter(prometheus.CounterOpts{
		Name: "payment_webhook_publish_errors_total",
		Help: "Webhook events failed to publish to Kafka",
	})
	queueDepth := prometheus.NewGauge(prometheus.GaugeOpts{
		Name: "payment_webhook_publish_queue_depth",
		Help: "Publish queue depth",
	})
	prometheus.MustRegister(
		requestsTotal,
		requestDuration,
		signatureFailures,
		payloadFailures,
		dedupeDuplicates,
		dedupeErrors,
		publishEnqueued,
		publishDropped,
		publishSuccess,
		publishErrors,
		queueDepth,
	)
	return &metrics{
		requestsTotal:     requestsTotal,
		requestDuration:   requestDuration,
		signatureFailures: signatureFailures,
		payloadFailures:   payloadFailures,
		dedupeDuplicates:  dedupeDuplicates,
		dedupeErrors:      dedupeErrors,
		publishEnqueued:   publishEnqueued,
		publishDropped:    publishDropped,
		publishSuccess:    publishSuccess,
		publishErrors:     publishErrors,
		queueDepth:        queueDepth,
	}
}

func initTracing(cfg Config, logger *slog.Logger) (func(context.Context) error, error) {
	endpoint := envOr("OTEL_EXPORTER_OTLP_ENDPOINT", "")
	traceEndpoint := envOr("OTEL_EXPORTER_OTLP_TRACES_ENDPOINT", "")
	if endpoint == "" && traceEndpoint == "" {
		otel.SetTracerProvider(trace.NewNoopTracerProvider())
		return func(context.Context) error { return nil }, nil
	}
	if traceEndpoint != "" {
		endpoint = traceEndpoint
	}
	opts := []otlptracehttp.Option{}
	if strings.HasPrefix(endpoint, "http://") || strings.HasPrefix(endpoint, "https://") {
		opts = append(opts, otlptracehttp.WithEndpointURL(endpoint))
	} else {
		opts = append(opts, otlptracehttp.WithEndpoint(endpoint))
		opts = append(opts, otlptracehttp.WithInsecure())
	}
	exporter, err := otlptracehttp.New(context.Background(), opts...)
	if err != nil {
		return func(context.Context) error { return nil }, err
	}
	res, err := resource.New(context.Background(),
		resource.WithAttributes(
			semconv.ServiceName("payment-webhook-service"),
			attribute.String("service.port", cfg.Port),
		),
	)
	if err != nil {
		return func(context.Context) error { return nil }, err
	}
	tp := sdktrace.NewTracerProvider(
		sdktrace.WithResource(res),
		sdktrace.WithBatcher(exporter),
	)
	otel.SetTracerProvider(tp)
	otel.SetTextMapPropagator(propagation.NewCompositeTextMapPropagator(propagation.TraceContext{}, propagation.Baggage{}))
	logger.Info("otel tracing enabled")
	return tp.Shutdown, nil
}

func newInMemoryDedupe(ttl, cleanupInterval time.Duration) *inMemoryDedupe {
	store := &inMemoryDedupe{
		ttl:     ttl,
		entries: make(map[string]time.Time),
		stop:    make(chan struct{}),
	}
	go store.cleanupLoop(cleanupInterval)
	return store
}

func (d *inMemoryDedupe) CheckAndSet(_ context.Context, eventID string) (bool, error) {
	now := time.Now()
	d.mu.Lock()
	defer d.mu.Unlock()
	if exp, ok := d.entries[eventID]; ok && exp.After(now) {
		return false, nil
	}
	d.entries[eventID] = now.Add(d.ttl)
	return true, nil
}

func (d *inMemoryDedupe) Remove(_ context.Context, eventID string) error {
	d.mu.Lock()
	delete(d.entries, eventID)
	d.mu.Unlock()
	return nil
}

func (d *inMemoryDedupe) Close() error {
	close(d.stop)
	return nil
}

func (d *inMemoryDedupe) cleanupLoop(interval time.Duration) {
	ticker := time.NewTicker(interval)
	defer ticker.Stop()
	for {
		select {
		case <-ticker.C:
			now := time.Now()
			d.mu.Lock()
			for key, exp := range d.entries {
				if exp.Before(now) {
					delete(d.entries, key)
				}
			}
			d.mu.Unlock()
		case <-d.stop:
			return
		}
	}
}

func newRedisDedupe(cfg Config) (*redisDedupe, error) {
	client := redis.NewClient(&redis.Options{
		Addr:         cfg.RedisAddr,
		Password:     cfg.RedisPassword,
		DB:           cfg.RedisDB,
		DialTimeout:  cfg.RedisTimeout,
		ReadTimeout:  cfg.RedisTimeout,
		WriteTimeout: cfg.RedisTimeout,
	})
	ctx, cancel := context.WithTimeout(context.Background(), cfg.RedisTimeout)
	defer cancel()
	if err := client.Ping(ctx).Err(); err != nil {
		return nil, err
	}
	return &redisDedupe{
		client:  client,
		ttl:     cfg.DedupeTTL,
		prefix:  "payment-webhook:event:",
		timeout: cfg.RedisTimeout,
	}, nil
}

func (d *redisDedupe) CheckAndSet(ctx context.Context, eventID string) (bool, error) {
	ctx, cancel := context.WithTimeout(ctx, d.timeout)
	defer cancel()
	return d.client.SetNX(ctx, d.prefix+eventID, "1", d.ttl).Result()
}

func (d *redisDedupe) Remove(ctx context.Context, eventID string) error {
	ctx, cancel := context.WithTimeout(ctx, d.timeout)
	defer cancel()
	return d.client.Del(ctx, d.prefix+eventID).Err()
}

func (d *redisDedupe) Close() error {
	return d.client.Close()
}

func (h *webhookHandler) ServeHTTP(w http.ResponseWriter, r *http.Request) {
	start := time.Now()
	status := http.StatusOK
	endpoint := "webhook"
	defer func() {
		h.metrics.requestsTotal.WithLabelValues(endpoint, strconv.Itoa(status)).Inc()
		h.metrics.requestDuration.WithLabelValues(endpoint).Observe(time.Since(start).Seconds())
	}()

	if r.Method != http.MethodPost {
		w.Header().Set("Allow", "POST")
		status = http.StatusMethodNotAllowed
		writeError(w, status, "method not allowed")
		return
	}
	if h.cfg.RequireWebhookSecret && h.cfg.WebhookSecret == "" {
		status = http.StatusServiceUnavailable
		writeError(w, status, "webhook secret not configured")
		return
	}
	if h.cfg.RequireKafka && h.publishCh == nil {
		status = http.StatusServiceUnavailable
		writeError(w, status, "kafka not configured")
		return
	}

	r.Body = http.MaxBytesReader(w, r.Body, h.cfg.MaxBodyBytes)
	defer r.Body.Close()
	payload, err := io.ReadAll(r.Body)
	if err != nil {
		status = http.StatusRequestEntityTooLarge
		writeError(w, status, "request body too large")
		return
	}
	if len(payload) == 0 {
		status = http.StatusBadRequest
		h.metrics.payloadFailures.Inc()
		writeError(w, status, "empty payload")
		return
	}
	signatureHeader := r.Header.Get(h.cfg.SignatureHeader)
	if !verifySignature(payload, signatureHeader, h.cfg.WebhookSecret, h.cfg.SignatureTolerance) {
		status = http.StatusBadRequest
		h.metrics.signatureFailures.Inc()
		writeError(w, status, "invalid signature")
		return
	}

	var event stripeEvent
	if err := json.Unmarshal(payload, &event); err != nil {
		status = http.StatusBadRequest
		h.metrics.payloadFailures.Inc()
		writeError(w, status, "invalid event payload")
		return
	}
	if event.ID == "" {
		status = http.StatusBadRequest
		h.metrics.payloadFailures.Inc()
		writeError(w, status, "missing event id")
		return
	}
	eventType := event.Type
	if eventType == "" {
		eventType = "unknown"
	}

	ctx := r.Context()
	isNew, err := h.dedupe.CheckAndSet(ctx, event.ID)
	if err != nil {
		status = http.StatusServiceUnavailable
		h.metrics.dedupeErrors.Inc()
		writeError(w, status, "dedupe unavailable")
		return
	}
	if !isNew {
		status = http.StatusOK
		h.metrics.dedupeDuplicates.Inc()
		writeJSON(w, status, statusResponse{Status: "duplicate", EventID: event.ID, Duplicate: true})
		return
	}

	headers := []kafka.Header{
		{Key: "event_id", Value: []byte(event.ID)},
		{Key: "event_type", Value: []byte(eventType)},
	}
	otel.GetTextMapPropagator().Inject(ctx, kafkaHeaderCarrier{headers: &headers})

	req := publishRequest{
		message: kafka.Message{
			Key:     []byte(event.ID),
			Value:   payload,
			Time:    time.Now().UTC(),
			Headers: headers,
		},
		spanContext: trace.SpanContextFromContext(ctx),
		eventID:     event.ID,
		eventType:   eventType,
	}

	select {
	case h.publishCh <- req:
		h.metrics.publishEnqueued.Inc()
		h.metrics.queueDepth.Set(float64(len(h.publishCh)))
		status = http.StatusAccepted
		writeJSON(w, status, statusResponse{Status: "accepted", EventID: event.ID})
		loggerWithTrace(ctx, h.logger).Info("webhook accepted", "event_id", event.ID, "event_type", eventType)
	default:
		_ = h.dedupe.Remove(ctx, event.ID)
		h.metrics.publishDropped.Inc()
		status = http.StatusServiceUnavailable
		writeError(w, status, "publish queue full")
		loggerWithTrace(ctx, h.logger).Warn("publish queue full", "event_id", event.ID)
	}
}

func runPublisher(writer *kafka.Writer, publishCh <-chan publishRequest, metrics *metrics, logger *slog.Logger, tracer trace.Tracer, timeout time.Duration) {
	for req := range publishCh {
		metrics.queueDepth.Set(float64(len(publishCh)))
		ctx := trace.ContextWithSpanContext(context.Background(), req.spanContext)
		ctx, span := tracer.Start(ctx, "kafka.publish", trace.WithAttributes(
			attribute.String("messaging.system", "kafka"),
			attribute.String("messaging.destination", writer.Topic),
			attribute.String("messaging.operation", "publish"),
			attribute.String("event.id", req.eventID),
			attribute.String("event.type", req.eventType),
		))
		publishCtx, cancel := context.WithTimeout(ctx, timeout)
		err := writer.WriteMessages(publishCtx, req.message)
		cancel()
		if err != nil {
			metrics.publishErrors.Inc()
			loggerWithTrace(ctx, logger).Error("failed to publish to kafka", "error", err, "event_id", req.eventID)
		} else {
			metrics.publishSuccess.Inc()
		}
		span.End()
	}
}

func (r readiness) handleReady(w http.ResponseWriter, req *http.Request) {
	if req.Method != http.MethodGet && req.Method != http.MethodHead {
		w.Header().Set("Allow", "GET, HEAD")
		writeError(w, http.StatusMethodNotAllowed, "method not allowed")
		return
	}
	if !r.secretSet {
		writeError(w, http.StatusServiceUnavailable, "webhook secret not configured")
		return
	}
	if !r.kafkaConfigured {
		writeError(w, http.StatusServiceUnavailable, "kafka not configured")
		return
	}
	if r.redisClient != nil {
		ctx, cancel := context.WithTimeout(req.Context(), r.redisTimeout)
		defer cancel()
		if err := r.redisClient.Ping(ctx).Err(); err != nil {
			writeError(w, http.StatusServiceUnavailable, "redis unavailable")
			return
		}
	}
	writeJSON(w, http.StatusOK, map[string]string{"status": "ready"})
}

func handleHealth(w http.ResponseWriter, req *http.Request) {
	if req.Method != http.MethodGet && req.Method != http.MethodHead {
		w.Header().Set("Allow", "GET, HEAD")
		writeError(w, http.StatusMethodNotAllowed, "method not allowed")
		return
	}
	writeJSON(w, http.StatusOK, map[string]string{"status": "ok"})
}

func verifySignature(payload []byte, signatureHeader, secret string, tolerance time.Duration) bool {
	if secret == "" || signatureHeader == "" {
		return false
	}
	timestamp, signatures := parseSignatureHeader(signatureHeader)
	if timestamp == "" || len(signatures) == 0 {
		return false
	}
	parsedTime, err := strconv.ParseInt(timestamp, 10, 64)
	if err != nil {
		return false
	}
	if tolerance > 0 {
		now := time.Now().Unix()
		diff := now - parsedTime
		if diff < 0 {
			diff = -diff
		}
		if diff > int64(tolerance.Seconds()) {
			return false
		}
	}
	mac := hmac.New(sha256.New, []byte(secret))
	_, _ = mac.Write([]byte(timestamp))
	_, _ = mac.Write([]byte("."))
	_, _ = mac.Write(payload)
	expected := hex.EncodeToString(mac.Sum(nil))
	for _, signature := range signatures {
		if hmac.Equal([]byte(signature), []byte(expected)) {
			return true
		}
	}
	return false
}

func parseSignatureHeader(header string) (string, []string) {
	var timestamp string
	var signatures []string
	parts := strings.Split(header, ",")
	for _, part := range parts {
		part = strings.TrimSpace(part)
		if part == "" {
			continue
		}
		pair := strings.SplitN(part, "=", 2)
		if len(pair) != 2 {
			continue
		}
		switch pair[0] {
		case "t":
			timestamp = pair[1]
		case "v1":
			signatures = append(signatures, pair[1])
		}
	}
	return timestamp, signatures
}

func loggerWithTrace(ctx context.Context, logger *slog.Logger) *slog.Logger {
	span := trace.SpanFromContext(ctx)
	sc := span.SpanContext()
	if !sc.IsValid() {
		return logger
	}
	return logger.With("trace_id", sc.TraceID().String(), "span_id", sc.SpanID().String())
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

func waitWithTimeout(wg *sync.WaitGroup, timeout time.Duration, logger *slog.Logger) {
	done := make(chan struct{})
	go func() {
		wg.Wait()
		close(done)
	}()
	select {
	case <-done:
		return
	case <-time.After(timeout):
		logger.Warn("publish shutdown timeout exceeded")
	}
}

func envOr(key, fallback string) string {
	if value := strings.TrimSpace(os.Getenv(key)); value != "" {
		return value
	}
	return fallback
}

func envInt(key string, fallback int) int {
	value := strings.TrimSpace(os.Getenv(key))
	if value == "" {
		return fallback
	}
	parsed, err := strconv.Atoi(value)
	if err != nil {
		return fallback
	}
	return parsed
}

func filterEmpty(values []string) []string {
	out := make([]string, 0, len(values))
	for _, value := range values {
		if value != "" {
			out = append(out, value)
		}
	}
	return out
}

type kafkaHeaderCarrier struct {
	headers *[]kafka.Header
}

func (c kafkaHeaderCarrier) Get(key string) string {
	for _, header := range *c.headers {
		if strings.EqualFold(header.Key, key) {
			return string(header.Value)
		}
	}
	return ""
}

func (c kafkaHeaderCarrier) Set(key, value string) {
	*c.headers = append(*c.headers, kafka.Header{Key: key, Value: []byte(value)})
}

func (c kafkaHeaderCarrier) Keys() []string {
	keys := make([]string, 0, len(*c.headers))
	for _, header := range *c.headers {
		keys = append(keys, header.Key)
	}
	return keys
}

