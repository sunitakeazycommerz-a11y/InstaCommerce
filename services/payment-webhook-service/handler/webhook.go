// Package handler provides production webhook handling for multiple payment
// service providers (Stripe, Razorpay, PhonePe). Each PSP has its own
// signature verification scheme and event-parsing logic.
package handler

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"log/slog"
	"net/http"
	"strings"
	"time"

	"github.com/segmentio/kafka-go"
	"go.opentelemetry.io/otel"
	"go.opentelemetry.io/otel/attribute"
	"go.opentelemetry.io/otel/trace"
)

// ErrMissingEventID indicates the PSP payload does not contain an event identifier.
var ErrMissingEventID = errors.New("handler: event ID is required")

// ErrProducerNotConfigured indicates Kafka publishing has not been configured.
var ErrProducerNotConfigured = errors.New("handler: kafka producer not configured")

// SchemaVersion is the current version of the Kafka transport envelope.
// Bump when fields are added so consumers can branch on capability.
const SchemaVersion = 2

// PSPType identifies a payment service provider.
type PSPType string

const (
	// Stripe PSP.
	Stripe PSPType = "stripe"
	// Razorpay PSP.
	Razorpay PSPType = "razorpay"
	// PhonePe PSP.
	PhonePe PSPType = "phonepe"
)

const (
	maxWebhookBody        = 1 << 20
	defaultPublishTimeout = 5 * time.Second
)

// StatusResponse is the JSON response returned by webhook ingestion endpoints.
type StatusResponse struct {
	Status    string `json:"status"`
	EventID   string `json:"event_id,omitempty"`
	Duplicate bool   `json:"duplicate,omitempty"`
}

// WebhookEvent is the canonical, PSP-agnostic representation of a payment
// webhook event that is published to Kafka for downstream consumers.
type WebhookEvent struct {
	// --- v1 fields (always present) ---
	ID          string    `json:"id"`
	PSP         PSPType   `json:"psp"`
	EventType   string    `json:"event_type"` // e.g. payment.captured, payment.failed, refund.processed
	PaymentID   string    `json:"payment_id"`
	OrderID     string    `json:"order_id"`
	AmountCents int64     `json:"amount_cents"`
	Currency    string    `json:"currency"`
	Status      string    `json:"status"`
	ReceivedAt  time.Time `json:"received_at"`

	// --- v2 fields (additive, backward-compatible) ---

	// Version indicates the schema version of this event envelope.
	// Absent (zero) in v1 messages; set to SchemaVersion for v2+.
	Version int `json:"schema_version,omitempty"`

	// RawPSPPayload is the verbatim JSON body received from the PSP,
	// embedded as-is (no base64). Consumers that need exact PSP semantics
	// (e.g. refund evidence, dispute fields) should prefer this over the
	// lossy canonical fields above.
	RawPSPPayload json.RawMessage `json:"raw_psp_payload,omitempty"`

	// RawPayload is kept for internal use only (not serialised to Kafka).
	RawPayload []byte `json:"-"`
}

// KafkaProducer abstracts Kafka message publishing so that the handler can be
// tested without a live broker.
type KafkaProducer interface {
	// Publish sends a serialised event to the given topic. Implementations
	// must respect ctx cancellation and return a wrapped error on failure.
	Publish(ctx context.Context, topic string, key []byte, value []byte) error
}

// HeaderKafkaProducer is an optional extension of KafkaProducer that carries
// Kafka headers and event metadata (e.g. event_id, event_type, trace context).
type HeaderKafkaProducer interface {
	KafkaProducer
	PublishMessage(ctx context.Context, topic string, msg kafka.Message) error
}

// MessageWriter abstracts kafka.Writer for production publishing and unit testing.
type MessageWriter interface {
	WriteMessages(ctx context.Context, msgs ...kafka.Message) error
	Close() error
}

// KafkaWriterProducer adapts a MessageWriter (such as *kafka.Writer) to KafkaProducer
// and HeaderKafkaProducer.
type KafkaWriterProducer struct {
	writer MessageWriter
}

// NewKafkaWriterProducer creates a KafkaWriterProducer around writer.
func NewKafkaWriterProducer(writer MessageWriter) *KafkaWriterProducer {
	return &KafkaWriterProducer{writer: writer}
}

// Publish sends a keyed payload to Kafka.
func (p *KafkaWriterProducer) Publish(ctx context.Context, topic string, key []byte, value []byte) error {
	return p.PublishMessage(ctx, topic, kafka.Message{
		Key:   key,
		Value: value,
		Time:  time.Now().UTC(),
	})
}

// PublishMessage sends a full kafka.Message (including headers and timestamp) to Kafka.
func (p *KafkaWriterProducer) PublishMessage(ctx context.Context, _ string, msg kafka.Message) error {
	if p == nil || p.writer == nil {
		return ErrProducerNotConfigured
	}
	return p.writer.WriteMessages(ctx, msg)
}

// UnconfiguredKafkaProducer returns ErrProducerNotConfigured so endpoints return HTTP 503.
type UnconfiguredKafkaProducer struct{}

// Publish returns ErrProducerNotConfigured.
func (UnconfiguredKafkaProducer) Publish(_ context.Context, _ string, _ []byte, _ []byte) error {
	return ErrProducerNotConfigured
}

// WebhookOption configures optional settings on WebhookHandler.
type WebhookOption func(*WebhookHandler)

// WithMaxBodyBytes overrides the maximum allowed webhook payload size.
func WithMaxBodyBytes(limit int64) WebhookOption {
	return func(h *WebhookHandler) {
		if limit > 0 {
			h.maxBodyBytes = limit
		}
	}
}

// WithPublishTimeout overrides the per-message Kafka publish timeout.
func WithPublishTimeout(timeout time.Duration) WebhookOption {
	return func(h *WebhookHandler) {
		if timeout > 0 {
			h.publishTimeout = timeout
		}
	}
}

// WithStripeSignatureHeader overrides the HTTP header inspected for Stripe signatures.
func WithStripeSignatureHeader(header string) WebhookOption {
	return func(h *WebhookHandler) {
		if strings.TrimSpace(header) != "" {
			h.stripeSigHeader = strings.TrimSpace(header)
		}
	}
}

// WithTracer overrides the OpenTelemetry tracer used for Kafka publish spans.
func WithTracer(tracer trace.Tracer) WebhookOption {
	return func(h *WebhookHandler) {
		if tracer != nil {
			h.tracer = tracer
		}
	}
}

// WebhookHandler routes incoming PSP callbacks through signature verification,
// idempotency de-duplication, and Kafka publishing.
type WebhookHandler struct {
	verifiers       map[PSPType]SignatureVerifier
	deduper         DedupeStore
	producer        KafkaProducer
	topic           string
	logger          *slog.Logger
	metrics         *WebhookMetrics
	maxBodyBytes    int64
	publishTimeout  time.Duration
	stripeSigHeader string
	tracer          trace.Tracer
}

// NewWebhookHandler creates a ready-to-use WebhookHandler.
//
// verifiers must contain at least one PSP entry. producer, deduper, logger,
// and metrics must be non-nil; the constructor returns an error otherwise.
func NewWebhookHandler(
	verifiers map[PSPType]SignatureVerifier,
	deduper DedupeStore,
	producer KafkaProducer,
	topic string,
	logger *slog.Logger,
	metrics *WebhookMetrics,
	opts ...WebhookOption,
) (*WebhookHandler, error) {
	if len(verifiers) == 0 {
		return nil, fmt.Errorf("handler: at least one SignatureVerifier is required")
	}
	if deduper == nil {
		return nil, fmt.Errorf("handler: DedupeStore must not be nil")
	}
	if producer == nil {
		return nil, fmt.Errorf("handler: KafkaProducer must not be nil")
	}
	if logger == nil {
		return nil, fmt.Errorf("handler: logger must not be nil")
	}
	if metrics == nil {
		return nil, fmt.Errorf("handler: WebhookMetrics must not be nil")
	}
	h := &WebhookHandler{
		verifiers:       verifiers,
		deduper:         deduper,
		producer:        producer,
		topic:           topic,
		logger:          logger,
		metrics:         metrics,
		maxBodyBytes:    maxWebhookBody,
		publishTimeout:  defaultPublishTimeout,
		stripeSigHeader: "Stripe-Signature",
		tracer:          otel.Tracer("payment-webhook-service"),
	}
	for _, opt := range opts {
		opt(h)
	}
	return h, nil
}

// HandleStripe is the HTTP handler for Stripe webhook callbacks.
// Stripe signs payloads using HMAC-SHA256 and passes the signature in the
// "Stripe-Signature" header (or configured override) with format: t=<timestamp>,v1=<sig>.
func (h *WebhookHandler) HandleStripe(w http.ResponseWriter, r *http.Request) {
	sigHeader := h.stripeSigHeader
	if sigHeader == "" {
		sigHeader = "Stripe-Signature"
	}
	h.handlePSP(w, r, Stripe, sigHeader)
}

// HandleRazorpay is the HTTP handler for Razorpay webhook callbacks.
// Razorpay signs payloads using HMAC-SHA256 and passes the signature in the
// "X-Razorpay-Signature" header as a hex-encoded string.
func (h *WebhookHandler) HandleRazorpay(w http.ResponseWriter, r *http.Request) {
	h.handlePSP(w, r, Razorpay, "X-Razorpay-Signature")
}

// HandlePhonePe is the HTTP handler for PhonePe webhook callbacks.
func (h *WebhookHandler) HandlePhonePe(w http.ResponseWriter, r *http.Request) {
	h.handlePSP(w, r, PhonePe, "X-Verify")
}

// handlePSP is the shared implementation for all PSP webhook endpoints.
func (h *WebhookHandler) handlePSP(w http.ResponseWriter, r *http.Request, psp PSPType, sigHeader string) {
	ctx := r.Context()

	if r.Method != http.MethodPost {
		w.Header().Set("Allow", "POST")
		h.writeError(w, http.StatusMethodNotAllowed, "method not allowed")
		return
	}

	h.metrics.EventsReceived.WithLabelValues(string(psp), "").Inc()

	verifier, ok := h.verifiers[psp]
	if !ok {
		h.metrics.EventsFailed.WithLabelValues(string(psp), "no_verifier").Inc()
		h.writeError(w, http.StatusInternalServerError, "unsupported PSP")
		return
	}
	if _, unconfigured := verifier.(*UnconfiguredVerifier); unconfigured {
		h.metrics.EventsFailed.WithLabelValues(string(psp), "unconfigured_verifier").Inc()
		h.writeError(w, http.StatusServiceUnavailable, "webhook secret not configured")
		return
	}
	if _, unconfigured := h.producer.(UnconfiguredKafkaProducer); unconfigured {
		h.metrics.EventsFailed.WithLabelValues(string(psp), "unconfigured_kafka").Inc()
		h.writeError(w, http.StatusServiceUnavailable, "kafka not configured")
		return
	}
	if _, unconfiguredPtr := h.producer.(*UnconfiguredKafkaProducer); unconfiguredPtr {
		h.metrics.EventsFailed.WithLabelValues(string(psp), "unconfigured_kafka").Inc()
		h.writeError(w, http.StatusServiceUnavailable, "kafka not configured")
		return
	}

	maxBody := h.maxBodyBytes
	if maxBody <= 0 {
		maxBody = maxWebhookBody
	}
	// Read body with size limit.
	r.Body = http.MaxBytesReader(w, r.Body, maxBody)
	defer r.Body.Close()

	payload, err := io.ReadAll(r.Body)
	if err != nil {
		h.metrics.EventsFailed.WithLabelValues(string(psp), "read_body").Inc()
		var maxBytesErr *http.MaxBytesError
		if errors.As(err, &maxBytesErr) {
			h.writeError(w, http.StatusRequestEntityTooLarge, "request body too large")
			return
		}
		h.writeError(w, http.StatusBadRequest, "failed to read request body")
		return
	}
	if len(payload) == 0 {
		h.metrics.EventsFailed.WithLabelValues(string(psp), "empty_body").Inc()
		h.writeError(w, http.StatusBadRequest, "empty payload")
		return
	}

	// Verify signature.
	verifyStart := time.Now()
	signature := r.Header.Get(sigHeader)
	if err := verifier.Verify(payload, signature); err != nil {
		h.metrics.VerifyLatency.WithLabelValues(string(psp)).Observe(time.Since(verifyStart).Seconds())
		if errors.Is(err, ErrVerifierNotConfigured) {
			h.metrics.EventsFailed.WithLabelValues(string(psp), "unconfigured_verifier").Inc()
			h.writeError(w, http.StatusServiceUnavailable, "webhook secret not configured")
			return
		}
		h.metrics.EventsFailed.WithLabelValues(string(psp), "signature").Inc()
		h.loggerWithTrace(ctx).WarnContext(ctx, "signature verification failed",
			"psp", psp, "error", err)
		h.writeError(w, http.StatusUnauthorized, "invalid signature")
		return
	}
	h.metrics.VerifyLatency.WithLabelValues(string(psp)).Observe(time.Since(verifyStart).Seconds())

	// Parse event.
	event, err := h.parseEvent(psp, payload)
	if err != nil {
		h.metrics.EventsFailed.WithLabelValues(string(psp), "parse").Inc()
		h.loggerWithTrace(ctx).WarnContext(ctx, "event parse failed",
			"psp", psp, "error", err)
		if errors.Is(err, ErrMissingEventID) {
			h.writeError(w, http.StatusBadRequest, "missing event id")
			return
		}
		h.writeError(w, http.StatusBadRequest, "invalid event payload")
		return
	}
	if event.EventType == "" {
		event.EventType = "unknown"
	}

	// Marshal canonical envelope before claiming idempotency lock.
	processStart := time.Now()
	encoded, err := json.Marshal(event)
	if err != nil {
		h.metrics.EventsFailed.WithLabelValues(string(psp), "marshal").Inc()
		h.writeError(w, http.StatusInternalServerError, "failed to encode event")
		return
	}

	// Atomic idempotency check.
	isDuplicate, err := h.deduper.CheckAndMark(ctx, event.ID)
	if err != nil {
		h.metrics.EventsFailed.WithLabelValues(string(psp), "dedupe").Inc()
		h.writeError(w, http.StatusServiceUnavailable, "dedupe unavailable")
		return
	}
	if isDuplicate {
		h.metrics.EventsDuplicate.WithLabelValues(string(psp), event.EventType).Inc()
		h.loggerWithTrace(ctx).DebugContext(ctx, "duplicate event skipped",
			"psp", psp, "event_id", event.ID)
		h.writeJSON(w, http.StatusOK, StatusResponse{
			Status:    "duplicate",
			EventID:   event.ID,
			Duplicate: true,
		})
		return
	}

	// Publish to Kafka keyed by PaymentID (fallback event.ID).
	if err := h.publishEvent(ctx, event, encoded); err != nil {
		removeCtx, removeCancel := context.WithTimeout(context.Background(), 5*time.Second)
		_ = h.deduper.Remove(removeCtx, event.ID)
		removeCancel()

		if errors.Is(err, ErrProducerNotConfigured) {
			h.metrics.EventsFailed.WithLabelValues(string(psp), "unconfigured_kafka").Inc()
			h.writeError(w, http.StatusServiceUnavailable, "kafka not configured")
			return
		}
		h.metrics.EventsFailed.WithLabelValues(string(psp), "publish").Inc()
		h.loggerWithTrace(ctx).ErrorContext(ctx, "kafka publish failed",
			"psp", psp, "event_id", event.ID, "error", err)
		h.writeError(w, http.StatusServiceUnavailable, "failed to publish event")
		return
	}

	h.metrics.EventsProcessed.WithLabelValues(string(psp), event.EventType).Inc()
	h.metrics.ProcessLatency.WithLabelValues(string(psp)).Observe(time.Since(processStart).Seconds())

	h.loggerWithTrace(ctx).InfoContext(ctx, "webhook accepted",
		"psp", psp, "event_id", event.ID, "event_type", event.EventType)
	h.writeJSON(w, http.StatusAccepted, StatusResponse{
		Status:  "accepted",
		EventID: event.ID,
	})
}

func (h *WebhookHandler) publishEvent(ctx context.Context, event WebhookEvent, encoded []byte) error {
	kafkaKey := event.PaymentID
	if kafkaKey == "" {
		kafkaKey = event.ID
	}

	timeout := h.publishTimeout
	if timeout <= 0 {
		timeout = defaultPublishTimeout
	}
	publishCtx, cancel := context.WithTimeout(ctx, timeout)
	defer cancel()

	if h.tracer != nil {
		var span trace.Span
		publishCtx, span = h.tracer.Start(publishCtx, "kafka.publish", trace.WithAttributes(
			attribute.String("messaging.system", "kafka"),
			attribute.String("messaging.destination", h.topic),
			attribute.String("messaging.operation", "publish"),
			attribute.String("event.id", event.ID),
			attribute.String("event.type", event.EventType),
		))
		defer span.End()
	}

	if headerProd, ok := h.producer.(HeaderKafkaProducer); ok {
		headers := []kafka.Header{
			{Key: "event_id", Value: []byte(event.ID)},
			{Key: "event_type", Value: []byte(event.EventType)},
		}
		otel.GetTextMapPropagator().Inject(publishCtx, kafkaHeaderCarrier{headers: &headers})
		return headerProd.PublishMessage(publishCtx, h.topic, kafka.Message{
			Key:     []byte(kafkaKey),
			Value:   encoded,
			Time:    event.ReceivedAt,
			Headers: headers,
		})
	}

	return h.producer.Publish(publishCtx, h.topic, []byte(kafkaKey), encoded)
}

func (h *WebhookHandler) loggerWithTrace(ctx context.Context) *slog.Logger {
	span := trace.SpanFromContext(ctx)
	sc := span.SpanContext()
	if !sc.IsValid() {
		return h.logger
	}
	return h.logger.With("trace_id", sc.TraceID().String(), "span_id", sc.SpanID().String())
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

// ParseEvent converts a raw PSP payload into a canonical WebhookEvent.
func ParseEvent(psp PSPType, payload []byte) (WebhookEvent, error) {
	// Each PSP sends a different JSON schema. We normalise into WebhookEvent.
	var raw map[string]interface{}
	if err := json.Unmarshal(payload, &raw); err != nil {
		return WebhookEvent{}, fmt.Errorf("handler: unmarshal payload: %w", err)
	}

	event := WebhookEvent{
		PSP:           psp,
		RawPayload:    payload,
		RawPSPPayload: json.RawMessage(payload),
		Version:       SchemaVersion,
		ReceivedAt:    time.Now().UTC(),
	}

	switch psp {
	case Stripe:
		event.ID, _ = jsonString(raw, "id")
		event.EventType, _ = jsonString(raw, "type")
		if data, ok := raw["data"].(map[string]interface{}); ok {
			if obj, ok := data["object"].(map[string]interface{}); ok {
				event.PaymentID, _ = jsonString(obj, "id")
				event.OrderID, _ = jsonString(obj, "metadata", "order_id")
				event.AmountCents = jsonInt64(obj, "amount")
				event.Currency, _ = jsonString(obj, "currency")
				event.Status, _ = jsonString(obj, "status")
			}
		}
	case Razorpay:
		event.EventType, _ = jsonString(raw, "event")
		if payload, ok := raw["payload"].(map[string]interface{}); ok {
			if payment, ok := payload["payment"].(map[string]interface{}); ok {
				if entity, ok := payment["entity"].(map[string]interface{}); ok {
					event.ID, _ = jsonString(entity, "id")
					event.PaymentID, _ = jsonString(entity, "id")
					event.OrderID, _ = jsonString(entity, "order_id")
					event.AmountCents = jsonInt64(entity, "amount")
					event.Currency, _ = jsonString(entity, "currency")
					event.Status, _ = jsonString(entity, "status")
				}
			}
		}
	case PhonePe:
		event.ID, _ = jsonString(raw, "transactionId")
		event.EventType, _ = jsonString(raw, "event")
		event.PaymentID, _ = jsonString(raw, "transactionId")
		event.OrderID, _ = jsonString(raw, "merchantOrderId")
		event.AmountCents = jsonInt64(raw, "amount")
		event.Currency = "INR" // PhonePe is INR-only.
		event.Status, _ = jsonString(raw, "state")
	default:
		return WebhookEvent{}, fmt.Errorf("handler: unsupported PSP %q", psp)
	}

	if event.ID == "" {
		return WebhookEvent{}, ErrMissingEventID
	}

	return event, nil
}

// parseEvent converts a raw PSP payload into a canonical WebhookEvent.
func (h *WebhookHandler) parseEvent(psp PSPType, payload []byte) (WebhookEvent, error) {
	return ParseEvent(psp, payload)
}

// jsonString extracts a nested string value from a map by walking keys.
func jsonString(m map[string]interface{}, keys ...string) (string, bool) {
	current := m
	for i, key := range keys {
		val, ok := current[key]
		if !ok {
			return "", false
		}
		if i == len(keys)-1 {
			s, ok := val.(string)
			return s, ok
		}
		next, ok := val.(map[string]interface{})
		if !ok {
			return "", false
		}
		current = next
	}
	return "", false
}

// jsonInt64 extracts a numeric value as int64 from a map.
func jsonInt64(m map[string]interface{}, key string) int64 {
	val, ok := m[key]
	if !ok {
		return 0
	}
	switch v := val.(type) {
	case float64:
		return int64(v)
	case int64:
		return v
	case json.Number:
		n, _ := v.Int64()
		return n
	default:
		return 0
	}
}

// writeJSON writes a JSON response with the given status code.
func (h *WebhookHandler) writeJSON(w http.ResponseWriter, status int, payload interface{}) {
	w.Header().Set("Content-Type", "application/json")
	w.WriteHeader(status)
	enc := json.NewEncoder(w)
	enc.SetEscapeHTML(false)
	_ = enc.Encode(payload)
}

// writeError writes a JSON error response.
func (h *WebhookHandler) writeError(w http.ResponseWriter, status int, message string) {
	h.writeJSON(w, status, map[string]string{"error": message})
}
