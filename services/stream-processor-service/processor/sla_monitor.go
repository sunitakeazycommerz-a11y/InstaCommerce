package processor

// Real-time SLA monitoring. Alert when zone drops below 90% 10-min delivery.
// Uses sliding window (30 min) per zone.

import (
	"log/slog"
	"sync"
	"time"

	"github.com/prometheus/client_golang/prometheus"
	"github.com/prometheus/client_golang/prometheus/promauto"
)

const (
	slaTargetMinutes = 10.0
	windowDuration   = 30 * time.Minute
)

type SLAAlert struct {
	ZoneID     string
	SLAPercent float64
	Threshold  float64
	Timestamp  time.Time
}

type deliveryRecord struct {
	timestamp    time.Time
	deliveryMins float64
}

type SLAWindow struct {
	ZoneID       string
	WindowStart  time.Time
	TotalOrders  int
	SLAMetOrders int
	SLAPercent   float64
	records      []deliveryRecord
}

type SLAMonitor struct {
	windows   map[string]*SLAWindow // zone -> window
	threshold float64               // 0.90
	alertCh   chan SLAAlert
	mu        sync.RWMutex
	logger    *slog.Logger
	metrics   *slaMetrics
}

type slaMetrics struct {
	slaPercent    *prometheus.GaugeVec
	alertsTotal   *prometheus.CounterVec
	windowOrders  *prometheus.GaugeVec
}

func newSLAMetrics() *slaMetrics {
	return &slaMetrics{
		slaPercent: promauto.NewGaugeVec(prometheus.GaugeOpts{
			Name: "sla_window_percent",
			Help: "Current SLA compliance percentage per zone (30-min window)",
		}, []string{"zone_id"}),

		alertsTotal: promauto.NewCounterVec(prometheus.CounterOpts{
			Name: "sla_alerts_total",
			Help: "Total SLA breach alerts emitted per zone",
		}, []string{"zone_id"}),

		windowOrders: promauto.NewGaugeVec(prometheus.GaugeOpts{
			Name: "sla_window_orders",
			Help: "Total orders in current SLA window per zone",
		}, []string{"zone_id"}),
	}
}

func NewSLAMonitor(threshold float64, logger *slog.Logger) *SLAMonitor {
	m := &SLAMonitor{
		windows:   make(map[string]*SLAWindow),
		threshold: threshold,
		alertCh:   make(chan SLAAlert, 100),
		logger:    logger.With("component", "sla_monitor"),
		metrics:   newSLAMetrics(),
	}

	// Drain alert channel in background to avoid blocking
	go m.drainAlerts()

	return m
}

// AlertCh returns the channel on which SLA breach alerts are published.
func (m *SLAMonitor) AlertCh() <-chan SLAAlert {
	return m.alertCh
}

func (m *SLAMonitor) RecordDelivery(zoneID string, deliveryTimeMinutes float64) {
	m.mu.Lock()
	defer m.mu.Unlock()

	now := time.Now()

	w, ok := m.windows[zoneID]
	if !ok {
		w = &SLAWindow{
			ZoneID:      zoneID,
			WindowStart: now,
			records:     make([]deliveryRecord, 0, 256),
		}
		m.windows[zoneID] = w
	}

	// Evict records outside the sliding window
	m.evictOldRecords(w, now)

	// Add new record
	rec := deliveryRecord{timestamp: now, deliveryMins: deliveryTimeMinutes}
	w.records = append(w.records, rec)
	w.TotalOrders = len(w.records)

	// Recount SLA-met orders
	metCount := 0
	for _, r := range w.records {
		if r.deliveryMins <= slaTargetMinutes {
			metCount++
		}
	}
	w.SLAMetOrders = metCount

	if w.TotalOrders > 0 {
		w.SLAPercent = float64(w.SLAMetOrders) / float64(w.TotalOrders)
	}

	// Emit Prometheus metrics
	m.metrics.slaPercent.WithLabelValues(zoneID).Set(w.SLAPercent)
	m.metrics.windowOrders.WithLabelValues(zoneID).Set(float64(w.TotalOrders))

	// Check threshold breach
	if w.SLAPercent < m.threshold && w.TotalOrders >= 5 {
		alert := SLAAlert{
			ZoneID:     zoneID,
			SLAPercent: w.SLAPercent,
			Threshold:  m.threshold,
			Timestamp:  now,
		}

		select {
		case m.alertCh <- alert:
			m.metrics.alertsTotal.WithLabelValues(zoneID).Inc()
		default:
			m.logger.Warn("sla alert channel full, dropping alert", "zone_id", zoneID)
		}
	}
}

// GetSLAPercent returns the current SLA percentage for a zone. Thread-safe.
func (m *SLAMonitor) GetSLAPercent(zoneID string) (float64, bool) {
	m.mu.RLock()
	defer m.mu.RUnlock()

	w, ok := m.windows[zoneID]
	if !ok {
		return 0, false
	}
	return w.SLAPercent, true
}

func (m *SLAMonitor) evictOldRecords(w *SLAWindow, now time.Time) {
	cutoff := now.Add(-windowDuration)
	idx := 0
	for idx < len(w.records) && w.records[idx].timestamp.Before(cutoff) {
		idx++
	}
	if idx > 0 {
		w.records = w.records[idx:]
		w.WindowStart = cutoff
	}
}

func (m *SLAMonitor) drainAlerts() {
	for alert := range m.alertCh {
		m.logger.Warn("SLA breach detected",
			"zone_id", alert.ZoneID,
			"sla_percent", alert.SLAPercent,
			"threshold", alert.Threshold,
		)
	}
}
