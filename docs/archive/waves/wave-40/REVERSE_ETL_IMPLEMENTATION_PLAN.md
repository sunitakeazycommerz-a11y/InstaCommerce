# Wave 40 Phase 5: Reverse ETL Orchestrator Implementation Plan

## Overview
Implement a production-grade Reverse ETL orchestrator service that consumes CDC events from PostgreSQL ledgers and pushes transactional data to 3 external systems in real-time.

## Timeline
- **Start**: April 14, 2026
- **End**: April 27, 2026 (14 days)
- **Parallel**: Running alongside Austin gated traffic increase (5%)

## Architecture

### System Components

```
PostgreSQL Ledgers (CDC Topics)
    ↓
┌─────────────────────────────────────────┐
│   Reverse ETL Orchestrator Service      │
│                                          │
│  ┌──────────────────────────────────┐   │
│  │  Kafka Consumer                   │   │
│  │  - partition assignment           │   │
│  │  - offset management              │   │
│  │  - checkpoint tracking            │   │
│  └──────────────────────────────────┘   │
│           ↓                               │
│  ┌──────────────────────────────────┐   │
│  │  Transformation Engine            │   │
│  │  - SQL-like DSL                   │   │
│  │  - Field mapping                  │   │
│  │  - PII redaction                  │   │
│  │  - Event filtering                │   │
│  └──────────────────────────────────┘   │
│           ↓                               │
│  ┌──────────────────────────────────┐   │
│  │  Sink Router                      │   │
│  │  - route by event type            │   │
│  │  - idempotency key generation     │   │
│  │  - circuit breaker logic          │   │
│  └──────────────────────────────────┘   │
│      ↙         ↓         ↘               │
└─────────────────────────────────────────┘
   ↓           ↓           ↓
[Warehouse]  [Marketing]  [Analytics]
(15s batch)  (sub-sec)    (5m agg)
```

### CDC Topics (Input)
- `payment_ledger.cdc` - Payment transactions
- `order_ledger.cdc` - Order events
- `fulfillment_ledger.cdc` - Fulfillment updates

### Sink Destinations (Output)

#### 1. Data Warehouse (Snowflake/BigQuery)
- **Type**: Batch (15-second micro-batches)
- **Schema**:
  - `dim_payments`: Payment details with timestamp
  - `dim_orders`: Order details with status
  - `dim_fulfillments`: Fulfillment tracking
  - `fact_transactions`: Transactional facts
- **Error Handling**: Dead-letter queue for schema violations
- **Consistency**: Exactly-once delivery with deduplication

#### 2. Marketing Platform (Segment/mParticle)
- **Type**: Real-time (sub-second)
- **Events**:
  - `order.created` - New orders
  - `payment.confirmed` - Payment success
  - `fulfillment.updated` - Shipment updates
- **Privacy**: PII redaction (email, phone, name masked)
- **Retry**: Exponential backoff (3 attempts, 2s base, 5s max)

#### 3. Analytics System (Elasticsearch/Analytics DB)
- **Type**: Aggregated (5-minute buckets)
- **Indices**:
  - `orders_v1`: Hourly order counts, success rates
  - `payments_v1`: Payment metrics by status
  - `fulfillments_v1`: Fulfillment SLO tracking
- **Retention**: 90 days rolling
- **Availability**: Graceful degradation if cluster down

## Implementation Roadmap

### Week 1 (Apr 14-20)

#### Day 1-2: Kafka Consumer + Checkpoint System
- [x] Kafka consumer group management
- [x] Partition assignment strategy
- [x] Offset checkpointing to PostgreSQL
- [x] Exactly-once delivery guarantee
- [x] Circuit breaker for broker failures
- **Deliverable**: `internal/kafka/consumer.go`, `internal/kafka/checkpoint.go`

#### Day 3-4: Transformation Engine
- [x] DSL parser (SQL-like: SELECT, WHERE, RENAME)
- [x] Field mapping logic
- [x] Type conversions
- [x] PII redaction filters
- **Deliverable**: `internal/transform/engine.go`, `internal/transform/dsl.go`

#### Day 5: Error Handling & Circuit Breaker
- [x] Circuit breaker implementation
- [x] Retry logic with exponential backoff
- [x] Dead-letter queue routing
- [x] Error metrics & alerting
- **Deliverable**: `internal/error/circuit_breaker.go`, `internal/error/handler.go`

### Week 2 (Apr 21-27)

#### Day 1-2: Sink Implementations
- [x] Warehouse sink (Snowflake/BigQuery connector)
- [x] Marketing sink (Segment HTTP API)
- [x] Analytics sink (Elasticsearch bulk indexing)
- **Deliverable**: `internal/sink/warehouse.go`, `internal/sink/marketing.go`, `internal/sink/analytics.go`

#### Day 3: Orchestrator & Event Router
- [x] Main orchestrator logic
- [x] Event routing by subscription
- [x] Idempotency key generation
- [x] Monitoring & observability
- **Deliverable**: `internal/processor/orchestrator.go`

#### Day 4: Integration Tests
- [x] End-to-end tests (mock Kafka, mock sinks)
- [x] Exactly-once semantic tests
- [x] Circuit breaker failure scenarios
- [x] Performance tests (100K events/sec load)
- **Deliverable**: `internal/processor/orchestrator_test.go`

#### Day 5: Deployment & Documentation
- [x] Kubernetes manifests (3 replicas, resource limits)
- [x] Sink configuration YAML files
- [x] Monitoring dashboards
- [x] Runbooks & troubleshooting guides
- **Deliverable**: `k8s/`, `docs/reverse-etl/`, `docs/observability/`

## Core Components to Build

### 1. Kafka Consumer (`internal/kafka/consumer.go`)
```go
type KafkaConsumer struct {
    brokers       []string
    consumerGroup string
    topics        []string
    checkpoint    CheckpointManager
    circuitBreaker *CircuitBreaker
    metrics       *KafkaMetrics
}

func (c *KafkaConsumer) Start(ctx context.Context) error
func (c *KafkaConsumer) Consume(ctx context.Context) <-chan *DebeziumEnvelope
func (c *KafkaConsumer) CommitOffset(topic string, partition int32, offset int64) error
func (c *KafkaConsumer) GetConsumerLag() map[string]int64
```

### 2. Checkpoint Manager (`internal/kafka/checkpoint.go`)
```go
type CheckpointManager struct {
    db *sql.DB
}

func (cm *CheckpointManager) SaveCheckpoint(ctx context.Context, data CheckpointData) error
func (cm *CheckpointManager) GetCheckpoint(ctx context.Context, topic string, partition int32) (int64, error)
func (cm *CheckpointManager) InitializeCheckpoints(ctx context.Context, topic string) error
```

### 3. Transformation Engine (`internal/transform/engine.go`)
```go
type TransformationEngine struct {
    dslParser *DSLParser
    rules     map[string]TransformRule
    piiFilter *PIIFilter
}

func (te *TransformationEngine) Transform(ctx context.Context, envelope *DebeziumEnvelope) (*TransformedEvent, error)
func (te *TransformationEngine) ValidateSchema(data map[string]interface{}, schema string) error
```

### 4. Sink Interface & Implementations
```go
type Sink interface {
    Write(ctx context.Context, event *TransformedEvent) *SinkResult
    Validate() error
    HealthCheck(ctx context.Context) error
    GetMetrics() SinkMetrics
}

type WarehouseSink struct { /* Snowflake/BigQuery */ }
type MarketingSink struct { /* Segment API */ }
type AnalyticsSink struct { /* Elasticsearch */ }
```

### 5. Circuit Breaker (`internal/error/circuit_breaker.go`)
```go
type CircuitBreaker struct {
    failureThreshold int
    resetTimeout     time.Duration
    halfOpenRequests int
    state            string // closed, open, half-open
}

func (cb *CircuitBreaker) Execute(fn func() error) error
func (cb *CircuitBreaker) GetMetrics() CircuitBreakerMetrics
```

## Metrics & Observability

### Prometheus Metrics
- `reverse_etl_events_consumed_total` - Counter (by topic, partition)
- `reverse_etl_transformation_duration_seconds` - Histogram (by event_type)
- `reverse_etl_sink_write_duration_seconds` - Histogram (by sink_type)
- `reverse_etl_sink_errors_total` - Counter (by sink_type, error_code)
- `reverse_etl_circuit_breaker_state` - Gauge (by sink_type)
- `reverse_etl_kafka_lag_bytes` - Gauge (by topic, partition)
- `reverse_etl_idempotency_cache_hits_total` - Counter
- `reverse_etl_dlq_events_total` - Counter (by reason)

### OpenTelemetry Tracing
- `reverse-etl.consumer` - Kafka consumption span
- `reverse-etl.transform` - Transformation span
- `reverse-etl.sink.write` - Sink write span
- `reverse-etl.sink.retry` - Retry attempt span

### Grafana Dashboards
1. **Overview Dashboard** - Consumer lag, throughput, error rates
2. **Per-Sink Dashboard** - Latency, error rates, circuit breaker state
3. **Data Quality Dashboard** - DLQ rates, schema violations, deduplication rate
4. **Performance Dashboard** - p50/p95/p99 latencies, throughput trends

## Database Schema (PostgreSQL)

```sql
-- Checkpoints for exactly-once semantics
CREATE TABLE reverse_etl_checkpoints (
    topic VARCHAR(255) NOT NULL,
    partition INT NOT NULL,
    committed_offset BIGINT NOT NULL,
    last_committed_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (topic, partition)
);

-- Idempotency cache for deduplication
CREATE TABLE reverse_etl_idempotency (
    idempotency_key VARCHAR(255) PRIMARY KEY,
    event_id UUID NOT NULL,
    sink_type VARCHAR(50) NOT NULL,
    result JSONB NOT NULL,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    INDEX idx_created_at (created_at)
);

-- Dead-letter queue
CREATE TABLE reverse_etl_dlq (
    id UUID PRIMARY KEY,
    event_id UUID NOT NULL,
    sink_type VARCHAR(50) NOT NULL,
    reason VARCHAR(500) NOT NULL,
    event_data JSONB NOT NULL,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    resolved_at TIMESTAMP,
    INDEX idx_created_at (created_at),
    INDEX idx_resolved (resolved_at)
);

-- Sink audit log
CREATE TABLE reverse_etl_sink_audit (
    id UUID PRIMARY KEY,
    event_id UUID NOT NULL,
    sink_type VARCHAR(50) NOT NULL,
    status VARCHAR(20) NOT NULL, -- success, failure, retry
    latency_ms INT NOT NULL,
    retry_count INT DEFAULT 0,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    INDEX idx_created_at (created_at)
);
```

## Deployment Strategy

### Kubernetes Deployment
- **Replicas**: 3 (for high availability)
- **Resource Limits**:
  - CPU: 2 cores (request: 500m)
  - Memory: 2Gi (request: 512Mi)
  - Storage: 10Gi (for local checkpoint cache)
- **Liveness Probe**: /health/live (10s initial, 5s interval)
- **Readiness Probe**: /health/ready (20s initial, 10s interval)
- **Pod Disruption Budget**: min 2 replicas available

### Environment Variables
```bash
KAFKA_BROKERS=kafka-broker-1:9092,kafka-broker-2:9092,kafka-broker-3:9092
KAFKA_CONSUMER_GROUP=reverse-etl-orchestrator-prod
KAFKA_TOPICS=payment_ledger.cdc,order_ledger.cdc,fulfillment_ledger.cdc

DATABASE_URL=postgres://user:pass@pg-primary:5432/reverse_etl?sslmode=require
DATABASE_MAX_CONN=50

WAREHOUSE_TYPE=snowflake
WAREHOUSE_ACCOUNT=account-id
WAREHOUSE_USER=etl_user
WAREHOUSE_PASSWORD=<secret>

MARKETING_PLATFORM=segment
MARKETING_API_KEY=<secret>
MARKETING_BATCH_SIZE=1000
MARKETING_BATCH_TIMEOUT_MS=5000

ANALYTICS_TYPE=elasticsearch
ANALYTICS_HOSTS=es-node1:9200,es-node2:9200,es-node3:9200

OTEL_EXPORTER_OTLP_ENDPOINT=http://otel-collector:4318
SERVICE_VERSION=1.0.0
ENVIRONMENT=production
```

## Testing Strategy

### Unit Tests
- Transformation DSL parser (50+ test cases)
- PII redaction filters (30+ test cases)
- Circuit breaker state transitions (25+ test cases)

### Integration Tests
- End-to-end consumer → transform → sink (10+ scenarios)
- Exactly-once semantics verification (5+ scenarios)
- Circuit breaker failover (8+ scenarios)
- Performance tests: 100K events/sec throughput

### Load Tests
- Kafka lag tracking under load
- Sink latency distribution (p50, p95, p99)
- Memory usage stability over 24 hours
- Connection pool behavior under spike load

## Success Criteria

1. **Latency**: p99 < 5 seconds end-to-end
2. **Throughput**: ≥ 100K events/sec per instance
3. **Availability**: 99.9% uptime across 3 replicas
4. **Delivery**: Exactly-once semantics verified
5. **Data Quality**: Zero duplicate transactions in warehouse
6. **Monitoring**: 15+ Prometheus metrics, 4 Grafana dashboards
7. **Documentation**: Runbooks, troubleshooting guides, ADRs
8. **Error Handling**: <0.1% DLQ rate, circuit breaker resilience

## Risk Mitigation

| Risk | Mitigation |
|------|-----------|
| Kafka broker failure | Circuit breaker + graceful degradation |
| Sink unavailability | Retry logic + dead-letter queue |
| Schema drift | Schema validation before transform |
| Duplicate events | Idempotency key + deduplication cache |
| Consumer lag | Offset checkpointing, partition rebalancing |
| PII leakage | Redaction filters, field masking |
| Memory leak | Connection pooling, cache eviction |
| Clock skew | NTP sync, timestamp validation |

## Deliverables Summary

### Code
- ✅ Kafka consumer with checkpoint system
- ✅ Transformation engine with DSL
- ✅ 3 sink implementations
- ✅ Circuit breaker & error handling
- ✅ Integration tests (15+)
- ✅ Configuration management

### Infrastructure
- ✅ Kubernetes deployment manifests
- ✅ Terraform sink configuration
- ✅ Database migrations (Flyway V1-V3)
- ✅ ConfigMap for sink definitions

### Observability
- ✅ Prometheus metrics (15+)
- ✅ OpenTelemetry tracing
- ✅ 4 Grafana dashboards
- ✅ Alert rules (20+)

### Documentation
- ✅ Architecture diagrams (3 + E2E)
- ✅ Runbooks (5: setup, troubleshooting, scaling, monitoring, failover)
- ✅ ADR: Reverse ETL orchestrator design
- ✅ API documentation (swagger)
- ✅ Sink configuration guide
