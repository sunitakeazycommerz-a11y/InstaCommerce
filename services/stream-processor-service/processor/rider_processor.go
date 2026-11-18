package processor

// Processes rider.events and rider.location.updates
// Computes: rider utilization per zone, active/idle/offline counts,
// average delivery time per rider, earnings tracker

import (
	"context"
	"fmt"
	"log/slog"
	"sync"
	"time"

	"github.com/prometheus/client_golang/prometheus"
	"github.com/prometheus/client_golang/prometheus/promauto"
	"github.com/redis/go-redis/v9"
)

type RiderEvent struct {
	EventType     string    `json:"eventType"` // RiderOnline, RiderOffline, RiderAssigned, RiderDelivered, RiderIdle
	RiderID       string    `json:"riderId"`
	ZoneID        string    `json:"zoneId"`
	OrderID       string    `json:"orderId,omitempty"`
	EarningsCents int64     `json:"earningsCents,omitempty"`
	Timestamp     time.Time `json:"timestamp"`
}

func (e *RiderEvent) UnmarshalJSON(data []byte) error {
	meta, body, err := unwrapEnvelope(data)
	if err != nil {
		return err
	}
	sources := []map[string]any{body, meta}

	e.EventType = normalizeRiderEventType(firstString(sources, "eventType", "event_type", "type"))
	e.RiderID = firstString(sources, "riderId", "rider_id", "aggregateId", "aggregate_id", "id")
	e.ZoneID = firstString(sources, "zoneId", "zone_id")
	e.OrderID = firstString(sources, "orderId", "order_id")

	if earnings, ok := firstInt64(sources, "earningsCents", "earnings_cents", "payoutCents", "payout_cents"); ok {
		e.EarningsCents = earnings
	}
	if ts, ok := firstTime(sources, "timestamp", "timestamp_ms", "timestampMs", "assignedAt", "assigned_at", "eventTime", "event_time"); ok {
		e.Timestamp = ts
	}
	return nil
}

var riderEventAliases = map[string]string{
	"RIDERONLINE":     "RiderOnline",
	"RIDER_ONLINE":    "RiderOnline",
	"RIDERACTIVATED":  "RiderOnline",
	"RIDER_ACTIVATED": "RiderOnline",
	"RIDERIDLE":       "RiderIdle",
	"RIDER_IDLE":      "RiderIdle",
	"RIDERASSIGNED":   "RiderAssigned",
	"RIDER_ASSIGNED":  "RiderAssigned",
	"RIDEROFFLINE":    "RiderOffline",
	"RIDER_OFFLINE":   "RiderOffline",
	"RIDERSUSPENDED":  "RiderOffline",
	"RIDER_SUSPENDED": "RiderOffline",
	"RIDERDELIVERED":  "RiderDelivered",
	"RIDER_DELIVERED": "RiderDelivered",
}

func normalizeRiderEventType(raw string) string {
	return normalizeEventType(raw, riderEventAliases)
}

type LocationUpdate struct {
	RiderID   string    `json:"riderId"`
	Latitude  float64   `json:"latitude"`
	Longitude float64   `json:"longitude"`
	ZoneID    string    `json:"zoneId"`
	Speed     float64   `json:"speed"`
	Timestamp time.Time `json:"timestamp"`
}

func (u *LocationUpdate) UnmarshalJSON(data []byte) error {
	meta, body, err := unwrapEnvelope(data)
	if err != nil {
		return err
	}
	sources := []map[string]any{body, meta}

	u.RiderID = firstString(sources, "riderId", "rider_id", "aggregateId", "aggregate_id")
	u.ZoneID = firstString(sources, "zoneId", "zone_id", "h3Index", "h3_index")
	if lat, ok := firstFloat64(sources, "latitude", "lat"); ok {
		u.Latitude = lat
	}
	if lng, ok := firstFloat64(sources, "longitude", "lng", "lon"); ok {
		u.Longitude = lng
	}
	if speed, ok := firstFloat64(sources, "speed", "speedKmh", "speed_kmh"); ok {
		u.Speed = speed
	}
	if ts, ok := firstTime(sources, "timestamp", "timestamp_ms", "timestampMs", "eventTime", "event_time"); ok {
		u.Timestamp = ts
	}
	return nil
}

type riderState struct {
	Status    string
	ZoneID    string
	zoneLabel string
	UpdatedAt time.Time
}

type riderMetrics struct {
	ridersByStatus   *prometheus.GaugeVec
	deliveriesTotal  *prometheus.CounterVec
	earningsTotal    *prometheus.CounterVec
	locationUpdates  prometheus.Counter
	processingErrors prometheus.Counter
}

var newRiderMetrics = sync.OnceValue(func() *riderMetrics {
	return &riderMetrics{
		ridersByStatus: promauto.NewGaugeVec(prometheus.GaugeOpts{
			Name: "riders_by_status",
			Help: "Number of riders by status and zone",
		}, []string{"status", "zone_id"}),

		deliveriesTotal: promauto.NewCounterVec(prometheus.CounterOpts{
			Name: "rider_deliveries_total",
			Help: "Total deliveries completed per rider",
		}, []string{"rider_id", "zone_id"}),

		earningsTotal: promauto.NewCounterVec(prometheus.CounterOpts{
			Name: "rider_earnings_total_cents",
			Help: "Total rider earnings in cents",
		}, []string{"rider_id", "zone_id"}),

		locationUpdates: promauto.NewCounter(prometheus.CounterOpts{
			Name: "rider_location_updates_total",
			Help: "Total rider location updates processed",
		}),

		processingErrors: promauto.NewCounter(prometheus.CounterOpts{
			Name: "rider_processing_errors_total",
			Help: "Total rider event processing errors",
		}),
	}
})

type RiderProcessor struct {
	redis      *redis.Client
	logger     *slog.Logger
	metrics    *riderMetrics
	states     map[string]*riderState // riderId -> state
	mu         sync.RWMutex
	riderGuard *labelCardinalityGuard
	zoneGuard  *labelCardinalityGuard
}

func NewRiderProcessor(rdb *redis.Client, logger *slog.Logger) *RiderProcessor {
	if logger == nil {
		logger = slog.Default()
	}
	return &RiderProcessor{
		redis:      rdb,
		logger:     logger.With("processor", "rider"),
		metrics:    newRiderMetrics(),
		states:     make(map[string]*riderState),
		riderGuard: newLabelCardinalityGuard(maxBoundedLabelValues),
		zoneGuard:  newLabelCardinalityGuard(maxBoundedLabelValues),
	}
}

func (p *RiderProcessor) ProcessRiderEvent(ctx context.Context, event RiderEvent) error {
	event.EventType = normalizeRiderEventType(event.EventType)
	if event.Timestamp.IsZero() {
		event.Timestamp = time.Now().UTC()
	}
	zoneLabel := p.zoneGuard.normalize(event.ZoneID, "unknown")
	riderLabel := p.riderGuard.normalize(event.RiderID, "unknown")

	p.logger.Info("processing rider event",
		"eventType", event.EventType,
		"riderId", event.RiderID,
		"zoneId", event.ZoneID,
	)

	p.mu.Lock()

	// Decrement old status gauge if rider had a previous state
	if prev, ok := p.states[event.RiderID]; ok {
		prevZoneLabel := prev.zoneLabel
		if prevZoneLabel == "" {
			prevZoneLabel = p.zoneGuard.normalize(prev.ZoneID, "unknown")
		}
		p.metrics.ridersByStatus.WithLabelValues(prev.Status, prevZoneLabel).Dec()
	}

	// Map event type to rider status
	var status string
	switch event.EventType {
	case "RiderOnline", "RiderIdle", "RiderDelivered":
		status = "idle"
	case "RiderAssigned":
		status = "active"
	case "RiderOffline":
		status = "offline"
	default:
		status = "unknown"
	}

	p.states[event.RiderID] = &riderState{
		Status:    status,
		ZoneID:    event.ZoneID,
		zoneLabel: zoneLabel,
		UpdatedAt: event.Timestamp,
	}

	// Increment new status gauge
	p.metrics.ridersByStatus.WithLabelValues(status, zoneLabel).Inc()

	p.mu.Unlock()

	if event.EventType == "RiderDelivered" {
		p.metrics.deliveriesTotal.WithLabelValues(riderLabel, zoneLabel).Inc()
		if event.EarningsCents > 0 {
			p.metrics.earningsTotal.WithLabelValues(riderLabel, zoneLabel).Add(float64(event.EarningsCents))
		}
	}

	if p.redis == nil {
		return nil
	}

	pipe := p.redis.Pipeline()

	// Store rider status in Redis using raw ZoneID.
	riderKey := fmt.Sprintf("rider:status:%s", event.RiderID)
	pipe.HSet(ctx, riderKey, map[string]interface{}{
		"status":  status,
		"zone_id": event.ZoneID,
		"updated": event.Timestamp.Unix(),
	})
	pipe.Expire(ctx, riderKey, redisMetricsTTL)

	// Zone-level rider counts using raw ZoneID.
	zoneKey := fmt.Sprintf("riders:zone:%s:%s", event.ZoneID, status)
	pipe.Incr(ctx, zoneKey)
	pipe.Expire(ctx, zoneKey, redisMetricsTTL)

	if event.EventType == "RiderDelivered" && event.EarningsCents > 0 {
		earningsKey := fmt.Sprintf("rider:earnings:%s:%s", event.RiderID, time.Now().Format("2006-01-02"))
		pipe.IncrBy(ctx, earningsKey, event.EarningsCents)
		pipe.Expire(ctx, earningsKey, 48*time.Hour)
	}

	if _, err := pipe.Exec(ctx); err != nil {
		p.metrics.processingErrors.Inc()
		return fmt.Errorf("redis pipeline exec: %w", err)
	}

	return nil
}

func (p *RiderProcessor) ProcessLocationUpdate(ctx context.Context, update LocationUpdate) error {
	if update.Timestamp.IsZero() {
		update.Timestamp = time.Now().UTC()
	}
	p.metrics.locationUpdates.Inc()

	if p.redis == nil {
		return nil
	}

	pipe := p.redis.Pipeline()

	// Store latest location in Redis (for real-time map) using raw ZoneID.
	locKey := fmt.Sprintf("rider:location:%s", update.RiderID)
	pipe.HSet(ctx, locKey, map[string]interface{}{
		"lat":       update.Latitude,
		"lng":       update.Longitude,
		"zone_id":   update.ZoneID,
		"speed":     update.Speed,
		"timestamp": update.Timestamp.Unix(),
	})
	pipe.Expire(ctx, locKey, 10*time.Minute)

	// Add to zone's active riders geo set with 24h TTL using raw ZoneID.
	geoKey := fmt.Sprintf("riders:geo:%s", update.ZoneID)
	pipe.GeoAdd(ctx, geoKey, &redis.GeoLocation{
		Name:      update.RiderID,
		Longitude: update.Longitude,
		Latitude:  update.Latitude,
	})
	pipe.Expire(ctx, geoKey, redisMetricsTTL)

	if _, err := pipe.Exec(ctx); err != nil {
		p.metrics.processingErrors.Inc()
		return fmt.Errorf("redis pipeline exec: %w", err)
	}

	return nil
}
