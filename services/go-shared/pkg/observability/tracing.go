// Package observability provides unified OpenTelemetry tracing, Prometheus
// metrics, and structured logging for all InstaCommerce Go services.
package observability

import (
	"context"
	"fmt"
	"log/slog"
	"os"

	"go.opentelemetry.io/otel"
	"go.opentelemetry.io/otel/exporters/otlp/otlptrace/otlptracehttp"
	"go.opentelemetry.io/otel/propagation"
	"go.opentelemetry.io/otel/sdk/resource"
	sdktrace "go.opentelemetry.io/otel/sdk/trace"
	semconv "go.opentelemetry.io/otel/semconv/v1.24.0"
)

// InitTracer initialises an OpenTelemetry TracerProvider that exports spans
// via OTLP/HTTP to the endpoint specified by the OTEL_EXPORTER_OTLP_ENDPOINT
// environment variable.
//
// The returned shutdown function flushes pending spans and releases resources;
// it must be called on application exit (typically deferred from main).
//
//	shutdown, err := observability.InitTracer(ctx, "dispatch-optimizer-service", logger)
//	if err != nil { ... }
//	defer shutdown(ctx)
func InitTracer(ctx context.Context, serviceName string, logger *slog.Logger) (func(context.Context) error, error) {
	endpoint := os.Getenv("OTEL_EXPORTER_OTLP_ENDPOINT")
	if endpoint == "" {
		endpoint = "localhost:4318"
	}

	exporter, err := otlptracehttp.New(ctx,
		otlptracehttp.WithEndpoint(endpoint),
		otlptracehttp.WithInsecure(),
	)
	if err != nil {
		return nil, fmt.Errorf("creating OTLP trace exporter: %w", err)
	}

	version := os.Getenv("SERVICE_VERSION")
	if version == "" {
		version = "unknown"
	}
	environment := os.Getenv("ENVIRONMENT")
	if environment == "" {
		environment = "development"
	}

	res, err := resource.Merge(
		resource.Default(),
		resource.NewWithAttributes(
			semconv.SchemaURL,
			semconv.ServiceName(serviceName),
			semconv.ServiceVersion(version),
			semconv.DeploymentEnvironment(environment),
		),
	)
	if err != nil {
		return nil, fmt.Errorf("creating OTel resource: %w", err)
	}

	tp := sdktrace.NewTracerProvider(
		sdktrace.WithBatcher(exporter),
		sdktrace.WithResource(res),
		sdktrace.WithSampler(sdktrace.ParentBased(sdktrace.TraceIDRatioBased(1.0))),
	)

	otel.SetTracerProvider(tp)
	otel.SetTextMapPropagator(propagation.NewCompositeTextMapPropagator(
		propagation.TraceContext{},
		propagation.Baggage{},
	))

	logger.Info("tracer initialised",
		slog.String("service", serviceName),
		slog.String("endpoint", endpoint),
	)

	return tp.Shutdown, nil
}
