package main

import (
	"context"
	"encoding/json"
	"errors"
	"net"
	"runtime"
	"sync"
	"sync/atomic"
	"testing"
	"time"

	"github.com/IBM/sarama"
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

func newTestRelayService(brokers []string) *relayService {
	cfg := sarama.NewConfig()
	cfg.ClientID = "outbox-relay-test"
	cfg.Version = sarama.V2_5_0_0
	cfg.Net.DialTimeout = 5 * time.Second
	cfg.Net.ReadTimeout = 5 * time.Second
	cfg.Net.WriteTimeout = 5 * time.Second
	return &relayService{
		cfg: Config{
			KafkaBrokers:  brokers,
			KafkaClientID: "outbox-relay-test",
			ReadyTimeout:  5 * time.Second,
		},
		kafkaConfig: cfg,
	}
}

func TestCheckKafkaHonorsContextDeadlineWithoutGoroutineLeak(t *testing.T) {
	ln, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		t.Fatalf("failed to listen: %v", err)
	}
	defer ln.Close()

	var openConns atomic.Int64
	var wg sync.WaitGroup
	wg.Add(1)
	go func() {
		defer wg.Done()
		for {
			conn, err := ln.Accept()
			if err != nil {
				return
			}
			openConns.Add(1)
			wg.Add(1)
			go func(c net.Conn) {
				defer wg.Done()
				defer openConns.Add(-1)
				defer c.Close()
				buf := make([]byte, 256)
				for {
					if _, err := c.Read(buf); err != nil {
						return
					}
				}
			}(conn)
		}
	}()

	svc := newTestRelayService([]string{ln.Addr().String()})

	runtime.GC()
	baseGoroutines := runtime.NumGoroutine()

	const attempts = 8
	for i := 0; i < attempts; i++ {
		ctx, cancel := context.WithTimeout(context.Background(), 40*time.Millisecond)
		start := time.Now()
		err := svc.checkKafka(ctx)
		elapsed := time.Since(start)
		cancel()

		if !errors.Is(err, context.DeadlineExceeded) {
			t.Fatalf("attempt %d (elapsed=%v ctxErr=%v): expected context.DeadlineExceeded, got %v", i, elapsed, ctx.Err(), err)
		}
		if elapsed > 300*time.Millisecond {
			t.Fatalf("attempt %d: checkKafka took %v, exceeded context deadline bound", i, elapsed)
		}
	}

	deadline := time.Now().Add(400 * time.Millisecond)
	for time.Now().Before(deadline) {
		if openConns.Load() == 0 && runtime.NumGoroutine() <= baseGoroutines+2 {
			break
		}
		time.Sleep(10 * time.Millisecond)
	}

	if open := openConns.Load(); open != 0 {
		t.Fatalf("expected 0 open broker connections after timeout, got %d", open)
	}
	if got := runtime.NumGoroutine(); got > baseGoroutines+2 {
		t.Fatalf("goroutine leak after checkKafka timeouts: baseline=%d current=%d", baseGoroutines, got)
	}

	_ = ln.Close()
	wg.Wait()
}

func TestCheckKafkaReturnsCanceledWithoutDialing(t *testing.T) {
	ln, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		t.Fatalf("failed to listen: %v", err)
	}
	defer ln.Close()

	var accepted atomic.Int64
	go func() {
		for {
			conn, err := ln.Accept()
			if err != nil {
				return
			}
			accepted.Add(1)
			_ = conn.Close()
		}
	}()

	svc := newTestRelayService([]string{ln.Addr().String()})
	ctx, cancel := context.WithCancel(context.Background())
	cancel()

	if err := svc.checkKafka(ctx); !errors.Is(err, context.Canceled) {
		t.Fatalf("expected context.Canceled, got %v", err)
	}
	if n := accepted.Load(); n != 0 {
		t.Fatalf("expected 0 dials on canceled context, got %d", n)
	}
}

func TestCheckKafkaHealthyMockBroker(t *testing.T) {
	broker := sarama.NewMockBroker(t, 1)
	defer broker.Close()

	metadata := sarama.NewMockMetadataResponse(t).
		SetBroker(broker.Addr(), broker.BrokerID()).
		SetController(broker.BrokerID())
	broker.SetHandlerByMap(map[string]sarama.MockResponse{
		"ApiVersionsRequest": sarama.NewMockApiVersionsResponse(t),
		"MetadataRequest":    metadata,
	})

	svc := newTestRelayService([]string{broker.Addr()})
	ctx, cancel := context.WithTimeout(context.Background(), 2*time.Second)
	defer cancel()

	if err := svc.checkKafka(ctx); err != nil {
		t.Fatalf("expected healthy mock broker to pass checkKafka, got %v", err)
	}
}
