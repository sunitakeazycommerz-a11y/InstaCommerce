# Go Shared Platform Library (`go-shared`)

Authoritative architectural reference for the `services/go-shared` platform library.

---

## 1. Overview & Boundaries

`go-shared` is a pure Go leaf library imported by all **8 Go microservices** across InstaCommerce (`cdc-consumer-service`, `dispatch-optimizer-service`, `location-ingestion-service`, `outbox-relay-service`, `payment-webhook-service`, `reconciliation-engine`, `reverse-etl-orchestrator`, `stream-processor-service`).

- **Module:** `github.com/instacommerce/go-shared`
- **Go Version:** 1.25.0 (`toolchain go1.25.1`)
- **Location:** `services/go-shared/`

---

## 2. Package Map

| Package | Purpose | Key Types / Functions |
|---|---|---|
| `pkg/auth` | Service-to-service internal auth | `InternalTokenValidator`, `GenerateInternalToken`, HTTP middleware |
| `pkg/config` | Environment variable parsing | `GetStringOrDefault`, `GetIntOrDefault`, `GetDurationOrDefault` |
| `pkg/correlation` | OpenTelemetry distributed tracing correlation | `CorrelationMiddleware`, `FromContext`, `WithCorrelationID` |
| `pkg/health` | Kubernetes readiness/liveness handlers | `HealthHandler`, `CheckFunc`, `/health/ready`, `/health/live` |
| `pkg/httpclient` | Resilient HTTP client | Circuit breaker, exponential backoff, connection pooling |
| `pkg/kafka` | Kafka consumer & producer wrappers | `NewConsumerGroup`, `NewSyncProducer`, batching, signal handling |
| `pkg/observability` | OTel & Prometheus bootstrap | Tracer provider, meter provider, slog structured logging |
| `pkg/proto` | Generated Protobuf & gRPC stubs | 10 domain proto packages compiled from `contracts/` |

---

## 3. Testing

All packages maintain unit test suites:
- `pkg/auth/middleware_test.go`
- `pkg/correlation/middleware_test.go`
- `pkg/health/handler_test.go`
- `pkg/httpclient/client_test.go`
- `pkg/kafka/consumer_test.go`

Run tests via:
```bash
cd services/go-shared && go test -race ./...
```
