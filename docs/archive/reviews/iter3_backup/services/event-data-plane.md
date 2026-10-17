# Event Data Plane — Deep Implementation Guide

**Scope**: `outbox-relay-service`, `cdc-consumer-service`, `stream-processor-service`,
`services/go-shared/pkg/kafka`, `contracts/`  
**Layer**: Asynchronous truth, producer/consumer contracts, idempotency, commit semantics,
DLQ/replay, delivery guarantees, operational ownership  
**Builds on**: `docs/reviews/contracts-events-review.md`,
`PRINCIPAL-ENGINEERING-REVIEW-ITERATION-3-2026-03-06.md`  
**Date**: 2026-03-07  
**Status**: Implementation guide — production hardening required before scale

---

## 1. Executive Summary

The event data plane has a coherent three-layer design: an **outbox relay** surfaces domain
state changes from PostgreSQL into Kafka; a **CDC consumer** sinks Debezium change events into
BigQuery for analytics; a **stream processor** materialises real-time business metrics from
Kafka into Redis and Prometheus. The architecture is sound. The implementation has seven
material gaps that will cause operational pain at scale:

| Gap | Severity | Where |
|-----|----------|-------|
| Partial-commit race in outbox relay | 🔴 Critical | `outbox-relay-service` |
| No DLQ for relay produce failures | 🔴 Critical | `outbox-relay-service` |
| Stream processor not idempotent | 🔴 Critical | `stream-processor-service` |
| Dual topic subscription as naming fix | 🟡 High | `stream-processor-service` |
| `StartOffset: LastOffset` silently drops backlog | 🟡 High | `stream-processor-service` |
| Missing outbox table cleanup in 11 of 13 services | 🟡 High | Java producer services |
| `go-shared` consumer's auto-commit `CommitInterval` | 🟡 Medium | Any future consumer |

This guide documents each gap with exact code references, explains the correct semantics,
provides the fix, and closes with migration options and an ownership map.

---

## 2. System Architecture

### 2.1 Three-Layer Event Plane

```
┌─────────────────────────────────────────────────────────────────────────────────┐
│ PRODUCER LAYER (Java/Spring Boot — 13 services)                                  │
│                                                                                   │
│  OrderService ──@Transactional──► outbox_events (same DB TX)                     │
│  PaymentService                     aggregate_type, aggregate_id, event_type,     │
│  InventoryService     ...           payload JSONB, sent=false                     │
│  (+ 10 more)                                                                      │
└────────────────────────────────┬────────────────────────────────────────────────┘
                                 │ poll every 1s, SELECT…FOR UPDATE SKIP LOCKED
                                 ▼
┌─────────────────────────────────────────────────────────────────────────────────┐
│ RELAY LAYER (Go — outbox-relay-service :8103)                                    │
│                                                                                   │
│  sarama.SyncProducer                                                              │
│  RequiredAcks=WaitForAll, Idempotent=true, MaxOpenRequests=1, Retry.Max=10       │
│  Topic routing: aggregate_type → canonical topic OR OUTBOX_TOPIC override        │
│  Envelope: {id,eventType,aggregateType,aggregateId,eventTime,schemaVersion,      │
│             payload} + headers {event_id,event_type,aggregate_type,              │
│             schema_version}                                                       │
└────────────────────────────────┬────────────────────────────────────────────────┘
                                 │ Kafka
                    ┌────────────┴────────────────────┐
                    │  Domain Topics (orders.events,   │
                    │  payments.events, inventory.events│
                    │  fulfillment.events, etc.)        │
                    │                                  │
                    │  Debezium CDC Topics              │
                    │  (table-level change streams)     │
                    └────────┬───────────────┬─────────┘
                             │               │
             ┌───────────────▼──┐     ┌──────▼──────────────────────────────────┐
             │ STREAM PROCESSOR │     │ CDC CONSUMER  (Go — :8104)               │
             │ (Go — :8108)     │     │                                           │
             │ group:stream-    │     │ group:cdc-consumer-service               │
             │ processor        │     │ CommitInterval=0 (manual only)           │
             │ CommitInterval=1s│     │ BatchSize=500, Timeout=5s                │
             │ StartOffset=Last │     │ BigQuery sink (insertID=topic-part-off)  │
             │                  │     │ DLQ: cdc.dlq (RequiredAcks=All)         │
             │ Redis + Prom     │     │ Retry: 5x exponential backoff 1s→30s    │
             └──────────────────┘     └──────────────────────────────────────────┘
```

### 2.2 Kafka Topic Inventory

| Topic | Producer | Consumers | Notes |
|-------|----------|-----------|-------|
| `orders.events` | outbox-relay (order agg) | stream-processor, downstream services | Canonical name |
| `order.events` | legacy / older services | stream-processor (dual sub) | Naming collision |
| `payments.events` | outbox-relay (payment agg) | stream-processor, downstream | Canonical name |
| `payment.events` | legacy / older services | stream-processor (dual sub) | Naming collision |
| `inventory.events` | outbox-relay (inventory agg) | stream-processor | Single topic |
| `fulfillment.events` | outbox-relay (fulfillment agg) | downstream services | Not in stream-processor |
| `rider.events` | outbox-relay (rider agg) | stream-processor | Status events |
| `rider.location.updates` | location-ingestion-service | stream-processor | Location pings |
| `catalog.events` | outbox-relay (catalog agg) | downstream services | Not stream-processed |
| `identity.events` | outbox-relay (identity agg) | downstream services | GDPR erasure |
| `wallet.events` | outbox-relay (wallet agg) | downstream | Declared but not confirmed published |
| `pricing.events` | outbox-relay (pricing agg) | downstream | Declared but not confirmed published |
| `fraud.events` | outbox-relay (fraud agg) | downstream | |
| `warehouse.events` | outbox-relay (warehouse agg) | downstream | |
| `notification.events` | outbox-relay (notification agg) | downstream | |
| `cdc.dlq` | cdc-consumer-service | replay operators | Dead-letter queue |

---

## 3. Asynchronous Truth: The Outbox Pattern

### 3.1 What "Asynchronous Truth" Means Here

In InstaCommerce, **no service publishes directly to Kafka from its business logic**.
Instead, every state change is first written to a relational `outbox_events` row **inside the
same database transaction** that mutates the domain aggregate. The outbox-relay-service polls
that table and forwards rows to Kafka after the fact. This means:

- **Database is the synchronous truth**: the DB commit is the authoritative record of what happened.
- **Kafka is the asynchronous projection**: the broker eventually receives a projection of that truth.
- The gap between commit and relay is the **outbox lag** (measured by `outbox.relay.lag.seconds`).

This pattern is correct and well-implemented. The risk is in the seams: what happens when the
relay fails mid-batch, and what guarantees downstream consumers can rely on.

### 3.2 Producer Implementation (Java Side)

**OutboxService** (representative, all 13 services follow the same pattern):

```java
// order-service/src/main/java/…/service/OutboxService.java
@Transactional(propagation = Propagation.MANDATORY)   // ← MANDATORY: must join outer TX
public void publish(String aggregateType, String aggregateId,
                    String eventType, Object payload) {
    OutboxEvent outboxEvent = new OutboxEvent();
    outboxEvent.setAggregateType(aggregateType);
    outboxEvent.setAggregateId(aggregateId);
    outboxEvent.setEventType(eventType);
    outboxEvent.setPayload(writePayload(payload));
    outboxEventRepository.save(outboxEvent);
}
```

`Propagation.MANDATORY` is the critical invariant: it enforces that `publish()` is only ever
called within an already-open transaction. If the outer TX rolls back (payment capture fails,
inventory reservation fails), the outbox row is never written, preventing phantom events.

**Outbox table schema** (13 services, slight variations):

```sql
-- Canonical (order-service, payment-service, fulfillment-service, etc.)
CREATE TABLE outbox_events (
    id             UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    aggregate_type VARCHAR(50)  NOT NULL,
    aggregate_id   VARCHAR(255) NOT NULL,
    event_type     VARCHAR(50)  NOT NULL,
    payload        JSONB        NOT NULL,
    created_at     TIMESTAMPTZ  NOT NULL DEFAULT now(),
    sent           BOOLEAN      NOT NULL DEFAULT false
);
CREATE INDEX idx_outbox_unsent ON outbox_events (sent) WHERE sent = false;
```

```sql
-- inventory-service variant (better index for relay's ORDER BY created_at)
CREATE INDEX idx_outbox_events_unsent ON outbox_events (created_at) WHERE sent = false;
```

> ⚠️ **Inconsistency**: Most services index on `(sent) WHERE sent = false` but the relay's
> poll query is `ORDER BY created_at … FOR UPDATE SKIP LOCKED`. The inventory-service's
> `(created_at) WHERE sent = false` index is better aligned with the actual query plan.
> All other 12 services should adopt the `(created_at)` partial index.

### 3.3 Relay Implementation (Go Side)

The relay's poll loop is in `relayBatch()`:

```go
// outbox-relay-service/main.go — relayBatch (abridged)
tx, _ := s.db.Begin(ctx)
rows, _ := tx.Query(ctx, selectSQL, s.cfg.BatchSize)
// selectSQL: SELECT … FROM outbox_events WHERE sent = false
//            ORDER BY created_at LIMIT $1 FOR UPDATE SKIP LOCKED

for _, evt := range events {
    topic := resolveKafkaTopic(s.cfg.KafkaTopic, evt.AggregateType)
    message := buildEnvelope(evt)     // wraps payload into canonical envelope

    if _, _, err := s.producer.SendMessage(message); err != nil {
        sendErr = err
        break   // ← stops producing, falls through to COMMIT
    }

    tx.Exec(ctx, updateSQL, evt.ID)   // UPDATE SET sent = true
}
tx.Commit(ctx)   // commits partial progress
```

**FOR UPDATE SKIP LOCKED** is correct: multiple relay instances can run concurrently on the
same database without duplicating work. Each instance locks a distinct batch of rows.

---

## 4. Critical Gap: Partial-Commit Race in the Relay

### 4.1 The Bug

The relay's loop is: **Produce to Kafka → Mark `sent = true` → (next iteration)**. If
the **UPDATE SET sent = true** succeeds but the outer **COMMIT** fails (network partition,
DB failover), the row goes back to `sent = false` and will be re-relayed. The Kafka producer
is idempotent (`Producer.Idempotent = true`), which prevents duplicates at the broker level
for **the same producer session**. However:

1. The idempotent producer's sequence number resets across restarts. A replay from a new
   producer session will produce a distinct sequence, and Kafka will accept it as a new write.
2. Consumers who rely on `event_id` deduplication (most do not today) are safe. Consumers
   who rely on Kafka-level deduplication only (e.g., exactly-once semantics via transactions)
   are not protected.

There is a more common and more dangerous failure: `SendMessage` succeeds → `tx.Exec(UPDATE)`
succeeds → `tx.Commit` fails. The row is not committed as `sent = true`. On the next poll,
the relay will **re-publish the same event_id** to Kafka. Every consumer that does not
deduplicate on `event_id` will process the event twice.

### 4.2 Current At-Least-Once Guarantee

The relay delivers **at-least-once**. Under crash/restart scenarios, some events will be
published more than once. This is acceptable only if all consumers are idempotent. Today,
stream-processor consumers are not (see §8).

### 4.3 Fix: Swap the Order — Mark First, Produce Second

The safer order is:

```go
// Recommended implementation
tx, _ := s.db.Begin(ctx)
rows, _ := tx.Query(ctx, `SELECT … WHERE sent = false … FOR UPDATE SKIP LOCKED`, batch)

var toSend []outboxEvent
for _, evt := range events {
    tx.Exec(ctx, `UPDATE outbox_events SET sent = true WHERE id = $1`, evt.ID)
    toSend = append(toSend, evt)
}
tx.Commit(ctx)   // commit marking BEFORE producing

for _, evt := range toSend {
    // If produce fails here, the row is permanently marked sent = true but
    // never reached Kafka. This is the "lost message" scenario.
    // Use a separate retry queue / DLQ for failed produces.
    if _, _, err := s.producer.SendMessage(buildMessage(evt)); err != nil {
        sendToDLQ(evt, err)
    }
}
```

This trades "duplicate" risk (current) for "lost message" risk (alternative), both worse
than true exactly-once. The recommended production path is option B in §12 (Debezium WAL
mining), which eliminates the relay entirely and delegates delivery to the Kafka connector.

If the poll-based relay must be kept, add a **relay DLQ** for produce failures:

```go
if _, _, err := s.producer.SendMessage(message); err != nil {
    s.relayDLQWriter.WriteMessages(ctx, kafka.Message{
        Topic:   "outbox.relay.dlq",
        Key:     []byte(evt.ID),
        Value:   value,
        Headers: []kafka.Header{{Key: "relay_error", Value: []byte(err.Error())}},
    })
    // Do NOT break — mark sent=true and continue, let DLQ handle replay
    // This prevents a single broker error from stalling the entire outbox
}
```

---

## 5. Producer/Consumer Contracts

### 5.1 Canonical Event Envelope

The relay builds the envelope in `buildEventMessage()`:

```go
// outbox-relay-service/main.go
envelope := map[string]any{
    "id":            evt.ID,
    "eventId":       evt.ID,
    "aggregateType": evt.AggregateType,
    "aggregateId":   evt.AggregateID,
    "eventType":     evt.EventType,
    "eventTime":     evt.CreatedAt.UTC().Format(time.RFC3339Nano),
    "schemaVersion": "v1",
    "payload":       payload,
}
```

Kafka headers set by the relay:

```
event_id        = evt.ID
event_type      = evt.EventType
aggregate_type  = evt.AggregateType
schema_version  = "v1"  (hardcoded — not dynamic)
```

The `contracts/README.md` mandates these envelope fields:

```json
{
  "event_id":       "550e8400-e29b-41d4-a716-446655440000",
  "event_type":     "OrderPlaced",
  "aggregate_id":   "order-12345",
  "schema_version": "v1",
  "source_service": "order-service",
  "correlation_id": "req-abc-123",
  "timestamp":      "2024-01-15T10:30:00Z",
  "payload":        {}
}
```

**Gaps between implementation and spec**:

| Field | Spec | Relay Implementation | Status |
|-------|------|----------------------|--------|
| `event_id` | Required | ✅ `evt.ID` (UUID) | ✅ Present |
| `event_type` | Required | ✅ `evt.EventType` | ✅ Present |
| `aggregate_id` | Required | ✅ `evt.AggregateID` | ✅ Present |
| `aggregate_type` | Not in spec envelope | ✅ Added | ℹ️ Extension |
| `schema_version` | Required | ✅ Hardcoded `"v1"` | ⚠️ Not per-schema |
| `source_service` | Required | ❌ Missing | 🔴 Gap |
| `correlation_id` | Required | ❌ Missing | 🔴 Gap |
| `timestamp` | Required | ✅ `eventTime` (RFC3339Nano) | ⚠️ Field renamed |

**Two fields are completely absent from the relay envelope**: `source_service` and
`correlation_id`. Any consumer trying to correlate an event back to its originating service
or request trace has no standard field to do so.

**Fix**: The outbox table should store `source_service` and `correlation_id` at write time,
and the relay should propagate them. Add a migration:

```sql
ALTER TABLE outbox_events
    ADD COLUMN source_service  TEXT,
    ADD COLUMN correlation_id  TEXT;
```

Update `OutboxService.publish()` to accept these (derive from MDC in Java):

```java
@Transactional(propagation = Propagation.MANDATORY)
public void publish(String aggregateType, String aggregateId,
                    String eventType, Object payload) {
    OutboxEvent evt = new OutboxEvent();
    evt.setSourceService(applicationName);  // from spring.application.name
    evt.setCorrelationId(MDC.get("correlation_id"));  // from incoming request context
    // …
}
```

### 5.2 Topic Naming and Dual-Subscription Problem

The outbox relay's canonical topic routing:

```go
// outbox-relay-service/main.go — resolveKafkaTopic
case "order", "orders":  return "orders.events"
case "payment", "payments": return "payments.events"
// …
```

The stream-processor subscribes to **both** names as a compensating control:

```go
// stream-processor-service/main.go
startConsumer(…, "order.events",   handleOrderEvent)
startConsumer(…, "orders.events",  handleOrderEvent)  // defensive duplicate
startConsumer(…, "payment.events",  handlePaymentEvent)
startConsumer(…, "payments.events", handlePaymentEvent)
```

This dual-subscription means:
1. Events on `order.events` AND `orders.events` both reach the order processor.
2. Both topics are tracked by the same consumer group (`stream-processor`), which means
   consumer group offsets and rebalances are more complex.
3. If a service misconfigures its aggregate_type and publishes to the wrong topic alias,
   the stream-processor silently accepts it. No alert fires.

**This is a Band-Aid, not a fix.** See §12.1 for the recommended topic consolidation path.

### 5.3 Schema Version Hardcoding

The relay sets `schema_version: "v1"` for all events regardless of the actual schema. When
a schema evolves to `v2`, the relay will silently stamp `"v1"` on a `v2`-shaped payload.
Consumers that branch on `schema_version` for deserialization will deserialize incorrectly.

**Fix**: Store `schema_version` in `outbox_events`:

```sql
ALTER TABLE outbox_events ADD COLUMN schema_version VARCHAR(10) NOT NULL DEFAULT 'v1';
```

Pass it through `OutboxService.publish()` and the relay must read it from the row rather
than hardcoding `"v1"` in `buildEventMessage()`.

---

## 6. Commit Semantics

### 6.1 outbox-relay-service

**Mechanism**: `sarama.SyncProducer` — `SendMessage()` blocks until the broker returns ACK or
error. This is synchronous, blocking commit semantics.

**Producer config**:

```go
kafkaConfig.Producer.RequiredAcks = sarama.WaitForAll   // all in-sync replicas
kafkaConfig.Producer.Idempotent   = true                 // PID + sequence numbers
kafkaConfig.Net.MaxOpenRequests   = 1                    // required for idempotence
kafkaConfig.Producer.Retry.Max    = 10                   // retry transient errors
```

`WaitForAll` means the produce call waits for the partition leader to replicate to all ISR
before ACKing. Under broker failures, this can cause produce latency spikes of 100ms–10s.
With `Retry.Max = 10` and default backoff (Sarama default: 100ms initial), worst-case stall
per message is approximately 1–2 seconds. The relay poll interval is 1s, so during broker
failover the relay may appear stuck.

**Operational tuning required**: Add `kafkaConfig.Producer.Retry.Backoff` and
`kafkaConfig.Metadata.Retry.Max` to the relay config, and expose them as environment
variables to allow ops tuning without a code change.

### 6.2 cdc-consumer-service

**Mechanism**: `kafka-go` `FetchMessage` + explicit `CommitMessages` with `CommitInterval: 0`.

```go
// cdc-consumer-service/main.go
reader := kafka.NewReader(kafka.ReaderConfig{
    CommitInterval: 0,   // ← manual-only commits; no background auto-commit
    …
})
```

`CommitInterval: 0` means offset commits are **fully manual and synchronous**. The reader
will NOT advance offsets unless `CommitMessages` is explicitly called and returns without
error. This is the strictest and safest commit mode.

**Commit point**: After `flushBatch()` completes — which means after either:
- BigQuery insert succeeds (all rows committed), OR
- BigQuery retry exhausted → DLQ write succeeds → commit offsets

The critical invariant is: **offsets are committed regardless of BigQuery success or failure**
as long as the DLQ write succeeds. This prevents infinite retry loops at the expense of
accepting that some events will only be in the DLQ rather than BigQuery.

If `sendToDLQ()` itself fails (`return dlqErr`), `runBatcher` returns an error, which
is reported via `reportErr`, which triggers service shutdown. The service will restart
(Kubernetes), and on restart it will re-fetch the uncommitted batch from the beginning of
the current window, retrying the DLQ write. This is the correct backpressure behavior.

**One edge case**: `flushBatch` calls `commitMessages` with a 10s timeout
(`cfg.KafkaCommitTimeout`). If the Kafka broker is slow during the commit, the commit times
out, `flushBatch` returns an error, and `runBatcher` calls `reportErr`. The service restarts.
On restart, the batch is re-fetched and re-processed — BigQuery `insertID = topic-partition-offset`
deduplicates at the BigQuery level, so this reprocessing is safe.

### 6.3 stream-processor-service

**Mechanism**: `kafka-go` `FetchMessage` + explicit `CommitMessages` with `CommitInterval: time.Second`.

```go
// stream-processor-service/main.go
reader := kafka.NewReader(kafka.ReaderConfig{
    CommitInterval: time.Second,   // ← async commits batched every 1s
    StartOffset:    kafka.LastOffset,
    …
})
// …
msg, _ := reader.FetchMessage(ctx)
if err := handler(ctx, msg); err != nil {
    logger.Error("process message error", …)
    continue   // ← no CommitMessages → offset NOT advanced → redelivered
}
reader.CommitMessages(ctx, msg)
```

With `CommitInterval: time.Second` and explicit `CommitMessages`:
- `CommitMessages` is **non-blocking** — it queues the commit internally.
- The background goroutine flushes committed offsets to the broker every 1 second.
- Offset is NOT advanced for messages where the handler returned an error (no `CommitMessages`
  called). This is correct behaviour — the message will be redelivered.

**However, there is a correctness subtlety**: because commits are async (queued, not
synchronous), if the service crashes between a successful `CommitMessages` call and the
background flush, the offset will not have been recorded at the broker. On restart with
`StartOffset: kafka.LastOffset`, the reader will NOT seek to the last committed offset —
it will seek to the **latest available offset**, silently skipping the messages that were
processed but not committed before the crash.

> 🔴 **`StartOffset: kafka.LastOffset` is dangerous for a stateful processor**.  
> It should be `kafka.FirstOffset` (seek to earliest if no committed offset exists) or
> left as the default (which uses committed offset if available, `LastOffset` only if
> the consumer group has never committed). The current value means a cold-start or group
> reset will silently skip all messages that arrived before the service started.

For a real-time metrics service this may be acceptable (metrics are approximate), but
it must be documented as a known semantic, and the consumer group must NOT be reset
carelessly.

### 6.4 go-shared Consumer

```go
// services/go-shared/pkg/kafka/consumer.go
reader := kafkago.NewReader(kafkago.ReaderConfig{
    CommitInterval: time.Second,   // async batched commits
    StartOffset:    kafkago.LastOffset,
    …
})
// handler failure → no CommitMessages → redelivery (correct)
// handler success → CommitMessages (non-blocking) → commit within 1s
```

The go-shared consumer has the same `StartOffset: LastOffset` issue and the same async
commit characteristic. Any service that adopts this consumer for **transactional** use cases
(not just metrics) should override `CommitInterval: 0` and `StartOffset: kafkago.FirstOffset`.

---

## 7. Dead-Letter Queue Implementation

### 7.1 cdc-consumer-service — Full DLQ Implementation

The cdc-consumer has the most mature DLQ implementation in the platform:

```go
// DLQ writer: RequiredAcks=RequireAll, Async=false — synchronous, durable
dlqWriter := &kafka.Writer{
    Addr:         kafka.TCP(cfg.KafkaBrokers...),
    Topic:        cfg.KafkaDLQTopic,       // default: "cdc.dlq"
    Balancer:     &kafka.LeastBytes{},
    RequiredAcks: kafka.RequireAll,
    Async:        false,
}
```

DLQ messages include full provenance headers:

```
dlq_reason         = truncated error string (max 512 chars)
original_topic     = source topic name
original_partition = source partition
original_offset    = source offset
trace_id           = OTEL trace ID (if available)
+ all original message headers preserved
```

This metadata makes replay tractable: an operator can inspect `original_topic`,
`original_partition`, `original_offset` to seek to the exact position in the source topic
and reprocess. The `cdc.dlq` topic itself should have a long retention policy (72h minimum)
to give operators a window for diagnosis and replay.

**DLQ trigger points** (two distinct paths):

1. **Transform failure** (malformed JSON, unexpected Debezium envelope shape):
   - Calls `sendToDLQ` for the single failed message
   - Commits its offset immediately after DLQ write
   - Does NOT stop other messages from processing

2. **BigQuery batch failure** (exhausted retries or non-retryable API error):
   - Calls `sendToDLQ` for the entire batch
   - Commits all offsets after DLQ write
   - Prevents retry loops that would block offset advancement

**Retryable vs non-retryable BQ errors**:

```go
func isRetryable(err error) bool {
    if errors.Is(err, context.DeadlineExceeded) { return true }
    var apiErr *googleapi.Error
    if errors.As(err, &apiErr) {
        return apiErr.Code == http.StatusTooManyRequests ||
               apiErr.Code >= http.StatusInternalServerError
    }
    var netErr net.Error
    if errors.As(err, &netErr) { return netErr.Timeout() || netErr.Temporary() }
    return false
}
```

Non-retryable errors (400, 403, malformed rows) go directly to DLQ without retrying.
Retryable errors (429, 5xx, timeouts) get up to 5 attempts with exponential backoff
(1s base, 30s max, 20% jitter).

### 7.2 outbox-relay-service — No DLQ (Gap)

When `SendMessage` fails for a Kafka produce error, the relay:

1. Breaks the inner loop
2. Commits the already-sent events (those where `sent = true` was written before the error)
3. Logs an error
4. Returns from `relayBatch()`, which will be retried on the next tick (1s)

**Problem**: A persistent produce error (invalid topic, auth failure, topic not yet created)
will **stall the entire outbox indefinitely**. Because `FOR UPDATE SKIP LOCKED` re-selects
the same batch each tick (the failed events are not marked `sent = true`), the relay will
retry the same failing event every second, generating log noise and leaving the outbox lag
growing without bound.

There is no circuit breaker, no exponential backoff on produce failures, no alert threshold
for "N consecutive produce failures on event_id X".

**Fix — three options**:

| Option | Mechanism | Trade-off |
|--------|-----------|-----------|
| **A** (minimal) | Add `retry_count` column; skip events with `retry_count > N` to DLQ topic | Schema migration required; DLQ adds complexity |
| **B** (recommended) | Add relay DLQ writer; on produce failure write to `outbox.relay.dlq` and mark `sent = true` | Prevents stall; DLQ needs replay tool |
| **C** (operational) | Add alert on `outbox.relay.failures` counter growth rate; page on-call | No code change; relies on operator response |

Option B is recommended. The relay DLQ topic should be separate from `cdc.dlq` because the
semantics are different: `outbox.relay.dlq` contains domain events that failed Kafka
ingestion, while `cdc.dlq` contains analytics events that failed BigQuery ingestion.

### 7.3 stream-processor-service — No DLQ (Gap)

Handler failures are logged and skipped:

```go
if err := handler(ctx, msg); err != nil {
    logger.Error("process message error", "topic", topic, "offset", msg.Offset, "error", err)
    continue   // offset IS eventually committed on next successful message in partition
}
```

Wait — this is more subtle. Calling `continue` without `CommitMessages` means the current
message's offset is NOT explicitly committed. However, with `CommitInterval: time.Second`,
the next successful message processed in the same partition WILL call `CommitMessages`, which
commits ALL messages up to and including that message's offset. This means the **failed
message's offset is committed as a side effect of the next success**.

> 🔴 **Failed messages in stream-processor are silently dropped after the next success
> in the same partition.** The metric is simply not counted. No DLQ, no alert, no retry.

For a metrics service this is often acceptable — stale or corrupt events should not block
live metric computation. However the service provides no way to distinguish "low order
volume" from "consumer failure silently dropping all order events". The `order_processing_errors_total`
counter is the only signal.

**Minimum fix**: Expose a per-topic dead-letter counter and add an alert rule when
`order_processing_errors_total` exceeds N over M minutes.

---

## 8. Idempotency

### 8.1 Producer Side (outbox-relay)

The relay uses `sarama.SyncProducer` with `Idempotent = true`. This activates Kafka's
EOS producer: each producer instance gets a Producer ID (PID) and attaches a monotonically
increasing sequence number to each message. The broker rejects duplicates from the **same
PID** within the current epoch.

**Limitation**: PID and epoch reset when the relay restarts. A produce + crash + restart +
reproduced scenario will get a new PID, and the broker will accept both messages as distinct.
Idempotence is **per-session**, not **cross-session**. The only cross-session idempotency
guarantee comes from `event_id` deduplication at the consumer side.

### 8.2 cdc-consumer BigQuery Deduplication

The CDC consumer uses `insertID` for BigQuery deduplication:

```go
insertID := fmt.Sprintf("%s-%d-%d", msg.Topic, msg.Partition, msg.Offset)
return bqRow{values: values, insertID: insertID}, nil
```

BigQuery's streaming insert deduplication uses `insertID` to deduplicate rows for a short
window (approximately 1 minute). Replays within that window are safe. Replays after the
deduplication window (replaying from DLQ hours later) may result in duplicate rows in
BigQuery.

**Recommendation**: For long-delay replays, either use BigQuery MERGE (upsert) instead of
streaming insert, or add a `DEDUP` view over the raw table that selects `DISTINCT` on
`topic + partition + offset`.

### 8.3 stream-processor Redis Operations — NOT Idempotent

Every processor writes to Redis using `INCR` and `INCRBY`:

```go
// order_processor.go
pipe.Incr(ctx, orderMinuteKey)
pipe.IncrBy(ctx, gmvKey, event.TotalCents)
```

```go
// payment_processor.go
pipe.Incr(ctx, methodKey)
pipe.IncrBy(ctx, revenueKey, event.AmountCents)
```

These operations are **not idempotent**. If a message is redelivered (outbox relay duplicate,
consumer restart), the counter will be incremented twice. For approximate metrics (orders per
minute, GMV) this introduces a bounded error proportional to the redelivery rate. For
exact financial counters (`payment_revenue_total_cents`) this is incorrect behavior.

**The sliding window processors** (PaymentProcessor, InventoryProcessor) use in-memory
Go slices with `sync.Mutex` to track window entries. These slices reset on service restart,
so all window state is lost when the pod restarts. Combined with `StartOffset: LastOffset`,
a restart means the service starts fresh with no window state and begins accumulating from
the point the pod came up.

**Fix options**:

| Metric type | Idempotency strategy |
|-------------|----------------------|
| Approximate counters (OPM, GMV) | Document as approximate; add `event_id` set in Redis with TTL; skip INCR if `event_id` already in set |
| Exact financial counters | Move out of stream-processor; compute from BigQuery (authoritative) |
| Sliding windows | Persist window state to Redis on update; reload on startup |

**Example idempotent INCR with dedup**:

```go
eventKey := fmt.Sprintf("processed:%s", event.OrderID)
pipe.SetNX(ctx, eventKey, 1, 2*time.Hour)   // only set if not exists
// Conditional INCR: evaluate SetNX result before INCR in Lua script or two-phase
```

A simpler approach is a Lua script:

```lua
-- Only increment if event has not been seen
local seen = redis.call('SETNX', KEYS[1], 1)
if seen == 1 then
    redis.call('EXPIRE', KEYS[1], 7200)
    redis.call('INCR', KEYS[2])
    return 1
end
return 0
```

### 8.4 Consumer Side Domain Services — No Enforcement

The `event_id` field is present in Kafka headers and the envelope body. However, inspection
of downstream Java service consumers (order-service Kafka listeners, etc.) shows no
platform-level deduplication enforcement. Each team is responsible for its own consumer
idempotency, which the contracts-events-review scored at 7/10 at API level but notes as
"missing in event consumers".

**Platform recommendation**: Add `event_id` deduplication to the `go-shared` consumer wrapper
using a Redis SET with TTL, and document it as the standard pattern for Java consumers via a
shared `EventIdempotencyFilter` bean.

---

## 9. Delivery Guarantees Summary

| Service | Delivery Guarantee | Kafka ACK | Offset Commit | Idempotent Producer | Consumer Dedup |
|---------|-------------------|-----------|----------------|---------------------|----------------|
| outbox-relay-service | At-least-once | `WaitForAll` | DB TX marks sent | ✅ Session-scoped | ❌ None enforced |
| cdc-consumer-service | At-least-once (DLQ-safe) | `RequireAll` (DLQ) | Manual, post-flush | N/A (consumer) | ✅ BQ insertID |
| stream-processor-service | Best-effort at-least-once | N/A | Async (1s) | N/A (consumer) | ❌ None |
| go-shared consumer | At-least-once (handler-gated) | N/A | Async (1s) | N/A (consumer) | ❌ None |

**Exactly-once** is not achieved anywhere in the current implementation. For financial
counters (`payment_revenue_total_cents`, GMV), this is a correctness gap. For operational
metrics (orders per minute, rider counts) the at-least-once + approximate semantics is
acceptable if documented.

---

## 10. Operational Ownership

### 10.1 Service Assignments

| Service | Port | Owner Team | SLA | On-call |
|---------|------|------------|-----|---------|
| outbox-relay-service | 8103 | Platform / Data | Outbox lag P99 < 5s | Platform |
| cdc-consumer-service | 8104 | Data Platform | DLQ rate < 0.1% | Data Platform |
| stream-processor-service | 8108 | Analytics / Ops | Metric staleness < 30s | Analytics |

### 10.2 Health Endpoints

All three services follow the standard Go health convention:

| Endpoint | outbox-relay | cdc-consumer | stream-processor |
|----------|-------------|--------------|-----------------|
| `/health` | ✅ `{"status":"ok"}` | ✅ `{"status":"ok"}` | ✅ `{"status":"ok"}` |
| `/health/live` | ✅ same | ✅ same | ❌ Not implemented |
| `/ready` | ✅ Checks DB + Kafka | ✅ via `readiness.ready` | ❌ Not implemented |
| `/health/ready` | ✅ same | ✅ same | ❌ Not implemented |
| `/metrics` | ❌ Not implemented | ✅ Prometheus | ✅ Prometheus |

**Gaps**:
- `outbox-relay-service` has no `/metrics` endpoint — Prometheus metrics are exported via
  OTLP only. This breaks standard Kubernetes PodMonitor scraping.
- `stream-processor-service` has no liveness or readiness endpoints beyond `/health`.
  Kubernetes will consider it ready immediately on startup, before it has connected to Kafka.

### 10.3 Alert Rules Required

| Metric | Condition | Severity | Owner |
|--------|-----------|----------|-------|
| `outbox.relay.lag.seconds` p99 | > 10s for 5m | Page | Platform |
| `outbox.relay.failures` rate | > 0 for 2m | Alert | Platform |
| `cdc_consumer_lag` | > 5000 for 10m | Alert | Data Platform |
| `cdc_dlq_total` rate | > 1/min | Alert | Data Platform |
| `cdc_batch_latency_seconds` p99 | > 10s | Alert | Data Platform |
| `order_processing_errors_total` rate | > 5/min | Alert | Analytics |
| `payment_processing_errors_total` rate | > 5/min | Page | Analytics |
| `sla_alerts_total` rate | > 0 | Alert | Ops / Delivery |
| `inventory_cascade_alerts_total` rate | > 0 | Page | Ops / Inventory |

### 10.4 Outbox Table Retention

`OutboxCleanupJob` exists in **order-service only** (confirmed by source inspection).
The other 12 producer services have no outbox cleanup. Over time, `sent = true` rows
accumulate indefinitely, causing:

1. Table bloat slowing `SELECT … WHERE sent = false` queries even with a partial index.
2. `pg_dump` / `pg_basebackup` taking longer.
3. Postgres autovacuum overhead on the hot outbox table.

**Fix**: Apply `OutboxCleanupJob` pattern to all 12 remaining services, or add a
shared ShedLock-guarded cleanup job bean to a shared library:

```java
@Scheduled(cron = "0 0 * * * *")   // hourly
@SchedulerLock(name = "outboxCleanup", lockAtMostFor = "PT10M")
@Transactional
public void cleanup() {
    int deleted = outboxEventRepository.deleteSentEventsBefore(
        Instant.now().minus(Duration.ofHours(48)));
    log.info("outbox cleanup deleted {} events", deleted);
}
```

---

## 11. Schema Evolution and Contract Enforcement

### 11.1 Current State

The `contracts/` module defines JSON Schema (draft-07) event definitions under
`src/main/resources/schemas/` and `./gradlew :contracts:build` runs proto compilation and
schema validation in CI. The schema evolution rules (additive = same version, breaking =
new `vN` file) are documented in `contracts/README.md`.

**Runtime enforcement is absent.** The relay stamps `schema_version: "v1"` on every message
regardless of actual payload shape. No consumer validates incoming messages against their
declared schema version. The BigQuery sink (`cdc-consumer`) uses `SkipInvalidRows = true`,
which silently drops schema violations at the analytics layer.

### 11.2 Breaking Change Risk

The 90-day deprecation window rule exists on paper. There is no tooling to detect a breaking
change at runtime or to enforce that v1 consumers have migrated before v1 is removed. The
CI breaking-change detection in `contracts/README.md` compares schemas but does not
enumerate which services depend on which schema version.

**Recommended enforcement layer**:

1. Add a schema version mapping to the outbox_events table (see §5.3).
2. Add a Confluent Schema Registry (or Apicurio) to validate payloads at produce time.
3. Add consumer-side validation as a middleware step in `go-shared/pkg/kafka/consumer.go`.

### 11.3 Double-Field Issue in Envelope

`buildEventMessage()` in the relay merges payload fields into the top-level envelope if
they don't collide with standard envelope fields:

```go
if payloadMap, ok := payload.(map[string]any); ok {
    for key, value := range payloadMap {
        if _, exists := envelope[key]; !exists {
            envelope[key] = value
        }
    }
}
```

This means the envelope contains BOTH `payload: {orderId: "x", …}` AND `orderId: "x"` at
the top level. Consumers that read `envelope.orderId` directly will work, but consumers that
navigate `envelope.payload.orderId` will also work. This creates two divergent consumer
patterns with no enforcement of which is canonical.

**Fix**: Remove the field-hoisting logic. The canonical envelope has a `payload` wrapper;
consumers should navigate through it.

---

## 12. Migration Options and Recommended Path

### 12.1 Topic Naming Consolidation

**Situation**: `order.events` and `orders.events` are both in use. The relay publishes to
`orders.events` (canonical). Legacy or misconfigured producers publish to `order.events`.
The stream-processor subscribes to both.

**Options**:

| Option | Steps | Risk |
|--------|-------|------|
| **A — Keep dual sub** | Document it, add alert if one topic is empty for N minutes | Technical debt; consumer group complexity |
| **B — Redirect legacy** | Deploy Kafka MirrorMaker or kafka-connect to copy `order.events` → `orders.events`; then decommission `order.events` | Short migration window; requires all producers updated |
| **C — Consumer migration** | Update all consumers to subscribe to `orders.events` only; then shut down legacy producers | Zero downtime if done in order |

**Recommended: Option C** with a six-week migration window:
- Week 1-2: Identify all producers of `order.events` and `payment.events`
- Week 3-4: Update them to use canonical names
- Week 5: Remove dual-subscription from stream-processor
- Week 6: Drop legacy topics after confirming zero consumer lag

### 12.2 Outbox Relay vs Debezium WAL Mining

**Current**: Poll-based relay (outbox-relay-service polls `outbox_events` table).
**Alternative**: Debezium log-mining (reads PostgreSQL WAL directly for `outbox_events` table
changes and forwards to Kafka via Kafka Connect).

| Dimension | Poll-based (current) | Debezium WAL mining |
|-----------|---------------------|---------------------|
| Latency | 1s poll interval (configurable) | Sub-second (WAL-based) |
| Database load | Read-heavy (SELECT + UPDATE) | Minimal (reads WAL stream) |
| Exactly-once | No (at-least-once) | Yes (Kafka Connect exactly-once with transactions) |
| Operational complexity | Low (simple Go binary) | Medium (Kafka Connect cluster, connector config) |
| Ordering guarantees | Preserved via `ORDER BY created_at` | Preserved via WAL LSN order |
| Multi-tenant | Each service needs own relay instance | One connector per DB/table |
| Schema migration needed | Yes (retention cleanup, new columns) | Minimal |
| Already in infra? | Yes (outbox-relay-service) | Yes (Debezium in docker-compose) |

Debezium is **already running in docker-compose** for CDC. Extending it to cover the
`outbox_events` table is a configuration change, not a new dependency.

**Recommended migration path**:

```
Phase 1 (Month 1): Harden current relay
- Add outbox.relay.dlq for produce failures
- Fix partial-commit ordering (mark then produce)
- Add /metrics endpoint
- Add outbox retention to 12 remaining services
- Fix StartOffset in stream-processor

Phase 2 (Month 2-3): Parallel run
- Configure Debezium Outbox Event Router connector for 2 pilot services (order, payment)
- Both relay and Debezium produce; consumers dedup on event_id
- Validate latency, ordering, duplicate rate

Phase 3 (Month 4): Cutover
- Disable poll-based relay for migrated services
- Retire outbox-relay-service once all services migrated
- Keep Debezium CDC connector for analytics (cdc-consumer path unchanged)
```

### 12.3 Stream Processor Idempotency — Phased Fix

| Phase | Change | Risk |
|-------|--------|------|
| **Now** | Add `order_processing_errors_total` alert; document best-effort semantics | None |
| **Sprint 1** | Add Redis dedup set for `payment_revenue_total_cents` (financial counter) | Redis memory, +1 RTT |
| **Sprint 2** | Add Redis dedup for `gmv_total_cents` | Same |
| **Sprint 3** | Persist sliding windows to Redis on mutation; restore on startup | Complexity; eliminates cold-start gap |
| **Quarter** | Move financial aggregates to BigQuery materialized view (authoritative) | Architectural shift; eliminates stream-processor for finance |

---

## 13. Configuration Reference

### 13.1 outbox-relay-service

| Variable | Default | Notes |
|----------|---------|-------|
| `OUTBOX_POLL_INTERVAL` | `1s` | Reduce to `500ms` for sub-second latency target |
| `OUTBOX_BATCH_SIZE` | `100` | Increase to `500` for high-throughput services |
| `OUTBOX_TABLE` | `outbox_events` | Must be alphanumeric; injected via SQL identifier sanitizer |
| `OUTBOX_TOPIC` | *(empty)* | If empty, uses aggregate-type routing |
| `KAFKA_BROKERS` | *required* | |
| `KAFKA_CLIENT_ID` | `outbox-relay-service` | |
| `SHUTDOWN_TIMEOUT` | `20s` | Increase if batch size is large |
| `READY_TIMEOUT` | `2s` | |
| `OTEL_EXPORTER_OTLP_ENDPOINT` | — | |

### 13.2 cdc-consumer-service

| Variable | Default | Notes |
|----------|---------|-------|
| `KAFKA_TOPICS` | *required* | Comma-separated CDC topic list |
| `KAFKA_GROUP_ID` | `cdc-consumer-service` | |
| `KAFKA_DLQ_TOPIC` | `cdc.dlq` | Ensure topic exists with replication ≥ 3 |
| `KAFKA_COMMIT_TIMEOUT` | `10s` | |
| `BQ_BATCH_SIZE` | `500` | Tune per throughput; larger batches = fewer BQ API calls |
| `BQ_BATCH_TIMEOUT` | `5s` | Max wait before flushing incomplete batch |
| `BQ_MAX_RETRIES` | `5` | |
| `BQ_BACKOFF_MAX` | `30s` | |

### 13.3 stream-processor-service

| Variable | Default | Notes |
|----------|---------|-------|
| `KAFKA_BROKERS` | `localhost:9092` | |
| `CONSUMER_GROUP_ID` | `stream-processor` | |
| `REDIS_ADDR` | `localhost:6379` | |
| `REDIS_PASSWORD` | — | |
| `HTTP_PORT` | `8108` | |

---

## 14. Key Risks, Decisions, and Open Questions

### Risks

| Risk | Probability | Impact | Mitigation |
|------|-------------|--------|------------|
| Outbox relay stalls on persistent Kafka error | Medium | High (events stop flowing) | Add relay DLQ + alert |
| stream-processor double-counts revenue on restart | Medium | High (financial inaccuracy) | Add Redis dedup for financial counters |
| Outbox table bloat in 12 services | High (already happening) | Medium (slow queries) | Add retention jobs |
| schema_version hardcoded at v1 in relay | Low (until v2 schema) | High (silent deserialisation failure) | Add schema_version column to outbox_events |
| source_service absent from envelope | High (current) | Medium (traceability gap) | Add column and propagate |
| DLQ for cdc.dlq not monitored | Unknown | High (silent data loss) | Add alert on cdc_dlq_total growth |

### Open Questions

1. **Who owns `cdc.dlq` replay?** Is there a runbook? What is the SLA for replaying DLQ
   messages into BigQuery? Replay tooling does not exist today.

2. **Should wallet.events and pricing.events be consumed by stream-processor?** Both topics
   are in the relay's routing table, but neither is subscribed to by stream-processor. Wallet
   cashback metrics and pricing change metrics would be valuable for the ops dashboard.

3. **Is there a schema registry?** The contracts README references JSON Schema files but no
   runtime registry. Confluent Schema Registry or Apicurio would enable runtime validation
   without a deploy.

4. **Should stream-processor connect to fulfillment.events?** Fulfillment events (PickTask,
   RiderAssigned, DeliveryCompleted) are produced but not consumed by stream-processor.
   A fulfillment SLA (pick-to-dispatch time) would complement the delivery SLA already
   implemented.

5. **What is the BigQuery deduplication window for `cdc-consumer`?** BigQuery streaming
   insert `insertID` dedup is best-effort and has a ~1-minute window. Replays from DLQ after
   1 minute may result in duplicate rows. Is the analytics team aware?

---

## 15. Summary Scorecard

| Dimension | Score | Verdict |
|-----------|-------|---------|
| Outbox correctness (Java producer side) | 8/10 | `Propagation.MANDATORY` is correct; envelope gaps fixable |
| Relay reliability | 5/10 | No DLQ, partial-commit risk, no produce-failure circuit breaker |
| CDC consumer reliability | 8/10 | Manual commits, DLQ, BQ dedup — good; replay tooling absent |
| Stream processor delivery | 5/10 | No DLQ, not idempotent, `StartOffset: LastOffset` dangerous |
| Topic naming consistency | 4/10 | Dual-subscription band-aid; consolidation required |
| Idempotency (platform-wide) | 4/10 | Only BQ side; Redis counters and domain consumers lack it |
| Schema contract enforcement | 4/10 | Files exist, runtime enforcement absent |
| Observability completeness | 5/10 | Relay missing `/metrics`; stream-processor missing readiness |
| DLQ coverage | 4/10 | cdc-consumer has it; relay and stream-processor do not |
| Operational ownership | 6/10 | Endpoints exist; alerts, runbooks, and cleanup mostly absent |

**Overall: 5.3/10 — Sound foundation, implementation hardening required before production scale.**
