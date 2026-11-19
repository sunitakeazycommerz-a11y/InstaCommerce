package main

import (
	"context"
	"errors"
	"log/slog"
	"net/http"
	"net/url"
	"os"
	"strings"
	"time"

	"github.com/instacommerce/go-shared/pkg/correlation"
	"github.com/instacommerce/go-shared/pkg/health"
	"github.com/prometheus/client_golang/prometheus"
	"github.com/prometheus/client_golang/prometheus/promhttp"
	"github.com/segmentio/kafka-go"
	"go.opentelemetry.io/otel"
	"go.opentelemetry.io/otel/exporters/otlp/otlptrace/otlptracegrpc"
	"go.opentelemetry.io/otel/sdk/resource"
	sdktrace "go.opentelemetry.io/otel/sdk/trace"
	semconv "go.opentelemetry.io/otel/semconv/v1.26.0"
	"go.opentelemetry.io/otel/trace"
)

type Metrics struct {
	consumerLag  *prometheus.GaugeVec
	batchLatency prometheus.Histogram
	dlqCount     prometheus.Counter
}

type kafkaHeaderCarrier []kafka.Header

func (c kafkaHeaderCarrier) Get(key string) string {
	for _, header := range c {
		if strings.EqualFold(header.Key, key) {
			return string(header.Value)
		}
	}
	return ""
}

func (c kafkaHeaderCarrier) Set(string, string) {}

func (c kafkaHeaderCarrier) Keys() []string {
	keys := make([]string, 0, len(c))
	for _, header := range c {
		keys = append(keys, header.Key)
	}
	return keys
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
	var opts []otlptracegrpc.Option
	if endpoint := strings.TrimSpace(os.Getenv("OTEL_EXPORTER_OTLP_ENDPOINT")); endpoint != "" {
		parsed, err := url.Parse(endpoint)
		if err != nil {
			logger.Warn("invalid otlp endpoint", "endpoint", endpoint, "error", err)
		} else {
			target := parsed.Host
			if target == "" {
				target = parsed.Path
			}
			if target != "" {
				opts = append(opts, otlptracegrpc.WithEndpoint(target))
				if parsed.Scheme == "" || parsed.Scheme == "http" {
					opts = append(opts, otlptracegrpc.WithInsecure())
				}
			}
		}
	} else {
		opts = append(opts, otlptracegrpc.WithInsecure())
	}

	exporter, err := otlptracegrpc.New(ctx, opts...)
	if err != nil {
		logger.Warn("failed to initialize otlp exporter", "error", err)
		tracerProvider := sdktrace.NewTracerProvider(
			sdktrace.WithResource(resource.NewWithAttributes(semconv.SchemaURL, semconv.ServiceName(serviceName))),
		)
		otel.SetTracerProvider(tracerProvider)
		return tracerProvider, otel.Tracer(serviceName)
	}

	res, err := resource.New(ctx, resource.WithAttributes(semconv.ServiceName(serviceName)))
	if err != nil {
		logger.Warn("failed to create otel resource", "error", err)
	}

	tracerProvider := sdktrace.NewTracerProvider(
		sdktrace.WithBatcher(exporter),
		sdktrace.WithResource(res),
	)
	otel.SetTracerProvider(tracerProvider)
	return tracerProvider, otel.Tracer(serviceName)
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
