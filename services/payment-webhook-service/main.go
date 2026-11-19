package main

import (
	"context"
	"errors"
	"net/http"
	"os"
	"os/signal"
	"syscall"
	"time"

	"github.com/instacommerce/go-shared/pkg/correlation"
	"github.com/instacommerce/go-shared/pkg/health"
	"github.com/instacommerce/go-shared/pkg/observability"
	"github.com/instacommerce/payment-webhook-service/handler"
	"github.com/prometheus/client_golang/prometheus"
	"github.com/prometheus/client_golang/prometheus/promhttp"
	"github.com/redis/go-redis/v9"
	"github.com/segmentio/kafka-go"
	"go.opentelemetry.io/contrib/instrumentation/net/http/otelhttp"
	"go.opentelemetry.io/otel"
)

func main() {
	cfg := loadConfig()
	logger := observability.NewLogger(os.Getenv("LOG_LEVEL"))

	shutdownTracer, err := initTracing(cfg, logger)
	if err != nil {
		logger.Error("failed to initialize tracing", "error", err)
	}
	defer func() {
		ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
		defer cancel()
		if err := shutdownTracer(ctx); err != nil {
			logger.Error("failed to shutdown tracer", "error", err)
		}
	}()

	var dedupe handler.DedupeStore
	var redisClient *redis.Client
	if cfg.RedisAddr != "" {
		redisStore, err := handler.NewRedisIdempotencyStore(handler.RedisDedupeConfig{
			Addr:     cfg.RedisAddr,
			Password: cfg.RedisPassword,
			DB:       cfg.RedisDB,
			TTL:      cfg.DedupeTTL,
			Timeout:  cfg.RedisTimeout,
		})
		if err != nil {
			logger.Error("failed to initialize redis dedupe", "error", err)
		} else {
			dedupe = redisStore
			redisClient = redisStore.Client()
		}
	}
	if dedupe == nil {
		dedupe = handler.NewInMemoryIdempotencyStore(cfg.DedupeTTL, 10000, cfg.DedupeCleanup)
	}
	defer func() {
		if err := dedupe.Close(); err != nil {
			logger.Error("failed to close dedupe store", "error", err)
		}
	}()

	var writer handler.MessageWriter
	var producer handler.KafkaProducer = handler.UnconfiguredKafkaProducer{}
	if len(cfg.KafkaBrokers) > 0 {
		writer = kafka.NewWriter(kafka.WriterConfig{
			Brokers:      cfg.KafkaBrokers,
			Topic:        cfg.KafkaTopic,
			Balancer:     &kafka.LeastBytes{},
			RequiredAcks: int(kafka.RequireOne),
			Async:        false,
			BatchTimeout: 5 * time.Millisecond,
		})
		producer = handler.NewKafkaWriterProducer(writer)
	}

	whMetrics := handler.NewWebhookMetrics(prometheus.DefaultRegisterer)
	whHandler, err := handler.NewWebhookHandler(
		buildVerifiers(cfg),
		dedupe,
		producer,
		cfg.KafkaTopic,
		logger,
		whMetrics,
		handler.WithMaxBodyBytes(cfg.MaxBodyBytes),
		handler.WithPublishTimeout(cfg.PublishTimeout),
		handler.WithStripeSignatureHeader(cfg.SignatureHeader),
		handler.WithReplayTolerance(cfg.SignatureTolerance),
		handler.WithTracer(otel.Tracer("payment-webhook-service")),
	)
	if err != nil {
		logger.Error("failed to initialize webhook handler", "error", err)
		os.Exit(1)
	}

	healthHandler := newHealthHandler(hasAnyVerifierSecret(cfg), writer != nil, redisClient)
	mux := newHTTPMux(whHandler, healthHandler)

	server := &http.Server{
		Addr:              ":" + cfg.Port,
		Handler:           otelhttp.NewHandler(correlation.Middleware(mux), "payment-webhook"),
		ReadHeaderTimeout: 5 * time.Second,
		ReadTimeout:       10 * time.Second,
		WriteTimeout:      10 * time.Second,
		IdleTimeout:       60 * time.Second,
	}

	errCh := make(chan error, 1)
	go func() {
		logger.Info("payment webhook service listening", "addr", server.Addr)
		if err := server.ListenAndServe(); err != nil && !errors.Is(err, http.ErrServerClosed) {
			errCh <- err
		}
		close(errCh)
	}()

	quit := make(chan os.Signal, 1)
	signal.Notify(quit, syscall.SIGINT, syscall.SIGTERM)
	select {
	case sig := <-quit:
		logger.Info("received signal, shutting down", "signal", sig.String())
	case err := <-errCh:
		if err != nil {
			logger.Error("server error", "error", err)
		}
	}
	signal.Stop(quit)

	health.ShutdownServer(server, healthHandler, cfg.ShutdownTimeout, logger)
	if writer != nil {
		if err := writer.Close(); err != nil {
			logger.Error("failed to close kafka writer", "error", err)
		}
	}
	logger.Info("shutdown complete")
}

func newHTTPMux(wh *handler.WebhookHandler, hh *health.Handler) *http.ServeMux {
	mux := http.NewServeMux()
	mux.HandleFunc("/payments/webhook", wh.HandleStripe)
	mux.HandleFunc("/webhooks/stripe", wh.HandleStripe)
	mux.HandleFunc("/webhooks/razorpay", wh.HandleRazorpay)
	mux.HandleFunc("/webhooks/phonepe", wh.HandlePhonePe)
	mux.Handle("/metrics", promhttp.Handler())
	hh.RegisterRoutes(mux)
	mux.HandleFunc("/ready", hh.ReadyHandler())
	return mux
}

func newHealthHandler(secretSet, kafkaConfigured bool, redisClient *redis.Client) *health.Handler {
	var checks []health.Check
	if !secretSet {
		checks = append(checks, health.Check{
			Name:  "secret",
			Check: func(context.Context) error { return errors.New("webhook secret not configured") },
		})
	}
	if !kafkaConfigured {
		checks = append(checks, health.Check{
			Name:  "kafka",
			Check: func(context.Context) error { return errors.New("kafka not configured") },
		})
	}
	if redisClient != nil {
		checks = append(checks, health.Check{
			Name:  "redis",
			Check: func(ctx context.Context) error { return redisClient.Ping(ctx).Err() },
		})
	}
	hh := health.NewHandler(checks...)
	hh.SetReady(secretSet && kafkaConfigured)
	return hh
}
