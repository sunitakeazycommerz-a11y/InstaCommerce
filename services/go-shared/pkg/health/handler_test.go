package health

import (
	"context"
	"encoding/json"
	"errors"
	"net/http"
	"net/http/httptest"
	"testing"
)

func TestLiveHandler_ReturnsAlive(t *testing.T) {
	h := NewHandler()
	req := httptest.NewRequest(http.MethodGet, "/health/live", nil)
	rr := httptest.NewRecorder()

	h.LiveHandler().ServeHTTP(rr, req)

	if rr.Code != http.StatusOK {
		t.Fatalf("expected status 200, got %d", rr.Code)
	}

	var resp map[string]string
	if err := json.Unmarshal(rr.Body.Bytes(), &resp); err != nil {
		t.Fatalf("failed to decode response: %v", err)
	}
	if resp["status"] != "alive" {
		t.Fatalf("expected status 'alive', got %q", resp["status"])
	}
}

func TestReadyHandler_TogglesReadiness(t *testing.T) {
	h := NewHandler()

	// Initial state: not ready
	req := httptest.NewRequest(http.MethodGet, "/health/ready", nil)
	rr := httptest.NewRecorder()
	h.ReadyHandler().ServeHTTP(rr, req)

	if rr.Code != http.StatusServiceUnavailable {
		t.Fatalf("expected status 503 when not ready, got %d", rr.Code)
	}

	// Set ready
	h.SetReady(true)
	rr2 := httptest.NewRecorder()
	h.ReadyHandler().ServeHTTP(rr2, req)

	if rr2.Code != http.StatusOK {
		t.Fatalf("expected status 200 when ready, got %d", rr2.Code)
	}
}

func TestHealthHandler_AllChecksPass(t *testing.T) {
	h := NewHandler(
		Check{
			Name: "postgres",
			Check: func(ctx context.Context) error {
				return nil
			},
		},
		Check{
			Name: "redis",
			Check: func(ctx context.Context) error {
				return nil
			},
		},
	)

	req := httptest.NewRequest(http.MethodGet, "/health", nil)
	rr := httptest.NewRecorder()
	h.HealthHandler().ServeHTTP(rr, req)

	if rr.Code != http.StatusOK {
		t.Fatalf("expected status 200, got %d", rr.Code)
	}

	var resp healthResponse
	if err := json.Unmarshal(rr.Body.Bytes(), &resp); err != nil {
		t.Fatalf("failed to parse json response: %v", err)
	}
	if resp.Status != "ok" {
		t.Fatalf("expected status 'ok', got %q", resp.Status)
	}
	if len(resp.Checks) != 2 {
		t.Fatalf("expected 2 checks, got %d", len(resp.Checks))
	}
}

func TestHealthHandler_FailingCheckReturnsDegraded(t *testing.T) {
	h := NewHandler(
		Check{
			Name: "postgres",
			Check: func(ctx context.Context) error {
				return errors.New("connection timeout")
			},
		},
	)

	req := httptest.NewRequest(http.MethodGet, "/health", nil)
	rr := httptest.NewRecorder()
	h.HealthHandler().ServeHTTP(rr, req)

	if rr.Code != http.StatusServiceUnavailable {
		t.Fatalf("expected status 503, got %d", rr.Code)
	}

	var resp healthResponse
	if err := json.Unmarshal(rr.Body.Bytes(), &resp); err != nil {
		t.Fatalf("failed to parse json response: %v", err)
	}
	if resp.Status != "degraded" {
		t.Fatalf("expected status 'degraded', got %q", resp.Status)
	}
}
