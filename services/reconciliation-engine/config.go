package main

import (
	"os"
	"strconv"
	"strings"
	"time"
)

const (
	defaultPort                  = "8107"
	defaultSchedule              = "5m"
	defaultKafkaTopic            = "reconciliation.events"
	defaultReconciliationTimeout = 2 * time.Minute
)

type Config struct {
	Port                  string
	ServiceName           string
	LogLevel              string
	Schedule              string
	RunOnStartup          bool
	ReconciliationTimeout time.Duration
	PSPExportPath         string
	LedgerPath            string
	LedgerOutputPath      string
	FixStatePath          string
	KafkaBrokers          []string
	KafkaTopic            string
	KafkaClientID         string
	CDCEnabled            bool
	CDCTopics             []string
	CDCGroupID            string
	CDCMinBytes           int
	CDCMaxBytes           int
	CDCMaxWait            time.Duration
	CDCCommitInterval     time.Duration
	CDCBatchSize          int
	CDCBatchTimeout       time.Duration
	DBHost                string
	DBPort                string
	DBUser                string
	DBPassword            string
	DBName                string
	DBMaxConnections      int
	DBConnTimeout         time.Duration
}

func loadConfig() Config {
	port := getenv("PORT", "")
	if port == "" {
		port = getenv("SERVER_PORT", defaultPort)
	}

	ledgerPath := getenv("LEDGER_PATH", "")
	outputPath := getenv("LEDGER_OUTPUT_PATH", "")
	if outputPath == "" {
		outputPath = ledgerPath
	}

	return Config{
		Port:                  port,
		ServiceName:           getenv("OTEL_SERVICE_NAME", "reconciliation-engine"),
		LogLevel:              getenv("LOG_LEVEL", "info"),
		Schedule:              getenv("RECONCILIATION_SCHEDULE", defaultSchedule),
		RunOnStartup:          getenvBool("RECONCILIATION_RUN_ON_STARTUP", true),
		ReconciliationTimeout: getenvDuration("RECONCILIATION_TIMEOUT", defaultReconciliationTimeout),
		PSPExportPath:         getenv("PSP_EXPORT_PATH", ""),
		LedgerPath:            ledgerPath,
		LedgerOutputPath:      outputPath,
		FixStatePath:          getenv("FIX_STATE_PATH", ""),
		KafkaBrokers:          splitCSV(getenv("KAFKA_BROKERS", "")),
		KafkaTopic:            getenv("KAFKA_TOPIC", defaultKafkaTopic),
		KafkaClientID:         getenv("KAFKA_CLIENT_ID", "reconciliation-engine"),
		CDCEnabled:            getenvBool("CDC_ENABLED", true),
		CDCTopics:             splitCSV(getenv("CDC_TOPICS", "payments.cdc,reconciliation.cdc")),
		CDCGroupID:            getenv("CDC_GROUP_ID", "reconciliation-cdc-consumer"),
		CDCMinBytes:           getenvInt("CDC_MIN_BYTES", 10*1024),
		CDCMaxBytes:           getenvInt("CDC_MAX_BYTES", 10*1024*1024),
		CDCMaxWait:            getenvDuration("CDC_MAX_WAIT", 5*time.Second),
		CDCCommitInterval:     getenvDuration("CDC_COMMIT_INTERVAL", 10*time.Second),
		CDCBatchSize:          getenvInt("CDC_BATCH_SIZE", 500),
		CDCBatchTimeout:       getenvDuration("CDC_BATCH_TIMEOUT", 5*time.Second),
		DBHost:                getenv("DB_HOST", "localhost"),
		DBPort:                getenv("DB_PORT", "5432"),
		DBUser:                getenv("DB_USER", "reconciliation"),
		DBPassword:            getenv("DB_PASSWORD", ""),
		DBName:                getenv("DB_NAME", "reconciliation"),
		DBMaxConnections:      getenvInt("DB_MAX_CONNECTIONS", 20),
		DBConnTimeout:         getenvDuration("DB_CONN_TIMEOUT", 10*time.Second),
	}
}

func getenv(key, fallback string) string {
	if value := strings.TrimSpace(os.Getenv(key)); value != "" {
		return value
	}
	return fallback
}

func getenvDuration(key string, fallback time.Duration) time.Duration {
	value := strings.TrimSpace(os.Getenv(key))
	if value == "" {
		return fallback
	}
	parsed, err := time.ParseDuration(value)
	if err != nil {
		return fallback
	}
	return parsed
}

func getenvBool(key string, fallback bool) bool {
	value := strings.TrimSpace(os.Getenv(key))
	if value == "" {
		return fallback
	}
	return strings.EqualFold(value, "true") || value == "1" || strings.EqualFold(value, "yes")
}

func getenvInt(key string, fallback int) int {
	value := strings.TrimSpace(os.Getenv(key))
	if value == "" {
		return fallback
	}
	parsed, err := strconv.Atoi(value)
	if err != nil {
		return fallback
	}
	return parsed
}

func splitCSV(value string) []string {
	if strings.TrimSpace(value) == "" {
		return nil
	}
	parts := strings.FieldsFunc(value, func(r rune) bool {
		return r == ',' || r == ';' || r == ' '
	})
	out := make([]string, 0, len(parts))
	for _, part := range parts {
		trimmed := strings.TrimSpace(part)
		if trimmed != "" {
			out = append(out, trimmed)
		}
	}
	return out
}
