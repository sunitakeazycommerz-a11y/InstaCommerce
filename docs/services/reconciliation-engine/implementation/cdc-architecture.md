# Reconciliation Engine CDC Architecture

> Wave 36 Track B: CDC Wiring for Reconciliation Engine

## Overview

The reconciliation engine uses Debezium CDC (Change Data Capture) with PostgreSQL logical decoding to capture payment ledger changes in real-time and aggregate them into daily reconciliation snapshots. This document describes the CDC architecture, event flow, and integration patterns.

## Table of Contents

1. [CDC Data Flow](#cdc-data-flow)
2. [Kafka Topics](#kafka-topics)
3. [Event Envelope](#event-envelope)
4. [CDC Consumer](#cdc-consumer)
5. [Daily Snapshot Aggregation](#daily-snapshot-aggregation)
6. [Integration with Reconciliation Job](#integration-with-reconciliation-job)
7. [Deployment Architecture](#deployment-architecture)
8. [SLO and Performance](#slo-and-performance)
9. [Monitoring and Observability](#monitoring-and-observability)
10. [Failure Handling](#failure-handling)

---

## CDC Data Flow

```
┌─────────────────────────────────────────────────────────────────┐
│ PostgreSQL Reconciliation Database (replica with logical slots) │
├─────────────────────────────────────────────────────────────────┤
│ • reconciliation_runs (daily batches)                           │
│ • reconciliation_mismatches (transaction-level results)         │
│ • reconciliation_fixes (audit trail)                            │
└────────────────────────────┬────────────────────────────────────┘
                             │ (PostgreSQL logical decoding)
                             │ (pgoutput plugin)
                             ▼
                ┌────────────────────────┐
                │ Debezium Connector     │
                │ (Kafka Connect cluster)│
                └────────────┬───────────┘
                             │ (Topic transforms)
                             ▼
        ┌────────────────────────────────────────────┐
        │ Kafka Topic: reconciliation.cdc            │
        │ • Partition 0: reconciliation_runs         │
        │ • Partition 1: reconciliation_mismatches   │
        │ • Partition 2: reconciliation_fixes        │
        └────────┬─────────────────────────┬─────────┘
                 │                         │
        ┌────────▼──────────┐    ┌────────▼──────────┐
        │ CDC Consumer      │    │ Other Consumers   │
        │ (reconciliation-  │    │ (analytics, audit)│
        │  engine)          │    └───────────────────┘
        └────────┬──────────┘
                 │ (Event aggregation)
                 ▼
        ┌────────────────────────┐
        │ Daily Snapshot         │
        │ (in-memory aggregation)│
        └────────┬───────────────┘
                 │
        ┌────────▼──────────────────┐
        │ Reconciliation Job        │
        │ (triggered by snapshot)   │
        └────────┬─────────────────┘
                 │
        ┌────────▼──────────────────┐
        │ reconciliation.events     │
        │ (mismatch, fixed, summary)│
        └──────────────────────────┘
```

---

## Kafka Topics

### Input Topic: `reconciliation.cdc`

**Purpose**: Captures all changes to reconciliation database tables

**Configuration**:
- **Partitions**: 3 (default; can be increased for throughput)
- **Replication Factor**: 2 (HA for financial data)
- **Retention**: 24 hours (sufficient for nightly batch processing)
- **Compression**: Snappy (recommended for cost/throughput balance)
- **Cleanup Policy**: Delete (not compact, as we need historical changelog)
- **Min In-Sync Replicas**: 2 (durability for financial records)

**Schema**:
Messages in `reconciliation.cdc` follow the Debezium envelope structure:

```json
{
  "payload": {
    "op": "i",
    "ts_ms": 1711000000000,
    "txid": 12345,
    "lsn": 987654321,
    "source": {
      "version": "2.4.0",
      "connector": "postgres",
      "name": "reconciliation",
      "database": "reconciliation",
      "schema": "public",
      "table": "reconciliation_runs",
      "txId": 12345,
      "lsn": 987654321
    },
    "before": null,
    "after": {
      "run_id": 1,
      "run_date": "2024-03-21",
      "status": "COMPLETED",
      "mismatch_count": 5,
      "auto_fixed_count": 3,
      "manual_review_count": 2,
      "started_at": "2024-03-21T12:00:00Z",
      "completed_at": "2024-03-21T12:05:00Z",
      "created_at": "2024-03-21T12:00:00Z"
    }
  }
}
```

### Output Topic: `reconciliation.events`

**Purpose**: Publishes reconciliation lifecycle events (mismatches, fixes, summaries)

**Existing Events**:
- `mismatch`: Individual transaction discrepancy detected
- `fixed`: Auto-fix applied successfully
- `manual_review`: Requires human review
- `summary`: Run completion summary

**New CDC Events** (emitted when snapshot ready):
- `snapshot_ready`: Daily CDC aggregation complete, ready for reconciliation
- `reconciliation_started`: Job triggered by snapshot
- `reconciliation_completed`: Results available

---

## Event Envelope

All events published to `reconciliation.events` follow the shared `EventEnvelope` contract (see `contracts/src/main/java/com/instacommerce/contracts/events/EventEnvelope.java`):

```java
public record EventEnvelope(
    String id,                 // Unique event ID
    String eventType,          // e.g., "reconciliation.mismatch"
    String aggregateType,      // "reconciliation_run"
    String aggregateId,        // run_id as string
    Instant eventTime,         // Event timestamp
    String schemaVersion,      // "1.0"
    String sourceService,      // "reconciliation-engine"
    String correlationId,      // For tracing across services
    JsonNode payload           // Event-specific data
)
```

**Example Event**:

```json
{
  "id": "evt-2024-03-21-001",
  "eventType": "reconciliation.snapshot_ready",
  "aggregateType": "reconciliation_run",
  "aggregateId": "1",
  "eventTime": "2024-03-21T12:10:00Z",
  "schemaVersion": "1.0",
  "sourceService": "reconciliation-engine",
  "correlationId": "req-2024-03-21-abc123",
  "payload": {
    "run_date": "2024-03-21",
    "mismatch_count": 5,
    "auto_fixed_count": 3,
    "manual_review_count": 2,
    "snapshot_ready_at": "2024-03-21T12:10:00Z"
  }
}
```

---

## CDC Consumer

### Architecture

The CDC consumer (`pkg/cdc/payment_ledger_consumer.go`) is responsible for:

1. **Consuming CDC Events**: Subscribes to `reconciliation.cdc` Kafka topic
2. **Parsing Debezium Envelopes**: Extracts operation type (INSERT/UPDATE/DELETE) and before/after states
3. **Aggregating by Date**: Groups events by `run_date` for daily reconciliation
4. **Storing Snapshots**: Maintains in-memory snapshots of current daily state
5. **Triggering Reconciliation**: Signals when snapshot is complete

### Configuration

**Environment Variables**:

```bash
# Enable/disable CDC consumer
CDC_ENABLED=true

# Kafka connectivity
KAFKA_BROKERS=kafka-0:9092,kafka-1:9092,kafka-2:9092
CDC_GROUP_ID=reconciliation-cdc-consumer

# Consumer settings
CDC_TOPICS=reconciliation.cdc
CDC_MIN_BYTES=10240
CDC_MAX_BYTES=10485760  # 10 MB
CDC_MAX_WAIT=5s
CDC_COMMIT_INTERVAL=10s

# Batching for performance
CDC_BATCH_SIZE=500
CDC_BATCH_TIMEOUT=5s
```

### Consumer Types

```go
type DebeziumChangeEvent struct {
    Envelope struct {
        Before map[string]interface{}  // Previous row state
        After  map[string]interface{}  // Current row state
        Source SourceMetadata           // Database metadata
        Op     string                   // Operation: i, u, d, t
        TsMs   int64                    // Timestamp
    }
}

type DailySnapshot struct {
    RunDate              string  // Date in YYYY-MM-DD format
    Transactions         map[string]*ReconciliationLedgerEntry  // By transaction_id
    Runs                 []*ReconciliationLedgerEntry           // Run-level aggregations
    TotalMismatches      int
    TotalAutoFixed       int
    TotalManualReview    int
    LastUpdate           time.Time
}

type CDCMetrics struct {
    eventsReceived       int64
    eventsProcessed      int64
    eventsFailed         int64
    snapshotsCreated     int64
    lastProcessedLSN     int64  // PostgreSQL LSN for recovery
    consumerLag          int64
}
```

---

## Daily Snapshot Aggregation

### Process

1. **Initialization**: CDC consumer creates empty `DailySnapshot` for each unique `run_date`

2. **Event Processing**:
   - For `reconciliation_runs` table:
     - Extract run metadata (status, counts, timestamps)
     - Accumulate mismatch counts for the day
   - For `reconciliation_mismatches` table:
     - Extract transaction-level details
     - Store for later analysis if needed

3. **Accumulation**: Events are accumulated in-memory; counts are summed:
   ```
   TotalMismatches += event.mismatch_count
   TotalAutoFixed += event.auto_fixed_count
   TotalManualReview += event.manual_review_count
   ```

4. **Completion Signal**: When reconciliation job completes, snapshot is retrieved:
   ```go
   snapshot, err := cdcConsumer.AggregateDaily(ctx, runDate)
   ```

5. **Cleanup**: After processing, snapshot is purged from memory:
   ```go
   cdcConsumer.PurgeSnapshot(runDate)
   ```

### Idempotency

- **Transaction IDs**: All records include a `transaction_id` primary key; duplicate events are harmless
- **Offset Management**: Kafka consumer group offset tracking ensures no replay
- **Snapshot Versioning**: Each snapshot is associated with a `run_id`; re-processing the same run_id is safe

---

## Integration with Reconciliation Job

### Workflow

1. **Scheduler Triggers**: Cron/interval triggers `Reconciler.Run()`

2. **CDC Snapshot Retrieval**:
   ```go
   // In Reconciler.Run() [future Track D]
   if cdcConsumer != nil {
       snapshot, err := cdcConsumer.AggregateDaily(ctx, now)
       if err != nil {
           logger.Warn("cdc snapshot not ready", "error", err)
           // Fall back to file-based ledger (backward compatible)
       } else {
           // Use CDC data for reconciliation
           reconciler.compareLedger(snapshot)
       }
   }
   ```

3. **Emit Events**:
   - After CDC data is ready: emit `reconciliation.snapshot_ready`
   - When job starts: emit `reconciliation_started`
   - When job completes: emit `reconciliation_completed` with counts

4. **Clean Up**: Purge snapshot from memory after job finishes

---

## Deployment Architecture

### Debezium Connector Deployment

**Option 1: Kafka Connect on GKE** (Recommended)
- Deploy Debezium as Kafka Connect image on Kubernetes
- Use ConfigMap for connector config
- Enable Prometheus metrics for monitoring

**Option 2: Managed Kafka Connect Service**
- Use Confluent Cloud or GCP Pub/Sub (if available)
- Terraform manages connector lifecycle

### Terraform Configuration

```hcl
# infra/terraform/debezium-connector-reconciliation.tf

resource "kafka_topic" "reconciliation_cdc" {
  name              = "reconciliation.cdc"
  partitions        = 3
  replication_factor = 2
  config = {
    "cleanup.policy"        = "delete"
    "retention.ms"          = "86400000"
    "compression.type"      = "snappy"
    "min.insync.replicas"   = "2"
  }
}

resource "debezium_connector" "reconciliation_runs_connector" {
  name = "reconciliation-runs-connector"
  config = {
    "connector.class"           = "io.debezium.connector.postgresql.PostgresConnector"
    "database.hostname"         = var.reconciliation_db_host
    "database.port"             = 5432
    "database.user"             = var.reconciliation_db_user
    "database.password"         = var.reconciliation_db_password
    "database.dbname"           = "reconciliation"
    "database.server.name"      = "reconciliation"
    "plugin.name"               = "pgoutput"
    "publication.name"          = "reconciliation_cdc_publication"
    "table.include.list"        = "public.reconciliation_runs,public.reconciliation_mismatches"
    "transforms"                = "route,unwrap"
    "transforms.route.type"     = "org.apache.kafka.connect.transforms.RegexRouter"
    "transforms.route.replacement" = "reconciliation.cdc"
    "topic.prefix"              = "reconciliation"
    "snapshot.mode"             = "initial"
    "tasks.max"                 = "1"
  }
}
```

### PostgreSQL Prerequisites

**Logical Decoding Setup**:

```sql
-- On reconciliation database (via migrations in Wave 36 Track A)

-- Enable logical decoding
ALTER SYSTEM SET wal_level = logical;

-- Create replication slot for Debezium
SELECT pg_create_logical_replication_slot('reconciliation_cdc_slot', 'pgoutput');

-- Create publication for CDC
CREATE PUBLICATION reconciliation_cdc_publication FOR TABLE
  public.reconciliation_runs,
  public.reconciliation_mismatches,
  public.reconciliation_fixes;

-- Grant Debezium user replication privileges
CREATE ROLE debezium_user REPLICATION LOGIN PASSWORD 'secure_password';
GRANT CONNECT ON DATABASE reconciliation TO debezium_user;
GRANT USAGE ON SCHEMA public TO debezium_user;
GRANT SELECT ON ALL TABLES IN SCHEMA public TO debezium_user;
GRANT EXECUTE ON FUNCTION pg_logical_slot_get_binary_changes(text, pg_lsn, integer, boolean, boolean) TO debezium_user;
```

---

## SLO and Performance

### Latency SLO

| Phase | Target | Notes |
|-------|--------|-------|
| CDC capture (PG → Kafka) | <500ms | Network latency, CDC flush interval |
| Consumer processing | <1s per 100 events | In-memory aggregation, no DB access |
| Snapshot ready signal | <5s after job completion | Kafka produce latency |
| Total ledger → reconciliation start | <1 minute | 24h retention = time to catch up |

### Throughput

- **Events/sec**: ~1,000 CDC events per day for typical 5-10k transaction workload
- **Consumer throughput**: Easily handles 10,000+ events/sec per consumer instance

### Resource Usage

- **Memory**: ~50 MB per daily snapshot (1,000 transactions × 50 KB)
- **Kafka Storage**: ~1 GB/day at 1,000 events/sec with 24-hour retention
- **CPU**: <5% on single core (consumer is I/O bound)

---

## Monitoring and Observability

### Prometheus Metrics

```
# Consumer lag
cdc_consumer_lag{topic="reconciliation.cdc", partition="0"}

# Events processed
cdc_events_received_total
cdc_events_processed_total
cdc_events_failed_total

# Snapshot lifecycle
reconciliation_snapshots_created_total
reconciliation_snapshot_latency_seconds

# PostgreSQL replication slot lag
pg_replication_slot_lag_bytes{slot_name="reconciliation_cdc_slot"}
```

### Distributed Tracing

All CDC consumer methods use OpenTelemetry:

```go
ctx, span := tracer.Start(ctx, "kafka.consume",
    trace.WithAttributes(
        attribute.String("messaging.system", "kafka"),
        attribute.String("messaging.destination", msg.Topic),
    ),
)
defer span.End()
```

### Alerting

Key alerts:

1. **CDC Lag > 5 minutes**: Indicates Debezium connectivity issue or consumer lag
2. **Snapshot Not Ready by 12:30 UTC**: Daily reconciliation dependency
3. **Replication Slot Lag > 1 GB**: PostgreSQL storage pressure

---

## Failure Handling

### Debezium Connector Failures

**Scenario**: Connector crashes or loses connectivity

**Recovery**:
- Kafka Connect auto-restarts connector
- Replication slot retained; connector resumes from last LSN
- No data loss; full replay possible

**Operator Action**: Check Debezium logs; verify PostgreSQL connectivity

### Consumer Failures

**Scenario**: Reconciliation engine crashes during CDC consumption

**Recovery**:
- Kafka consumer group offset retained
- Upon restart, consumer resumes from last committed offset
- In-memory snapshots are lost; rebuilt from next CDC batch

**Mitigation**:
- Increase commit frequency if snapshot loss is unacceptable
- Implement snapshot persistence (Wave 36 Track C future enhancement)

### Network Partition

**Scenario**: Kafka broker unreachable

**Behavior**:
- Consumer continues with stale in-memory snapshot
- Replication slot holds WAL; no PostgreSQL storage impact
- Upon network recovery, full replay possible

**Mitigation**: Multiple Kafka brokers (HA), network observability

---

## Backward Compatibility

CDC is **optional and non-blocking**:

- If CDC is disabled or unavailable: reconciliation-engine falls back to file-based ledger (existing behavior)
- CDC and file-based ledger can coexist during rollout
- No changes to `reconciliation.events` topic schema; new events are additive

---

## Testing

### Unit Tests

See `pkg/cdc/payment_ledger_consumer_test.go`:

- ✅ Parse Debezium change event
- ✅ Extract operation (INSERT/UPDATE/DELETE)
- ✅ Daily aggregation: group transactions by date
- ✅ Multiple events per transaction: sum correctly
- ✅ Out-of-order events: handle gracefully (idempotent by transaction_id)
- ✅ Duplicate events: process idempotently

### Integration Tests

- Debezium connector lifecycle (start, capture, stop)
- PostgreSQL → Kafka → Consumer pipeline
- Daily snapshot accuracy with mixed INSERT/UPDATE/DELETE operations

### Local Development

**Docker Compose**:

```bash
# Start PostgreSQL + Kafka + Debezium
docker-compose -f docker-compose.cdc.yml up

# Verify connector status
curl localhost:8083/connectors/reconciliation-runs-connector

# Monitor Kafka topic
kafka-console-consumer --bootstrap-server localhost:9092 \
  --topic reconciliation.cdc --from-beginning
```

---

## Future Enhancements

1. **Wave 36 Track C**: Implement reconciliation logic using CDC data
2. **Wave 36 Track D**: Snapshot persistence to PostgreSQL (replace in-memory store)
3. **Wave 37**: Cross-datacenter CDC replication (active-passive failover)
4. **Wave 38**: Schema evolution support (Debezium schema registry)

---

## References

- [Debezium PostgreSQL Connector Docs](https://debezium.io/documentation/reference/stable/connectors/postgresql.html)
- [PostgreSQL Logical Decoding](https://www.postgresql.org/docs/current/logicaldecoding.html)
- [Kafka Connect Architecture](https://kafka.apache.org/documentation/#connect)
- [EventEnvelope Contract](../../contracts/src/main/java/com/instacommerce/contracts/events/EventEnvelope.java)
- [Reconciliation Engine README](../README.md)
