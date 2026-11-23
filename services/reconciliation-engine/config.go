package main

import (
	"time"

	sharedconfig "github.com/instacommerce/go-shared/pkg/config"
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
	KafkaBrokers          []string
	KafkaTopic            string
	KafkaClientID         string
	CDCEnabled            bool
	CDCTopics             []string
	CDCDLTTopic           string
	CDCGroupID            string
	CDCMaxRetries         int
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
	port := sharedconfig.GetEnvTrimmed("PORT", "")
	if port == "" {
		port = sharedconfig.GetEnvTrimmed("SERVER_PORT", defaultPort)
	}

	return Config{
		Port:                  port,
		ServiceName:           sharedconfig.GetEnvTrimmed("OTEL_SERVICE_NAME", "reconciliation-engine"),
		LogLevel:              sharedconfig.GetEnvTrimmed("LOG_LEVEL", "info"),
		Schedule:              sharedconfig.GetEnvTrimmed("RECONCILIATION_SCHEDULE", defaultSchedule),
		RunOnStartup:          sharedconfig.GetEnvBool("RECONCILIATION_RUN_ON_STARTUP", true),
		ReconciliationTimeout: sharedconfig.GetEnvDuration("RECONCILIATION_TIMEOUT", defaultReconciliationTimeout),
		KafkaBrokers:          sharedconfig.SplitCSV(sharedconfig.GetEnvTrimmed("KAFKA_BROKERS", "")),
		KafkaTopic:            sharedconfig.GetEnvTrimmed("KAFKA_TOPIC", defaultKafkaTopic),
		KafkaClientID:         sharedconfig.GetEnvTrimmed("KAFKA_CLIENT_ID", "reconciliation-engine"),
		CDCEnabled:            sharedconfig.GetEnvBool("CDC_ENABLED", true),
		CDCTopics:             sharedconfig.SplitCSV(sharedconfig.GetEnvTrimmed("CDC_TOPICS", "payments.cdc,reconciliation.cdc")),
		CDCDLTTopic:           sharedconfig.GetEnvTrimmed("CDC_DLT_TOPIC", "reconciliation.events.DLT"),
		CDCGroupID:            sharedconfig.GetEnvTrimmed("CDC_GROUP_ID", "reconciliation-cdc-consumer"),
		CDCMaxRetries:         sharedconfig.GetEnvInt("CDC_MAX_RETRIES", 3),
		CDCMinBytes:           sharedconfig.GetEnvInt("CDC_MIN_BYTES", 10*1024),
		CDCMaxBytes:           sharedconfig.GetEnvInt("CDC_MAX_BYTES", 10*1024*1024),
		CDCMaxWait:            sharedconfig.GetEnvDuration("CDC_MAX_WAIT", 5*time.Second),
		CDCCommitInterval:     sharedconfig.GetEnvDuration("CDC_COMMIT_INTERVAL", 10*time.Second),
		CDCBatchSize:          sharedconfig.GetEnvInt("CDC_BATCH_SIZE", 500),
		CDCBatchTimeout:       sharedconfig.GetEnvDuration("CDC_BATCH_TIMEOUT", 5*time.Second),
		DBHost:                sharedconfig.GetEnvTrimmed("DB_HOST", "localhost"),
		DBPort:                sharedconfig.GetEnvTrimmed("DB_PORT", "5432"),
		DBUser:                sharedconfig.GetEnvTrimmed("DB_USER", "postgres"),
		DBPassword:            sharedconfig.GetEnvTrimmed("DB_PASSWORD", "devpass"),
		DBName:                sharedconfig.GetEnvTrimmed("DB_NAME", "reconciliation"),
		DBMaxConnections:      sharedconfig.GetEnvInt("DB_MAX_CONNECTIONS", 20),
		DBConnTimeout:         sharedconfig.GetEnvDuration("DB_CONN_TIMEOUT", 10*time.Second),
	}
}
