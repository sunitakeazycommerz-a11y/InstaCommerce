// Package handler provides production GPS location ingestion over HTTP and WebSocket.
package handler

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"log/slog"
	"time"

	"github.com/instacommerce/go-shared/pkg/correlation"
	"github.com/instacommerce/location-ingestion-service/model"
	"github.com/segmentio/kafka-go"
	"go.opentelemetry.io/otel/trace"
)

const (
	defaultMaxBodyBytes         = 1 << 20
	defaultWSReadLimit          = 1 << 20
	defaultProcessTimeout       = 3 * time.Second
	maxLocationHandlerBody      = 256 << 10
	LowBatteryThresholdPct      = model.LowBatteryThresholdPct
	WeakAccuracyThresholdMeters = model.WeakAccuracyThresholdMeters
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
type LocationUpdate = model.LocationUpdate

func isFinite(v float64) bool {
	return model.IsFinite(v)
}

func appendUniqueFlag(flags []string, flag string) []string {
	return model.AppendUniqueFlag(flags, flag)
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
	u.ClassifyTelemetryFlags()
	return u, nil
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
	if p == nil || p.producer == nil {
		return ErrKafkaDisabled
	}
	if ctx == nil {
		ctx = context.Background()
	}
	envelope = normalizeGeofenceEnvelope(ctx, envelope)

	data, err := json.Marshal(envelope)
	if err != nil {
		return fmt.Errorf("geofence: marshal envelope: %w", err)
	}
	headers := []kafka.Header{
		{Key: "event_id", Value: []byte(envelope.ID)},
		{Key: "event_type", Value: []byte(envelope.EventType)},
		{Key: "aggregate_type", Value: []byte(envelope.AggregateType)},
		{Key: "aggregate_id", Value: []byte(envelope.AggregateID)},
		{Key: "schema_version", Value: []byte(envelope.SchemaVersion)},
		{Key: "source_service", Value: []byte(envelope.SourceService)},
	}
	if envelope.CorrelationID != "" {
		headers = append(headers, kafka.Header{Key: "correlation_id", Value: []byte(envelope.CorrelationID)})
	}
	msg := KafkaMessage{
		Key:     []byte(envelope.AggregateID),
		Value:   data,
		Headers: headers,
	}

	publishCtx := ctx
	if _, hasDeadline := ctx.Deadline(); !hasDeadline {
		var cancel context.CancelFunc
		publishCtx, cancel = context.WithTimeout(ctx, 5*time.Second)
		defer cancel()
	}

	if err := p.producer.PublishBatch(publishCtx, p.topic, []KafkaMessage{msg}); err != nil {
		if p.logger != nil {
			p.logger.ErrorContext(ctx, "geofence publish failed",
				"topic", p.topic, "rider_id", envelope.AggregateID, "error", err)
		}
		return err
	}
	return nil
}

func normalizeGeofenceEnvelope(ctx context.Context, env GeofenceEventEnvelope) GeofenceEventEnvelope {
	if env.AggregateID == "" {
		env.AggregateID = env.Payload.RiderID
	}
	if env.Payload.RiderID == "" {
		env.Payload.RiderID = env.AggregateID
	}
	if env.EventType == "" {
		env.EventType = "RiderGeofenceTransition"
	}
	if env.AggregateType == "" {
		env.AggregateType = "RiderGeofence"
	}
	if env.SchemaVersion == "" {
		env.SchemaVersion = "v1"
	}
	if env.SourceService == "" {
		env.SourceService = "location-ingestion-service"
	}
	if env.Payload.Timestamp.IsZero() {
		if parsed, err := time.Parse(time.RFC3339Nano, env.EventTime); err == nil {
			env.Payload.Timestamp = parsed.UTC()
		} else {
			env.Payload.Timestamp = time.Now().UTC()
		}
	} else {
		env.Payload.Timestamp = env.Payload.Timestamp.UTC()
	}
	if env.EventTime == "" {
		env.EventTime = env.Payload.Timestamp.Format(time.RFC3339Nano)
	}
	if env.ID == "" {
		if env.EventID != "" {
			env.ID = env.EventID
		} else {
			env.ID = fmt.Sprintf("geo_%s_%d", env.AggregateID, env.Payload.Timestamp.UnixNano())
		}
	}
	if env.EventID == "" {
		env.EventID = env.ID
	}
	if env.CorrelationID == "" && ctx != nil {
		env.CorrelationID = correlation.FromContext(ctx)
	}
	return env
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
