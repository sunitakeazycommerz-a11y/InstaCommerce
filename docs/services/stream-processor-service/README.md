# Stream Processor Service

## Overview

The stream-processor-service is a high-throughput, event-driven processing engine built on Kafka Streams. It processes event streams in real-time, performs stateful windowing aggregations, deduplicates events, and maintains materialized state views for analytics and alerting. This service powers real-time metrics, fraud detection, and inventory updates.

**Service Ownership**: Platform Team - Data Streaming
**Language**: Go 1.22
**Default Port**: 8085
**Status**: Tier 2 Critical (Real-time analytics backbone)

## SLOs & Availability

- **Availability SLO**: 99.9% (43 minutes downtime/month)
- **P99 Latency**: < 5 seconds per event (end-to-end processing)
- **Error Rate**: < 0.1% (event drops due to processing failure)
- **Max Throughput**: 50,000 events/second (across all topics)

## Key Responsibilities

1. **Event Stream Processing**: Consume from multiple Kafka topics (orders, payments, inventory)
2. **Stateful Aggregation**: Windowed operations (5-min, 1-hour windows) for metrics calculation
3. **Deduplication**: Detect and filter duplicate events using idempotency keys (Redis cache)
4. **Materialized Views**: Maintain state store (PostgreSQL) for real-time query access
5. **Event Publishing**: Emit aggregated events to downstream topics for alerting/dashboards

## Deployment

### GKE Deployment
- **Namespace**: streaming
- **Replicas**: 2 (HA with Kafka Streams task rebalancing)
- **CPU Request/Limit**: 600m / 1200m
- **Memory Request/Limit**: 768Mi / 1536Mi

### Cluster Configuration
- **Ingress**: Internal only (state queries from monitoring/dashboards)
- **NetworkPolicy**: Allow from Kafka brokers, monitoring stack
- **Service Account**: stream-processor-service
- **StatefulSet**: Yes (maintains local state store for Kafka Streams)

## Stream Processing Architecture

```
┌──────────────────────────────────────────────────────────────────┐
│ Kafka Cluster                                                     │
├────────────┬────────────┬────────────┬────────────┬───────────────┤
│ orders     │ payments   │ inventory  │ shipments  │ fraud-scores  │
│ (100/s)    │ (50/s)     │ (20/s)     │ (10/s)     │ (external)    │
└────────────┼────────────┼────────────┼────────────┴───────────────┘
             │
             │ Kafka Streams Topology
             │
    ┌────────▼──────────────────────────────────────┐
    │ Stream-Processor-Service (Go)                 │
    │ ├─ Deduplication Layer                        │
    │ │  └─ Redis: idempotency key cache (5 min TTL)│
    │ ├─ Windowing Engine                          │
    │ │  ├─ Tumbling windows (1 hour)               │
    │ │  ├─ Session windows (5 min gap)             │
    │ │  └─ State Store (PostgreSQL)                │
    │ ├─ Aggregation Logic                         │
    │ │  ├─ Order metrics (count, value by hour)    │
    │ │  ├─ Payment metrics (success rate, latency) │
    │ │  └─ Inventory delta tracking                │
    │ └─ Event Publishing                          │
    │    └─ Output topics (metrics, alerts)         │
    └────────┬────────────────────────────────────┘
             │
    ┌────────▼──────────────────────────┐
    │ Output Topics                     │
    ├─ metrics-aggregated (1min window) │
    ├─ alerts-triggered                 │
    ├─ inventory-changes                │
    └──────────────────────────────────┘
```

### Processing Pipeline

1. **Consume**: Read from source topics (orders, payments, inventory)
2. **Deserialize**: JSON → Go structs using encoding/json
3. **Deduplicate**: Check Redis for idempotency key; skip if present
4. **Enrich**: Lookup product/merchant metadata from state store
5. **Aggregate**: Apply windowing logic (count, sum, avg)
6. **Store State**: Persist aggregated state to PostgreSQL
7. **Publish**: Emit results to output topics
8. **Track Lag**: Publish consumer lag metrics

## Integrations

### Kafka Topics
| Topic | Consumers | Partition | Purpose | Retention |
|-------|-----------|-----------|---------|-----------|
| orders | stream-processor | 10 | Order events | 24 hours |
| payments | stream-processor | 10 | Payment completion | 24 hours |
| inventory | stream-processor | 5 | Inventory updates | 24 hours |
| metrics-aggregated | grafana | 3 | 1-min aggregated metrics | 7 days |
| alerts-triggered | alerting-service | 3 | Alert events | 48 hours |

### State Stores
- **PostgreSQL**: Materialized aggregation state (orders_1h_agg, payments_1h_agg tables)
- **Redis**: Deduplication cache (5-min TTL per idempotency key)
- **Local RocksDB**: Kafka Streams internal state store (compacted, replicated to brokers)

## Endpoints

### Public (Unauthenticated)
- `GET /health` - Liveness probe
- `GET /metrics` - Prometheus metrics (Prometheus format)

### State Query API (Requires JWT)
- `GET /v1/stream/state/order-metrics?window=1h&from=2024-01-01T00:00:00Z` - Query aggregated order metrics
- `GET /v1/stream/state/payment-metrics?window=1h` - Query payment metrics for last hour
- `GET /v1/stream/state/inventory-delta` - Current inventory deltas by warehouse
- `GET /v1/stream/lag` - Consumer lag for all topics

### Example Requests

```bash
# Get order metrics for past hour
curl -X GET 'http://stream-processor:8085/v1/stream/state/order-metrics?window=1h' \
  -H 'Authorization: Bearer <jwt_token>'

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
