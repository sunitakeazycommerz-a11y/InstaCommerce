package handler

import (
	"bytes"
	"context"
	"encoding/json"
	"io"
	"log/slog"
	"math"
	"net/http"
	"net/http/httptest"
	"testing"
	"time"

	"github.com/prometheus/client_golang/prometheus"
	"github.com/stretchr/testify/assert"
	"github.com/stretchr/testify/require"
)

func TestLocationHandler_OutOfOrderStoreRejectionSkipsGeofenceAndRedisLock(t *testing.T) {
	dedupSpy := newSpyGeofenceDeduplicator()
	h, producer, _, geofencer := setupTestHandler(t, WithTelemetrySmoother(nil))
	defer h.Close()
	geofencer.SetDeduplicator(dedupSpy)

	geofencer.AddZone(Zone{
		ID:        "store-ooo-01",
		Type:      ZoneStore,
		CenterLat: 12.9716,
		CenterLng: 77.5946,
		RadiusKm:  0.2,
	})

	now := time.Now().UTC()
	tsNew := now.UnixMilli()
	tsStale := now.Add(-30 * time.Second).UnixMilli()

	body1, err := json.Marshal(LocationInput{
		RiderID:   "rider-p1-8",
		Lat:       12.9716,
		Lng:       77.5946,
		Timestamp: &tsNew,
	})
	require.NoError(t, err)
	rec1 := httptest.NewRecorder()
	h.HandleIngest(rec1, httptest.NewRequest(http.MethodPost, "/ingest", bytes.NewReader(body1)))
	require.Equal(t, http.StatusAccepted, rec1.Code)
	require.Len(t, producer.getMessages(DefaultGeofenceTopic), 1)
	assert.Equal(t, int64(1), dedupSpy.calls.Load())

	body2, err := json.Marshal(LocationInput{
		RiderID:   "rider-p1-8",
		Lat:       12.9800,
		Lng:       77.6000,
		Timestamp: &tsStale,
	})
	require.NoError(t, err)
	rec2 := httptest.NewRecorder()
	h.HandleIngest(rec2, httptest.NewRequest(http.MethodPost, "/ingest", bytes.NewReader(body2)))
	assert.Equal(t, http.StatusOK, rec2.Code)
	assert.Contains(t, rec2.Body.String(), "out_of_order_timestamp")

	assert.Len(t, producer.getMessages(DefaultGeofenceTopic), 1)
	assert.Equal(t, int64(1), dedupSpy.calls.Load())
	track := geofencer.tracking[riderZoneKey{riderID: "rider-p1-8", zoneID: "store-ooo-01"}]
	require.NotNil(t, track)
	assert.True(t, track.inside)
}

func TestNormalizeAndValidate_RejectsNaNInfAndBadBattery(t *testing.T) {
	badBatt := -5.0
	overBatt := 105.0
	nan := math.NaN()
	inf := math.Inf(1)

	inputs := []struct {
		name  string
		input LocationInput
	}{
		{"nan lat", LocationInput{RiderID: "r1", Lat: nan, Lng: 77.5}},
		{"inf lng", LocationInput{RiderID: "r1", Lat: 12.9, Lng: inf}},
		{"nan speed", LocationInput{RiderID: "r1", Lat: 12.9, Lng: 77.5, Speed: &nan}},
		{"negative battery", LocationInput{RiderID: "r1", Lat: 12.9, Lng: 77.5, BatteryPct: &badBatt}},
		{"over 100 battery", LocationInput{RiderID: "r1", Lat: 12.9, Lng: 77.5, BatteryPct: &overBatt}},
	}
	for _, tc := range inputs {
		t.Run(tc.name, func(t *testing.T) {
			_, err := NormalizeLocation(tc.input)
			require.ErrorIs(t, err, ErrInvalidPayload)
		})
	}

	u := LocationUpdate{RiderID: "r1", Lat: math.NaN(), Lng: 77.5}
	require.Error(t, u.Validate())
}

func TestRiderTelemetrySmoother_TeleportationAndStationaryDrift(t *testing.T) {
	smoother := NewRiderTelemetrySmoother()
	now := time.Now().UTC()

	first, err := smoother.Filter(LocationUpdate{
		RiderID:   "rider-telemetry",
		Lat:       12.97160,
		Lng:       77.59460,
		Speed:     0,
		Heading:   45,
		Accuracy:  5,
		Timestamp: now,
	})
	require.NoError(t, err)

	drift, err := smoother.Filter(LocationUpdate{
		RiderID:   "rider-telemetry",
		Lat:       12.97164,
		Lng:       77.59462,
		Speed:     0.5,
		HasSpeed:  true,
		Heading:   120,
		Accuracy:  10,
		Timestamp: now.Add(20 * time.Second),
	})
	require.NoError(t, err)
	assert.True(t, drift.StationaryDrift)
	assert.Contains(t, drift.TelemetryFlags, "stationary_drift")
	assert.Equal(t, first.Lat, drift.Lat)
	assert.Equal(t, first.Lng, drift.Lng)
	assert.Equal(t, 0.0, drift.Speed)

	_, err = smoother.Filter(LocationUpdate{
		RiderID:   "rider-telemetry",
		Lat:       12.97360,
		Lng:       77.59460,
		Accuracy:  5,
		Timestamp: now.Add(23 * time.Second),
	})
	require.Error(t, err)
	assert.ErrorIs(t, err, ErrTeleportAnomaly)
	assert.ErrorIs(t, err, ErrVelocityOutlier)
}

func TestLocationHandler_TelemetryFlaggingAndTeleportHTTPStatus(t *testing.T) {
	reg := prometheus.NewRegistry()
	metrics := NewLocationMetrics(reg)
	producer := newMockKafkaProducer()
	logger := slog.New(slog.NewJSONHandler(io.Discard, nil))
	batcher, err := NewLocationBatcher(producer, "rider.location.updates", 10, time.Hour, logger)
	require.NoError(t, err)

	h := NewIngestHandler(
		IngestConfig{ProcessTimeout: time.Second, H3Resolution: 8},
		batcher, newMockPositionStore(), nil, logger, metrics,
	)
	defer h.Close()

	now := time.Now().UTC()
	ts1 := now.Add(-10 * time.Second).UnixMilli()
	batt := 11.5
	acc := 32.0
	body1, err := json.Marshal(LocationInput{
		RiderID:    "rider-flags",
		Lat:        12.9716,
		Lng:        77.5946,
		Timestamp:  &ts1,
		BatteryPct: &batt,
		Accuracy:   &acc,
	})
	require.NoError(t, err)
	rec1 := httptest.NewRecorder()
	h.HandleIngest(rec1, httptest.NewRequest(http.MethodPost, "/ingest", bytes.NewReader(body1)))
	require.Equal(t, http.StatusAccepted, rec1.Code)

	batcher.flush(context.Background())
	msgs := producer.getMessages("rider.location.updates")
	require.Len(t, msgs, 1)
	var ev LocationEvent
	require.NoError(t, json.Unmarshal(msgs[0].Value, &ev))
	assert.True(t, ev.LowBattery)
	assert.True(t, ev.WeakAccuracy)
	assert.Contains(t, ev.TelemetryFlags, "low_battery")
	assert.Contains(t, ev.TelemetryFlags, "weak_accuracy")
	require.NotNil(t, ev.BatteryPct)
	assert.InDelta(t, 11.5, *ev.BatteryPct, 1e-6)

	ts2 := now.UnixMilli()
	bodyTeleport, err := json.Marshal(LocationInput{
		RiderID:   "rider-flags",
		Lat:       13.0500,
		Lng:       77.5946,
		Timestamp: &ts2,
	})
	require.NoError(t, err)
	rec2 := httptest.NewRecorder()
	h.HandleIngest(rec2, httptest.NewRequest(http.MethodPost, "/ingest", bytes.NewReader(bodyTeleport)))
	assert.Equal(t, http.StatusUnprocessableEntity, rec2.Code)
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
	assert.Equal(t, "RiderGeofence", env.AggregateType)
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

	geofencer.AddZone(Zone{
		ID:        "store-blr-01",
		Type:      ZoneStore,
		CenterLat: 12.9716,
		CenterLng: 77.5946,
		RadiusKm:  0.2,
	})

	update := LocationUpdate{
		RiderID:    "rider-999",
		Lat:        12.9716,
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
	assert.Contains(t, store.positions, "rider-999")

	messages := producer.getMessages(DefaultGeofenceTopic)
	require.Len(t, messages, 1)
	assert.Equal(t, "rider-999", string(messages[0].Key))

	var envelope GeofenceEventEnvelope
	err = json.Unmarshal(messages[0].Value, &envelope)
	require.NoError(t, err)

	assert.Equal(t, "RiderGeofenceTransition", envelope.EventType)
	assert.Equal(t, "RiderGeofence", envelope.AggregateType)
	assert.Equal(t, "rider-999", envelope.AggregateID)
	assert.Equal(t, "ENTERED_STORE", envelope.Payload.EventType)
	assert.Equal(t, "store-blr-01", envelope.Payload.ZoneID)
}

func TestLocationBatcher_AddPropagatesEnqueueError(t *testing.T) {
	logger := slog.New(slog.NewTextHandler(io.Discard, nil))
	batcher, err := NewLocationBatcher(newMockKafkaProducer(), "rider.location.updates", 10, time.Hour, logger, WithBatchBufferCapacity(1))
	require.NoError(t, err)

	require.NoError(t, batcher.Add(LocationUpdate{RiderID: "r1"}))
	err = batcher.Add(LocationUpdate{RiderID: "r2"})
	require.ErrorIs(t, err, ErrKafkaBufferFull)
}
