package main

import (
	"context"
	"errors"
	"log/slog"
	"net/http"
	"os"
	"time"

	"github.com/instacommerce/go-shared/pkg/correlation"
	"github.com/instacommerce/go-shared/pkg/health"
	"github.com/instacommerce/go-shared/pkg/observability"
	"github.com/prometheus/client_golang/prometheus"
	"github.com/prometheus/client_golang/prometheus/promhttp"
	"go.opentelemetry.io/otel"
	sdktrace "go.opentelemetry.io/otel/sdk/trace"
	"go.opentelemetry.io/otel/trace"
)

type Metrics struct {
	consumerLag  *prometheus.GaugeVec
	batchLatency prometheus.Histogram
	dlqCount     prometheus.Counter
}

func initMetrics() Metrics {
	consumerLag := prometheus.NewGaugeVec(prometheus.GaugeOpts{
		Name: "cdc_consumer_lag",
		Help: "Kafka consumer lag per topic and partition.",
	}, []string{"topic", "partition"})

	batchLatency := prometheus.NewHistogram(prometheus.HistogramOpts{
		Name:    "cdc_batch_latency_seconds",
		Help:    "Latency for BigQuery batch inserts.",
		Buckets: prometheus.DefBuckets,
	})

	dlqCount := prometheus.NewCounter(prometheus.CounterOpts{
		Name: "cdc_dlq_total",
		Help: "Number of messages sent to the Kafka DLQ.",
	})

	prometheus.MustRegister(consumerLag, batchLatency, dlqCount)
	return Metrics{
		consumerLag:  consumerLag,
		batchLatency: batchLatency,
		dlqCount:     dlqCount,
	}
}

func setupLogger(level slog.Level) *slog.Logger {
	handler := slog.NewJSONHandler(os.Stdout, &slog.HandlerOptions{Level: level})
	logger := slog.New(handler)
	slog.SetDefault(logger)
	return logger
}

func setupTracing(ctx context.Context, logger *slog.Logger) (*sdktrace.TracerProvider, trace.Tracer) {
	providers, err := observability.InitOTel(ctx, observability.OTelConfig{
		ServiceName:  serviceName,
		Protocol:     observability.OTelProtocolGRPC,
		ResolveEnv:   true,
		FallbackNoop: true,
	}, logger)
	if err != nil {
		logger.Warn("failed to initialize tracing", "error", err)
		return nil, otel.Tracer(serviceName)
	}
	return providers.TracerProvider, otel.Tracer(serviceName)
}

func startHTTPServer(port string, healthHandler *health.Handler, logger *slog.Logger) *http.Server {
	mux := http.NewServeMux()
	healthHandler.RegisterRoutes(mux)
	mux.HandleFunc("/ready", healthHandler.ReadyHandler())
	mux.Handle("/metrics", promhttp.Handler())

	server := &http.Server{
		Addr:              ":" + port,
		Handler:           correlation.Middleware(mux),
		ReadHeaderTimeout: 5 * time.Second,
		ReadTimeout:       15 * time.Second,
		WriteTimeout:      15 * time.Second,
		IdleTimeout:       60 * time.Second,
	}

	go func() {
		logger.Info("http server listening", "addr", server.Addr)
		if err := server.ListenAndServe(); err != nil && !errors.Is(err, http.ErrServerClosed) {
			logger.Error("http server failed", "error", err)
		}
	}()
	return server
}
