// Package handler provides production GPS location ingestion over HTTP and
// WebSocket. Incoming updates are validated, enriched with geofence data,
// batched, and published to Kafka while the latest position is persisted to
// Redis.
package handler

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"log/slog"
	"net/http"
	"time"

	"github.com/gorilla/websocket"
	"github.com/prometheus/client_golang/prometheus"
)

// maxLocationBody is the upper bound on HTTP request body size (256 KiB).
const maxLocationBody = 256 << 10

// LocationUpdate represents a single GPS ping from a delivery rider.
type LocationUpdate struct {
	RiderID    string    `json:"rider_id"`
	Lat        float64   `json:"lat"`
	Lng        float64   `json:"lng"`
	Accuracy   float64   `json:"accuracy_meters"`
	Speed      float64   `json:"speed_kmh"`
	Heading    float64   `json:"heading_degrees"`
	Timestamp  time.Time `json:"timestamp"`
	BatteryPct float64   `json:"battery_pct"`
	H3Index    string    `json:"h3_index,omitempty"`
}

// Validate checks that all required fields are present and within physically
// plausible ranges.
func (u *LocationUpdate) Validate() error {
	if u.RiderID == "" {
		return fmt.Errorf("location: rider_id is required")
	}
	if u.Lat < -90 || u.Lat > 90 {
		return fmt.Errorf("location: lat %f out of range [-90, 90]", u.Lat)
	}
	if u.Lng < -180 || u.Lng > 180 {
		return fmt.Errorf("location: lng %f out of range [-180, 180]", u.Lng)
	}
	if u.Accuracy < 0 {
		return fmt.Errorf("location: accuracy must be >= 0")
	}
	if u.Accuracy > 50.0 {
		return fmt.Errorf("location: accuracy %.1fm exceeds 50m threshold (poor GPS fix)", u.Accuracy)
	}
	if u.Speed < 0 {
		return fmt.Errorf("location: speed must be >= 0")
	}
	if u.Heading < 0 || u.Heading >= 360 {
		return fmt.Errorf("location: heading must be in [0, 360)")
	}
	if u.BatteryPct < 0 || u.BatteryPct > 100 {
		return fmt.Errorf("location: battery_pct must be in [0, 100]")
	}
	if u.Timestamp.IsZero() {
		u.Timestamp = time.Now().UTC()
	}
	return nil
}

// LocationMetrics holds Prometheus metrics for the location handler.
type LocationMetrics struct {
	UpdatesReceived *prometheus.CounterVec
	UpdatesInvalid  *prometheus.CounterVec
	UpdatesDropped  *prometheus.CounterVec
	ProcessLatency  *prometheus.HistogramVec
	GeofenceEmitted *prometheus.CounterVec
	GeofenceDropped prometheus.Counter
}

// NewLocationMetrics creates and registers location ingestion metrics.
func NewLocationMetrics(reg prometheus.Registerer) *LocationMetrics {
	m := &LocationMetrics{
		UpdatesReceived: prometheus.NewCounterVec(prometheus.CounterOpts{
			Namespace: "location_handler",
			Name:      "updates_received_total",
			Help:      "Total location updates received, by source (http, websocket).",
		}, []string{"source"}),
		UpdatesInvalid: prometheus.NewCounterVec(prometheus.CounterOpts{
			Namespace: "location_handler",
			Name:      "updates_invalid_total",
			Help:      "Total invalid location updates, by source and reason.",
		}, []string{"source", "reason"}),
		UpdatesDropped: prometheus.NewCounterVec(prometheus.CounterOpts{
			Namespace: "location_handler",
			Name:      "updates_dropped_total",
			Help:      "Total location updates dropped, by source and reason.",
		}, []string{"source", "reason"}),
		ProcessLatency: prometheus.NewHistogramVec(prometheus.HistogramOpts{
			Namespace: "location_handler",
			Name:      "process_latency_seconds",
			Help:      "End-to-end processing latency in seconds, by source.",
			Buckets:   []float64{0.001, 0.005, 0.01, 0.025, 0.05, 0.1, 0.25},
		}, []string{"source"}),
		GeofenceEmitted: prometheus.NewCounterVec(prometheus.CounterOpts{
			Namespace: "location_handler",
			Name:      "geofence_events_emitted_total",
			Help:      "Total geofence transition events emitted, by event_type and zone_id.",
		}, []string{"event_type", "zone_id"}),
		GeofenceDropped: prometheus.NewCounter(prometheus.CounterOpts{
			Namespace: "location_handler",
			Name:      "geofence_events_dropped_total",
			Help:      "Total geofence transition events dropped due to buffer overflow.",
		}),
	}
	reg.MustRegister(
		m.UpdatesReceived,
		m.UpdatesInvalid,
		m.UpdatesDropped,
		m.ProcessLatency,
		m.GeofenceEmitted,
		m.GeofenceDropped,
	)
	return m
}

// LatestPositionStore abstracts the persistence of the most recent rider
// position (typically Redis). See store.LatestPositionStore for the concrete
// implementation.
type LatestPositionStore interface {
	Update(ctx context.Context, update LocationUpdate) error
}

// GeofencePublisher abstracts publishing geofence transition events to a message bus.
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
	return p.producer.PublishBatch(ctx, p.topic, []KafkaMessage{msg})
}

// LocationHandlerOption configures optional behavior on LocationHandler.
type LocationHandlerOption func(*LocationHandler)

// WithGeofencePublisher sets a custom GeofencePublisher.
func WithGeofencePublisher(publisher GeofencePublisher) LocationHandlerOption {
	return func(h *LocationHandler) {
		h.geofencePublisher = publisher
	}
}

// WithGeofenceProducer sets a KafkaProducer and topic for geofence event publishing.
func WithGeofenceProducer(producer KafkaProducer, topic string) LocationHandlerOption {
	return func(h *LocationHandler) {
		if topic == "" {
			topic = DefaultGeofenceTopic
		}
		h.geofencePublisher = NewKafkaGeofencePublisher(producer, topic, h.logger)
	}
}

// WithSynchronousGeofence forces geofence event publishing to run synchronously on the handler thread.
// Primarily used for unit testing.
func WithSynchronousGeofence(sync bool) LocationHandlerOption {
	return func(h *LocationHandler) {
		h.synchronousGeo = sync
	}
}

// WithTelemetrySmoother sets a custom RiderTelemetrySmoother for velocity outlier and Kalman filtering.
func WithTelemetrySmoother(smoother *RiderTelemetrySmoother) LocationHandlerOption {
	return func(h *LocationHandler) {
		h.telemetrySmoother = smoother
	}
}

// WithConnectionRateLimiter sets a custom ConnectionRateLimiter for WebSocket connection throttling.
func WithConnectionRateLimiter(limiter *ConnectionRateLimiter) LocationHandlerOption {
	return func(h *LocationHandler) {
		h.rateLimiter = limiter
	}
}

// LocationHandler processes GPS pings over HTTP and WebSocket, enriches them
// with geofence events, batches them for Kafka, and stores the latest position
// in Redis.
type LocationHandler struct {
	kafkaBatcher      *LocationBatcher
	redisStore        LatestPositionStore
	geofencer         *GeofenceChecker
	logger            *slog.Logger
	metrics           *LocationMetrics
	geofencePublisher GeofencePublisher
	geofenceQueue     chan GeofenceEventEnvelope
	synchronousGeo    bool
	workerCancel      context.CancelFunc
	telemetrySmoother *RiderTelemetrySmoother
	rateLimiter       *ConnectionRateLimiter
}

// NewLocationHandler creates a ready-to-use LocationHandler.
func NewLocationHandler(
	batcher *LocationBatcher,
	store LatestPositionStore,
	geofencer *GeofenceChecker,
	logger *slog.Logger,
	metrics *LocationMetrics,
	opts ...LocationHandlerOption,
) (*LocationHandler, error) {
	if batcher == nil {
		return nil, fmt.Errorf("handler: LocationBatcher must not be nil")
	}
	if store == nil {
		return nil, fmt.Errorf("handler: LatestPositionStore must not be nil")
	}
	if logger == nil {
		return nil, fmt.Errorf("handler: logger must not be nil")
	}
	if metrics == nil {
		return nil, fmt.Errorf("handler: LocationMetrics must not be nil")
	}
	h := &LocationHandler{
		kafkaBatcher:      batcher,
		redisStore:        store,
		geofencer:         geofencer,
		logger:            logger,
		metrics:           metrics,
		geofenceQueue:     make(chan GeofenceEventEnvelope, 1024),
		telemetrySmoother: NewRiderTelemetrySmoother(),
		rateLimiter:       NewConnectionRateLimiter(100.0, 200.0, 20000),
	}
	for _, opt := range opts {
		opt(h)
	}

	if h.geofencePublisher != nil && !h.synchronousGeo {
		ctx, cancel := context.WithCancel(context.Background())
		h.workerCancel = cancel
		go h.geofenceWorker(ctx)
	}

	return h, nil
}

// Close gracefully stops any background workers.
func (h *LocationHandler) Close() {
	if h.workerCancel != nil {
		h.workerCancel()
	}
}

func (h *LocationHandler) geofenceWorker(ctx context.Context) {
	for {
		select {
		case <-ctx.Done():
			return
		case env, ok := <-h.geofenceQueue:
			if !ok {
				return
			}
			if err := h.geofencePublisher.PublishGeofenceEvent(ctx, env); err != nil {
				h.logger.Error("failed to publish geofence event",
					"rider_id", env.Payload.RiderID,
					"event_type", env.Payload.EventType,
					"error", err)
			}
		}
	}
}

// HandleHTTP accepts a single JSON LocationUpdate via POST.
func (h *LocationHandler) HandleHTTP(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodPost {
		w.Header().Set("Allow", "POST")
		h.writeError(w, http.StatusMethodNotAllowed, "method not allowed")
		return
	}

	h.metrics.UpdatesReceived.WithLabelValues("http").Inc()

	r.Body = http.MaxBytesReader(w, r.Body, maxLocationBody)
	defer r.Body.Close()

	body, err := io.ReadAll(r.Body)
	if err != nil {
		h.metrics.UpdatesInvalid.WithLabelValues("http", "body_read").Inc()
		h.writeError(w, http.StatusRequestEntityTooLarge, "request body too large")
		return
	}

	var update LocationUpdate
	if err := json.Unmarshal(body, &update); err != nil {
		h.metrics.UpdatesInvalid.WithLabelValues("http", "decode").Inc()
		h.writeError(w, http.StatusBadRequest, "invalid JSON payload")
		return
	}

	if err := h.processUpdate(r.Context(), update, "http"); err != nil {
		h.writeError(w, http.StatusUnprocessableEntity, err.Error())
		return
	}

	w.Header().Set("Content-Type", "application/json")
	w.WriteHeader(http.StatusAccepted)
	_ = json.NewEncoder(w).Encode(map[string]string{"status": "accepted"})
}

// HandleWebSocket upgrades the connection and reads a continuous stream of
// LocationUpdate JSON messages. Throttles excessive concurrent connections (Issue #618).
func (h *LocationHandler) HandleWebSocket(w http.ResponseWriter, r *http.Request) {
	if h.rateLimiter != nil && !h.rateLimiter.Allow() {
		h.metrics.UpdatesDropped.WithLabelValues("websocket", "rate_limit").Inc()
		w.Header().Set("Retry-After", "5")
		h.writeError(w, http.StatusTooManyRequests, "connection rate limit exceeded; apply exponential backoff")
		return
	}
	if h.rateLimiter != nil {
		defer h.rateLimiter.ReleaseConn()
	}

	upgrader := websocket.Upgrader{
		ReadBufferSize:  1024,
		WriteBufferSize: 1024,
		CheckOrigin:     func(_ *http.Request) bool { return true },
	}

	conn, err := upgrader.Upgrade(w, r, nil)
	if err != nil {
		h.logger.ErrorContext(r.Context(), "websocket upgrade failed", "error", err)
		return
	}
	defer conn.Close()

	h.handleWebSocketConn(r.Context(), conn)
}

// handleWebSocketConn reads messages from conn until the connection is closed
// or ctx is cancelled, keeping the connection alive with ping/pong frames.
func (h *LocationHandler) handleWebSocketConn(ctx context.Context, conn *websocket.Conn) {
	_ = conn.SetReadDeadline(time.Now().Add(60 * time.Second))
	conn.SetPongHandler(func(string) error {
		_ = conn.SetReadDeadline(time.Now().Add(60 * time.Second))
		return nil
	})

	pingDone := make(chan struct{})
	defer close(pingDone)

	go func() {
		ticker := time.NewTicker(25 * time.Second)
		defer ticker.Stop()
		for {
			select {
			case <-pingDone:
				return
			case <-ticker.C:
				_ = conn.SetWriteDeadline(time.Now().Add(10 * time.Second))
				if err := conn.WriteMessage(websocket.PingMessage, nil); err != nil {
					return
				}
			}
		}
	}()

	for {
		select {
		case <-ctx.Done():
			return
		default:
		}

		_, msg, err := conn.ReadMessage()
		if err != nil {
			if websocket.IsCloseError(err, websocket.CloseNormalClosure, websocket.CloseGoingAway) {
				return
			}
			h.logger.Warn("websocket read error", "error", err)
			return
		}

		h.metrics.UpdatesReceived.WithLabelValues("websocket").Inc()

		var update LocationUpdate
		if err := json.Unmarshal(msg, &update); err != nil {
			h.metrics.UpdatesInvalid.WithLabelValues("websocket", "decode").Inc()
			continue
		}

		if err := h.processUpdate(ctx, update, "websocket"); err != nil {
			h.logger.Warn("websocket update rejected",
				"rider_id", update.RiderID, "error", err)
		}
	}
}

// processUpdate validates an update, smooths telemetry / rejects velocity anomalies,
// indexes to H3, runs geofence checks, persists to Redis, and enqueues to Kafka.
func (h *LocationHandler) processUpdate(ctx context.Context, update LocationUpdate, source string) error {
	start := time.Now()

	if err := update.Validate(); err != nil {
		h.metrics.UpdatesInvalid.WithLabelValues(source, "validation").Inc()
		return fmt.Errorf("handler: %w", err)
	}

	// 1. Out-of-order check, velocity outlier anomaly detection, and Kalman smoothing (Issues #597, #613)
	if h.telemetrySmoother != nil {
		smoothed, err := h.telemetrySmoother.Filter(update)
		if err != nil {
			if errors.Is(err, ErrOutOfOrderPing) {
				h.metrics.UpdatesInvalid.WithLabelValues(source, "out_of_order").Inc()
			} else if errors.Is(err, ErrVelocityOutlier) {
				h.metrics.UpdatesInvalid.WithLabelValues(source, "velocity_outlier").Inc()
			} else {
				h.metrics.UpdatesInvalid.WithLabelValues(source, "telemetry_filter").Inc()
			}
			return fmt.Errorf("handler: %w", err)
		}
		update = smoothed
	}

	// 2. Compute H3 Resolution 8 spatial index (Issue #607)
	if update.H3Index == "" {
		update.H3Index = LatLngToH3Res8(update.Lat, update.Lng)
	}

	// Geofence detection (non-blocking, best-effort).
	if h.geofencer != nil {
		events := h.geofencer.Check(update)
		for _, ev := range events {
			h.logger.InfoContext(ctx, "geofence event",
				"rider_id", ev.RiderID,
				"event_type", ev.EventType,
				"zone_id", ev.ZoneID,
				"h3_index", ev.H3Index)

			if h.geofencePublisher != nil {
				env := ev.ToEnvelope(update.Lat, update.Lng)
				if h.metrics != nil && h.metrics.GeofenceEmitted != nil {
					h.metrics.GeofenceEmitted.WithLabelValues(ev.EventType, ev.ZoneID).Inc()
				}
				if h.synchronousGeo {
					if err := h.geofencePublisher.PublishGeofenceEvent(ctx, env); err != nil {
						h.logger.ErrorContext(ctx, "failed to publish geofence event synchronously",
							"rider_id", ev.RiderID, "error", err)
					}
				} else {
					select {
					case h.geofenceQueue <- env:
					default:
						if h.metrics != nil && h.metrics.GeofenceDropped != nil {
							h.metrics.GeofenceDropped.Inc()
						}
						h.logger.WarnContext(ctx, "geofence queue full, dropped event",
							"rider_id", ev.RiderID, "event_type", ev.EventType)
					}
				}
			}
		}
	}

	// Store latest position in Redis.
	if err := h.redisStore.Update(ctx, update); err != nil {
		h.metrics.UpdatesDropped.WithLabelValues(source, "redis").Inc()
		h.logger.ErrorContext(ctx, "redis store failed",
			"rider_id", update.RiderID, "error", err)
		return fmt.Errorf("handler: failed to store position: %w", err)
	}

	// Enqueue for Kafka batching.
	h.kafkaBatcher.Add(update)

	h.metrics.ProcessLatency.WithLabelValues(source).Observe(time.Since(start).Seconds())
	return nil
}

// writeError writes a JSON error response.
func (h *LocationHandler) writeError(w http.ResponseWriter, status int, message string) {
	w.Header().Set("Content-Type", "application/json")
	w.WriteHeader(status)
	_ = json.NewEncoder(w).Encode(map[string]string{"error": message})
}
