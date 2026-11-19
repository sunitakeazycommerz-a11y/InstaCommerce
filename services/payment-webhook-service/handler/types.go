// Package handler provides production webhook handling for payment service providers.
package handler

import (
	"encoding/json"
	"errors"
	"strings"
	"time"

	"go.opentelemetry.io/otel/trace"
)

var (
	// ErrMissingEventID indicates the PSP payload does not contain an event identifier.
	ErrMissingEventID = errors.New("handler: event ID is required")
	// ErrProducerNotConfigured indicates Kafka publishing has not been configured.
	ErrProducerNotConfigured = errors.New("handler: kafka producer not configured")
)

const (
	SchemaVersion         = 2
	maxWebhookBody        = 1 << 20
	defaultPublishTimeout = 5 * time.Second
)

// PSPType identifies a payment service provider.
type PSPType string

const (
	Stripe   PSPType = "stripe"
	Razorpay PSPType = "razorpay"
	PhonePe  PSPType = "phonepe"
)

// StatusResponse is the JSON response returned by webhook ingestion endpoints.
type StatusResponse struct {
	Status    string `json:"status"`
	EventID   string `json:"event_id,omitempty"`
	Duplicate bool   `json:"duplicate,omitempty"`
}

// WebhookEvent is the canonical, PSP-agnostic representation published to Kafka.
type WebhookEvent struct {
	ID            string          `json:"id"`
	PSP           PSPType         `json:"psp"`
	EventType     string          `json:"event_type"`
	PaymentID     string          `json:"payment_id"`
	OrderID       string          `json:"order_id"`
	AmountCents   int64           `json:"amount_cents"`
	Currency      string          `json:"currency"`
	Status        string          `json:"status"`
	ReceivedAt    time.Time       `json:"received_at"`
	Version       int             `json:"schema_version,omitempty"`
	RawPSPPayload json.RawMessage `json:"raw_psp_payload,omitempty"`
	RawPayload    []byte          `json:"-"`
}

// WebhookOption configures optional settings on WebhookHandler.
type WebhookOption func(*WebhookHandler)

func WithMaxBodyBytes(limit int64) WebhookOption {
	return func(h *WebhookHandler) {
		if limit > 0 {
			h.maxBodyBytes = limit
		}
	}
}

func WithPublishTimeout(timeout time.Duration) WebhookOption {
	return func(h *WebhookHandler) {
		if timeout > 0 {
			h.publishTimeout = timeout
		}
	}
}

func WithStripeSignatureHeader(header string) WebhookOption {
	return func(h *WebhookHandler) {
		if strings.TrimSpace(header) != "" {
			h.stripeSigHeader = strings.TrimSpace(header)
		}
	}
}

func WithReplayTolerance(tolerance time.Duration) WebhookOption {
	return func(h *WebhookHandler) {
		if tolerance >= 0 {
			h.replayTolerance = tolerance
		}
	}
}

func WithTracer(tracer trace.Tracer) WebhookOption {
	return func(h *WebhookHandler) {
		if tracer != nil {
			h.tracer = tracer
		}
	}
}
