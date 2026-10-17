---
name: monorepo-conventions
description: Authoritative conventions for package structures, REST APIs, error responses, Kafka events, and observability.
---

# Monorepo Conventions Skill

Use this skill when designing APIs, structuring new packages, implementing error handling, publishing Kafka events, or adding metrics and structured logging.

---

## 1. Service Layout Conventions

### Java / Spring Boot 4
```
services/<service-name>/
├── build.gradle.kts
├── Dockerfile
├── README.md
└── src/
    ├── main/
    │   ├── java/com/instacommerce/<domain>/
    │   │   ├── api/          # REST controllers, DTOs, request validators
    │   │   ├── domain/       # Aggregates, domain entities, value objects, exceptions
    │   │   ├── service/      # Application services, transactional use cases
    │   │   ├── repository/   # Spring Data JPA repositories
    │   │   └── config/       # Security, Kafka, Redis, Actuator configuration
    │   └── resources/
    │       ├── application.yml
    │       └── db/migration/ # Flyway migrations: V1__initial.sql, V2__outbox.sql
    └── test/                 # Pure unit tests and @SpringBootTest Testcontainers
```

### Go Services
```
services/<service-name>/
├── go.mod
├── go.sum
├── Dockerfile
├── README.md
├── cmd/server/main.go        # Server entrypoint and graceful shutdown
├── api/                      # HTTP router, request/response handlers
├── internal/                 # Private package logic
└── pkg/                      # Reusable domain packages
```

### Python / FastAPI Services
```
services/<service-name>/
├── requirements.txt
├── Dockerfile
├── README.md
├── app/
│   ├── main.py               # FastAPI application entrypoint
│   ├── config.py             # Pydantic Settings
│   ├── auth.py               # Token verification middleware
│   └── api/                  # Route handlers
└── tests/                    # pytest suites
```

---

## 2. API Contract & Error Standard (RFC 7807)

All microservices must return standard RFC 7807 `ProblemDetail` JSON payloads for HTTP 4xx and 5xx errors:

```json
{
  "type": "https://api.instacommerce.dev/errors/OUT_OF_STOCK",
  "title": "Insufficient Inventory",
  "status": 409,
  "detail": "SKU item-9872 has 0 available units in darkstore-sf-1",
  "instance": "/api/v1/orders",
  "timestamp": "2026-10-12T08:00:00Z",
  "correlation_id": "req-abcdef12345"
}
```

---

## 3. Kafka Event Envelope Standard

All asynchronous domain events published to Kafka must wrap their domain payload inside `EventEnvelope.v1.json`:

```json
{
  "eventId": "c8a411e7-20b8-4d5c-897f-1d42a67e1a90",
  "eventType": "OrderPlaced",
  "aggregateType": "Order",
  "aggregate_id": "ord-88319",
  "event_time": "2026-10-12T08:00:00Z",
  "schema_version": "1.0.0",
  "source_service": "order-service",
  "correlation_id": "trace-91823-abc",
  "payload": {
    "order_id": "ord-88319",
    "customer_id": "cust-4412",
    "total_amount_cents": 2499,
    "store_id": "store-sf-1"
  }
}
```

---

## 4. Observability & Telemetry Standards

1. **Structured Logs**:
   - Logs must be formatted as JSON in production.
   - Must include standard fields: `timestamp`, `level`, `service_name`, `trace_id`, `span_id`, `message`.
2. **Standard HTTP Metrics**:
   - `http_server_requests_seconds_count` (Java) / `http_requests_total` (Go/Python).
   - `http_server_requests_seconds_bucket` (Java) / `http_request_duration_seconds_bucket` (Go/Python).
3. **Health Check Probes**:
   - Liveness: Indicates process is alive.
   - Readiness: Checks database, Kafka, and Redis connectivity before receiving traffic.

