# Outbox Relay Service

> **Go · Transactional Outbox Pattern · At-Least-Once Kafka Delivery**

Single-binary Go service that polls PostgreSQL `outbox_events` tables and reliably forwards domain events to Apache Kafka. It is the **authoritative event publisher** for all service-to-service domain events in InstaCommerce. The relay runs as a sidecar-style process per database, not per service — any number of Java producer services can share the same outbox table and relay instance.

**Key invariant:** The database is the synchronous truth; Kafka is the asynchronous projection. No service in the platform publishes directly to Kafka from business logic. Every domain state change is first written to an `outbox_events` row inside the same PostgreSQL transaction that mutates the domain aggregate. This relay then polls that table and forwards events to Kafka after the fact.

**Delivery guarantee:** At-least-once. The idempotent Kafka producer (`PID + sequence`) prevents duplicates within a single producer session, but duplicates are possible across relay restarts (see [Failure Modes](#failure-modes--recovery)).

---

## Table of Contents

1. [Service Role & Boundaries](#service-role--boundaries)
2. [High-Level Design](#high-level-design)
3. [Low-Level Design](#low-level-design)
4. [Outbox Relay Flow](#outbox-relay-flow)
5. [Event Publishing & Envelope](#event-publishing--envelope)
6. [Configuration](#configuration)
7. [Database Schema](#database-schema)
8. [Dependencies](#dependencies)
9. [Observability](#observability)
10. [Health & Readiness](#health--readiness)
11. [Testing](#testing)
12. [Failure Modes & Recovery](#failure-modes--recovery)
13. [Rollout & Rollback](#rollout--rollback)
14. [Known Limitations](#known-limitations)
15. [Build & Run](#build--run)
16. [Project Structure](#project-structure)
17. [Q-Commerce Event Delivery Comparison](#q-commerce-event-delivery-comparison)

---

## Service Role & Boundaries

| Attribute | Value |
|---|---|
| **Language / Runtime** | Go 1.24 (single binary, `main.go`) |
| **Port** | `8103` (configurable via `PORT` / `SERVER_PORT`) |
| **Owns** | Outbox-to-Kafka relay loop, canonical event envelope construction, aggregate→topic routing |
| **Does not own** | Outbox table writes (owned by 13 Java producer services), Debezium CDC path, consumer-side processing, DLQ management |
| **Upstream** | 13 Java/Spring Boot services writing `outbox_events` rows via `@Transactional(propagation = MANDATORY)` |
| **Downstream** | Kafka domain topics → fulfillment, notification, search, pricing, fraud, wallet-loyalty, audit-trail, rider-fleet, routing-eta, stream-processor |

The relay is intentionally **stateless** — it holds no persistent state beyond the PostgreSQL connection pool and the in-flight Kafka producer session. All durable state lives in the `outbox_events` table. This means relay instances can be restarted, scaled, or replaced without coordination.

### Two Parallel Data Paths

| Path | Source | Sink | Purpose |
|---|---|---|---|
| **Outbox relay** (this service) | `outbox_events` table (poll) | Domain Kafka topics (`orders.events`, etc.) | Authoritative domain events for service-to-service consumption |
| **Debezium CDC** (separate) | PostgreSQL WAL (logical decoding) | CDC Kafka topics (`db.public.table`) | Raw table change capture for BigQuery analytics |

Both paths run in parallel. This service is **not** part of the CDC/analytics pipeline.

---

## High-Level Design

```mermaid
graph TB
    subgraph Producers["Producer Layer — 13 Java/Spring Boot Services"]
        OS["order-service"]
        PS["payment-service"]
        IS["inventory-service"]
        FS["fulfillment-service"]
        MORE["catalog · identity · rider-fleet<br/>warehouse · pricing · wallet-loyalty<br/>fraud · notification · config"]
    end

    subgraph DB["PostgreSQL (per-service database)"]
        OT[("outbox_events<br/>sent = false")]
    end

    subgraph Relay["Outbox Relay Service (Go :8103)"]
        PL["Poll Loop<br/>1s interval · batch 100<br/>SELECT … FOR UPDATE SKIP LOCKED"]
        KP["Sarama SyncProducer<br/>Idempotent · WaitForAll"]
    end

    subgraph Kafka["Apache Kafka"]
        DT["Domain Topics<br/>orders.events · payments.events<br/>inventory.events · fulfillment.events<br/>catalog.events · identity.events<br/>rider.events · warehouse.events<br/>pricing.events · wallet.events<br/>fraud.events · notification.events"]
    end

    subgraph Consumers["Downstream Consumers"]
        SC["fulfillment · notification · search<br/>pricing · fraud · wallet-loyalty<br/>audit-trail · rider-fleet · routing-eta<br/>stream-processor"]
    end

    subgraph Observability["Observability"]
        OTEL["OpenTelemetry<br/>Traces + Metrics<br/>(OTLP HTTP)"]
        HP["Health + Ready<br/>Endpoints"]
    end

    OS & PS & IS & FS & MORE -->|"@Transactional<br/>INSERT outbox_events"| OT
    OT -->|"Poll every 1s"| PL
    PL --> KP
    KP -->|"Produce"| DT
    DT -->|"Consume"| SC
    PL & KP --> OTEL
    Relay --> HP
```

---

## Low-Level Design

### Component Diagram

```mermaid
classDiagram
    class Config {
        +Port string
        +DatabaseURL string
        +PollInterval time.Duration
        +BatchSize int
        +OutboxTable string
        +KafkaBrokers []string
        +KafkaTopic string
        +KafkaClientID string
        +ShutdownTimeout time.Duration
        +ReadyTimeout time.Duration
        +OTelServiceName string
        +OTLPEndpoint string
        +LogLevel slog.Level
    }

    class relayService {
        -cfg Config
        -logger *slog.Logger
        -db *pgxpool.Pool
        -producer sarama.SyncProducer
        -kafkaConfig *sarama.Config
        -tracer trace.Tracer
        -metrics relayMetrics
        -selectSQL string
        -updateSQL string
        -shuttingDown atomic.Bool
        +pollLoop(ctx context.Context)
        +relayBatch(ctx context.Context) error
        +handleHealth(w, r)
        +handleReady(w, r)
        -checkKafka(ctx) error
        -fail(ctx, err) error
    }

    class relayMetrics {
        +relayed metric.Int64Counter
        +failures metric.Int64Counter
        +lag metric.Float64Histogram
    }

    class outboxEvent {
        +ID string
        +AggregateType string
        +AggregateID string
        +EventType string
        +Payload []byte
        +CreatedAt time.Time
    }

    class telemetry {
        +tracer trace.Tracer
        +meter metric.Meter
        +shutdown func
    }

    relayService --> Config : uses
    relayService --> relayMetrics : records
    relayService --> outboxEvent : processes
    relayService --> telemetry : traces
    telemetry --> Config : configured by

    note for relayService "Core loop: pollLoop → relayBatch\nSQL injection safe via pgx.Identifier.Sanitize()\nGraceful shutdown via atomic.Bool + context cancellation"
```

### Key Internal Functions

| Function | Responsibility | Source |
|---|---|---|
| `loadConfig()` | Parse env vars with validation; safe-identifier check on table name | `main.go:227` |
| `newKafkaProducer()` | Build idempotent `sarama.SyncProducer` with `WaitForAll`, `MaxOpenRequests=1` | `main.go:629` |
| `newRelayService()` | Pre-build parameterized SQL via `pgx.Identifier.Sanitize()` to prevent injection | `main.go:409` |
| `pollLoop()` | Ticker-driven loop calling `relayBatch()`, respects context cancellation | `main.go:432` |
| `relayBatch()` | Single poll-produce-mark cycle inside one DB transaction | `main.go:449` |
| `resolveKafkaTopic()` | Map `aggregate_type` → canonical Kafka topic, or use `OUTBOX_TOPIC` override | `main.go:709` |
| `buildEventMessage()` | Construct canonical JSON envelope; hoist payload fields to top level | `main.go:779` |
| `normalizeAggregateType()` | Lowercase, strip non-alphanumeric, collapse to underscore-delimited form | `main.go:748` |

---

## Outbox Relay Flow

### Poll-Produce-Mark Cycle

```mermaid
flowchart TD
    A["Ticker fires every POLL_INTERVAL (default 1s)"] --> B["BEGIN PostgreSQL transaction"]
    B --> C["SELECT id, aggregate_type, aggregate_id,<br/>event_type, payload, created_at<br/>FROM outbox_events<br/>WHERE sent = false<br/>ORDER BY created_at<br/>LIMIT $batch_size<br/>FOR UPDATE SKIP LOCKED"]
    C --> D{"Events found?"}
    D -->|"No"| E["(implicit empty commit)<br/>Sleep until next tick"]
    D -->|"Yes"| F["For each event in batch"]
    F --> G["resolveKafkaTopic()<br/>OUTBOX_TOPIC override or<br/>aggregate_type → canonical topic"]
    G --> H["buildEventMessage()<br/>Construct envelope JSON +<br/>set Kafka headers"]
    H --> I["SyncProducer.SendMessage()<br/>(blocks until all ISR ACK)"]
    I --> J{"Produce OK?"}
    J -->|"Yes"| K["UPDATE outbox_events<br/>SET sent = true<br/>WHERE id = $id"]
    K --> L["Record relay lag metric<br/>(time.Since created_at)"]
    L --> M["Increment outbox.relay.count"]
    M --> F
    J -->|"No"| N["Increment outbox.relay.failures<br/>Log error with event_id + topic<br/>Break inner loop"]
    N --> O["COMMIT transaction<br/>(partial progress: already-marked events committed)"]
    E --> A
    O --> P["Return sendErr<br/>(logged by pollLoop)"]
    P --> A

    style N fill:#fee,stroke:#c00
    style O fill:#ffe,stroke:#a80
```

### Sequence Diagram — End-to-End Event Flow

```mermaid
sequenceDiagram
    participant Java as Java Producer Service
    participant PG as PostgreSQL
    participant Relay as outbox-relay-service
    participant Kafka as Kafka Broker
    participant Consumer as Downstream Consumer

    Java->>PG: BEGIN TX
    Java->>PG: INSERT/UPDATE domain entity
    Java->>PG: INSERT INTO outbox_events<br/>(aggregate_type, aggregate_id,<br/>event_type, payload, sent=false)
    Java->>PG: COMMIT TX
    Note over Java,PG: Atomic: both writes succeed<br/>or both roll back

    loop Every 1s (configurable)
        Relay->>PG: BEGIN TX
        Relay->>PG: SELECT ... WHERE sent=false<br/>ORDER BY created_at<br/>LIMIT 100 FOR UPDATE SKIP LOCKED
        PG-->>Relay: Batch of unsent events (rows locked)

        loop For each event in batch
            Relay->>Relay: resolveKafkaTopic(aggregate_type)
            Relay->>Relay: buildEventMessage() → envelope JSON
            Relay->>Kafka: SyncProducer.SendMessage<br/>(key=aggregateId, topic=resolved,<br/>acks=all, idempotent=true)
            Kafka-->>Relay: ACK (all ISR)
            Relay->>PG: UPDATE SET sent=true WHERE id=$id
        end

        Relay->>PG: COMMIT TX
    end

    Kafka->>Consumer: Deliver message (at-least-once)
    Consumer->>Consumer: Process event
    Consumer->>Kafka: Commit offset
```

### Aggregate-to-Topic Routing

The relay maps `aggregate_type` (after normalization) to a canonical Kafka topic. If `OUTBOX_TOPIC` is set, all events route to that single override topic.

| `aggregate_type` (normalized) | Kafka Topic |
|---|---|
| `order`, `orders` | `orders.events` |
| `payment`, `payments` | `payments.events` |
| `inventory` | `inventory.events` |
| `fulfillment` | `fulfillment.events` |
| `catalog`, `product`, `products` | `catalog.events` |
| `identity`, `user`, `users` | `identity.events` |
| `rider`, `riders`, `riderfleet`, `rider_fleet` | `rider.events` |
| `warehouse`, `store`, `stores` | `warehouse.events` |
| `pricing`, `price` | `pricing.events` |
| `wallet`, `loyalty` | `wallet.events` |
| `fraud` | `fraud.events` |
| `notification`, `notifications` | `notification.events` |
| *(unrecognized)* | `{normalized}.events` |
| *(empty)* | `unknown.events` |

> Source: `resolveKafkaTopic()` and `normalizeAggregateType()` in `main.go:709-777`

---

## Event Publishing & Envelope

### Kafka Producer Settings

| Setting | Value | Rationale |
|---|---|---|
| `Version` | `V2_5_0_0` | Minimum Kafka version for idempotent producer support |
| `RequiredAcks` | `WaitForAll` | All in-sync replicas must acknowledge before success |
| `Idempotent` | `true` | Broker-side PID + sequence dedup (within session) |
| `MaxOpenRequests` | `1` | Required for idempotent producer correctness |
| `Retry.Max` | `10` | Resilience to transient broker failures |
| `Return.Successes` | `true` | Required for `SyncProducer` to return partition/offset |
| Message key | `aggregateId` | Ensures per-aggregate ordering within a Kafka partition |

### Published Envelope Shape

The relay constructs a canonical JSON envelope via `buildEventMessage()`:

```json
{
  "id":            "550e8400-e29b-41d4-a716-446655440000",
  "eventId":       "550e8400-e29b-41d4-a716-446655440000",
  "aggregateType": "Order",
  "aggregateId":   "order-12345",
  "eventType":     "OrderPlaced",
  "eventTime":     "2024-01-15T10:30:00.000000000Z",
  "schemaVersion": "v1",
  "payload":       { "orderId": "...", "userId": "...", "totalCents": 4990 }
}
```

If `payload` is a JSON object, its top-level fields are hoisted into the envelope (without overwriting existing envelope keys). This means consumers may see payload fields at the top level alongside the envelope fields.

### Kafka Headers

| Header | Value | Purpose |
|---|---|---|
| `event_id` | UUID string | Infrastructure-level deduplication key |
| `event_type` | e.g. `OrderPlaced` | Consumer routing / filtering |
| `aggregate_type` | e.g. `Order` | Log aggregation, observability |
| `schema_version` | `v1` (hardcoded) | Version discrimination for consumers |

> **Note:** `schema_version` is currently hardcoded to `"v1"` for all events regardless of actual schema version. See [Known Limitations](#known-limitations).

---

## Configuration

All configuration is via environment variables. No config files are used.

| Variable | Default | Required | Description |
|---|---|---|---|
| `PORT` / `SERVER_PORT` | `8103` | No | HTTP listen port for health endpoints |
| `DATABASE_URL` / `POSTGRES_DSN` | — | **Yes** | PostgreSQL connection string (DSN format) |
| `OUTBOX_POLL_INTERVAL` | `1s` | No | Polling interval (Go duration syntax: `500ms`, `2s`, etc.) |
| `OUTBOX_BATCH_SIZE` | `100` | No | Maximum events fetched per poll cycle (must be > 0) |
| `OUTBOX_TABLE` | `outbox_events` | No | Table name to poll (alphanumeric + underscore only; validated at startup) |
| `OUTBOX_TOPIC` | *(empty)* | No | Override Kafka topic for all events; if empty, uses aggregate→topic routing |
| `KAFKA_BROKERS` | — | **Yes** | Comma-separated Kafka broker list |
| `KAFKA_CLIENT_ID` | `outbox-relay-service` | No | Kafka client identifier |
| `SHUTDOWN_TIMEOUT` | `20s` | No | Graceful shutdown deadline for HTTP server + poll loop drain |
| `READY_TIMEOUT` | `2s` | No | Timeout for readiness probe checks (DB ping + Kafka controller check) |
| `LOG_LEVEL` | `info` | No | Structured log level: `debug`, `info`, `warn`, `error` |
| `OTEL_SERVICE_NAME` | `outbox-relay-service` | No | OpenTelemetry service name attribute |
| `OTEL_EXPORTER_OTLP_ENDPOINT` | *(empty)* | No | OTLP HTTP endpoint; if empty, telemetry is no-op |

### Startup Validation

The service exits immediately on startup if:
- `DATABASE_URL` / `POSTGRES_DSN` is missing
- `KAFKA_BROKERS` is missing or empty after trimming
- `OUTBOX_BATCH_SIZE` is ≤ 0
- `OUTBOX_TABLE` contains characters outside `[a-zA-Z0-9_]`
- PostgreSQL `Ping()` fails
- Kafka producer creation fails (broker unreachable)

---

## Database Schema

The relay expects the following table (name configurable via `OUTBOX_TABLE`):

```sql
CREATE TABLE outbox_events (
    id             UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    aggregate_type TEXT        NOT NULL,
    aggregate_id   TEXT        NOT NULL,
    event_type     TEXT        NOT NULL,
    payload        JSONB       NOT NULL,
    sent           BOOLEAN     NOT NULL DEFAULT FALSE,
    created_at     TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- Partial index aligned with the relay's ORDER BY created_at query:
CREATE INDEX idx_outbox_unsent ON outbox_events (created_at) WHERE sent = FALSE;
```

> **Index note:** The relay queries `ORDER BY created_at … FOR UPDATE SKIP LOCKED`. A partial index on `(created_at) WHERE sent = false` aligns with this query plan. Some upstream services use `(sent) WHERE sent = false` instead — the `(created_at)` variant is more efficient for the relay's access pattern.

### SQL Injection Prevention

The table name from `OUTBOX_TABLE` is validated at startup via `isSafeIdentifier()` (alphanumeric + underscore only) and sanitized at query-build time via `pgx.Identifier.Sanitize()`. The `selectSQL` and `updateSQL` are pre-built once and reused for all poll cycles.

---

## Dependencies

| Dependency | Version | Purpose |
|---|---|---|
| Go | 1.24+ | Runtime (single binary, `CGO_ENABLED=0`) |
| `github.com/IBM/sarama` | v1.43.2 | Kafka client — idempotent `SyncProducer` with `WaitForAll` acks |
| `github.com/jackc/pgx/v5` | v5.8.0 | PostgreSQL driver — connection pooling via `pgxpool` |
| `go.opentelemetry.io/otel` | v1.41.0 | OpenTelemetry SDK — distributed tracing + metrics via OTLP HTTP |

**External runtime dependencies:**
- **PostgreSQL** — any version supporting `gen_random_uuid()`, partial indexes, and `FOR UPDATE SKIP LOCKED` (9.5+)
- **Apache Kafka** — version ≥ 2.5 (required for idempotent producer support per `sarama.V2_5_0_0`)

---

## Observability

### Metrics

Exported via OpenTelemetry OTLP HTTP (periodic reader, 15s interval). No Prometheus `/metrics` scrape endpoint exists today (see [Known Limitations](#known-limitations)).

| Metric | Type | Unit | Description |
|---|---|---|---|
| `outbox.relay.count` | Int64 Counter | — | Events successfully produced to Kafka and marked `sent=true` |
| `outbox.relay.failures` | Int64 Counter | — | Relay failures (DB errors, Kafka produce errors, context cancellations excluded) |
| `outbox.relay.lag.seconds` | Float64 Histogram | seconds | Time between `outbox_events.created_at` and successful relay |

### Tracing

Each `relayBatch()` call creates an `outbox.relay.batch` span with attributes:
- `outbox.batch_size`, `outbox.table`

Each individual message publish creates an `outbox.relay.publish` child span with:
- `event.id`, `event.type`, `aggregate.type`, `aggregate.id`, `kafka.topic`

Errors are recorded on spans via `span.RecordError()`.

**Propagation:** W3C TraceContext + Baggage (`propagation.NewCompositeTextMapPropagator`).

### Structured Logging

JSON-formatted via `log/slog` to stdout. Key fields in relay logs:
- `event_id`, `topic`, `lag_seconds` (on success at `DEBUG` level)
- `error`, `event_id`, `topic` (on produce failure at `ERROR` level)

### Recommended Alert Rules

| Condition | Severity | Action |
|---|---|---|
| `rate(outbox.relay.failures) > 0` for 2m | Warning | Investigate stuck events or Kafka connectivity |
| `outbox.relay.lag.seconds` P99 > 10s for 5m | Critical/Page | Outbox is backing up; check relay health, DB load, Kafka throughput |
| Readiness endpoint returning 503 | Critical | Relay cannot reach DB or Kafka; pods will be removed from service |

---

## Health & Readiness

| Endpoint | Method | Behavior |
|---|---|---|
| `GET /health` | `GET`, `HEAD` | Always returns `200 {"status":"ok"}` (liveness) |
| `GET /health/live` | `GET`, `HEAD` | Alias for `/health` |
| `GET /health/ready` | `GET`, `HEAD` | Returns `200 {"status":"ready"}` if DB ping + Kafka controller check pass within `READY_TIMEOUT`; returns `503` if shutting down or either check fails |
| `GET /ready` | `GET`, `HEAD` | Alias for `/health/ready` |

The readiness check creates a fresh `sarama.Client` connection to verify broker reachability and controller availability. During graceful shutdown, the `shuttingDown` atomic flag is set, causing readiness to return `503` immediately — this drains the pod from load-balancer rotation before the poll loop stops.

### HTTP Server Timeouts

| Timeout | Value |
|---|---|
| `ReadHeaderTimeout` | 5s |
| `ReadTimeout` | 15s |
| `WriteTimeout` | 15s |
| `IdleTimeout` | 60s |

---

## Testing

### Local Validation

```bash
cd services/outbox-relay-service
go build ./...          # Compile check
go vet ./...            # Static analysis
```

### Integration Testing

No unit tests exist in the current codebase. Integration testing requires:

1. **Start local infrastructure:** `docker-compose up -d` (PostgreSQL on 5432, Kafka on 9092)
2. **Seed the outbox table:** Insert test rows into `outbox_events` with `sent=false`
3. **Run the relay:** `DATABASE_URL="postgres://..." KAFKA_BROKERS="localhost:9092" go run .`
4. **Verify:** Consume from the expected Kafka topic and confirm envelope structure

### CI

The relay is validated in `.github/workflows/ci.yml` as part of the Go service matrix: `go test ./...` then `go build ./...`. Path filters trigger on changes to `services/outbox-relay-service/**` or `services/go-shared/**`.

---

## Failure Modes & Recovery

### Failure Decision Tree

```mermaid
flowchart TD
    A["relayBatch() called"] --> B{"DB BEGIN or<br/>SELECT fails?"}
    B -->|"Yes"| C["Rollback TX<br/>Increment outbox.relay.failures<br/>Return error → pollLoop logs it<br/>Retry on next tick (1s)"]
    B -->|"No"| D{"Zero events?"}
    D -->|"Yes"| E["Commit empty TX<br/>Sleep until next tick"]
    D -->|"No"| F["Process events in loop"]
    F --> G{"Kafka SendMessage<br/>fails?"}
    G -->|"Yes"| H["Increment outbox.relay.failures<br/>Log error with event_id + topic<br/>Break inner loop<br/>Fall through to COMMIT"]
    G -->|"No"| I{"DB UPDATE<br/>SET sent=true fails?"}
    I -->|"Yes"| J["Rollback entire TX<br/>Increment failures<br/>All events in batch<br/>return to unsent state"]
    I -->|"No"| K["Record lag metric<br/>Continue to next event"]
    H --> L["COMMIT TX<br/>(partial progress:<br/>already-marked events saved)"]
    K --> F
    L --> A
    J --> A
    E --> A
    C --> A

    style C fill:#fee,stroke:#c00
    style H fill:#fee,stroke:#c00
    style J fill:#fee,stroke:#c00
    style L fill:#ffe,stroke:#a80
```

### Failure Mode Catalogue

| # | Failure | Detection | Impact | Recovery |
|---|---|---|---|---|
| F1 | Kafka broker down | `outbox.relay.failures` rate > 0 | Outbox lag grows; no events published | Auto-recovery on broker restore; relay retries every poll interval |
| F2 | DB connection lost | Readiness probe fails → pod restart | Outbox lag grows | Kubernetes restarts pod; relay reconnects via `pgxpool` |
| F3 | Persistent produce error (e.g., invalid topic, auth failure) | Same `event_id` in error logs repeatedly | **Entire outbox stalls** on one bad event | Manual: `UPDATE outbox_events SET sent = true WHERE id = '<stuck-id>'` |
| F4 | Outbox table bloat (no cleanup) | Slow query logs, `pg_stat_user_tables` dead tuple count | Relay poll latency degrades | `DELETE FROM outbox_events WHERE sent = true AND created_at < (now() - interval '7 days')` |
| F5 | DB UPDATE succeeds but COMMIT fails | Event re-delivered on next poll | Duplicate event in Kafka | Consumers must be idempotent on `event_id` |
| F6 | Relay restart (new PID) | — | Idempotent producer sequence resets; duplicates possible for in-flight events | Consumer-side dedup on `event_id` header |
| F7 | `schema_version` mismatch (v2 payload stamped as v1) | Consumer deserialization errors | Events processed with wrong schema | See [Known Limitations](#known-limitations) |

### Partial-Commit Semantics

The relay's inner loop is: **Produce → Mark sent → (next event)**. On produce failure, it breaks the loop and commits the transaction. Events already marked `sent=true` in that batch are committed; the failing event and remaining events return to `sent=false` and will be retried on the next tick. This is the **partial-commit** behavior — it prevents a single bad event from blocking all prior successfully-relayed events in the same batch.

However, if `SendMessage` succeeds but the subsequent `tx.Commit()` fails, the event was published to Kafka but remains `sent=false`. The next poll will re-publish it. This is the fundamental source of at-least-once (not exactly-once) delivery.

---

## Rollout & Rollback

### Deployment Model

The relay is deployed as a Kubernetes Deployment (Helm chart in `deploy/helm/`, key `outbox-relay` in `values-dev.yaml`). It is a singleton-per-database by design — `FOR UPDATE SKIP LOCKED` enables multiple replicas if horizontal scaling is needed.

### Safe Rollout Sequence

1. **Pre-check:** Confirm `outbox.relay.lag.seconds` is stable and low
2. **Deploy:** Rolling update (Kubernetes default). New pod starts, passes readiness, old pod receives SIGTERM
3. **Graceful shutdown:** Old pod sets `shuttingDown=true` → readiness returns 503 → drains current batch → HTTP server shutdown → poll loop context cancelled → `wg.Wait()` with `SHUTDOWN_TIMEOUT` (20s) deadline → telemetry flush
4. **Post-check:** Confirm new pod's `outbox.relay.count` is incrementing and `lag` is stable

### Rollback

Standard Kubernetes rollback (`kubectl rollout undo` or ArgoCD revert). The relay is stateless — any version that can connect to the same PostgreSQL and Kafka will resume from the current `sent=false` rows. No data migration is needed between relay versions.

### `OUTBOX_TOPIC` Override for Migration

Set `OUTBOX_TOPIC` to route all events to a single topic during testing or topic migration. Remove the override to return to aggregate-based routing.

---

## Known Limitations

| # | Limitation | Impact | Mitigation Path |
|---|---|---|---|
| 1 | **No relay DLQ** — a persistent produce error on one event stalls the outbox for that aggregate type indefinitely | Head-of-line blocking; operator must manually skip the stuck event | Add a DLQ topic (`outbox.relay.dlq`) and mark-then-produce flow |
| 2 | **`schema_version` hardcoded to `"v1"`** — not read from the outbox row | Silent version mismatch when v2 schemas deploy | Add `schema_version` column to `outbox_events`; relay reads from row |
| 3 | **`source_service` and `correlation_id` absent from envelope** — mandated by `contracts/README.md` but not emitted | No distributed trace continuity across async boundary; audit/GDPR gaps | Add columns to `outbox_events`; propagate via `MDC` in Java producers |
| 4 | **Both `id` and `eventId` emitted** in envelope (redundant) | Consumer confusion on canonical ID field | Standardize on one; deprecate the other |
| 5 | **Payload field hoisting** — top-level payload fields merged into envelope | Two divergent deserialization patterns across consumers | Document as intentional or remove hoisting |
| 6 | **No `/metrics` Prometheus endpoint** — metrics exported via OTLP only | PodMonitor/ServiceMonitor cannot scrape directly | Add Prometheus HTTP handler or use OpenTelemetry Collector Prometheus exporter |
| 7 | **No outbox cleanup in 12 of 13 producer services** — only `order-service` has a ShedLock-guarded cleanup job | `outbox_events` table grows unbounded; vacuum pressure | Add shared cleanup job to all producer services |
| 8 | **Singular/plural topic drift** — some downstream consumers subscribe to both `order.events` and `orders.events` as compensation | Doubles consumer-group complexity; masks routing misconfig | Consolidate all producers to canonical plural topic names |

> Sources: `docs/reviews/iter3/services/event-data-plane.md`, `docs/reviews/iter3/diagrams/lld/eventing-outbox-replay.md`, `docs/reviews/iter3/platform/contracts-event-governance.md`

---

## Build & Run

### Local

```bash
cd services/outbox-relay-service
go build -o outbox-relay .

DATABASE_URL="postgres://user:pass@localhost:5432/orders_db" \
KAFKA_BROKERS="localhost:9092" \
LOG_LEVEL="debug" \
./outbox-relay
```

### Docker

```bash
docker build -t outbox-relay-service .
docker run \
  -e DATABASE_URL="postgres://user:pass@host.docker.internal:5432/orders_db" \
  -e KAFKA_BROKERS="host.docker.internal:9092" \
  -p 8103:8103 \
  outbox-relay-service
```

### With Local Infrastructure

```bash
# From repository root — starts PostgreSQL, Kafka, and supporting services
docker-compose up -d

# Then run the relay against local infra
cd services/outbox-relay-service
DATABASE_URL="postgres://postgres:postgres@localhost:5432/instacommerce" \
KAFKA_BROKERS="localhost:9092" \
go run .
```

---

## Project Structure

```
outbox-relay-service/
├── main.go         # All application code: config, poll loop, Kafka producer,
│                   # topic routing, envelope construction, health endpoints,
│                   # telemetry setup, graceful shutdown (~837 lines)
├── Dockerfile      # Multi-stage build: golang:1.26-alpine → alpine:3.23
│                   # Non-root user (app:1001), HEALTHCHECK on /health
└── go.mod          # Module: github.com/instacommerce/outbox-relay-service
```

---

## Q-Commerce Event Delivery Comparison

For context on how InstaCommerce's outbox relay pattern compares to event-delivery approaches in the q-commerce space:

| Dimension | InstaCommerce (this service) | Poll-Based Outbox (industry standard) | CDC-Based Outbox (Debezium EventRouter) |
|---|---|---|---|
| **Mechanism** | Application-level poll (`SELECT … FOR UPDATE SKIP LOCKED`) | Same | WAL logical decoding via Debezium connector |
| **Latency** | Poll interval (default 1s) + produce time | Same | Sub-second (WAL tail) |
| **DB load** | Periodic queries against partial index; scales with outbox depth | Same | WAL read only; no query load on primary |
| **Delivery guarantee** | At-least-once (idempotent producer, but cross-session duplicates possible) | Same | At-least-once (Debezium + idempotent producer) or exactly-once (with Kafka transactions) |
| **Concurrency** | `FOR UPDATE SKIP LOCKED` enables safe multi-instance | Requires explicit coordination or row locking | Single connector task per table (Debezium managed) |
| **Operational complexity** | Single Go binary; no JVM/connector infrastructure | Same | Requires Kafka Connect cluster + connector management |
| **Cleanup** | Application must delete/archive `sent=true` rows | Same | Debezium reads WAL; outbox rows can be deleted immediately after commit |

India's leading q-commerce operators (Zepto, Blinkit, Swiggy Instamart) use event-driven architectures with Kafka as the backbone for real-time SLA enforcement, inventory cascades, and fulfillment state machines. Zepto's public engineering materials describe Apache Flink-based stream processing consuming Kafka events for per-stage SLA breach detection. The outbox pattern (poll or CDC-based) is the industry standard mechanism for getting domain events into Kafka without dual-write risk.

> **Migration note:** The `docs/reviews/iter3/services/event-data-plane.md` review recommends evaluating Debezium Outbox Event Router (`io.debezium.transforms.outbox.EventRouter`) as a potential replacement for this poll-based relay, trading operational complexity (Kafka Connect cluster) for lower latency and reduced DB query load. See also `docs/reviews/iter3/benchmarks/public-best-practices.md` §3.

---

*Last verified against: `services/outbox-relay-service/main.go` (837 lines), `go.mod` (Go 1.24, sarama v1.43.2, pgx v5.8.0, OTel v1.41.0), `Dockerfile`, and review documents under `docs/reviews/iter3/`.*
