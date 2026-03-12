package handler

import (
	"encoding/json"
	"testing"
	"time"
)

// TestKafkaEnvelopeContainsRawPSPPayload verifies that json.Marshal of a
// WebhookEvent (the Kafka wire format) includes the raw_psp_payload field as
// embedded JSON rather than base64 or omitted.
func TestKafkaEnvelopeContainsRawPSPPayload(t *testing.T) {
	rawStripe := json.RawMessage(`{"id":"evt_123","type":"payment_intent.succeeded","data":{"object":{"id":"pi_456","amount":4999,"currency":"usd","status":"succeeded","metadata":{"order_id":"ord-abc"}}}}`)

	event := WebhookEvent{
		ID:            "evt_123",
		PSP:           Stripe,
		EventType:     "payment_intent.succeeded",
		PaymentID:     "pi_456",
		OrderID:       "ord-abc",
		AmountCents:   4999,
		Currency:      "usd",
		Status:        "succeeded",
		Version:       SchemaVersion,
		RawPSPPayload: rawStripe,
		ReceivedAt:    time.Date(2025, 1, 15, 10, 30, 0, 0, time.UTC),
	}

	encoded, err := json.Marshal(event)
	if err != nil {
		t.Fatalf("json.Marshal failed: %v", err)
	}

	// Decode into a generic map to inspect wire-level keys.
	var wire map[string]json.RawMessage
	if err := json.Unmarshal(encoded, &wire); err != nil {
		t.Fatalf("json.Unmarshal into map failed: %v", err)
	}

	// v1 canonical fields must still be present.
	for _, key := range []string{"id", "psp", "event_type", "payment_id", "order_id", "amount_cents", "currency", "status", "received_at"} {
		if _, ok := wire[key]; !ok {
			t.Errorf("v1 field %q missing from Kafka envelope", key)
		}
	}

	// v2 fields.
	if _, ok := wire["schema_version"]; !ok {
		t.Fatal("schema_version missing from Kafka envelope")
	}
	rawField, ok := wire["raw_psp_payload"]
	if !ok {
		t.Fatal("raw_psp_payload missing from Kafka envelope")
	}

	// raw_psp_payload must be embedded JSON, not a base64 string.
	// A base64 string would start with a quote and contain letters/digits.
	// Embedded JSON (an object) starts with '{'.
	if rawField[0] != '{' {
		t.Fatalf("raw_psp_payload should be embedded JSON object, got prefix %q", string(rawField[:20]))
	}

	// Verify the embedded payload round-trips.
	var inner map[string]interface{}
	if err := json.Unmarshal(rawField, &inner); err != nil {
		t.Fatalf("raw_psp_payload is not valid JSON: %v", err)
	}
	if inner["id"] != "evt_123" {
		t.Errorf("raw_psp_payload.id = %v, want evt_123", inner["id"])
	}
}

// TestKafkaEnvelopeBackwardCompatible verifies that a v1 consumer that does
// not know about raw_psp_payload or schema_version can still unmarshal the
// message into its existing struct without error.
func TestKafkaEnvelopeBackwardCompatible(t *testing.T) {
	rawStripe := json.RawMessage(`{"id":"evt_v2","type":"charge.succeeded"}`)

	event := WebhookEvent{
		ID:            "evt_v2",
		PSP:           Stripe,
		EventType:     "charge.succeeded",
		PaymentID:     "pi_99",
		OrderID:       "ord-99",
		AmountCents:   1000,
		Currency:      "usd",
		Status:        "succeeded",
		Version:       SchemaVersion,
		RawPSPPayload: rawStripe,
		ReceivedAt:    time.Date(2025, 6, 1, 0, 0, 0, 0, time.UTC),
	}

	encoded, err := json.Marshal(event)
	if err != nil {
		t.Fatalf("json.Marshal failed: %v", err)
	}

	// Simulate a v1 consumer struct that only knows about the original fields.
	type V1Event struct {
		ID          string  `json:"id"`
		PSP         PSPType `json:"psp"`
		EventType   string  `json:"event_type"`
		PaymentID   string  `json:"payment_id"`
		OrderID     string  `json:"order_id"`
		AmountCents int64   `json:"amount_cents"`
		Currency    string  `json:"currency"`
		Status      string  `json:"status"`
		ReceivedAt  string  `json:"received_at"`
	}

	var v1 V1Event
	if err := json.Unmarshal(encoded, &v1); err != nil {
		t.Fatalf("v1 consumer unmarshal failed (backward compat broken): %v", err)
	}
	if v1.ID != "evt_v2" {
		t.Errorf("v1.ID = %q, want evt_v2", v1.ID)
	}
	if v1.AmountCents != 1000 {
		t.Errorf("v1.AmountCents = %d, want 1000", v1.AmountCents)
	}
}

// TestRawPSPPayloadOmittedWhenNil ensures that if RawPSPPayload is nil the
// field is absent from the wire format (omitempty).
func TestRawPSPPayloadOmittedWhenNil(t *testing.T) {
	event := WebhookEvent{
		ID:         "evt_legacy",
		PSP:        Razorpay,
		EventType:  "payment.authorized",
		ReceivedAt: time.Now().UTC(),
	}

	encoded, err := json.Marshal(event)
	if err != nil {
		t.Fatalf("json.Marshal failed: %v", err)
	}

	var wire map[string]json.RawMessage
	if err := json.Unmarshal(encoded, &wire); err != nil {
		t.Fatalf("json.Unmarshal into map failed: %v", err)
	}

	if _, ok := wire["raw_psp_payload"]; ok {
		t.Error("raw_psp_payload should be absent when nil, got a value")
	}
	if _, ok := wire["schema_version"]; ok {
		t.Error("schema_version should be absent when zero (omitempty), got a value")
	}
}

// TestParseEventSetsRawPSPPayload verifies that parseEvent populates the
// RawPSPPayload field for each supported PSP.
func TestParseEventSetsRawPSPPayload(t *testing.T) {
	h := &WebhookHandler{} // parseEvent only needs the receiver, no deps.

	tests := []struct {
		name    string
		psp     PSPType
		payload string
	}{
		{
			name:    "stripe",
			psp:     Stripe,
			payload: `{"id":"evt_s1","type":"payment_intent.succeeded","data":{"object":{"id":"pi_1","amount":500,"currency":"usd","status":"succeeded","metadata":{"order_id":"o1"}}}}`,
		},
		{
			name:    "razorpay",
			psp:     Razorpay,
			payload: `{"event":"payment.authorized","payload":{"payment":{"entity":{"id":"pay_r1","order_id":"o2","amount":600,"currency":"INR","status":"authorized"}}}}`,
		},
		{
			name:    "phonepe",
			psp:     PhonePe,
			payload: `{"transactionId":"txn_p1","event":"PAYMENT_SUCCESS","merchantOrderId":"o3","amount":700,"state":"COMPLETED"}`,
		},
	}

	for _, tc := range tests {
		t.Run(tc.name, func(t *testing.T) {
			event, err := h.parseEvent(tc.psp, []byte(tc.payload))
			if err != nil {
				t.Fatalf("parseEvent(%s) error: %v", tc.psp, err)
			}

			if event.RawPSPPayload == nil {
				t.Fatal("RawPSPPayload is nil")
			}

			// Must be valid JSON.
			if !json.Valid(event.RawPSPPayload) {
				t.Fatal("RawPSPPayload is not valid JSON")
			}

			// Must byte-equal the input.
			if string(event.RawPSPPayload) != tc.payload {
				t.Errorf("RawPSPPayload = %s, want %s", event.RawPSPPayload, tc.payload)
			}

			if event.Version != SchemaVersion {
				t.Errorf("Version = %d, want %d", event.Version, SchemaVersion)
			}
		})
	}
}

// TestSchemaVersionValue documents the current schema version constant.
func TestSchemaVersionValue(t *testing.T) {
	if SchemaVersion < 2 {
		t.Errorf("SchemaVersion = %d, expected >= 2 for raw_psp_payload support", SchemaVersion)
	}
}
