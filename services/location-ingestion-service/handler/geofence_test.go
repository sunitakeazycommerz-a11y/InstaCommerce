package handler

import (
	"context"
	"encoding/json"
	"fmt"
	"testing"
	"time"

	"github.com/instacommerce/go-shared/pkg/correlation"
	"github.com/stretchr/testify/assert"
	"github.com/stretchr/testify/require"
)

func TestGeofenceEventToEnvelope_ContractStructure(t *testing.T) {
	eventTime := time.Date(2026, 11, 21, 10, 15, 30, 123456000, time.UTC)
	ev := GeofenceEvent{
		RiderID:   "rider-contract-42",
		ZoneID:    "zone-koramangala-01",
		EventType: "ENTERED_STORE",
		H3Index:   "8960145b267ffff",
		Timestamp: eventTime,
	}

	env := ev.ToEnvelope(12.9352, 77.6245)
	env.CorrelationID = "corr-geo-99"

	raw, err := json.Marshal(env)
	require.NoError(t, err)

	var root map[string]any
	require.NoError(t, json.Unmarshal(raw, &root))

	expectedID := fmt.Sprintf("geo_rider-contract-42_%d", eventTime.UnixNano())
	assert.Equal(t, expectedID, root["id"])
	assert.Equal(t, expectedID, root["eventId"])
	assert.Equal(t, "RiderGeofenceTransition", root["eventType"])
	assert.Equal(t, "RiderGeofence", root["aggregateType"])
	assert.Equal(t, "rider-contract-42", root["aggregateId"])
	assert.Equal(t, eventTime.Format(time.RFC3339Nano), root["eventTime"])
	assert.Equal(t, "v1", root["schemaVersion"])
	assert.Equal(t, "location-ingestion-service", root["source_service"])
	assert.Equal(t, "corr-geo-99", root["correlation_id"])

	payload, ok := root["payload"].(map[string]any)
	require.True(t, ok, "payload must serialize as a nested JSON object")
	assert.Equal(t, "rider-contract-42", payload["rider_id"])
	assert.Equal(t, "zone-koramangala-01", payload["zone_id"])
	assert.Equal(t, "ENTERED_STORE", payload["event_type"])
	assert.Equal(t, "8960145b267ffff", payload["h3_index"])
	assert.Equal(t, eventTime.Format(time.RFC3339Nano), payload["timestamp"])
	assert.InDelta(t, 12.9352, payload["lat"], 1e-6)
	assert.InDelta(t, 77.6245, payload["lng"], 1e-6)
}

func TestGeofenceEventToEnvelope_ZeroTimestampFallback(t *testing.T) {
	ev := GeofenceEvent{
		RiderID:   "rider-zero-ts",
		ZoneID:    "zone-1",
		EventType: "EXITED_ZONE",
		H3Index:   "8960145b267ffff",
	}
	before := time.Now().UTC().Add(-time.Second)
	env := ev.ToEnvelope(12.9716, 77.5946)
	after := time.Now().UTC().Add(time.Second)

	assert.Equal(t, "RiderGeofence", env.AggregateType)
	assert.Equal(t, "RiderGeofenceTransition", env.EventType)
	assert.False(t, env.Payload.Timestamp.IsZero())
	assert.True(t, !env.Payload.Timestamp.Before(before) && !env.Payload.Timestamp.After(after))
	parsedTime, err := time.Parse(time.RFC3339Nano, env.EventTime)
	require.NoError(t, err)
	assert.Equal(t, env.Payload.Timestamp, parsedTime)
}

func TestKafkaGeofencePublisher_NormalizesAndAttachesHeaders(t *testing.T) {
	var nilPub *KafkaGeofencePublisher
	assert.ErrorIs(t, nilPub.PublishGeofenceEvent(context.Background(), GeofenceEventEnvelope{}), ErrKafkaDisabled)

	emptyPub := NewKafkaGeofencePublisher(nil, "", nil)
	assert.ErrorIs(t, emptyPub.PublishGeofenceEvent(context.Background(), GeofenceEventEnvelope{}), ErrKafkaDisabled)

	producer := newMockKafkaProducer()
	pub := NewKafkaGeofencePublisher(producer, "", nil)

	ctx := context.WithValue(context.Background(), correlation.ContextKey, "corr-from-ctx")
	partial := GeofenceEventEnvelope{
		Payload: GeofenceEventPayload{
			RiderID:   "rider-partial-1",
			ZoneID:    "zone-partial-1",
			EventType: "NEAR_DELIVERY",
			H3Index:   "8960145b267ffff",
			Lat:       12.9716,
			Lng:       77.5946,
		},
	}

	require.NoError(t, pub.PublishGeofenceEvent(ctx, partial))

	msgs := producer.getMessages(DefaultGeofenceTopic)
	require.Len(t, msgs, 1)
	assert.Equal(t, "rider-partial-1", string(msgs[0].Key))

	headerValues := make(map[string]string, len(msgs[0].Headers))
	for _, h := range msgs[0].Headers {
		headerValues[h.Key] = string(h.Value)
	}
	assert.Equal(t, "RiderGeofenceTransition", headerValues["event_type"])
	assert.Equal(t, "RiderGeofence", headerValues["aggregate_type"])
	assert.Equal(t, "rider-partial-1", headerValues["aggregate_id"])
	assert.Equal(t, "v1", headerValues["schema_version"])
	assert.Equal(t, "location-ingestion-service", headerValues["source_service"])
	assert.Equal(t, "corr-from-ctx", headerValues["correlation_id"])
	assert.NotEmpty(t, headerValues["event_id"])

	var decoded map[string]any
	require.NoError(t, json.Unmarshal(msgs[0].Value, &decoded))
	assert.Equal(t, headerValues["event_id"], decoded["id"])
	assert.Equal(t, "RiderGeofenceTransition", decoded["eventType"])
	assert.Equal(t, "RiderGeofence", decoded["aggregateType"])
	assert.Equal(t, "rider-partial-1", decoded["aggregateId"])
	assert.Equal(t, "v1", decoded["schemaVersion"])
	assert.Equal(t, "corr-from-ctx", decoded["correlation_id"])
}

func TestLocationHandler_AsyncGeofenceWorkerDrainsOnClose(t *testing.T) {
	producer := newMockKafkaProducer()
	h, _, _, _ := setupTestHandler(t,
		WithGeofenceProducer(producer, DefaultGeofenceTopic),
		WithSynchronousGeofence(false),
	)

	ev := GeofenceEvent{
		RiderID:   "rider-async-1",
		ZoneID:    "store-async-1",
		EventType: "ENTERED_STORE",
		H3Index:   "8960145b267ffff",
		Timestamp: time.Now().UTC(),
	}
	h.geofenceQueue <- ev.ToEnvelope(12.9716, 77.5946)
	h.Close()

	msgs := producer.getMessages(DefaultGeofenceTopic)
	require.Len(t, msgs, 1)
	assert.Equal(t, "rider-async-1", string(msgs[0].Key))
}

func TestInMemoryGeofenceDeduplicator_SweepsExpiredEntries(t *testing.T) {
	dedup := NewInMemoryGeofenceDeduplicator()
	ctx := context.Background()
	for i := 0; i < 1005; i++ {
		key := fmt.Sprintf("expired-%d", i)
		require.True(t, dedup.TryAcquire(ctx, key, -time.Millisecond))
	}
	require.True(t, dedup.TryAcquire(ctx, "active-key", time.Minute))
	assert.False(t, dedup.TryAcquire(ctx, "active-key", time.Minute))

	dedup.mu.Lock()
	count := len(dedup.entries)
	dedup.mu.Unlock()
	assert.LessOrEqual(t, count, 5)
}
