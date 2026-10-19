package handler

import (
	"bytes"
	"context"
	"encoding/json"
	"io"
	"log/slog"
	"net/http"
	"net/http/httptest"
	"testing"
	"time"

	"github.com/instacommerce/dispatch-optimizer-service/optimizer"
	"github.com/stretchr/testify/assert"
	"github.com/stretchr/testify/require"
)

type mockPublisher struct {
	publishedTopic string
	publishedKey   string
	publishedEvent *RiderAssignedEvent
}

func (m *mockPublisher) Publish(ctx context.Context, topic string, key string, payload any) error {
	m.publishedTopic = topic
	m.publishedKey = key
	if ev, ok := payload.(*RiderAssignedEvent); ok {
		m.publishedEvent = ev
	}
	return nil
}

func TestHandleDispatchAssign_Success(t *testing.T) {
	matcher := optimizer.NewBipartiteMatcher(nil, nil)
	pub := &mockPublisher{}
	logger := slog.New(slog.NewJSONHandler(io.Discard, nil))
	handler := HandleDispatchAssign(matcher, pub, logger)

	reqPayload := DispatchAssignRequest{
		OrderID: "order-123",
		StoreID: "store-456",
		OrderPosition: optimizer.Position{
			Lat: 12.9716,
			Lng: 77.5946,
		},
		AvailableRiders: []optimizer.RiderState{
			{
				ID:          "rider-007",
				Position:    optimizer.Position{Lat: 12.9720, Lng: 77.5950},
				IsAvailable: true,
				AvgSpeedKmh: 20.0,
			},
		},
	}

	body, err := json.Marshal(reqPayload)
	require.NoError(t, err)

	req := httptest.NewRequest(http.MethodPost, "/dispatch/assign", bytes.NewReader(body))
	rec := httptest.NewRecorder()

	handler.ServeHTTP(rec, req)

	assert.Equal(t, http.StatusOK, rec.Code)
	var resp DispatchAssignResponse
	err = json.Unmarshal(rec.Body.Bytes(), &resp)
	require.NoError(t, err)

	assert.Equal(t, "ASSIGNED", resp.Status)
	assert.Equal(t, "rider-007", resp.RiderID)
	assert.Equal(t, "order-123", resp.OrderID)
	require.NotNil(t, resp.Event)
	assert.Equal(t, "rider-007", resp.Event.RiderID)
	assert.Equal(t, "order-123", resp.Event.OrderID)
	assert.Equal(t, "store-456", resp.Event.StoreID)

	// Verify publisher was called with rider.events
	assert.Equal(t, "rider.events", pub.publishedTopic)
	assert.Equal(t, "rider-007", pub.publishedKey)
	require.NotNil(t, pub.publishedEvent)
}

func TestHandleDispatchAssign_EmptyRidersQueueForRetry(t *testing.T) {
	matcher := optimizer.NewBipartiteMatcher(nil, nil)
	pub := &mockPublisher{}
	logger := slog.New(slog.NewJSONHandler(io.Discard, nil))
	handler := HandleDispatchAssign(matcher, pub, logger)

	reqPayload := DispatchAssignRequest{
		OrderID: "order-123",
		StoreID: "store-456",
		OrderPosition: optimizer.Position{
			Lat: 12.9716,
			Lng: 77.5946,
		},
		AvailableRiders: []optimizer.RiderState{}, // Empty riders!
	}

	body, err := json.Marshal(reqPayload)
	require.NoError(t, err)

	req := httptest.NewRequest(http.MethodPost, "/dispatch/assign", bytes.NewReader(body))
	rec := httptest.NewRecorder()

	handler.ServeHTTP(rec, req)

	assert.Equal(t, http.StatusOK, rec.Code)
	var resp DispatchAssignResponse
	err = json.Unmarshal(rec.Body.Bytes(), &resp)
	require.NoError(t, err)

	assert.Equal(t, "QUEUED_NO_RIDERS", resp.Status)
	assert.Equal(t, 30, resp.RetryAfterSeconds)
	assert.False(t, resp.Escalated)
	assert.Nil(t, pub.publishedEvent)
}

func TestHandleDispatchAssign_EscalationAfterTimeout(t *testing.T) {
	matcher := optimizer.NewBipartiteMatcher(&optimizer.MatcherConfig{
		EscalationTimeout: 3 * time.Minute,
	}, nil)
	pub := &mockPublisher{}
	logger := slog.New(slog.NewJSONHandler(io.Discard, nil))
	handler := HandleDispatchAssign(matcher, pub, logger)

	reqPayload := DispatchAssignRequest{
		OrderID:   "order-delayed",
		StoreID:   "store-456",
		CreatedAt: time.Now().Add(-5 * time.Minute), // Created 5m ago (> 3m)
		OrderPosition: optimizer.Position{
			Lat: 12.9716,
			Lng: 77.5946,
		},
		AvailableRiders: []optimizer.RiderState{},
	}

	body, err := json.Marshal(reqPayload)
	require.NoError(t, err)

	req := httptest.NewRequest(http.MethodPost, "/dispatch/assign", bytes.NewReader(body))
	rec := httptest.NewRecorder()

	handler.ServeHTTP(rec, req)

	assert.Equal(t, http.StatusOK, rec.Code)
	var resp DispatchAssignResponse
	err = json.Unmarshal(rec.Body.Bytes(), &resp)
	require.NoError(t, err)

	assert.Equal(t, "ESCALATED", resp.Status)
	assert.True(t, resp.Escalated)
	assert.Contains(t, resp.EscalationReason, "pending assignment for >=")
}

func TestHandleDispatchAssign_ValidationErrors(t *testing.T) {
	matcher := optimizer.NewBipartiteMatcher(nil, nil)
	logger := slog.New(slog.NewJSONHandler(io.Discard, nil))
	handler := HandleDispatchAssign(matcher, nil, logger)

	// Missing order_id
	badReq := DispatchAssignRequest{
		StoreID:       "store-456",
		OrderPosition: optimizer.Position{Lat: 12.97, Lng: 77.59},
	}
	body, _ := json.Marshal(badReq)
	req := httptest.NewRequest(http.MethodPost, "/dispatch/assign", bytes.NewReader(body))
	rec := httptest.NewRecorder()
	handler.ServeHTTP(rec, req)
	assert.Equal(t, http.StatusBadRequest, rec.Code)
}

type spyMutex struct {
	lockCount   int
	unlockCount int
}

func (s *spyMutex) Lock(ctx context.Context, riderID string, ttl time.Duration) (func() error, error) {
	s.lockCount++
	return func() error {
		s.unlockCount++
		return nil
	}, nil
}

type panickingPublisher struct{}

func (p *panickingPublisher) Publish(ctx context.Context, topic string, key string, payload any) error {
	panic("unexpected kafka publisher runtime panic")
}

func TestHandleDispatchAssign_PanicRecoveryReleasesMutexLock(t *testing.T) {
	matcher := optimizer.NewBipartiteMatcher(nil, nil)
	panicker := &panickingPublisher{}
	spy := &spyMutex{}
	logger := slog.New(slog.NewJSONHandler(io.Discard, nil))

	handler := HandleDispatchAssignWithLocker(matcher, panicker, spy, logger)

	reqPayload := DispatchAssignRequest{
		OrderID: "order-panic-test",
		StoreID: "store-1",
		OrderPosition: optimizer.Position{
			Lat: 12.9716,
			Lng: 77.5946,
		},
		AvailableRiders: []optimizer.RiderState{
			{
				ID:          "rider-locked",
				Position:    optimizer.Position{Lat: 12.9720, Lng: 77.5950},
				IsAvailable: true,
			},
		},
	}

	body, _ := json.Marshal(reqPayload)
	req := httptest.NewRequest(http.MethodPost, "/dispatch/assign", bytes.NewReader(body))
	rec := httptest.NewRecorder()

	// Must not crash or propagate unhandled panic
	assert.NotPanics(t, func() {
		handler.ServeHTTP(rec, req)
	})

	assert.Equal(t, http.StatusInternalServerError, rec.Code)
	// Mutex lock must have been acquired and subsequently unlocked during panic recovery (Issue #594)
	assert.Equal(t, 1, spy.lockCount, "Lock must have been acquired")
	assert.Equal(t, 1, spy.unlockCount, "Lock must have been released by defer recover()")
}

func TestHandleRiderResponse_AutomatedReDispatchOnRejectionOrTimeout(t *testing.T) {
	matcher := optimizer.NewBipartiteMatcher(nil, nil)
	pub := &mockPublisher{}
	logger := slog.New(slog.NewJSONHandler(io.Discard, nil))
	locker := NewInMemoryRiderMutex()

	responseHandler := HandleRiderResponse(matcher, pub, locker, logger)

	rider1 := optimizer.RiderState{
		ID:          "rider-first-choice",
		Position:    optimizer.Position{Lat: 12.9720, Lng: 77.5950}, // ~200m
		IsAvailable: true,
	}
	rider2 := optimizer.RiderState{
		ID:          "rider-second-choice",
		Position:    optimizer.Position{Lat: 12.9750, Lng: 77.5980}, // ~500m
		IsAvailable: true,
	}

	// Rider 1 times out after 45 seconds or rejects -> Request automated re-dispatch
	riderResp := RiderResponseRequest{
		OrderID:         "order-redispatch",
		StoreID:         "store-100",
		RiderID:         "rider-first-choice",
		Accepted:        false,
		Reason:          "TIMEOUT",
		OrderPosition:   optimizer.Position{Lat: 12.9716, Lng: 77.5946},
		AttemptCount:    1,
		AvailableRiders: []optimizer.RiderState{rider1, rider2},
	}

	body, _ := json.Marshal(riderResp)
	req := httptest.NewRequest(http.MethodPost, "/dispatch/response", bytes.NewReader(body))
	rec := httptest.NewRecorder()

	responseHandler.ServeHTTP(rec, req)

	assert.Equal(t, http.StatusOK, rec.Code)
	var resp DispatchAssignResponse
	err := json.Unmarshal(rec.Body.Bytes(), &resp)
	require.NoError(t, err)

	// Must be re-assigned to rider-second-choice
	assert.Equal(t, "ASSIGNED", resp.Status)
	assert.Equal(t, "rider-second-choice", resp.RiderID)
	assert.Equal(t, 2, resp.AttemptCount)
	assert.Contains(t, resp.ExcludedRiderIDs, "rider-first-choice")
	assert.False(t, resp.LeadDispatcherAlert)
}

func TestHandleDispatchAssign_LeadDispatcherAlertAfterThreeAttempts(t *testing.T) {
	matcher := optimizer.NewBipartiteMatcher(nil, nil)
	pub := &mockPublisher{}
	logger := slog.New(slog.NewJSONHandler(io.Discard, nil))

	handler := HandleDispatchAssign(matcher, pub, logger)

	reqPayload := DispatchAssignRequest{
		OrderID: "order-failing-attempts",
		StoreID: "store-100",
		OrderPosition: optimizer.Position{
			Lat: 12.9716,
			Lng: 77.5946,
		},
		AttemptCount: 2, // 2 prior attempts, this is 3rd attempt
		AvailableRiders: []optimizer.RiderState{
			{
				ID:          "rider-3rd",
				Position:    optimizer.Position{Lat: 12.9720, Lng: 77.5950},
				IsAvailable: true,
			},
		},
	}

	body, _ := json.Marshal(reqPayload)
	req := httptest.NewRequest(http.MethodPost, "/dispatch/assign", bytes.NewReader(body))
	rec := httptest.NewRecorder()

	handler.ServeHTTP(rec, req)

	assert.Equal(t, http.StatusOK, rec.Code)
	var resp DispatchAssignResponse
	err := json.Unmarshal(rec.Body.Bytes(), &resp)
	require.NoError(t, err)

	assert.Equal(t, 3, resp.AttemptCount)
	assert.True(t, resp.LeadDispatcherAlert, "Attempt 3 must trigger lead dispatcher alert")
}

func TestHandleDispatchAssign_EmptyAvailableRidersDoesNotPanic(t *testing.T) {
	matcher := optimizer.NewBipartiteMatcher(nil, nil)
	pub := &mockPublisher{}
	logger := slog.New(slog.NewJSONHandler(io.Discard, nil))

	handler := HandleDispatchAssign(matcher, pub, logger)

	reqPayload := DispatchAssignRequest{
		OrderID: "order-empty-riders",
		StoreID: "store-empty",
		OrderPosition: optimizer.Position{
			Lat: 12.9716,
			Lng: 77.5946,
		},
		AttemptCount:    1,
		AvailableRiders: []optimizer.RiderState{},
	}

	body, _ := json.Marshal(reqPayload)
	req := httptest.NewRequest(http.MethodPost, "/dispatch/assign", bytes.NewReader(body))
	rec := httptest.NewRecorder()

	assert.NotPanics(t, func() {
		handler.ServeHTTP(rec, req)
	})

	assert.Equal(t, http.StatusOK, rec.Code)
	var resp DispatchAssignResponse
	err := json.Unmarshal(rec.Body.Bytes(), &resp)
	require.NoError(t, err)

	assert.Equal(t, "QUEUED_NO_RIDERS", resp.Status)
	assert.Equal(t, "order-empty-riders", resp.OrderID)
}

