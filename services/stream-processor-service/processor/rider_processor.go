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
	EventType  string    `json:"eventType"` // RiderOnline, RiderOffline, RiderAssigned, RiderDelivered, RiderIdle
	RiderID    string    `json:"riderId"`
	ZoneID     string    `json:"zoneId"`
	OrderID    string    `json:"orderId,omitempty"`
	EarningsCents int64  `json:"earningsCents,omitempty"`
	Timestamp  time.Time `json:"timestamp"`
}

type LocationUpdate struct {
	RiderID   string    `json:"riderId"`
	Latitude  float64   `json:"latitude"`
	Longitude float64   `json:"longitude"`
	ZoneID    string    `json:"zoneId"`
	Speed     float64   `json:"speed"`
	Timestamp time.Time `json:"timestamp"`
}

type riderState struct {
	Status    string
	ZoneID    string
	UpdatedAt time.Time
}

type riderMetrics struct {
	ridersByStatus   *prometheus.GaugeVec
	deliveriesTotal  *prometheus.CounterVec
	earningsTotal    *prometheus.CounterVec
	locationUpdates  prometheus.Counter
	processingErrors prometheus.Counter
}

func newRiderMetrics() *riderMetrics {
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
}

type RiderProcessor struct {
	redis   *redis.Client
	logger  *slog.Logger
	metrics *riderMetrics
	states  map[string]*riderState // riderId -> state
	mu      sync.RWMutex
}

func NewRiderProcessor(rdb *redis.Client, logger *slog.Logger) *RiderProcessor {
	return &RiderProcessor{
		redis:   rdb,
		logger:  logger.With("processor", "rider"),
		metrics: newRiderMetrics(),
		states:  make(map[string]*riderState),
	}
}

func (p *RiderProcessor) ProcessRiderEvent(ctx context.Context, event RiderEvent) error {
	p.logger.Info("processing rider event",
		"eventType", event.EventType,
		"riderId", event.RiderID,
		"zoneId", event.ZoneID,
	)

	p.mu.Lock()

	// Decrement old status gauge if rider had a previous state
	if prev, ok := p.states[event.RiderID]; ok {
		p.metrics.ridersByStatus.WithLabelValues(prev.Status, prev.ZoneID).Dec()
	}

	// Map event type to rider status
	var status string
	switch event.EventType {
	case "RiderOnline", "RiderIdle":
		status = "idle"
	case "RiderAssigned":
		status = "active"
	case "RiderOffline":
		status = "offline"
	case "RiderDelivered":
		status = "idle"
	default:
		status = "unknown"
	}

	p.states[event.RiderID] = &riderState{
		Status:    status,
		ZoneID:    event.ZoneID,
		UpdatedAt: event.Timestamp,
	}

	// Increment new status gauge
	p.metrics.ridersByStatus.WithLabelValues(status, event.ZoneID).Inc()

	p.mu.Unlock()

	pipe := p.redis.Pipeline()

	// Store rider status in Redis
	riderKey := fmt.Sprintf("rider:status:%s", event.RiderID)
	pipe.HSet(ctx, riderKey, map[string]interface{}{
		"status":  status,
		"zone_id": event.ZoneID,
		"updated": event.Timestamp.Unix(),
	})
	pipe.Expire(ctx, riderKey, 24*time.Hour)

	// Zone-level rider counts
	zoneKey := fmt.Sprintf("riders:zone:%s:%s", event.ZoneID, status)
	pipe.Incr(ctx, zoneKey)
	pipe.Expire(ctx, zoneKey, 2*time.Hour)

	if event.EventType == "RiderDelivered" {
		p.metrics.deliveriesTotal.WithLabelValues(event.RiderID, event.ZoneID).Inc()

		if event.EarningsCents > 0 {
			p.metrics.earningsTotal.WithLabelValues(event.RiderID, event.ZoneID).Add(float64(event.EarningsCents))

			earningsKey := fmt.Sprintf("rider:earnings:%s:%s", event.RiderID, time.Now().Format("2006-01-02"))
			pipe.IncrBy(ctx, earningsKey, event.EarningsCents)
			pipe.Expire(ctx, earningsKey, 48*time.Hour)
		}
	}

	if _, err := pipe.Exec(ctx); err != nil {
		p.metrics.processingErrors.Inc()
		return fmt.Errorf("redis pipeline exec: %w", err)
	}

	return nil
}

func (p *RiderProcessor) ProcessLocationUpdate(ctx context.Context, update LocationUpdate) error {
	p.metrics.locationUpdates.Inc()

	pipe := p.redis.Pipeline()

	// Store latest location in Redis (for real-time map)
	locKey := fmt.Sprintf("rider:location:%s", update.RiderID)
	pipe.HSet(ctx, locKey, map[string]interface{}{
		"lat":       update.Latitude,
		"lng":       update.Longitude,
		"zone_id":   update.ZoneID,
		"speed":     update.Speed,
		"timestamp": update.Timestamp.Unix(),
	})
	pipe.Expire(ctx, locKey, 10*time.Minute)

	// Add to zone's active riders geo set
	pipe.GeoAdd(ctx, fmt.Sprintf("riders:geo:%s", update.ZoneID), &redis.GeoLocation{
		Name:      update.RiderID,
		Longitude: update.Longitude,
		Latitude:  update.Latitude,
	})

	if _, err := pipe.Exec(ctx); err != nil {
		p.metrics.processingErrors.Inc()
		return fmt.Errorf("redis pipeline exec: %w", err)
	}

	return nil
}
