// Package handler provides production GPS location ingestion over HTTP and
// WebSocket. Incoming updates are validated, persisted atomically to Redis,
// evaluated for geofence transitions, and batched to Kafka.
package handler

import (
	"bytes"
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"log/slog"
	"net/http"
	"sync"
	"sync/atomic"
	"time"

	"github.com/gorilla/websocket"
	"github.com/instacommerce/go-shared/pkg/correlation"
	"github.com/prometheus/client_golang/prometheus"
	"go.opentelemetry.io/otel/trace"
)

const (
	defaultMaxBodyBytes    = 1 << 20
	defaultWSReadLimit     = 1 << 20
	defaultProcessTimeout  = 3 * time.Second
	maxLocationHandlerBody = 256 << 10
)

var (
	// ErrInvalidPayload indicates a malformed or out-of-bounds GPS update.
	ErrInvalidPayload = errors.New("invalid payload")
	// ErrOutOfOrderTimestamp indicates an incoming GPS update has a timestamp <= the stored timestamp.
	ErrOutOfOrderTimestamp = errors.New("out of order timestamp")
)

// LocationInput is the wire format accepted by /ingest, /ingest/location, /ingest/batch, and /ingest/ws.
type LocationInput struct {
	RiderID   string   `json:"rider_id"`
	Lat       float64  `json:"lat"`
	Lng       float64  `json:"lng"`
	Timestamp *int64   `json:"timestamp,omitempty"`
	Speed     *float64 `json:"speed,omitempty"`
	Heading   *float64 `json:"heading,omitempty"`
	Accuracy  *float64 `json:"accuracy,omitempty"`
}

// NormalizeLocation validates a LocationInput and converts it into a canonical LocationUpdate.
func NormalizeLocation(input LocationInput) (LocationUpdate, error) {
	if input.RiderID == "" {
		return LocationUpdate{}, fmt.Errorf("%w: rider_id is required", ErrInvalidPayload)
	}
	if input.Lat < -90 || input.Lat > 90 {
		return LocationUpdate{}, fmt.Errorf("%w: lat out of range", ErrInvalidPayload)
	}
	if input.Lng < -180 || input.Lng > 180 {
		return LocationUpdate{}, fmt.Errorf("%w: lng out of range", ErrInvalidPayload)
	}
	if input.Speed != nil && *input.Speed < 0 {
		return LocationUpdate{}, fmt.Errorf("%w: speed must be >= 0", ErrInvalidPayload)
	}
	if input.Heading != nil && (*input.Heading < 0 || *input.Heading >= 360) {
		return LocationUpdate{}, fmt.Errorf("%w: heading must be between 0 and 360", ErrInvalidPayload)
	}
	if input.Accuracy != nil && *input.Accuracy < 0 {
		return LocationUpdate{}, fmt.Errorf("%w: accuracy must be >= 0", ErrInvalidPayload)
	}
	if input.Accuracy != nil && *input.Accuracy > 50.0 {
		return LocationUpdate{}, fmt.Errorf("%w: accuracy exceeds 50m limit (poor GPS fix)", ErrInvalidPayload)
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
	return u, nil
}

// LocationUpdate represents a single GPS ping from a delivery rider.
type LocationUpdate struct {
	RiderID     string    `json:"rider_id"`
	Lat         float64   `json:"lat"`
	Lng         float64   `json:"lng"`
	Accuracy    float64   `json:"accuracy_meters"`
	Speed       float64   `json:"speed_kmh"`
	Heading     float64   `json:"heading_degrees"`
	Timestamp   time.Time `json:"timestamp"`
	BatteryPct  float64   `json:"battery_pct"`
	H3Index     string    `json:"h3_index,omitempty"`
	Geofence    bool      `json:"geofence_hit,omitempty"`
	Source      string    `json:"source,omitempty"`
	HasSpeed    bool      `json:"-"`
	HasHeading  bool      `json:"-"`
	HasAccuracy bool      `json:"-"`
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

// LatestPositionStore abstracts persistence of the most recent rider position.
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
func WithSynchronousGeofence(sync bool) LocationHandlerOption {
	return func(h *LocationHandler) {
		h.synchronousGeo = sync
	}
}

// WithTelemetrySmoother sets a custom RiderTelemetrySmoother (nil disables smoothing).
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

// WithTracer sets an OpenTelemetry tracer for ingest spans.
func WithTracer(tracer trace.Tracer) LocationHandlerOption {
	return func(h *LocationHandler) {
		h.tracer = tracer
	}
}

// LocationHandler processes GPS pings over HTTP, batch HTTP, and WebSocket,
// persists the latest position to Redis before running geofence checks, and
// batches accepted pings to Kafka.
type LocationHandler struct {
	cfg               IngestConfig
	kafkaBatcher      *LocationBatcher
	redisStore        LatestPositionStore
	geofencer         *GeofenceChecker
	logger            *slog.Logger
	metrics           *LocationMetrics
	tracer            trace.Tracer
	geofencePublisher GeofencePublisher
	geofenceQueue     chan GeofenceEventEnvelope
	synchronousGeo    bool
	workerCancel      context.CancelFunc
	wg                sync.WaitGroup
	telemetrySmoother *RiderTelemetrySmoother
	rateLimiter       *ConnectionRateLimiter
	wsWG              sync.WaitGroup
	wsMu              sync.Mutex
	wsConns           map[*websocket.Conn]struct{}
	shuttingDown      atomic.Bool
}

// NewLocationHandler creates a ready-to-use LocationHandler requiring non-nil dependencies.
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
	return NewIngestHandler(
		IngestConfig{
			MaxBodyBytes:     maxLocationHandlerBody,
			WebSocketReadMax: defaultWSReadLimit,
			ProcessTimeout:   defaultProcessTimeout,
			H3Resolution:     8,
		},
		batcher,
		store,
		geofencer,
		logger,
		metrics,
		opts...,
	), nil
}

// NewIngestHandler creates a LocationHandler allowing optional nil dependencies
// (for graceful degradation or isolated unit tests).
func NewIngestHandler(
	cfg IngestConfig,
	batcher *LocationBatcher,
	store LatestPositionStore,
	geofencer *GeofenceChecker,
	logger *slog.Logger,
	metrics *LocationMetrics,
	opts ...LocationHandlerOption,
) *LocationHandler {
	if cfg.MaxBodyBytes <= 0 {
		cfg.MaxBodyBytes = defaultMaxBodyBytes
	}
	if cfg.WebSocketReadMax <= 0 {
		cfg.WebSocketReadMax = defaultWSReadLimit
	}
	if cfg.ProcessTimeout <= 0 {
		cfg.ProcessTimeout = defaultProcessTimeout
	}
	if logger == nil {
		logger = slog.Default()
	}
	h := &LocationHandler{
		cfg:               cfg,
		kafkaBatcher:      batcher,
		redisStore:        store,
		geofencer:         geofencer,
		logger:            logger,
		metrics:           metrics,
		geofenceQueue:     make(chan GeofenceEventEnvelope, 1024),
		telemetrySmoother: NewRiderTelemetrySmoother(),
		rateLimiter:       NewConnectionRateLimiter(100.0, 200.0, 20000),
		wsConns:           make(map[*websocket.Conn]struct{}),
	}
	for _, opt := range opts {
		opt(h)
	}

	if h.geofencePublisher != nil && !h.synchronousGeo {
		ctx, cancel := context.WithCancel(context.Background())
		h.workerCancel = cancel
		h.wg.Add(1)
		go func() {
			defer h.wg.Done()
			h.geofenceWorker(ctx)
		}()
	}

	return h
}

// Close gracefully stops background geofence workers and active WebSockets.
func (h *LocationHandler) Close() {
	h.CloseWebSockets()
	if h.workerCancel != nil {
		h.workerCancel()
	}
	h.wg.Wait()
}

// CloseWebSockets closes all active WebSocket connections and waits for their
// handler goroutines to exit.
func (h *LocationHandler) CloseWebSockets() {
	h.shuttingDown.Store(true)
	h.wsMu.Lock()
	conns := make([]*websocket.Conn, 0, len(h.wsConns))
	for conn := range h.wsConns {
		conns = append(conns, conn)
	}
	h.wsMu.Unlock()

	for _, conn := range conns {
		_ = conn.WriteControl(
			websocket.CloseMessage,
			websocket.FormatCloseMessage(websocket.CloseGoingAway, "server shutting down"),
			time.Now().Add(time.Second),
		)
		_ = conn.Close()
	}
	h.wsWG.Wait()
}

// ActiveWebSockets returns the count of currently open WebSocket connections.
func (h *LocationHandler) ActiveWebSockets() int {
	h.wsMu.Lock()
	defer h.wsMu.Unlock()
	return len(h.wsConns)
}

func (h *LocationHandler) registerWSConn(conn *websocket.Conn) bool {
	h.wsMu.Lock()
	defer h.wsMu.Unlock()
	if h.shuttingDown.Load() {
		return false
	}
	if h.wsConns == nil {
		h.wsConns = make(map[*websocket.Conn]struct{})
	}
	h.wsConns[conn] = struct{}{}
	return true
}

func (h *LocationHandler) unregisterWSConn(conn *websocket.Conn) {
	h.wsMu.Lock()
	delete(h.wsConns, conn)
	h.wsMu.Unlock()
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

	h.recordReceived("http")

	maxBytes := h.cfg.MaxBodyBytes
	if maxBytes <= 0 {
		maxBytes = maxLocationHandlerBody
	}
	r.Body = http.MaxBytesReader(w, r.Body, maxBytes)
	defer r.Body.Close()

	body, err := io.ReadAll(r.Body)
	if err != nil {
		h.recordInvalid("http", "body_read")
		var maxBytesErr *http.MaxBytesError
		if errors.As(err, &maxBytesErr) {
			h.writeError(w, http.StatusRequestEntityTooLarge, "request body too large")
			return
		}
		h.writeError(w, http.StatusBadRequest, "failed to read request body")
		return
	}

	var update LocationUpdate
	if err := json.Unmarshal(body, &update); err != nil {
		h.recordInvalid("http", "decode")
		h.writeError(w, http.StatusBadRequest, "invalid JSON payload")
		return
	}

	if err := h.processUpdate(r.Context(), update, "http"); err != nil {
		if isOutOfOrderErr(err) {
			h.writeJSON(w, http.StatusOK, map[string]string{
				"status": "ignored",
				"reason": "out_of_order_timestamp",
			})
			return
		}
		h.writeError(w, http.StatusUnprocessableEntity, err.Error())
		return
	}

	h.writeJSON(w, http.StatusAccepted, map[string]string{"status": "accepted"})
}

// HandleIngest accepts a single strict JSON LocationInput via POST (/ingest, /ingest/location).
func (h *LocationHandler) HandleIngest(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodPost {
		w.Header().Set("Allow", "POST")
		h.writeError(w, http.StatusMethodNotAllowed, "method not allowed")
		return
	}

	h.recordReceived("http")

	maxBytes := h.cfg.MaxBodyBytes
	if maxBytes <= 0 {
		maxBytes = defaultMaxBodyBytes
	}
	r.Body = http.MaxBytesReader(w, r.Body, maxBytes)
	decoder := json.NewDecoder(r.Body)
	decoder.DisallowUnknownFields()

	var input LocationInput
	if err := decoder.Decode(&input); err != nil {
		h.recordDropped("http", "decode")
		var maxBytesErr *http.MaxBytesError
		if errors.As(err, &maxBytesErr) {
			h.writeError(w, http.StatusRequestEntityTooLarge, "request body too large")
			return
		}
		h.writeError(w, http.StatusBadRequest, "invalid JSON payload")
		return
	}
	if err := decoder.Decode(&struct{}{}); err != io.EOF {
		h.recordDropped("http", "decode")
		h.writeError(w, http.StatusBadRequest, "payload must contain a single JSON object")
		return
	}

	if err := h.ProcessInput(r.Context(), input, "http"); err != nil {
		if isOutOfOrderErr(err) {
			h.writeJSON(w, http.StatusOK, map[string]string{
				"status": "ignored",
				"reason": "out_of_order_timestamp",
			})
			return
		}
		status := http.StatusServiceUnavailable
		if errors.Is(err, ErrInvalidPayload) {
			status = http.StatusBadRequest
		}
		h.writeError(w, status, err.Error())
		return
	}

	h.writeJSON(w, http.StatusAccepted, map[string]string{"status": "accepted"})
}

// HandleBatchIngest accepts a batch of LocationInput updates (/ingest/batch, /batch).
func (h *LocationHandler) HandleBatchIngest(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodPost {
		w.Header().Set("Allow", "POST")
		h.writeError(w, http.StatusMethodNotAllowed, "method not allowed")
		return
	}

	maxBytes := h.cfg.MaxBodyBytes
	if maxBytes <= 0 {
		maxBytes = defaultMaxBodyBytes
	}
	r.Body = http.MaxBytesReader(w, r.Body, maxBytes)
	body, err := io.ReadAll(r.Body)
	if err != nil {
		h.recordDropped("http", "decode")
		var maxBytesErr *http.MaxBytesError
		if errors.As(err, &maxBytesErr) {
			h.writeError(w, http.StatusRequestEntityTooLarge, "request body too large")
			return
		}
		h.writeError(w, http.StatusBadRequest, "failed to read request body")
		return
	}

	var inputs []LocationInput
	trimmed := bytes.TrimSpace(body)
	if len(trimmed) > 0 && trimmed[0] == '[' {
		if err := json.Unmarshal(trimmed, &inputs); err != nil {
			h.recordDropped("http", "decode")
			h.writeError(w, http.StatusBadRequest, "invalid JSON array payload")
			return
		}
	} else {
		var wrapper struct {
			Updates []LocationInput `json:"updates"`
		}
		if err := json.Unmarshal(trimmed, &wrapper); err != nil {
			h.recordDropped("http", "decode")
			h.writeError(w, http.StatusBadRequest, "invalid JSON batch payload")
			return
		}
		inputs = wrapper.Updates
	}

	if len(inputs) == 0 {
		h.writeError(w, http.StatusBadRequest, "batch must contain at least one update")
		return
	}

	accepted, ignored, failed := 0, 0, 0
	for _, input := range inputs {
		h.recordReceived("http")
		if err := h.ProcessInput(r.Context(), input, "http"); err != nil {
			if isOutOfOrderErr(err) {
				ignored++
				continue
			}
			failed++
			continue
		}
		accepted++
	}

	h.writeJSON(w, http.StatusAccepted, map[string]any{
		"status":   "accepted",
		"accepted": accepted,
		"ignored":  ignored,
		"failed":   failed,
	})
}

// HandleWebSocket upgrades the connection and streams LocationInput GPS messages.
func (h *LocationHandler) HandleWebSocket(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodGet {
		w.Header().Set("Allow", "GET")
		h.writeError(w, http.StatusMethodNotAllowed, "method not allowed")
		return
	}
	if h.shuttingDown.Load() {
		h.writeError(w, http.StatusServiceUnavailable, "service shutting down")
		return
	}

	if h.rateLimiter != nil && !h.rateLimiter.Allow() {
		h.recordDropped("websocket", "rate_limit")
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
	if !h.registerWSConn(conn) {
		_ = conn.Close()
		return
	}

	h.wsWG.Add(1)
	defer h.wsWG.Done()
	defer h.unregisterWSConn(conn)
	defer conn.Close()

	h.handleWebSocketConn(r.Context(), conn)
}

func (h *LocationHandler) handleWebSocketConn(ctx context.Context, conn *websocket.Conn) {
	readMax := h.cfg.WebSocketReadMax
	if readMax <= 0 {
		readMax = defaultWSReadLimit
	}
	conn.SetReadLimit(readMax)
	_ = conn.SetReadDeadline(time.Now().Add(60 * time.Second))
	conn.SetPongHandler(func(string) error {
		_ = conn.SetReadDeadline(time.Now().Add(60 * time.Second))
		return nil
	})

	pingDone := make(chan struct{})
	var pingWG sync.WaitGroup
	pingWG.Add(1)
	go func() {
		defer pingWG.Done()
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
	defer func() {
		close(pingDone)
		pingWG.Wait()
	}()

	for {
		select {
		case <-ctx.Done():
			return
		default:
		}
		if h.shuttingDown.Load() {
			return
		}

		messageType, payload, err := conn.ReadMessage()
		if err != nil {
			if websocket.IsCloseError(err, websocket.CloseNormalClosure, websocket.CloseGoingAway) {
				return
			}
			if !h.shuttingDown.Load() {
				h.logger.Warn("websocket read error", "error", err)
			}
			return
		}
		if messageType != websocket.TextMessage && messageType != websocket.BinaryMessage {
			continue
		}

		h.recordReceived("websocket")

		var input LocationInput
		decoder := json.NewDecoder(bytes.NewReader(payload))
		decoder.DisallowUnknownFields()
		if err := decoder.Decode(&input); err != nil {
			h.recordInvalid("websocket", "decode")
			continue
		}
		if err := decoder.Decode(&struct{}{}); err != io.EOF {
			h.recordInvalid("websocket", "decode")
			continue
		}

		if err := h.ProcessInput(ctx, input, "websocket"); err != nil {
			if !errors.Is(err, ErrInvalidPayload) && !isOutOfOrderErr(err) {
				h.logger.Warn("websocket update rejected",
					"rider_id", input.RiderID, "error", err)
			}
		}
	}
}

// ProcessInput validates a LocationInput and drives it through persistence, geofence checks, and Kafka batching.
func (h *LocationHandler) ProcessInput(ctx context.Context, input LocationInput, source string) error {
	start := time.Now()
	update, err := NormalizeLocation(input)
	if err != nil {
		h.recordInvalid(source, "validation")
		return err
	}
	return h.ingestNormalized(ctx, update, source, start)
}

// processUpdate validates a LocationUpdate and drives it through smoothing, persistence, geofence checks, and Kafka batching.
func (h *LocationHandler) processUpdate(ctx context.Context, update LocationUpdate, source string) error {
	start := time.Now()
	if err := update.Validate(); err != nil {
		h.recordInvalid(source, "validation")
		return fmt.Errorf("handler: %w", err)
	}
	return h.ingestNormalized(ctx, update, source, start)
}

func (h *LocationHandler) ingestNormalized(ctx context.Context, update LocationUpdate, source string, start time.Time) error {
	update.Source = source

	// 1. Optional in-memory velocity anomaly detection and Kalman smoothing (Issues #597, #613)
	if h.telemetrySmoother != nil {
		smoothed, err := h.telemetrySmoother.Filter(update)
		if err != nil {
			if errors.Is(err, ErrOutOfOrderPing) {
				h.recordInvalid(source, "out_of_order")
			} else if errors.Is(err, ErrVelocityOutlier) {
				h.recordInvalid(source, "velocity_outlier")
			} else {
				h.recordInvalid(source, "telemetry_filter")
			}
			return fmt.Errorf("handler: %w", err)
		}
		update = smoothed
	}

	// 2. Compute H3 spatial index when configured or unset.
	if update.H3Index == "" && h.cfg.H3Resolution > 0 {
		idx := LatLngToH3Res8(update.Lat, update.Lng)
		if h.cfg.H3Resolution != 8 {
			update.H3Index = fmt.Sprintf("res%d:%s", h.cfg.H3Resolution, idx)
		} else {
			update.H3Index = idx
		}
	}

	timeout := h.cfg.ProcessTimeout
	if timeout <= 0 {
		timeout = defaultProcessTimeout
	}
	processCtx, cancel := context.WithTimeout(ctx, timeout)
	defer cancel()

	if h.tracer != nil {
		var span trace.Span
		processCtx, span = h.tracer.Start(processCtx, "ingest.process")
		defer span.End()
	}

	// 3. Persist latest position to Redis FIRST (atomic CAS out-of-order check).
	// Stale / out-of-order pings MUST be rejected before geofence evaluation so
	// they do not emit spurious GeofenceEvents or consume the 3-minute Redis dedup lock.
	if h.redisStore != nil {
		if err := h.redisStore.Update(processCtx, update); err != nil {
			if isOutOfOrderErr(err) {
				h.recordDropped(source, "out_of_order")
				return fmt.Errorf("%w: %w", ErrOutOfOrderTimestamp, err)
			}
			h.recordDropped(source, "redis")
			trace.SpanFromContext(processCtx).RecordError(err)
			h.logger.ErrorContext(processCtx, "redis store failed",
				"rider_id", update.RiderID, "error", err)
			return fmt.Errorf("handler: failed to store position: %w", err)
		}
	}

	// 4. Evaluate geofences ONLY after out-of-order timestamp acceptance.
	if h.geofencer != nil {
		events := h.geofencer.Check(update)
		if len(events) > 0 {
			update.Geofence = true
			h.emitGeofenceEvents(processCtx, update, events)
		}
	}

	// 5. Enqueue accepted update for Kafka batching.
	if h.kafkaBatcher != nil {
		if err := h.kafkaBatcher.Enqueue(update); err != nil {
			h.recordDropped(source, "enqueue")
			trace.SpanFromContext(processCtx).RecordError(err)
			h.logger.ErrorContext(processCtx, "kafka enqueue failed",
				"rider_id", update.RiderID, "error", err)
			return fmt.Errorf("kafka enqueue failed: %w", err)
		}
	}

	h.recordLatency(source, time.Since(start))
	return nil
}

func (h *LocationHandler) emitGeofenceEvents(ctx context.Context, update LocationUpdate, events []GeofenceEvent) {
	corrID := correlation.FromContext(ctx)
	for _, ev := range events {
		h.logger.InfoContext(ctx, "geofence event",
			"rider_id", ev.RiderID,
			"event_type", ev.EventType,
			"zone_id", ev.ZoneID,
			"h3_index", ev.H3Index)

		if h.geofencePublisher == nil {
			continue
		}
		env := ev.ToEnvelope(update.Lat, update.Lng)
		env.CorrelationID = corrID
		if h.metrics != nil && h.metrics.GeofenceEmitted != nil {
			h.metrics.GeofenceEmitted.WithLabelValues(ev.EventType, ev.ZoneID).Inc()
		}
		if h.synchronousGeo {
			if err := h.geofencePublisher.PublishGeofenceEvent(ctx, env); err != nil {
				h.logger.WarnContext(ctx, "failed to publish geofence event",
					"rider_id", ev.RiderID, "event_type", ev.EventType, "error", err)
			}
			continue
		}
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

func isOutOfOrderErr(err error) bool {
	return errors.Is(err, ErrOutOfOrderTimestamp) || errors.Is(err, ErrOutOfOrderPing)
}

func (h *LocationHandler) recordReceived(source string) {
	if h != nil && h.metrics != nil && h.metrics.UpdatesReceived != nil {
		h.metrics.UpdatesReceived.WithLabelValues(source).Inc()
	}
}

func (h *LocationHandler) recordInvalid(source, reason string) {
	if h != nil && h.metrics != nil && h.metrics.UpdatesInvalid != nil {
		h.metrics.UpdatesInvalid.WithLabelValues(source, reason).Inc()
	}
}

func (h *LocationHandler) recordDropped(source, reason string) {
	if h != nil && h.metrics != nil && h.metrics.UpdatesDropped != nil {
		h.metrics.UpdatesDropped.WithLabelValues(source, reason).Inc()
	}
}

func (h *LocationHandler) recordLatency(source string, d time.Duration) {
	if h != nil && h.metrics != nil && h.metrics.ProcessLatency != nil {
		h.metrics.ProcessLatency.WithLabelValues(source).Observe(d.Seconds())
	}
}

func (h *LocationHandler) writeJSON(w http.ResponseWriter, status int, payload any) {
	w.Header().Set("Content-Type", "application/json")
	w.WriteHeader(status)
	enc := json.NewEncoder(w)
	enc.SetEscapeHTML(false)
	_ = enc.Encode(payload)
}

func (h *LocationHandler) writeError(w http.ResponseWriter, status int, message string) {
	h.writeJSON(w, status, map[string]string{"error": message})
}
