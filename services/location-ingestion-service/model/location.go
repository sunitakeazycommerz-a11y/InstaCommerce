package model

import (
	"fmt"
	"math"
	"time"
)

const (
	LowBatteryThresholdPct      = 15.0
	WeakAccuracyThresholdMeters = 25.0
)

// LocationUpdate represents a validated GPS ping from a delivery rider.
type LocationUpdate struct {
	RiderID         string    `json:"rider_id"`
	Lat             float64   `json:"lat"`
	Lng             float64   `json:"lng"`
	Accuracy        float64   `json:"accuracy_meters"`
	Speed           float64   `json:"speed_kmh"`
	Heading         float64   `json:"heading_degrees"`
	Timestamp       time.Time `json:"timestamp"`
	BatteryPct      float64   `json:"battery_pct"`
	H3Index         string    `json:"h3_index,omitempty"`
	Geofence        bool      `json:"geofence_hit,omitempty"`
	Source          string    `json:"source,omitempty"`
	LowBattery      bool      `json:"low_battery,omitempty"`
	WeakAccuracy    bool      `json:"weak_accuracy,omitempty"`
	StationaryDrift bool      `json:"stationary_drift,omitempty"`
	TelemetryFlags  []string  `json:"telemetry_flags,omitempty"`
	HasSpeed        bool      `json:"-"`
	HasHeading      bool      `json:"-"`
	HasAccuracy     bool      `json:"-"`
	HasBatteryPct   bool      `json:"-"`
}

// IsFinite reports whether v is neither NaN nor Inf.
func IsFinite(v float64) bool {
	return !math.IsNaN(v) && !math.IsInf(v, 0)
}

// AppendUniqueFlag appends flag to flags only if not already present.
func AppendUniqueFlag(flags []string, flag string) []string {
	for _, f := range flags {
		if f == flag {
			return flags
		}
	}
	return append(flags, flag)
}

// Validate checks that all required fields are present and within physically plausible ranges.
func (u *LocationUpdate) Validate() error {
	if u.RiderID == "" {
		return fmt.Errorf("location: rider_id is required")
	}
	if !IsFinite(u.Lat) || u.Lat < -90 || u.Lat > 90 {
		return fmt.Errorf("location: lat %f out of range [-90, 90]", u.Lat)
	}
	if !IsFinite(u.Lng) || u.Lng < -180 || u.Lng > 180 {
		return fmt.Errorf("location: lng %f out of range [-180, 180]", u.Lng)
	}
	if !IsFinite(u.Accuracy) || u.Accuracy < 0 {
		return fmt.Errorf("location: accuracy must be >= 0")
	}
	if u.Accuracy > 50.0 {
		return fmt.Errorf("location: accuracy %.1fm exceeds 50m threshold (poor GPS fix)", u.Accuracy)
	}
	if !IsFinite(u.Speed) || u.Speed < 0 {
		return fmt.Errorf("location: speed must be >= 0")
	}
	if !IsFinite(u.Heading) || u.Heading < 0 || u.Heading >= 360 {
		return fmt.Errorf("location: heading must be in [0, 360)")
	}
	if !IsFinite(u.BatteryPct) || u.BatteryPct < 0 || u.BatteryPct > 100 {
		return fmt.Errorf("location: battery_pct must be in [0, 100]")
	}
	if u.Timestamp.IsZero() {
		u.Timestamp = time.Now().UTC()
	}
	u.ClassifyTelemetryFlags()
	return nil
}

// ClassifyTelemetryFlags sets LowBattery/WeakAccuracy flags and records TelemetryFlags.
func (u *LocationUpdate) ClassifyTelemetryFlags() {
	if (u.HasBatteryPct || u.BatteryPct > 0) && u.BatteryPct < LowBatteryThresholdPct {
		u.LowBattery = true
		u.TelemetryFlags = AppendUniqueFlag(u.TelemetryFlags, "low_battery")
	}
	if (u.HasAccuracy || u.Accuracy > 0) && u.Accuracy > WeakAccuracyThresholdMeters {
		u.WeakAccuracy = true
		u.TelemetryFlags = AppendUniqueFlag(u.TelemetryFlags, "weak_accuracy")
	}
}
