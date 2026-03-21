// Package config provides configuration loading for the reverse-etl-orchestrator.
package config

import (
	"os"
	"strconv"
)

// Config holds the service configuration.
type Config struct {
	// Server addresses
	APIAddr     string
	HealthAddr  string
	MetricsAddr string

	// Database
	DatabaseURL string

	// Kafka
	KafkaBrokers      string
	KafkaConsumerGroup string

	// Temporal
	TemporalHost      string
	TemporalNamespace string

	// Service metadata
	Version     string
	Environment string
	ServiceName string
}

// Load reads configuration from environment variables.
func Load() (*Config, error) {
	cfg := &Config{
		APIAddr:            getEnv("API_ADDR", ":8080"),
		HealthAddr:         getEnv("HEALTH_ADDR", ":8081"),
		MetricsAddr:        getEnv("METRICS_ADDR", ":9090"),
		DatabaseURL:        getEnv("DATABASE_URL", "postgres://localhost:5432/reverse_etl?sslmode=disable"),
		KafkaBrokers:       getEnv("KAFKA_BROKERS", "localhost:9092"),
		KafkaConsumerGroup: getEnv("KAFKA_CONSUMER_GROUP", "reverse-etl-orchestrator"),
		TemporalHost:       getEnv("TEMPORAL_HOST", "localhost:7233"),
		TemporalNamespace:  getEnv("TEMPORAL_NAMESPACE", "reverse-etl"),
		Version:            getEnv("SERVICE_VERSION", "1.0.0"),
		Environment:        getEnv("ENVIRONMENT", "development"),
		ServiceName:        "reverse-etl-orchestrator",
	}

	return cfg, nil
}

func getEnv(key, defaultValue string) string {
	if value := os.Getenv(key); value != "" {
		return value
	}
	return defaultValue
}

func getEnvInt(key string, defaultValue int) int {
	if value := os.Getenv(key); value != "" {
		if i, err := strconv.Atoi(value); err == nil {
			return i
		}
	}
	return defaultValue
}
