package handler

import (
	"bytes"
	"context"
	"crypto/hmac"
	"crypto/sha256"
	"encoding/hex"
	"fmt"
	"io"
	"log/slog"
	"net/http"
	"net/http/httptest"
	"strconv"
	"testing"
	"time"

	"github.com/prometheus/client_golang/prometheus"
)

func TestSignatureVerifiers_KeyRotationAndReplayWindow(t *testing.T) {
	t.Run("stripe rotated secrets accept both active and previous keys", func(t *testing.T) {
		v, err := NewStripeVerifierWithRotation([]string{"whsec_primary", "whsec_rotated"}, 60*time.Second)
		if err != nil {
			t.Fatalf("NewStripeVerifierWithRotation: %v", err)
		}
		payload := []byte(`{"id":"evt_rot","type":"payment_intent.succeeded"}`)
		ts := time.Now().Unix()

		for _, key := range []string{"whsec_primary", "whsec_rotated"} {
			mac := hmac.New(sha256.New, []byte(key))
			mac.Write(fmt.Appendf(nil, "%d.", ts))
			mac.Write(payload)
			hdr := fmt.Sprintf("t=%d,v1=%s", ts, hex.EncodeToString(mac.Sum(nil)))
			if err := v.Verify(payload, hdr); err != nil {
				t.Fatalf("expected rotated key %q to verify, got %v", key, err)
			}
		}

		if err := v.Verify(payload, fmt.Sprintf("t=0,v1=%s", "deadbeef")); err == nil {
			t.Fatal("expected zero timestamp to fail")
		}
	})

	t.Run("phonepe rotated salts match matching index only", func(t *testing.T) {
		v, err := NewPhonePeVerifierWithRotation([]PhonePeSaltKey{
			{Key: "salt_v1", Index: "1"},
			{Key: "salt_v2", Index: "2"},
		})
		if err != nil {
			t.Fatalf("NewPhonePeVerifierWithRotation: %v", err)
		}
		payload := []byte(`{"transactionId":"txn_rot","amount":500}`)
		for _, tc := range []struct {
			key, idx string
			wantOK   bool
		}{
			{"salt_v1", "1", true},
			{"salt_v2", "2", true},
			{"salt_v1", "2", false},
			{"salt_v2", "3", false},
		} {
			h := sha256.New()
			h.Write(payload)
			h.Write([]byte(tc.key))
			h.Write([]byte(tc.idx))
			sig := hex.EncodeToString(h.Sum(nil)) + "###" + tc.idx
			err := v.Verify(payload, sig)
			if (err == nil) != tc.wantOK {
				t.Fatalf("key=%s idx=%s got err=%v wantOK=%v", tc.key, tc.idx, err, tc.wantOK)
			}
		}
	})
}

func TestWebhookHandler_EnforcesReplayWindowHeaderAndRazorpayCreatedAt(t *testing.T) {
	secret := "rzp_replay_secret"
	v, _ := NewRazorpayVerifier(secret)
	dedupe := NewInMemoryIdempotencyStore(time.Minute, 100, time.Minute)
	defer dedupe.Close()

	h, err := NewWebhookHandler(
		map[PSPType]SignatureVerifier{Razorpay: v},
		dedupe,
		testNoopProducer{},
		"payment.webhooks",
		slog.New(slog.NewTextHandler(io.Discard, nil)),
		NewWebhookMetrics(prometheus.NewRegistry()),
		WithReplayTolerance(60*time.Second),
	)
	if err != nil {
		t.Fatalf("NewWebhookHandler: %v", err)
	}

	staleTS := time.Now().Add(-2 * time.Minute).Unix()
	stalePayload := []byte(fmt.Sprintf(`{"event":"payment.captured","created_at":%d,"payload":{"payment":{"entity":{"id":"pay_old"}}}}`, staleTS))
	mac := hmac.New(sha256.New, []byte(secret))
	mac.Write(stalePayload)
	req := httptest.NewRequest(http.MethodPost, "/webhooks/razorpay", bytes.NewReader(stalePayload))
	req.Header.Set("X-Razorpay-Signature", hex.EncodeToString(mac.Sum(nil)))
	rec := httptest.NewRecorder()
	h.HandleRazorpay(rec, req)
	if rec.Code != http.StatusUnauthorized {
		t.Fatalf("expected 401 on stale Razorpay created_at, got %d", rec.Code)
	}

	freshPayload := []byte(`{"event":"payment.captured","payload":{"payment":{"entity":{"id":"pay_fresh"}}}}`)
	mac2 := hmac.New(sha256.New, []byte(secret))
	mac2.Write(freshPayload)
	req2 := httptest.NewRequest(http.MethodPost, "/webhooks/razorpay", bytes.NewReader(freshPayload))
	req2.Header.Set("X-Razorpay-Signature", hex.EncodeToString(mac2.Sum(nil)))
	req2.Header.Set("X-Webhook-Timestamp", strconv.FormatInt(time.Now().Unix(), 10))
	rec2 := httptest.NewRecorder()
	h.HandleRazorpay(rec2, req2)
	if rec2.Code != http.StatusAccepted {
		t.Fatalf("expected 202 on fresh X-Webhook-Timestamp, got %d (%s)", rec2.Code, rec2.Body.String())
	}

	spoofedReq := httptest.NewRequest(http.MethodPost, "/webhooks/razorpay", bytes.NewReader(stalePayload))
	spoofedReq.Header.Set("X-Razorpay-Signature", hex.EncodeToString(mac.Sum(nil)))
	spoofedReq.Header.Set("X-Webhook-Timestamp", strconv.FormatInt(time.Now().Unix(), 10))
	spoofedReq.Header.Set("X-Razorpay-Timestamp", strconv.FormatInt(time.Now().Unix(), 10))
	spoofedRec := httptest.NewRecorder()
	h.HandleRazorpay(spoofedRec, spoofedReq)
	if spoofedRec.Code != http.StatusUnauthorized {
		t.Fatalf("expected 401 when expired Razorpay created_at is accompanied by spoofed fresh headers, got %d", spoofedRec.Code)
	}

	missingTSReq := httptest.NewRequest(http.MethodPost, "/webhooks/razorpay", bytes.NewReader(freshPayload))
	missingTSReq.Header.Set("X-Razorpay-Signature", hex.EncodeToString(mac2.Sum(nil)))
	missingTSRec := httptest.NewRecorder()
	h.HandleRazorpay(missingTSRec, missingTSReq)
	if missingTSRec.Code != http.StatusUnauthorized {
		t.Fatalf("expected 401 when Razorpay payload lacks both created_at and timestamp header under replayTolerance, got %d", missingTSRec.Code)
	}
}

func TestWebhookHandler_RejectsMissingPhonePeTimestampAndStripeDedup(t *testing.T) {
	ppVerifier, _ := NewPhonePeVerifier("pp_salt", "1")
	stripeVerifier, _ := NewStripeVerifier("whsec_stripe_dedup", 60*time.Second)
	dedupe := NewInMemoryIdempotencyStore(time.Minute, 100, time.Minute)
	defer dedupe.Close()

	h, err := NewWebhookHandler(
		map[PSPType]SignatureVerifier{
			PhonePe: ppVerifier,
			Stripe:  stripeVerifier,
		},
		dedupe,
		testNoopProducer{},
		"payment.webhooks",
		slog.New(slog.NewTextHandler(io.Discard, nil)),
		NewWebhookMetrics(prometheus.NewRegistry()),
		WithReplayTolerance(60*time.Second),
	)
	if err != nil {
		t.Fatalf("NewWebhookHandler: %v", err)
	}

	ppPayload := []byte(`{"transactionId":"txn_pp_missing_ts","event":"PAYMENT_SUCCESS","amount":500,"state":"COMPLETED"}`)
	ppSum := sha256.New()
	ppSum.Write(ppPayload)
	ppSum.Write([]byte("pp_salt1"))
	ppSig := hex.EncodeToString(ppSum.Sum(nil)) + "###1"

	ppMissingReq := httptest.NewRequest(http.MethodPost, "/webhooks/phonepe", bytes.NewReader(ppPayload))
	ppMissingReq.Header.Set("X-Verify", ppSig)
	ppMissingRec := httptest.NewRecorder()
	h.HandlePhonePe(ppMissingRec, ppMissingReq)
	if ppMissingRec.Code != http.StatusUnauthorized {
		t.Fatalf("expected 401 on PhonePe missing timestamp under replayTolerance, got %d", ppMissingRec.Code)
	}

	ppFreshReq := httptest.NewRequest(http.MethodPost, "/webhooks/phonepe", bytes.NewReader(ppPayload))
	ppFreshReq.Header.Set("X-Verify", ppSig)
	ppFreshReq.Header.Set("X-Webhook-Timestamp", strconv.FormatInt(time.Now().Unix(), 10))
	ppFreshRec := httptest.NewRecorder()
	h.HandlePhonePe(ppFreshRec, ppFreshReq)
	if ppFreshRec.Code != http.StatusAccepted {
		t.Fatalf("expected 202 on PhonePe with fresh timestamp header, got %d (%s)", ppFreshRec.Code, ppFreshRec.Body.String())
	}

	stripePayload := []byte(`{"id":"evt_stripe_dedup","type":"payment_intent.succeeded"}`)
	ts := time.Now().Unix()
	mac := hmac.New(sha256.New, []byte("whsec_stripe_dedup"))
	mac.Write(fmt.Appendf(nil, "%d.", ts))
	mac.Write(stripePayload)
	stripeReq := httptest.NewRequest(http.MethodPost, "/webhooks/stripe", bytes.NewReader(stripePayload))
	stripeReq.Header.Set("Stripe-Signature", fmt.Sprintf("t=%d,v1=%s", ts, hex.EncodeToString(mac.Sum(nil))))
	stripeRec := httptest.NewRecorder()
	h.HandleStripe(stripeRec, stripeReq)
	if stripeRec.Code != http.StatusAccepted {
		t.Fatalf("expected 202 on valid Stripe signature without X-Webhook-Timestamp, got %d (%s)", stripeRec.Code, stripeRec.Body.String())
	}
}

type testNoopProducer struct{}

func (testNoopProducer) Publish(_ context.Context, _ string, _, _ []byte) error { return nil }
