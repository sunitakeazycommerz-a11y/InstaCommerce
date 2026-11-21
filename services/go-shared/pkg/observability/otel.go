package observability

import (
	"context"
	"fmt"
	"log/slog"
	"net/url"
	"os"
	"strings"
	"time"

	"go.opentelemetry.io/otel"
	"go.opentelemetry.io/otel/attribute"
	"go.opentelemetry.io/otel/exporters/otlp/otlpmetric/otlpmetrichttp"
	"go.opentelemetry.io/otel/exporters/otlp/otlptrace/otlptracegrpc"
	"go.opentelemetry.io/otel/exporters/otlp/otlptrace/otlptracehttp"
	"go.opentelemetry.io/otel/exporters/stdout/stdouttrace"
	"go.opentelemetry.io/otel/propagation"
	sdkmetric "go.opentelemetry.io/otel/sdk/metric"
	"go.opentelemetry.io/otel/sdk/resource"
	sdktrace "go.opentelemetry.io/otel/sdk/trace"
	semconv "go.opentelemetry.io/otel/semconv/v1.24.0"
)

// OTelProtocol selects the OTLP wire transport.
type OTelProtocol string

const (
	OTelProtocolHTTP OTelProtocol = "http"
	OTelProtocolGRPC OTelProtocol = "grpc"
)

// OTelConfig configures OpenTelemetry tracing and optional OTLP metrics.
type OTelConfig struct {
	ServiceName      string
	ServiceVersion   string
	Environment      string
	Endpoint         string
	Protocol         OTelProtocol
	ResolveEnv       bool
	EnableMetrics    bool
	MetricInterval   time.Duration
	Attributes       []attribute.KeyValue
	StdoutFallback   bool
	FallbackNoop     bool
	NoopIfNoEndpoint bool
}

// OTelProviders wraps initialized OpenTelemetry providers and their combined shutdown hook.
type OTelProviders struct {
	TracerProvider *sdktrace.TracerProvider
	MeterProvider  *sdkmetric.MeterProvider
	Shutdown       func(context.Context) error
}

// InitOTel initializes OpenTelemetry trace/metric providers, sets global propagators,
// and returns a combined Shutdown function.
func InitOTel(ctx context.Context, cfg OTelConfig, logger *slog.Logger) (*OTelProviders, error) {
	if logger == nil {
		logger = slog.Default()
	}

	endpoint := strings.TrimSpace(cfg.Endpoint)
	if endpoint == "" && cfg.ResolveEnv {
		endpoint = resolveOTLPEndpointFromEnv()
	}

	res, err := buildOTelResource(cfg)
	if err != nil {
		return nil, fmt.Errorf("creating OTel resource: %w", err)
	}

	tp, err := buildTracerProvider(ctx, cfg, endpoint, res, logger)
	if err != nil {
		return nil, err
	}
	otel.SetTracerProvider(tp)

	var mp *sdkmetric.MeterProvider
	if cfg.EnableMetrics {
		mp = buildMeterProvider(ctx, cfg, endpoint, res, logger)
		otel.SetMeterProvider(mp)
	}

	otel.SetTextMapPropagator(propagation.NewCompositeTextMapPropagator(
		propagation.TraceContext{},
		propagation.Baggage{},
	))

	shutdown := func(shutdownCtx context.Context) error {
		var firstErr error
		if err := tp.Shutdown(shutdownCtx); err != nil {
			firstErr = err
		}
		if mp != nil {
			if err := mp.Shutdown(shutdownCtx); err != nil && firstErr == nil {
				firstErr = err
			}
		}
		return firstErr
	}

	return &OTelProviders{
		TracerProvider: tp,
		MeterProvider:  mp,
		Shutdown:       shutdown,
	}, nil
}

func resolveOTLPEndpointFromEnv() string {
	if v := strings.TrimSpace(os.Getenv("OTEL_EXPORTER_OTLP_TRACES_ENDPOINT")); v != "" {
		return v
	}
	return strings.TrimSpace(os.Getenv("OTEL_EXPORTER_OTLP_ENDPOINT"))
}

func buildOTelResource(cfg OTelConfig) (*resource.Resource, error) {
	attrs := make([]attribute.KeyValue, 0, 3+len(cfg.Attributes))
	if cfg.ServiceName != "" {
		attrs = append(attrs, semconv.ServiceName(cfg.ServiceName))
	}
	if cfg.ServiceVersion != "" {
		attrs = append(attrs, semconv.ServiceVersion(cfg.ServiceVersion))
	}
	if cfg.Environment != "" {
		attrs = append(attrs, semconv.DeploymentEnvironment(cfg.Environment))
	}
	attrs = append(attrs, cfg.Attributes...)
	return resource.Merge(resource.Default(), resource.NewSchemaless(attrs...))
}

func buildTracerProvider(
	ctx context.Context,
	cfg OTelConfig,
	endpoint string,
	res *resource.Resource,
	logger *slog.Logger,
) (*sdktrace.TracerProvider, error) {
	if endpoint == "" && cfg.NoopIfNoEndpoint {
		return sdktrace.NewTracerProvider(sdktrace.WithResource(res)), nil
	}

	var exporter sdktrace.SpanExporter
	var err error
	switch cfg.Protocol {
	case OTelProtocolGRPC:
		exporter, err = buildGRPCTraceExporter(ctx, endpoint, cfg.StdoutFallback, logger)
	default:
		exporter, err = otlptracehttp.New(ctx, httpTraceEndpointOptions(endpoint)...)
	}

	if err != nil {
		if cfg.FallbackNoop {
			logger.Warn("failed to initialize OTLP trace exporter, using noop provider", "error", err)
			return sdktrace.NewTracerProvider(sdktrace.WithResource(res)), nil
		}
		return nil, fmt.Errorf("creating OTLP trace exporter: %w", err)
	}

	return sdktrace.NewTracerProvider(
		sdktrace.WithResource(res),
		sdktrace.WithBatcher(exporter),
	), nil
}

func buildGRPCTraceExporter(
	ctx context.Context,
	endpoint string,
	stdoutFallback bool,
	logger *slog.Logger,
) (sdktrace.SpanExporter, error) {
	if endpoint == "" {
		if stdoutFallback {
			logger.Info("OTLP endpoint not set; using stdout exporter")
			return stdouttrace.New(stdouttrace.WithPrettyPrint())
		}
		return otlptracegrpc.New(ctx)
	}

	opts := grpcEndpointOptions(endpoint, logger)
	return otlptracegrpc.New(ctx, opts...)
}

func buildMeterProvider(
	ctx context.Context,
	cfg OTelConfig,
	endpoint string,
	res *resource.Resource,
	logger *slog.Logger,
) *sdkmetric.MeterProvider {
	if endpoint == "" {
		return sdkmetric.NewMeterProvider(sdkmetric.WithResource(res))
	}
	exporter, err := otlpmetrichttp.New(ctx, httpMetricEndpointOptions(endpoint)...)
	if err != nil {
		logger.Warn("failed to create OTLP metric exporter, using noop exporter", "error", err)
		return sdkmetric.NewMeterProvider(sdkmetric.WithResource(res))
	}
	interval := cfg.MetricInterval
	if interval <= 0 {
		interval = 15 * time.Second
	}
	return sdkmetric.NewMeterProvider(
		sdkmetric.WithResource(res),
		sdkmetric.WithReader(sdkmetric.NewPeriodicReader(exporter, sdkmetric.WithInterval(interval))),
	)
}

func parseOTLPHTTPEndpoint(endpoint string) (endpointURL string, insecure bool) {
	if strings.HasPrefix(endpoint, "https://") {
		return endpoint, false
	}
	if strings.HasPrefix(endpoint, "http://") {
		return endpoint, true
	}
	return "", true
}

func httpTraceEndpointOptions(endpoint string) []otlptracehttp.Option {
	if endpoint == "" {
		return []otlptracehttp.Option{otlptracehttp.WithEndpoint("localhost:4318"), otlptracehttp.WithInsecure()}
	}
	u, insecure := parseOTLPHTTPEndpoint(endpoint)
	var opts []otlptracehttp.Option
	if u != "" {
		opts = append(opts, otlptracehttp.WithEndpointURL(u))
	} else {
		opts = append(opts, otlptracehttp.WithEndpoint(endpoint))
	}
	if insecure {
		opts = append(opts, otlptracehttp.WithInsecure())
	}
	return opts
}

func httpMetricEndpointOptions(endpoint string) []otlpmetrichttp.Option {
	u, insecure := parseOTLPHTTPEndpoint(endpoint)
	var opts []otlpmetrichttp.Option
	if u != "" {
		opts = append(opts, otlpmetrichttp.WithEndpointURL(u))
	} else {
		opts = append(opts, otlpmetrichttp.WithEndpoint(endpoint))
	}
	if insecure {
		opts = append(opts, otlpmetrichttp.WithInsecure())
	}
	return opts
}

func grpcEndpointOptions(endpoint string, logger *slog.Logger) []otlptracegrpc.Option {
	var opts []otlptracegrpc.Option
	if parsed, err := url.Parse(endpoint); err == nil {
		target := parsed.Host
		if target == "" {
			target = parsed.Path
		}
		if target != "" {
			opts = append(opts, otlptracegrpc.WithEndpoint(target))
			if parsed.Scheme == "" || strings.EqualFold(parsed.Scheme, "http") {
				opts = append(opts, otlptracegrpc.WithInsecure())
			}
			return opts
		}
	} else {
		logger.Warn("invalid otlp endpoint", "endpoint", endpoint, "error", err)
	}
	return []otlptracegrpc.Option{otlptracegrpc.WithInsecure()}
}
