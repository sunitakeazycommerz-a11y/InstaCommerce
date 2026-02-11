package handler

import (
	"github.com/prometheus/client_golang/prometheus"
)

// WebhookMetrics holds Prometheus counters and histograms for monitoring
// payment webhook processing across all PSPs.
type WebhookMetrics struct {
	// EventsReceived counts raw webhook requests, labelled by PSP and event type.
	EventsReceived *prometheus.CounterVec
	// EventsProcessed counts successfully processed events, labelled by PSP and event type.
	EventsProcessed *prometheus.CounterVec
	// EventsDuplicate counts deduplicated (skipped) events, labelled by PSP and event type.
	EventsDuplicate *prometheus.CounterVec
	// EventsFailed counts events that could not be processed, labelled by PSP and failure reason.
	EventsFailed *prometheus.CounterVec
	// VerifyLatency observes signature verification duration in seconds, labelled by PSP.
	VerifyLatency *prometheus.HistogramVec
	// ProcessLatency observes end-to-end processing duration in seconds, labelled by PSP.
	ProcessLatency *prometheus.HistogramVec
}

// NewWebhookMetrics creates and registers all Prometheus metrics for the
// webhook handler. The returned struct is ready to use with WebhookHandler.
func NewWebhookMetrics(reg prometheus.Registerer) *WebhookMetrics {
	m := &WebhookMetrics{
		EventsReceived: prometheus.NewCounterVec(prometheus.CounterOpts{
			Namespace: "payment_webhook",
			Name:      "events_received_total",
			Help:      "Total webhook events received, by PSP and event type.",
		}, []string{"psp", "event_type"}),

		EventsProcessed: prometheus.NewCounterVec(prometheus.CounterOpts{
			Namespace: "payment_webhook",
			Name:      "events_processed_total",
			Help:      "Total webhook events successfully processed, by PSP and event type.",
		}, []string{"psp", "event_type"}),

		EventsDuplicate: prometheus.NewCounterVec(prometheus.CounterOpts{
			Namespace: "payment_webhook",
			Name:      "events_duplicate_total",
			Help:      "Total duplicate webhook events skipped, by PSP and event type.",
		}, []string{"psp", "event_type"}),

		EventsFailed: prometheus.NewCounterVec(prometheus.CounterOpts{
			Namespace: "payment_webhook",
			Name:      "events_failed_total",
			Help:      "Total webhook events that failed processing, by PSP and failure reason.",
		}, []string{"psp", "reason"}),

		VerifyLatency: prometheus.NewHistogramVec(prometheus.HistogramOpts{
			Namespace: "payment_webhook",
			Name:      "verify_latency_seconds",
			Help:      "Signature verification latency in seconds, by PSP.",
			Buckets:   []float64{0.0001, 0.0005, 0.001, 0.005, 0.01, 0.05, 0.1},
		}, []string{"psp"}),

		ProcessLatency: prometheus.NewHistogramVec(prometheus.HistogramOpts{
			Namespace: "payment_webhook",
			Name:      "process_latency_seconds",
			Help:      "End-to-end webhook processing latency in seconds, by PSP.",
			Buckets:   []float64{0.001, 0.005, 0.01, 0.025, 0.05, 0.1, 0.25, 0.5, 1},
		}, []string{"psp"}),
	}

	reg.MustRegister(
		m.EventsReceived,
		m.EventsProcessed,
		m.EventsDuplicate,
		m.EventsFailed,
		m.VerifyLatency,
		m.ProcessLatency,
	)

	return m
}
