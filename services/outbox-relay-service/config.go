package main

import (
	"errors"
	"fmt"
	"log/slog"
	"os"
	"strconv"
	"strings"
	"time"
)

const (
	defaultPort          = "8103"
	defaultPollInterval  = 1 * time.Second
	defaultBatchSize     = 100
	defaultShutdownGrace = 20 * time.Second
	defaultReadyTimeout  = 2 * time.Second
)

type Config struct {
	Port            string
	DatabaseURL     string
	PollInterval    time.Duration
	BatchSize       int
	OutboxTable     string
	KafkaBrokers    []string
	KafkaTopic      string
	KafkaClientID   string
	DLQTopic        string
	ShutdownTimeout time.Duration
	ReadyTimeout    time.Duration
	OTelServiceName string
	OTLPEndpoint    string
	LogLevel        slog.Level
}

func loadConfig() (Config, error) {
	port := envOrDefault("PORT", envOrDefault("SERVER_PORT", defaultPort))
	databaseURL := envOrDefault("DATABASE_URL", envOrDefault("POSTGRES_DSN", ""))
	if databaseURL == "" {
		return Config{}, errors.New("DATABASE_URL or POSTGRES_DSN is required")
	}

	pollInterval, err := durationEnv("OUTBOX_POLL_INTERVAL", defaultPollInterval)
	if err != nil {
		return Config{}, err
	}

	batchSize, err := intEnv("OUTBOX_BATCH_SIZE", defaultBatchSize)
	if err != nil {
		return Config{}, err
	}
	if batchSize <= 0 {
		return Config{}, errors.New("OUTBOX_BATCH_SIZE must be greater than 0")
	}

	outboxTable := envOrDefault("OUTBOX_TABLE", "outbox_events")
	if !isSafeIdentifier(outboxTable) {
		return Config{}, fmt.Errorf("OUTBOX_TABLE %q contains invalid characters", outboxTable)
	}

	brokerList := envOrDefault("KAFKA_BROKERS", "")
	if brokerList == "" {
		return Config{}, errors.New("KAFKA_BROKERS is required")
	}
	brokers := splitAndTrim(brokerList)
	if len(brokers) == 0 {
		return Config{}, errors.New("KAFKA_BROKERS must include at least one broker")
	}

	shutdownTimeout, err := durationEnv("SHUTDOWN_TIMEOUT", defaultShutdownGrace)
	if err != nil {
		return Config{}, err
	}

	readyTimeout, err := durationEnv("READY_TIMEOUT", defaultReadyTimeout)
	if err != nil {
		return Config{}, err
	}

	return Config{
		Port:            port,
		DatabaseURL:     databaseURL,
		PollInterval:    pollInterval,
		BatchSize:       batchSize,
		OutboxTable:     outboxTable,
		KafkaBrokers:    brokers,
		KafkaTopic:      envOrDefault("OUTBOX_TOPIC", ""),
		KafkaClientID:   envOrDefault("KAFKA_CLIENT_ID", "outbox-relay-service"),
		DLQTopic:        envOrDefault("DLQ_TOPIC", "outbox.relay.dlq"),
		ShutdownTimeout: shutdownTimeout,
		ReadyTimeout:    readyTimeout,
		OTelServiceName: envOrDefault("OTEL_SERVICE_NAME", "outbox-relay-service"),
		OTLPEndpoint:    envOrDefault("OTEL_EXPORTER_OTLP_ENDPOINT", ""),
		LogLevel:        parseLogLevel(envOrDefault("LOG_LEVEL", "info")),
	}, nil
}

func envOrDefault(key, fallback string) string {
	if value := strings.TrimSpace(os.Getenv(key)); value != "" {
		return value
	}
	return fallback
}

func intEnv(key string, fallback int) (int, error) {
	raw := strings.TrimSpace(os.Getenv(key))
	if raw == "" {
		return fallback, nil
	}
	val, err := strconv.Atoi(raw)
	if err != nil {
		return 0, fmt.Errorf("%s must be an integer", key)
	}
	return val, nil
}

func durationEnv(key string, fallback time.Duration) (time.Duration, error) {
	raw := strings.TrimSpace(os.Getenv(key))
	if raw == "" {
		return fallback, nil
	}
	val, err := time.ParseDuration(raw)
	if err != nil {
		return 0, fmt.Errorf("%s must be a duration", key)
	}
	return val, nil
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

func splitAndTrim(raw string) []string {
	parts := strings.Split(raw, ",")
	out := make([]string, 0, len(parts))
	for _, part := range parts {
		if trimmed := strings.TrimSpace(part); trimmed != "" {
			out = append(out, trimmed)
		}
	}
	return out
}

func isSafeIdentifier(value string) bool {
	if value == "" {
		return false
	}
	for _, r := range value {
		switch {
		case r >= 'a' && r <= 'z':
		case r >= 'A' && r <= 'Z':
		case r >= '0' && r <= '9':
		case r == '_':
		default:
			return false
		}
	}
	return true
}
