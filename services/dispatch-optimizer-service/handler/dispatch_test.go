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
