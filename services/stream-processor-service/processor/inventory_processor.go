package processor

// Processes inventory.events
// Computes: inventory velocity (per SKU, per store, 1hr window),
// stockout cascade detection (>10 SKUs at zero in single store)

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

type InventoryEvent struct {
	EventType string    `json:"eventType"` // StockReserved, StockConfirmed, StockReleased, LowStockAlert, StockUpdated, StockDepleted, StockReplenished
	SKUID     string    `json:"skuId"`
	StoreID   string    `json:"storeId"`
	Quantity  int       `json:"quantity"`
	PrevQty   int       `json:"prevQty"`
	Threshold int       `json:"threshold,omitempty"`
	Timestamp time.Time `json:"timestamp"`
}

func (e *InventoryEvent) UnmarshalJSON(data []byte) error {
	meta, body, err := unwrapEnvelope(data)
	if err != nil {
		return err
	}
	sources := []map[string]any{body, meta}

	e.EventType = normalizeInventoryEventType(firstString(sources, "eventType", "event_type", "type"))
	e.SKUID = firstString(sources, "skuId", "sku_id", "productId", "product_id", "sku")
	e.StoreID = firstString(sources, "storeId", "store_id", "warehouseId", "warehouse_id")

	if qty, ok := firstInt64(sources, "quantity", "currentQuantity", "current_quantity", "newOnHand", "new_on_hand", "available"); ok {
		e.Quantity = int(qty)
	}
	if prev, ok := firstInt64(sources, "prevQty", "prev_qty", "previousQuantity", "previous_quantity"); ok {
		e.PrevQty = int(prev)
	}
	if thresh, ok := firstInt64(sources, "threshold"); ok {
		e.Threshold = int(thresh)
	}
	if ts, ok := firstTime(sources, "timestamp", "eventTime", "event_time", "createdAt", "created_at"); ok {
		e.Timestamp = ts
	}

	// Support batch inventory events (StockReserved / StockConfirmed / StockReleased) with items[]
	if e.SKUID == "" {
		if items, ok := body["items"].([]any); ok && len(items) > 0 {
			if firstItem, ok := items[0].(map[string]any); ok {
				itemSources := []map[string]any{firstItem}
				e.SKUID = firstString(itemSources, "skuId", "sku_id", "productId", "product_id", "sku")
				if e.Quantity == 0 {
					if q, ok := firstInt64(itemSources, "quantity", "currentQuantity", "current_quantity"); ok {
						e.Quantity = int(q)
					}
				}
			}
		}
	}
	return nil
}

var inventoryEventAliases = map[string]string{
	"STOCKRESERVED":     "StockReserved",
	"STOCK_RESERVED":    "StockReserved",
	"STOCKCONFIRMED":    "StockConfirmed",
	"STOCK_CONFIRMED":   "StockConfirmed",
	"STOCKRELEASED":     "StockReleased",
	"STOCK_RELEASED":    "StockReleased",
	"LOWSTOCKALERT":     "LowStockAlert",
	"LOW_STOCK_ALERT":   "LowStockAlert",
	"STOCKUPDATED":      "StockUpdated",
	"STOCK_UPDATED":     "StockUpdated",
	"STOCKDEPLETED":     "StockDepleted",
	"STOCK_DEPLETED":    "StockDepleted",
	"STOCKREPLENISHED":  "StockReplenished",
	"STOCK_REPLENISHED": "StockReplenished",
}

func normalizeInventoryEventType(raw string) string {
	return normalizeEventType(raw, inventoryEventAliases)
}

type velocityRecord struct {
	timestamp time.Time
	delta     int // positive = replenished, negative = sold
}

type inventoryMetrics struct {
	stockUpdatesTotal *prometheus.CounterVec
	stockoutsTotal    *prometheus.CounterVec
	cascadeAlerts     *prometheus.CounterVec
	velocityGauge     *prometheus.GaugeVec
	processingErrors  prometheus.Counter
}

var newInventoryMetrics = sync.OnceValue(func() *inventoryMetrics {
	return &inventoryMetrics{
		stockUpdatesTotal: promauto.NewCounterVec(prometheus.CounterOpts{
			Name: "inventory_stock_updates_total",
			Help: "Total stock update events by type",
		}, []string{"event_type", "store_id"}),

		stockoutsTotal: promauto.NewCounterVec(prometheus.CounterOpts{
			Name: "inventory_stockouts_total",
			Help: "Total stockout events per store",
		}, []string{"store_id"}),

		cascadeAlerts: promauto.NewCounterVec(prometheus.CounterOpts{
			Name: "inventory_cascade_alerts_total",
			Help: "Total stockout cascade alerts (>10 SKUs at zero in one store)",
		}, []string{"store_id"}),

		velocityGauge: promauto.NewGaugeVec(prometheus.GaugeOpts{
			Name: "inventory_velocity_per_hour",
			Help: "Inventory velocity (units change) per SKU per store over 1hr window",
		}, []string{"sku_id", "store_id"}),

		processingErrors: promauto.NewCounter(prometheus.CounterOpts{
			Name: "inventory_processing_errors_total",
			Help: "Total inventory processing errors",
		}),
	}
})

type InventoryProcessor struct {
	redis   *redis.Client
	logger  *slog.Logger
	metrics *inventoryMetrics

	// velocity tracking: store:sku -> records
	velocity map[string][]velocityRecord
	// stockout tracking: store -> set of SKUs at zero
	stockouts  map[string]map[string]struct{}
	mu         sync.RWMutex
	storeGuard *labelCardinalityGuard
	skuGuard   *labelCardinalityGuard
	eventGuard *labelCardinalityGuard
}

func NewInventoryProcessor(rdb *redis.Client, logger *slog.Logger) *InventoryProcessor {
	if logger == nil {
		logger = slog.Default()
	}
	return &InventoryProcessor{
		redis:      rdb,
		logger:     logger.With("processor", "inventory"),
		metrics:    newInventoryMetrics(),
		velocity:   make(map[string][]velocityRecord),
		stockouts:  make(map[string]map[string]struct{}),
		storeGuard: newLabelCardinalityGuard(maxBoundedLabelValues),
		skuGuard:   newLabelCardinalityGuard(maxBoundedLabelValues),
		eventGuard: newLabelCardinalityGuard(64),
	}
}

func (p *InventoryProcessor) Process(ctx context.Context, event InventoryEvent) error {
	event.EventType = normalizeInventoryEventType(event.EventType)
	storeLabel := p.storeGuard.normalize(event.StoreID, "unknown")
	eventLabel := p.eventGuard.normalize(event.EventType, "unknown")

	p.logger.Info("processing inventory event",
		"eventType", event.EventType,
		"skuId", event.SKUID,
		"storeId", event.StoreID,
		"quantity", event.Quantity,
	)

	p.metrics.stockUpdatesTotal.WithLabelValues(eventLabel, storeLabel).Inc()

	now := time.Now()

	// Update velocity window using raw domain IDs (guard is strictly for Prometheus labels).
	delta := event.Quantity - event.PrevQty
	if (event.EventType == "StockReserved" || event.EventType == "StockConfirmed") && event.PrevQty == 0 && event.Quantity > 0 {
		delta = -event.Quantity
	}
	p.updateVelocity(event.StoreID, event.SKUID, delta, now)

	var pipe redis.Pipeliner
	if p.redis != nil {
		pipe = p.redis.Pipeline()
	}

	switch event.EventType {
	case "StockDepleted":
		p.metrics.stockoutsTotal.WithLabelValues(storeLabel).Inc()
		p.trackStockout(event.StoreID, event.SKUID, true)
		if pipe != nil {
			stockoutKey := fmt.Sprintf("stockout:%s:%s", event.StoreID, event.SKUID)
			pipe.Set(ctx, stockoutKey, now.Unix(), redisMetricsTTL)
		}

	case "LowStockAlert":
		if pipe != nil {
			alertKey := fmt.Sprintf("low_stock:%s:%s", event.StoreID, event.SKUID)
			pipe.Set(ctx, alertKey, event.Quantity, redisMetricsTTL)
		}
		if event.Quantity <= 0 {
			p.metrics.stockoutsTotal.WithLabelValues(storeLabel).Inc()
			p.trackStockout(event.StoreID, event.SKUID, true)
			if pipe != nil {
				stockoutKey := fmt.Sprintf("stockout:%s:%s", event.StoreID, event.SKUID)
				pipe.Set(ctx, stockoutKey, now.Unix(), redisMetricsTTL)
			}
		}

	case "StockReplenished", "StockReleased":
		if event.Quantity > 0 {
			p.trackStockout(event.StoreID, event.SKUID, false)
		}
		if pipe != nil {
			stockoutKey := fmt.Sprintf("stockout:%s:%s", event.StoreID, event.SKUID)
			pipe.Del(ctx, stockoutKey)
		}
	}

	if pipe == nil {
		return nil
	}

	// Store current quantity in Redis with 24h TTL under raw store/SKU key.
	qtyKey := fmt.Sprintf("inventory:%s:%s", event.StoreID, event.SKUID)
	pipe.Set(ctx, qtyKey, event.Quantity, redisMetricsTTL)

	if _, err := pipe.Exec(ctx); err != nil {
		p.metrics.processingErrors.Inc()
		return fmt.Errorf("redis pipeline exec: %w", err)
	}

	return nil
}

func (p *InventoryProcessor) updateVelocity(storeID, skuID string, delta int, now time.Time) {
	p.mu.Lock()
	defer p.mu.Unlock()

	key := storeID + ":" + skuID
	cutoff := now.Add(-1 * time.Hour)

	if len(p.velocity) >= maxBoundedLabelValues {
		if _, exists := p.velocity[key]; !exists {
			for k, recs := range p.velocity {
				if len(recs) == 0 || recs[len(recs)-1].timestamp.Before(cutoff) {
					delete(p.velocity, k)
				}
			}
		}
	}

	records := p.velocity[key]

	// Evict old records
	idx := 0
	for idx < len(records) && records[idx].timestamp.Before(cutoff) {
		idx++
	}
	records = records[idx:]

	// Append new record
	records = append(records, velocityRecord{timestamp: now, delta: delta})
	p.velocity[key] = records

	// Compute total velocity over the window
	totalDelta := 0
	for _, r := range records {
		totalDelta += r.delta
	}

	skuLabel := skuID
	storeLabel := storeID
	if p.skuGuard != nil {
		skuLabel = p.skuGuard.normalize(skuID, "unknown")
	}
	if p.storeGuard != nil {
		storeLabel = p.storeGuard.normalize(storeID, "unknown")
	}
	p.metrics.velocityGauge.WithLabelValues(skuLabel, storeLabel).Set(float64(totalDelta))
}

func (p *InventoryProcessor) trackStockout(storeID, skuID string, depleted bool) {
	p.mu.Lock()
	defer p.mu.Unlock()

	if _, ok := p.stockouts[storeID]; !ok {
		p.stockouts[storeID] = make(map[string]struct{})
	}

	if depleted {
		p.stockouts[storeID][skuID] = struct{}{}
	} else {
		delete(p.stockouts[storeID], skuID)
	}

	// Cascade detection: >10 SKUs at zero in a single store
	if len(p.stockouts[storeID]) > 10 {
		storeLabel := storeID
		if p.storeGuard != nil {
			storeLabel = p.storeGuard.normalize(storeID, "unknown")
		}
		p.metrics.cascadeAlerts.WithLabelValues(storeLabel).Inc()
		p.logger.Warn("stockout cascade detected",
			"store_id", storeID,
			"zero_sku_count", len(p.stockouts[storeID]),
		)
	}
}
