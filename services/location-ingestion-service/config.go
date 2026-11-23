package main

import (
	"context"
	"log/slog"
	"net/http"
	"os"
	"strings"
	"time"

	"github.com/instacommerce/go-shared/pkg/config"
	"github.com/instacommerce/go-shared/pkg/health"
	"github.com/instacommerce/go-shared/pkg/observability"
	sdktrace "go.opentelemetry.io/otel/sdk/trace"
)

const (
	defaultPort              = "8105"
	defaultKafkaTopic        = "rider.location.updates"
	defaultKafkaBatchSize    = 200
	defaultKafkaBatchTimeout = 1 * time.Second
	defaultKafkaWriteTimeout = 5 * time.Second
	defaultKafkaBuffer       = 2000
	defaultRedisAddr         = "localhost:6379"
	defaultRedisKeyPrefix    = "rider:location:"
	defaultRedisTTL          = 10 * time.Minute
	defaultMaxBodyBytes      = 1 << 20
	defaultWebSocketRead     = 1 << 20
	defaultShutdownTimeout   = 15 * time.Second
	defaultProcessTimeout    = 3 * time.Second
	kafkaProbeBrokerTimeout  = 2 * time.Second
)

type Config struct {
	Port              string
	ServiceName       string
	KafkaBrokers      []string
	KafkaTopic        string
	KafkaBatchSize    int
	KafkaBatchTimeout time.Duration
	KafkaWriteTimeout time.Duration
	KafkaBuffer       int
	RedisAddr         string
	RedisPassword     string
	RedisDB           int
	RedisKeyPrefix    string
	RedisTTL          time.Duration
	MaxBodyBytes      int64
	WebSocketReadMax  int64
	ShutdownTimeout   time.Duration
	ProcessTimeout    time.Duration
	H3Resolution      int
	LogLevel          slog.Level
}

func loadConfig() Config {
	port := config.GetEnv("PORT", config.GetEnv("SERVER_PORT", defaultPort))
	kafkaBatchSize := max(config.GetEnvInt("KAFKA_BATCH_SIZE", defaultKafkaBatchSize), 1)
	kafkaBuffer := max(config.GetEnvInt("KAFKA_BUFFER", defaultKafkaBuffer), 1)
	redisTTL := config.GetEnvDuration("REDIS_TTL", defaultRedisTTL)
	if redisTTL <= 0 {
		redisTTL = defaultRedisTTL
	}
	return Config{
		Port:              port,
		ServiceName:       "location-ingestion-service",
		KafkaBrokers:      config.GetEnvSlice("KAFKA_BROKERS", ",", nil),
		KafkaTopic:        config.GetEnv("KAFKA_TOPIC", defaultKafkaTopic),
		KafkaBatchSize:    kafkaBatchSize,
		KafkaBatchTimeout: config.GetEnvDuration("KAFKA_BATCH_TIMEOUT", defaultKafkaBatchTimeout),
		KafkaWriteTimeout: config.GetEnvDuration("KAFKA_WRITE_TIMEOUT", defaultKafkaWriteTimeout),
		KafkaBuffer:       kafkaBuffer,
		RedisAddr:         config.GetEnv("REDIS_ADDR", defaultRedisAddr),
		RedisPassword:     os.Getenv("REDIS_PASSWORD"),
		RedisDB:           config.GetEnvInt("REDIS_DB", 0),
		RedisKeyPrefix:    config.GetEnv("REDIS_KEY_PREFIX", defaultRedisKeyPrefix),
		RedisTTL:          redisTTL,
		MaxBodyBytes:      int64(config.GetEnvInt("MAX_BODY_BYTES", defaultMaxBodyBytes)),
		WebSocketReadMax:  int64(config.GetEnvInt("WS_READ_LIMIT", defaultWebSocketRead)),
		ShutdownTimeout:   config.GetEnvDuration("SHUTDOWN_TIMEOUT", defaultShutdownTimeout),
		ProcessTimeout:    config.GetEnvDuration("PROCESS_TIMEOUT", defaultProcessTimeout),
		H3Resolution:      config.GetEnvInt("H3_RESOLUTION", 0),
		LogLevel:          parseLogLevel(os.Getenv("LOG_LEVEL")),
	}
}

func parseLogLevel(raw string) slog.Level {
	switch strings.ToLower(strings.TrimSpace(raw)) {
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

func newLogger(cfg Config) *slog.Logger {
	h := slog.NewJSONHandler(os.Stdout, &slog.HandlerOptions{Level: cfg.LogLevel})
	logger := slog.New(h).With("service", cfg.ServiceName)
	slog.SetDefault(logger)
	return logger
}

func initTracing(ctx context.Context, cfg Config, logger *slog.Logger) *sdktrace.TracerProvider {
	providers, err := observability.InitOTel(ctx, observability.OTelConfig{
		ServiceName:      cfg.ServiceName,
		Protocol:         observability.OTelProtocolGRPC,
		ResolveEnv:       true,
		NoopIfNoEndpoint: true,
		FallbackNoop:     true,
	}, logger)
	if err != nil {
		logger.Warn("otel init failed, tracing disabled", "error", err)
		return nil
	}
	return providers.TracerProvider
}

func shutdownServer(server *http.Server, healthHandler *health.Handler, timeout time.Duration, logger *slog.Logger) {
	health.ShutdownServer(server, healthHandler, timeout, logger)
}

func checkKafka(ctx context.Context, brokers []string) error {
	return health.CheckTCPBrokers(ctx, brokers, kafkaProbeBrokerTimeout)
}

func checkKafkaWithDial(ctx context.Context, brokers []string, dial func(context.Context, string) error) error {
	return health.CheckTCPBrokersWithDial(ctx, brokers, kafkaProbeBrokerTimeout, dial)
}
