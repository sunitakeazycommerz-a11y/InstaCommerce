package handler

import (
	"bytes"
	"encoding/json"
	"io"
	"log/slog"
	"net/http"
	"net/http/httptest"
	"testing"
	"time"

	"github.com/instacommerce/dispatch-optimizer-service/optimizer"
)

func newTestLogger() *slog.Logger {
	return slog.New(slog.NewTextHandler(io.Discard, nil))
}

func TestHandleAssignV2_MethodNotAllowed(t *testing.T) {
	solver := optimizer.NewSolver(optimizer.SolverConfig{})
	handler := HandleAssignV2(solver, newTestLogger())

	req := httptest.NewRequest(http.MethodGet, "/v2/optimize/assign", nil)
	rr := httptest.NewRecorder()

	handler.ServeHTTP(rr, req)

	if rr.Code != http.StatusMethodNotAllowed {
		t.Fatalf("expected 405 Method Not Allowed, got %d", rr.Code)
	}
	if allow := rr.Header().Get("Allow"); allow != "POST" {
		t.Fatalf("expected Allow: POST, got %q", allow)
	}
}

func TestHandleAssignV2_InvalidBody(t *testing.T) {
	solver := optimizer.NewSolver(optimizer.SolverConfig{})
	handler := HandleAssignV2(solver, newTestLogger())

	req := httptest.NewRequest(http.MethodPost, "/v2/optimize/assign", bytes.NewBufferString("not-valid-json"))
	rr := httptest.NewRecorder()

	handler.ServeHTTP(rr, req)

	if rr.Code != http.StatusBadRequest {
		t.Fatalf("expected 400 Bad Request, got %d", rr.Code)
	}
}

func TestHandleAssignV2_BothEmptyValidation(t *testing.T) {
	solver := optimizer.NewSolver(optimizer.SolverConfig{})
	handler := HandleAssignV2(solver, newTestLogger())

	body := V2AssignRequest{
		Riders: []optimizer.RiderState{},
		Orders: []optimizer.OrderRequest{},
	}
	data, _ := json.Marshal(body)

	req := httptest.NewRequest(http.MethodPost, "/v2/optimize/assign", bytes.NewReader(data))
	rr := httptest.NewRecorder()

	handler.ServeHTTP(rr, req)

	if rr.Code != http.StatusBadRequest {
		t.Fatalf("expected 400 Bad Request when both empty, got %d", rr.Code)
	}
}

func TestHandleAssignV2_Success(t *testing.T) {
	solver := optimizer.NewSolver(optimizer.SolverConfig{})
	handler := HandleAssignV2(solver, newTestLogger())

	body := V2AssignRequest{
		Riders: []optimizer.RiderState{
			{
				ID:             "rider-1",
				Position:       optimizer.Position{Lat: 12.9716, Lng: 77.5946},
				IsAvailable:    true,
				ActiveOrders:   0,
				AvgSpeedKmh:    25.0,
				VehicleType:    "scooter",
				BatteryPercent: 100.0,
			},
		},
		Orders: []optimizer.OrderRequest{
			{
				ID:          "order-1",
				Position:    optimizer.Position{Lat: 12.9720, Lng: 77.5950},
				ItemCount:   2,
				Weight:      1.5,
				CreatedAt:   time.Now().Add(-5 * time.Minute),
				SLADeadline: time.Now().Add(15 * time.Minute),
			},
		},
	}
	data, _ := json.Marshal(body)

	req := httptest.NewRequest(http.MethodPost, "/v2/optimize/assign", bytes.NewReader(data))
	rr := httptest.NewRecorder()

	handler.ServeHTTP(rr, req)

	if rr.Code != http.StatusOK {
		t.Fatalf("expected 200 OK, got %d (body: %s)", rr.Code, rr.Body.String())
	}

	var result optimizer.SolverResult
	if err := json.Unmarshal(rr.Body.Bytes(), &result); err != nil {
		t.Fatalf("failed to decode response: %v", err)
	}

	if len(result.Assignments) == 0 {
		t.Fatalf("expected at least 1 assignment, got 0")
	}
	if result.Assignments[0].RiderID != "rider-1" {
		t.Fatalf("expected assignment to rider-1, got %s", result.Assignments[0].RiderID)
	}
}
