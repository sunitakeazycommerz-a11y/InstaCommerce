// Package handler provides production GPS location ingestion over HTTP and WebSocket.
package handler

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"log/slog"
	"math"
	"time"

	"go.opentelemetry.io/otel/trace"
)

const (
	defaultMaxBodyBytes         = 1 << 20
	defaultWSReadLimit          = 1 << 20
	defaultProcessTimeout       = 3 * time.Second
	maxLocationHandlerBody      = 256 << 10
	LowBatteryThresholdPct      = 15.0
	WeakAccuracyThresholdMeters = 25.0
)

var (
	// ErrInvalidPayload indicates a malformed or out-of-bounds GPS update.
	ErrInvalidPayload = errors.New("invalid payload")
	// ErrOutOfOrderTimestamp indicates an incoming GPS update has a timestamp <= the stored timestamp.
	ErrOutOfOrderTimestamp = errors.New("out of order timestamp")
)

// LocationInput is the wire format accepted by /ingest, /ingest/location, /ingest/batch, and /ingest/ws.
type LocationInput struct {
	RiderID    string   `json:"rider_id"`
	Lat        float64  `json:"lat"`
	Lng        float64  `json:"lng"`
	Timestamp  *int64   `json:"timestamp,omitempty"`
	Speed      *float64 `json:"speed,omitempty"`
	Heading    *float64 `json:"heading,omitempty"`
	Accuracy   *float64 `json:"accuracy,omitempty"`
	BatteryPct *float64 `json:"battery_pct,omitempty"`
}

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

func isFinite(v float64) bool {
	return !math.IsNaN(v) && !math.IsInf(v, 0)
}

func appendUniqueFlag(flags []string, flag string) []string {
	for _, f := range flags {
		if f == flag {
			return flags
		}
	}
	return append(flags, flag)
}

// NormalizeLocation validates a LocationInput and converts it into a canonical LocationUpdate.
func NormalizeLocation(input LocationInput) (LocationUpdate, error) {
	if input.RiderID == "" {
		return LocationUpdate{}, fmt.Errorf("%w: rider_id is required", ErrInvalidPayload)
	}
	if !isFinite(input.Lat) || input.Lat < -90 || input.Lat > 90 {
		return LocationUpdate{}, fmt.Errorf("%w: lat out of range", ErrInvalidPayload)
	}
	if !isFinite(input.Lng) || input.Lng < -180 || input.Lng > 180 {
		return LocationUpdate{}, fmt.Errorf("%w: lng out of range", ErrInvalidPayload)
	}
	if input.Speed != nil && (!isFinite(*input.Speed) || *input.Speed < 0) {
		return LocationUpdate{}, fmt.Errorf("%w: speed must be >= 0", ErrInvalidPayload)
	}
	if input.Heading != nil && (!isFinite(*input.Heading) || *input.Heading < 0 || *input.Heading >= 360) {
		return LocationUpdate{}, fmt.Errorf("%w: heading must be between 0 and 360", ErrInvalidPayload)
	}
	if input.Accuracy != nil {
		if !isFinite(*input.Accuracy) || *input.Accuracy < 0 {
			return LocationUpdate{}, fmt.Errorf("%w: accuracy must be >= 0", ErrInvalidPayload)
		}
		if *input.Accuracy > 50.0 {
			return LocationUpdate{}, fmt.Errorf("%w: accuracy exceeds 50m limit (poor GPS fix)", ErrInvalidPayload)
		}
	}
	if input.BatteryPct != nil && (!isFinite(*input.BatteryPct) || *input.BatteryPct < 0 || *input.BatteryPct > 100) {
		return LocationUpdate{}, fmt.Errorf("%w: battery_pct must be in [0, 100]", ErrInvalidPayload)
	}

	var ts time.Time
	now := time.Now().UTC()
	if input.Timestamp == nil || *input.Timestamp <= 0 {
		ts = now
	} else {
		ts = time.UnixMilli(*input.Timestamp).UTC()
		if ts.After(now.Add(1 * time.Minute)) {
			return LocationUpdate{}, fmt.Errorf("%w: timestamp too far in the future", ErrInvalidPayload)
		}
	}

	u := LocationUpdate{
		RiderID:   input.RiderID,
		Lat:       input.Lat,
		Lng:       input.Lng,
		Timestamp: ts,
	}
	if input.Speed != nil {
		u.Speed = *input.Speed
		u.HasSpeed = true
	}
	if input.Heading != nil {
		u.Heading = *input.Heading
		u.HasHeading = true
	}
	if input.Accuracy != nil {
		u.Accuracy = *input.Accuracy
		u.HasAccuracy = true
	}
	if input.BatteryPct != nil {
		u.BatteryPct = *input.BatteryPct
		u.HasBatteryPct = true
	}
	u.classifyTelemetryFlags()
	return u, nil
}

// Validate checks that all required fields are present and within physically plausible ranges.
func (u *LocationUpdate) Validate() error {
	if u.RiderID == "" {
		return fmt.Errorf("location: rider_id is required")
	}
	if !isFinite(u.Lat) || u.Lat < -90 || u.Lat > 90 {
		return fmt.Errorf("location: lat %f out of range [-90, 90]", u.Lat)
	}
	if !isFinite(u.Lng) || u.Lng < -180 || u.Lng > 180 {
		return fmt.Errorf("location: lng %f out of range [-180, 180]", u.Lng)
	}
	if !isFinite(u.Accuracy) || u.Accuracy < 0 {
		return fmt.Errorf("location: accuracy must be >= 0")
	}
	if u.Accuracy > 50.0 {
		return fmt.Errorf("location: accuracy %.1fm exceeds 50m threshold (poor GPS fix)", u.Accuracy)
	}
	if !isFinite(u.Speed) || u.Speed < 0 {
		return fmt.Errorf("location: speed must be >= 0")
	}
	if !isFinite(u.Heading) || u.Heading < 0 || u.Heading >= 360 {
		return fmt.Errorf("location: heading must be in [0, 360)")
	}
	if !isFinite(u.BatteryPct) || u.BatteryPct < 0 || u.BatteryPct > 100 {
		return fmt.Errorf("location: battery_pct must be in [0, 100]")
	}
	if u.Timestamp.IsZero() {
		u.Timestamp = time.Now().UTC()
	}
	u.classifyTelemetryFlags()
	return nil
}

func (u *LocationUpdate) classifyTelemetryFlags() {
	if (u.HasBatteryPct || u.BatteryPct > 0) && u.BatteryPct < LowBatteryThresholdPct {
		u.LowBattery = true
		u.TelemetryFlags = appendUniqueFlag(u.TelemetryFlags, "low_battery")
	}
	if (u.HasAccuracy || u.Accuracy > 0) && u.Accuracy > WeakAccuracyThresholdMeters {
		u.WeakAccuracy = true
		u.TelemetryFlags = appendUniqueFlag(u.TelemetryFlags, "weak_accuracy")
	}
}

// LatestPositionStore abstracts persistence of the most recent rider position.
type LatestPositionStore interface {
	Update(ctx context.Context, update LocationUpdate) error
}

// GeofencePublisher abstracts publishing geofence transition events.
type GeofencePublisher interface {
	PublishGeofenceEvent(ctx context.Context, envelope GeofenceEventEnvelope) error
}

// KafkaGeofencePublisher publishes geofence transition envelopes to Kafka.
type KafkaGeofencePublisher struct {
	producer KafkaProducer
	topic    string
	logger   *slog.Logger
}

// NewKafkaGeofencePublisher creates a new KafkaGeofencePublisher.
func NewKafkaGeofencePublisher(producer KafkaProducer, topic string, logger *slog.Logger) *KafkaGeofencePublisher {
	if topic == "" {
		topic = DefaultGeofenceTopic
	}
	return &KafkaGeofencePublisher{
		producer: producer,
		topic:    topic,
		logger:   logger,
	}
}

// PublishGeofenceEvent serializes the envelope and publishes it to Kafka.
func (p *KafkaGeofencePublisher) PublishGeofenceEvent(ctx context.Context, envelope GeofenceEventEnvelope) error {
	data, err := json.Marshal(envelope)
	if err != nil {
		return fmt.Errorf("geofence: marshal envelope: %w", err)
	}
	msg := KafkaMessage{
		Key:   []byte(envelope.AggregateID),
		Value: data,
	}
	if err := p.producer.PublishBatch(ctx, p.topic, []KafkaMessage{msg}); err != nil {
		if p.logger != nil {
			p.logger.ErrorContext(ctx, "geofence publish failed",
				"topic", p.topic, "rider_id", envelope.AggregateID, "error", err)
		}
		return err
	}
	return nil
}

// IngestConfig holds runtime limits for HTTP, batch, and WebSocket ingestion.
type IngestConfig struct {
	MaxBodyBytes     int64
	WebSocketReadMax int64
	ProcessTimeout   time.Duration
	H3Resolution     int
}

// LocationHandlerOption configures optional behavior on LocationHandler.
type LocationHandlerOption func(*LocationHandler)

func WithGeofencePublisher(publisher GeofencePublisher) LocationHandlerOption {
	return func(h *LocationHandler) { h.geofencePublisher = publisher }
}

func WithGeofenceProducer(producer KafkaProducer, topic string) LocationHandlerOption {
	return func(h *LocationHandler) {
		if topic == "" {
			topic = DefaultGeofenceTopic
		}
		h.geofencePublisher = NewKafkaGeofencePublisher(producer, topic, h.logger)
	}
}

func WithSynchronousGeofence(sync bool) LocationHandlerOption {
	return func(h *LocationHandler) { h.synchronousGeo = sync }
}

func WithTelemetrySmoother(smoother *RiderTelemetrySmoother) LocationHandlerOption {
	return func(h *LocationHandler) { h.telemetrySmoother = smoother }
}

func WithConnectionRateLimiter(limiter *ConnectionRateLimiter) LocationHandlerOption {
	return func(h *LocationHandler) { h.rateLimiter = limiter }
}

func WithTracer(tracer trace.Tracer) LocationHandlerOption {
	return func(h *LocationHandler) { h.tracer = tracer }
}
