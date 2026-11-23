package kafka

import (
	"encoding/json"
	"testing"
	"time"
)

func TestEventEnvelopeEmitsDualIDAndTimeFields(t *testing.T) {
	ts := time.Date(2026, time.March, 15, 10, 30, 0, 0, time.UTC)

	tests := []struct {
		name     string
		envelope EventEnvelope
	}{
		{
			name: "populated via EventID and Timestamp",
			envelope: EventEnvelope{
				EventID:       "evt-100",
				EventType:     "OrderPlaced",
				AggregateID:   "ord-100",
				AggregateType: "Order",
				SchemaVersion: "v1",
				SourceService: "order-service",
				CorrelationID: "corr-100",
				Timestamp:     ts,
				Payload:       map[string]any{"totalCents": float64(499)},
			},
		},
		{
			name: "populated via ID and EventTime",
			envelope: EventEnvelope{
				ID:            "evt-100",
				EventType:     "OrderPlaced",
				AggregateID:   "ord-100",
				AggregateType: "Order",
				SchemaVersion: "v1",
				SourceService: "order-service",
				CorrelationID: "corr-100",
				EventTime:     ts,
				Payload:       map[string]any{"totalCents": float64(499)},
			},
		},
	}

	for _, tc := range tests {
		t.Run(tc.name, func(t *testing.T) {
			if err := tc.envelope.Validate(); err != nil {
				t.Fatalf("Validate() unexpected error: %v", err)
			}
			raw, err := json.Marshal(tc.envelope)
			if err != nil {
				t.Fatalf("Marshal() unexpected error: %v", err)
			}

			var decoded map[string]any
			if err := json.Unmarshal(raw, &decoded); err != nil {
				t.Fatalf("Unmarshal(map) unexpected error: %v", err)
			}

			if decoded["id"] != "evt-100" || decoded["eventId"] != "evt-100" {
				t.Fatalf("expected id and eventId to equal evt-100, got id=%v eventId=%v", decoded["id"], decoded["eventId"])
			}
			if decoded["eventTime"] != decoded["timestamp"] || decoded["eventTime"] == "" {
				t.Fatalf("expected matching non-empty eventTime and timestamp, got eventTime=%v timestamp=%v", decoded["eventTime"], decoded["timestamp"])
			}

			var roundtrip EventEnvelope
			if err := json.Unmarshal(raw, &roundtrip); err != nil {
				t.Fatalf("Unmarshal(EventEnvelope) unexpected error: %v", err)
			}
			if roundtrip.ID != "evt-100" || roundtrip.EventID != "evt-100" {
				t.Fatalf("roundtrip ID mismatch: id=%q eventId=%q", roundtrip.ID, roundtrip.EventID)
			}
			if !roundtrip.EventTime.Equal(ts) || !roundtrip.Timestamp.Equal(ts) {
				t.Fatalf("roundtrip time mismatch: eventTime=%v timestamp=%v", roundtrip.EventTime, roundtrip.Timestamp)
			}
		})
	}
}

func TestEventEnvelopeUnmarshalJavaAndLegacyAliases(t *testing.T) {
	tests := []struct {
		name string
		raw  string
	}{
		{
			name: "java schema primary id and eventTime",
			raw: `{
				"id": "evt-java-1",
				"eventType": "PaymentCaptured",
				"aggregateId": "pay-1",
				"aggregateType": "Payment",
				"schemaVersion": "v1",
				"sourceService": "payment-service",
				"correlationId": "corr-1",
				"eventTime": "2026-03-15T10:30:00Z",
				"payload": {"amountCents": 1200}
			}`,
		},
		{
			name: "snake_case aliases",
			raw: `{
				"event_id": "evt-java-1",
				"event_type": "PaymentCaptured",
				"aggregate_id": "pay-1",
				"aggregate_type": "Payment",
				"schema_version": "v1",
				"source_service": "payment-service",
				"correlation_id": "corr-1",
				"event_time": "2026-03-15T10:30:00Z",
				"payload": {"amountCents": 1200}
			}`,
		},
	}

	for _, tc := range tests {
		t.Run(tc.name, func(t *testing.T) {
			var env EventEnvelope
			if err := json.Unmarshal([]byte(tc.raw), &env); err != nil {
				t.Fatalf("Unmarshal() error: %v", err)
			}
			if err := env.Validate(); err != nil {
				t.Fatalf("Validate() error: %v", err)
			}
			if env.ID != "evt-java-1" || env.EventID != "evt-java-1" {
				t.Fatalf("expected evt-java-1, got id=%q eventId=%q", env.ID, env.EventID)
			}
			if env.EventTime.IsZero() || !env.EventTime.Equal(env.Timestamp) {
				t.Fatalf("expected synced non-zero times, got eventTime=%v timestamp=%v", env.EventTime, env.Timestamp)
			}
			if env.SourceService != "payment-service" || env.CorrelationID != "corr-1" {
				t.Fatalf("expected synced metadata, got source=%q corr=%q", env.SourceService, env.CorrelationID)
			}
		})
	}
}
