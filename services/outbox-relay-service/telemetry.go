package main

import (
	"context"
	"log/slog"
	"sync"
	"time"

	"github.com/instacommerce/go-shared/pkg/observability"
	"github.com/prometheus/client_golang/prometheus"
	"go.opentelemetry.io/otel"
	"go.opentelemetry.io/otel/metric"
	"go.opentelemetry.io/otel/trace"
)

var (
	promMetricsOnce  sync.Once
	promRelayCount   prometheus.Counter
	promRelayFailure prometheus.Counter
	promRelayDLQ     prometheus.Counter
	promRelayLag     prometheus.Histogram
)

func initPromRelayMetrics() {
	promMetricsOnce.Do(func() {
		promRelayCount = prometheus.NewCounter(prometheus.CounterOpts{
			Name: "outbox_relay_events_total",
			Help: "Number of outbox events successfully relayed.",
		})
		promRelayFailure = prometheus.NewCounter(prometheus.CounterOpts{
			Name: "outbox_relay_failures_total",
			Help: "Number of outbox relay failures.",
		})
		promRelayDLQ = prometheus.NewCounter(prometheus.CounterOpts{
			Name: "outbox_relay_dlq_total",
			Help: "Number of outbox events sent to dead-letter queue.",
		})
		promRelayLag = prometheus.NewHistogram(prometheus.HistogramOpts{
			Name:    "outbox_relay_lag_seconds",
			Help:    "Lag between outbox event creation and relay in seconds.",
			Buckets: prometheus.DefBuckets,
		})
		prometheus.MustRegister(promRelayCount, promRelayFailure, promRelayDLQ, promRelayLag)
	})
}

type telemetry struct {
	tracer   trace.Tracer
	meter    metric.Meter
	shutdown func(context.Context) error
}

type relayMetrics struct {
	relayed      metric.Int64Counter
	failures     metric.Int64Counter
	dlq          metric.Int64Counter
	lag          metric.Float64Histogram
	promRelayed  prometheus.Counter
	promFailures prometheus.Counter
	promDLQ      prometheus.Counter
	promLag      prometheus.Histogram
}

func (m relayMetrics) addRelayed(ctx context.Context, n int64) {
	if m.relayed != nil {
		m.relayed.Add(ctx, n)
	}
	if m.promRelayed != nil && n > 0 {
		m.promRelayed.Add(float64(n))
	}
}

func (m relayMetrics) addFailures(ctx context.Context, n int64) {
	if m.failures != nil {
		m.failures.Add(ctx, n)
	}
	if m.promFailures != nil && n > 0 {
		m.promFailures.Add(float64(n))
	}
}

func (m relayMetrics) addDLQ(ctx context.Context, n int64) {
	if m.dlq != nil {
		m.dlq.Add(ctx, n)
	}
	if m.promDLQ != nil && n > 0 {
		m.promDLQ.Add(float64(n))
	}
}

func (m relayMetrics) recordLag(ctx context.Context, seconds float64) {
	if m.lag != nil {
		m.lag.Record(ctx, seconds)
	}
	if m.promLag != nil && seconds >= 0 {
		m.promLag.Observe(seconds)
	}
}

func setupTelemetry(ctx context.Context, cfg Config, logger *slog.Logger) (*telemetry, error) {
	providers, err := observability.InitOTel(ctx, observability.OTelConfig{
		ServiceName:      cfg.OTelServiceName,
		Endpoint:         cfg.OTLPEndpoint,
		Protocol:         observability.OTelProtocolHTTP,
		EnableMetrics:    true,
		MetricInterval:   15 * time.Second,
		NoopIfNoEndpoint: true,
		FallbackNoop:     true,
	}, logger)
	if err != nil {
		return nil, err
	}

	return &telemetry{
		tracer:   otel.Tracer("outbox-relay"),
		meter:    otel.Meter("outbox-relay"),
		shutdown: providers.Shutdown,
	}, nil
}

func newRelayMetrics(meter metric.Meter) (relayMetrics, error) {
	initPromRelayMetrics()
	relayed, err := meter.Int64Counter("outbox.relay.count", metric.WithDescription("Number of outbox events successfully relayed."))
	if err != nil {
		return relayMetrics{}, err
	}
	failures, err := meter.Int64Counter("outbox.relay.failures", metric.WithDescription("Number of outbox relay failures."))
	if err != nil {
		return relayMetrics{}, err
	}
	dlq, err := meter.Int64Counter("outbox.relay.dlq.count", metric.WithDescription("Number of outbox events sent to dead-letter queue."))
	if err != nil {
		return relayMetrics{}, err
	}
	lag, err := meter.Float64Histogram("outbox.relay.lag.seconds", metric.WithDescription("Lag between outbox event creation and relay."), metric.WithUnit("s"))
	if err != nil {
		return relayMetrics{}, err
	}
	return relayMetrics{
		relayed:      relayed,
		failures:     failures,
		dlq:          dlq,
		lag:          lag,
		promRelayed:  promRelayCount,
		promFailures: promRelayFailure,
		promDLQ:      promRelayDLQ,
		promLag:      promRelayLag,
	}, nil
}
