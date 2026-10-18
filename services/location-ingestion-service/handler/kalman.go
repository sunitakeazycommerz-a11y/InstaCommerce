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
	// ErrOutOfOrderPing is returned when an incoming ping's timestamp is older than the latest received ping.
	ErrOutOfOrderPing = errors.New("out of order ping: timestamp is older than latest known location")
)

const (
	// MaxPlausibleSpeedKmh is the 80 km/h cap for two-wheeler delivery riders in urban corridors (Issue #613).
	MaxPlausibleSpeedKmh = 80.0

	// MinTimeDeltaSec is the minimum time between pings to perform velocity checks.
	MinTimeDeltaSec = 0.5
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
// discarding multipath reflection spikes and teleportation spoofing (Issue #613).
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

// Filter evaluates an incoming GPS update against prior state, performs velocity
// outlier checks, and applies Kalman smoothing to coordinates, speed, and heading.
func (s *RiderTelemetrySmoother) Filter(update LocationUpdate) (LocationUpdate, error) {
	s.mu.Lock()
	defer s.mu.Unlock()

	state, exists := s.states[update.RiderID]
	if !exists {
		// First ping for this rider: initialize state
		s.states[update.RiderID] = &RiderKalmanState{
			Lat:       update.Lat,
			Lng:       update.Lng,
			SpeedKmh:  update.Speed,
			Heading:   update.Heading,
			Timestamp: update.Timestamp,
		}
		return update, nil
	}

	// 1. Out-of-order ping rejection (Issue #597)
	if update.Timestamp.Before(state.Timestamp) {
		return update, fmt.Errorf("%w: incoming %s < last %s",
			ErrOutOfOrderPing, update.Timestamp.Format(time.RFC3339Nano), state.Timestamp.Format(time.RFC3339Nano))
	}

	dtSec := update.Timestamp.Sub(state.Timestamp).Seconds()

	// 2. Velocity outlier anomaly detection (Issue #613)
	if dtSec >= MinTimeDeltaSec {
		distKm := haversineKm(state.Lat, state.Lng, update.Lat, update.Lng)
		computedSpeedKmh := (distKm / dtSec) * 3600.0

		// If computed speed exceeds 80 km/h and distance is non-trivial (>50m), reject
		if computedSpeedKmh > MaxPlausibleSpeedKmh && distKm > 0.05 {
			return update, fmt.Errorf("%w: computed speed %.1f km/h exceeds %.1f km/h threshold (dist: %.2f km in %.1fs)",
				ErrVelocityOutlier, computedSpeedKmh, MaxPlausibleSpeedKmh, distKm, dtSec)
		}
	}

	// 3. Kalman filter coordinate and speed smoothing (Issue #613)
	// Compute adaptive Kalman gain based on reported GPS accuracy.
	// For high-accuracy fixes (accuracy <= 5m), gain is near 1.0 (95-98% trust in GPS measurement).
	// For noisy fixes (accuracy > 20m), gain drops to dampen multipath jitter.
	accuracy := update.Accuracy
	if accuracy <= 0 {
		accuracy = 5.0
	}
	coordGain := 1.0 - math.Min(0.5, accuracy/100.0)
	if coordGain < 0.5 {
		coordGain = 0.5
	}

	// Smooth coordinates
	smoothedLat := state.Lat + coordGain*(update.Lat-state.Lat)
	smoothedLng := state.Lng + coordGain*(update.Lng-state.Lng)

	// Smooth speed with 70% measurement weighting
	smoothedSpeed := state.SpeedKmh + 0.7*(update.Speed-state.SpeedKmh)

	// Smooth heading around circular 0/360 boundary
	smoothedHeading := smoothAngle(state.Heading, update.Heading, 0.8)

	// Update state
	state.Lat = smoothedLat
	state.Lng = smoothedLng
	state.SpeedKmh = smoothedSpeed
	state.Heading = smoothedHeading
	state.Timestamp = update.Timestamp

	// Return smoothed update
	smoothedUpdate := update
	smoothedUpdate.Lat = smoothedLat
	smoothedUpdate.Lng = smoothedLng
	smoothedUpdate.Speed = smoothedSpeed
	smoothedUpdate.Heading = smoothedHeading

	return smoothedUpdate, nil
}

// smoothAngle interpolates between two angles in degrees [0, 360) using circular shortest path.
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
