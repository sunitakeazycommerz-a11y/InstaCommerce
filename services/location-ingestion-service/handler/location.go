// Package handler provides production GPS location ingestion over HTTP and
// WebSocket. Incoming updates are validated, enriched with geofence data,
// batched, and published to Kafka while the latest position is persisted to
// Redis.
package handler

import (
	"context"
	"encoding/json"
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
	}
	reg.MustRegister(
		m.UpdatesReceived,
		m.UpdatesInvalid,
		m.UpdatesDropped,
		m.ProcessLatency,
	)
	return m
}

// LatestPositionStore abstracts the persistence of the most recent rider
// position (typically Redis). See store.LatestPositionStore for the concrete
// implementation.
type LatestPositionStore interface {
	Update(ctx context.Context, update LocationUpdate) error
}

// LocationHandler processes GPS pings over HTTP and WebSocket, enriches them
// with geofence events, batches them for Kafka, and stores the latest position
// in Redis.
type LocationHandler struct {
	kafkaBatcher *LocationBatcher
	redisStore   LatestPositionStore
	geofencer    *GeofenceChecker
	logger       *slog.Logger
	metrics      *LocationMetrics
}

// NewLocationHandler creates a ready-to-use LocationHandler.
func NewLocationHandler(
	batcher *LocationBatcher,
	store LatestPositionStore,
	geofencer *GeofenceChecker,
	logger *slog.Logger,
	metrics *LocationMetrics,
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
	return &LocationHandler{
		kafkaBatcher: batcher,
		redisStore:   store,
		geofencer:    geofencer,
		logger:       logger,
		metrics:      metrics,
	}, nil
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
// LocationUpdate JSON messages.
func (h *LocationHandler) HandleWebSocket(w http.ResponseWriter, r *http.Request) {
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
// or ctx is cancelled.
func (h *LocationHandler) handleWebSocketConn(ctx context.Context, conn *websocket.Conn) {
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

// processUpdate validates an update, runs geofence checks, persists to Redis,
// and enqueues to Kafka.
func (h *LocationHandler) processUpdate(ctx context.Context, update LocationUpdate, source string) error {
	start := time.Now()

	if err := update.Validate(); err != nil {
		h.metrics.UpdatesInvalid.WithLabelValues(source, "validation").Inc()
		return fmt.Errorf("handler: %w", err)
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
