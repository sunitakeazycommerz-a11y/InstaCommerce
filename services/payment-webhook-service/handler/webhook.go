// Package handler provides production webhook handling for multiple payment
// service providers (Stripe, Razorpay, PhonePe). Each PSP has its own
// signature verification scheme and event-parsing logic.
package handler

import (
	"context"
	"encoding/json"
	"fmt"
	"io"
	"log/slog"
	"net/http"
	"time"
)

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

// maxWebhookBody is the upper bound on request body size (1 MiB).
const maxWebhookBody = 1 << 20

// WebhookEvent is the canonical, PSP-agnostic representation of a payment
// webhook event that is published to Kafka for downstream consumers.
type WebhookEvent struct {
	ID          string    `json:"id"`
	PSP         PSPType   `json:"psp"`
	EventType   string    `json:"event_type"`   // e.g. payment.captured, payment.failed, refund.processed
	PaymentID   string    `json:"payment_id"`
	OrderID     string    `json:"order_id"`
	AmountCents int64     `json:"amount_cents"`
	Currency    string    `json:"currency"`
	Status      string    `json:"status"`
	RawPayload  []byte    `json:"-"`
	ReceivedAt  time.Time `json:"received_at"`
}

// KafkaProducer abstracts Kafka message publishing so that the handler can be
// tested without a live broker.
type KafkaProducer interface {
	// Publish sends a serialised event to the given topic. Implementations
	// must respect ctx cancellation and return a wrapped error on failure.
	Publish(ctx context.Context, topic string, key []byte, value []byte) error
}

// WebhookHandler routes incoming PSP callbacks through signature verification,
// idempotency de-duplication, and Kafka publishing.
type WebhookHandler struct {
	verifiers map[PSPType]SignatureVerifier
	deduper   *IdempotencyStore
	producer  KafkaProducer
	topic     string
	logger    *slog.Logger
	metrics   *WebhookMetrics
}

// NewWebhookHandler creates a ready-to-use WebhookHandler.
//
// verifiers must contain at least one PSP entry. producer, deduper, logger,
// and metrics must be non-nil; the constructor returns an error otherwise.
func NewWebhookHandler(
	verifiers map[PSPType]SignatureVerifier,
	deduper *IdempotencyStore,
	producer KafkaProducer,
	topic string,
	logger *slog.Logger,
	metrics *WebhookMetrics,
) (*WebhookHandler, error) {
	if len(verifiers) == 0 {
		return nil, fmt.Errorf("handler: at least one SignatureVerifier is required")
	}
	if deduper == nil {
		return nil, fmt.Errorf("handler: IdempotencyStore must not be nil")
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
	return &WebhookHandler{
		verifiers: verifiers,
		deduper:   deduper,
		producer:  producer,
		topic:     topic,
		logger:    logger,
		metrics:   metrics,
	}, nil
}

// HandleStripe is the HTTP handler for Stripe webhook callbacks.
// Stripe signs payloads using HMAC-SHA256 and passes the signature in the
// "Stripe-Signature" header with format: t=<timestamp>,v1=<sig>.
func (h *WebhookHandler) HandleStripe(w http.ResponseWriter, r *http.Request) {
	h.handlePSP(w, r, Stripe, "Stripe-Signature")
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

	// Read body with size limit.
	r.Body = http.MaxBytesReader(w, r.Body, maxWebhookBody)
	defer r.Body.Close()

	payload, err := io.ReadAll(r.Body)
	if err != nil {
		h.metrics.EventsFailed.WithLabelValues(string(psp), "read_body").Inc()
		h.writeError(w, http.StatusRequestEntityTooLarge, "request body too large")
		return
	}

	// Verify signature.
	verifyStart := time.Now()
	verifier, ok := h.verifiers[psp]
	if !ok {
		h.metrics.EventsFailed.WithLabelValues(string(psp), "no_verifier").Inc()
		h.writeError(w, http.StatusInternalServerError, "unsupported PSP")
		return
	}

	signature := r.Header.Get(sigHeader)
	if err := verifier.Verify(payload, signature); err != nil {
		h.metrics.VerifyLatency.WithLabelValues(string(psp)).Observe(time.Since(verifyStart).Seconds())
		h.metrics.EventsFailed.WithLabelValues(string(psp), "signature").Inc()
		h.logger.WarnContext(ctx, "signature verification failed",
			"psp", psp, "error", err)
		h.writeError(w, http.StatusUnauthorized, "invalid signature")
		return
	}
	h.metrics.VerifyLatency.WithLabelValues(string(psp)).Observe(time.Since(verifyStart).Seconds())

	// Parse event.
	event, err := h.parseEvent(psp, payload)
	if err != nil {
		h.metrics.EventsFailed.WithLabelValues(string(psp), "parse").Inc()
		h.logger.WarnContext(ctx, "event parse failed",
			"psp", psp, "error", err)
		h.writeError(w, http.StatusBadRequest, "invalid event payload")
		return
	}

	// Idempotency check.
	if h.deduper.IsDuplicate(event.ID) {
		h.metrics.EventsDuplicate.WithLabelValues(string(psp), event.EventType).Inc()
		h.logger.DebugContext(ctx, "duplicate event skipped",
			"psp", psp, "event_id", event.ID)
		h.writeJSON(w, http.StatusOK, map[string]string{"status": "duplicate"})
		return
	}
	h.deduper.Mark(event.ID)

	// Publish to Kafka.
	processStart := time.Now()
	encoded, err := json.Marshal(event)
	if err != nil {
		h.metrics.EventsFailed.WithLabelValues(string(psp), "marshal").Inc()
		h.writeError(w, http.StatusInternalServerError, "internal error")
		return
	}

	if err := h.producer.Publish(ctx, h.topic, []byte(event.ID), encoded); err != nil {
		h.metrics.EventsFailed.WithLabelValues(string(psp), "publish").Inc()
		h.logger.ErrorContext(ctx, "kafka publish failed",
			"psp", psp, "event_id", event.ID, "error", err)
		h.writeError(w, http.StatusServiceUnavailable, "publish failed")
		return
	}

	h.metrics.EventsProcessed.WithLabelValues(string(psp), event.EventType).Inc()
	h.metrics.ProcessLatency.WithLabelValues(string(psp)).Observe(time.Since(processStart).Seconds())

	h.logger.InfoContext(ctx, "webhook processed",
		"psp", psp, "event_id", event.ID, "event_type", event.EventType)
	h.writeJSON(w, http.StatusAccepted, map[string]string{
		"status":   "accepted",
		"event_id": event.ID,
	})
}

// parseEvent converts a raw PSP payload into a canonical WebhookEvent.
func (h *WebhookHandler) parseEvent(psp PSPType, payload []byte) (WebhookEvent, error) {
	// Each PSP sends a different JSON schema. We normalise into WebhookEvent.
	var raw map[string]interface{}
	if err := json.Unmarshal(payload, &raw); err != nil {
		return WebhookEvent{}, fmt.Errorf("handler: unmarshal payload: %w", err)
	}

	event := WebhookEvent{
		PSP:        psp,
		RawPayload: payload,
		ReceivedAt: time.Now().UTC(),
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
		return WebhookEvent{}, fmt.Errorf("handler: event ID is required")
	}

	return event, nil
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
