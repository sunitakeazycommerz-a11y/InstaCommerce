# CDC Consumer Service

## Overview

The cdc-consumer-service processes Debezium CDC (Change Data Capture) envelopes from PostgreSQL source connectors, performing deduplication, event aggregation, and state rebuilding to support downstream services. It acts as the canonical event transformation layer between raw database changes and domain events.

**Service Ownership**: Platform Team - Event Infrastructure Tier 2
**Language**: Go 1.22
**Default Port**: 8088
**Status**: Tier 2 Critical (Event processing)

## SLOs & Availability

- **Availability SLO**: 99.9% (43 minutes downtime/month)
- **P99 Consumer Lag**: < 2s (Kafka partition to consumption)
- **Deduplication Accuracy**: 99.99% (< 1 in 10,000 events duplicate)
- **Event Aggregation Latency**: < 1s (50-event window)
- **Max Throughput**: 50,000 events/second (per partition)

## Key Responsibilities

1. **Debezium Envelope Parsing**: Parse `before`/`after` fields, extract database operations (INSERT/UPDATE/DELETE)
2. **Deduplication**: In-memory cache + Redis cross-pod deduplication (5-minute sliding window)
3. **State Rebuilding**: Reconstruct entity state from CDC events for complex aggregations
4. **Event Routing**: Route events to staging tables for reconciliation-engine queries

## Deployment

### GKE Deployment
- **Namespace**: event-infrastructure
- **Replicas**: 3 (parallel partition consumption)
- **CPU Request/Limit**: 500m / 1000m
- **Memory Request/Limit**: 512Mi / 1024Mi

### Cluster Configuration
- **Ingress**: Internal-only (no external access needed)
- **NetworkPolicy**: Allow egress to Kafka, PostgreSQL (staging), Redis only
- **Service Account**: cdc-consumer-service

## Debezium CDC Processing Architecture

```
┌──────────────────────────────────────────────────────────────────┐
│ PostgreSQL (Source Database)                                     │
│ ┌────────────────────────────────────────────────────────────┐  │
│ │ Table: orders (with WAL enabled)                          │  │
│ │ - order_id: 123, status: pending -> fulfilled             │  │
│ │ - order_id: 456, status: new -> cancelled                 │  │
│ └────────────────────────────────────────────────────────────┘  │
└──────────────────────────┬───────────────────────────────────────┘
                           │
           Streaming Replication Protocol (WAL)
                           │
          ┌────────────────▼───────────────────┐
          │  Debezium PostgreSQL Connector      │
          │  • Captures DML events from WAL     │
          │  • Generates CDC envelopes         │
          │  • Publishes to Kafka topics       │
          └────────────────┬───────────────────┘
                           │
           Topic: db.public.orders (partitioned by PK)
                           │
          ┌────────────────▼───────────────────────────────┐
          │  CDC Consumer Service                           │
          │  ┌──────────────────────────────────────────┐  │
          │  │ Partition 0: Consumer 1                  │  │
          │  │ Partition 1: Consumer 2                  │  │
          │  │ Partition 2: Consumer 3                  │  │
          │  └──────────────────────────────────────────┘  │
          │                                                 │
          │  1. Parse Envelope                              │
          │     - Extract op: c/u/d (create/update/delete) │
          │     - Extract before/after snapshots            │
          │     - Get transaction ID for dedup             │
          │                                                 │
          │  2. Deduplication (Redis-backed)                │
          │     - Check: txn_id in dedup_cache?             │
          │     - If seen: skip (duplicate)                 │
          │     - If new: add with 5-min TTL               │
          │                                                 │
          │  3. State Rebuilding                            │
          │     - Load current state from cache             │
          │     - Apply delta from CDC event               │
          │     - Store in staging table                    │
          │                                                 │
          │  4. Event Aggregation (50-event batches)        │
          │     - Collect state snapshots                   │
          │     - Emit domain events (OrderFulfilled, etc.) │
          └────────────────┬───────────────────────────────┘
                           │
          ┌────────────────▼───────────────────┐
          │  PostgreSQL (Staging Tables)        │
          │  • staging_orders_state             │
          │  • staging_order_events             │
          │  (used by reconciliation-engine)    │
          └────────────────────────────────────┘
```

### Deduplication Strategy

- **Transaction ID Tracking**: Debezium embeds `txn_id` in CDC envelope
- **Redis Cache**: 5-minute sliding window (with automatic expiration)
- **Fallback**: In-memory Bloom filter if Redis unavailable
- **Metrics**: Track duplicate events (should be near-zero in steady state)

## Integrations

### Event Consumption
| Service | Topic | Partitions | Purpose |
|---------|-------|-----------|---------|
| PostgreSQL (Debezium) | db.public.orders | 3 | Order CDC events |
| PostgreSQL (Debezium) | db.public.payments | 3 | Payment CDC events |
| PostgreSQL (Debezium) | db.public.fulfillment | 3 | Fulfillment CDC events |

### Storage Integration
| System | Purpose | Timeout |
|--------|---------|---------|
| PostgreSQL (staging DB) | Write state snapshots to `staging_*` tables | 5s |
| Redis (dedup cache) | Distributed deduplication across pod replicas | 2s |

### Downstream
- **reconciliation-engine**: Queries staging tables to validate state consistency

## Endpoints

### Health & Metrics (Unauthenticated)
- `GET /health` - Liveness probe (checks Kafka + Redis + PostgreSQL connectivity)
- `GET /metrics` - Prometheus metrics (events processed, duplicates, lag)
- `GET /status` - Consumer status (partition lag, dedup cache size)

### Debug API (Service-to-Service Token Required)
- `GET /debug/dedup-cache` - Show current deduplication cache (for troubleshooting)
- `GET /debug/partition-lag` - Per-partition consumer lag
- `POST /debug/reset-offset/{partition}` - Manual offset reset (emergency only)

### Example Requests

```bash
# Check CDC consumer health
curl -s http://cdc-consumer-service:8088/health | jq .

# View current metrics
curl -s http://cdc-consumer-service:8088/metrics | grep cdc_consumer

# Check partition lag
curl -s http://cdc-consumer-service:8088/status | jq '.partitions[] | {id, lag_ms, offset}'

# View deduplication cache size (debug only)
curl -s http://cdc-consumer-service:8088/debug/dedup-cache \
  -H "Authorization: Bearer $SERVICE_TOKEN" | jq '.cache_size'
```

## Configuration

### Environment Variables
```env
SERVER_PORT=8088
KAFKA_BROKERS=kafka-0.kafka.event-infrastructure:9092,kafka-1.kafka.event-infrastructure:9092
KAFKA_CONSUMER_GROUP=cdc-consumer-service
KAFKA_TOPICS=db.public.orders,db.public.payments,db.public.fulfillment
POSTGRES_STAGING_URL=postgresql://user:pass@pg-staging.default.svc.cluster.local:5432/instacommerce_staging
REDIS_URL=redis://redis-cache.event-infrastructure:6379
DEDUP_WINDOW_SECONDS=300
STATE_BATCH_SIZE=50
OTEL_EXPORTER_OTLP_TRACES_ENDPOINT=http://otel-collector.monitoring:4318/v1/traces
LOG_LEVEL=info
```

### config.yaml
```yaml
cdc-consumer:
  kafka:
    brokers: ${KAFKA_BROKERS}
    consumer-group: ${KAFKA_CONSUMER_GROUP:cdc-consumer-service}
    topics: ${KAFKA_TOPICS}
    isolation-level: read_committed
  deduplication:
    window-seconds: ${DEDUP_WINDOW_SECONDS:300}
    backend: redis # or memory
  state-rebuilding:
    batch-size: ${STATE_BATCH_SIZE:50}
    staging-tables:
      - name: staging_orders_state
        source-table: public.orders
      - name: staging_payments_state
        source-table: public.payments
  storage:
    postgres:
      url: ${POSTGRES_STAGING_URL}
      pool-size: 10
      timeout-seconds: 5
    redis:
      url: ${REDIS_URL}
      ttl-seconds: 300
```

## Monitoring & Alerts

### Key Metrics
- `cdc_consumer_events_received_total` (counter) - Events received from Kafka
- `cdc_consumer_events_deduplicated_total` (counter) - Duplicate events filtered
- `cdc_consumer_events_processed_total` (counter) - Events written to staging tables
- `cdc_consumer_partition_lag_ms` (gauge) - Per-partition consumer lag
- `cdc_consumer_dedup_cache_size` (gauge) - Current deduplication cache entries
- `cdc_consumer_state_rebuild_duration_ms` (histogram) - Time to rebuild entity state

### Alerting Rules
- `cdc_consumer_partition_lag_ms > 2000` - Consumer lag exceeds SLO (possible under-consumption)
- `cdc_consumer_events_deduplicated_total > 100/min` - High duplicate rate (possible producer misconfiguration)
- `cdc_consumer_postgres_write_errors_total > 10/min` - Staging table write failures
- `cdc_consumer_redis_errors_total > 5/min` - Redis deduplication failures (fallback to memory)

### Logging
- **WARN**: Deduplication cache miss, Redis unavailable (fallback to memory), partition rebalance
- **INFO**: Consumer lag < 500ms (healthy state), batch committed (offset tracked)
- **ERROR**: Debezium envelope parse failures, staging table errors, partition assignment failures

## Security Considerations

### Threat Mitigations
1. **Event Filtering**: Validates CDC envelope schema (rejects malformed events)
2. **State Isolation**: Staging tables segregated from production (no direct app access)
3. **Audit Trail**: All CDC events logged with timestamp, operation type, and actor
4. **Transactional Integrity**: Writes to staging tables within same transaction boundary as deduplication

### Known Risks
- **Debezium connector compromise**: Attacker can inject false CDC events (mitigated by PostgreSQL RBAC on WAL)
- **Redis credentials theft**: Allows poisoning deduplication cache (mitigated by TLS + internal network only)
- **Staging table data exposure**: Stale snapshots visible to reconciliation-engine (expected behavior, audit-only)

## Troubleshooting

### High Consumer Lag
1. **Check partition count**: Increase `KAFKA_TOPICS` partition count (if partitions < replicas)
2. **Check processing time**: Monitor `cdc_consumer_state_rebuild_duration_ms` (if > 100ms, optimize aggregation logic)
3. **Check Redis connectivity**: `redis-cli -h redis-cache ping` should return PONG
4. **Review error logs**: `kubectl logs -l app=cdc-consumer -f` for parse/write failures

### Deduplication Cache Misses
1. **Verify Redis operational**: `kubectl get pod redis-cache-0 -o jsonpath='{.status.phase}'` should be Running
2. **Check TTL setting**: `DEDUP_WINDOW_SECONDS` must be >= max event processing latency
3. **Monitor duplicates**: High `cdc_consumer_events_deduplicated_total` (normal, not an error)
4. **Review Debezium connector offset**: Verify `read_committed` isolation level (at-least-once delivery)

### Staging Table Bloat
1. **Check retention policy**: `SELECT COUNT(*) FROM staging_orders_state` (should be stable)
2. **Monitor TTL cleanup**: Verify archival job runs daily (cleans 7+ day old snapshots)
3. **Check storage quotas**: `df -h /var/lib/postgresql` (if > 80%, increase PVC size)

## Related Documentation

- **ADR-014**: Reconciliation Authority Model (CDC justification for audit trail)
- **ADR-009**: Event-Driven Architecture (CDC event ordering guarantees)
- **Runbook**: cdc-consumer-service/runbook.md
- **Integration Guide**: docs/patterns/debezium-cdc.md
- **Reconciliation Service**: docs/services/reconciliation-engine/README.md
