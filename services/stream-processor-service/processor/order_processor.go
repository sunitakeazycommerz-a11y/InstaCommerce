package processor

// Processes order.events for real-time metrics.
// Computes: orders per minute (per store, zone, total), GMV running total,
// SLA compliance (% delivered in 10min), cart abandonment rate

import (
	"context"
	"fmt"
	"log/slog"
	"time"

	"github.com/prometheus/client_golang/prometheus"
	"github.com/prometheus/client_golang/prometheus/promauto"
	"github.com/redis/go-redis/v9"
)

type OrderEvent struct {
	EventType   string     `json:"eventType"` // OrderPlaced, OrderConfirmed, OrderDelivered, OrderCancelled
	OrderID     string     `json:"orderId"`
	UserID      string     `json:"userId"`
	StoreID     string     `json:"storeId"`
	TotalCents  int64      `json:"totalCents"`
	ItemCount   int        `json:"itemCount"`
	PlacedAt    time.Time  `json:"placedAt"`
	DeliveredAt *time.Time `json:"deliveredAt,omitempty"`
	ZoneID      string     `json:"zoneId"`
}

type OrderMetrics struct {
	OrdersTotal       *prometheus.CounterVec
	GMVTotalCents     prometheus.Counter
	DeliveryDuration  *prometheus.HistogramVec
	SLACompliance     *prometheus.GaugeVec
	CancellationTotal *prometheus.CounterVec
	ProcessingErrors  prometheus.Counter
}

func NewOrderMetrics() *OrderMetrics {
	return &OrderMetrics{
		OrdersTotal: promauto.NewCounterVec(prometheus.CounterOpts{
			Name: "orders_total",
			Help: "Total orders processed by event type",
		}, []string{"event_type", "store_id", "zone_id"}),

		GMVTotalCents: promauto.NewCounter(prometheus.CounterOpts{
			Name: "gmv_total_cents",
			Help: "Gross merchandise value running total in cents",
		}),

		DeliveryDuration: promauto.NewHistogramVec(prometheus.HistogramOpts{
			Name:    "delivery_duration_minutes",
			Help:    "Delivery duration in minutes",
			Buckets: []float64{5, 7, 8, 9, 10, 12, 15, 20, 30, 45, 60},
		}, []string{"zone_id", "store_id"}),

		SLACompliance: promauto.NewGaugeVec(prometheus.GaugeOpts{
			Name: "sla_compliance_ratio",
			Help: "SLA compliance ratio (delivered within 10 min) per zone",
		}, []string{"zone_id"}),

		CancellationTotal: promauto.NewCounterVec(prometheus.CounterOpts{
			Name: "order_cancellations_total",
			Help: "Total order cancellations",
		}, []string{"store_id", "zone_id"}),

		ProcessingErrors: promauto.NewCounter(prometheus.CounterOpts{
			Name: "order_processing_errors_total",
			Help: "Total order processing errors",
		}),
	}
}

type OrderProcessor struct {
	redis      *redis.Client
	metrics    *OrderMetrics
	slaMonitor *SLAMonitor
	logger     *slog.Logger
}

func NewOrderProcessor(rdb *redis.Client, metrics *OrderMetrics, slaMonitor *SLAMonitor, logger *slog.Logger) *OrderProcessor {
	return &OrderProcessor{
		redis:      rdb,
		metrics:    metrics,
		slaMonitor: slaMonitor,
		logger:     logger.With("processor", "order"),
	}
}

func (p *OrderProcessor) Process(ctx context.Context, event OrderEvent) error {
	p.logger.Info("processing order event",
		"eventType", event.EventType,
		"orderId", event.OrderID,
		"storeId", event.StoreID,
		"zoneId", event.ZoneID,
	)

	// Increment order counter (per store, per zone, total)
	p.metrics.OrdersTotal.WithLabelValues(event.EventType, event.StoreID, event.ZoneID).Inc()

	now := time.Now()
	minuteKey := now.Format("2006-01-02T15:04")

	pipe := p.redis.Pipeline()

	// Per-minute order count
	orderMinuteKey := fmt.Sprintf("orders:count:%s", minuteKey)
	pipe.Incr(ctx, orderMinuteKey)
	pipe.Expire(ctx, orderMinuteKey, 2*time.Hour)

	// Per-store per-minute
	storeMinuteKey := fmt.Sprintf("orders:store:%s:%s", event.StoreID, minuteKey)
	pipe.Incr(ctx, storeMinuteKey)
	pipe.Expire(ctx, storeMinuteKey, 2*time.Hour)

	// Per-zone per-minute
	zoneMinuteKey := fmt.Sprintf("orders:zone:%s:%s", event.ZoneID, minuteKey)
	pipe.Incr(ctx, zoneMinuteKey)
	pipe.Expire(ctx, zoneMinuteKey, 2*time.Hour)

	switch event.EventType {
	case "OrderPlaced":
		// Update GMV running total
		p.metrics.GMVTotalCents.Add(float64(event.TotalCents))

		gmvKey := fmt.Sprintf("gmv:total:%s", now.Format("2006-01-02"))
		pipe.IncrBy(ctx, gmvKey, event.TotalCents)
		pipe.Expire(ctx, gmvKey, 48*time.Hour)

	case "OrderDelivered":
		if event.DeliveredAt != nil {
			deliveryMinutes := event.DeliveredAt.Sub(event.PlacedAt).Minutes()
			p.metrics.DeliveryDuration.WithLabelValues(event.ZoneID, event.StoreID).Observe(deliveryMinutes)

			// Update SLA monitor
			p.slaMonitor.RecordDelivery(event.ZoneID, deliveryMinutes)

			// Store delivery time in Redis for dashboards
			deliveryKey := fmt.Sprintf("delivery:time:%s:%s", event.ZoneID, minuteKey)
			pipe.LPush(ctx, deliveryKey, deliveryMinutes)
			pipe.Expire(ctx, deliveryKey, 2*time.Hour)
		}

	case "OrderCancelled":
		p.metrics.CancellationTotal.WithLabelValues(event.StoreID, event.ZoneID).Inc()

		cancelKey := fmt.Sprintf("orders:cancelled:%s:%s", event.ZoneID, minuteKey)
		pipe.Incr(ctx, cancelKey)
		pipe.Expire(ctx, cancelKey, 2*time.Hour)
	}

	if _, err := pipe.Exec(ctx); err != nil {
		p.metrics.ProcessingErrors.Inc()
		return fmt.Errorf("redis pipeline exec: %w", err)
	}

	return nil
}
