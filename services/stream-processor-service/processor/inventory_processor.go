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
	EventType   string    `json:"eventType"` // StockUpdated, StockDepleted, StockReplenished
	SKUID       string    `json:"skuId"`
	StoreID     string    `json:"storeId"`
	Quantity    int       `json:"quantity"`
	PrevQty     int       `json:"prevQty"`
	Timestamp   time.Time `json:"timestamp"`
}

type velocityRecord struct {
	timestamp time.Time
	delta     int // positive = replenished, negative = sold
}

type inventoryMetrics struct {
	stockUpdatesTotal  *prometheus.CounterVec
	stockoutsTotal     *prometheus.CounterVec
	cascadeAlerts      *prometheus.CounterVec
	velocityGauge      *prometheus.GaugeVec
	processingErrors   prometheus.Counter
}

func newInventoryMetrics() *inventoryMetrics {
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
}

type InventoryProcessor struct {
	redis   *redis.Client
	logger  *slog.Logger
	metrics *inventoryMetrics

	// velocity tracking: store:sku -> records
	velocity map[string][]velocityRecord
	// stockout tracking: store -> set of SKUs at zero
	stockouts map[string]map[string]struct{}
	mu        sync.RWMutex
}

func NewInventoryProcessor(rdb *redis.Client, logger *slog.Logger) *InventoryProcessor {
	return &InventoryProcessor{
		redis:     rdb,
		logger:    logger.With("processor", "inventory"),
		metrics:   newInventoryMetrics(),
		velocity:  make(map[string][]velocityRecord),
		stockouts: make(map[string]map[string]struct{}),
	}
}

func (p *InventoryProcessor) Process(ctx context.Context, event InventoryEvent) error {
	p.logger.Info("processing inventory event",
		"eventType", event.EventType,
		"skuId", event.SKUID,
		"storeId", event.StoreID,
		"quantity", event.Quantity,
	)

	p.metrics.stockUpdatesTotal.WithLabelValues(event.EventType, event.StoreID).Inc()

	pipe := p.redis.Pipeline()
	now := time.Now()

	// Update velocity window
	delta := event.Quantity - event.PrevQty
	p.updateVelocity(event.StoreID, event.SKUID, delta, now)

	switch event.EventType {
	case "StockDepleted":
		p.metrics.stockoutsTotal.WithLabelValues(event.StoreID).Inc()
		p.trackStockout(event.StoreID, event.SKUID, true)

		stockoutKey := fmt.Sprintf("stockout:%s:%s", event.StoreID, event.SKUID)
		pipe.Set(ctx, stockoutKey, now.Unix(), 2*time.Hour)

	case "StockReplenished":
		p.trackStockout(event.StoreID, event.SKUID, false)

		stockoutKey := fmt.Sprintf("stockout:%s:%s", event.StoreID, event.SKUID)
		pipe.Del(ctx, stockoutKey)
	}

	// Store current quantity in Redis
	qtyKey := fmt.Sprintf("inventory:%s:%s", event.StoreID, event.SKUID)
	pipe.Set(ctx, qtyKey, event.Quantity, 0)

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

	p.metrics.velocityGauge.WithLabelValues(skuID, storeID).Set(float64(totalDelta))
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
		p.metrics.cascadeAlerts.WithLabelValues(storeID).Inc()
		p.logger.Warn("stockout cascade detected",
			"store_id", storeID,
			"zero_sku_count", len(p.stockouts[storeID]),
		)
	}
}
