package main

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"log/slog"
	"net"
	"strings"
	"sync/atomic"
	"time"

	"github.com/IBM/sarama"
	"github.com/instacommerce/go-shared/pkg/health"
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

type telemetry struct {
	tracer   trace.Tracer
	meter    metric.Meter
	shutdown func(context.Context) error
}

type relayMetrics struct {
	relayed  metric.Int64Counter
	failures metric.Int64Counter
	dlq      metric.Int64Counter
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
	SourceService string
	CorrelationID string
	SchemaVersion string
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
		if err := traceProvider.Shutdown(ctx); err != nil {
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

	traceExporter, err := otlptracehttp.New(ctx, otlpHTTPEndpointOptions(cfg.OTLPEndpoint)...)
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

	metricExporter, err := otlpmetrichttp.New(ctx, otlpMetricHTTPEndpointOptions(cfg.OTLPEndpoint)...)
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
	if strings.HasPrefix(endpoint, "https://") {
		return []otlptracehttp.Option{otlptracehttp.WithEndpointURL(endpoint)}
	}
	if strings.HasPrefix(endpoint, "http://") {
		return []otlptracehttp.Option{otlptracehttp.WithEndpointURL(endpoint), otlptracehttp.WithInsecure()}
	}
	return []otlptracehttp.Option{otlptracehttp.WithEndpoint(endpoint), otlptracehttp.WithInsecure()}
}

func otlpMetricHTTPEndpointOptions(endpoint string) []otlpmetrichttp.Option {
	if strings.HasPrefix(endpoint, "https://") {
		return []otlpmetrichttp.Option{otlpmetrichttp.WithEndpointURL(endpoint)}
	}
	if strings.HasPrefix(endpoint, "http://") {
		return []otlpmetrichttp.Option{otlpmetrichttp.WithEndpointURL(endpoint), otlpmetrichttp.WithInsecure()}
	}
	return []otlpmetrichttp.Option{otlpmetrichttp.WithEndpoint(endpoint), otlpmetrichttp.WithInsecure()}
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
	dlq, err := meter.Int64Counter(
		"outbox.relay.dlq.count",
		metric.WithDescription("Number of outbox events sent to dead-letter queue."),
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
	return relayMetrics{relayed: relayed, failures: failures, dlq: dlq, lag: lag}, nil
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

	if len(events) == 0 {
		return nil
	}

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

		traceID := ""
		if msgSpan.SpanContext().HasTraceID() {
			traceID = msgSpan.SpanContext().TraceID().String()
		}

		message := &sarama.ProducerMessage{
			Topic: topic,
			Key:   sarama.StringEncoder(evt.AggregateID),
			Value: sarama.ByteEncoder(buildEventMessage(evt)),
			Headers: buildSaramaHeaders(evt, traceID,
				sarama.RecordHeader{Key: []byte("schema_version"), Value: []byte("v1")},
			),
		}

		if _, _, err := s.producer.SendMessage(message); err != nil {
			s.metrics.failures.Add(msgCtx, 1)
			msgSpan.RecordError(err)
			s.logger.Error("failed to publish message", "error", err, "event_id", evt.ID, "topic", topic)

			dlqMessage := &sarama.ProducerMessage{
				Topic: s.cfg.DLQTopic,
				Key:   sarama.StringEncoder(evt.AggregateID),
				Value: sarama.ByteEncoder(buildDLQMessage(evt, topic, err)),
				Headers: buildSaramaHeaders(evt, traceID,
					sarama.RecordHeader{Key: []byte("original_topic"), Value: []byte(topic)},
					sarama.RecordHeader{Key: []byte("dlq_reason"), Value: []byte(err.Error())},
				),
			}

			if _, _, dlqErr := s.producer.SendMessage(dlqMessage); dlqErr != nil {
				s.logger.Error("failed to publish to DLQ, pipeline stalled",
					"error", dlqErr, "event_id", evt.ID, "dlq_topic", s.cfg.DLQTopic)
				msgSpan.End()
				sendErr = err
				break
			}

			s.metrics.dlq.Add(msgCtx, 1)
			s.logger.Warn("event sent to DLQ after primary produce failure",
				"event_id", evt.ID, "topic", topic, "dlq_topic", s.cfg.DLQTopic)
			msgSpan.End()

			if _, err := tx.Exec(ctx, s.updateSQL, evt.ID); err != nil {
				span.RecordError(err)
				return s.fail(ctx, err)
			}
			continue
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

	return sendErr
}

func buildSaramaHeaders(evt outboxEvent, traceID string, extra ...sarama.RecordHeader) []sarama.RecordHeader {
	headers := []sarama.RecordHeader{
		{Key: []byte("event_id"), Value: []byte(evt.ID)},
		{Key: []byte("event_type"), Value: []byte(evt.EventType)},
		{Key: []byte("aggregate_type"), Value: []byte(evt.AggregateType)},
		{Key: []byte("source_service"), Value: []byte(evt.SourceService)},
		{Key: []byte("correlation_id"), Value: []byte(evt.CorrelationID)},
		{Key: []byte("X-Correlation-Id"), Value: []byte(evt.CorrelationID)},
		{Key: []byte("X-Source-Service"), Value: []byte(evt.SourceService)},
	}
	headers = append(headers, extra...)
	if traceID != "" {
		headers = append(headers,
			sarama.RecordHeader{Key: []byte("X-Trace-Id"), Value: []byte(traceID)},
			sarama.RecordHeader{Key: []byte("X-B3-TraceId"), Value: []byte(traceID)},
		)
	}
	return headers
}

func (s *relayService) fail(ctx context.Context, err error) error {
	if err != nil && !errors.Is(err, context.Canceled) {
		s.metrics.failures.Add(ctx, 1)
	}
	return err
}

type contextProxyDialer struct {
	ctx    context.Context
	dialer interface {
		Dial(network, addr string) (net.Conn, error)
	}
}

func (d *contextProxyDialer) Dial(network, addr string) (net.Conn, error) {
	if err := d.ctx.Err(); err != nil {
		return nil, err
	}

	var conn net.Conn
	var err error
	if ctxDialer, ok := d.dialer.(interface {
		DialContext(context.Context, string, string) (net.Conn, error)
	}); ok {
		conn, err = ctxDialer.DialContext(d.ctx, network, addr)
	} else {
		conn, err = d.dialer.Dial(network, addr)
	}
	if err != nil {
		return nil, err
	}

	stop := context.AfterFunc(d.ctx, func() {
		_ = conn.SetDeadline(time.Now())
	})
	return &deadlineConn{Conn: conn, stop: stop}, nil
}

type deadlineConn struct {
	net.Conn
	stop func() bool
}

func (c *deadlineConn) Close() error {
	if c.stop != nil {
		c.stop()
	}
	return c.Conn.Close()
}

func (s *relayService) probeContext(ctx context.Context) (context.Context, context.CancelFunc, time.Duration, error) {
	readyTimeout := s.cfg.ReadyTimeout
	if readyTimeout <= 0 {
		readyTimeout = defaultReadyTimeout
	}
	return health.BrokerAttemptContext(ctx, 1, readyTimeout)
}

func (s *relayService) healthKafkaConfig(ctx context.Context, timeout time.Duration) *sarama.Config {
	fresh := sarama.NewConfig()
	var cfg sarama.Config
	if s.kafkaConfig != nil {
		cfg = *s.kafkaConfig
		cfg.MetricRegistry = fresh.MetricRegistry
	} else {
		cfg = *fresh
	}

	// Keep Sarama socket deadlines slightly behind ctx so AfterFunc closes on ctx expiration first.
	socketTimeout := timeout + 50*time.Millisecond

	baseDialer := cfg.Net.Proxy.Dialer
	if !cfg.Net.Proxy.Enable || baseDialer == nil {
		baseDialer = &net.Dialer{
			Timeout:   socketTimeout,
			KeepAlive: cfg.Net.KeepAlive,
			LocalAddr: cfg.Net.LocalAddr,
		}
	}

	cfg.Net.DialTimeout = socketTimeout
	cfg.Net.ReadTimeout = socketTimeout
	cfg.Net.WriteTimeout = socketTimeout
	cfg.Metadata.Timeout = socketTimeout
	cfg.Metadata.Retry.Max = 0
	cfg.Metadata.RefreshFrequency = 0
	cfg.ApiVersionsRequest = false
	cfg.Net.Proxy.Enable = true
	cfg.Net.Proxy.Dialer = &contextProxyDialer{
		ctx:    ctx,
		dialer: baseDialer,
	}
	return &cfg
}

func probeError(ctx context.Context, err error) error {
	if ctxErr := ctx.Err(); ctxErr != nil {
		return ctxErr
	}
	if deadline, ok := ctx.Deadline(); ok && !time.Now().Before(deadline) {
		return context.DeadlineExceeded
	}
	return err
}

func (s *relayService) checkKafka(ctx context.Context) error {
	probeCtx, cancel, timeout, err := s.probeContext(ctx)
	if err != nil {
		return err
	}
	defer cancel()

	client, err := sarama.NewClient(s.cfg.KafkaBrokers, s.healthKafkaConfig(probeCtx, timeout))
	if err != nil {
		return probeError(probeCtx, err)
	}
	defer client.Close()

	if err := probeCtx.Err(); err != nil {
		return err
	}

	controller, err := client.Controller()
	if err != nil {
		return probeError(probeCtx, err)
	}

	connected, err := controller.Connected()
	if err != nil {
		return probeError(probeCtx, err)
	}
	if !connected {
		return probeError(probeCtx, sarama.ErrNotConnected)
	}
	return nil
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

func resolveKafkaTopic(overrideTopic, aggregateType string) string {
	if trimmed := strings.TrimSpace(overrideTopic); trimmed != "" {
		return trimmed
	}

	normalized := normalizeAggregateType(aggregateType)
	switch normalized {
	case "order", "orders":
		return "orders.events"
	case "payment", "payments":
		return "payments.events"
	case "inventory":
		return "inventory.events"
	case "fulfillment":
		return "fulfillment.events"
	case "catalog", "product", "products":
		return "catalog.events"
	case "identity", "user", "users":
		return "identity.events"
	case "rider", "riders", "riderfleet", "rider_fleet":
		return "rider.events"
	case "warehouse", "store", "stores":
		return "warehouse.events"
	case "pricing", "price":
		return "pricing.events"
	case "wallet", "loyalty":
		return "wallet.events"
	case "fraud":
		return "fraud.events"
	case "notification", "notifications":
		return "notification.events"
	case "cart", "carts":
		return "cart.events"
	case "delivery", "routing":
		return "delivery.events"
	default:
		if normalized == "" {
			return "unknown.events"
		}
		return normalized + ".events"
	}
}

func normalizeAggregateType(raw string) string {
	raw = strings.TrimSpace(strings.ToLower(raw))
	if raw == "" {
		return ""
	}

	var b strings.Builder
	b.Grow(len(raw))
	lastWasUnderscore := false
	for _, r := range raw {
		switch {
		case r >= 'a' && r <= 'z':
			b.WriteRune(r)
			lastWasUnderscore = false
		case r >= '0' && r <= '9':
			b.WriteRune(r)
			lastWasUnderscore = false
		default:
			if b.Len() == 0 {
				continue
			}
			if !lastWasUnderscore {
				b.WriteByte('_')
				lastWasUnderscore = true
			}
		}
	}
	return strings.Trim(b.String(), "_")
}

func buildEventMessage(evt outboxEvent) []byte {
	var payload any
	if err := json.Unmarshal(evt.Payload, &payload); err != nil {
		return evt.Payload
	}

	schemaVersion := evt.SchemaVersion
	if schemaVersion == "" {
		schemaVersion = "v1"
	}

	eventTime := evt.CreatedAt.UTC().Format(time.RFC3339Nano)
	envelope := map[string]any{
		"id":             evt.ID,
		"eventId":        evt.ID,
		"event_id":       evt.ID,
		"aggregateType":  evt.AggregateType,
		"aggregate_type": evt.AggregateType,
		"aggregateId":    evt.AggregateID,
		"aggregate_id":   evt.AggregateID,
		"eventType":      evt.EventType,
		"event_type":     evt.EventType,
		"eventTime":      eventTime,
		"event_time":     eventTime,
		"timestamp":      eventTime,
		"schemaVersion":  schemaVersion,
		"schema_version": schemaVersion,
		"source_service": evt.SourceService,
		"sourceService":  evt.SourceService,
		"payload":        payload,
	}
	if evt.CorrelationID != "" {
		envelope["correlation_id"] = evt.CorrelationID
		envelope["correlationId"] = evt.CorrelationID
	}
	if payloadMap, ok := payload.(map[string]any); ok {
		for key, value := range payloadMap {
			if _, exists := envelope[key]; !exists {
				envelope[key] = value
			}
		}
	}

	bytes, err := json.Marshal(envelope)
	if err != nil {
		return evt.Payload
	}
	return bytes
}

func buildDLQMessage(evt outboxEvent, targetTopic string, produceErr error) []byte {
	var payload any
	if err := json.Unmarshal(evt.Payload, &payload); err != nil {
		payload = string(evt.Payload)
	}

	dlqEnvelope := map[string]any{
		"eventId":       evt.ID,
		"originalTopic": targetTopic,
		"error":         produceErr.Error(),
		"timestamp":     time.Now().UTC().Format(time.RFC3339Nano),
		"aggregateType": evt.AggregateType,
		"aggregateId":   evt.AggregateID,
		"eventType":     evt.EventType,
		"payload":       payload,
	}

	bytes, err := json.Marshal(dlqEnvelope)
	if err != nil {
		return evt.Payload
	}
	return bytes
}
