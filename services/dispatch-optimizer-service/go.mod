module github.com/instacommerce/dispatch-optimizer-service

go 1.22

require (
	github.com/prometheus/client_golang v1.19.0
	go.opentelemetry.io/contrib/instrumentation/net/http/otelhttp v0.50.0
	go.opentelemetry.io/otel v1.24.0
	go.opentelemetry.io/otel/exporters/otlp/otlptrace/otlptracegrpc v1.24.0
	go.opentelemetry.io/otel/sdk v1.24.0
)
