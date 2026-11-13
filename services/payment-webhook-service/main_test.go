package main

import (
	"bytes"
	"context"
	"crypto/hmac"
	"crypto/sha256"
	"encoding/hex"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"log/slog"
	"net/http"
	"net/http/httptest"
	"sync"
	"testing"
	"time"

	"github.com/instacommerce/payment-webhook-service/handler"
	"github.com/prometheus/client_golang/prometheus"
	"github.com/segmentio/kafka-go"
	"go.opentelemetry.io/otel"
)

type stubMessageWriter struct {
	mu       sync.Mutex
	messages []kafka.Message
	err      error
}

func (s *stubMessageWriter) WriteMessages(_ context.Context, msgs ...kafka.Message) error {
	s.mu.Lock()
	defer s.mu.Unlock()
	if s.err != nil {
		return s.err
	}
	s.messages = append(s.messages, msgs...)
	return nil
}

func (s *stubMessageWriter) Close() error {
	return nil
}

func newTestWebhookHandler(t *testing.T, writer messageWriter, secret string) (*webhookHandler, *inMemoryDedupe) {
	t.Helper()
	dedupe := newInMemoryDedupe(time.Minute, time.Minute)
	t.Cleanup(func() { _ = dedupe.Close() })
	h := &webhookHandler{
		cfg: Config{
			WebhookSecret:        secret,
			SignatureHeader:      "Stripe-Signature",
			SignatureTolerance:   30 * time.Second,
			MaxBodyBytes:         1 << 20,
			KafkaTopic:           "payment.webhooks",
			RequireKafka:         true,
			RequireWebhookSecret: true,
		},
		logger:         slog.New(slog.NewTextHandler(io.Discard, nil)),
		metrics:        newMetricsWithRegisterer(prometheus.NewRegistry()),
		dedupe:         dedupe,
		writer:         writer,
		publishTimeout: 2 * time.Second,
		tracer:         otel.Tracer("test"),
	}
	return h, dedupe
}

func sendSignedWebhook(t *testing.T, h *webhookHandler, secret string, ts int64, payload []byte) *httptest.ResponseRecorder {
	t.Helper()
	req := httptest.NewRequest(http.MethodPost, "/payments/webhook", bytes.NewReader(payload))
	req.Header.Set("Stripe-Signature", generateStripeHeader(secret, ts, payload))
	rec := httptest.NewRecorder()
	h.ServeHTTP(rec, req)
	return rec
}

func generateStripeHeader(secret string, timestamp int64, payload []byte) string {
	mac := hmac.New(sha256.New, []byte(secret))
	_, _ = mac.Write([]byte(fmt.Sprintf("%d", timestamp)))
	_, _ = mac.Write([]byte("."))
	_, _ = mac.Write(payload)
	sig := hex.EncodeToString(mac.Sum(nil))
	return fmt.Sprintf("t=%d,v1=%s", timestamp, sig)
}

func TestVerifySignature(t *testing.T) {
	secret := "whsec_test_secret_12345"
	payload := []byte(`{"id":"evt_123","type":"payment_intent.succeeded"}`)
	tolerance := 30 * time.Second

	t.Run("valid signature within tolerance", func(t *testing.T) {
		ts := time.Now().Unix() - 10 // 10s old
		header := generateStripeHeader(secret, ts, payload)

		if !verifySignature(payload, header, secret, tolerance) {
			t.Errorf("expected signature to be valid within 30s tolerance")
		}
	})

	t.Run("expired signature exceeds tolerance", func(t *testing.T) {
		ts := time.Now().Unix() - 35 // 35s old, beyond 30s tolerance
		header := generateStripeHeader(secret, ts, payload)

		if verifySignature(payload, header, secret, tolerance) {
			t.Errorf("expected signature to be rejected when older than 30s tolerance")
		}
	})

	t.Run("future timestamp exceeds tolerance", func(t *testing.T) {
		ts := time.Now().Unix() + 35 // 35s in future, beyond 30s tolerance
		header := generateStripeHeader(secret, ts, payload)

		if verifySignature(payload, header, secret, tolerance) {
			t.Errorf("expected signature to be rejected when future diff exceeds tolerance")
		}
	})

	t.Run("tampered payload fails verification", func(t *testing.T) {
		ts := time.Now().Unix()
		header := generateStripeHeader(secret, ts, payload)
		tampered := []byte(`{"id":"evt_123","type":"payment_intent.failed"}`)

		if verifySignature(tampered, header, secret, tolerance) {
			t.Errorf("expected signature to fail on tampered payload")
		}
	})

	t.Run("wrong secret fails verification", func(t *testing.T) {
		ts := time.Now().Unix()
		header := generateStripeHeader("wrong_secret", ts, payload)

		if verifySignature(payload, header, secret, tolerance) {
			t.Errorf("expected signature to fail with incorrect secret")
		}
	})
}

func TestLoadConfig_DefaultTolerance(t *testing.T) {
	t.Setenv("PORT", "8106")
	t.Setenv("WEBHOOK_SECRET", "test_secret")
	t.Setenv("KAFKA_BROKERS", "localhost:9092")

	cfg := loadConfig()
	expectedTolerance := 30 * time.Second
	if cfg.SignatureTolerance != expectedTolerance {
		t.Errorf("expected default SignatureTolerance to be %v, got %v", expectedTolerance, cfg.SignatureTolerance)
	}
}

func TestWebhookHandler_PublishesWebhookEventEnvelope(t *testing.T) {
	secret := "whsec_test_secret_12345"
	writer := &stubMessageWriter{}
	h, _ := newTestWebhookHandler(t, writer, secret)

	rawPayload := []byte(`{"id":"evt_envelope_1","type":"payment_intent.succeeded","data":{"object":{"id":"pi_123","amount":2500,"currency":"usd","status":"succeeded","metadata":{"order_id":"ord_456"}}}}`)
	rec := sendSignedWebhook(t, h, secret, time.Now().Unix(), rawPayload)

	if rec.Code != http.StatusAccepted {
		t.Fatalf("expected status 202 Accepted, got %d: %s", rec.Code, rec.Body.String())
	}

	if len(writer.messages) != 1 {
		t.Fatalf("expected 1 Kafka message, got %d", len(writer.messages))
	}

	msg := writer.messages[0]
	if string(msg.Key) != "evt_envelope_1" {
		t.Errorf("expected Kafka key evt_envelope_1, got %q", string(msg.Key))
	}

	var envelope handler.WebhookEvent
	if err := json.Unmarshal(msg.Value, &envelope); err != nil {
		t.Fatalf("unmarshal published Kafka message: %v", err)
	}

	if envelope.ID != "evt_envelope_1" {
		t.Errorf("expected id evt_envelope_1, got %q", envelope.ID)
	}
	if envelope.PSP != handler.Stripe {
		t.Errorf("expected psp %q, got %q", handler.Stripe, envelope.PSP)
	}
	if envelope.EventType != "payment_intent.succeeded" {
		t.Errorf("expected event_type payment_intent.succeeded, got %q", envelope.EventType)
	}
	if envelope.PaymentID != "pi_123" {
		t.Errorf("expected payment_id pi_123, got %q", envelope.PaymentID)
	}
	if envelope.OrderID != "ord_456" {
		t.Errorf("expected order_id ord_456, got %q", envelope.OrderID)
	}
	if envelope.AmountCents != 2500 {
		t.Errorf("expected amount_cents 2500, got %d", envelope.AmountCents)
	}
	if envelope.Currency != "usd" {
		t.Errorf("expected currency usd, got %q", envelope.Currency)
	}
	if envelope.Status != "succeeded" {
		t.Errorf("expected status succeeded, got %q", envelope.Status)
	}
	if envelope.Version != handler.SchemaVersion {
		t.Errorf("expected schema_version %d, got %d", handler.SchemaVersion, envelope.Version)
	}
	if envelope.ReceivedAt.IsZero() {
		t.Errorf("expected non-zero received_at")
	}
	if string(envelope.RawPSPPayload) != string(rawPayload) {
		t.Errorf("expected raw_psp_payload %s, got %s", string(rawPayload), string(envelope.RawPSPPayload))
	}

	headerMap := make(map[string]string, len(msg.Headers))
	for _, hdr := range msg.Headers {
		headerMap[hdr.Key] = string(hdr.Value)
	}
	if headerMap["event_id"] != "evt_envelope_1" {
		t.Errorf("expected header event_id evt_envelope_1, got %q", headerMap["event_id"])
	}
	if headerMap["event_type"] != "payment_intent.succeeded" {
		t.Errorf("expected header event_type payment_intent.succeeded, got %q", headerMap["event_type"])
	}
}

func TestWebhookHandler_GuardsAndFallbacks(t *testing.T) {
	secret := "whsec_test_secret_12345"

	t.Run("missing event type defaults to unknown inside envelope", func(t *testing.T) {
		writer := &stubMessageWriter{}
		h, _ := newTestWebhookHandler(t, writer, secret)
		rawPayload := []byte(`{"id":"evt_no_type","data":{"object":{"id":"pi_no_type"}}}`)
		rec := sendSignedWebhook(t, h, secret, time.Now().Unix(), rawPayload)

		if rec.Code != http.StatusAccepted {
			t.Fatalf("expected 202, got %d", rec.Code)
		}
		if len(writer.messages) != 1 {
			t.Fatalf("expected 1 message, got %d", len(writer.messages))
		}
		var envelope handler.WebhookEvent
		if err := json.Unmarshal(writer.messages[0].Value, &envelope); err != nil {
			t.Fatalf("unmarshal envelope: %v", err)
		}
		if envelope.EventType != "unknown" {
			t.Errorf("expected event_type unknown, got %q", envelope.EventType)
		}
		if envelope.PSP != handler.Stripe || envelope.Version != handler.SchemaVersion || len(envelope.RawPSPPayload) == 0 {
			t.Errorf("expected full envelope fields, got psp=%q schema_version=%d raw_len=%d", envelope.PSP, envelope.Version, len(envelope.RawPSPPayload))
		}
	})

	t.Run("rejects replay beyond 30s tolerance", func(t *testing.T) {
		writer := &stubMessageWriter{}
		h, _ := newTestWebhookHandler(t, writer, secret)
		rawPayload := []byte(`{"id":"evt_stale","type":"payment_intent.succeeded"}`)
		rec := sendSignedWebhook(t, h, secret, time.Now().Unix()-35, rawPayload)

		if rec.Code != http.StatusBadRequest {
			t.Fatalf("expected 400 Bad Request, got %d", rec.Code)
		}
		if len(writer.messages) != 0 {
			t.Errorf("expected 0 published messages, got %d", len(writer.messages))
		}
	})

	t.Run("rejects missing event id", func(t *testing.T) {
		writer := &stubMessageWriter{}
		h, _ := newTestWebhookHandler(t, writer, secret)
		rawPayload := []byte(`{"type":"payment_intent.succeeded"}`)
		rec := sendSignedWebhook(t, h, secret, time.Now().Unix(), rawPayload)

		if rec.Code != http.StatusBadRequest {
			t.Fatalf("expected 400 Bad Request, got %d", rec.Code)
		}
		if len(writer.messages) != 0 {
			t.Errorf("expected 0 published messages, got %d", len(writer.messages))
		}
	})

	t.Run("deduplicates repeated event id", func(t *testing.T) {
		writer := &stubMessageWriter{}
		h, _ := newTestWebhookHandler(t, writer, secret)
		rawPayload := []byte(`{"id":"evt_dup","type":"payment_intent.succeeded"}`)

		first := sendSignedWebhook(t, h, secret, time.Now().Unix(), rawPayload)
		if first.Code != http.StatusAccepted {
			t.Fatalf("expected first request 202, got %d", first.Code)
		}

		second := sendSignedWebhook(t, h, secret, time.Now().Unix(), rawPayload)
		if second.Code != http.StatusOK {
			t.Fatalf("expected duplicate request 200, got %d", second.Code)
		}
		var resp statusResponse
		if err := json.Unmarshal(second.Body.Bytes(), &resp); err != nil {
			t.Fatalf("unmarshal duplicate response: %v", err)
		}
		if !resp.Duplicate || resp.Status != "duplicate" {
			t.Errorf("expected duplicate response, got %+v", resp)
		}
		if len(writer.messages) != 1 {
			t.Errorf("expected only 1 published message, got %d", len(writer.messages))
		}
	})

	t.Run("rolls back dedupe on publish error so retry succeeds", func(t *testing.T) {
		writer := &stubMessageWriter{err: errors.New("broker down")}
		h, dedupe := newTestWebhookHandler(t, writer, secret)
		rawPayload := []byte(`{"id":"evt_retry","type":"payment_intent.succeeded"}`)

		failed := sendSignedWebhook(t, h, secret, time.Now().Unix(), rawPayload)
		if failed.Code != http.StatusServiceUnavailable {
			t.Fatalf("expected 503, got %d", failed.Code)
		}
		present, err := dedupe.Check(context.Background(), "evt_retry")
		if err != nil || present {
			t.Fatalf("expected dedupe entry removed on publish error, present=%v err=%v", present, err)
		}

		writer.mu.Lock()
		writer.err = nil
		writer.mu.Unlock()

		retried := sendSignedWebhook(t, h, secret, time.Now().Unix(), rawPayload)
		if retried.Code != http.StatusAccepted {
			t.Fatalf("expected retry 202, got %d", retried.Code)
		}
		if len(writer.messages) != 1 {
			t.Errorf("expected 1 message after retry, got %d", len(writer.messages))
		}
	})
}
