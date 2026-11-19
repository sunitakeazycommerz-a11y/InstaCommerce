package handler

import (
	"context"
	"errors"
	"fmt"
	"log/slog"
	"sync"
	"sync/atomic"
	"time"

	"github.com/gorilla/websocket"
	"github.com/instacommerce/go-shared/pkg/correlation"
	"go.opentelemetry.io/otel/trace"
)

// LocationHandler processes GPS pings over HTTP, batch HTTP, and WebSocket.
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

// NewIngestHandler creates a LocationHandler allowing optional nil dependencies.
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
		if opt != nil {
			opt(h)
		}
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

// ProcessInput validates a LocationInput and drives persistence, geofencing, and Kafka batching.
func (h *LocationHandler) ProcessInput(ctx context.Context, input LocationInput, source string) error {
	start := time.Now()
	update, err := NormalizeLocation(input)
	if err != nil {
		h.recordInvalid(source, "validation")
		return err
	}
	return h.ingestNormalized(ctx, update, source, start)
}

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

	if h.telemetrySmoother != nil {
		smoothed, err := h.telemetrySmoother.Filter(update)
		if err != nil {
			switch {
			case errors.Is(err, ErrOutOfOrderPing):
				h.recordInvalid(source, "out_of_order")
			case errors.Is(err, ErrTeleportAnomaly):
				h.recordInvalid(source, "teleport_anomaly")
			case errors.Is(err, ErrVelocityOutlier):
				h.recordInvalid(source, "velocity_outlier")
			default:
				h.recordInvalid(source, "telemetry_filter")
			}
			return fmt.Errorf("handler: %w", err)
		}
		update = smoothed
	}

	h.recordFlags(update.TelemetryFlags)

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

	if h.redisStore != nil {
		if err := h.redisStore.Update(processCtx, update); err != nil {
			if isOutOfOrderErr(err) {
				h.recordDropped(source, "out_of_order")
				return fmt.Errorf("%w: %w", ErrOutOfOrderTimestamp, err)
			}
			h.recordDropped(source, "redis")
			trace.SpanFromContext(processCtx).RecordError(err)
			h.logger.ErrorContext(processCtx, "redis store failed", "rider_id", update.RiderID, "error", err)
			return fmt.Errorf("handler: failed to store position: %w", err)
		}
	}

	if h.geofencer != nil {
		events := h.geofencer.Check(update)
		if len(events) > 0 {
			update.Geofence = true
			h.emitGeofenceEvents(processCtx, update, events)
		}
	}

	if h.kafkaBatcher != nil {
		if err := h.kafkaBatcher.Enqueue(update); err != nil {
			h.recordDropped(source, "enqueue")
			trace.SpanFromContext(processCtx).RecordError(err)
			h.logger.ErrorContext(processCtx, "kafka enqueue failed", "rider_id", update.RiderID, "error", err)
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
