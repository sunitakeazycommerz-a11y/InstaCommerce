# Stream Processor Service - High-Level Design

```mermaid
flowchart LR
    subgraph Producers["Producer Layer"]
        OS["Order Service<br/>(order.events)"]
        PS["Payment Service<br/>(payment.events)"]
        RS["Rider Service<br/>(rider.events)"]
        LI["Location Ingestion<br/>(rider.location.updates)"]
        IS["Inventory Service<br/>(inventory.events)"]
    end

    subgraph Kafka["Kafka Cluster"]
        OE["order.events<br/>orders.events"]
        PE["payment.events<br/>payments.events"]
        RE["rider.events"]
        RL["rider.location.updates"]
        IE["inventory.events"]
    end

    subgraph SP["stream-processor-service :8108"]
        direction TB
        CONSUME["Kafka Consumer"]
        PROCESSORS["Event Processors<br/>(Order, Payment, Rider, Inventory)"]
        WINDOWING["Sliding Window<br/>Aggregations"]
        SLA["SLA Monitor<br/>(Delivery Compliance)"]
        DEDUP["Deduplication<br/>Manager"]
        ENRICHMENT["Event Enrichment<br/>(joins, lookups)"]
    end

    subgraph Sinks["Output Sinks"]
        REDIS[("Redis<br/>Metrics Cache")]
        PROM["Prometheus<br/>Metrics"]
    end

    subgraph Observability["Observability"]
        METRICS["Metrics"]
        LOGS["Structured Logs"]
    end

    OS --> OE
    PS --> PE
    RS --> RE
    LI --> RL
    IS --> IE

    OE --> CONSUME
    PE --> CONSUME
    RE --> CONSUME
    RL --> CONSUME
    IE --> CONSUME

    CONSUME --> PROCESSORS
    PROCESSORS --> DEDUP
    DEDUP --> ENRICHMENT
    ENRICHMENT --> WINDOWING
    WINDOWING --> SLA

    SLA --> REDIS
    SLA --> PROM
    PROCESSORS --> METRICS
    DEDUP --> METRICS
    ENRICHMENT --> LOGS
```

## Key Characteristics

- **Multi-Source Streaming**: Order, payment, rider, inventory, location events
- **Real-Time Aggregations**: Sliding windows (30s, 5m, 1h) for counters/gauges
- **Deduplication**: Per-event idempotent processing with offset tracking
- **Event Enrichment**: Join with catalog/pricing lookups
- **SLA Monitoring**: Delivery compliance per zone with 30-min windows
- **Redis Dual Output**: TTL-bounded metrics for ops dashboards
- **Prometheus Native**: Direct metric emission for alerting
- **Stateless Design**: Consumer group tracks offsets
