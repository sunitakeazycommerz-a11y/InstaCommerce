package handler

import (
	"container/list"
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
	MaxPlausibleSpeedKmh     = 80.0
	MaxTeleportSpeedKmh      = 150.0
	MinTimeDeltaSec          = 0.5
	StationaryDriftRadiusKm  = 0.015
	StationaryMaxSpeedKmh    = 1.5
	DefaultSmootherStateTTL  = 1 * time.Hour
	DefaultSmootherMaxStates = 50000
)

// RiderKalmanState holds the filtered coordinate and velocity state for a single rider.
type RiderKalmanState struct {
	Lat        float64
	Lng        float64
	SpeedKmh   float64
	Heading    float64
	Timestamp  time.Time
	LastSeenAt time.Time
	elem       *list.Element
}

// RiderTelemetrySmoother tracks and smooths GPS telemetry for active riders,
// rejecting velocity/teleportation outliers and suppressing stationary multipath drift.
type RiderTelemetrySmoother struct {
	mu        sync.RWMutex
	states    map[string]*RiderKalmanState
	lru       *list.List
	stateTTL  time.Duration
	maxStates int
	nowFn     func() time.Time
}

// NewRiderTelemetrySmoother creates a new telemetry smoother with default TTL and capacity bounds.
func NewRiderTelemetrySmoother() *RiderTelemetrySmoother {
	return NewRiderTelemetrySmootherWithLimits(DefaultSmootherStateTTL, DefaultSmootherMaxStates)
}

// NewRiderTelemetrySmootherWithLimits creates a bounded telemetry smoother.
func NewRiderTelemetrySmootherWithLimits(ttl time.Duration, maxStates int) *RiderTelemetrySmoother {
	if ttl <= 0 {
		ttl = DefaultSmootherStateTTL
	}
	if maxStates <= 0 {
		maxStates = DefaultSmootherMaxStates
	}
	return &RiderTelemetrySmoother{
		states:    make(map[string]*RiderKalmanState),
		lru:       list.New(),
		stateTTL:  ttl,
		maxStates: maxStates,
		nowFn:     func() time.Time { return time.Now().UTC() },
	}
}

// Filter validates ping ordering, rejects velocity/teleportation spikes (>80 / >150 km/h),
// suppresses stationary drift jitter, and applies Kalman smoothing.
func (s *RiderTelemetrySmoother) Filter(update LocationUpdate) (LocationUpdate, error) {
	s.mu.Lock()
	defer s.mu.Unlock()

	now := s.nowFn()
	s.evictExpiredLocked(now)

	state, exists := s.states[update.RiderID]
	smoothed, nextState, err := s.previewAgainstState(state, exists, update)
	if err != nil {
		return update, err
	}
	s.commitLocked(update.RiderID, nextState, now)
	return smoothed, nil
}

// Preview computes smoothed telemetry without mutating per-rider state.
func (s *RiderTelemetrySmoother) Preview(update LocationUpdate) (LocationUpdate, RiderKalmanState, error) {
	s.mu.RLock()
	defer s.mu.RUnlock()

	state, exists := s.activeStateLocked(update.RiderID, s.nowFn())
	return s.previewAgainstState(state, exists, update)
}

// Commit stores a previously previewed state transition without regressing newer timestamps.
func (s *RiderTelemetrySmoother) Commit(riderID string, nextState RiderKalmanState) {
	s.mu.Lock()
	defer s.mu.Unlock()

	now := s.nowFn()
	s.evictExpiredLocked(now)
	s.commitLocked(riderID, nextState, now)
}

// Len returns the number of active rider states currently tracked.
func (s *RiderTelemetrySmoother) Len() int {
	s.mu.RLock()
	defer s.mu.RUnlock()
	return len(s.states)
}

func (s *RiderTelemetrySmoother) activeStateLocked(riderID string, now time.Time) (*RiderKalmanState, bool) {
	state, exists := s.states[riderID]
	if !exists {
		return nil, false
	}
	if s.stateTTL > 0 && now.Sub(state.LastSeenAt) > s.stateTTL {
		return nil, false
	}
	return state, true
}

func (s *RiderTelemetrySmoother) evictExpiredLocked(now time.Time) {
	if s.stateTTL <= 0 || s.lru == nil {
		return
	}
	for back := s.lru.Back(); back != nil; {
		prev := back.Prev()
		riderID, ok := back.Value.(string)
		if !ok {
			s.lru.Remove(back)
			back = prev
			continue
		}
		st := s.states[riderID]
		if st == nil || now.Sub(st.LastSeenAt) > s.stateTTL {
			s.lru.Remove(back)
			delete(s.states, riderID)
			back = prev
			continue
		}
		break
	}
}

func (s *RiderTelemetrySmoother) evictOldestLocked() {
	if s.lru == nil {
		return
	}
	back := s.lru.Back()
	if back == nil {
		return
	}
	s.lru.Remove(back)
	if riderID, ok := back.Value.(string); ok {
		delete(s.states, riderID)
	}
}

func (s *RiderTelemetrySmoother) commitLocked(riderID string, candidate RiderKalmanState, now time.Time) {
	if existing, exists := s.states[riderID]; exists {
		if candidate.Timestamp.Before(existing.Timestamp) {
			return
		}
		existing.Lat = candidate.Lat
		existing.Lng = candidate.Lng
		existing.SpeedKmh = candidate.SpeedKmh
		existing.Heading = candidate.Heading
		existing.Timestamp = candidate.Timestamp
		existing.LastSeenAt = now
		if existing.elem != nil {
			s.lru.MoveToFront(existing.elem)
		} else if s.lru != nil {
			existing.elem = s.lru.PushFront(riderID)
		}
		return
	}
	for s.maxStates > 0 && len(s.states) >= s.maxStates {
		s.evictOldestLocked()
	}
	st := &RiderKalmanState{
		Lat:        candidate.Lat,
		Lng:        candidate.Lng,
		SpeedKmh:   candidate.SpeedKmh,
		Heading:    candidate.Heading,
		Timestamp:  candidate.Timestamp,
		LastSeenAt: now,
	}
	if s.lru != nil {
		st.elem = s.lru.PushFront(riderID)
	}
	s.states[riderID] = st
}

func (s *RiderTelemetrySmoother) previewAgainstState(state *RiderKalmanState, exists bool, update LocationUpdate) (LocationUpdate, RiderKalmanState, error) {
	if !exists {
		return update, RiderKalmanState{
			Lat:       update.Lat,
			Lng:       update.Lng,
			SpeedKmh:  update.Speed,
			Heading:   update.Heading,
			Timestamp: update.Timestamp,
		}, nil
	}

	if update.Timestamp.Before(state.Timestamp) {
		return update, RiderKalmanState{}, fmt.Errorf("%w: incoming %s < last %s",
			ErrOutOfOrderPing, update.Timestamp.Format(time.RFC3339Nano), state.Timestamp.Format(time.RFC3339Nano))
	}

	dtSec := update.Timestamp.Sub(state.Timestamp).Seconds()
	distKm := haversineKm(state.Lat, state.Lng, update.Lat, update.Lng)
	if distKm > 0.05 && dtSec <= 0 {
		return update, RiderKalmanState{}, fmt.Errorf("%w (%w): jump %.2f km with non-positive delta %.3fs",
			ErrVelocityOutlier, ErrTeleportAnomaly, distKm, dtSec)
	}
	var computedSpeedKmh float64
	if dtSec > 0 {
		computedSpeedKmh = (distKm / dtSec) * 3600.0
		if distKm > 0.05 {
			if computedSpeedKmh > MaxTeleportSpeedKmh {
				return update, RiderKalmanState{}, fmt.Errorf("%w (%w): computed speed %.1f km/h exceeds %.1f km/h limit (dist: %.2f km in %.1fs)",
					ErrVelocityOutlier, ErrTeleportAnomaly, computedSpeedKmh, MaxTeleportSpeedKmh, distKm, dtSec)
			}
			if computedSpeedKmh > MaxPlausibleSpeedKmh {
				return update, RiderKalmanState{}, fmt.Errorf("%w: computed speed %.1f km/h exceeds %.1f km/h threshold (dist: %.2f km in %.1fs)",
					ErrVelocityOutlier, computedSpeedKmh, MaxPlausibleSpeedKmh, distKm, dtSec)
			}
		}
	}

	stationarySpeedOK := dtSec < MinTimeDeltaSec || computedSpeedKmh <= StationaryMaxSpeedKmh
	if distKm <= StationaryDriftRadiusKm && stationarySpeedOK && (!update.HasSpeed || update.Speed <= StationaryMaxSpeedKmh) {
		next := RiderKalmanState{
			Lat:       state.Lat,
			Lng:       state.Lng,
			SpeedKmh:  0,
			Heading:   state.Heading,
			Timestamp: update.Timestamp,
		}
		suppressed := update
		suppressed.Lat = state.Lat
		suppressed.Lng = state.Lng
		suppressed.Speed = 0
		suppressed.Heading = state.Heading
		suppressed.StationaryDrift = true
		suppressed.TelemetryFlags = appendUniqueFlag(suppressed.TelemetryFlags, "stationary_drift")
		return suppressed, next, nil
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

	next := RiderKalmanState{
		Lat:       smoothedLat,
		Lng:       smoothedLng,
		SpeedKmh:  smoothedSpeed,
		Heading:   smoothedHeading,
		Timestamp: update.Timestamp,
	}

	smoothedUpdate := update
	smoothedUpdate.Lat = smoothedLat
	smoothedUpdate.Lng = smoothedLng
	smoothedUpdate.Speed = smoothedSpeed
	smoothedUpdate.Heading = smoothedHeading
	return smoothedUpdate, next, nil
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
