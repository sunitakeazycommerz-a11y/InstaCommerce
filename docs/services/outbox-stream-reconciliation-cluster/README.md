# Outbox Relay, Stream Processor & Reconciliation Engine Cluster

## Overview

This cluster comprises three **event plane** services handling reliable domain event publishing and real-time metrics:

1. **outbox-relay-service**: Authoritative event publisher (PostgreSQL → Kafka via transactional outbox)
2. **stream-processor-service**: Real-time operational metrics (Kafka → Redis/Prometheus)
3. **reconciliation-engine**: Financial ledger reconciliation and anomaly detection (file-based → DB-backed)

## Cluster Architecture

```
┌────────────────────────────────────────┐
│  13 Java Producer Services             │
│  (order, payment, fulfillment, ...)    │
└────────────────────────────────────────┘
     │
     ├─ INSERT outbox_events (tx-scoped)
     │
     ▼
┌──────────────────────────┐
│ PostgreSQL (outbox_events│
│ sent = false, poll)      │
└──────────────────────────┘
     │
     ├─ Poll every 1s
     │
     ▼
┌──────────────────────────┐
│ outbox-relay-service     │
│ (Go :8103)               │
│ Produce to Kafka         │
└──────────────────────────┘
     │
     ├─ Domain topics (orders.events, etc.)
     │
     ├─────────────────────────────────────┐
     │                                     │
     ▼                                     ▼
┌──────────────────┐          ┌──────────────────────┐
│ stream-processor │          │ audit-trail-service  │
│ (Go :8108)       │          │ (other consumers)    │
│ Redis metrics    │          │                      │
└──────────────────┘          └──────────────────────┘
     │
     ├─ Per-minute aggregations
     │
     ├─────────────────────┬────────────────┐
     ▼                     ▼                ▼
 Redis keys         Prometheus metrics   Alert channel
(TTL-bounded)       (/metrics)           (SLA breaches)
```

## Service Responsibilities

### Outbox Relay Service (Tier 1)
- **Transactional Outbox**: Polls `outbox_events` table for unsent events
- **Reliable Publishing**: Kafka idempotent producer with `WaitForAll` acks
- **Topic Routing**: Maps aggregate type → canonical Kafka topic
- **Partial Commit**: Keeps batch progress on individual failures
- **Graceful Shutdown**: Drains current batch on termination
- **Port**: 8103 (dev), 8080 (container)
- **Runtime**: Go 1.24, stateless single binary

### Stream Processor Service (Tier 2)
- **Real-Time Aggregation**: Sliding-window counters (orders, payments, riders, inventory)
- **SLA Monitoring**: 30-min delivery window with 90% compliance threshold
- **Dual Sinks**: Redis (for dashboards) + Prometheus (for alerting)
- **Deduplication**: Redis SET-based event ID dedup (Wave 32 improvement)
- **Port**: 8108 (dev), 8080 (container)
- **Runtime**: Go 1.24

### Reconciliation Engine (Tier 2)
- **Financial Ledger**: Tallies transactions, settlements, refunds
- **Anomaly Detection**: Flag duplicate charges, partial failures
- **Reconciliation Reports**: Daily/hourly settlement verification
- **DLQ Management**: Routes unprocessable records for manual review
- **Port**: 8090 (dev), 8080 (container)
- **Runtime**: Go 1.24 (Wave 36: migrate from file-based to DB-backed)

## Key Technologies

| Component | Version | Purpose |
|-----------|---------|---------|
| Kafka | 2.8+ | Domain events (13 producer services) |
| PostgreSQL | 15+ | Outbox storage (13 databases) |
| Redis | 7.0+ | Metrics cache, dedup set |
| Prometheus | — | Metrics scraping + alerting |
| Go | 1.24 | High-performance event processing |
| OpenTelemetry | v1.41+ | Distributed tracing |
| sarama | v1.43.2 | Kafka client (idempotent producer) |
| go-redis | v9.18+ | Redis client with pipeline |

## Event Publishing Flow

### 1. Producer Side (Java Service)

```java
@Service
public class OrderService {
    @Transactional(propagation = REQUIRED)
    public void placeOrder(OrderRequest req) {
        Order order = orderRepo.save(new Order(...));

        // Outbox pattern: within same transaction
        outboxService.publish("Order", order.getId(), "OrderPlaced",
            Map.of("orderId", order.getId(), "totalCents", req.getTotalCents()));

        // Both INSERT domain aggregate + INSERT outbox event
        // succeed/fail atomically
    }
}
```

**Database**: `outbox_events` row written to PostgreSQL with `sent = false`

### 2. Relay Side (Go Service)

```
Tick every 1s:
  BEGIN TX
    SELECT id, aggregate_type, aggregate_id, event_type, payload
    FROM outbox_events WHERE sent = false ORDER BY created_at LIMIT 100
    FOR UPDATE SKIP LOCKED

  FOR each event:
    topic = resolveKafkaTopic(aggregate_type)       # order → orders.events
    Kafka.SyncProducer.SendMessage(key=aggregateId, topic=topic, envelope)
    UPDATE outbox_events SET sent = true WHERE id = event.id

  COMMIT TX
```

**Guarantee**: At-least-once delivery (idempotent producer within session; duplicates possible across restarts)

## Kafka Topics & Routing

| Aggregate Type | Kafka Topic |
|---|---|
| Order | `orders.events` |
| Payment | `payments.events` |
| Inventory | `inventory.events` |
| Fulfillment | `fulfillment.events` |
| Rider | `rider.events` |
| Catalog | `catalog.events` |
| Identity | `identity.events` |
| Notification | `notification.events` |
| Wallet | `wallet.events` |
| Pricing | `pricing.events` |
| Fraud | `fraud.events` |
| Rider Fleet | `rider.events` |
| Warehouse | `warehouse.events` |

**Dead Letter Queue**: `outbox.relay.dlq` for undeliverable events

## Event Envelope

```json
{
  "id": "550e8400-e29b-41d4-a716-446655440000",
  "eventId": "550e8400-e29b-41d4-a716-446655440000",
  "aggregateType": "Order",
  "aggregateId": "order-12345",
  "eventType": "OrderPlaced",
  "eventTime": "2025-01-15T10:30:00.000Z",
  "schemaVersion": "v1",
  "payload": {
    "orderId": "order-12345",
    "userId": "user-abc",
    "totalCents": 4990,
    "itemCount": 3
  }
}
```

**Kafka Headers**:
- `event_id`: UUID string
- `event_type`: e.g., `OrderPlaced`
- `aggregate_type`: e.g., `Order`
- `schema_version`: `v1` (hardcoded; Wave 35 improvement needed)

## Stream Processor Metrics

### Order Metrics

```
orders_total{event_type, store_id, zone_id}        # Counter
gmv_total_cents{}                                  # GMV running total
delivery_duration_minutes{zone_id, store_id}       # Histogram (5-60 min buckets)
sla_compliance_ratio{zone_id}                      # Gauge: 0-1 ratio
order_cancellations_total{store_id, zone_id}       # Counter
```

### Payment Metrics

```
payments_total{event_type, method}                 # Counter
payment_success_rate{method}                       # 5-min sliding window gauge
payment_failures_by_code{failure_code, method}     # Counter
refunds_total{event_type}                          # Counter
```

### Rider Metrics

```
riders_by_status{status, zone_id}                  # Gauge (online|idle|offline)
rider_deliveries_total{rider_id, zone_id}          # Counter
rider_earnings_total_cents{rider_id, zone_id}      # Counter
rider_location_updates_total{}                     # Counter
```

### Inventory Metrics

```
inventory_stock_updates_total{event_type, store_id}  # Counter
inventory_stockouts_total{store_id}                  # Counter
inventory_cascade_alerts_total{store_id}             # >10 SKUs OOS
inventory_velocity_per_hour{sku_id, store_id}       # 1-hr window gauge
```

### SLA Monitoring

```
sla_window_percent{zone_id}                        # 30-min window gauge
sla_alerts_total{zone_id}                          # Counter of breaches
sla_window_orders{zone_id}                         # Current window order count
```

## Redis Output Schema

### Order Keys (2h TTL)
```
orders:count:{minute}
orders:store:{storeId}:{minute}
orders:zone:{zoneId}:{minute}
gmv:total:{date}                          # 48h TTL
delivery:time:{zoneId}:{minute}
orders:cancelled:{zoneId}:{minute}
```

### Rider Keys (24h base TTL)
```
rider:status:{riderId}
riders:zone:{zoneId}:{status}
rider:earnings:{riderId}:{date}           # 48h TTL
rider:location:{riderId}                  # 10min TTL
riders:geo:{zoneId}                       # Geo-spatial set
```

## Configuration

### Outbox Relay Service

```bash
PORT=8103
DATABASE_URL=postgres://user:pass@localhost:5432/orders_db
KAFKA_BROKERS=localhost:9092
OUTBOX_POLL_INTERVAL=1s
OUTBOX_BATCH_SIZE=100
OUTBOX_TABLE=outbox_events
OUTBOX_TOPIC=                             # Empty = use aggregate→topic routing
SHUTDOWN_TIMEOUT=20s
READY_TIMEOUT=2s
LOG_LEVEL=info
OTEL_EXPORTER_OTLP_ENDPOINT=http://otel-collector:4318/v1/traces
```

### Stream Processor Service

```bash
HTTP_PORT=8108
KAFKA_BROKERS=localhost:9092
CONSUMER_GROUP_ID=stream-processor
REDIS_ADDR=localhost:6379
REDIS_PASSWORD=
LOG_LEVEL=info
```

### Reconciliation Engine

```bash
PORT=8090
KAFKA_BROKERS=localhost:9092
KAFKA_TOPICS=payments.events,refunds.events,settlements.events
KAFKA_GROUP_ID=reconciliation-engine
POSTGRES_DSN=postgres://user:pass@localhost:5432/reconciliation_db
REPORT_INTERVAL=24h                       # Daily reconciliation
ANOMALY_THRESHOLD=0.05                    # 5% variance tolerance
```

## Observability

### Outbox Relay Metrics

```
outbox.relay.count{}                      # Events successfully relayed
outbox.relay.failures{}                   # Relay errors
outbox.relay.lag.seconds{}                # Time from creation to relay
```

### Stream Processor Metrics

All Prometheus metrics via `/metrics` endpoint and OTLP export.

### Reconciliation Metrics

```
reconciliation.processed_transactions{}   # Total processed
reconciliation.anomalies_detected{}       # Anomalies found
reconciliation.settlement_variance{}      # Variance from expected
```

## Health & Readiness

| Service | Liveness | Readiness |
|---------|----------|-----------|
| outbox-relay | `/health` | `/health/ready` (DB + Kafka) |
| stream-processor | `/health` | `/health` (always ready once connected) |
| reconciliation-engine | `/health` | `/ready` (Kafka + DB) |

## Failure Modes

| Scenario | Detection | Recovery |
|----------|-----------|----------|
| Kafka broker down | Relay produce fails, lag grows | Auto-reconnect; retry on next poll |
| PostgreSQL down | Relay poll fails | Restart pod; try again |
| Bad event (unserializable) | Produce error for same event_id | Manual DLQ skip needed |
| Stream processor lag spike | Consumer lag > 10,000 | Scale replicas; check Redis |
| Reconciliation mismatch | Daily report flagged variance | Manual audit + correction |

## Deployment Checklist

- [ ] Outbox cleanup job runs on producer services (scheduled)
- [ ] DLQ topics exist: `outbox.relay.dlq`, `cdc.dlq`
- [ ] Kafka broker reachability confirmed from relay pods
- [ ] PostgreSQL partial index on `outbox_events (created_at) WHERE sent=false` exists
- [ ] Stream processor Redis connection pool sized for topic count
- [ ] Reconciliation engine database initialized with ledger schema
- [ ] All readiness probes return 200 before traffic

## Known Limitations

1. **No relay DLQ**: Stuck event blocks all subsequent events of same aggregate type
2. **`schema_version` hardcoded**: v1 for all events, no version discrimination
3. **Stream processor not idempotent**: Redelivered messages double-count metrics
4. **Dual topic subscription**: Subscribes to both `order.events` and `orders.events` (legacy)
5. **Reconciliation file-based**: Migrating to DB in Wave 36
6. **No automatic key rotation**: Outbox relay keys frozen at deploy time
7. **SLA alert channel buffer**: 100 items; overflows silently dropped

## Wave Roadmap

### Wave 33 (Current)
- ✅ Outbox relay wired for all 13 services
- ✅ Stream processor Redis dedup
- ✅ Audit trail event collection

### Wave 34
- Per-service token rollout (identity-service)
- Admin gateway authentication hardening

### Wave 35
- Outbox relay DLQ with manual recovery UI
- Schema versioning in event envelope

### Wave 36
- Reconciliation engine DB migration
- Stream processor idempotency via event_id dedup

## References

- [Outbox Relay README](/services/outbox-relay-service/README.md) — Full specification
- [Stream Processor README](/services/stream-processor-service/README.md) — Metrics details
- [Reconciliation Engine README](/services/reconciliation-engine/README.md) — Ledger logic
- [Data Flow Architecture](/docs/architecture/DATA-FLOW.md) — Event pipeline design
- [ADR-004: Event Envelope](/docs/adr/004-event-envelope.md) — Event contract
- [ADR-005: Durable Idempotency](/docs/adr/005-durable-idempotency.md) — Outbox pattern
