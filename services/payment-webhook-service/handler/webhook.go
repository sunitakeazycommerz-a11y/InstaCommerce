package handler

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"log/slog"
	"net/http"
	"strconv"
	"strings"
	"time"

	"go.opentelemetry.io/otel"
	"go.opentelemetry.io/otel/trace"
)

// WebhookHandler routes incoming PSP callbacks through signature verification,
// replay window checks, idempotency de-duplication, and Kafka publishing.
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
	replayTolerance time.Duration
	nowFunc         func() time.Time
	tracer          trace.Tracer
}

// NewWebhookHandler creates a ready-to-use WebhookHandler.
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
		nowFunc:         time.Now,
		tracer:          otel.Tracer("payment-webhook-service"),
	}
	for _, opt := range opts {
		if opt != nil {
			opt(h)
		}
	}
	return h, nil
}

func (h *WebhookHandler) HandleStripe(w http.ResponseWriter, r *http.Request) {
	sigHeader := h.stripeSigHeader
	if sigHeader == "" {
		sigHeader = "Stripe-Signature"
	}
	h.handlePSP(w, r, Stripe, sigHeader)
}

func (h *WebhookHandler) HandleRazorpay(w http.ResponseWriter, r *http.Request) {
	h.handlePSP(w, r, Razorpay, "X-Razorpay-Signature")
}

func (h *WebhookHandler) HandlePhonePe(w http.ResponseWriter, r *http.Request) {
	h.handlePSP(w, r, PhonePe, "X-Verify")
}

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
	if isProducerUnconfigured(h.producer) {
		h.metrics.EventsFailed.WithLabelValues(string(psp), "unconfigured_kafka").Inc()
		h.writeError(w, http.StatusServiceUnavailable, "kafka not configured")
		return
	}

	maxBody := h.maxBodyBytes
	if maxBody <= 0 {
		maxBody = maxWebhookBody
	}
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
		h.loggerWithTrace(ctx).WarnContext(ctx, "signature verification failed", "psp", psp, "error", err)
		h.writeError(w, http.StatusUnauthorized, "invalid signature")
		return
	}
	if err := h.enforceReplayWindow(r, psp, sigHeader, verifier, payload); err != nil {
		h.metrics.VerifyLatency.WithLabelValues(string(psp)).Observe(time.Since(verifyStart).Seconds())
		h.metrics.EventsFailed.WithLabelValues(string(psp), "replay_window").Inc()
		h.loggerWithTrace(ctx).WarnContext(ctx, "webhook timestamp outside replay window", "psp", psp, "error", err)
		h.writeError(w, http.StatusUnauthorized, "signature timestamp outside replay window")
		return
	}
	h.metrics.VerifyLatency.WithLabelValues(string(psp)).Observe(time.Since(verifyStart).Seconds())

	event, err := ParseEvent(psp, payload)
	if err != nil {
		h.metrics.EventsFailed.WithLabelValues(string(psp), "parse").Inc()
		h.loggerWithTrace(ctx).WarnContext(ctx, "event parse failed", "psp", psp, "error", err)
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

	processStart := time.Now()
	encoded, err := json.Marshal(event)
	if err != nil {
		h.metrics.EventsFailed.WithLabelValues(string(psp), "marshal").Inc()
		h.writeError(w, http.StatusInternalServerError, "failed to encode event")
		return
	}

	isDuplicate, err := h.deduper.CheckAndMark(ctx, event.ID)
	if err != nil {
		h.metrics.EventsFailed.WithLabelValues(string(psp), "dedupe").Inc()
		h.writeError(w, http.StatusServiceUnavailable, "dedupe unavailable")
		return
	}
	if isDuplicate {
		h.metrics.EventsDuplicate.WithLabelValues(string(psp), event.EventType).Inc()
		h.writeJSON(w, http.StatusOK, StatusResponse{
			Status:    "duplicate",
			EventID:   event.ID,
			Duplicate: true,
		})
		return
	}

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
		h.loggerWithTrace(ctx).ErrorContext(ctx, "kafka publish failed", "psp", psp, "event_id", event.ID, "error", err)
		h.writeError(w, http.StatusServiceUnavailable, "failed to publish event")
		return
	}

	h.metrics.EventsProcessed.WithLabelValues(string(psp), event.EventType).Inc()
	h.metrics.ProcessLatency.WithLabelValues(string(psp)).Observe(time.Since(processStart).Seconds())
	h.loggerWithTrace(ctx).InfoContext(ctx, "webhook accepted", "psp", psp, "event_id", event.ID, "event_type", event.EventType)
	h.writeJSON(w, http.StatusAccepted, StatusResponse{
		Status:  "accepted",
		EventID: event.ID,
	})
}

func (h *WebhookHandler) enforceReplayWindow(r *http.Request, psp PSPType, sigHeader string, verifier SignatureVerifier, payload []byte) error {
	if h.replayTolerance <= 0 {
		return nil
	}
	if psp == Stripe {
		if sv, ok := verifier.(*StripeVerifier); ok && sv.Tolerance() > 0 && h.replayTolerance >= sv.Tolerance() {
			return nil
		}
		rawTS, _ := parseStripeHeader(r.Header.Get(sigHeader))
		if rawTS == "" {
			return fmt.Errorf("verify: missing webhook timestamp for %s", psp)
		}
		nowFn := h.nowFunc
		if nowFn == nil {
			nowFn = time.Now
		}
		return validateUnixReplayWindow(rawTS, h.replayTolerance, nowFn())
	}

	var rawTS string
	if psp == Razorpay {
		var envelope struct {
			CreatedAt *int64 `json:"created_at"`
		}
		if json.Unmarshal(payload, &envelope) == nil && envelope.CreatedAt != nil {
			rawTS = strconv.FormatInt(*envelope.CreatedAt, 10)
		}
		if rawTS == "" {
			rawTS = strings.TrimSpace(r.Header.Get("X-Webhook-Timestamp"))
		}
		if rawTS == "" {
			rawTS = strings.TrimSpace(r.Header.Get("X-Razorpay-Timestamp"))
		}
	} else {
		rawTS = strings.TrimSpace(r.Header.Get("X-Webhook-Timestamp"))
	}
	if rawTS == "" {
		return fmt.Errorf("verify: missing webhook timestamp for %s", psp)
	}
	nowFn := h.nowFunc
	if nowFn == nil {
		nowFn = time.Now
	}
	return validateUnixReplayWindow(rawTS, h.replayTolerance, nowFn())
}

func isProducerUnconfigured(p KafkaProducer) bool {
	if _, ok := p.(UnconfiguredKafkaProducer); ok {
		return true
	}
	if _, ok := p.(*UnconfiguredKafkaProducer); ok {
		return true
	}
	return false
}

func (h *WebhookHandler) loggerWithTrace(ctx context.Context) *slog.Logger {
	span := trace.SpanFromContext(ctx)
	sc := span.SpanContext()
	if !sc.IsValid() {
		return h.logger
	}
	return h.logger.With("trace_id", sc.TraceID().String(), "span_id", sc.SpanID().String())
}

func (h *WebhookHandler) writeJSON(w http.ResponseWriter, status int, payload interface{}) {
	w.Header().Set("Content-Type", "application/json")
	w.WriteHeader(status)
	enc := json.NewEncoder(w)
	enc.SetEscapeHTML(false)
	_ = enc.Encode(payload)
}

func (h *WebhookHandler) writeError(w http.ResponseWriter, status int, message string) {
	h.writeJSON(w, status, map[string]string{"error": message})
}
