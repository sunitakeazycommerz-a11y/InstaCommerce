package main

import (
	"encoding/json"
	"testing"
	"time"
)

func TestResolveKafkaTopic(t *testing.T) {
	tests := []struct {
		override string
		aggType  string
		expected string
	}{
		{"custom.topic", "order", "custom.topic"},
		{"", "order", "orders.events"},
		{"", "orders", "orders.events"},
		{"", "payment", "payments.events"},
		{"", "payments", "payments.events"},
		{"", "cart", "cart.events"},
		{"", "carts", "cart.events"},
		{"", "delivery", "delivery.events"},
		{"", "routing", "delivery.events"},
		{"", "warehouse", "warehouse.events"},
		{"", "store", "warehouse.events"},
		{"", "inventory", "inventory.events"},
		{"", "unknown_custom", "unknown_custom.events"},
		{"", "", "unknown.events"},
	}

	for _, tt := range tests {
		t.Run(tt.aggType, func(t *testing.T) {
			got := resolveKafkaTopic(tt.override, tt.aggType)
			if got != tt.expected {
				t.Errorf("resolveKafkaTopic(%q, %q) = %q, want %q", tt.override, tt.aggType, got, tt.expected)
			}
		})
	}
}

func TestBuildEventMessage(t *testing.T) {
	evt := outboxEvent{
		ID:            "evt-12345",
		AggregateType: "order",
		AggregateID:   "ord-67890",
		EventType:     "ORDER_CREATED",
		Payload:       []byte(`{"total_cents": 5000, "user_id": "usr-1"}`),
		CreatedAt:     time.Date(2026, 10, 16, 12, 0, 0, 0, time.UTC),
		SourceService: "order-service",
		CorrelationID: "corr-999",
		SchemaVersion: "v1",
	}

	raw := buildEventMessage(evt)

	var env map[string]any
	if err := json.Unmarshal(raw, &env); err != nil {
		t.Fatalf("failed to unmarshal built event message: %v", err)
	}

	// Verify required canonical fields
	if env["id"] != "evt-12345" {
		t.Errorf("expected id 'evt-12345', got %v", env["id"])
	}
	if env["eventId"] != "evt-12345" {
		t.Errorf("expected eventId 'evt-12345', got %v", env["eventId"])
	}
	if env["event_id"] != "evt-12345" {
		t.Errorf("expected event_id 'evt-12345', got %v", env["event_id"])
	}
	if env["eventType"] != "ORDER_CREATED" {
		t.Errorf("expected eventType 'ORDER_CREATED', got %v", env["eventType"])
	}
	if env["event_type"] != "ORDER_CREATED" {
		t.Errorf("expected event_type 'ORDER_CREATED', got %v", env["event_type"])
	}
	if env["source_service"] != "order-service" {
		t.Errorf("expected source_service 'order-service', got %v", env["source_service"])
	}
	if env["sourceService"] != "order-service" {
		t.Errorf("expected sourceService 'order-service', got %v", env["sourceService"])
	}
	if env["correlation_id"] != "corr-999" {
		t.Errorf("expected correlation_id 'corr-999', got %v", env["correlation_id"])
	}
	if env["correlationId"] != "corr-999" {
		t.Errorf("expected correlationId 'corr-999', got %v", env["correlationId"])
	}
	if env["schemaVersion"] != "v1" {
		t.Errorf("expected schemaVersion 'v1', got %v", env["schemaVersion"])
	}

	// Verify payload exists as nested map
	payload, ok := env["payload"].(map[string]any)
	if !ok {
		t.Fatalf("expected payload to be map[string]any, got %T", env["payload"])
	}
	if payload["user_id"] != "usr-1" {
		t.Errorf("expected payload user_id 'usr-1', got %v", payload["user_id"])
	}

	// Verify flattened legacy properties exist at root
	if env["user_id"] != "usr-1" {
		t.Errorf("expected flattened user_id 'usr-1', got %v", env["user_id"])
	}
}

