package processor

// Processes order.events for real-time metrics.
// Computes: orders per minute (per store, zone, total), GMV running total,
// SLA compliance (% delivered in 10min), cart abandonment rate

import (
	"context"
	"fmt"
	"log/slog"
	"strings"
	"sync"
	"time"

	"github.com/prometheus/client_golang/prometheus"
	"github.com/prometheus/client_golang/prometheus/promauto"
	"github.com/redis/go-redis/v9"
)

const redisMetricsTTL = 24 * time.Hour

type OrderEvent struct {
	EventType   string     `json:"eventType"` // OrderCreated, OrderPlaced, OrderConfirmed, OrderPacked, OrderDispatched, OrderDelivered, OrderCancelled
	OrderID     string     `json:"orderId"`
	UserID      string     `json:"userId"`
	StoreID     string     `json:"storeId"`
	TotalCents  int64      `json:"totalCents"`
	ItemCount   int        `json:"itemCount"`
	PlacedAt    time.Time  `json:"placedAt"`
	DeliveredAt *time.Time `json:"deliveredAt,omitempty"`
	ZoneID      string     `json:"zoneId"`
}

func (e *OrderEvent) UnmarshalJSON(data []byte) error {
	meta, body, err := unwrapEnvelope(data)
	if err != nil {
		return err
	}
	sources := []map[string]any{body, meta}

	e.EventType = normalizeOrderEventType(firstString(sources, "eventType", "event_type", "type"))
	e.OrderID = firstString(sources, "orderId", "order_id", "aggregateId", "aggregate_id", "id")
	e.UserID = firstString(sources, "userId", "user_id", "customerId", "customer_id")
	e.StoreID = firstString(sources, "storeId", "store_id", "darkStoreId", "dark_store_id")
	e.ZoneID = firstString(sources, "zoneId", "zone_id")

	if total, ok := firstInt64(sources, "totalCents", "total_cents", "amountCents", "amount_cents", "grandTotalCents", "grand_total_cents"); ok {
		e.TotalCents = total
	}
	if count, ok := firstInt64(sources, "itemCount", "item_count"); ok {
		e.ItemCount = int(count)
	} else if items, ok := body["items"].([]any); ok {
		e.ItemCount = len(items)
	}

	if placedAt, ok := firstTime(sources, "placedAt", "placed_at", "createdAt", "created_at"); ok {
		e.PlacedAt = placedAt
	}
	if deliveredAt, ok := firstTime(sources, "deliveredAt", "delivered_at"); ok {
		e.DeliveredAt = &deliveredAt
	} else if e.EventType == "OrderDelivered" {
		if ts, ok := firstTime(sources, "eventTime", "event_time", "timestamp"); ok {
			e.DeliveredAt = &ts
		}
	}
	return nil
}

var orderEventAliases = map[string]string{
	"ORDERCREATED":     "OrderCreated",
	"ORDER_CREATED":    "OrderCreated",
	"ORDERPLACED":      "OrderPlaced",
	"ORDER_PLACED":     "OrderPlaced",
	"ORDERCONFIRMED":   "OrderConfirmed",
	"ORDER_CONFIRMED":  "OrderConfirmed",
	"ORDERPACKED":      "OrderPacked",
	"ORDER_PACKED":     "OrderPacked",
	"ORDERDISPATCHED":  "OrderDispatched",
	"ORDER_DISPATCHED": "OrderDispatched",
	"ORDERDELIVERED":   "OrderDelivered",
	"ORDER_DELIVERED":  "OrderDelivered",
	"ORDERCANCELLED":   "OrderCancelled",
	"ORDER_CANCELLED":  "OrderCancelled",
	"ORDERCANCELED":    "OrderCancelled",
	"ORDER_CANCELED":   "OrderCancelled",
}

func normalizeOrderEventType(raw string) string {
	return normalizeEventType(raw, orderEventAliases)
}

type OrderMetrics struct {
	OrdersTotal       *prometheus.CounterVec
	GMVTotalCents     prometheus.Counter
	DeliveryDuration  *prometheus.HistogramVec
	SLACompliance     *prometheus.GaugeVec
	CancellationTotal *prometheus.CounterVec
	ProcessingErrors  prometheus.Counter
}

var orderMetricsLoader = sync.OnceValue(func() *OrderMetrics {
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
})

func NewOrderMetrics() *OrderMetrics {
	return orderMetricsLoader()
}

type OrderProcessor struct {
	redis      *redis.Client
	metrics    *OrderMetrics
	slaMonitor *SLAMonitor
	logger     *slog.Logger
	storeGuard *labelCardinalityGuard
	zoneGuard  *labelCardinalityGuard
	eventGuard *labelCardinalityGuard
	watermarks *WatermarkTracker
}

func NewOrderProcessor(rdb *redis.Client, metrics *OrderMetrics, slaMonitor *SLAMonitor, logger *slog.Logger) *OrderProcessor {
	if logger == nil {
		logger = slog.Default()
	}
	if metrics == nil {
		metrics = NewOrderMetrics()
	}
	return &OrderProcessor{
		redis:      rdb,
		metrics:    metrics,
		slaMonitor: slaMonitor,
		logger:     logger.With("processor", "order"),
		storeGuard: newLabelCardinalityGuard(maxBoundedLabelValues),
		zoneGuard:  newLabelCardinalityGuard(maxBoundedLabelValues),
		eventGuard: newLabelCardinalityGuard(64),
		watermarks: NewWatermarkTracker(DefaultAllowedLateness),
	}
}

// WatermarkTracker exposes the event-time watermark tracker for order events.
func (p *OrderProcessor) WatermarkTracker() *WatermarkTracker {
	return p.watermarks
}

func (p *OrderProcessor) Process(ctx context.Context, event OrderEvent) error {
	event.EventType = normalizeOrderEventType(event.EventType)
	storeLabel := p.storeGuard.normalize(event.StoreID, "unknown")
	zoneLabel := p.zoneGuard.normalize(event.ZoneID, "unknown")
	eventLabel := p.eventGuard.normalize(event.EventType, "unknown")

	p.logger.Info("processing order event",
		"eventType", event.EventType,
		"orderId", event.OrderID,
		"storeId", event.StoreID,
		"zoneId", event.ZoneID,
	)

	p.metrics.OrdersTotal.WithLabelValues(eventLabel, storeLabel, zoneLabel).Inc()

	now := time.Now().UTC()
	if event.DeliveredAt != nil && !event.DeliveredAt.IsZero() {
		now = event.DeliveredAt.UTC()
	} else if !event.PlacedAt.IsZero() {
		now = event.PlacedAt.UTC()
	}
	p.watermarks.Observe("order.events", now)
	minuteKey := now.Format("2006-01-02T15:04")

	var pipe redis.Pipeliner
	if p.redis != nil {
		pipe = p.redis.Pipeline()

		// Per-minute order count
		orderMinuteKey := fmt.Sprintf("orders:count:%s", minuteKey)
		pipe.Incr(ctx, orderMinuteKey)
		pipe.Expire(ctx, orderMinuteKey, redisMetricsTTL)

		// Per-store per-minute (raw StoreID)
		storeMinuteKey := fmt.Sprintf("orders:store:%s:%s", event.StoreID, minuteKey)
		pipe.Incr(ctx, storeMinuteKey)
		pipe.Expire(ctx, storeMinuteKey, redisMetricsTTL)

		// Per-zone per-minute (raw ZoneID)
		zoneMinuteKey := fmt.Sprintf("orders:zone:%s:%s", event.ZoneID, minuteKey)
		pipe.Incr(ctx, zoneMinuteKey)
		pipe.Expire(ctx, zoneMinuteKey, redisMetricsTTL)
	}

	switch event.EventType {
	case "OrderPlaced", "OrderCreated":
		if event.TotalCents > 0 {
			p.metrics.GMVTotalCents.Add(float64(event.TotalCents))
		}
		if pipe != nil {
			gmvKey := fmt.Sprintf("gmv:total:%s", now.Format("2006-01-02"))
			pipe.IncrBy(ctx, gmvKey, event.TotalCents)
			pipe.Expire(ctx, gmvKey, 48*time.Hour)
		}

	case "OrderPacked", "OrderDispatched", "OrderConfirmed":
		if pipe != nil {
			stageKey := fmt.Sprintf("orders:stage:%s:%s", strings.ToLower(event.EventType), minuteKey)
			pipe.Incr(ctx, stageKey)
			pipe.Expire(ctx, stageKey, redisMetricsTTL)
		}

	case "OrderDelivered":
		if event.DeliveredAt != nil && !event.PlacedAt.IsZero() {
			deliveryMinutes := event.DeliveredAt.Sub(event.PlacedAt).Minutes()
			if deliveryMinutes >= 0 {
				p.metrics.DeliveryDuration.WithLabelValues(zoneLabel, storeLabel).Observe(deliveryMinutes)
				if p.slaMonitor != nil {
					p.slaMonitor.RecordDelivery(event.ZoneID, deliveryMinutes)
				}
				if pipe != nil {
					deliveryKey := fmt.Sprintf("delivery:time:%s:%s", event.ZoneID, minuteKey)
					pipe.LPush(ctx, deliveryKey, deliveryMinutes)
					pipe.Expire(ctx, deliveryKey, redisMetricsTTL)
				}
			}
		}

	case "OrderCancelled":
		p.metrics.CancellationTotal.WithLabelValues(storeLabel, zoneLabel).Inc()
		if pipe != nil {
			cancelKey := fmt.Sprintf("orders:cancelled:%s:%s", event.ZoneID, minuteKey)
			pipe.Incr(ctx, cancelKey)
			pipe.Expire(ctx, cancelKey, redisMetricsTTL)
		}
	}

	if pipe == nil {
		return nil
	}

	if _, err := pipe.Exec(ctx); err != nil {
		p.metrics.ProcessingErrors.Inc()
		return fmt.Errorf("redis pipeline exec: %w", err)
	}

	return nil
}
