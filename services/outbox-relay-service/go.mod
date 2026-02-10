module github.com/instacommerce/outbox-relay-service

go 1.22

require (
	github.com/IBM/sarama v1.43.2
	github.com/jackc/pgx/v5 v5.5.5
	go.opentelemetry.io/otel v1.28.0
	go.opentelemetry.io/otel/exporters/otlp/otlpmetric/otlpmetrichttp v1.28.0
	go.opentelemetry.io/otel/exporters/otlp/otlptrace/otlptracehttp v1.28.0
	go.opentelemetry.io/otel/sdk v1.28.0
)
