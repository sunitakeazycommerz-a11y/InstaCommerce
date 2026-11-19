package handler

import (
	"errors"
	"fmt"
	"math"
	"sync"
	"time"
)

var (
	// ErrVelocityOutlier is returned when rider computed speed exceeds the physical limit.
	ErrVelocityOutlier = errors.New("velocity outlier: instantaneous speed exceeds physical limits")
	// ErrTeleportAnomaly is returned when instantaneous speed exceeds 150 km/h between consecutive pings.
	ErrTeleportAnomaly = errors.New("teleport anomaly: impossible speed exceeds 150 km/h")
	// ErrOutOfOrderPing is returned when an incoming ping's timestamp is older than the latest received ping.
	ErrOutOfOrderPing = errors.New("out of order ping: timestamp is older than latest known location")
)

const (
	MaxPlausibleSpeedKmh    = 80.0
	MaxTeleportSpeedKmh     = 150.0
	MinTimeDeltaSec         = 0.5
	StationaryDriftRadiusKm = 0.015
	StationaryMaxSpeedKmh   = 1.5
)

// RiderKalmanState holds the filtered coordinate and velocity state for a single rider.
type RiderKalmanState struct {
	Lat       float64
	Lng       float64
	SpeedKmh  float64
	Heading   float64
	Timestamp time.Time
}

// RiderTelemetrySmoother tracks and smooths GPS telemetry for active riders,
// rejecting velocity/teleportation outliers and suppressing stationary multipath drift.
type RiderTelemetrySmoother struct {
	mu     sync.RWMutex
	states map[string]*RiderKalmanState
}

// NewRiderTelemetrySmoother creates a new telemetry smoother.
func NewRiderTelemetrySmoother() *RiderTelemetrySmoother {
	return &RiderTelemetrySmoother{
		states: make(map[string]*RiderKalmanState),
	}
}

// Filter validates ping ordering, rejects velocity/teleportation spikes (>80 / >150 km/h),
// suppresses stationary drift jitter, and applies Kalman smoothing.
func (s *RiderTelemetrySmoother) Filter(update LocationUpdate) (LocationUpdate, error) {
	s.mu.Lock()
	defer s.mu.Unlock()

	state, exists := s.states[update.RiderID]
	if !exists {
		s.states[update.RiderID] = &RiderKalmanState{
			Lat:       update.Lat,
			Lng:       update.Lng,
			SpeedKmh:  update.Speed,
			Heading:   update.Heading,
			Timestamp: update.Timestamp,
		}
		return update, nil
	}

	if update.Timestamp.Before(state.Timestamp) {
		return update, fmt.Errorf("%w: incoming %s < last %s",
			ErrOutOfOrderPing, update.Timestamp.Format(time.RFC3339Nano), state.Timestamp.Format(time.RFC3339Nano))
	}

	dtSec := update.Timestamp.Sub(state.Timestamp).Seconds()
	distKm := haversineKm(state.Lat, state.Lng, update.Lat, update.Lng)
	var computedSpeedKmh float64
	if dtSec >= MinTimeDeltaSec {
		computedSpeedKmh = (distKm / dtSec) * 3600.0
		if computedSpeedKmh > MaxTeleportSpeedKmh && distKm > 0.05 {
			return update, fmt.Errorf("%w (%w): computed speed %.1f km/h exceeds %.1f km/h limit (dist: %.2f km in %.1fs)",
				ErrVelocityOutlier, ErrTeleportAnomaly, computedSpeedKmh, MaxTeleportSpeedKmh, distKm, dtSec)
		}
		if computedSpeedKmh > MaxPlausibleSpeedKmh && distKm > 0.05 {
			return update, fmt.Errorf("%w: computed speed %.1f km/h exceeds %.1f km/h threshold (dist: %.2f km in %.1fs)",
				ErrVelocityOutlier, computedSpeedKmh, MaxPlausibleSpeedKmh, distKm, dtSec)
		}
	}

	if dtSec >= MinTimeDeltaSec && distKm <= StationaryDriftRadiusKm && computedSpeedKmh <= StationaryMaxSpeedKmh && (!update.HasSpeed || update.Speed <= StationaryMaxSpeedKmh) {
		state.Timestamp = update.Timestamp
		state.SpeedKmh = 0
		suppressed := update
		suppressed.Lat = state.Lat
		suppressed.Lng = state.Lng
		suppressed.Speed = 0
		suppressed.Heading = state.Heading
		suppressed.StationaryDrift = true
		suppressed.TelemetryFlags = appendUniqueFlag(suppressed.TelemetryFlags, "stationary_drift")
		return suppressed, nil
	}

	accuracy := update.Accuracy
	if accuracy <= 0 {
		accuracy = 5.0
	}
	coordGain := 1.0 - math.Min(0.5, accuracy/100.0)
	if coordGain < 0.5 {
		coordGain = 0.5
	}

	smoothedLat := state.Lat + coordGain*(update.Lat-state.Lat)
	smoothedLng := state.Lng + coordGain*(update.Lng-state.Lng)
	smoothedSpeed := state.SpeedKmh + 0.7*(update.Speed-state.SpeedKmh)
	smoothedHeading := smoothAngle(state.Heading, update.Heading, 0.8)

	state.Lat = smoothedLat
	state.Lng = smoothedLng
	state.SpeedKmh = smoothedSpeed
	state.Heading = smoothedHeading
	state.Timestamp = update.Timestamp

	smoothedUpdate := update
	smoothedUpdate.Lat = smoothedLat
	smoothedUpdate.Lng = smoothedLng
	smoothedUpdate.Speed = smoothedSpeed
	smoothedUpdate.Heading = smoothedHeading
	return smoothedUpdate, nil
}

func smoothAngle(prev, curr, gain float64) float64 {
	diff := curr - prev
	for diff > 180 {
		diff -= 360
	}
	for diff < -180 {
		diff += 360
	}
	res := prev + gain*diff
	if res < 0 {
		res += 360
	} else if res >= 360 {
		res -= 360
	}
	return res
}
