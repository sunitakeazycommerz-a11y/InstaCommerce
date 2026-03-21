# Outbox Relay Service - High-Level Design

```mermaid
graph TB
    ProducerSvc["📦 13 Producer Services<br/>(with outbox tables)"]
    OutboxTbl["📤 Outbox Tables<br/>(transactional)"]
    RelayService["🚀 Outbox Relay Service<br/>(Go)"]
    Kafka["📬 Kafka<br/>(14 domain topics)"]
    Subscribers["📨 Event Subscribers"]

    ProducerSvc -->|Write events| OutboxTbl
    RelayService -->|Poll (100ms)| OutboxTbl
    RelayService -->|Batch publish| Kafka
    Kafka -->|Subscribe| Subscribers

    style RelayService fill:#4A90E2,color:#fff
    style Kafka fill:#50E3C2,color:#000
```

## Architecture Overview

### Producer Services (13 total)
Services that generate domain events:
- Order Service
- Payment Service
- Fulfillment Service
- Inventory Service
- Catalog Service
- Pricing Service
- Notification Service
- Audit Service
- CDC Consumer Service
- Dispatch Optimizer
- Stream Processor
- Reconciliation Engine
- Mobile BFF Service

Each producer service has an `outbox_events` table within its business database.

### Outbox Table Pattern
Each producer maintains a transactional outbox table:
```sql
CREATE TABLE outbox_events (
    id UUID PRIMARY KEY,
    domain VARCHAR(50),  -- 'orders', 'payments', etc.
    topic VARCHAR(100),  -- Kafka topic
    payload JSONB,       -- Event data
    sent BOOLEAN DEFAULT false,
    created_at TIMESTAMP DEFAULT NOW(),
    sent_at TIMESTAMP NULL
);

CREATE INDEX ON outbox_events(sent) WHERE sent = false;
```

**Benefit**: Guaranteed event delivery via 2-phase commit (write order + event together)

### Relay Service
- **Language**: Go (high throughput, low latency)
- **Deployment**: 3 pods (horizontal scaling)
- **Polling Interval**: 100ms (balance between latency and database load)
- **Batch Size**: 1000 events per poll
- **Parallelism**: 3 concurrent database connections

### Kafka Topics (14 domain topics)
| Topic | Producer | Subscribers |
|-------|----------|-------------|
| orders.events | Order Service | Payment, Fulfillment, Analytics |
| payments.events | Payment Service | Fulfillment, Reconciliation, Audit |
| fulfillment.events | Fulfillment Service | Notification, Tracking |
| inventory.events | Inventory Service | Catalog, Pricing, Analytics |
| ... | ... | ... |

### Event Subscribers
Downstream services consume from Kafka topics:
- Event processing services
- Analytics and reporting
- Machine learning pipelines
- Notification services

## Event Flow Guarantee

**At-Least-Once Delivery**:
1. Producer writes event to outbox table (same transaction as business operation)
2. Relay polls periodically and finds unsent events
3. Relay publishes to Kafka (with idempotent key = event_id)
4. Relay marks event as sent after Kafka ACK
5. If Relay crashes before marking as sent, event is re-published (retry)

**Result**: No event loss, but possible duplicates (idempotent subscribers required)

## Performance Targets

- **Event Latency**: < 100ms (100ms poll + processing)
- **Throughput**: 10,000 events/second
- **Availability**: 99.9% (managed via SLA)
- **Database Load**: 1-2% CPU utilization on producer DBs
