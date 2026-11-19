package handler

import (
	"crypto/hmac"
	"crypto/sha256"
	"encoding/hex"
	"errors"
	"fmt"
	"strconv"
	"strings"
	"time"
)

// ErrVerifierNotConfigured indicates a PSP verifier lacks signing credentials.
var ErrVerifierNotConfigured = errors.New("verify: webhook verifier not configured")

// DefaultTimestampTolerance is the standard 300-second (5-minute) replay window for webhook signatures.
const DefaultTimestampTolerance = 300 * time.Second

// SignatureVerifier validates the cryptographic signature attached to a PSP webhook callback.
type SignatureVerifier interface {
	Verify(payload []byte, signature string) error
}

// UnconfiguredVerifier rejects callbacks with ErrVerifierNotConfigured so callers
// can surface HTTP 503 when signing secrets are not configured.
type UnconfiguredVerifier struct {
	Reason string
}

func (v *UnconfiguredVerifier) Verify(_ []byte, _ string) error {
	if v != nil && v.Reason != "" {
		return fmt.Errorf("%w: %s", ErrVerifierNotConfigured, v.Reason)
	}
	return ErrVerifierNotConfigured
}

// StripeVerifier validates Stripe webhook signatures with timestamp replay protection
// and supports multiple active secrets during key rotation.
type StripeVerifier struct {
	secrets   []string
	tolerance time.Duration
	nowFunc   func() time.Time
}

// NewStripeVerifier creates a StripeVerifier for a single webhook endpoint secret.
func NewStripeVerifier(secret string, tolerance time.Duration) (*StripeVerifier, error) {
	if strings.TrimSpace(secret) == "" {
		return nil, fmt.Errorf("verify: stripe webhook secret must not be empty")
	}
	return NewStripeVerifierWithRotation([]string{secret}, tolerance)
}

// NewStripeVerifierWithRotation creates a StripeVerifier validating against any active rotated secret.
func NewStripeVerifierWithRotation(secrets []string, tolerance time.Duration) (*StripeVerifier, error) {
	cleaned := cleanSecrets(secrets)
	if len(cleaned) == 0 {
		return nil, fmt.Errorf("verify: at least one stripe webhook secret is required")
	}
	if tolerance < 0 {
		tolerance = DefaultTimestampTolerance
	}
	return &StripeVerifier{
		secrets:   cleaned,
		tolerance: tolerance,
		nowFunc:   time.Now,
	}, nil
}

func (v *StripeVerifier) Tolerance() time.Duration {
	return v.tolerance
}

func (v *StripeVerifier) Verify(payload []byte, signature string) error {
	if strings.TrimSpace(signature) == "" {
		return fmt.Errorf("verify: missing Stripe-Signature header")
	}
	if v == nil || len(v.secrets) == 0 {
		return ErrVerifierNotConfigured
	}

	timestamp, sigs := parseStripeHeader(signature)
	if timestamp == "" || len(sigs) == 0 {
		return fmt.Errorf("verify: malformed Stripe-Signature header")
	}

	nowFn := v.nowFunc
	if nowFn == nil {
		nowFn = time.Now
	}
	if v.tolerance > 0 {
		if err := validateUnixReplayWindow(timestamp, v.tolerance, nowFn()); err != nil {
			return err
		}
	}

	signed := make([]byte, 0, len(timestamp)+1+len(payload))
	signed = append(signed, timestamp...)
	signed = append(signed, '.')
	signed = append(signed, payload...)

	for _, secret := range v.secrets {
		mac := hmac.New(sha256.New, []byte(secret))
		mac.Write(signed)
		expected := hex.EncodeToString(mac.Sum(nil))
		for _, sig := range sigs {
			if hmac.Equal([]byte(strings.ToLower(sig)), []byte(expected)) {
				return nil
			}
		}
	}
	return fmt.Errorf("verify: no matching v1 signature found")
}

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

// RazorpayVerifier validates Razorpay webhook HMAC-SHA256 signatures and supports key rotation.
type RazorpayVerifier struct {
	secrets []string
}

func NewRazorpayVerifier(secret string) (*RazorpayVerifier, error) {
	if strings.TrimSpace(secret) == "" {
		return nil, fmt.Errorf("verify: razorpay webhook secret must not be empty")
	}
	return NewRazorpayVerifierWithRotation([]string{secret})
}

func NewRazorpayVerifierWithRotation(secrets []string) (*RazorpayVerifier, error) {
	cleaned := cleanSecrets(secrets)
	if len(cleaned) == 0 {
		return nil, fmt.Errorf("verify: at least one razorpay webhook secret is required")
	}
	return &RazorpayVerifier{secrets: cleaned}, nil
}

func (v *RazorpayVerifier) Verify(payload []byte, signature string) error {
	signature = strings.TrimSpace(signature)
	if signature == "" {
		return fmt.Errorf("verify: missing X-Razorpay-Signature header")
	}
	if v == nil || len(v.secrets) == 0 {
		return ErrVerifierNotConfigured
	}
	if len(signature) != sha256.Size*2 {
		return fmt.Errorf("verify: invalid razorpay signature length")
	}
	if _, err := hex.DecodeString(signature); err != nil {
		return fmt.Errorf("verify: invalid razorpay signature hex: %w", err)
	}

	lowerSig := []byte(strings.ToLower(signature))
	for _, secret := range v.secrets {
		mac := hmac.New(sha256.New, []byte(secret))
		mac.Write(payload)
		expected := hex.EncodeToString(mac.Sum(nil))
		if hmac.Equal(lowerSig, []byte(expected)) {
			return nil
		}
	}
	return fmt.Errorf("verify: razorpay signature mismatch")
}

// PhonePeSaltKey pairs a salt key with its index for zero-downtime PhonePe salt rotation.
type PhonePeSaltKey struct {
	Key   string
	Index string
}

// PhonePeVerifier validates PhonePe X-Verify webhook signatures across active rotated salts.
type PhonePeVerifier struct {
	salts []PhonePeSaltKey
}

func NewPhonePeVerifier(saltKey, saltIndex string) (*PhonePeVerifier, error) {
	if strings.TrimSpace(saltKey) == "" {
		return nil, fmt.Errorf("verify: phonepe salt key must not be empty")
	}
	if strings.TrimSpace(saltIndex) == "" {
		return nil, fmt.Errorf("verify: phonepe salt index must not be empty")
	}
	return NewPhonePeVerifierWithRotation([]PhonePeSaltKey{{Key: saltKey, Index: saltIndex}})
}

func NewPhonePeVerifierWithRotation(salts []PhonePeSaltKey) (*PhonePeVerifier, error) {
	var valid []PhonePeSaltKey
	for _, s := range salts {
		key := strings.TrimSpace(s.Key)
		idx := strings.TrimSpace(s.Index)
		if key != "" && idx != "" {
			valid = append(valid, PhonePeSaltKey{Key: key, Index: idx})
		}
	}
	if len(valid) == 0 {
		return nil, fmt.Errorf("verify: at least one phonepe salt key and index is required")
	}
	return &PhonePeVerifier{salts: valid}, nil
}

func (v *PhonePeVerifier) Verify(payload []byte, signature string) error {
	signature = strings.TrimSpace(signature)
	if signature == "" {
		return fmt.Errorf("verify: missing X-Verify header")
	}
	if v == nil || len(v.salts) == 0 {
		return ErrVerifierNotConfigured
	}

	parts := strings.SplitN(signature, "###", 2)
	if len(parts) != 2 {
		return fmt.Errorf("verify: malformed X-Verify header")
	}
	receivedHash := strings.TrimSpace(parts[0])
	receivedIndex := strings.TrimSpace(parts[1])

	if receivedHash == "" || len(receivedHash) != sha256.Size*2 {
		return fmt.Errorf("verify: invalid phonepe signature hash length")
	}
	if _, err := hex.DecodeString(receivedHash); err != nil {
		return fmt.Errorf("verify: invalid phonepe signature hex: %w", err)
	}

	lowerHash := []byte(strings.ToLower(receivedHash))
	indexMatched := false
	for _, s := range v.salts {
		if s.Index != receivedIndex {
			continue
		}
		indexMatched = true
		h := sha256.New()
		h.Write(payload)
		h.Write([]byte(s.Key))
		h.Write([]byte(s.Index))
		expected := hex.EncodeToString(h.Sum(nil))
		if hmac.Equal(lowerHash, []byte(expected)) {
			return nil
		}
	}
	if !indexMatched {
		return fmt.Errorf("verify: phonepe salt index mismatch")
	}
	return fmt.Errorf("verify: phonepe signature mismatch")
}

func validateUnixReplayWindow(raw string, tolerance time.Duration, now time.Time) error {
	ts, err := strconv.ParseInt(strings.TrimSpace(raw), 10, 64)
	if err != nil || ts <= 0 {
		return fmt.Errorf("verify: invalid timestamp in signature")
	}
	age := now.Sub(time.Unix(ts, 0))
	if age < 0 {
		age = -age
	}
	if age > tolerance {
		return fmt.Errorf("verify: signature timestamp outside tolerance (%s)", age)
	}
	return nil
}

func cleanSecrets(secrets []string) []string {
	out := make([]string, 0, len(secrets))
	seen := make(map[string]struct{}, len(secrets))
	for _, s := range secrets {
		s = strings.TrimSpace(s)
		if s == "" {
			continue
		}
		if _, dup := seen[s]; !dup {
			seen[s] = struct{}{}
			out = append(out, s)
		}
	}
	return out
}
