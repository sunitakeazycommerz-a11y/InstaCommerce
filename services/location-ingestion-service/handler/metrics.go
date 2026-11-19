package handler

import (
	"time"

	"github.com/prometheus/client_golang/prometheus"
)

// LocationMetrics holds Prometheus metrics for the location handler.
type LocationMetrics struct {
	UpdatesReceived *prometheus.CounterVec
	UpdatesInvalid  *prometheus.CounterVec
	UpdatesDropped  *prometheus.CounterVec
	UpdatesFlagged  *prometheus.CounterVec
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
		UpdatesFlagged: prometheus.NewCounterVec(prometheus.CounterOpts{
			Namespace: "location_handler",
			Name:      "updates_flagged_total",
			Help:      "Total location updates annotated with telemetry quality flags.",
		}, []string{"flag"}),
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
		m.UpdatesFlagged,
		m.ProcessLatency,
		m.GeofenceEmitted,
		m.GeofenceDropped,
	)
	return m
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

func (h *LocationHandler) recordFlags(flags []string) {
	if h == nil || h.metrics == nil || h.metrics.UpdatesFlagged == nil {
		return
	}
	for _, flag := range flags {
		h.metrics.UpdatesFlagged.WithLabelValues(flag).Inc()
	}
}

func (h *LocationHandler) recordLatency(source string, d time.Duration) {
	if h != nil && h.metrics != nil && h.metrics.ProcessLatency != nil {
		h.metrics.ProcessLatency.WithLabelValues(source).Observe(d.Seconds())
	}
}
