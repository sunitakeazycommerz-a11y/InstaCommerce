# Outbox-Relay Service

## Overview

The outbox-relay-service is a CDC (Change Data Capture) alternative that polls the transactional outbox pattern table from services without native Debezium support. It provides exactly-once event delivery semantics to Kafka, ensuring no duplicate or lost events across service boundaries.

**Service Ownership**: Platform Team - Event Infrastructure Tier 2
**Language**: Go 1.22
**Default Port**: 8087
**Status**: Tier 2 Critical (Event delivery)

## SLOs & Availability

- **Availability SLO**: 99.9% (43 minutes downtime/month)
- **P95 Relay Latency**: < 500ms (from outbox table to Kafka)
- **P99 Relay Latency**: < 1s
- **Exactly-Once Delivery**: 100% (no duplicates or lost events)
- **Max Throughput**: 10,000 events/second (per batch)

## Key Responsibilities

1. **Outbox Table Polling**: Periodic scans (100ms interval) of `outbox` table across registered services
2. **Event Relay**: Publish outbox events to Kafka topics with partition key routing
3. **Exactly-Once Semantics**: Idempotent processing via offset tracking and outbox deletion atomicity
4. **Failure Recovery**: Resume polling from last committed offset in case of crashes

## Deployment

### GKE Deployment
- **Namespace**: event-infrastructure
- **Replicas**: 2 (HA with distributed polling)
- **CPU Request/Limit**: 500m / 1000m
- **Memory Request/Limit**: 256Mi / 512Mi

### Cluster Configuration
- **Ingress**: Internal-only (behind Istio IngressGateway)
- **NetworkPolicy**: Allow egress to PostgreSQL and Kafka clusters only
- **Service Account**: outbox-relay-service

## Outbox Pattern Architecture

```
┌────────────────────────────────────────────────────────────────┐
│ Domain Service (e.g., order-service, payment-service)         │
└─────────────────────────────┬────────────────────────────────┘
                              │
              1. Transactional Write
              (within service transaction)
                              │
          ┌─────────────────▼──────────────────┐
          │  PostgreSQL (Service Database)     │
          │  ┌──────────────────────────────┐  │
          │  │ Core Domain Table            │  │
          │  │ order_id: 123                │  │
          │  └──────────────────────────────┘  │
          │  ┌──────────────────────────────┐  │
          │  │ Outbox Table                 │  │
          │  │ event_id: uuid               │  │
          │  │ payload: {"type": "..."}     │  │
          │  │ published: false              │  │
          │  └──────────────────────────────┘  │
          └──────────────────────────────────┘
                              │
              2. Polling Scan (100ms interval)
                              │
          ┌─────────────────▼──────────────────┐
          │  Outbox-Relay-Service               │
          │  • Poll unpublished events          │
          │  • Validate partition keys          │
          │  • Batch send to Kafka              │
          │  • Mark published = true            │
          └──────────────────┬──────────────────┘
                              │
              3. Exactly-Once Delivery
                              │
                  ┌───────────▼──────────┐
                  │  Kafka (Event Stream)│
                  │  Topic: order-events │
                  │  [Partition 0, 1, 2]│
                  └───────────┬──────────┘
                              │
              4. Consumption (by multiple services)
                              │
          ┌─────────────────▼──────────────────┐
          │  Downstream Consumers               │
          │  • fulfillment-service              │
          │  • notification-service             │
          │  • analytics-service                │
          └──────────────────────────────────┘
```

### Idempotency Guarantee

1. **Publish Atomically**: Within database transaction, set `published=true` only after Kafka ACK
2. **Offset Tracking**: Consumer group offset committed only after processing completes
3. **Retry on Failure**: Exponential backoff (100ms, 200ms, 400ms, 800ms, 1.6s max) with jitter
4. **Dead-Letter Queue**: Events failing after 5 retries go to `outbox-dlq` topic

## Integrations

### Synchronous Calls
| Service | Endpoint | Timeout | Purpose |
|---------|----------|---------|---------|
| PostgreSQL (multi-tenanted) | jdbc:postgresql://pg:5432 | 5s | Poll outbox tables |
| Kafka Brokers | broker:9092 | 10s | Publish events |

### Configuration Services
- **Config-Feature-Flag-Service**: Feature flags for batch size, polling interval (real-time override via Redis pub/sub)

## Endpoints

### Health & Metrics (Unauthenticated)
- `GET /health` - Liveness probe (checks Kafka + PostgreSQL connectivity)
- `GET /metrics` - Prometheus metrics (events polled, published, failed)
- `GET /status` - Relay status (polling lag, error count)

### Admin API (Service-to-Service Token Required)
- `POST /relay/skip/{service_name}` - Temporarily skip polling a service (TTL-based)
- `GET /relay/status/{service_name}` - Detailed status for a service

### Example Requests

```bash
# Check outbox-relay health
curl -s http://outbox-relay-service:8087/health | jq .

# Check relay metrics
curl -s http://outbox-relay-service:8087/metrics | grep outbox_relay

# Get per-service relay status
curl -s http://outbox-relay-service:8087/status | jq '.services[] | {name, lag_ms, errors}'

# Skip a service temporarily (600 seconds)
curl -X POST http://outbox-relay-service:8087/relay/skip/order-service \
  -H "Authorization: Bearer $SERVICE_TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"ttlSeconds":600}'
```

## Configuration

### Environment Variables
```env
SERVER_PORT=8087
POSTGRES_URL=postgresql://user:pass@pg.default.svc.cluster.local:5432/instacommerce
KAFKA_BROKERS=kafka-0.kafka.event-infrastructure:9092,kafka-1.kafka.event-infrastructure:9092
POLLING_INTERVAL_MS=100
BATCH_SIZE=1000
MAX_RETRIES=5
RETRY_BACKOFF_MS=100
OTEL_EXPORTER_OTLP_TRACES_ENDPOINT=http://otel-collector.monitoring:4318/v1/traces
LOG_LEVEL=info
```

### config.yaml
```yaml
outbox-relay:
  polling:
    interval-ms: ${POLLING_INTERVAL_MS:100}
    batch-size: ${BATCH_SIZE:1000}
    timeout-ms: 5000
  kafka:
    brokers: ${KAFKA_BROKERS}
    retries: ${MAX_RETRIES:5}
    retry-backoff-ms: ${RETRY_BACKOFF_MS:100}
  services:
    - name: order-service
      table: public.outbox
      topic: order.events
      partition-key: order_id
    - name: payment-service
      table: public.outbox
      topic: payment.events
      partition-key: payment_id
    - name: fulfillment-service
      table: public.outbox
      topic: fulfillment.events
      partition-key: fulfillment_id
```

## Monitoring & Alerts

### Key Metrics
- `outbox_relay_events_polled_total` (counter) - Events polled from outbox
- `outbox_relay_events_published_total` (counter) - Events successfully published to Kafka
- `outbox_relay_events_failed_total` (counter) - Events failed after max retries
- `outbox_relay_polling_lag_ms` (gauge) - Lag between outbox write and relay publish
- `outbox_relay_batch_duration_seconds` (histogram) - Time to poll and publish batch
- `kafka_producer_record_error_total` (counter) - Kafka producer errors

### Alerting Rules
- `outbox_relay_events_failed_total > 100/min` - High failure rate (possible downstream issue)
- `outbox_relay_polling_lag_ms > 5000` - Excessive relay latency (polling slow or Kafka backpressure)
- `outbox_relay_postgres_connection_errors_total > 5/min` - Database connectivity issues
- `outbox_relay_kafka_producer_errors_total > 10/min` - Kafka broker issues

### Logging
- **WARN**: Polling timeout, batch retry, dead-letter queue entries
- **INFO**: Batch published (count, latency), service polling started/stopped
- **ERROR**: Database connection failures, Kafka broker unreachable, unknown payload formats

## Security Considerations

### Threat Mitigations
1. **Internal Service Token**: Per-service authentication prevents unauthorized relay operations
2. **Partition Key Validation**: Ensures events routed to correct Kafka partition (no data leakage)
3. **Payload Encryption**: Events encrypted at rest in PostgreSQL (via transparent encryption)
4. **Audit Logging**: All relay actions logged to centralized audit trail

### Known Risks
- **Compromised PostgreSQL**: Attacker can inject malicious events into outbox table
- **Kafka credentials theft**: Credentials allow publishing to any topic (mitigated by ACLs)
- **Polling timeout**: Misconfigured services can accumulate outbox backlog (monitor lag SLO)

## Troubleshooting

### High Relay Latency
1. **Check polling interval**: `POLLING_INTERVAL_MS` too large (increase frequency or reduce batch size)
2. **Check batch size**: `BATCH_SIZE` too large (Kafka compression overhead, reduce batch)
3. **Monitor Kafka lag**: `kafka_consumer_lag_seconds` indicates consumer group delay
4. **Check database**: `EXPLAIN ANALYZE SELECT * FROM outbox WHERE published=false` on slow services

### Events Not Published (Lost Events)
1. **Verify outbox table exists**: `SELECT COUNT(*) FROM public.outbox` on service database
2. **Check permissions**: Service account must have INSERT on outbox (DDL verification)
3. **Verify Kafka connectivity**: `kafka-broker-api-versions.sh --bootstrap-server kafka:9092`
4. **Review dead-letter queue**: `kafka-console-consumer.sh --topic outbox-dlq --from-beginning`

### Duplicate Events (Idempotency Failure)
1. **Check consumer group offset**: Verify consumer group offset is not reset
2. **Monitor retry count**: High `outbox_relay_batch_retry_count` indicates processing failures
3. **Verify partition key**: Ensure partition key uniqueness (no hash collisions)

## Related Documentation

- **ADR-014**: Reconciliation Authority Model (outbox pattern justification)
- **ADR-009**: Event-Driven Architecture (event semantics and ordering)
- **Runbook**: outbox-relay-service/runbook.md
- **Integration Guide**: docs/patterns/outbox-pattern.md
