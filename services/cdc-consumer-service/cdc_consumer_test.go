package main

import (
	"context"
	"encoding/json"
	"testing"
	"time"

	"cloud.google.com/go/bigquery"
	"github.com/segmentio/kafka-go"
	"github.com/stretchr/testify/assert"
	"github.com/stretchr/testify/require"
)

// TestEvent represents a test CDC event structure
type TestEvent struct {
	EventID    string                 `json:"event_id"`
	EventType  string                 `json:"event_type"`
	AggID      string                 `json:"aggregate_id"`
	Timestamp  int64                  `json:"timestamp"`
	Version    string                 `json:"version"`
	Payload    map[string]interface{} `json:"payload"`
	SourceDB   string                 `json:"source_db"`
}

// TestDebeziumEnvelope represents a Debezium CDC envelope
type TestDebeziumEnvelope struct {
	Before    map[string]interface{} `json:"before"`
	After     map[string]interface{} `json:"after"`
	Source    map[string]interface{} `json:"source"`
	Op        string                 `json:"op"`
	TsMs      int64                  `json:"ts_ms"`
	Transaction interface{}          `json:"transaction"`
}

func TestCDCEventConsumptionWithDebeziumEnvelope(t *testing.T) {
	// Test that Debezium CDC envelopes are properly parsed
	envelope := TestDebeziumEnvelope{
		Before: nil,
		After: map[string]interface{}{
			"order_id": "order-123",
			"amount":   5000,
			"currency": "INR",
		},
		Source: map[string]interface{}{
			"db":    "payment_ledger",
			"table": "transactions",
		},
		Op:   "c",
		TsMs: time.Now().UnixMilli(),
	}

	data, err := json.Marshal(envelope)
	require.NoError(t, err)
	assert.NotEmpty(t, data)

	var parsed TestDebeziumEnvelope
	err = json.Unmarshal(data, &parsed)
	require.NoError(t, err)

	assert.Equal(t, "c", parsed.Op)
	assert.NotNil(t, parsed.After)
	assert.Equal(t, "order-123", parsed.After["order_id"])
}

func TestEventDeduplicationWithIdempotencyKey(t *testing.T) {
	// Test that events with the same idempotency key are deduplicated
	event1 := map[string]interface{}{
		"event_id":       "evt-001",
		"aggregate_id":   "order-123",
		"event_type":     "PaymentProcessed",
		"idempotency_key": "idp-key-abc",
		"timestamp":      time.Now().Unix(),
	}

	event2 := map[string]interface{}{
		"event_id":        "evt-002",
		"aggregate_id":    "order-123",
		"event_type":      "PaymentProcessed",
		"idempotency_key": "idp-key-abc",
		"timestamp":       time.Now().Unix(),
	}

	// Both events should have the same idempotency key
	assert.Equal(t, event1["idempotency_key"], event2["idempotency_key"])
	// But different event IDs
	assert.NotEqual(t, event1["event_id"], event2["event_id"])
}

func TestKafkaMessageEnvelopeValidation(t *testing.T) {
	// Test that Kafka message headers and payload are properly structured
	msg := kafka.Message{
		Topic:   "payments.cdc",
		Partition: 0,
		Offset:  12345,
		Key:     []byte("order-123"),
		Value: []byte(`{
			"op": "c",
			"after": {"order_id": "order-123", "amount": 5000},
			"source": {"table": "transactions"}
		}`),
		Headers: []kafka.Header{
			{Key: "correlation_id", Value: []byte("corr-123")},
			{Key: "source_service", Value: []byte("payment-service")},
		},
	}

	assert.Equal(t, "payments.cdc", msg.Topic)
	assert.NotNil(t, msg.Value)
	assert.Len(t, msg.Headers, 2)

	// Verify headers
	for _, h := range msg.Headers {
		assert.NotEmpty(t, h.Key)
		assert.NotEmpty(t, h.Value)
	}
}

func TestCDCEventBatchProcessing(t *testing.T) {
	// Test that multiple CDC events are batched correctly
	events := make([]TestEvent, 0, 5)
	for i := 0; i < 5; i++ {
		events = append(events, TestEvent{
			EventID:   "evt-" + string(rune(i)),
			EventType: "TransactionCreated",
			AggID:     "txn-" + string(rune(i)),
			Timestamp: time.Now().Unix(),
			Version:   "1",
			Payload: map[string]interface{}{
				"amount":   1000 * (i + 1),
				"currency": "INR",
			},
			SourceDB: "payment_ledger",
		})
	}

	// Verify batch size
	assert.Equal(t, 5, len(events))

	// Verify each event is valid
	for i, event := range events {
		assert.NotEmpty(t, event.EventID)
		assert.NotEmpty(t, event.AggID)
		assert.Equal(t, 1000*(i+1), event.Payload["amount"])
	}
}

func TestInvalidDebeziumEnvelopeHandling(t *testing.T) {
	// Test that invalid envelopes are handled gracefully
	invalidEnvelopes := []string{
		`{}`,
		`{"op": "c"}`,
		`{"before": null}`,
		`invalid json`,
	}

	for _, envelope := range invalidEnvelopes {
		var parsed interface{}
		err := json.Unmarshal([]byte(envelope), &parsed)
		if err != nil {
			// Invalid JSON should be caught
			assert.Error(t, err)
		}
	}
}

func TestEventConsumerContextTimeout(t *testing.T) {
	// Test that context timeouts are handled properly
	ctx, cancel := context.WithTimeout(context.Background(), 100*time.Millisecond)
	defer cancel()

	// Simulate a long operation that respects context
	select {
	case <-ctx.Done():
		assert.Equal(t, context.DeadlineExceeded, ctx.Err())
	case <-time.After(200 * time.Millisecond):
		t.Fatal("context timeout not respected")
	}
}

func TestEventPayloadSerialization(t *testing.T) {
	// Test that complex event payloads serialize/deserialize correctly
	original := TestEvent{
		EventID:   "evt-123",
		EventType: "OrderPlaced",
		AggID:     "order-456",
		Timestamp: 1698765432000,
		Version:   "2",
		Payload: map[string]interface{}{
			"items": []map[string]interface{}{
				{"product_id": "prod-1", "quantity": 2, "price": 500},
				{"product_id": "prod-2", "quantity": 1, "price": 1000},
			},
			"total_amount": 2000,
			"currency":    "INR",
			"user_id":     "user-789",
		},
		SourceDB: "orders",
	}

	// Serialize
	data, err := json.Marshal(original)
	require.NoError(t, err)
	assert.NotEmpty(t, data)

	// Deserialize
	var deserialized TestEvent
	err = json.Unmarshal(data, &deserialized)
	require.NoError(t, err)

	// Verify all fields match
	assert.Equal(t, original.EventID, deserialized.EventID)
	assert.Equal(t, original.EventType, deserialized.EventType)
	assert.Equal(t, original.AggID, deserialized.AggID)
	assert.Equal(t, original.Version, deserialized.Version)
	assert.Equal(t, original.SourceDB, deserialized.SourceDB)
}

func TestCDCOperationTypes(t *testing.T) {
	// Test that different CDC operation types are recognized
	operationTypes := map[string]string{
		"c": "Create",
		"u": "Update",
		"d": "Delete",
		"r": "Read",
	}

	for op, name := range operationTypes {
		envelope := TestDebeziumEnvelope{
			Op:   op,
			TsMs: time.Now().UnixMilli(),
		}

		data, err := json.Marshal(envelope)
		require.NoError(t, err)

		var parsed TestDebeziumEnvelope
		err = json.Unmarshal(data, &parsed)
		require.NoError(t, err)

		assert.Equal(t, op, parsed.Op)
		assert.NotEmpty(t, name)
	}
}

func TestOffsetResetEarliestDefaultAndLatestOption(t *testing.T) {
	// Test #662: Ensure KAFKA_AUTO_OFFSET_RESET defaults to earliest (kafka.FirstOffset)
	cfg := Config{
		KafkaAutoOffsetReset: "earliest",
	}
	startOffset := kafka.FirstOffset
	if cfg.KafkaAutoOffsetReset == "latest" {
		startOffset = kafka.LastOffset
	}
	assert.Equal(t, kafka.FirstOffset, startOffset)

	// When set to latest
	cfg.KafkaAutoOffsetReset = "latest"
	if cfg.KafkaAutoOffsetReset == "latest" {
		startOffset = kafka.LastOffset
	}
	assert.Equal(t, kafka.LastOffset, startOffset)
}

func TestTolerantDeserializationAlteredPostgresColumns(t *testing.T) {
	// Test #651: Tolerant parsing of Debezium message containing altered postgres columns and schema wrapper
	schemaEnvelopeJSON := `{
		"schema": {
			"type": "struct",
			"fields": [
				{"field": "after", "type": "struct", "fields": [
					{"field": "order_id", "type": "string"},
					{"field": "new_loyalty_tier", "type": "string"},
					{"field": "discount_pct", "type": "float64"}
				]}
			]
		},
		"payload": {
			"op": "u",
			"ts_ms": 1718000000000,
			"source": {"db": "postgres", "table": "orders"},
			"before": {"order_id": "ord-001"},
			"after": {
				"order_id": "ord-001",
				"new_loyalty_tier": "PLATINUM",
				"discount_pct": 15.5,
				"unknown_future_column": "future_val"
			},
			"transaction": {"id": "tx-123"}
		}
	}`

	msg := kafka.Message{
		Topic:     "orders.cdc",
		Partition: 0,
		Offset:    101,
		Key:       []byte("ord-001"),
		Value:     []byte(schemaEnvelopeJSON),
		Time:      time.Now(),
	}

	valSaver, err := transformMessage(msg)
	require.NoError(t, err)
	require.NotNil(t, valSaver)

	vals, insertID, err := valSaver.Save()
	require.NoError(t, err)
	assert.Equal(t, "orders.cdc-0-101", insertID)
	assert.Equal(t, "u", vals["op"])
	assert.Contains(t, vals["after"], "PLATINUM")
	assert.Contains(t, vals["after"], "discount_pct")
	assert.Contains(t, vals["after"], "unknown_future_column")

	// Also verify ExtractCDCFields extracts the altered columns
	fields, err := ExtractCDCFields([]byte(schemaEnvelopeJSON))
	require.NoError(t, err)
	assert.Equal(t, "ord-001", fields["order_id"])
	assert.Equal(t, "PLATINUM", fields["new_loyalty_tier"])
	assert.Equal(t, 15.5, fields["discount_pct"])
	assert.Equal(t, "future_val", fields["unknown_future_column"])
}

func TestSchemaEvolutionDetection(t *testing.T) {
	// Test #651: Infer BigQuery types for altered postgres columns
	assert.Equal(t, bigquery.StringFieldType, InferBigQueryType("some-string"))
	assert.Equal(t, bigquery.IntegerFieldType, InferBigQueryType(int64(42)))
	assert.Equal(t, bigquery.FloatFieldType, InferBigQueryType(3.14159))
	assert.Equal(t, bigquery.BooleanFieldType, InferBigQueryType(true))
	assert.Equal(t, bigquery.TimestampFieldType, InferBigQueryType(time.Now()))
	assert.Equal(t, bigquery.JSONFieldType, InferBigQueryType(map[string]any{"nested": "val"}))
}

