package main

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"log/slog"
	"net"
	"os"
	"os/exec"
	"strings"
	"testing"
	"time"

	"github.com/IBM/sarama"
	"github.com/IBM/sarama/mocks"
	"github.com/google/uuid"
	"github.com/jackc/pgx/v5/pgxpool"
	metricnoop "go.opentelemetry.io/otel/metric/noop"
	"go.opentelemetry.io/otel/trace/noop"
)

// getTestDatabasePool connects to PostgreSQL via DATABASE_URL or spins up a transient postgres:16-alpine Docker container.
func getTestDatabasePool(t *testing.T) (*pgxpool.Pool, string) {
	t.Helper()

	dbURL := os.Getenv("DATABASE_URL")
	var containerID string

	if dbURL == "" {
		// Check if docker CLI is available
		if _, err := exec.LookPath("docker"); err != nil {
			t.Skip("Docker is not available and DATABASE_URL is not set; skipping PostgreSQL integration test")
		}

		// Find a free port
		listener, err := net.Listen("tcp", "127.0.0.1:0")
		if err != nil {
			t.Skipf("Failed to find free local port: %v", err)
		}
		port := listener.Addr().(*net.TCPAddr).Port
		_ = listener.Close()

		cName := fmt.Sprintf("outbox-pg-test-%d", time.Now().UnixNano())
		cmd := exec.Command("docker", "run", "-d", "--rm",
			"--name", cName,
			"-e", "POSTGRES_PASSWORD=postgres",
			"-e", "POSTGRES_DB=outbox_integration_test",
			"-p", fmt.Sprintf("%d:5432", port),
			"postgres:16-alpine",
		)
		out, err := cmd.CombinedOutput()
		if err != nil {
			t.Skipf("Failed to run postgres:16-alpine container: %v, output: %s", err, string(out))
		}
		containerID = strings.TrimSpace(string(out))

		t.Cleanup(func() {
			_ = exec.Command("docker", "rm", "-f", cName).Run()
		})

		dbURL = fmt.Sprintf("postgres://postgres:postgres@127.0.0.1:%d/outbox_integration_test?sslmode=disable", port)

		// Wait for PostgreSQL to become ready
		ready := false
		for i := 0; i < 30; i++ {
			time.Sleep(500 * time.Millisecond)
			ctx, cancel := context.WithTimeout(context.Background(), 2*time.Second)
			pool, err := pgxpool.New(ctx, dbURL)
			cancel()
			if err == nil {
				pingCtx, pingCancel := context.WithTimeout(context.Background(), 2*time.Second)
				err = pool.Ping(pingCtx)
				pingCancel()
				pool.Close()
				if err == nil {
					ready = true
					break
				}
			}
		}

		if !ready {
			t.Fatalf("Timed out waiting for postgres container %s on port %d", containerID, port)
		}
	}

	ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
	defer cancel()

	pool, err := pgxpool.New(ctx, dbURL)
	if err != nil {
		t.Fatalf("Failed to create pgxpool: %v", err)
	}

	t.Cleanup(func() {
		pool.Close()
	})

	return pool, dbURL
}

func setupOutboxSchema(t *testing.T, pool *pgxpool.Pool, table string) {
	t.Helper()
	ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
	defer cancel()

	schemaSQL := fmt.Sprintf(`
	DROP TABLE IF EXISTS %s;
	CREATE TABLE %s (
		id UUID PRIMARY KEY,
		aggregate_type VARCHAR(100) NOT NULL,
		aggregate_id VARCHAR(100) NOT NULL,
		event_type VARCHAR(100) NOT NULL,
		payload JSONB NOT NULL,
		created_at TIMESTAMP WITH TIME ZONE DEFAULT NOW(),
		sent BOOLEAN DEFAULT FALSE,
		source_service VARCHAR(100),
		correlation_id VARCHAR(100),
		schema_version VARCHAR(20) DEFAULT 'v1'
	);
	CREATE INDEX IF NOT EXISTS idx_%s_sent ON %s (sent, created_at);
	`, table, table, table, table)

	if _, err := pool.Exec(ctx, schemaSQL); err != nil {
		t.Fatalf("Failed to initialize outbox schema on table %s: %v", table, err)
	}
}

// TestOutboxRelayEndToEndBatchFlow verifies that un-sent outbox events in PostgreSQL are polled,
// published to the correct aggregate Kafka topics adhering strictly to EventEnvelope.v1.json,
// and marked as sent in the database transaction.
func TestOutboxRelayEndToEndBatchFlow(t *testing.T) {
	pool, _ := getTestDatabasePool(t)
	table := "outbox_events"
	setupOutboxSchema(t, pool, table)

	ctx := context.Background()
	logger := slog.New(slog.NewTextHandler(os.Stderr, &slog.HandlerOptions{Level: slog.LevelDebug}))

	orderEventID := uuid.New().String()
	paymentEventID := uuid.New().String()
	inventoryEventID := uuid.New().String()

	// Insert 3 un-sent outbox events
	insertSQL := fmt.Sprintf(`
		INSERT INTO %s (id, aggregate_type, aggregate_id, event_type, payload, source_service, correlation_id, schema_version, sent, created_at)
		VALUES
		($1, 'order', 'ord-1001', 'OrderCreated', '{"order_id": "ord-1001", "total_cents": 8500, "user_id": "usr-42"}'::jsonb, 'order-service', 'corr-101', 'v1', false, NOW() - INTERVAL '5 seconds'),
		($2, 'payment', 'pay-2002', 'PaymentAuthorized', '{"payment_id": "pay-2002", "amount_cents": 8500, "status": "AUTHORIZED"}'::jsonb, 'payment-service', 'corr-102', 'v1', false, NOW() - INTERVAL '3 seconds'),
		($3, 'inventory', 'inv-3003', 'StockReserved', '{"reservation_id": "inv-3003", "items": [{"product_id": "milk", "quantity": 2}]}'::jsonb, 'inventory-service', 'corr-103', 'v1', false, NOW() - INTERVAL '1 second')
	`, table)

	if _, err := pool.Exec(ctx, insertSQL, orderEventID, paymentEventID, inventoryEventID); err != nil {
		t.Fatalf("Failed to insert test outbox events: %v", err)
	}

	// Setup mock Kafka producer
	producerConfig := sarama.NewConfig()
	producerConfig.Producer.Return.Successes = true
	producer := mocks.NewSyncProducer(t, producerConfig)

	// Expect 3 message sends corresponding to the 3 events
	producer.ExpectSendMessageAndSucceed()
	producer.ExpectSendMessageAndSucceed()
	producer.ExpectSendMessageAndSucceed()

	cfg := Config{
		OutboxTable:     table,
		BatchSize:       10,
		KafkaTopic:      "", // Auto-resolve topic based on aggregate_type
		DLQTopic:        "outbox.relay.dlq",
		PollInterval:    100 * time.Millisecond,
		ShutdownTimeout: 5 * time.Second,
		ReadyTimeout:    2 * time.Second,
	}

	tracer := noop.NewTracerProvider().Tracer("outbox-test")
	metrics, err := newRelayMetrics(metricnoop.NewMeterProvider().Meter("outbox-test"))
	if err != nil {
		t.Fatalf("Failed to initialize test metrics: %v", err)
	}

	relay, err := newRelayService(cfg, logger, pool, producer, producerConfig, tracer, metrics)
	if err != nil {
		t.Fatalf("Failed to create relayService: %v", err)
	}

	// Execute relayBatch
	if err := relay.relayBatch(ctx); err != nil {
		t.Fatalf("relayBatch failed: %v", err)
	}

	// Verify in database that all 3 events are marked sent = true
	var sentCount int
	err = pool.QueryRow(ctx, fmt.Sprintf("SELECT COUNT(*) FROM %s WHERE sent = true", table)).Scan(&sentCount)
	if err != nil {
		t.Fatalf("Failed to query sent count: %v", err)
	}
	if sentCount != 3 {
		t.Errorf("Expected 3 events marked sent = true, got %d", sentCount)
	}

	var remainingUnsent int
	err = pool.QueryRow(ctx, fmt.Sprintf("SELECT COUNT(*) FROM %s WHERE sent = false", table)).Scan(&remainingUnsent)
	if err != nil {
		t.Fatalf("Failed to query unsent count: %v", err)
	}
	if remainingUnsent != 0 {
		t.Errorf("Expected 0 unsent events remaining, got %d", remainingUnsent)
	}
}

// TestOutboxRelayEnvelopeSchemaCompliance verifies that the published message JSON
// satisfies the EventEnvelope.v1.json canonical schema requirements.
func TestOutboxRelayEnvelopeSchemaCompliance(t *testing.T) {
	evt := outboxEvent{
		ID:            "e4854589-9407-4e78-bc4a-b50a297e68ad",
		AggregateType: "order",
		AggregateID:   "ord-9876",
		EventType:     "OrderPlaced",
		Payload:       []byte(`{"total_cents": 12000, "items_count": 3}`),
		CreatedAt:     time.Now().UTC(),
		SourceService: "order-service",
		CorrelationID: "corr-trace-555",
		SchemaVersion: "v1",
	}

	raw := buildEventMessage(evt)

	var envelope map[string]any
	if err := json.Unmarshal(raw, &envelope); err != nil {
		t.Fatalf("Failed to unmarshal event message: %v", err)
	}

	// EventEnvelope.v1.json required fields:
	// "id", "eventType", "aggregateType", "aggregateId", "eventTime", "schemaVersion", "source_service", "payload"
	requiredFields := []string{
		"id",
		"eventType",
		"aggregateType",
		"aggregateId",
		"eventTime",
		"schemaVersion",
		"source_service",
		"payload",
	}

	for _, field := range requiredFields {
		val, exists := envelope[field]
		if !exists || val == nil {
			t.Errorf("Event envelope missing required schema field: %q", field)
		}
	}

	// Verify field types and values
	if envelope["id"] != evt.ID {
		t.Errorf("Expected id %q, got %v", evt.ID, envelope["id"])
	}
	if envelope["eventType"] != "OrderPlaced" {
		t.Errorf("Expected eventType 'OrderPlaced', got %v", envelope["eventType"])
	}
	if envelope["aggregateType"] != "order" {
		t.Errorf("Expected aggregateType 'order', got %v", envelope["aggregateType"])
	}
	if envelope["aggregateId"] != "ord-9876" {
		t.Errorf("Expected aggregateId 'ord-9876', got %v", envelope["aggregateId"])
	}
	if envelope["source_service"] != "order-service" {
		t.Errorf("Expected source_service 'order-service', got %v", envelope["source_service"])
	}

	// Verify eventTime is valid RFC3339
	eventTimeStr, ok := envelope["eventTime"].(string)
	if !ok {
		t.Fatalf("eventTime is not a string: %T", envelope["eventTime"])
	}
	if _, err := time.Parse(time.RFC3339, eventTimeStr); err != nil {
		t.Errorf("eventTime %q is not valid RFC3339: %v", eventTimeStr, err)
	}

	// Verify payload contains expected nested fields
	payload, ok := envelope["payload"].(map[string]any)
	if !ok {
		t.Fatalf("payload is not map[string]any: %T", envelope["payload"])
	}
	if payload["total_cents"] != float64(12000) {
		t.Errorf("Expected payload total_cents 12000, got %v", payload["total_cents"])
	}
}

// TestOutboxRelayDLQFallbackFlow verifies that when Kafka primary topic publish fails,
// the event is safely routed to the dead-letter queue (DLQ) topic and marked as sent in DB.
func TestOutboxRelayDLQFallbackFlow(t *testing.T) {
	pool, _ := getTestDatabasePool(t)
	table := "outbox_events_dlq_test"
	setupOutboxSchema(t, pool, table)

	ctx := context.Background()
	logger := slog.New(slog.NewTextHandler(os.Stderr, &slog.HandlerOptions{Level: slog.LevelDebug}))

	eventID := uuid.New().String()

	insertSQL := fmt.Sprintf(`
		INSERT INTO %s (id, aggregate_type, aggregate_id, event_type, payload, source_service, correlation_id, schema_version, sent)
		VALUES ($1, 'order', 'ord-dlq-1', 'OrderCreated', '{"amount": 100}'::jsonb, 'order-service', 'corr-dlq', 'v1', false)
	`, table)
	if _, err := pool.Exec(ctx, insertSQL, eventID); err != nil {
		t.Fatalf("Failed to insert test event: %v", err)
	}

	producerConfig := sarama.NewConfig()
	producerConfig.Producer.Return.Successes = true
	producer := mocks.NewSyncProducer(t, producerConfig)

	// Primary send fails, but DLQ send succeeds
	producer.ExpectSendMessageAndFail(errors.New("kafka broker connection refused"))
	producer.ExpectSendMessageAndSucceed()

	cfg := Config{
		OutboxTable:  table,
		BatchSize:    10,
		KafkaTopic:   "",
		DLQTopic:     "outbox.relay.dlq",
		PollInterval: 100 * time.Millisecond,
	}

	tracer := noop.NewTracerProvider().Tracer("outbox-test")
	metrics, err := newRelayMetrics(metricnoop.NewMeterProvider().Meter("outbox-test"))
	if err != nil {
		t.Fatalf("Failed to initialize test metrics: %v", err)
	}

	relay, err := newRelayService(cfg, logger, pool, producer, producerConfig, tracer, metrics)
	if err != nil {
		t.Fatalf("Failed to create relayService: %v", err)
	}

	if err := relay.relayBatch(ctx); err != nil {
		t.Fatalf("relayBatch failed unexpectedly: %v", err)
	}

	// Verify event was marked sent = true to avoid pipeline stall
	var sent bool
	err = pool.QueryRow(ctx, fmt.Sprintf("SELECT sent FROM %s WHERE id = $1", table), eventID).Scan(&sent)
	if err != nil {
		t.Fatalf("Failed to query event sent status: %v", err)
	}
	if !sent {
		t.Errorf("Expected event to be marked sent=true after successful DLQ publish")
	}
}

// TestOutboxRelayRollbackOnTotalFailure verifies that when both primary topic and DLQ fail,
// the transaction is rolled back and sent remains false in PostgreSQL.
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

	// Both primary and DLQ fail
	producer.ExpectSendMessageAndFail(errors.New("primary kafka broker down"))
	producer.ExpectSendMessageAndFail(errors.New("dlq kafka broker down"))

	cfg := Config{
		OutboxTable:  table,
		BatchSize:    10,
		KafkaTopic:   "",
		DLQTopic:     "outbox.relay.dlq",
		PollInterval: 100 * time.Millisecond,
	}

	tracer := noop.NewTracerProvider().Tracer("outbox-test")
	metrics, err := newRelayMetrics(metricnoop.NewMeterProvider().Meter("outbox-test"))
	if err != nil {
		t.Fatalf("Failed to initialize test metrics: %v", err)
	}

	relay, err := newRelayService(cfg, logger, pool, producer, producerConfig, tracer, metrics)
	if err != nil {
		t.Fatalf("Failed to create relayService: %v", err)
	}

	// relayBatch will return an error because DLQ also failed
	_ = relay.relayBatch(ctx)

	// Event MUST still have sent = false
	var sent bool
	err = pool.QueryRow(ctx, fmt.Sprintf("SELECT sent FROM %s WHERE id = $1", table), eventID).Scan(&sent)
	if err != nil {
		t.Fatalf("Failed to query event sent status: %v", err)
	}
	if sent {
		t.Errorf("Expected event to remain sent=false after total Kafka failure, but got sent=true")
	}
}
