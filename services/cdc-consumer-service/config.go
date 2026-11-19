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

type Config struct {
	Port                 string
	LogLevel             slog.Level
	KafkaBrokers         []string
	KafkaGroupID         string
	KafkaTopics          []string
	KafkaDLQTopic        string
	KafkaAutoOffsetReset string
	KafkaMinBytes        int
	KafkaMaxBytes        int
	KafkaMaxWait         time.Duration
	KafkaCommitTimeout   time.Duration
	BatchSize            int
	BatchTimeout         time.Duration
	BQProject            string
	BQDataset            string
	BQTable              string
	BQInsertTimeout      time.Duration
	BQMaxRetries         int
	BQBaseBackoff        time.Duration
	BQMaxBackoff         time.Duration
	DLQWriteTimeout      time.Duration
}

func loadConfig() (Config, error) {
	var cfg Config
	var err error

	cfg.Port = getEnv("PORT", getEnv("SERVER_PORT", "8104"))
	cfg.KafkaGroupID = getEnv("KAFKA_GROUP_ID", serviceName)
	cfg.KafkaDLQTopic = getEnv("KAFKA_DLQ_TOPIC", "cdc.dlq")
	cfg.KafkaAutoOffsetReset = strings.ToLower(getEnv("KAFKA_AUTO_OFFSET_RESET", "earliest"))

	if cfg.LogLevel, err = parseLogLevel(getEnv("LOG_LEVEL", "info")); err != nil {
		return cfg, err
	}

	brokers := strings.TrimSpace(os.Getenv("KAFKA_BROKERS"))
	if brokers == "" {
		return cfg, errors.New("KAFKA_BROKERS is required")
	}
	cfg.KafkaBrokers = splitAndTrim(brokers)
	if len(cfg.KafkaBrokers) == 0 {
		return cfg, errors.New("KAFKA_BROKERS must contain at least one broker")
	}

	topics := strings.TrimSpace(os.Getenv("KAFKA_TOPICS"))
	if topics == "" {
		return cfg, errors.New("KAFKA_TOPICS is required")
	}
	cfg.KafkaTopics = splitAndTrim(topics)
	if len(cfg.KafkaTopics) == 0 {
		return cfg, errors.New("KAFKA_TOPICS must contain at least one topic")
	}

	cfg.BQProject = strings.TrimSpace(os.Getenv("BQ_PROJECT"))
	cfg.BQDataset = strings.TrimSpace(os.Getenv("BQ_DATASET"))
	cfg.BQTable = strings.TrimSpace(os.Getenv("BQ_TABLE"))
	if cfg.BQProject == "" || cfg.BQDataset == "" || cfg.BQTable == "" {
		return cfg, errors.New("BQ_PROJECT, BQ_DATASET, and BQ_TABLE are required")
	}

	if cfg.KafkaMinBytes, err = getEnvInt("KAFKA_MIN_BYTES", 10*1024); err != nil {
		return cfg, err
	}
	if cfg.KafkaMaxBytes, err = getEnvInt("KAFKA_MAX_BYTES", 10*1024*1024); err != nil {
		return cfg, err
	}
	if cfg.KafkaMaxWait, err = getEnvDuration("KAFKA_MAX_WAIT", 5*time.Second); err != nil {
		return cfg, err
	}
	if cfg.KafkaCommitTimeout, err = getEnvDuration("KAFKA_COMMIT_TIMEOUT", 10*time.Second); err != nil {
		return cfg, err
	}
	if cfg.BatchSize, err = getEnvInt("BQ_BATCH_SIZE", 500); err != nil {
		return cfg, err
	}
	if cfg.BatchSize <= 0 {
		return cfg, errors.New("BQ_BATCH_SIZE must be greater than 0")
	}
	if cfg.BatchTimeout, err = getEnvDuration("BQ_BATCH_TIMEOUT", 5*time.Second); err != nil {
		return cfg, err
	}
	if cfg.BQInsertTimeout, err = getEnvDuration("BQ_INSERT_TIMEOUT", 30*time.Second); err != nil {
		return cfg, err
	}
	if cfg.BQMaxRetries, err = getEnvInt("BQ_MAX_RETRIES", 5); err != nil {
		return cfg, err
	}
	if cfg.BQBaseBackoff, err = getEnvDuration("BQ_BACKOFF_BASE", time.Second); err != nil {
		return cfg, err
	}
	if cfg.BQMaxBackoff, err = getEnvDuration("BQ_BACKOFF_MAX", 30*time.Second); err != nil {
		return cfg, err
	}
	if cfg.DLQWriteTimeout, err = getEnvDuration("DLQ_WRITE_TIMEOUT", 10*time.Second); err != nil {
		return cfg, err
	}
	return cfg, nil
}

func getEnv(key, fallback string) string {
	if value := strings.TrimSpace(os.Getenv(key)); value != "" {
		return value
	}
	return fallback
}

func parseLogLevel(value string) (slog.Level, error) {
	switch strings.ToLower(value) {
	case "debug":
		return slog.LevelDebug, nil
	case "info":
		return slog.LevelInfo, nil
	case "warn", "warning":
		return slog.LevelWarn, nil
	case "error":
		return slog.LevelError, nil
	default:
		return slog.LevelInfo, fmt.Errorf("invalid log level %q", value)
	}
}

func getEnvDuration(key string, fallback time.Duration) (time.Duration, error) {
	value := strings.TrimSpace(os.Getenv(key))
	if value == "" {
		return fallback, nil
	}
	parsed, err := time.ParseDuration(value)
	if err != nil {
		return 0, fmt.Errorf("invalid duration for %s: %w", key, err)
	}
	return parsed, nil
}

func getEnvInt(key string, fallback int) (int, error) {
	value := strings.TrimSpace(os.Getenv(key))
	if value == "" {
		return fallback, nil
	}
	parsed, err := strconv.Atoi(value)
	if err != nil {
		return 0, fmt.Errorf("invalid int for %s: %w", key, err)
	}
	return parsed, nil
}

func splitAndTrim(value string) []string {
	parts := strings.Split(value, ",")
	result := make([]string, 0, len(parts))
	for _, part := range parts {
		part = strings.TrimSpace(part)
		if part != "" {
			result = append(result, part)
		}
	}
	return result
}
