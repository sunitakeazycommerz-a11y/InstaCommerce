module github.com/instacommerce/location-ingestion-service

go 1.22

require (
	github.com/gorilla/websocket v1.5.1
	github.com/prometheus/client_golang v1.19.0
	github.com/redis/go-redis/v9 v9.5.1
	github.com/segmentio/kafka-go v0.4.47
	go.opentelemetry.io/contrib/instrumentation/net/http/otelhttp v0.50.0
	go.opentelemetry.io/otel v1.25.0
	go.opentelemetry.io/otel/exporters/otlp/otlptrace/otlptracegrpc v1.25.0
	go.opentelemetry.io/otel/semconv/v1.25.0 v1.25.0
	go.opentelemetry.io/otel/sdk v1.25.0
)
