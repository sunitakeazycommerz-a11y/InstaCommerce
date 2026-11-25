package handler

import (
	"context"
	"errors"
	"io"
	"log/slog"
	"net/http"
	"net/http/httptest"
	"strings"
	"sync"
	"sync/atomic"
	"testing"
	"time"

	"github.com/gorilla/websocket"
	"github.com/prometheus/client_golang/prometheus"
	"github.com/stretchr/testify/assert"
	"github.com/stretchr/testify/require"
)

type recordingRedisStore struct {
	calls atomic.Int32
	err   error
}

func (r *recordingRedisStore) Update(context.Context, LocationUpdate) error {
	r.calls.Add(1)
	return r.err
}

type recordingGeofencePublisher struct {
	events []KafkaMessage
}

func (r *recordingGeofencePublisher) PublishBatch(_ context.Context, _ string, messages []KafkaMessage) error {
	r.events = append(r.events, messages...)
	return nil
}

type noopBatchProducer struct{}

func (noopBatchProducer) PublishBatch(context.Context, string, []KafkaMessage) error {
	return nil
}

func TestIngest_KafkaBufferFullDoesNotPartiallyMutateRedisGeofencerOrSmoother(t *testing.T) {
	logger := slog.New(slog.NewTextHandler(io.Discard, nil))
	metrics := NewLocationMetrics(prometheus.NewRegistry())

	batcher, err := NewLocationBatcher(
		noopBatchProducer{},
		"rider.location.updates",
		10,
		time.Minute,
		logger,
		WithBatchBufferCapacity(1),
	)
	require.NoError(t, err)
	require.NoError(t, batcher.Enqueue(LocationUpdate{RiderID: "r-existing", Lat: 12.97, Lng: 77.59}))

	redisStore := &recordingRedisStore{}
	geofencer := NewGeofenceChecker(8)
	geofencer.AddZone(Zone{
		ID:        "zone-1",
		Type:      ZoneStore,
		CenterLat: 12.9352,
		CenterLng: 77.6245,
		RadiusKm:  2.0,
	})
	geoPub := &recordingGeofencePublisher{}
	smoother := NewRiderTelemetrySmoother()

	h := NewIngestHandler(
		IngestConfig{ProcessTimeout: time.Second, H3Resolution: 8},
		batcher,
		redisStore,
		geofencer,
		logger,
		metrics,
		WithGeofenceProducer(geoPub, DefaultGeofenceTopic),
		WithSynchronousGeofence(true),
		WithTelemetrySmoother(smoother),
	)

	ts := time.Now().UTC()
	tsMs := ts.UnixMilli()
	err = h.ProcessInput(context.Background(), LocationInput{
		RiderID:   "rider-fail",
		Lat:       12.9352,
		Lng:       77.6245,
		Timestamp: &tsMs,
	}, "http")
	require.Error(t, err)
	assert.ErrorIs(t, err, ErrKafkaBufferFull)
	assert.Zero(t, redisStore.calls.Load(), "redis must not be mutated when Kafka buffer is full")
	assert.Empty(t, geoPub.events, "geofence event must not emit when Kafka buffer is full")
	_, _, previewErr := smoother.Preview(LocationUpdate{
		RiderID:   "rider-fail",
		Lat:       12.9352,
		Lng:       77.6245,
		Timestamp: ts,
	})
	assert.NoError(t, previewErr, "smoother state must not commit when Kafka buffer is full")
}

func TestIngest_RedisFailureReleasesBatchReservation(t *testing.T) {
	logger := slog.New(slog.NewTextHandler(io.Discard, nil))
	metrics := NewLocationMetrics(prometheus.NewRegistry())

	batcher, err := NewLocationBatcher(
		noopBatchProducer{},
		"rider.location.updates",
		10,
		time.Minute,
		logger,
		WithBatchBufferCapacity(1),
	)
	require.NoError(t, err)

	redisStore := &recordingRedisStore{err: errors.New("redis unavailable")}
	h := NewIngestHandler(
		IngestConfig{ProcessTimeout: time.Second},
		batcher,
		redisStore,
		nil,
		logger,
		metrics,
	)

	err = h.ProcessInput(context.Background(), LocationInput{
		RiderID: "rider-1",
		Lat:     12.9352,
		Lng:     77.6245,
	}, "http")
	require.Error(t, err)
	assert.Equal(t, int32(1), redisStore.calls.Load())

	// Reserved slot must be released on Redis error so subsequent update can enqueue.
	redisStore.err = nil
	err = h.ProcessInput(context.Background(), LocationInput{
		RiderID: "rider-1",
		Lat:     12.9353,
		Lng:     77.6246,
	}, "http")
	require.NoError(t, err)
}

func TestWebSocket_ConcurrentWriteControlPingAndCloseWebSocketsRaceFree(t *testing.T) {
	logger := slog.New(slog.NewTextHandler(io.Discard, nil))
	h := NewIngestHandler(IngestConfig{}, nil, nil, nil, logger, NewLocationMetrics(prometheus.NewRegistry()))

	connReady := make(chan *websocket.Conn, 1)
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		upgrader := websocket.Upgrader{CheckOrigin: func(*http.Request) bool { return true }}
		c, err := upgrader.Upgrade(w, r, nil)
		if err != nil {
			return
		}
		if !h.registerWSConn(c) {
			_ = c.Close()
			return
		}
		h.wsWG.Add(1)
		defer h.wsWG.Done()
		defer h.unregisterWSConn(c)
		defer c.Close()

		connReady <- c
		for {
			if _, _, readErr := c.ReadMessage(); readErr != nil {
				return
			}
		}
	}))
	defer srv.Close()

	wsURL := "ws" + strings.TrimPrefix(srv.URL, "http")
	client, _, err := websocket.DefaultDialer.Dial(wsURL, nil)
	require.NoError(t, err)
	defer client.Close()

	serverConn := <-connReady
	var wg sync.WaitGroup
	wg.Add(1)
	go func() {
		defer wg.Done()
		for i := 0; i < 20; i++ {
			if err := serverConn.WriteControl(websocket.PingMessage, nil, time.Now().Add(time.Second)); err != nil {
				return
			}
			time.Sleep(time.Millisecond)
		}
	}()

	time.Sleep(3 * time.Millisecond)
	h.CloseWebSockets()
	wg.Wait()
}
