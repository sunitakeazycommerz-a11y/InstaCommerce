package main

import (
	"context"
	"encoding/json"
	"errors"
	"strings"
	"sync"
	"testing"
	"time"
	"unicode/utf8"

	"github.com/IBM/sarama"
	"github.com/jackc/pgx/v5"
	"github.com/jackc/pgx/v5/pgconn"
	"go.opentelemetry.io/otel/trace"
)

type fakeRows struct {
	pgx.Rows
	events  []outboxEvent
	idx     int
	scanErr error
	rowsErr error
	closed  bool
	onClose func()
}

func (r *fakeRows) Next() bool {
	if r.closed || r.idx >= len(r.events) {
		return false
	}
	r.idx++
	return true
}

func (r *fakeRows) Scan(dest ...any) error {
	if r.scanErr != nil {
		return r.scanErr
	}
	e := r.events[r.idx-1]
	*dest[0].(*string) = e.ID
	*dest[1].(*string) = e.AggregateType
	*dest[2].(*string) = e.AggregateID
	*dest[3].(*string) = e.EventType
	*dest[4].(*[]byte) = e.Payload
	*dest[5].(*time.Time) = e.CreatedAt
	*dest[6].(*string) = e.SourceService
	*dest[7].(*string) = e.CorrelationID
	*dest[8].(*string) = e.SchemaVersion
	return nil
}

func (r *fakeRows) Err() error {
	return r.rowsErr
}

func (r *fakeRows) Close() {
	if !r.closed {
		r.closed = true
		if r.onClose != nil {
			r.onClose()
		}
	}
}

type fakeTx struct {
	pgx.Tx
	rows          *fakeRows
	queryErr      error
	execErr       error
	commitErr     error
	updatedIDs    []string
	committed     bool
	rolledBack    bool
	execWhileOpen bool
	commitCalls   int
	rollbackCalls int
}

func (t *fakeTx) Query(ctx context.Context, sql string, args ...any) (pgx.Rows, error) {
	if t.queryErr != nil {
		return nil, t.queryErr
	}
	return t.rows, nil
}

func (t *fakeTx) Exec(ctx context.Context, sql string, args ...any) (pgconn.CommandTag, error) {
	if t.rows != nil && !t.rows.closed {
		t.execWhileOpen = true
	}
	if t.execErr != nil {
		return pgconn.CommandTag{}, t.execErr
	}
	if len(args) > 0 {
		if id, ok := args[0].(string); ok {
			t.updatedIDs = append(t.updatedIDs, id)
		}
	}
	return pgconn.NewCommandTag("UPDATE 1"), nil
}

func (t *fakeTx) Commit(ctx context.Context) error {
	t.commitCalls++
	if t.commitErr != nil {
		return t.commitErr
	}
	t.committed = true
	return nil
}

func (t *fakeTx) Rollback(ctx context.Context) error {
	t.rollbackCalls++
	t.rolledBack = true
	return nil
}

type fakeOutboxDB struct {
	tx       pgx.Tx
	beginErr error
	pingErr  error
}

func (db *fakeOutboxDB) Begin(ctx context.Context) (pgx.Tx, error) {
	if db.beginErr != nil {
		return nil, db.beginErr
	}
	return db.tx, nil
}

func (db *fakeOutboxDB) Ping(ctx context.Context) error {
	return db.pingErr
}

type recordingProducer struct {
	sarama.SyncProducer
	mu   sync.Mutex
	msgs []*sarama.ProducerMessage
	send func(msg *sarama.ProducerMessage, callIndex int) error
}

func (p *recordingProducer) SendMessage(msg *sarama.ProducerMessage) (int32, int64, error) {
	p.mu.Lock()
	idx := len(p.msgs)
	p.msgs = append(p.msgs, msg)
	p.mu.Unlock()
	if p.send != nil {
		if err := p.send(msg, idx); err != nil {
			return 0, 0, err
		}
	}
	return 0, int64(idx + 1), nil
}

func headerMap(headers []sarama.RecordHeader) map[string]string {
	out := make(map[string]string, len(headers))
	for _, h := range headers {
		out[string(h.Key)] = string(h.Value)
	}
	return out
}

func TestBuildDLQHeadersAndMessageEdgeCases(t *testing.T) {
	now := time.Date(2026, 11, 20, 8, 30, 0, 0, time.UTC)
	longErr := errors.New(strings.Repeat("é", 300))

	evt := outboxEvent{
		ID:            "evt-edge-1",
		AggregateType: "rider_geofence",
		AggregateID:   "rider-7",
		EventType:     "RiderGeofenceTransition",
		Payload:       []byte("not-json"),
		CreatedAt:     now.Add(-2 * time.Minute),
		SourceService: "location-ingestion-service",
		CorrelationID: "corr-edge-1",
		SchemaVersion: "  v2  ",
	}

	raw := buildEnrichedDLQMessage(evt, "rider.geofence.events", "rider-7", 0, nil, now)
	var body map[string]any
	if err := json.Unmarshal(raw, &body); err != nil {
		t.Fatalf("unmarshal DLQ body: %v", err)
	}
	if body["error"] != "unknown error" {
		t.Fatalf("expected unknown error fallback, got %v", body["error"])
	}
	if body["errorCategory"] != "publish_failure" {
		t.Fatalf("expected publish_failure category, got %v", body["errorCategory"])
	}
	if body["attempts"] != float64(1) {
		t.Fatalf("expected attempts clamped to 1, got %v", body["attempts"])
	}
	if body["schemaVersion"] != "v2" {
		t.Fatalf("expected trimmed schemaVersion v2, got %v", body["schemaVersion"])
	}
	if body["payload"] != "not-json" {
		t.Fatalf("expected non-JSON payload preserved as string, got %v", body["payload"])
	}

	headers := headerMap(buildDLQHeaders(evt, "rider.geofence.events", "", "rider-7", "", -5, longErr, now))
	if headers["schema_version"] != "v2" {
		t.Fatalf("expected fallback schema_version v2, got %q", headers["schema_version"])
	}
	if headers["quarantine_attempts"] != "1" {
		t.Fatalf("expected clamped quarantine_attempts 1, got %q", headers["quarantine_attempts"])
	}
	reason := headers["dlq_reason"]
	if len(reason) > 512 {
		t.Fatalf("expected dlq_reason <= 512 bytes, got %d", len(reason))
	}
	if !utf8.ValidString(reason) {
		t.Fatalf("expected valid UTF-8 truncated dlq_reason")
	}

	evtNoSchema := evt
	evtNoSchema.SchemaVersion = ""
	noSchemaHeaders := headerMap(buildDLQHeaders(evtNoSchema, "rider.geofence.events", "", "rider-7", "", 2, nil, now))
	if noSchemaHeaders["schema_version"] != "unknown" {
		t.Fatalf("expected unknown schema_version fallback, got %q", noSchemaHeaders["schema_version"])
	}
	if noSchemaHeaders["dlq_reason"] != "unknown error" {
		t.Fatalf("expected nil err dlq_reason 'unknown error', got %q", noSchemaHeaders["dlq_reason"])
	}
}

func TestBuildEventMessageTrimsSchemaVersion(t *testing.T) {
	evt := outboxEvent{
		ID:            "evt-trim-1",
		AggregateType: "pick_batch",
		AggregateID:   "batch-42",
		EventType:     "PickBatchCompleted",
		Payload:       []byte(`{"batch_id":"batch-42","items":4}`),
		CreatedAt:     time.Date(2026, 11, 20, 9, 0, 0, 0, time.UTC),
		SourceService: "fulfillment-service",
		SchemaVersion: "  2.0.0  ",
	}
	raw := buildEventMessage(evt)
	var envelope map[string]any
	if err := json.Unmarshal(raw, &envelope); err != nil {
		t.Fatalf("unmarshal envelope: %v", err)
	}
	if envelope["schemaVersion"] != "2.0.0" || envelope["schema_version"] != "2.0.0" {
		t.Fatalf("expected trimmed schemaVersion 2.0.0, got %v / %v", envelope["schemaVersion"], envelope["schema_version"])
	}
}

func TestLoadConfigValidation(t *testing.T) {
	setRequired := func(t *testing.T) {
		t.Helper()
		t.Setenv("DATABASE_URL", "postgres://postgres:postgres@localhost:5432/outbox?sslmode=disable")
		t.Setenv("KAFKA_BROKERS", "localhost:9092, kafka-2:9092")
	}

	t.Run("requires database url", func(t *testing.T) {
		t.Setenv("DATABASE_URL", "")
		t.Setenv("POSTGRES_DSN", "")
		t.Setenv("KAFKA_BROKERS", "localhost:9092")
		if _, err := loadConfig(); err == nil {
			t.Fatal("expected error when DATABASE_URL and POSTGRES_DSN are missing")
		}
	})

	t.Run("requires kafka brokers", func(t *testing.T) {
		t.Setenv("DATABASE_URL", "postgres://postgres@localhost:5432/db")
		t.Setenv("KAFKA_BROKERS", " , ")
		if _, err := loadConfig(); err == nil {
			t.Fatal("expected error when KAFKA_BROKERS has no valid broker")
		}
	})

	t.Run("rejects unsafe outbox table", func(t *testing.T) {
		setRequired(t)
		t.Setenv("OUTBOX_TABLE", "outbox_events; DROP TABLE users")
		if _, err := loadConfig(); err == nil {
			t.Fatal("expected error for unsafe OUTBOX_TABLE")
		}
	})

	t.Run("rejects non-positive batch size", func(t *testing.T) {
		setRequired(t)
		t.Setenv("OUTBOX_BATCH_SIZE", "0")
		if _, err := loadConfig(); err == nil {
			t.Fatal("expected error for zero OUTBOX_BATCH_SIZE")
		}
	})

	t.Run("parses defaults and overrides", func(t *testing.T) {
		setRequired(t)
		t.Setenv("LOG_LEVEL", "warn")
		t.Setenv("DLQ_TOPIC", "custom.dlq")
		cfg, err := loadConfig()
		if err != nil {
			t.Fatalf("unexpected error: %v", err)
		}
		if len(cfg.KafkaBrokers) != 2 || cfg.KafkaBrokers[1] != "kafka-2:9092" {
			t.Fatalf("unexpected KafkaBrokers: %v", cfg.KafkaBrokers)
		}
		if cfg.DLQTopic != "custom.dlq" {
			t.Fatalf("expected custom.dlq, got %q", cfg.DLQTopic)
		}
	})
}

func TestRelayBatchUnitPaths(t *testing.T) {
	baseCfg := Config{
		BatchSize:   10,
		MaxRetries:  2,
		OutboxTable: "outbox_events",
		DLQTopic:    "outbox.relay.dlq",
	}

	t.Run("nil dependencies return error", func(t *testing.T) {
		svc := &relayService{cfg: baseCfg}
		if err := svc.relayBatch(context.Background()); err == nil {
			t.Fatal("expected error when dependencies are nil")
		}
	})

	t.Run("happy path routes new topics and closes rows before update", func(t *testing.T) {
		events := []outboxEvent{
			{
				ID:            "evt-geo",
				AggregateType: "rider_geofence",
				AggregateID:   "rider-1",
				EventType:     "RiderGeofenceTransition",
				Payload:       []byte(`{"rider_id":"rider-1","zone_id":"z-1"}`),
				CreatedAt:     time.Now().UTC().Add(-time.Second),
				SourceService: "location-ingestion-service",
				SchemaVersion: "v1",
			},
			{
				ID:            "evt-fail",
				AggregateType: "delivery_failure",
				AggregateID:   "del-9",
				EventType:     "DeliveryFailed",
				Payload:       []byte(`{"reason":"customer_unavailable"}`),
				CreatedAt:     time.Now().UTC().Add(-time.Second),
				SourceService: "fulfillment-service",
				SchemaVersion: "v1",
			},
		}
		rows := &fakeRows{events: events}
		tx := &fakeTx{rows: rows}
		producer := &recordingProducer{}
		svc, err := newRelayServiceWithDB(baseCfg, nil, &fakeOutboxDB{tx: tx}, producer, nil, trace.NewNoopTracerProvider().Tracer("test"), relayMetrics{})
		if err != nil {
			t.Fatalf("newRelayServiceWithDB: %v", err)
		}

		if err := svc.relayBatch(context.Background()); err != nil {
			t.Fatalf("relayBatch: %v", err)
		}
		if !rows.closed || tx.execWhileOpen {
			t.Fatalf("expected rows closed before tx.Exec; closed=%v execWhileOpen=%v", rows.closed, tx.execWhileOpen)
		}
		if !tx.committed || tx.rolledBack {
			t.Fatalf("expected tx committed; committed=%v rolledBack=%v", tx.committed, tx.rolledBack)
		}
		if len(tx.updatedIDs) != 2 {
			t.Fatalf("expected 2 sent updates, got %v", tx.updatedIDs)
		}
		if len(producer.msgs) != 2 {
			t.Fatalf("expected 2 published Kafka messages, got %d", len(producer.msgs))
		}
		if producer.msgs[0].Topic != "rider.geofence.events" {
			t.Fatalf("expected rider.geofence.events, got %q", producer.msgs[0].Topic)
		}
		if producer.msgs[1].Topic != "fulfillment.events" {
			t.Fatalf("expected fulfillment.events, got %q", producer.msgs[1].Topic)
		}
	})

	t.Run("validation failure quarantines to DLQ and marks sent", func(t *testing.T) {
		events := []outboxEvent{
			{
				ID:            "evt-bad-schema",
				AggregateType: "pick_batch",
				AggregateID:   "batch-7",
				EventType:     "PickBatchCreated",
				Payload:       []byte(`{"batch_id":"batch-7"}`),
				CreatedAt:     time.Now().UTC(),
				SourceService: "fulfillment-service",
				SchemaVersion: "invalid-ver",
			},
		}
		tx := &fakeTx{rows: &fakeRows{events: events}}
		producer := &recordingProducer{}
		svc, err := newRelayServiceWithDB(baseCfg, nil, &fakeOutboxDB{tx: tx}, producer, nil, trace.NewNoopTracerProvider().Tracer("test"), relayMetrics{})
		if err != nil {
			t.Fatalf("newRelayServiceWithDB: %v", err)
		}

		if err := svc.relayBatch(context.Background()); err != nil {
			t.Fatalf("relayBatch: %v", err)
		}
		if !tx.committed || len(tx.updatedIDs) != 1 || tx.updatedIDs[0] != "evt-bad-schema" {
			t.Fatalf("expected DLQ event marked sent and committed, updated=%v committed=%v", tx.updatedIDs, tx.committed)
		}
		if len(producer.msgs) != 1 || producer.msgs[0].Topic != baseCfg.DLQTopic {
			t.Fatalf("expected 1 DLQ message, got %+v", producer.msgs)
		}
		hdrs := headerMap(producer.msgs[0].Headers)
		if hdrs["original_topic"] != "fulfillment.events" || hdrs["error_category"] != "schema_version_invalid" || hdrs["schema_version"] != "invalid-ver" {
			t.Fatalf("unexpected DLQ headers: %+v", hdrs)
		}
	})

	t.Run("transient error retries then quarantines after MaxRetries", func(t *testing.T) {
		evt := outboxEvent{
			ID:            "evt-retry-1",
			AggregateType: "PickBatch",
			AggregateID:   "pb-1",
			EventType:     "PickBatchStarted",
			Payload:       []byte(`{"ok":true}`),
			CreatedAt:     time.Now().UTC(),
			SourceService: "fulfillment-service",
			SchemaVersion: "v1",
		}
		tx1 := &fakeTx{rows: &fakeRows{events: []outboxEvent{evt}}}
		db := &fakeOutboxDB{tx: tx1}
		producer := &recordingProducer{
			send: func(msg *sarama.ProducerMessage, callIndex int) error {
				if msg.Topic != baseCfg.DLQTopic {
					return errors.New("broker unavailable")
				}
				return nil
			},
		}
		svc, err := newRelayServiceWithDB(baseCfg, nil, db, producer, nil, trace.NewNoopTracerProvider().Tracer("test"), relayMetrics{})
		if err != nil {
			t.Fatalf("newRelayServiceWithDB: %v", err)
		}

		err = svc.relayBatch(context.Background())
		if err == nil {
			t.Fatal("expected error on first transient publish failure")
		}
		if len(tx1.updatedIDs) != 0 {
			t.Fatalf("expected no updated IDs on first retry, got %v", tx1.updatedIDs)
		}

		tx2 := &fakeTx{rows: &fakeRows{events: []outboxEvent{evt}}}
		db.tx = tx2
		if err := svc.relayBatch(context.Background()); err != nil {
			t.Fatalf("expected quarantine to succeed on attempt 2, got %v", err)
		}
		if len(tx2.updatedIDs) != 1 || !tx2.committed {
			t.Fatalf("expected event marked sent and tx committed on DLQ quarantine")
		}
		if len(producer.msgs) != 3 || producer.msgs[2].Topic != baseCfg.DLQTopic {
			t.Fatalf("expected 3rd producer call to target DLQ, got %+v", producer.msgs)
		}
	})

	t.Run("DLQ send failure stops batch without marking sent", func(t *testing.T) {
		evt := outboxEvent{
			ID:            "evt-dlq-fail",
			AggregateType: "geofence",
			AggregateID:   "",
			EventType:     "RiderGeofenceTransition",
			Payload:       []byte(`{}`),
			CreatedAt:     time.Now().UTC(),
			SchemaVersion: "v1",
		}
		tx := &fakeTx{rows: &fakeRows{events: []outboxEvent{evt}}}
		producer := &recordingProducer{
			send: func(msg *sarama.ProducerMessage, callIndex int) error {
				return errors.New("dlq unavailable")
			},
		}
		svc, err := newRelayServiceWithDB(baseCfg, nil, &fakeOutboxDB{tx: tx}, producer, nil, trace.NewNoopTracerProvider().Tracer("test"), relayMetrics{})
		if err != nil {
			t.Fatalf("newRelayServiceWithDB: %v", err)
		}

		if err := svc.relayBatch(context.Background()); err == nil {
			t.Fatal("expected error when DLQ send fails")
		}
		if len(tx.updatedIDs) != 0 {
			t.Fatalf("expected event not marked sent when DLQ send fails, got %v", tx.updatedIDs)
		}
	})
}
