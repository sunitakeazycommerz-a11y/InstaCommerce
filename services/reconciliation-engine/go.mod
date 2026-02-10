module github.com/instacommerce/reconciliation-engine

go 1.22

require (
	github.com/prometheus/client_golang v1.19.0
	github.com/robfig/cron/v3 v3.0.1
	github.com/segmentio/kafka-go v0.4.47
	go.opentelemetry.io/otel v1.24.0
	go.opentelemetry.io/otel/exporters/otlp/otlptrace v1.24.0
	go.opentelemetry.io/otel/exporters/stdout/stdouttrace v1.24.0
	go.opentelemetry.io/otel/sdk v1.24.0
)
