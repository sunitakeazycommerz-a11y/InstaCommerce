package handler

import (
	"crypto/hmac"
	"crypto/sha256"
	"encoding/hex"
	"fmt"
	"strconv"
	"strings"
	"time"
)

// SignatureVerifier validates the cryptographic signature attached to a PSP
// webhook callback. Each PSP uses a different signing scheme.
type SignatureVerifier interface {
	// Verify checks that signature is valid for the given payload.
	// Returns nil on success or a descriptive error on failure.
	Verify(payload []byte, signature string) error
}

// StripeVerifier validates Stripe webhook signatures.
//
// Stripe uses HMAC-SHA256 with a shared endpoint secret. The header format is:
//
//	t=<unix-timestamp>,v1=<hex-hmac>,v1=<hex-hmac>,...
//
// The signed payload is "<timestamp>.<body>".
type StripeVerifier struct {
	secret    string
	tolerance time.Duration // maximum age of signature; zero disables check
}

// NewStripeVerifier creates a StripeVerifier with the given webhook endpoint
// secret and timestamp tolerance. tolerance of zero disables replay protection.
func NewStripeVerifier(secret string, tolerance time.Duration) (*StripeVerifier, error) {
	if secret == "" {
		return nil, fmt.Errorf("verify: stripe webhook secret must not be empty")
	}
	return &StripeVerifier{secret: secret, tolerance: tolerance}, nil
}

// Verify validates a Stripe-Signature header value against the raw payload.
func (v *StripeVerifier) Verify(payload []byte, signature string) error {
	if signature == "" {
		return fmt.Errorf("verify: missing Stripe-Signature header")
	}

	timestamp, sigs := parseStripeHeader(signature)
	if timestamp == "" || len(sigs) == 0 {
		return fmt.Errorf("verify: malformed Stripe-Signature header")
	}

	// Replay protection.
	if v.tolerance > 0 {
		ts, err := strconv.ParseInt(timestamp, 10, 64)
		if err != nil {
			return fmt.Errorf("verify: invalid timestamp in signature: %w", err)
		}
		age := time.Since(time.Unix(ts, 0))
		if age < 0 {
			age = -age
		}
		if age > v.tolerance {
			return fmt.Errorf("verify: signature timestamp outside tolerance (%s)", age)
		}
	}

	// Compute expected signature: HMAC-SHA256(secret, "<timestamp>.<payload>").
	mac := hmac.New(sha256.New, []byte(v.secret))
	mac.Write([]byte(timestamp))
	mac.Write([]byte("."))
	mac.Write(payload)
	expected := hex.EncodeToString(mac.Sum(nil))

	for _, sig := range sigs {
		if hmac.Equal([]byte(sig), []byte(expected)) {
			return nil
		}
	}
	return fmt.Errorf("verify: no matching v1 signature found")
}

// parseStripeHeader splits a Stripe-Signature header into timestamp and v1
// signature values.
func parseStripeHeader(header string) (string, []string) {
	var ts string
	var sigs []string
	for _, part := range strings.Split(header, ",") {
		kv := strings.SplitN(strings.TrimSpace(part), "=", 2)
		if len(kv) != 2 {
			continue
		}
		switch kv[0] {
		case "t":
			ts = kv[1]
		case "v1":
			sigs = append(sigs, kv[1])
		}
	}
	return ts, sigs
}

// RazorpayVerifier validates Razorpay webhook signatures.
//
// Razorpay uses HMAC-SHA256 with a shared webhook secret. The hex-encoded
// signature is passed in the "X-Razorpay-Signature" header. The signed payload
// is the raw request body.
type RazorpayVerifier struct {
	secret string
}

// NewRazorpayVerifier creates a RazorpayVerifier with the given webhook secret.
func NewRazorpayVerifier(secret string) (*RazorpayVerifier, error) {
	if secret == "" {
		return nil, fmt.Errorf("verify: razorpay webhook secret must not be empty")
	}
	return &RazorpayVerifier{secret: secret}, nil
}

// Verify validates an X-Razorpay-Signature header value against the raw payload.
func (v *RazorpayVerifier) Verify(payload []byte, signature string) error {
	if signature == "" {
		return fmt.Errorf("verify: missing X-Razorpay-Signature header")
	}

	mac := hmac.New(sha256.New, []byte(v.secret))
	mac.Write(payload)
	expected := hex.EncodeToString(mac.Sum(nil))

	if !hmac.Equal([]byte(signature), []byte(expected)) {
		return fmt.Errorf("verify: razorpay signature mismatch")
	}
	return nil
}

// PhonePeVerifier validates PhonePe webhook signatures.
//
// PhonePe uses SHA-256 of "<base64-payload><salt-key><salt-index>" and passes
// the result in the "X-Verify" header as "<sha256-hex>###<salt-index>".
type PhonePeVerifier struct {
	saltKey   string
	saltIndex string
}

// NewPhonePeVerifier creates a PhonePeVerifier with the given salt key and
// index.
func NewPhonePeVerifier(saltKey, saltIndex string) (*PhonePeVerifier, error) {
	if saltKey == "" {
		return nil, fmt.Errorf("verify: phonepe salt key must not be empty")
	}
	if saltIndex == "" {
		return nil, fmt.Errorf("verify: phonepe salt index must not be empty")
	}
	return &PhonePeVerifier{saltKey: saltKey, saltIndex: saltIndex}, nil
}

// Verify validates an X-Verify header value against the raw payload.
func (v *PhonePeVerifier) Verify(payload []byte, signature string) error {
	if signature == "" {
		return fmt.Errorf("verify: missing X-Verify header")
	}

	parts := strings.SplitN(signature, "###", 2)
	if len(parts) != 2 {
		return fmt.Errorf("verify: malformed X-Verify header")
	}
	receivedHash := parts[0]
	receivedIndex := parts[1]

	if receivedIndex != v.saltIndex {
		return fmt.Errorf("verify: phonepe salt index mismatch")
	}

	h := sha256.New()
	h.Write(payload)
	h.Write([]byte(v.saltKey))
	h.Write([]byte(v.saltIndex))
	expected := hex.EncodeToString(h.Sum(nil))

	if !hmac.Equal([]byte(receivedHash), []byte(expected)) {
		return fmt.Errorf("verify: phonepe signature mismatch")
	}
	return nil
}
