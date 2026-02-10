module github.com/instacommerce/cdc-consumer-service

go 1.22

require (
	cloud.google.com/go/bigquery v1.63.1
	github.com/prometheus/client_golang v1.19.1
	github.com/segmentio/kafka-go v0.4.47
	go.opentelemetry.io/otel v1.28.0
	go.opentelemetry.io/otel/exporters/otlp/otlptrace/otlptracegrpc v1.28.0
	go.opentelemetry.io/otel/sdk v1.28.0
	google.golang.org/api v0.193.0
)
