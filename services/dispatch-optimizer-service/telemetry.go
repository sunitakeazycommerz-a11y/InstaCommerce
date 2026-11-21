package main

import (
	"context"
	"log/slog"
	"net/http"
	"os"
	"strconv"
	"strings"
	"time"

	"github.com/instacommerce/go-shared/pkg/observability"
	"github.com/prometheus/client_golang/prometheus"
	oteltrace "go.opentelemetry.io/otel/trace"
)

type serviceMetrics struct {
	requestCount     *prometheus.CounterVec
	requestDuration  *prometheus.HistogramVec
	optimizeDuration prometheus.Histogram
	assignedOrders   prometheus.Counter
	unassignedOrders prometheus.Counter
}

type responseRecorder struct {
	http.ResponseWriter
	status int
	bytes  int
}

func (r *responseRecorder) WriteHeader(status int) {
	r.status = status
	r.ResponseWriter.WriteHeader(status)
}

func (r *responseRecorder) Write(body []byte) (int, error) {
	if r.status == 0 {
		r.status = http.StatusOK
	}
	written, err := r.ResponseWriter.Write(body)
	r.bytes += written
	return written, err
}

func (r *responseRecorder) Unwrap() http.ResponseWriter {
	return r.ResponseWriter
}

func newMetrics() *serviceMetrics {
	metrics := &serviceMetrics{
		requestCount: prometheus.NewCounterVec(
			prometheus.CounterOpts{
				Name: "dispatch_optimizer_http_requests_total",
				Help: "Total number of HTTP requests handled.",
			},
			[]string{"path", "method", "status"},
		),
		requestDuration: prometheus.NewHistogramVec(
			prometheus.HistogramOpts{
				Name:    "dispatch_optimizer_http_request_duration_seconds",
				Help:    "HTTP request duration in seconds.",
				Buckets: prometheus.DefBuckets,
			},
			[]string{"path", "method", "status"},
		),
		optimizeDuration: prometheus.NewHistogram(
			prometheus.HistogramOpts{
				Name:    "dispatch_optimizer_assignment_duration_seconds",
				Help:    "Assignment optimization duration in seconds.",
				Buckets: prometheus.DefBuckets,
			},
		),
		assignedOrders: prometheus.NewCounter(
			prometheus.CounterOpts{
				Name: "dispatch_optimizer_assigned_orders_total",
				Help: "Total number of assigned orders.",
			},
		),
		unassignedOrders: prometheus.NewCounter(
			prometheus.CounterOpts{
				Name: "dispatch_optimizer_unassigned_orders_total",
				Help: "Total number of unassigned orders.",
			},
		),
	}
	prometheus.MustRegister(
		metrics.requestCount,
		metrics.requestDuration,
		metrics.optimizeDuration,
		metrics.assignedOrders,
		metrics.unassignedOrders,
	)
	return metrics
}

func initLogger() *slog.Logger {
	level := slog.LevelInfo
	if levelText := strings.TrimSpace(os.Getenv("LOG_LEVEL")); levelText != "" {
		var parsed slog.Level
		if err := parsed.UnmarshalText([]byte(levelText)); err == nil {
			level = parsed
		}
	}
	handler := slog.NewJSONHandler(os.Stdout, &slog.HandlerOptions{Level: level})
	return slog.New(handler).With("service", serviceName)
}

func initTracerProvider(ctx context.Context, logger *slog.Logger) func(context.Context) error {
	providers, err := observability.InitOTel(ctx, observability.OTelConfig{
		ServiceName:      serviceName,
		Protocol:         observability.OTelProtocolGRPC,
		ResolveEnv:       true,
		NoopIfNoEndpoint: true,
		FallbackNoop:     true,
	}, logger)
	if err != nil {
		logger.Error("failed to initialize tracer provider", "error", err)
		return func(context.Context) error { return nil }
	}
	return providers.Shutdown
}

func instrumentHandler(route string, logger *slog.Logger, metrics *serviceMetrics, next http.Handler) http.Handler {
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		start := time.Now()
		recorder := &responseRecorder{ResponseWriter: w}
		next.ServeHTTP(recorder, r)
		status := recorder.status
		if status == 0 {
			status = http.StatusOK
		}
		duration := time.Since(start)
		metrics.requestCount.WithLabelValues(route, r.Method, strconv.Itoa(status)).Inc()
		metrics.requestDuration.WithLabelValues(route, r.Method, strconv.Itoa(status)).Observe(duration.Seconds())
		if route != "health" {
			traceID, spanID := traceFields(r.Context())
			logger.Info("request completed",
				"method", r.Method,
				"path", r.URL.Path,
				"status", status,
				"duration_ms", duration.Milliseconds(),
				"bytes", recorder.bytes,
				"trace_id", traceID,
				"span_id", spanID,
			)
		}
	})
}

func traceFields(ctx context.Context) (string, string) {
	spanCtx := oteltrace.SpanContextFromContext(ctx)
	if !spanCtx.IsValid() {
		return "", ""
	}
	return spanCtx.TraceID().String(), spanCtx.SpanID().String()
}
