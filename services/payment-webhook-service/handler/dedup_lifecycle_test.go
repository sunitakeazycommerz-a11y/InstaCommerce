package handler

import (
	"bytes"
	"context"
	"crypto/hmac"
	"crypto/sha256"
	"encoding/hex"
	"io"
	"log/slog"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"
	"time"

	"github.com/prometheus/client_golang/prometheus"
	"github.com/segmentio/kafka-go"
)

type captureWebhookWriter struct {
	messages []kafka.Message
}

func (c *captureWebhookWriter) WriteMessages(_ context.Context, msgs ...kafka.Message) error {
	c.messages = append(c.messages, msgs...)
	return nil
}

func (c *captureWebhookWriter) Close() error { return nil }

func signRazorpayPayload(secret string, payload []byte) string {
	mac := hmac.New(sha256.New, []byte(secret))
	mac.Write(payload)
	return hex.EncodeToString(mac.Sum(nil))
}

func signPhonePePayload(saltKey, saltIndex string, payload []byte) string {
	sum := sha256.New()
	sum.Write(payload)
	sum.Write([]byte(saltKey))
	sum.Write([]byte(saltIndex))
	return hex.EncodeToString(sum.Sum(nil)) + "###" + saltIndex
}

func TestRazorpayAndPhonePeLifecycleEventsNotDroppedBySamePaymentID(t *testing.T) {
	writer := &captureWebhookWriter{}
	store := NewInMemoryIdempotencyStore(10*time.Minute, 1000, time.Hour)
	defer func() { _ = store.Close() }()

	metrics := NewWebhookMetrics(prometheus.NewRegistry())

	const (
		rzpSecret   = "rzp_lifecycle_secret"
		ppSaltKey   = "pp_lifecycle_salt"
		ppSaltIndex = "1"
	)
	rzpVerifier, err := NewRazorpayVerifier(rzpSecret)
	if err != nil {
		t.Fatalf("NewRazorpayVerifier: %v", err)
	}
	ppVerifier, err := NewPhonePeVerifier(ppSaltKey, ppSaltIndex)
	if err != nil {
		t.Fatalf("NewPhonePeVerifier: %v", err)
	}
	stripeVerifier, err := NewStripeVerifier("whsec_test", 5*time.Minute)
	if err != nil {
		t.Fatalf("NewStripeVerifier: %v", err)
	}

	verifiers := map[PSPType]SignatureVerifier{
		Stripe:   stripeVerifier,
		Razorpay: rzpVerifier,
		PhonePe:  ppVerifier,
	}

	h, err := NewWebhookHandler(
		verifiers,
		store,
		NewKafkaWriterProducer(writer),
		"payment.webhooks",
		slog.New(slog.NewTextHandler(io.Discard, nil)),
		metrics,
	)
	if err != nil {
		t.Fatalf("NewWebhookHandler: %v", err)
	}

	mux := http.NewServeMux()
	mux.HandleFunc("/webhooks/razorpay", h.HandleRazorpay)
	mux.HandleFunc("/webhooks/phonepe", h.HandlePhonePe)

	t.Run("razorpay authorize -> capture -> refund same payment id all processed and duplicates deduped", func(t *testing.T) {
		beforeCount := len(writer.messages)
		authorized := []byte(`{"event":"payment.authorized","payload":{"payment":{"entity":{"id":"pay_shared_1","order_id":"ord_1","amount":1200,"currency":"INR","status":"authorized"}}}}`)
		captured := []byte(`{"event":"payment.captured","payload":{"payment":{"entity":{"id":"pay_shared_1","order_id":"ord_1","amount":1200,"currency":"INR","status":"captured"}}}}`)
		refunded := []byte(`{"event":"refund.processed","payload":{"refund":{"entity":{"id":"rfnd_1","payment_id":"pay_shared_1","amount":1200,"currency":"INR","status":"processed"}}}}`)

		for _, tc := range []struct {
			name    string
			body    []byte
			eventID string
		}{
			{name: "authorized", body: authorized},
			{name: "captured", body: captured},
			{name: "refunded", body: refunded},
			{name: "captured with explicit X-Razorpay-Event-Id", body: captured, eventID: "evt_rzp_header_unique"},
		} {
			req := httptest.NewRequest(http.MethodPost, "/webhooks/razorpay", bytes.NewReader(tc.body))
			req.Header.Set("X-Razorpay-Signature", signRazorpayPayload(rzpSecret, tc.body))
			if tc.eventID != "" {
				req.Header.Set("X-Razorpay-Event-Id", tc.eventID)
			}
			rec := httptest.NewRecorder()
			mux.ServeHTTP(rec, req)
			if rec.Code != http.StatusAccepted {
				t.Fatalf("%s expected 202, got %d body=%s", tc.name, rec.Code, rec.Body.String())
			}
		}
		if len(writer.messages) != beforeCount+4 {
			t.Fatalf("expected %d published events, got %d", beforeCount+4, len(writer.messages))
		}

		dupReq := httptest.NewRequest(http.MethodPost, "/webhooks/razorpay", bytes.NewReader(captured))
		dupReq.Header.Set("X-Razorpay-Signature", signRazorpayPayload(rzpSecret, captured))
		dupRec := httptest.NewRecorder()
		mux.ServeHTTP(dupRec, dupReq)
		if dupRec.Code != http.StatusOK || !strings.Contains(dupRec.Body.String(), `"duplicate":true`) {
			t.Fatalf("expected duplicate 200 response, got %d body=%s", dupRec.Code, dupRec.Body.String())
		}
		if len(writer.messages) != beforeCount+4 {
			t.Fatalf("duplicate must not publish, count=%d", len(writer.messages))
		}

		dupHdrReq := httptest.NewRequest(http.MethodPost, "/webhooks/razorpay", bytes.NewReader(captured))
		dupHdrReq.Header.Set("X-Razorpay-Signature", signRazorpayPayload(rzpSecret, captured))
		dupHdrReq.Header.Set("X-Razorpay-Event-Id", "evt_rzp_header_unique")
		dupHdrRec := httptest.NewRecorder()
		mux.ServeHTTP(dupHdrRec, dupHdrReq)
		if dupHdrRec.Code != http.StatusOK || !strings.Contains(dupHdrRec.Body.String(), `"duplicate":true`) {
			t.Fatalf("expected header duplicate 200 response, got %d body=%s", dupHdrRec.Code, dupHdrRec.Body.String())
		}
		if len(writer.messages) != beforeCount+4 {
			t.Fatalf("header duplicate must not publish, count=%d", len(writer.messages))
		}
	})

	t.Run("phonepe authorize -> capture -> refund same transactionId all processed and duplicates deduped", func(t *testing.T) {
		beforeCount := len(writer.messages)
		authorized := []byte(`{"transactionId":"txn_shared_1","event":"PAYMENT_AUTHORIZED","merchantOrderId":"ord_2","amount":1500,"state":"PENDING"}`)
		captured := []byte(`{"transactionId":"txn_shared_1","event":"PAYMENT_SUCCESS","merchantOrderId":"ord_2","amount":1500,"state":"COMPLETED"}`)
		refunded := []byte(`{"transactionId":"txn_shared_1","event":"REFUND_SUCCESS","merchantOrderId":"ord_2","amount":1500,"state":"REFUNDED"}`)

		for _, body := range [][]byte{authorized, captured, refunded} {
			req := httptest.NewRequest(http.MethodPost, "/webhooks/phonepe", bytes.NewReader(body))
			req.Header.Set("X-Verify", signPhonePePayload(ppSaltKey, ppSaltIndex, body))
			rec := httptest.NewRecorder()
			mux.ServeHTTP(rec, req)
			if rec.Code != http.StatusAccepted {
				t.Fatalf("expected 202, got %d body=%s", rec.Code, rec.Body.String())
			}
		}
		if len(writer.messages) != beforeCount+3 {
			t.Fatalf("expected %d published events, got %d", beforeCount+3, len(writer.messages))
		}

		dupReq := httptest.NewRequest(http.MethodPost, "/webhooks/phonepe", bytes.NewReader(captured))
		dupReq.Header.Set("X-Verify", signPhonePePayload(ppSaltKey, ppSaltIndex, captured))
		dupRec := httptest.NewRecorder()
		mux.ServeHTTP(dupRec, dupReq)
		if dupRec.Code != http.StatusOK || !strings.Contains(dupRec.Body.String(), `"duplicate":true`) {
			t.Fatalf("expected duplicate 200 response, got %d body=%s", dupRec.Code, dupRec.Body.String())
		}
		if len(writer.messages) != beforeCount+3 {
			t.Fatalf("duplicate must not publish, count=%d", len(writer.messages))
		}
	})
}
