package main

import (
	"context"
	"log/slog"
	"os"
	"strings"
	"time"

	"github.com/instacommerce/go-shared/pkg/config"
	"github.com/instacommerce/payment-webhook-service/handler"
	"go.opentelemetry.io/otel"
	"go.opentelemetry.io/otel/attribute"
	"go.opentelemetry.io/otel/exporters/otlp/otlptrace/otlptracehttp"
	"go.opentelemetry.io/otel/propagation"
	"go.opentelemetry.io/otel/sdk/resource"
	sdktrace "go.opentelemetry.io/otel/sdk/trace"
	semconv "go.opentelemetry.io/otel/semconv/v1.24.0"
	"go.opentelemetry.io/otel/trace"
)

const (
	defaultPort               = "8106"
	defaultSignatureHeader    = "Stripe-Signature"
	defaultSignatureTolerance = 300
	defaultMaxBodyBytes       = 1 << 20
	defaultDedupeTTLSeconds   = 86400
	defaultCleanupSeconds     = 60
	defaultPublishTimeoutMS   = 5000
	defaultShutdownSeconds    = 15
)

type Config struct {
	Port               string
	WebhookSecret      string
	RazorpaySecret     string
	PhonePeSaltKey     string
	PhonePeSaltIndex   string
	SignatureHeader    string
	SignatureTolerance time.Duration
	MaxBodyBytes       int64
	DedupeTTL          time.Duration
	DedupeCleanup      time.Duration
	RedisAddr          string
	RedisPassword      string
	RedisDB            int
	RedisTimeout       time.Duration
	KafkaBrokers       []string
	KafkaTopic         string
	PublishTimeout     time.Duration
	ShutdownTimeout    time.Duration
}

func loadConfig() Config {
	port := config.GetEnv("PORT", config.GetEnv("SERVER_PORT", defaultPort))
	secret := config.GetEnv("WEBHOOK_SECRET", config.GetEnv("STRIPE_WEBHOOK_SECRET", ""))
	brokers := strings.FieldsFunc(os.Getenv("KAFKA_BROKERS"), func(r rune) bool {
		return r == ',' || r == ' ' || r == ';'
	})
	return Config{
		Port:               port,
		WebhookSecret:      strings.TrimSpace(secret),
		RazorpaySecret:     strings.TrimSpace(os.Getenv("RAZORPAY_WEBHOOK_SECRET")),
		PhonePeSaltKey:     strings.TrimSpace(os.Getenv("PHONEPE_SALT_KEY")),
		PhonePeSaltIndex:   strings.TrimSpace(os.Getenv("PHONEPE_SALT_INDEX")),
		SignatureHeader:    config.GetEnv("WEBHOOK_SIGNATURE_HEADER", defaultSignatureHeader),
		SignatureTolerance: time.Duration(config.GetEnvInt("WEBHOOK_TOLERANCE_SECONDS", defaultSignatureTolerance)) * time.Second,
		MaxBodyBytes:       int64(config.GetEnvInt("MAX_BODY_BYTES", defaultMaxBodyBytes)),
		DedupeTTL:          time.Duration(config.GetEnvInt("DEDUPE_TTL_SECONDS", defaultDedupeTTLSeconds)) * time.Second,
		DedupeCleanup:      time.Duration(config.GetEnvInt("DEDUPE_CLEANUP_SECONDS", defaultCleanupSeconds)) * time.Second,
		RedisAddr:          strings.TrimSpace(os.Getenv("REDIS_ADDR")),
		RedisPassword:      strings.TrimSpace(os.Getenv("REDIS_PASSWORD")),
		RedisDB:            config.GetEnvInt("REDIS_DB", 0),
		RedisTimeout:       time.Duration(config.GetEnvInt("REDIS_TIMEOUT_MS", 50)) * time.Millisecond,
		KafkaBrokers:       brokers,
		KafkaTopic:         config.GetEnv("KAFKA_TOPIC", "payment.webhooks"),
		PublishTimeout:     time.Duration(config.GetEnvInt("PUBLISH_TIMEOUT_MS", defaultPublishTimeoutMS)) * time.Millisecond,
		ShutdownTimeout:    time.Duration(config.GetEnvInt("SHUTDOWN_TIMEOUT_SECONDS", defaultShutdownSeconds)) * time.Second,
	}
}

func buildVerifiers(cfg Config) map[handler.PSPType]handler.SignatureVerifier {
	verifiers := map[handler.PSPType]handler.SignatureVerifier{
		handler.Stripe:   &handler.UnconfiguredVerifier{Reason: "stripe webhook secret not configured"},
		handler.Razorpay: &handler.UnconfiguredVerifier{Reason: "razorpay webhook secret not configured"},
		handler.PhonePe:  &handler.UnconfiguredVerifier{Reason: "phonepe salt not configured"},
	}
	if v, err := handler.NewStripeVerifier(cfg.WebhookSecret, cfg.SignatureTolerance); err == nil {
		verifiers[handler.Stripe] = v
	}
	if v, err := handler.NewRazorpayVerifier(cfg.RazorpaySecret); err == nil {
		verifiers[handler.Razorpay] = v
	}
	if v, err := handler.NewPhonePeVerifier(cfg.PhonePeSaltKey, cfg.PhonePeSaltIndex); err == nil {
		verifiers[handler.PhonePe] = v
	}
	return verifiers
}

func initTracing(cfg Config, logger *slog.Logger) (func(context.Context) error, error) {
	endpoint := strings.TrimSpace(os.Getenv("OTEL_EXPORTER_OTLP_TRACES_ENDPOINT"))
	if endpoint == "" {
		endpoint = strings.TrimSpace(os.Getenv("OTEL_EXPORTER_OTLP_ENDPOINT"))
	}
	if endpoint == "" {
		otel.SetTracerProvider(trace.NewNoopTracerProvider())
		return func(context.Context) error { return nil }, nil
	}
	var opts []otlptracehttp.Option
	if strings.HasPrefix(endpoint, "http://") || strings.HasPrefix(endpoint, "https://") {
		opts = append(opts, otlptracehttp.WithEndpointURL(endpoint))
	} else {
		opts = append(opts, otlptracehttp.WithEndpoint(endpoint), otlptracehttp.WithInsecure())
	}
	exporter, err := otlptracehttp.New(context.Background(), opts...)
	if err != nil {
		return func(context.Context) error { return nil }, err
	}
	res, err := resource.New(context.Background(), resource.WithAttributes(
		semconv.ServiceName("payment-webhook-service"),
		attribute.String("service.port", cfg.Port),
	))
	if err != nil {
		return func(context.Context) error { return nil }, err
	}
	tp := sdktrace.NewTracerProvider(sdktrace.WithResource(res), sdktrace.WithBatcher(exporter))
	otel.SetTracerProvider(tp)
	otel.SetTextMapPropagator(propagation.NewCompositeTextMapPropagator(propagation.TraceContext{}, propagation.Baggage{}))
	logger.Info("otel tracing enabled")
	return tp.Shutdown, nil
}
