package main

import (
	"bytes"
	"context"
	"crypto/hmac"
	"crypto/sha256"
	"encoding/hex"
	"encoding/json"
	"fmt"
	"io"
	"log/slog"
	"net/http"
	"net/http/httptest"
	"strings"
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

type failingDedupeStore struct {
	err error
}

func (f *failingDedupeStore) Check(context.Context, string) (bool, error) { return false, f.err }
func (f *failingDedupeStore) CheckAndMark(context.Context, string) (bool, error) {
	return false, f.err
}
func (f *failingDedupeStore) Remove(context.Context, string) error { return nil }
func (f *failingDedupeStore) Close() error                         { return nil }

func newTestWebhookHandler(t *testing.T, writer handler.MessageWriter, secret string, maxBodyBytes ...int64) (*handler.WebhookHandler, *handler.InMemoryIdempotencyStore) {
	t.Helper()
	dedupe := handler.NewInMemoryIdempotencyStore(time.Minute, 1000, time.Minute)
	t.Cleanup(func() { _ = dedupe.Close() })

	bodyLimit := int64(1 << 20)
	if len(maxBodyBytes) > 0 && maxBodyBytes[0] > 0 {
		bodyLimit = maxBodyBytes[0]
	}

	cfg := Config{
		WebhookSecret:      secret,
		RazorpaySecret:     "rzp_test_secret",
		PhonePeSaltKey:     "phonepe_salt",
		PhonePeSaltIndex:   "1",
		SignatureHeader:    "Stripe-Signature",
		SignatureTolerance: 30 * time.Second,
		MaxBodyBytes:       bodyLimit,
		KafkaTopic:         "payment.webhooks",
		PublishTimeout:     2 * time.Second,
	}

	var producer handler.KafkaProducer = handler.UnconfiguredKafkaProducer{}
	if writer != nil {
		producer = handler.NewKafkaWriterProducer(writer)
	}

	h, err := handler.NewWebhookHandler(
		buildVerifiers(cfg),
		dedupe,
		producer,
		cfg.KafkaTopic,
		slog.New(slog.NewTextHandler(io.Discard, nil)),
		handler.NewWebhookMetrics(prometheus.NewRegistry()),
		handler.WithMaxBodyBytes(cfg.MaxBodyBytes),
		handler.WithPublishTimeout(cfg.PublishTimeout),
		handler.WithStripeSignatureHeader(cfg.SignatureHeader),
		handler.WithTracer(otel.Tracer("test")),
	)
	if err != nil {
		t.Fatalf("NewWebhookHandler failed: %v", err)
	}
	return h, dedupe
}

func sendSignedWebhook(t *testing.T, h http.Handler, secret string, ts int64, payload []byte) *httptest.ResponseRecorder {
	t.Helper()
	req := httptest.NewRequest(http.MethodPost, "/payments/webhook", bytes.NewReader(payload))
	req.Header.Set("Stripe-Signature", generateStripeHeader(secret, ts, payload))
	rec := httptest.NewRecorder()
	h.ServeHTTP(rec, req)
	return rec
}

func generateStripeHeader(secret string, timestamp int64, payload []byte) string {
	mac := hmac.New(sha256.New, []byte(secret))
	_, _ = mac.Write(fmt.Appendf(nil, "%d", timestamp))
	_, _ = mac.Write([]byte("."))
	_, _ = mac.Write(payload)
	sig := hex.EncodeToString(mac.Sum(nil))
	return fmt.Sprintf("t=%d,v1=%s", timestamp, sig)
}

func TestVerifySignature(t *testing.T) {
	secret := "whsec_test_secret_12345"
	payload := []byte(`{"id":"evt_123","type":"payment_intent.succeeded"}`)
	verifier, err := handler.NewStripeVerifier(secret, 30*time.Second)
	if err != nil {
		t.Fatalf("NewStripeVerifier failed: %v", err)
	}

	t.Run("valid signature within tolerance", func(t *testing.T) {
		ts := time.Now().Unix() - 10 // 10s old
		header := generateStripeHeader(secret, ts, payload)

		if err := verifier.Verify(payload, header); err != nil {
			t.Errorf("expected signature to be valid within 30s tolerance, got %v", err)
		}
	})

	t.Run("expired signature exceeds tolerance", func(t *testing.T) {
		ts := time.Now().Unix() - 35 // 35s old, beyond 30s tolerance
		header := generateStripeHeader(secret, ts, payload)

		if err := verifier.Verify(payload, header); err == nil {
			t.Errorf("expected signature to be rejected when older than 30s tolerance")
		}
	})

	t.Run("future timestamp exceeds tolerance", func(t *testing.T) {
		ts := time.Now().Unix() + 35 // 35s in future, beyond 30s tolerance
		header := generateStripeHeader(secret, ts, payload)

		if err := verifier.Verify(payload, header); err == nil {
			t.Errorf("expected signature to be rejected when future diff exceeds tolerance")
		}
	})

	t.Run("tampered payload fails verification", func(t *testing.T) {
		ts := time.Now().Unix()
		header := generateStripeHeader(secret, ts, payload)
		tampered := []byte(`{"id":"evt_123","type":"payment_intent.failed"}`)

		if err := verifier.Verify(tampered, header); err == nil {
			t.Errorf("expected signature to fail on tampered payload")
		}
	})

	t.Run("wrong secret fails verification", func(t *testing.T) {
		ts := time.Now().Unix()
		header := generateStripeHeader("wrong_secret", ts, payload)

		if err := verifier.Verify(payload, header); err == nil {
			t.Errorf("expected signature to fail with incorrect secret")
		}
	})
}

func TestWebhookHandler_PublishesWebhookEventEnvelope(t *testing.T) {
	secret := "whsec_test_secret_12345"
	writer := &stubMessageWriter{}
	h, _ := newTestWebhookHandler(t, writer, secret)
	mux := newHTTPMux(h, newHealthHandler(true, true, nil))

	rawPayload := []byte(`{"id":"evt_envelope_1","type":"payment_intent.succeeded","data":{"object":{"id":"pi_123","amount":2500,"currency":"usd","status":"succeeded","metadata":{"order_id":"ord_456"}}}}`)
	rec := sendSignedWebhook(t, mux, secret, time.Now().Unix(), rawPayload)

	if rec.Code != http.StatusAccepted {
		t.Fatalf("expected status 202 Accepted, got %d: %s", rec.Code, rec.Body.String())
	}

	if len(writer.messages) != 1 {
		t.Fatalf("expected 1 Kafka message, got %d", len(writer.messages))
	}

	msg := writer.messages[0]
	if string(msg.Key) != "pi_123" {
		t.Errorf("expected Kafka key pi_123, got %q", string(msg.Key))
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

func TestWebhookHandler_RoutesAndSecurity(t *testing.T) {
	secret := "whsec_test_secret_12345"
	writer := &stubMessageWriter{}
	h, _ := newTestWebhookHandler(t, writer, secret)
	mux := newHTTPMux(h, newHealthHandler(true, true, nil))

	for _, ep := range []string{"/health", "/health/live", "/health/ready", "/ready", "/metrics"} {
		req := httptest.NewRequest(http.MethodGet, ep, nil)
		rec := httptest.NewRecorder()
		mux.ServeHTTP(rec, req)
		if rec.Code != http.StatusOK {
			t.Errorf("GET %s expected 200, got %d", ep, rec.Code)
		}
	}

	// /webhooks/stripe
	stripePayload := []byte(`{"id":"evt_route_stripe","type":"payment_intent.succeeded","data":{"object":{"id":"pi_s1"}}}`)
	sReq := httptest.NewRequest(http.MethodPost, "/webhooks/stripe", bytes.NewReader(stripePayload))
	sReq.Header.Set("Stripe-Signature", generateStripeHeader(secret, time.Now().Unix(), stripePayload))
	sRec := httptest.NewRecorder()
	mux.ServeHTTP(sRec, sReq)
	if sRec.Code != http.StatusAccepted {
		t.Fatalf("/webhooks/stripe expected 202, got %d: %s", sRec.Code, sRec.Body.String())
	}

	// /webhooks/razorpay
	rzpPayload := []byte(`{"event":"payment.captured","payload":{"payment":{"entity":{"id":"pay_rzp_1","order_id":"ord_1","amount":1200,"currency":"INR","status":"captured"}}}}`)
	mac := hmac.New(sha256.New, []byte("rzp_test_secret"))
	mac.Write(rzpPayload)
	rReq := httptest.NewRequest(http.MethodPost, "/webhooks/razorpay", bytes.NewReader(rzpPayload))
	rReq.Header.Set("X-Razorpay-Signature", hex.EncodeToString(mac.Sum(nil)))
	rRec := httptest.NewRecorder()
	mux.ServeHTTP(rRec, rReq)
	if rRec.Code != http.StatusAccepted {
		t.Fatalf("/webhooks/razorpay expected 202, got %d: %s", rRec.Code, rRec.Body.String())
	}

	// /webhooks/phonepe
	ppPayload := []byte(`{"transactionId":"txn_pp_1","event":"PAYMENT_SUCCESS","merchantOrderId":"ord_2","amount":1500,"state":"COMPLETED"}`)
	sum := sha256.New()
	sum.Write(ppPayload)
	sum.Write([]byte("phonepe_salt1"))
	pReq := httptest.NewRequest(http.MethodPost, "/webhooks/phonepe", bytes.NewReader(ppPayload))
	pReq.Header.Set("X-Verify", hex.EncodeToString(sum.Sum(nil))+"###1")
	pRec := httptest.NewRecorder()
	mux.ServeHTTP(pRec, pReq)
	if pRec.Code != http.StatusAccepted {
		t.Fatalf("/webhooks/phonepe expected 202, got %d: %s", pRec.Code, pRec.Body.String())
	}

	// Oversized body -> 413 Payload Too Large
	smallHandler, _ := newTestWebhookHandler(t, writer, secret, 32)
	smallMux := newHTTPMux(smallHandler, newHealthHandler(true, true, nil))
	bigReq := httptest.NewRequest(http.MethodPost, "/webhooks/stripe", strings.NewReader(`{"id":"`+strings.Repeat("x", 64)+`"}`))
	bigRec := httptest.NewRecorder()
	smallMux.ServeHTTP(bigRec, bigReq)
	if bigRec.Code != http.StatusRequestEntityTooLarge {
		t.Fatalf("expected 413 RequestEntityTooLarge, got %d", bigRec.Code)
	}
}

func TestWebhookHandler_GuardsAndFallbacks(t *testing.T) {
	secret := "whsec_test_secret_12345"

	t.Run("missing event type defaults to unknown inside envelope", func(t *testing.T) {
		writer := &stubMessageWriter{}
		h, _ := newTestWebhookHandler(t, writer, secret)
		mux := newHTTPMux(h, newHealthHandler(true, true, nil))
		rawPayload := []byte(`{"id":"evt_no_type","data":{"object":{"id":"pi_no_type"}}}`)
		rec := sendSignedWebhook(t, mux, secret, time.Now().Unix(), rawPayload)

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
		mux := newHTTPMux(h, newHealthHandler(true, true, nil))
		rawPayload := []byte(`{"id":"evt_stale","type":"payment_intent.succeeded"}`)
		rec := sendSignedWebhook(t, mux, secret, time.Now().Unix()-35, rawPayload)

		if rec.Code != http.StatusUnauthorized {
			t.Fatalf("expected 401 Unauthorized, got %d", rec.Code)
		}
		if len(writer.messages) != 0 {
			t.Errorf("expected 0 published messages, got %d", len(writer.messages))
		}
	})

	t.Run("rejects missing event id", func(t *testing.T) {
		writer := &stubMessageWriter{}
		h, _ := newTestWebhookHandler(t, writer, secret)
		mux := newHTTPMux(h, newHealthHandler(true, true, nil))
		rawPayload := []byte(`{"type":"payment_intent.succeeded"}`)
		rec := sendSignedWebhook(t, mux, secret, time.Now().Unix(), rawPayload)

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
		mux := newHTTPMux(h, newHealthHandler(true, true, nil))
		rawPayload := []byte(`{"id":"evt_dup","type":"payment_intent.succeeded"}`)

		first := sendSignedWebhook(t, mux, secret, time.Now().Unix(), rawPayload)
		if first.Code != http.StatusAccepted {
			t.Fatalf("expected first request 202, got %d", first.Code)
		}

		second := sendSignedWebhook(t, mux, secret, time.Now().Unix(), rawPayload)
		if second.Code != http.StatusOK {
			t.Fatalf("expected duplicate request 200, got %d", second.Code)
		}
		var resp handler.StatusResponse
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
}
