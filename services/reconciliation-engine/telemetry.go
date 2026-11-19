package main

import (
	"context"
	"database/sql"
	"fmt"
	"log/slog"
	"net/url"
	"os"
	"strings"
	"time"

	"github.com/prometheus/client_golang/prometheus"
	"go.opentelemetry.io/otel"
	"go.opentelemetry.io/otel/exporters/otlp/otlptrace/otlptracegrpc"
	"go.opentelemetry.io/otel/exporters/stdout/stdouttrace"
	"go.opentelemetry.io/otel/sdk/resource"
	sdktrace "go.opentelemetry.io/otel/sdk/trace"
	"go.opentelemetry.io/otel/semconv/v1.21.0"
	"go.opentelemetry.io/otel/trace"
)

type Metrics struct {
	mismatches   prometheus.Counter
	fixed        prometheus.Counter
	manualReview prometheus.Counter
}

func NewMetrics() *Metrics {
	m := &Metrics{
		mismatches: prometheus.NewCounter(prometheus.CounterOpts{
			Name: "reconciliation_mismatches_total",
			Help: "Total number of reconciliation mismatches found.",
		}),
		fixed: prometheus.NewCounter(prometheus.CounterOpts{
			Name: "reconciliation_fixed_total",
			Help: "Total number of reconciliation mismatches fixed automatically.",
		}),
		manualReview: prometheus.NewCounter(prometheus.CounterOpts{
			Name: "reconciliation_manual_review_total",
			Help: "Total number of reconciliation mismatches sent for manual review.",
		}),
	}
	prometheus.MustRegister(m.mismatches, m.fixed, m.manualReview)
	return m
}

func (m *Metrics) RecordMismatch()     { m.mismatches.Inc() }
func (m *Metrics) RecordFixed()        { m.fixed.Inc() }
func (m *Metrics) RecordManualReview() { m.manualReview.Inc() }

func newLogger(level string) *slog.Logger {
	handler := slog.NewJSONHandler(os.Stdout, &slog.HandlerOptions{Level: parseLogLevel(level)})
	return slog.New(handler)
}

func parseLogLevel(level string) slog.Level {
	switch strings.ToLower(strings.TrimSpace(level)) {
	case "debug":
		return slog.LevelDebug
	case "warn", "warning":
		return slog.LevelWarn
	case "error":
		return slog.LevelError
	default:
		return slog.LevelInfo
	}
}

func initTracer(ctx context.Context, serviceName string, logger *slog.Logger) (func(context.Context) error, error) {
	res, err := resource.New(ctx, resource.WithAttributes(semconv.ServiceName(serviceName)))
	if err != nil {
		return nil, err
	}
	exporter, err := buildTraceExporter(ctx, logger)
	if err != nil {
		return nil, err
	}
	tp := sdktrace.NewTracerProvider(
		sdktrace.WithBatcher(exporter),
		sdktrace.WithResource(res),
	)
	otel.SetTracerProvider(tp)
	return tp.Shutdown, nil
}

func buildTraceExporter(ctx context.Context, logger *slog.Logger) (sdktrace.SpanExporter, error) {
	endpoint := getenv("OTEL_EXPORTER_OTLP_ENDPOINT", "")
	if endpoint == "" {
		endpoint = getenv("OTEL_EXPORTER_OTLP_TRACES_ENDPOINT", "")
	}
	if endpoint == "" {
		logger.Info("OTLP endpoint not set; using stdout exporter")
		return stdouttrace.New(stdouttrace.WithPrettyPrint())
	}

	secure := false
	if parsed, err := url.Parse(endpoint); err == nil && parsed.Host != "" {
		endpoint = parsed.Host
		secure = strings.EqualFold(parsed.Scheme, "https")
	} else if strings.HasPrefix(endpoint, "https://") {
		secure = true
	}

	endpoint = strings.TrimPrefix(strings.TrimPrefix(endpoint, "http://"), "https://")
	options := []otlptracegrpc.Option{otlptracegrpc.WithEndpoint(endpoint)}
	if !secure {
		options = append(options, otlptracegrpc.WithInsecure())
	}
	return otlptracegrpc.New(ctx, options...)
}

func initDatabase(ctx context.Context, cfg Config, logger *slog.Logger) (*sql.DB, error) {
	dsn := fmt.Sprintf("postgres://%s:%s@%s:%s/%s?sslmode=disable&connect_timeout=%d",
		cfg.DBUser, cfg.DBPassword, cfg.DBHost, cfg.DBPort, cfg.DBName, int(cfg.DBConnTimeout.Seconds()),
	)
	db, err := sql.Open("postgres", dsn)
	if err != nil {
		return nil, fmt.Errorf("failed to open database: %w", err)
	}
	db.SetMaxOpenConns(cfg.DBMaxConnections)
	db.SetMaxIdleConns(cfg.DBMaxConnections / 2)
	db.SetConnMaxLifetime(30 * time.Minute)

	connCtx, cancel := context.WithTimeout(ctx, cfg.DBConnTimeout)
	defer cancel()
	if err := db.PingContext(connCtx); err != nil {
		db.Close()
		return nil, fmt.Errorf("failed to verify database connection: %w", err)
	}
	logger.Info("database connection pool initialized", "host", cfg.DBHost, "port", cfg.DBPort, "database", cfg.DBName)
	return db, nil
}

func loggerWithContext(ctx context.Context, logger *slog.Logger) *slog.Logger {
	span := trace.SpanContextFromContext(ctx)
	if !span.IsValid() {
		return logger
	}
	return logger.With("trace_id", span.TraceID().String(), "span_id", span.SpanID().String())
}
