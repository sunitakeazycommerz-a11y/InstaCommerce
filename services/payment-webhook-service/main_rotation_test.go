package main

import (
	"bytes"
	"context"
	"crypto/hmac"
	"crypto/sha256"
	"encoding/hex"
	"errors"
	"io"
	"log/slog"
	"net/http"
	"net/http/httptest"
	"testing"
	"time"

	"github.com/instacommerce/payment-webhook-service/handler"
	"github.com/prometheus/client_golang/prometheus"
)

func TestWebhookHandler_DedupeErrorAndPublishRollback(t *testing.T) {
	secret := "whsec_test_secret_12345"

	t.Run("returns 503 when dedupe store errors", func(t *testing.T) {
		writer := &stubMessageWriter{}
		verifier, _ := handler.NewStripeVerifier(secret, 30*time.Second)
		h, err := handler.NewWebhookHandler(
			map[handler.PSPType]handler.SignatureVerifier{handler.Stripe: verifier},
			&failingDedupeStore{err: errors.New("redis down")},
			handler.NewKafkaWriterProducer(writer),
			"payment.webhooks",
			slog.New(slog.NewTextHandler(io.Discard, nil)),
			handler.NewWebhookMetrics(prometheus.NewRegistry()),
		)
		if err != nil {
			t.Fatalf("NewWebhookHandler: %v", err)
		}
		rec := sendSignedWebhook(t, http.HandlerFunc(h.HandleStripe), secret, time.Now().Unix(), []byte(`{"id":"evt_dedup_err","type":"payment_intent.succeeded"}`))
		if rec.Code != http.StatusServiceUnavailable {
			t.Fatalf("expected 503, got %d", rec.Code)
		}
	})

	t.Run("rolls back dedupe on publish error so retry succeeds", func(t *testing.T) {
		writer := &stubMessageWriter{err: errors.New("broker down")}
		h, dedupe := newTestWebhookHandler(t, writer, secret)
		mux := newHTTPMux(h, newHealthHandler(true, true, nil))
		rawPayload := []byte(`{"id":"evt_retry","type":"payment_intent.succeeded"}`)

		failed := sendSignedWebhook(t, mux, secret, time.Now().Unix(), rawPayload)
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

		retried := sendSignedWebhook(t, mux, secret, time.Now().Unix(), rawPayload)
		if retried.Code != http.StatusAccepted {
			t.Fatalf("expected retry 202, got %d", retried.Code)
		}
		if len(writer.messages) != 1 {
			t.Errorf("expected 1 message after retry, got %d", len(writer.messages))
		}
	})
}

func TestLoadConfig_DefaultTolerance(t *testing.T) {
	t.Setenv("PORT", "8106")
	t.Setenv("WEBHOOK_SECRET", "test_secret")
	t.Setenv("KAFKA_BROKERS", "localhost:9092")

	cfg := loadConfig()
	if cfg.SignatureTolerance != 300*time.Second {
		t.Errorf("expected default SignatureTolerance to be 300s, got %v", cfg.SignatureTolerance)
	}
}

func TestConfigKeyRotationAndHealthReadiness(t *testing.T) {
	t.Setenv("WEBHOOK_SECRETS", "whsec_new,whsec_old")
	t.Setenv("RAZORPAY_WEBHOOK_SECRETS", "rzp_new,rzp_old")
	t.Setenv("PHONEPE_SALT_KEYS", "salt_new:2,salt_old:1")
	t.Setenv("WEBHOOK_TOLERANCE_SECONDS", "120")

	cfg := loadConfig()
	if !hasAnyVerifierSecret(cfg) {
		t.Fatal("expected rotated secrets to satisfy verifier readiness")
	}

	verifiers := buildVerifiers(cfg)
	rzpPayload := []byte(`{"event":"payment.captured","payload":{"payment":{"entity":{"id":"pay_rot_1","order_id":"ord_1","amount":100,"currency":"INR","status":"captured"}}}}`)
	mac := hmac.New(sha256.New, []byte("rzp_old"))
	mac.Write(rzpPayload)
	sig := hex.EncodeToString(mac.Sum(nil))

	dedupe := handler.NewInMemoryIdempotencyStore(time.Minute, 100, time.Minute)
	defer dedupe.Close()
	writer := &stubMessageWriter{}
	h, err := handler.NewWebhookHandler(
		verifiers,
		dedupe,
		handler.NewKafkaWriterProducer(writer),
		"payment.webhooks",
		slog.New(slog.NewTextHandler(io.Discard, nil)),
		handler.NewWebhookMetrics(prometheus.NewRegistry()),
		handler.WithReplayTolerance(cfg.SignatureTolerance),
	)
	if err != nil {
		t.Fatalf("NewWebhookHandler: %v", err)
	}

	req := httptest.NewRequest(http.MethodPost, "/webhooks/razorpay", bytes.NewReader(rzpPayload))
	req.Header.Set("X-Razorpay-Signature", sig)
	rec := httptest.NewRecorder()
	h.HandleRazorpay(rec, req)
	if rec.Code != http.StatusAccepted {
		t.Fatalf("expected rotated Razorpay old secret to verify cleanly, got %d (%s)", rec.Code, rec.Body.String())
	}
}
