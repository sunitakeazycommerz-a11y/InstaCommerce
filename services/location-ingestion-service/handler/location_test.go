package handler

import (
	"bytes"
	"context"
	"encoding/json"
	"fmt"
	"io"
	"log/slog"
	"net/http"
	"net/http/httptest"
	"sync"
	"sync/atomic"
	"testing"
	"time"

	"github.com/prometheus/client_golang/prometheus"
	"github.com/stretchr/testify/assert"
	"github.com/stretchr/testify/require"
)

// mockKafkaProducer captures published messages in memory for test assertions.
type mockKafkaProducer struct {
	mu       sync.Mutex
	messages map[string][]KafkaMessage
}

func newMockKafkaProducer() *mockKafkaProducer {
	return &mockKafkaProducer{
		messages: make(map[string][]KafkaMessage),
	}
}

func (m *mockKafkaProducer) PublishBatch(ctx context.Context, topic string, messages []KafkaMessage) error {
	m.mu.Lock()
	defer m.mu.Unlock()
	m.messages[topic] = append(m.messages[topic], messages...)
	return nil
}

func (m *mockKafkaProducer) getMessages(topic string) []KafkaMessage {
	m.mu.Lock()
	defer m.mu.Unlock()
	copied := make([]KafkaMessage, len(m.messages[topic]))
	copy(copied, m.messages[topic])
	return copied
}

// mockPositionStore stores latest coordinates in memory.
type mockPositionStore struct {
	mu        sync.Mutex
	positions map[string]LocationUpdate
}

func newMockPositionStore() *mockPositionStore {
	return &mockPositionStore{
		positions: make(map[string]LocationUpdate),
	}
}

func (m *mockPositionStore) Update(ctx context.Context, update LocationUpdate) error {
	m.mu.Lock()
	defer m.mu.Unlock()
	if prev, ok := m.positions[update.RiderID]; ok && !prev.Timestamp.IsZero() && !update.Timestamp.After(prev.Timestamp) {
		return fmt.Errorf("%w: %s <= %s", ErrOutOfOrderPing, update.Timestamp, prev.Timestamp)
	}
	m.positions[update.RiderID] = update
	return nil
}

func setupTestHandler(t *testing.T, opts ...LocationHandlerOption) (*LocationHandler, *mockKafkaProducer, *mockPositionStore, *GeofenceChecker) {
	producer := newMockKafkaProducer()
	logger := slog.New(slog.NewTextHandler(io.Discard, nil))
	batcher, err := NewLocationBatcher(producer, "rider.location.updates", 50, 50*time.Millisecond, logger)
	require.NoError(t, err)

	store := newMockPositionStore()
	geofencer := NewGeofenceChecker(9)
	metrics := NewLocationMetrics(prometheus.NewRegistry())

	defaultOpts := []LocationHandlerOption{
		WithGeofenceProducer(producer, DefaultGeofenceTopic),
		WithSynchronousGeofence(true),
	}
	allOpts := append(defaultOpts, opts...)

	h, err := NewLocationHandler(batcher, store, geofencer, logger, metrics, allOpts...)
	require.NoError(t, err)

	return h, producer, store, geofencer
}

func TestLocationHandler_GeofencePublishing_NearDeliveryAndExit(t *testing.T) {
	h, producer, _, geofencer := setupTestHandler(t)
	defer h.Close()

	geofencer.AddZone(Zone{
		ID:        "cust-zone-55",
		Type:      ZoneDelivery,
		CenterLat: 12.9700,
		CenterLng: 77.5900,
		RadiusKm:  0.1, // 100 meters
	})

	// 1. Inside delivery zone (near delivery)
	updateNear := LocationUpdate{
		RiderID:    "rider-777",
		Lat:        12.9700,
		Lng:        77.5900,
		Accuracy:   3.0,
		Speed:      5.0,
		Heading:    0.0,
		BatteryPct: 90.0,
		Timestamp:  time.Now().UTC(),
	}

	err := h.processUpdate(context.Background(), updateNear, "test")
	require.NoError(t, err)

	messages := producer.getMessages(DefaultGeofenceTopic)
	require.Len(t, messages, 1)

	var env GeofenceEventEnvelope
	err = json.Unmarshal(messages[0].Value, &env)
	require.NoError(t, err)
	assert.Equal(t, "NEAR_DELIVERY", env.Payload.EventType)
	assert.Equal(t, "cust-zone-55", env.Payload.ZoneID)

	// 2. Rider moves slightly outside boundary (130m away, <= 1.5 * radius = 150m)
	// At lat 12.97, ~0.001 deg is ~111m
	updateExit := LocationUpdate{
		RiderID:    "rider-777",
		Lat:        12.9711,
		Lng:        77.5900,
		Accuracy:   3.0,
		Speed:      12.0,
		Heading:    0.0,
		BatteryPct: 89.0,
		Timestamp:  updateNear.Timestamp.Add(30 * time.Second),
	}

	err = h.processUpdate(context.Background(), updateExit, "test")
	require.NoError(t, err)

	messages = producer.getMessages(DefaultGeofenceTopic)
	require.Len(t, messages, 2)

	err = json.Unmarshal(messages[1].Value, &env)
	require.NoError(t, err)
	assert.Equal(t, "EXITED_ZONE", env.Payload.EventType)
	assert.Equal(t, "cust-zone-55", env.Payload.ZoneID)
}

func TestLocationHandler_ValidationErrors(t *testing.T) {
	h, _, _, _ := setupTestHandler(t)
	defer h.Close()

	// Malformed JSON (syntax error) -> 400 Bad Request
	req := httptest.NewRequest(http.MethodPost, "/location", bytes.NewReader([]byte("{invalid-json")))
	w := httptest.NewRecorder()
	h.HandleHTTP(w, req)
	assert.Equal(t, http.StatusBadRequest, w.Code)

	// Missing rider_id (validation failure) -> 422 Unprocessable Entity
	badUpdate := LocationUpdate{
		RiderID: "",
		Lat:     12.0,
		Lng:     77.0,
	}
	body, _ := json.Marshal(badUpdate)
	req = httptest.NewRequest(http.MethodPost, "/location", bytes.NewReader(body))
	w = httptest.NewRecorder()
	h.HandleHTTP(w, req)
	assert.Equal(t, http.StatusUnprocessableEntity, w.Code)

	// Out of range latitude -> 422 Unprocessable Entity
	badLatUpdate := LocationUpdate{
		RiderID: "r1",
		Lat:     95.0,
		Lng:     77.0,
	}
	body, _ = json.Marshal(badLatUpdate)
	req = httptest.NewRequest(http.MethodPost, "/location", bytes.NewReader(body))
	w = httptest.NewRecorder()
	h.HandleHTTP(w, req)
	assert.Equal(t, http.StatusUnprocessableEntity, w.Code)
}

func TestGeofenceChecker_DebounceAndHysteresisDeduplication(t *testing.T) {
	geofencer := NewGeofenceChecker(9)
	geofencer.AddZone(Zone{
		ID:                  "store-indiranagar-01",
		Type:                ZoneStore,
		CenterLat:           12.9716,
		CenterLng:           77.5946,
		RadiusKm:            0.2, // 200m
		MinConsecutivePings: 3,   // Requires 3 consecutive pings before arrival confirmation (Issue #590)
		HysteresisWindow:    3 * time.Minute,
	})

	now := time.Now().UTC()
	makePing := func(offsetSec int) LocationUpdate {
		return LocationUpdate{
			RiderID:   "rider-debounced",
			Lat:       12.9716,
			Lng:       77.5946,
			Timestamp: now.Add(time.Duration(offsetSec) * time.Second),
		}
	}

	// Ping 1: Inside boundary, but consecutive count = 1 < 3 -> No event
	evs1 := geofencer.Check(makePing(0))
	assert.Empty(t, evs1, "Ping 1 should be debounced")

	// Ping 2: Inside boundary, consecutive count = 2 < 3 -> No event
	evs2 := geofencer.Check(makePing(5))
	assert.Empty(t, evs2, "Ping 2 should be debounced")

	// Ping 3: Inside boundary, consecutive count = 3 >= 3 -> Event emitted!
	evs3 := geofencer.Check(makePing(10))
	require.Len(t, evs3, 1, "Ping 3 should confirm arrival")
	assert.Equal(t, "ENTERED_STORE", evs3[0].EventType)
	assert.Equal(t, "store-indiranagar-01", evs3[0].ZoneID)

	// Pings 4 through 10 within 3-minute window (e.g. at 20s, 30s, 60s) -> Suppressed by hysteresis
	for sec := 20; sec <= 60; sec += 10 {
		evs := geofencer.Check(makePing(sec))
		assert.Empty(t, evs, "Subsequent pings within 3m hysteresis must be deduplicated")
	}

	// Ping after 3-minute hysteresis window expires (185s > 180s) -> Emits once again if re-entered
	// Rider exits zone first
	exitPing := LocationUpdate{
		RiderID:   "rider-debounced",
		Lat:       12.9735, // 250m away
		Lng:       77.5946,
		Timestamp: now.Add(185 * time.Second),
	}
	exitEvs := geofencer.Check(exitPing)
	require.Len(t, exitEvs, 1)
	assert.Equal(t, "EXITED_ZONE", exitEvs[0].EventType)
}

type spyGeofenceDeduplicator struct {
	calls atomic.Int64
	inner *InMemoryGeofenceDeduplicator
}

func newSpyGeofenceDeduplicator() *spyGeofenceDeduplicator {
	return &spyGeofenceDeduplicator{inner: NewInMemoryGeofenceDeduplicator()}
}

func (s *spyGeofenceDeduplicator) TryAcquire(ctx context.Context, key string, ttl time.Duration) bool {
	s.calls.Add(1)
	return s.inner.TryAcquire(ctx, key, ttl)
}

func TestGeofenceChecker_OutOfOrderPingDoesNotMutateStateOrConsumeRedisLock(t *testing.T) {
	geofencer := NewGeofenceChecker(9)
	dedupSpy := newSpyGeofenceDeduplicator()
	geofencer.SetDeduplicator(dedupSpy)

	geofencer.AddZone(Zone{
		ID:                  "store-main",
		Type:                ZoneStore,
		CenterLat:           12.9716,
		CenterLng:           77.5946,
		RadiusKm:            0.2,
		MinConsecutivePings: 1,
		HysteresisWindow:    3 * time.Minute,
	})
	geofencer.AddZone(Zone{
		ID:                  "zone-other",
		Type:                ZoneDelivery,
		CenterLat:           12.9900,
		CenterLng:           77.6100,
		RadiusKm:            0.2,
		MinConsecutivePings: 1,
		HysteresisWindow:    3 * time.Minute,
	})

	t0 := time.Date(2026, 11, 18, 10, 0, 0, 0, time.UTC)

	// 1. Accepted ping enters store-main at t0.
	evs := geofencer.Check(LocationUpdate{
		RiderID:   "rider-ooo",
		Lat:       12.9716,
		Lng:       77.5946,
		Timestamp: t0,
	})
	require.Len(t, evs, 1)
	assert.Equal(t, "ENTERED_STORE", evs[0].EventType)
	assert.Equal(t, int64(1), dedupSpy.calls.Load())

	trackMain := geofencer.tracking[riderZoneKey{riderID: "rider-ooo", zoneID: "store-main"}]
	require.NotNil(t, trackMain)
	assert.True(t, trackMain.inside)
	assert.Equal(t, t0, trackMain.lastEventTime)

	trackOther := geofencer.tracking[riderZoneKey{riderID: "rider-ooo", zoneID: "zone-other"}]
	require.NotNil(t, trackOther)
	trackOtherBefore := *trackOther

	geofencer.AddZone(Zone{
		ID:                  "zone-added-later",
		Type:                ZoneDelivery,
		CenterLat:           12.9900,
		CenterLng:           77.6100,
		RadiusKm:            0.2,
		MinConsecutivePings: 1,
		HysteresisWindow:    3 * time.Minute,
	})

	// 2. Stale ping outside store-main (at t0 - 10s and t0) inside zone-other/zone-added-later must return nil
	// without emitting EXITED_ZONE or NEAR_DELIVERY, mutating tracking state, or calling TryAcquire.
	for _, staleTs := range []time.Time{t0.Add(-10 * time.Second), t0} {
		staleEvs := geofencer.Check(LocationUpdate{
			RiderID:   "rider-ooo",
			Lat:       12.9900,
			Lng:       77.6100,
			Timestamp: staleTs,
		})
		assert.Nil(t, staleEvs)
		assert.True(t, trackMain.inside, "stale ping must not clear track.inside")
		assert.Equal(t, t0, trackMain.lastEventTime, "stale ping must not overwrite lastEventTime")
		assert.Equal(t, trackOtherBefore, *trackOther, "stale ping must not mutate another zone's tracking state")
		assert.Equal(t, int64(1), dedupSpy.calls.Load(), "stale ping must not consume Redis dedup lock")
		_, createdLater := geofencer.tracking[riderZoneKey{riderID: "rider-ooo", zoneID: "zone-added-later"}]
		assert.False(t, createdLater, "stale ping must not create tracking state for new zones")
	}

	// 3. Debounce state protection: ping at t0+5s inside zone-other with MinConsecutivePings=3,
	// followed by older ping at t0+2s must not increment consecutiveInside.
	geofencer.AddZone(Zone{
		ID:                  "zone-debounced",
		Type:                ZoneStore,
		CenterLat:           12.9500,
		CenterLng:           77.5800,
		RadiusKm:            0.2,
		MinConsecutivePings: 3,
		HysteresisWindow:    3 * time.Minute,
	})
	assert.Empty(t, geofencer.Check(LocationUpdate{
		RiderID:   "rider-debounce-ooo",
		Lat:       12.9500,
		Lng:       77.5800,
		Timestamp: t0.Add(5 * time.Second),
	}))
	assert.Empty(t, geofencer.Check(LocationUpdate{
		RiderID:   "rider-debounce-ooo",
		Lat:       12.9500,
		Lng:       77.5800,
		Timestamp: t0.Add(2 * time.Second),
	}))
	trackDeb := geofencer.tracking[riderZoneKey{riderID: "rider-debounce-ooo", zoneID: "zone-debounced"}]
	require.NotNil(t, trackDeb)
	assert.Equal(t, 1, trackDeb.consecutiveInside, "out-of-order ping must not advance debounce counter")
}
