package main

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"log/slog"
	"os"
	"testing"
	"time"

	"github.com/IBM/sarama"
	"github.com/IBM/sarama/mocks"
	"github.com/google/uuid"
	metricnoop "go.opentelemetry.io/otel/metric/noop"
	"go.opentelemetry.io/otel/trace/noop"
)

func TestValidateOutboxEvent_PartitionKeySchemaVersionAndPayload(t *testing.T) {
	tests := []struct {
		name         string
		evt          outboxEvent
		wantKey      string
		wantSchema   string
		wantErr      bool
		wantCategory string
	}{
		{
			name: "valid v1 envelope with aggregate partition key",
			evt: outboxEvent{
				ID:            "evt-1",
				AggregateID:   " ord-99 ",
				SchemaVersion: "v1",
				Payload:       []byte(`{"total":100}`),
			},
			wantKey:    "ord-99",
			wantSchema: "v1",
		},
		{
			name: "valid semver schema version",
			evt: outboxEvent{
				ID:            "evt-2",
				AggregateID:   "pay-42",
				SchemaVersion: "2.1.0",
				Payload:       []byte(`{"ok":true}`),
			},
			wantKey:    "pay-42",
			wantSchema: "2.1.0",
		},
		{
			name: "blank partition key rejected",
			evt: outboxEvent{
				ID:            "evt-3",
				AggregateID:   "   ",
				SchemaVersion: "v1",
				Payload:       []byte(`{}`),
			},
			wantErr:      true,
			wantCategory: "partition_key_missing",
		},
		{
			name: "invalid schema version rejected",
			evt: outboxEvent{
				ID:            "evt-4",
				AggregateID:   "ord-4",
				SchemaVersion: "v0",
				Payload:       []byte(`{}`),
			},
			wantErr:      true,
			wantCategory: "schema_version_invalid",
		},
		{
			name: "malformed JSON payload rejected",
			evt: outboxEvent{
				ID:            "evt-5",
				AggregateID:   "ord-5",
				SchemaVersion: "v1",
				Payload:       []byte(`{broken`),
			},
			wantErr:      true,
			wantCategory: "payload_invalid",
		},
	}

	for _, tc := range tests {
		t.Run(tc.name, func(t *testing.T) {
			key, ver, err := validateOutboxEvent(tc.evt)
			if tc.wantErr {
				if err == nil {
					t.Fatal("expected error, got nil")
				}
				if got := classifyRelayError(err); got != tc.wantCategory {
					t.Fatalf("category=%q want=%q", got, tc.wantCategory)
				}
				return
			}
			if err != nil {
				t.Fatalf("unexpected error: %v", err)
			}
			if key != tc.wantKey || ver != tc.wantSchema {
				t.Fatalf("got (%q, %q), want (%q, %q)", key, ver, tc.wantKey, tc.wantSchema)
			}
		})
	}
}

func TestBuildEnrichedDLQMetadataAndHeaders(t *testing.T) {
	now := time.Date(2026, 11, 19, 12, 0, 0, 0, time.UTC)
	evt := outboxEvent{
		ID:            "evt-dlq-1",
		AggregateType: "Order",
		AggregateID:   "ord-100",
		EventType:     "OrderCreated",
		Payload:       []byte(`{"amount":500}`),
		CreatedAt:     now.Add(-time.Minute),
		SourceService: "order-service",
		CorrelationID: "corr-dlq-1",
		SchemaVersion: "v2",
	}
	cause := fmt.Errorf("%w: %q", errInvalidSchemaVersion, "bad-ver")
	raw := buildEnrichedDLQMessage(evt, "orders.events", "ord-100", 3, cause, now)

	var body map[string]any
	if err := json.Unmarshal(raw, &body); err != nil {
		t.Fatalf("unmarshal DLQ envelope: %v", err)
	}
	if body["partitionKey"] != "ord-100" || body["errorCategory"] != "schema_version_invalid" || body["attempts"] != float64(3) || body["quarantinedAt"] == "" {
		t.Fatalf("missing quarantine metadata in DLQ envelope: %+v", body)
	}

	headers := buildDLQHeaders(evt, "orders.events", "trace-1", "ord-100", "v2", 3, cause, now)
	hm := make(map[string]string, len(headers))
	counts := make(map[string]int, len(headers))
	for _, h := range headers {
		k := string(h.Key)
		hm[k] = string(h.Value)
		counts[k]++
	}
	for _, k := range []string{"partition_key", "error_category", "quarantine_attempts", "quarantined_at", "original_topic"} {
		if hm[k] == "" {
			t.Fatalf("expected header %q to be populated", k)
		}
	}
	for _, k := range []string{"partition_key", "schema_version"} {
		if counts[k] != 1 {
			t.Fatalf("expected header %q once, got %d", k, counts[k])
		}
	}
}

func TestOutboxRelayRollbackOnTotalFailure(t *testing.T) {
	pool, _ := getTestDatabasePool(t)
	table := "outbox_events_rollback_test"
	setupOutboxSchema(t, pool, table)

	ctx := context.Background()
	logger := slog.New(slog.NewTextHandler(os.Stderr, &slog.HandlerOptions{Level: slog.LevelDebug}))

	eventID := uuid.New().String()
	insertSQL := fmt.Sprintf(`
		INSERT INTO %s (id, aggregate_type, aggregate_id, event_type, payload, source_service, correlation_id, schema_version, sent)
		VALUES ($1, 'order', 'ord-fail-1', 'OrderCreated', '{"amount": 200}'::jsonb, 'order-service', 'corr-fail', 'v1', false)
	`, table)
	if _, err := pool.Exec(ctx, insertSQL, eventID); err != nil {
		t.Fatalf("Failed to insert test event: %v", err)
	}

	producerConfig := sarama.NewConfig()
	producerConfig.Producer.Return.Successes = true
	producer := mocks.NewSyncProducer(t, producerConfig)
	producer.ExpectSendMessageAndFail(errors.New("primary kafka broker down"))
	producer.ExpectSendMessageAndFail(errors.New("dlq kafka broker down"))

	cfg := Config{OutboxTable: table, BatchSize: 10, DLQTopic: "outbox.relay.dlq", PollInterval: 100 * time.Millisecond}
	tracer := noop.NewTracerProvider().Tracer("outbox-test")
	metrics, err := newRelayMetrics(metricnoop.NewMeterProvider().Meter("outbox-test"))
	if err != nil {
		t.Fatalf("Failed to initialize test metrics: %v", err)
	}

	relay, err := newRelayService(cfg, logger, pool, producer, producerConfig, tracer, metrics)
	if err != nil {
		t.Fatalf("Failed to create relayService: %v", err)
	}

	_ = relay.relayBatch(ctx)

	var sent bool
	if err := pool.QueryRow(ctx, fmt.Sprintf("SELECT sent FROM %s WHERE id = $1", table), eventID).Scan(&sent); err != nil {
		t.Fatalf("Failed to query event sent status: %v", err)
	}
	if sent {
		t.Errorf("Expected event to remain sent=false after total Kafka failure, but got sent=true")
	}
}
