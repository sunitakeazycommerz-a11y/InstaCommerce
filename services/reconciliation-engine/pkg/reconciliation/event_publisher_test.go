package reconciliation

import (
	"context"
	"encoding/json"
	"io"
	"log/slog"
	"testing"
	"time"

	"github.com/instacommerce/go-shared/pkg/correlation"
	"github.com/segmentio/kafka-go"
	"github.com/stretchr/testify/assert"
	"github.com/stretchr/testify/require"
)

type stubKafkaWriter struct {
	messages []kafka.Message
}

func (s *stubKafkaWriter) WriteMessages(_ context.Context, msgs ...kafka.Message) error {
	s.messages = append(s.messages, msgs...)
	return nil
}

func (s *stubKafkaWriter) Close() error { return nil }

func TestEventEnvelope_CanonicalADR004Fields(t *testing.T) {
	ts := time.Date(2026, 11, 13, 5, 30, 0, 0, time.UTC)
	env := EventEnvelope{
		EventID:       "evt-123",
		EventType:     "ReconciliationCompleted",
		AggregateID:   "run-42",
		AggregateType: "ReconciliationRun",
		SchemaVersion: "1.0.0",
		SourceService: "reconciliation-engine",
		CorrelationID: "corr-99",
		Timestamp:     ts,
		Payload:       map[string]any{"total_mismatches": 2},
	}

	raw, err := json.Marshal(env)
	require.NoError(t, err)

	var decoded map[string]any
	require.NoError(t, json.Unmarshal(raw, &decoded))

	requiredCamelKeys := []string{
		"eventId",
		"eventType",
		"aggregateId",
		"aggregateType",
		"schemaVersion",
		"sourceService",
		"correlationId",
		"timestamp",
		"payload",
	}
	assert.Len(t, decoded, len(requiredCamelKeys))
	for _, key := range requiredCamelKeys {
		assert.Contains(t, decoded, key, "ADR-004 requires canonical camelCase field %q", key)
	}

	forbiddenSnakeKeys := []string{
		"event_id",
		"event_type",
		"aggregate_id",
		"aggregate_type",
		"schema_version",
		"source_service",
		"correlation_id",
	}
	for _, key := range forbiddenSnakeKeys {
		assert.NotContains(t, decoded, key, "ADR-004 forbids snake_case field %q", key)
	}
}

func TestEventPublisher_PublishesCanonicalADR004Messages(t *testing.T) {
	writer := &stubKafkaWriter{}
	pub := newEventPublisherWithWriter(writer, "reconciliation.events", slog.New(slog.NewTextHandler(io.Discard, nil)))

	ctxWithCorr := context.WithValue(context.Background(), correlation.ContextKey, "corr-abc-123")
	mismatch := MismatchDetail{
		TransactionID: "txn-99",
		LedgerAmount:  "1500",
		PSPAmount:     "1000",
		Reason:        "amount_mismatch_manual_review",
		AutoFixed:     false,
		ManualReview:  true,
	}

	require.NoError(t, pub.PublishMismatchEvent(ctxWithCorr, 7, mismatch))
	require.NoError(t, pub.PublishDiscrepancyAlert(context.Background(), 7, mismatch, 500))
	require.NoError(t, pub.PublishCompletionEvent(ctxWithCorr, 7, 1, 0, 1))

	require.Len(t, writer.messages, 3)

	expectedTypes := []string{
		"ReconciliationMismatchFound",
		"ReconciliationDiscrepancyAlert",
		"ReconciliationCompleted",
	}
	expectedCorrs := []string{"corr-abc-123", "run-7", "corr-abc-123"}

	for i, msg := range writer.messages {
		var envelope map[string]any
		require.NoError(t, json.Unmarshal(msg.Value, &envelope))
		assert.Equal(t, expectedTypes[i], envelope["eventType"])
		assert.Equal(t, "run-7", envelope["aggregateId"])
		assert.Equal(t, "ReconciliationRun", envelope["aggregateType"])
		assert.Equal(t, "1.0.0", envelope["schemaVersion"])
		assert.Equal(t, "reconciliation-engine", envelope["sourceService"])
		assert.Equal(t, expectedCorrs[i], envelope["correlationId"])
		assert.NotEmpty(t, envelope["eventId"])
		assert.NotEmpty(t, envelope["payload"])
		tsStr, ok := envelope["timestamp"].(string)
		require.True(t, ok)
		_, parseErr := time.Parse(time.RFC3339Nano, tsStr)
		assert.NoError(t, parseErr)
	}
}
