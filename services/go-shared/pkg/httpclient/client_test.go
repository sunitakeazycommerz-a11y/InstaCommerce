package httpclient

import (
	"context"
	"net/http"
	"net/http/httptest"
	"sync/atomic"
	"testing"
	"time"

	"github.com/instacommerce/go-shared/pkg/correlation"
	"go.opentelemetry.io/otel"
	"go.opentelemetry.io/otel/propagation"
	sdktrace "go.opentelemetry.io/otel/sdk/trace"
)

func TestTraceContextAndCorrelationPropagation(t *testing.T) {
	// Set up TracerProvider and TextMapPropagator
	tp := sdktrace.NewTracerProvider()
	otel.SetTracerProvider(tp)
	otel.SetTextMapPropagator(propagation.NewCompositeTextMapPropagator(
		propagation.TraceContext{},
		propagation.Baggage{},
	))
	defer func() { _ = tp.Shutdown(context.Background()) }()

	var receivedTraceparent string
	var receivedCorrelationID string

	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		receivedTraceparent = r.Header.Get("traceparent")
		receivedCorrelationID = r.Header.Get(correlation.Header)
		w.WriteHeader(http.StatusOK)
		_, _ = w.Write([]byte(`{"status":"ok"}`))
	}))
	defer server.Close()

	client := NewClient(WithTimeout(2 * time.Second))

	// Create context with active trace and correlation ID
	tracer := tp.Tracer("test-tracer")
	ctx, span := tracer.Start(context.Background(), "test-span")
	defer span.End()

	ctx = context.WithValue(ctx, correlation.ContextKey, "corr-test-12345")

	req, err := http.NewRequestWithContext(ctx, http.MethodGet, server.URL, nil)
	if err != nil {
		t.Fatalf("failed to create request: %v", err)
	}

	resp, err := client.Do(ctx, req)
	if err != nil {
		t.Fatalf("client.Do failed: %v", err)
	}
	defer resp.Body.Close()

	if resp.StatusCode != http.StatusOK {
		t.Errorf("expected 200 OK, got %d", resp.StatusCode)
	}

	if receivedTraceparent == "" {
		t.Errorf("expected traceparent header to be propagated, got empty")
	}

	if receivedCorrelationID != "corr-test-12345" {
		t.Errorf("expected X-Correlation-ID to be 'corr-test-12345', got '%s'", receivedCorrelationID)
	}
}

func TestCircuitBreakerTripsAndRecovers(t *testing.T) {
	var attempts int32
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		curr := atomic.AddInt32(&attempts, 1)
		if curr <= 2 {
			w.WriteHeader(http.StatusInternalServerError)
			return
		}
		w.WriteHeader(http.StatusOK)
	}))
	defer server.Close()

	client := NewClient(
		WithMaxRetries(0),
		WithCircuitBreakerThreshold(2),
		WithCircuitBreakerResetTimeout(50*time.Millisecond),
	)

	ctx := context.Background()

	// Attempt 1: 500 fail
	_, _ = client.Get(ctx, server.URL)
	// Attempt 2: 500 fail -> trips breaker
	_, _ = client.Get(ctx, server.URL)

	// Attempt 3: breaker is open -> rejected without hitting server
	_, err := client.Get(ctx, server.URL)
	if err == nil {
		t.Fatalf("expected circuit breaker open error, got nil")
	}

	// Wait for reset timeout
	time.Sleep(60 * time.Millisecond)

	// Attempt 4: half-open probe succeeds
	resp, err := client.Get(ctx, server.URL)
	if err != nil {
		t.Fatalf("expected request to succeed after half-open transition, got: %v", err)
	}
	defer resp.Body.Close()

	if resp.StatusCode != http.StatusOK {
		t.Errorf("expected 200 OK, got %d", resp.StatusCode)
	}
}

func TestDNSCacheLookupAndExpiration(t *testing.T) {
	cache := NewDNSCache(50 * time.Millisecond)
	ctx := context.Background()

	// Initial lookup
	ips1, err := cache.LookupHost(ctx, "localhost")
	if err != nil {
		t.Fatalf("LookupHost failed: %v", err)
	}
	if len(ips1) == 0 {
		t.Fatalf("expected at least one IP for localhost, got 0")
	}

	// Immediate second lookup should hit cache
	ips2, err := cache.LookupHost(ctx, "localhost")
	if err != nil {
		t.Fatalf("cached LookupHost failed: %v", err)
	}
	if len(ips2) != len(ips1) {
		t.Errorf("expected %d IPs from cache, got %d", len(ips1), len(ips2))
	}

	// Wait for TTL to expire (50ms)
	time.Sleep(60 * time.Millisecond)

	// Third lookup should refresh cache
	ips3, err := cache.LookupHost(ctx, "localhost")
	if err != nil {
		t.Fatalf("refreshed LookupHost failed: %v", err)
	}
	if len(ips3) == 0 {
		t.Fatalf("expected at least one IP after refresh, got 0")
	}
}

func TestHTTPClientWithDNSCacheAndIdleTimeout(t *testing.T) {
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.WriteHeader(http.StatusOK)
		_, _ = w.Write([]byte(`{"status":"healthy"}`))
	}))
	defer server.Close()

	client := NewClient(
		WithDNSCacheTTL(100*time.Millisecond),
		WithIdleConnTimeout(15*time.Second),
		WithTimeout(2*time.Second),
	)

	ctx := context.Background()
	resp, err := client.Get(ctx, server.URL)
	if err != nil {
		t.Fatalf("client.Get failed: %v", err)
	}
	defer resp.Body.Close()

	if resp.StatusCode != http.StatusOK {
		t.Errorf("expected 200 OK, got %d", resp.StatusCode)
	}
}

