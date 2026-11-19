package main

import (
	"context"
	"log/slog"
	"strings"
	"time"

	"go.opentelemetry.io/otel"
	"go.opentelemetry.io/otel/exporters/otlp/otlpmetric/otlpmetrichttp"
	"go.opentelemetry.io/otel/exporters/otlp/otlptrace/otlptracehttp"
	"go.opentelemetry.io/otel/metric"
	"go.opentelemetry.io/otel/propagation"
	sdkmetric "go.opentelemetry.io/otel/sdk/metric"
	"go.opentelemetry.io/otel/sdk/resource"
	sdktrace "go.opentelemetry.io/otel/sdk/trace"
	"go.opentelemetry.io/otel/semconv/v1.26.0"
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

func setupTelemetry(ctx context.Context, cfg Config, logger *slog.Logger) (*telemetry, error) {
	res, err := resource.New(ctx, resource.WithAttributes(semconv.ServiceName(cfg.OTelServiceName)))
	if err != nil {
		logger.Warn("failed to create otel resource", "error", err)
	}
	if res == nil {
		res = resource.Empty()
	}

	traceProvider := newTraceProvider(ctx, cfg, logger, res)
	metricProvider := newMetricProvider(ctx, cfg, logger, res)

	otel.SetTracerProvider(traceProvider)
	otel.SetMeterProvider(metricProvider)
	otel.SetTextMapPropagator(propagation.NewCompositeTextMapPropagator(propagation.TraceContext{}, propagation.Baggage{}))

	shutdown := func(ctx context.Context) error {
		var shutdownErr error
		if err := traceProvider.Shutdown(ctx); err != nil {
			shutdownErr = err
		}
		if err := metricProvider.Shutdown(ctx); err != nil && shutdownErr == nil {
			shutdownErr = err
		}
		return shutdownErr
	}

	return &telemetry{
		tracer:   otel.Tracer("outbox-relay"),
		meter:    otel.Meter("outbox-relay"),
		shutdown: shutdown,
	}, nil
}

func newTraceProvider(ctx context.Context, cfg Config, logger *slog.Logger, res *resource.Resource) *sdktrace.TracerProvider {
	if cfg.OTLPEndpoint == "" {
		return sdktrace.NewTracerProvider(sdktrace.WithResource(res))
	}
	traceExporter, err := otlptracehttp.New(ctx, otlpHTTPEndpointOptions(cfg.OTLPEndpoint)...)
	if err != nil {
		logger.Warn("failed to create otel trace exporter, using noop exporter", "error", err)
		return sdktrace.NewTracerProvider(sdktrace.WithResource(res))
	}
	return sdktrace.NewTracerProvider(
		sdktrace.WithResource(res),
		sdktrace.WithBatcher(traceExporter),
	)
}

func newMetricProvider(ctx context.Context, cfg Config, logger *slog.Logger, res *resource.Resource) *sdkmetric.MeterProvider {
	if cfg.OTLPEndpoint == "" {
		return sdkmetric.NewMeterProvider(sdkmetric.WithResource(res))
	}
	metricExporter, err := otlpmetrichttp.New(ctx, otlpMetricHTTPEndpointOptions(cfg.OTLPEndpoint)...)
	if err != nil {
		logger.Warn("failed to create otel metric exporter, using noop exporter", "error", err)
		return sdkmetric.NewMeterProvider(sdkmetric.WithResource(res))
	}
	reader := sdkmetric.NewPeriodicReader(metricExporter, sdkmetric.WithInterval(15*time.Second))
	return sdkmetric.NewMeterProvider(
		sdkmetric.WithResource(res),
		sdkmetric.WithReader(reader),
	)
}

func otlpHTTPEndpointOptions(endpoint string) []otlptracehttp.Option {
	if strings.HasPrefix(endpoint, "https://") {
		return []otlptracehttp.Option{otlptracehttp.WithEndpointURL(endpoint)}
	}
	if strings.HasPrefix(endpoint, "http://") {
		return []otlptracehttp.Option{otlptracehttp.WithEndpointURL(endpoint), otlptracehttp.WithInsecure()}
	}
	return []otlptracehttp.Option{otlptracehttp.WithEndpoint(endpoint), otlptracehttp.WithInsecure()}
}

func otlpMetricHTTPEndpointOptions(endpoint string) []otlpmetrichttp.Option {
	if strings.HasPrefix(endpoint, "https://") {
		return []otlpmetrichttp.Option{otlpmetrichttp.WithEndpointURL(endpoint)}
	}
	if strings.HasPrefix(endpoint, "http://") {
		return []otlpmetrichttp.Option{otlpmetrichttp.WithEndpointURL(endpoint), otlpmetrichttp.WithInsecure()}
	}
	return []otlpmetrichttp.Option{otlpmetrichttp.WithEndpoint(endpoint), otlpmetrichttp.WithInsecure()}
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
