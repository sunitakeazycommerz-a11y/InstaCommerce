package main

import (
	"errors"
	"fmt"
	"log/slog"
	"os"
	"strings"
	"time"

	"github.com/instacommerce/go-shared/pkg/config"
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

	cfg.Port = config.GetEnvTrimmed("PORT", config.GetEnvTrimmed("SERVER_PORT", "8104"))
	cfg.KafkaGroupID = config.GetEnvTrimmed("KAFKA_GROUP_ID", serviceName)
	cfg.KafkaDLQTopic = config.GetEnvTrimmed("KAFKA_DLQ_TOPIC", "cdc.dlq")
	cfg.KafkaAutoOffsetReset = strings.ToLower(config.GetEnvTrimmed("KAFKA_AUTO_OFFSET_RESET", "earliest"))

	if cfg.LogLevel, err = parseLogLevel(config.GetEnvTrimmed("LOG_LEVEL", "info")); err != nil {
		return cfg, err
	}

	brokers := strings.TrimSpace(os.Getenv("KAFKA_BROKERS"))
	if brokers == "" {
		return cfg, errors.New("KAFKA_BROKERS is required")
	}
	cfg.KafkaBrokers = config.SplitCSV(brokers)
	if len(cfg.KafkaBrokers) == 0 {
		return cfg, errors.New("KAFKA_BROKERS must contain at least one broker")
	}

	topics := strings.TrimSpace(os.Getenv("KAFKA_TOPICS"))
	if topics == "" {
		return cfg, errors.New("KAFKA_TOPICS is required")
	}
	cfg.KafkaTopics = config.SplitCSV(topics)
	if len(cfg.KafkaTopics) == 0 {
		return cfg, errors.New("KAFKA_TOPICS must contain at least one topic")
	}

	cfg.BQProject = strings.TrimSpace(os.Getenv("BQ_PROJECT"))
	cfg.BQDataset = strings.TrimSpace(os.Getenv("BQ_DATASET"))
	cfg.BQTable = strings.TrimSpace(os.Getenv("BQ_TABLE"))
	if cfg.BQProject == "" || cfg.BQDataset == "" || cfg.BQTable == "" {
		return cfg, errors.New("BQ_PROJECT, BQ_DATASET, and BQ_TABLE are required")
	}

	if cfg.KafkaMinBytes, err = config.GetEnvIntStrict("KAFKA_MIN_BYTES", 10*1024); err != nil {
		return cfg, err
	}
	if cfg.KafkaMaxBytes, err = config.GetEnvIntStrict("KAFKA_MAX_BYTES", 10*1024*1024); err != nil {
		return cfg, err
	}
	if cfg.KafkaMaxWait, err = config.GetEnvDurationStrict("KAFKA_MAX_WAIT", 5*time.Second); err != nil {
		return cfg, err
	}
	if cfg.KafkaCommitTimeout, err = config.GetEnvDurationStrict("KAFKA_COMMIT_TIMEOUT", 10*time.Second); err != nil {
		return cfg, err
	}
	if cfg.BatchSize, err = config.GetEnvIntStrict("BQ_BATCH_SIZE", 500); err != nil {
		return cfg, err
	}
	if cfg.BatchSize <= 0 {
		return cfg, errors.New("BQ_BATCH_SIZE must be greater than 0")
	}
	if cfg.BatchTimeout, err = config.GetEnvDurationStrict("BQ_BATCH_TIMEOUT", 5*time.Second); err != nil {
		return cfg, err
	}
	if cfg.BQInsertTimeout, err = config.GetEnvDurationStrict("BQ_INSERT_TIMEOUT", 30*time.Second); err != nil {
		return cfg, err
	}
	if cfg.BQMaxRetries, err = config.GetEnvIntStrict("BQ_MAX_RETRIES", 5); err != nil {
		return cfg, err
	}
	if cfg.BQBaseBackoff, err = config.GetEnvDurationStrict("BQ_BACKOFF_BASE", time.Second); err != nil {
		return cfg, err
	}
	if cfg.BQMaxBackoff, err = config.GetEnvDurationStrict("BQ_BACKOFF_MAX", 30*time.Second); err != nil {
		return cfg, err
	}
	if cfg.DLQWriteTimeout, err = config.GetEnvDurationStrict("DLQ_WRITE_TIMEOUT", 10*time.Second); err != nil {
		return cfg, err
	}
	return cfg, nil
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
