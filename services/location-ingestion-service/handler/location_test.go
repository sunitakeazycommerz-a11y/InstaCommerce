package handler

import (
	"bytes"
	"context"
	"encoding/json"
	"io"
	"log/slog"
	"net/http"
	"net/http/httptest"
	"sync"
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

func TestGeofenceEvent_ToEnvelope(t *testing.T) {
	now := time.Now().UTC()
	ev := GeofenceEvent{
		RiderID:   "rider-001",
		EventType: "ENTERED_STORE",
		ZoneID:    "store-101",
		H3Index:   "h3-test-123",
		Timestamp: now,
	}

	env := ev.ToEnvelope(12.9716, 77.5946)
	assert.Equal(t, "RiderGeofenceTransition", env.EventType)
	assert.Equal(t, "Rider", env.AggregateType)
	assert.Equal(t, "rider-001", env.AggregateID)
	assert.Equal(t, "v1", env.SchemaVersion)
	assert.Equal(t, "location-ingestion-service", env.SourceService)
	assert.Equal(t, "ENTERED_STORE", env.Payload.EventType)
	assert.Equal(t, "store-101", env.Payload.ZoneID)
	assert.Equal(t, 12.9716, env.Payload.Lat)
	assert.Equal(t, 77.5946, env.Payload.Lng)
}

func TestLocationHandler_GeofencePublishing_EnteredStore(t *testing.T) {
	h, producer, store, geofencer := setupTestHandler(t)
	defer h.Close()

	// Register store zone (Center at Bangalore: 12.9716, 77.5946 with 200m radius = 0.2km)
	geofencer.AddZone(Zone{
		ID:        "store-blr-01",
		Type:      ZoneStore,
		CenterLat: 12.9716,
		CenterLng: 77.5946,
		RadiusKm:  0.2,
	})

	update := LocationUpdate{
		RiderID:    "rider-999",
		Lat:        12.9716, // Exactly at store center
		Lng:        77.5946,
		Accuracy:   5.0,
		Speed:      15.0,
		Heading:    90.0,
		BatteryPct: 85.0,
		Timestamp:  time.Now().UTC(),
	}

	payloadBytes, err := json.Marshal(update)
	require.NoError(t, err)

	req := httptest.NewRequest(http.MethodPost, "/location", bytes.NewReader(payloadBytes))
	w := httptest.NewRecorder()

	h.HandleHTTP(w, req)
	assert.Equal(t, http.StatusAccepted, w.Code)

	// Verify position saved to store
	assert.Contains(t, store.positions, "rider-999")

	// Verify geofence transition published to Kafka topic rider.geofence.events
	messages := producer.getMessages(DefaultGeofenceTopic)
	require.Len(t, messages, 1)
	assert.Equal(t, "rider-999", string(messages[0].Key))

	var envelope GeofenceEventEnvelope
	err = json.Unmarshal(messages[0].Value, &envelope)
	require.NoError(t, err)

	assert.Equal(t, "RiderGeofenceTransition", envelope.EventType)
	assert.Equal(t, "Rider", envelope.AggregateType)
	assert.Equal(t, "rider-999", envelope.AggregateID)
	assert.Equal(t, "ENTERED_STORE", envelope.Payload.EventType)
	assert.Equal(t, "store-blr-01", envelope.Payload.ZoneID)
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
		Timestamp:  time.Now().UTC(),
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
