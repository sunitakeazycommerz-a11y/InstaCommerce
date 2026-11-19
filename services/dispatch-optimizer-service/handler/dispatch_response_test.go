package handler

import (
	"bytes"
	"context"
	"encoding/json"
	"errors"
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

type backendErrMutex struct {
	err error
}

func (m *backendErrMutex) Lock(_ context.Context, _ string, _ time.Duration) (func() error, error) {
	return nil, m.err
}

func TestHandleRiderResponse_AutomatedReDispatchOnRejectionOrTimeout(t *testing.T) {
	matcher := optimizer.NewBipartiteMatcher(nil, nil)
	pub := &mockPublisher{}
	logger := slog.New(slog.NewJSONHandler(io.Discard, nil))
	locker := NewInMemoryRiderMutex()
	responseHandler := HandleRiderResponse(matcher, pub, locker, logger)

	rider1 := optimizer.RiderState{ID: "rider-first-choice", Position: optimizer.Position{Lat: 12.9720, Lng: 77.5950}, IsAvailable: true}
	rider2 := optimizer.RiderState{ID: "rider-second-choice", Position: optimizer.Position{Lat: 12.9750, Lng: 77.5980}, IsAvailable: true}

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
	require.NoError(t, json.Unmarshal(rec.Body.Bytes(), &resp))
	assert.Equal(t, "ASSIGNED", resp.Status)
	assert.Equal(t, "rider-second-choice", resp.RiderID)
	assert.Equal(t, 2, resp.AttemptCount)
	assert.Contains(t, resp.ExcludedRiderIDs, "rider-first-choice")
	assert.False(t, resp.LeadDispatcherAlert)
}

func TestHandleDispatchAssign_LeadDispatcherAlertAndEmptyRiders(t *testing.T) {
	matcher := optimizer.NewBipartiteMatcher(nil, nil)
	logger := slog.New(slog.NewJSONHandler(io.Discard, nil))

	t.Run("attempt 3 triggers lead alert", func(t *testing.T) {
		h := HandleDispatchAssign(matcher, &mockPublisher{}, logger)
		body, _ := json.Marshal(DispatchAssignRequest{
			OrderID:       "order-failing-attempts",
			StoreID:       "store-100",
			OrderPosition: optimizer.Position{Lat: 12.9716, Lng: 77.5946},
			AttemptCount:  2,
			AvailableRiders: []optimizer.RiderState{
				{ID: "rider-3rd", Position: optimizer.Position{Lat: 12.9720, Lng: 77.5950}, IsAvailable: true},
			},
		})
		rec := httptest.NewRecorder()
		h.ServeHTTP(rec, httptest.NewRequest(http.MethodPost, "/dispatch/assign", bytes.NewReader(body)))
		require.Equal(t, http.StatusOK, rec.Code)
		var resp DispatchAssignResponse
		require.NoError(t, json.Unmarshal(rec.Body.Bytes(), &resp))
		assert.Equal(t, 3, resp.AttemptCount)
		assert.True(t, resp.LeadDispatcherAlert)
	})

	t.Run("empty riders queues without panic", func(t *testing.T) {
		h := HandleDispatchAssign(matcher, &mockPublisher{}, logger)
		body, _ := json.Marshal(DispatchAssignRequest{
			OrderID:         "order-empty-riders",
			StoreID:         "store-empty",
			OrderPosition:   optimizer.Position{Lat: 12.9716, Lng: 77.5946},
			AttemptCount:    1,
			AvailableRiders: []optimizer.RiderState{},
		})
		rec := httptest.NewRecorder()
		assert.NotPanics(t, func() {
			h.ServeHTTP(rec, httptest.NewRequest(http.MethodPost, "/dispatch/assign", bytes.NewReader(body)))
		})
		require.Equal(t, http.StatusOK, rec.Code)
		var resp DispatchAssignResponse
		require.NoError(t, json.Unmarshal(rec.Body.Bytes(), &resp))
		assert.Equal(t, "QUEUED_NO_RIDERS", resp.Status)
	})
}

func TestHandleDispatchAssign_LockNormalReleaseAndLockErrorHandling(t *testing.T) {
	matcher := optimizer.NewBipartiteMatcher(nil, nil)
	logger := slog.New(slog.NewJSONHandler(io.Discard, nil))

	t.Run("normal success path releases rider lock", func(t *testing.T) {
		spy := &spyMutex{}
		h := HandleDispatchAssignWithLocker(matcher, &mockPublisher{}, spy, logger)
		body, _ := json.Marshal(DispatchAssignRequest{
			OrderID:       "order-normal-unlock",
			StoreID:       "store-1",
			OrderPosition: optimizer.Position{Lat: 12.9716, Lng: 77.5946},
			AvailableRiders: []optimizer.RiderState{
				{ID: "rider-1", Position: optimizer.Position{Lat: 12.9720, Lng: 77.5950}, IsAvailable: true},
			},
		})
		rec := httptest.NewRecorder()
		h.ServeHTTP(rec, httptest.NewRequest(http.MethodPost, "/dispatch/assign", bytes.NewReader(body)))
		require.Equal(t, http.StatusOK, rec.Code)
		assert.Equal(t, 1, spy.lockCount)
		assert.Equal(t, 1, spy.unlockCount)
	})

	t.Run("non-ErrRiderAlreadyLocked error aborts assignment", func(t *testing.T) {
		pub := &mockPublisher{}
		h := HandleDispatchAssignWithLocker(matcher, pub, &backendErrMutex{err: errors.New("redis connection refused")}, logger)
		body, _ := json.Marshal(DispatchAssignRequest{
			OrderID:       "order-lock-err",
			StoreID:       "store-1",
			OrderPosition: optimizer.Position{Lat: 12.9716, Lng: 77.5946},
			AvailableRiders: []optimizer.RiderState{
				{ID: "rider-1", Position: optimizer.Position{Lat: 12.9720, Lng: 77.5950}, IsAvailable: true},
			},
		})
		rec := httptest.NewRecorder()
		h.ServeHTTP(rec, httptest.NewRequest(http.MethodPost, "/dispatch/assign", bytes.NewReader(body)))
		assert.Equal(t, http.StatusServiceUnavailable, rec.Code)
		assert.Nil(t, pub.publishedEvent)
	})
}

func TestHandleRiderResponse_ReDispatchValidationAndColdChain(t *testing.T) {
	matcher := optimizer.NewBipartiteMatcher(nil, nil)
	logger := slog.New(slog.NewJSONHandler(io.Discard, nil))

	t.Run("missing store_id returns 400", func(t *testing.T) {
		h := HandleRiderResponse(matcher, &mockPublisher{}, NewInMemoryRiderMutex(), logger)
		body, _ := json.Marshal(RiderResponseRequest{
			OrderID:       "order-1",
			RiderID:       "rider-1",
			Accepted:      false,
			OrderPosition: optimizer.Position{Lat: 12.9716, Lng: 77.5946},
		})
		rec := httptest.NewRecorder()
		h.ServeHTTP(rec, httptest.NewRequest(http.MethodPost, "/dispatch/response", bytes.NewReader(body)))
		assert.Equal(t, http.StatusBadRequest, rec.Code)
	})

	t.Run("panic during re-dispatch releases rider lock", func(t *testing.T) {
		spy := &spyMutex{}
		h := HandleRiderResponse(matcher, &panickingPublisher{}, spy, logger)
		body, _ := json.Marshal(RiderResponseRequest{
			OrderID:       "order-1",
			StoreID:       "store-1",
			RiderID:       "rider-1",
			Accepted:      false,
			OrderPosition: optimizer.Position{Lat: 12.9716, Lng: 77.5946},
			AvailableRiders: []optimizer.RiderState{
				{ID: "rider-2", Position: optimizer.Position{Lat: 12.9720, Lng: 77.5950}, IsAvailable: true},
			},
		})
		rec := httptest.NewRecorder()
		assert.NotPanics(t, func() {
			h.ServeHTTP(rec, httptest.NewRequest(http.MethodPost, "/dispatch/response", bytes.NewReader(body)))
		})
		assert.Equal(t, http.StatusInternalServerError, rec.Code)
		assert.Equal(t, 1, spy.lockCount)
		assert.Equal(t, 1, spy.unlockCount)
	})

	t.Run("cold-chain dispatch selects thermal rider", func(t *testing.T) {
		h := HandleDispatchAssign(matcher, &mockPublisher{}, logger)
		body, _ := json.Marshal(DispatchAssignRequest{
			OrderID:           "order-frozen",
			StoreID:           "store-1",
			OrderPosition:     optimizer.Position{Lat: 12.9716, Lng: 77.5946},
			RequiresColdChain: true,
			ColdChainTier:     "frozen",
			Weight:            2.0,
			VolumeLiters:      6.0,
			AvailableRiders: []optimizer.RiderState{
				{ID: "rider-no-thermal", Position: optimizer.Position{Lat: 12.9717, Lng: 77.5947}, IsAvailable: true, HasThermalBag: false},
				{ID: "rider-thermal", Position: optimizer.Position{Lat: 12.9740, Lng: 77.5970}, IsAvailable: true, HasThermalBag: true},
			},
		})
		rec := httptest.NewRecorder()
		h.ServeHTTP(rec, httptest.NewRequest(http.MethodPost, "/dispatch/assign", bytes.NewReader(body)))
		require.Equal(t, http.StatusOK, rec.Code)
		var resp DispatchAssignResponse
		require.NoError(t, json.Unmarshal(rec.Body.Bytes(), &resp))
		assert.Equal(t, "ASSIGNED", resp.Status)
		assert.Equal(t, "rider-thermal", resp.RiderID)
	})

	t.Run("invalid negative weight and store_position rejected", func(t *testing.T) {
		cases := []DispatchAssignRequest{
			{
				OrderID:       "o1",
				StoreID:       "s1",
				OrderPosition: optimizer.Position{Lat: 12.97, Lng: 77.59},
				Weight:        -1,
			},
			{
				OrderID:       "o2",
				StoreID:       "s1",
				OrderPosition: optimizer.Position{Lat: 12.97, Lng: 77.59},
				StorePosition: &optimizer.Position{Lat: 95, Lng: 77.59},
			},
		}
		h := HandleDispatchAssign(matcher, nil, nil)
		for _, tc := range cases {
			body, _ := json.Marshal(tc)
			rec := httptest.NewRecorder()
			h.ServeHTTP(rec, httptest.NewRequest(http.MethodPost, "/dispatch/assign", bytes.NewReader(body)))
			assert.Equal(t, http.StatusBadRequest, rec.Code)
		}
	})
}
