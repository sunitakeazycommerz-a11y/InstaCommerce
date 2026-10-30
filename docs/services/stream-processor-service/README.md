# Stream Processor Service

## Overview

The stream-processor-service is a high-throughput, event-driven processing service written in Go. It consumes domain events across orders, payments, riders, and inventory, computes real-time sliding-window aggregations, and dual-writes results to Redis (for operational dashboards) and Prometheus (for SLA monitoring and alerting).

**Service Ownership**: Platform Team - Data Streaming
**Language**: Go 1.25.0
**Default Port**: 8108
**Status**: Tier 2 Critical (Real-time analytics backbone)

## SLOs & Availability

- **Availability SLO**: 99.9% (43 minutes downtime/month)
- **P99 Latency**: < 500ms sliding-window update latency
- **Error Rate**: < 0.1% event processing failure
- **Max Throughput**: 50,000 events/second (across all partitions)

## Key Responsibilities

1. **Event Stream Processing**: Consume from 7 Kafka topics (`orders.events`, `payments.events`, `rider.events`, `rider.location.updates`, `inventory.events`).
2. **Stateful Windowed Aggregations**: Maintain sliding windows in memory and write operational counters/gauges.
3. **Redis Real-Time Aggregates**: Dual-write TTL-bounded metric hashes and sets to Redis.
4. **Prometheus Metrics**: Expose SLA compliance and delivery performance metrics on `/metrics`.

## Deployment

### GKE Deployment
- **Namespace**: instacommerce
- **Replicas**: 2 (HA stateless deployment)
- **CPU Request/Limit**: 500m / 1000m
- **Memory Request/Limit**: 512Mi / 1024Mi

### Cluster Configuration
- **Ingress**: Internal only
- **NetworkPolicy**: Allow egress to Kafka brokers and Redis
- **Service Account**: stream-processor-service

## Stream Processing Architecture

```
┌──────────────────────────────────────────────────────────────────┐
│ Kafka Topics                                                     │
├────────────┬────────────┬────────────┬────────────┬──────────────┤
│ orders     │ payments   │ riders     │ rider.loc  │ inventory    │
└────────────┼────────────┼────────────┼────────────┴──────────────┘
             │
             │ Native Go Kafka Consumer (segmentio/kafka-go)
             ▼
┌──────────────────────────────────────────────────────────────────┐
│ Stream-Processor-Service (Port 8108)                             │
│ ├─ Event Deserialization                                         │
│ ├─ Sliding Window Aggregations (Order velocity, Delivery SLA)   │
│ ├─ Dual-Write Pipeline                                           │
└────────────┬─────────────────────────────────┬───────────────────┘
             │                                 │
             ▼                                 ▼
┌───────────────────────────┐     ┌────────────────────────────┐
│ Redis Operational Cache   │     │ Prometheus Metrics Scrape  │
│ • TTL-bounded counters    │     │ • :8108/metrics            │
│ • Zone SLA compliance     │     │ • Alertmanager evaluation  │
└───────────────────────────┘     └────────────────────────────┘
```

## Integrations

### Kafka Topics
| Topic | Purpose |
|-------|---------|
| `order.events` / `orders.events` | Order lifecycle velocity |
| `payment.events` / `payments.events` | Payment completion rate |
| `rider.events` | Rider status and idle duration |
| `rider.location.updates` | Real-time GPS stream |
| `inventory.events` | Stock depletion tracking |

### Downstream Sinks
- **Redis**: Fast operational key-value and geo aggregations for frontend BFFs.
- **Prometheus**: Scrapes `/metrics` for SLA alerts.

## Endpoints

### Health & Metrics (Unauthenticated)
- `GET /health/live` - Liveness probe
- `GET /health/ready` - Readiness probe (checks Kafka and Redis connectivity)
- `GET /metrics` - Prometheus metrics

### Example Requests

```bash
# Check stream processor health
curl -s http://localhost:8108/health/ready | jq .

# Scrape metrics
curl -s http://localhost:8108/metrics | grep stream_processor_
```

# Response:
# {
#   "metrics": [
#     {
#       "window_start": "2024-01-01T01:00:00Z",
#       "window_end": "2024-01-01T02:00:00Z",
#       "order_count": 1250,
#       "order_value_sum": 45678.50,
#       "avg_order_value": 36.54
#     }
#   ]
# }

# Check consumer lag
curl -X GET 'http://stream-processor:8085/v1/stream/lag' \
  -H 'Authorization: Bearer <jwt_token>'

# Get inventory deltas
curl -X GET 'http://stream-processor:8085/v1/stream/state/inventory-delta' \
  -H 'Authorization: Bearer <jwt_token>'
```

## Configuration

### Environment Variables
```env
SERVER_PORT=8085
KAFKA_BROKERS=kafka1:9092,kafka2:9092,kafka3:9092
KAFKA_CONSUMER_GROUP=stream-processor-v1
KAFKA_TOPICS=orders,payments,inventory,shipments
POSTGRES_URL=postgres://processor_user:pass@postgres:5432/stream_processor
POSTGRES_POOL_SIZE=20
REDIS_HOST=redis
REDIS_PORT=6379
REDIS_PASSWORD=<redis_password>
DEDUP_CACHE_TTL_SECONDS=300
WINDOW_SIZE_MINUTES=60
OTEL_EXPORTER_OTLP_ENDPOINT=http://otel-collector:4317
LOG_LEVEL=info
```

### config.yaml
```yaml
kafka:
  brokers:
    - ${KAFKA_BROKERS}
  consumer-group: ${KAFKA_CONSUMER_GROUP}
  session-timeout-ms: 30000
  heartbeat-interval-ms: 10000
  topics:
    - name: orders
      partitions: 10
    - name: payments
      partitions: 10
    - name: inventory
      partitions: 5

stream:
  deduplication-enabled: true
  dedup-cache-ttl-seconds: ${DEDUP_CACHE_TTL_SECONDS:300}
  window-size-minutes: ${WINDOW_SIZE_MINUTES:60}
  parallelism: 4

postgres:
  url: ${POSTGRES_URL}
  pool-size: ${POSTGRES_POOL_SIZE:20}
  state-tables:
    - orders_1h_agg
    - payments_1h_agg
    - inventory_state
```

## Monitoring & Alerts

### Key Metrics
- `kafka_stream_records_consumed_total` (counter) - Events consumed by topic
- `kafka_stream_records_processed_total` (counter) - Events successfully processed
- `kafka_stream_processing_error_total` (counter) - Processing failures
- `kafka_stream_dedup_cache_hit_ratio` (gauge) - Deduplication effectiveness
- `kafka_stream_window_latency_seconds` (histogram) - Time to output windowed aggregate
- `kafka_consumer_lag_seconds` (gauge) - Lag behind latest offset
- `postgres_state_store_rows` (gauge) - Size of materialized state tables

### Alerting Rules
- `kafka_stream_lag_seconds > 60` - Processing falling behind (backlog building)
- `kafka_stream_processing_error_rate > 0.1%` - Error rate above SLO
- `kafka_stream_dedup_hit_ratio < 10%` - Low deduplication effectiveness (check Redis)
- `kafka_stream_window_latency_p99 > 10s` - Windowing too slow (may need scaling)
- `postgres_state_store_size_bytes > 50GB` - State store growth (compaction may be needed)

### Logging
- **ERROR**: Processing failures (malformed events, database errors)
- **WARN**: High lag detection, deduplication misses, state store growth
- **INFO**: Windowing completion, lag tracking, periodic health check

## Security Considerations

### Threat Mitigations
1. **Deduplication via Idempotency Keys**: Prevents double-processing of events (reduces data corruption)
2. **State Encryption**: PostgreSQL state store encrypted at-rest (GKE secret management)
3. **Consumer Group Authorization**: Kafka ACLs restrict stream-processor to allowed topics
4. **Event Validation**: Deserialize + validate events; drop malformed data (prevent injection)
5. **Audit Logging**: All state mutations logged with timestamp, user context

### Known Risks
- **Redis Cache Poisoning**: Compromised Redis could cause deduplication bypass (duplicate processing)
- **PostgreSQL State Corruption**: Database errors could cause inconsistent aggregations
- **Consumer Lag Exploitation**: If lag grows too large, duplicate events might reprocess (edge case)
- **Kafka Topic ACLs**: Misconfiguration could allow unauthorized consumers

## Troubleshooting

### Consumer Lag Growing
1. **Check processing speed**: Monitor `kafka_stream_window_latency_p99` (should be <5s)
2. **Verify partitions**: Run `kafka-topics --describe | grep orders` confirm partition count
3. **Check PostgreSQL**: Verify state store writes are fast (`EXPLAIN ANALYZE UPDATE orders_1h_agg`)
4. **Scale replicas**: If CPU/memory high, increase replicas in Deployment spec

### Deduplication Not Working
1. **Redis connectivity**: `redis-cli -h redis PING` verify cache available
2. **TTL configuration**: Confirm `DEDUP_CACHE_TTL_SECONDS` matches event batch interval
3. **Check cache hit ratio**: Monitor `kafka_stream_dedup_cache_hit_ratio` metric
4. **Review logs**: Check for Redis connection errors (ERROR level)

### State Store Growing Too Large
1. **Enable compaction**: Verify PostgreSQL table has TTL-based cleanup (DELETE old rows)
2. **Review aggregate logic**: Check if aggregations are properly windowing (not accumulating all-time)
3. **Check table size**: `SELECT pg_size_pretty(pg_total_relation_size('orders_1h_agg'))` in PostgreSQL
4. **Archive old data**: Move data older than 30 days to data warehouse

## Related Documentation

- **Runbook**: stream-processor-service/runbook.md
- **Kafka Topology**: Detailed stream topology diagram
- **State Schema**: PostgreSQL schema for materialized state tables
