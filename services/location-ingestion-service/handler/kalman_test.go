package handler

import (
	"testing"
	"time"

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
