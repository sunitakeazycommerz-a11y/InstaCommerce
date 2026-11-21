package main

import (
	"context"
	"log/slog"
	"time"

	"github.com/instacommerce/go-shared/pkg/observability"
	"go.opentelemetry.io/otel"
	"go.opentelemetry.io/otel/metric"
	"go.opentelemetry.io/otel/trace"
)

type telemetry struct {
	tracer   trace.Tracer
	meter    metric.Meter
	shutdown func(context.Context) error
}

type relayMetrics struct {
	relayed  metric.Int64Counter
	failures metric.Int64Counter
	dlq      metric.Int64Counter
	lag      metric.Float64Histogram
}

func (m relayMetrics) addRelayed(ctx context.Context, n int64) {
	if m.relayed != nil {
		m.relayed.Add(ctx, n)
	}
}

func (m relayMetrics) addFailures(ctx context.Context, n int64) {
	if m.failures != nil {
		m.failures.Add(ctx, n)
	}
}

func (m relayMetrics) addDLQ(ctx context.Context, n int64) {
	if m.dlq != nil {
		m.dlq.Add(ctx, n)
	}
}

func (m relayMetrics) recordLag(ctx context.Context, seconds float64) {
	if m.lag != nil {
		m.lag.Record(ctx, seconds)
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
	return relayMetrics{relayed: relayed, failures: failures, dlq: dlq, lag: lag}, nil
}
