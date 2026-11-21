package handler

import (
	"context"
	"io"
	"log/slog"
	"testing"
	"time"

	"github.com/prometheus/client_golang/prometheus"
	"github.com/segmentio/kafka-go"
	"github.com/stretchr/testify/assert"
	"github.com/stretchr/testify/require"
)

func TestRiderTelemetrySmoother_OutOfOrderPingRejection(t *testing.T) {
	smoother := NewRiderTelemetrySmoother()
	now := time.Now().UTC()

	// Initial ping
	ping1 := LocationUpdate{
		RiderID:   "rider-out-of-order",
		Lat:       12.9716,
		Lng:       77.5946,
		Accuracy:  5.0,
		Speed:     20.0,
		Heading:   90.0,
		Timestamp: now,
	}

	filtered1, err := smoother.Filter(ping1)
	require.NoError(t, err)
	assert.Equal(t, ping1.Lat, filtered1.Lat)

	// Second ping: older timestamp (arrived out of order)
	ping2 := LocationUpdate{
		RiderID:   "rider-out-of-order",
		Lat:       12.9718,
		Lng:       77.5948,
		Accuracy:  5.0,
		Speed:     20.0,
		Heading:   90.0,
		Timestamp: now.Add(-5 * time.Second),
	}

	_, err = smoother.Filter(ping2)
	require.Error(t, err)
	assert.ErrorIs(t, err, ErrOutOfOrderPing)
}

func TestRiderTelemetrySmoother_VelocityOutlierRejection(t *testing.T) {
	smoother := NewRiderTelemetrySmoother()
	now := time.Now().UTC()

	// Initial ping
	ping1 := LocationUpdate{
		RiderID:   "rider-speeding",
		Lat:       12.9716,
		Lng:       77.5946,
		Accuracy:  5.0,
		Speed:     20.0,
		Heading:   90.0,
		Timestamp: now,
	}

	_, err := smoother.Filter(ping1)
	require.NoError(t, err)

	// Second ping 2 seconds later: rider jumps 2 kilometers (speed = 3600 km/h -> GPS spoofing / multipath spike)
	ping2 := LocationUpdate{
		RiderID:   "rider-speeding",
		Lat:       12.9900, // ~2.04 km away
		Lng:       77.5946,
		Accuracy:  5.0,
		Speed:     20.0,
		Heading:   90.0,
		Timestamp: now.Add(2 * time.Second),
	}

	_, err = smoother.Filter(ping2)
	require.Error(t, err)
	assert.ErrorIs(t, err, ErrVelocityOutlier)
}

func TestRiderTelemetrySmoother_Smoothing(t *testing.T) {
	smoother := NewRiderTelemetrySmoother()
	now := time.Now().UTC()

	// Initial ping
	ping1 := LocationUpdate{
		RiderID:   "rider-smooth",
		Lat:       12.9700,
		Lng:       77.5900,
		Accuracy:  5.0,
		Speed:     25.0,
		Heading:   359.0,
		Timestamp: now,
	}

	_, err := smoother.Filter(ping1)
	require.NoError(t, err)

	// Second ping with slight jitter and heading crossing 0 deg boundary
	ping2 := LocationUpdate{
		RiderID:   "rider-smooth",
		Lat:       12.9702,
		Lng:       77.5902,
		Accuracy:  20.0, // noisy accuracy
		Speed:     30.0,
		Heading:   2.0, // crosses 360 boundary (diff is 3 degrees, not 357 degrees)
		Timestamp: now.Add(2 * time.Second),
	}

	smoothed, err := smoother.Filter(ping2)
	require.NoError(t, err)

	// Heading must not spin backwards 350 degrees
	assert.True(t, smoothed.Heading < 10.0 || smoothed.Heading > 355.0)
	// Speed smoothed between 25 and 30
	assert.True(t, smoothed.Speed >= 25.0 && smoothed.Speed <= 30.0)
}

func TestRiderTelemetrySmoother_RapidAndZeroDeltaGuards(t *testing.T) {
	now := time.Now().UTC()

	t.Run("zero-delta jump triggers teleport anomaly", func(t *testing.T) {
		smoother := NewRiderTelemetrySmoother()
		_, err := smoother.Filter(LocationUpdate{
			RiderID:   "rider-zero-delta",
			Lat:       12.9716,
			Lng:       77.5946,
			Timestamp: now,
		})
		require.NoError(t, err)

		_, err = smoother.Filter(LocationUpdate{
			RiderID:   "rider-zero-delta",
			Lat:       12.9736,
			Lng:       77.5946,
			Timestamp: now,
		})
		require.Error(t, err)
		assert.ErrorIs(t, err, ErrTeleportAnomaly)
	})

	t.Run("sub-0.5s jump triggers teleport anomaly", func(t *testing.T) {
		smoother := NewRiderTelemetrySmoother()
		_, err := smoother.Filter(LocationUpdate{
			RiderID:   "rider-rapid-jump",
			Lat:       12.9716,
			Lng:       77.5946,
			Timestamp: now,
		})
		require.NoError(t, err)

		_, err = smoother.Filter(LocationUpdate{
			RiderID:   "rider-rapid-jump",
			Lat:       12.9730,
			Lng:       77.5946,
			Timestamp: now.Add(200 * time.Millisecond),
		})
		require.Error(t, err)
		assert.ErrorIs(t, err, ErrTeleportAnomaly)
	})

	t.Run("sub-0.5s jitter within drift radius triggers stationary drift", func(t *testing.T) {
		smoother := NewRiderTelemetrySmoother()
		first, err := smoother.Filter(LocationUpdate{
			RiderID:   "rider-rapid-drift",
			Lat:       12.97160,
			Lng:       77.59460,
			Timestamp: now,
		})
		require.NoError(t, err)

		drift, err := smoother.Filter(LocationUpdate{
			RiderID:   "rider-rapid-drift",
			Lat:       12.97162,
			Lng:       77.59462,
			Speed:     0.5,
			HasSpeed:  true,
			Timestamp: now.Add(200 * time.Millisecond),
		})
		require.NoError(t, err)
		assert.True(t, drift.StationaryDrift)
		assert.Equal(t, first.Lat, drift.Lat)
		assert.Equal(t, first.Lng, drift.Lng)
	})
}

func TestRiderTelemetrySmoother_BoundedTTLAndCapacity(t *testing.T) {
	now := time.Now().UTC()
	smoother := NewRiderTelemetrySmootherWithLimits(10*time.Minute, 2)
	smoother.nowFn = func() time.Time { return now }

	_, err := smoother.Filter(LocationUpdate{RiderID: "r1", Lat: 12.97, Lng: 77.59, Timestamp: now})
	require.NoError(t, err)
	now = now.Add(time.Second)
	_, err = smoother.Filter(LocationUpdate{RiderID: "r2", Lat: 12.97, Lng: 77.59, Timestamp: now})
	require.NoError(t, err)
	now = now.Add(time.Second)
	_, err = smoother.Filter(LocationUpdate{RiderID: "r3", Lat: 12.97, Lng: 77.59, Timestamp: now})
	require.NoError(t, err)

	assert.Equal(t, 2, smoother.Len())
	_, _, _, okR1 := smoother.peekForTest("r1")
	assert.False(t, okR1)

	now = now.Add(15 * time.Minute)
	_, err = smoother.Filter(LocationUpdate{RiderID: "r4", Lat: 12.97, Lng: 77.59, Timestamp: now})
	require.NoError(t, err)
	assert.Equal(t, 1, smoother.Len())
}

func (s *RiderTelemetrySmoother) peekForTest(riderID string) (float64, float64, time.Time, bool) {
	s.mu.RLock()
	defer s.mu.RUnlock()
	st, ok := s.states[riderID]
	if !ok {
		return 0, 0, time.Time{}, false
	}
	return st.Lat, st.Lng, st.Timestamp, true
}

func TestLocationHandler_DoesNotMutateSmootherBeforeStoreAndEnqueueSucceed(t *testing.T) {
	logger := slog.New(slog.NewJSONHandler(io.Discard, nil))
	reg := prometheus.NewRegistry()
	metrics := NewLocationMetrics(reg)
	now := time.Now().UTC()

	t.Run("redis out-of-order rejection does not pollute smoother", func(t *testing.T) {
		smoother := NewRiderTelemetrySmoother()
		store := newMockPositionStore()
		store.positions["rider-ooo"] = LocationUpdate{
			RiderID:   "rider-ooo",
			Lat:       12.9716,
			Lng:       77.5946,
			Timestamp: now.Add(10 * time.Second),
		}
		batcher, err := NewLocationBatcher(newMockKafkaProducer(), "rider.location.updates", 10, time.Hour, logger)
		require.NoError(t, err)

		h := NewIngestHandler(
			IngestConfig{ProcessTimeout: time.Second, H3Resolution: 8},
			batcher, store, nil, logger, metrics,
			WithTelemetrySmoother(smoother),
		)
		defer h.Close()

		err = h.processUpdate(context.Background(), LocationUpdate{
			RiderID:   "rider-ooo",
			Lat:       12.9800,
			Lng:       77.6000,
			Timestamp: now,
		}, "http")
		require.ErrorIs(t, err, ErrOutOfOrderTimestamp)
		assert.Equal(t, 0, smoother.Len())
	})

	t.Run("kafka enqueue buffer full does not mutate smoother", func(t *testing.T) {
		smoother := NewRiderTelemetrySmoother()
		store := newMockPositionStore()
		batcher, err := NewLocationBatcher(
			newMockKafkaProducer(), "rider.location.updates", 10, time.Hour, logger,
			WithBatchBufferCapacity(1),
		)
		require.NoError(t, err)

		h := NewIngestHandler(
			IngestConfig{ProcessTimeout: time.Second, H3Resolution: 8},
			batcher, store, nil, logger, metrics,
			WithTelemetrySmoother(smoother),
		)
		defer h.Close()

		require.NoError(t, h.processUpdate(context.Background(), LocationUpdate{
			RiderID:   "rider-buf-1",
			Lat:       12.9716,
			Lng:       77.5946,
			Timestamp: now,
		}, "http"))
		assert.Equal(t, 1, smoother.Len())

		err = h.processUpdate(context.Background(), LocationUpdate{
			RiderID:   "rider-buf-2",
			Lat:       12.9716,
			Lng:       77.5946,
			Timestamp: now.Add(time.Second),
		}, "http")
		require.ErrorIs(t, err, ErrKafkaBufferFull)
		_, _, _, exists := smoother.peekForTest("rider-buf-2")
		assert.False(t, exists)
	})
}

func TestKafkaWriterProducer_HonorsTopic(t *testing.T) {
	writer := &kafka.Writer{Topic: "configured.topic"}
	p := NewKafkaWriterProducer(writer)
	err := p.PublishBatch(context.Background(), "other.topic", []KafkaMessage{{Key: []byte("k"), Value: []byte("v")}})
	require.Error(t, err)
	assert.Contains(t, err.Error(), "does not match target topic")
}
