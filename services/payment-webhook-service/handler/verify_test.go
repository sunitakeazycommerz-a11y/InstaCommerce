package handler

import (
	"crypto/hmac"
	"crypto/sha256"
	"encoding/hex"
	"fmt"
	"strings"
	"testing"
	"time"
)

func TestStripeVerifier(t *testing.T) {
	secret := "whsec_stripe_unit"
	v, err := NewStripeVerifier(secret, 30*time.Second)
	if err != nil {
		t.Fatalf("NewStripeVerifier: %v", err)
	}
	payload := []byte(`{"id":"evt_1","type":"payment_intent.succeeded"}`)
	ts := time.Now().Unix()

	mac := hmac.New(sha256.New, []byte(secret))
	mac.Write(fmt.Appendf(nil, "%d.", ts))
	mac.Write(payload)
	sig := hex.EncodeToString(mac.Sum(nil))
	hdr := fmt.Sprintf("t=%d,v1=%s", ts, sig)

	if err := v.Verify(payload, hdr); err != nil {
		t.Fatalf("expected valid stripe signature, got %v", err)
	}
	if err := v.Verify(payload, ""); err == nil {
		t.Fatal("expected error for missing signature")
	}
	if err := v.Verify(payload, fmt.Sprintf("t=%d,v1=%s", ts-120, sig)); err == nil {
		t.Fatal("expected error for expired timestamp")
	}
}

func TestRazorpayVerifier_RejectsStripeHeaderFormatAndValidatesRawHMAC(t *testing.T) {
	secret := "rzp_secret_123"
	v, err := NewRazorpayVerifier(secret)
	if err != nil {
		t.Fatalf("NewRazorpayVerifier: %v", err)
	}
	payload := []byte(`{"event":"payment.captured"}`)

	mac := hmac.New(sha256.New, []byte(secret))
	mac.Write(payload)
	validSig := hex.EncodeToString(mac.Sum(nil))

	if err := v.Verify(payload, validSig); err != nil {
		t.Fatalf("expected valid razorpay signature, got %v", err)
	}
	if err := v.Verify(payload, strings.ToUpper(validSig)); err != nil {
		t.Fatalf("expected uppercase hex razorpay signature to pass, got %v", err)
	}

	// Stripe-style timestamped header must NOT be parsed or accepted by RazorpayVerifier.
	stripeStyle := fmt.Sprintf("t=%d,v1=%s", time.Now().Unix(), validSig)
	if err := v.Verify(payload, stripeStyle); err == nil {
		t.Fatal("expected RazorpayVerifier to reject Stripe-style t=...,v1=... header")
	}
}

func TestPhonePeVerifier_EnforcesCanonicalSaltIndexVerification(t *testing.T) {
	saltKey := "salt_key_abc"
	saltIndex := "2"
	v, err := NewPhonePeVerifier(saltKey, saltIndex)
	if err != nil {
		t.Fatalf("NewPhonePeVerifier: %v", err)
	}
	payload := []byte(`{"transactionId":"txn_1","state":"COMPLETED"}`)

	hCanonical := sha256.New()
	hCanonical.Write(payload)
	hCanonical.Write([]byte(saltKey))
	hCanonical.Write([]byte(saltIndex))
	canonicalSig := hex.EncodeToString(hCanonical.Sum(nil)) + "###" + saltIndex

	if err := v.Verify(payload, canonicalSig); err != nil {
		t.Fatalf("expected canonical PhonePe signature to succeed, got %v", err)
	}

	// Fallback hash without saltIndex must be rejected even with ###saltIndex suffix.
	hNoIdx := sha256.New()
	hNoIdx.Write(payload)
	hNoIdx.Write([]byte(saltKey))
	fallbackSig := hex.EncodeToString(hNoIdx.Sum(nil)) + "###" + saltIndex
	if err := v.Verify(payload, fallbackSig); err == nil {
		t.Fatal("expected PhonePeVerifier to reject hash computed without saltIndex")
	}

	// Wrong index suffix must be rejected.
	wrongIdxSig := hex.EncodeToString(hCanonical.Sum(nil)) + "###1"
	if err := v.Verify(payload, wrongIdxSig); err == nil {
		t.Fatal("expected PhonePeVerifier to reject mismatched salt index")
	}
}
