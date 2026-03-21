# Stream Processor Service - All 7 Diagrams

## 1. High-Level Design

```mermaid
graph TB
    KafkaTopics["📬 Kafka Topics<br/>(orders, payments, etc.)"]
    StreamProc["⚡ Stream Processor<br/>(Go + Redis)"]
    TimescaleDB["📊 TimescaleDB<br/>(hypertable metrics)"]
    Redis["💾 Redis<br/>(sliding window state)"]
    Metrics["📈 Grafana<br/>(SLA dashboard)"]

    KafkaTopics -->|Consume| StreamProc
    StreamProc -->|Window aggregate| StreamProc
    StreamProc -->|Persist| TimescaleDB
    StreamProc -->|State| Redis
    TimescaleDB -->|Query| Metrics

    style StreamProc fill:#4A90E2,color:#fff
    style Redis fill:#F5A623,color:#000
```

## 2. Low-Level Design

```mermaid
graph TD
    KafkaMsg["Kafka Message<br/>(event)"]
    Deserialize["Deserialize"]
    EventFilter["Filter by type<br/>(OrderPlaced, PaymentCompleted)"]
    WindowBuffer["Add to sliding window<br/>(30-min window, Redis)"]
    AggregateMetrics["Aggregate metrics<br/>count, sum, avg"]
    CalculateSLA["Calculate SLA<br/>(success%, latency p99)"]
    WriteTimescale["Write to TimescaleDB<br/>(hypertable)"]
    PublishAlert["Publish alert<br/>if SLA breach"]

    KafkaMsg --> Deserialize
    Deserialize --> EventFilter
    EventFilter --> WindowBuffer
    WindowBuffer --> AggregateMetrics
    AggregateMetrics --> CalculateSLA
    CalculateSLA --> WriteTimescale
    WriteTimescale --> PublishAlert

    style AggregateMetrics fill:#7ED321,color:#000
    style CalculateSLA fill:#F5A623,color:#000
```

## 3. Flowchart - Metrics Aggregation

```mermaid
flowchart TD
    A["Kafka event received<br/>{type: OrderPlaced, latency: 150ms}"]
    B["Extract event type<br/>& timestamp"]
    C["Add to 30-min sliding window<br/>(Redis: window:orders:30m)"]
    D["Every 1 minute:<br/>Calculate aggregates"]
    E["Sum orders placed: 1000"]
    F["Avg latency: 145ms"]
    G["P99 latency: 450ms"]
    H["Calculate SLA breach<br/>target p99: 500ms"]
    I{{"SLA breached?"}}
    J["Query TimescaleDB<br/>recent metrics"]
    K["Trend analysis:<br/>Degrading?"]
    L["INSERT into metrics table"]
    M["Publish alert<br/>to PagerDuty"]
    N["Return metrics"]

    A --> B
    B --> C
    C --> D
    D --> E
    E --> F
    F --> G
    G --> H
    H --> I
    I -->|No| L
    I -->|Yes| J
    J --> K
    K --> M
    K --> L
    L --> N

    style A fill:#4A90E2,color:#fff
    style M fill:#FF6B6B,color:#fff
    style L fill:#7ED321,color:#000
```

## 4. Sequence - SLA Calculation

```mermaid
sequenceDiagram
    participant OrderSvc as Order Service
    participant Kafka as Kafka
    participant StreamProc as Stream Processor
    participant Redis as Redis<br/>(Window State)
    participant TimescaleDB as TimescaleDB
    participant AlertingSvc as Alerting Service

    OrderSvc->>Kafka: OrderPlaced {latency: 150ms}
    Kafka-->>StreamProc: Event
    StreamProc->>Redis: ZADD window:orders:30m<br/>score=timestamp, member=latency
    Redis-->>StreamProc: OK

    Note over StreamProc,Redis: Every 1 minute

    StreamProc->>Redis: ZRANGE window:orders:30m
    Redis-->>StreamProc: [latencies]
    StreamProc->>StreamProc: Calculate p99
    StreamProc->>TimescaleDB: INSERT metrics<br/>(p99=450ms)
    TimescaleDB-->>StreamProc: OK

    alt p99 > 500ms (SLA breach)
        StreamProc->>AlertingSvc: POST alert
        AlertingSvc-->>StreamProc: OK
    end

    Note over OrderSvc,AlertingSvc: Latency: ~100ms from event to alert
```

## 5. State Machine

```mermaid
stateDiagram-v2
    [*] --> CONSUMING
    CONSUMING --> DESERIALIZING
    DESERIALIZING --> BUFFERING
    BUFFERING --> AGGREGATING
    AGGREGATING --> CALCULATING_SLA
    CALCULATING_SLA --> HEALTHY
    CALCULATING_SLA --> DEGRADED
    CALCULATING_SLA --> CRITICAL
    HEALTHY --> PERSISTING
    DEGRADED --> ALERTING
    ALERTING --> PERSISTING
    CRITICAL --> ESCALATING
    ESCALATING --> PERSISTING
    PERSISTING --> COMPLETE
    COMPLETE --> [*]

    note right of BUFFERING
        30-min sliding window
        Redis-backed
    end note

    note right of CALCULATING_SLA
        p99, p95 latencies
        error rates
        success %
    end note

    note right of CRITICAL
        SLA breach > 5min
        Page on-call
    end note
```

## 6. ER - Metrics Schema

```mermaid
erDiagram
    METRICS {
        time timestamp PK "TimescaleDB hypertable"
        string domain "orders, payments"
        integer count
        float avg_latency_ms
        float p99_latency_ms
        float error_rate
        boolean sla_breached
    }

    WINDOW_STATE {
        string window_key PK "Redis key"
        array values "Timeseries values"
        timestamp expires_at "30min"
    }
```

## 7. End-to-End

```mermaid
graph TB
    OrderSvc["📦 Order Service<br/>(emits OrderPlaced)"]
    Kafka["📬 Kafka Topics<br/>(orders, payments, riders)"]
    Partition["🔢 Partitions<br/>(sharded)"]
    StreamProc["⚡ Stream Processor<br/>(3 instances<br/>consumer group)"]
    Redis["💾 Redis<br/>(window:orders:30m<br/>window:payments:30m)"]
    TimescaleDB["📊 TimescaleDB<br/>(hypertable metrics<br/>compressed after 7 days)"]
    Grafana["📈 Grafana Dashboard<br/>(SLA visualization)"]
    AlertingSvc["🚨 PagerDuty<br/>(breach alerts)"]

    OrderSvc -->|1. Event| Kafka
    Kafka -->|2. Partition| Partition
    Partition -->|3. Consume| StreamProc
    StreamProc -->|4. Add to window| Redis
    StreamProc -->|5. Every 1min| StreamProc
    StreamProc -->|6. Calculate metrics| StreamProc
    StreamProc -->|7. Persist| TimescaleDB
    TimescaleDB -->|8. Query| Grafana
    Grafana -->|9. Visualize| Grafana
    StreamProc -->|10. Alert| AlertingSvc

    style StreamProc fill:#4A90E2,color:#fff
    style Redis fill:#F5A623,color:#000
    style TimescaleDB fill:#7ED321,color:#000
```
