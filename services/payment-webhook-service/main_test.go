package main

import (
	"crypto/hmac"
	"crypto/sha256"
	"encoding/hex"
	"fmt"
	"testing"
	"time"
)

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
