# Outbox Relay Service - All 7 Diagrams

## 1. High-Level Design

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

## 2. Low-Level Design

```mermaid
graph TD
    PollJob["Polling Job<br/>(100ms interval)"]
    QueryUnsent["SELECT FROM outbox_events<br/>WHERE sent=false<br/>LIMIT 1000"]
    GetEvents["Fetch batch"]
    GroupByTopic["Group by topic"]
    BuildRecords["Build Kafka records<br/>(with key, headers)"]
    PublishBatch["Publish batch<br/>(async)"]
    CheckACK["Wait for ACK<br/>(5s timeout)"]
    UpdateSent["UPDATE sent=true<br/>sent_at=now()"]
    HandleFailures["Handle publish failures<br/>(retry logic)"]

    PollJob --> QueryUnsent
    QueryUnsent --> GetEvents
    GetEvents --> GroupByTopic
    GroupByTopic --> BuildRecords
    BuildRecords --> PublishBatch
    PublishBatch --> CheckACK
    CheckACK --> UpdateSent
    UpdateSent --> HandleFailures

    style PublishBatch fill:#7ED321,color:#000
    style UpdateSent fill:#F5A623,color:#000
```

## 3. Flowchart - Event Relay

```mermaid
flowchart TD
    A["Polling job runs<br/>every 100ms"]
    B["Query outbox tables<br/>on 13 producer DBs"]
    C["SELECT unsent events<br/>sent=false"]
    D["Batch size: 1000<br/>ordered by created_at"]
    E["Group by domain topic"]
    F["For each group:<br/>Build Kafka records"]
    G["Add idempotent key<br/>(event_id)"]
    H["Publish to Kafka<br/>async batch"]
    I{{"All published?"}}
    J["UPDATE sent=true"]
    K["Commit in transaction"]
    L["Mark success<br/>sent_at"]
    M["Retry failed events"]

    A --> B
    B --> C
    C --> D
    D --> E
    E --> F
    F --> G
    G --> H
    H --> I
    I -->|Yes| J
    I -->|No| M
    J --> K
    K --> L

    style A fill:#4A90E2,color:#fff
    style H fill:#7ED321,color:#000
    style L fill:#52C41A,color:#fff
```

## 4. Sequence - Event Publishing

```mermaid
sequenceDiagram
    participant OrderSvc as Order Service
    participant OrderDB as Order DB<br/>(outbox)
    participant RelayPoll as Relay Polling
    participant Kafka as Kafka
    participant PaymentSvc as Payment Service

    OrderSvc->>OrderDB: INSERT order
    OrderSvc->>OrderDB: INSERT outbox_events<br/>(sent=false)
    Note over OrderSvc,OrderDB: Single transaction

    RelayPoll->>OrderDB: SELECT sent=false
    OrderDB-->>RelayPoll: 100 events
    RelayPoll->>Kafka: Batch publish<br/>orders.events topic
    Kafka-->>RelayPoll: ACK (100 msg)
    RelayPoll->>OrderDB: UPDATE sent=true
    OrderDB-->>RelayPoll: OK

    PaymentSvc->>Kafka: Subscribe orders.events
    Kafka-->>PaymentSvc: Events pushed

    Note over OrderSvc,PaymentSvc: End-to-end: ~100-200ms
```

## 5. State Machine

```mermaid
stateDiagram-v2
    [*] --> POLLING
    POLLING --> FETCHING
    FETCHING --> GROUPING
    GROUPING --> PUBLISHING
    PUBLISHING --> ACK_WAIT
    ACK_WAIT --> ACK_SUCCESS
    ACK_WAIT --> ACK_TIMEOUT
    ACK_SUCCESS --> UPDATING
    ACK_TIMEOUT --> RETRY
    RETRY --> PUBLISHING
    UPDATING --> COMPLETE
    COMPLETE --> POLLING
    COMPLETE --> [*]

    note right of POLLING
        100ms interval
        Prevents stale events
    end note

    note right of PUBLISHING
        Batch mode (1000 events)
        All-or-nothing semantics
    end note

    note right of RETRY
        3x retry with backoff
        Dead-letter after max
    end note
```

## 6. ER - Outbox Schema

```mermaid
erDiagram
    OUTBOX_EVENTS {
        uuid id PK
        string domain "orders, payments, etc."
        string topic "Kafka topic"
        json payload "Event data"
        boolean sent "Default: false"
        timestamp created_at
        timestamp sent_at "null until sent"
    }
```

## 7. End-to-End

```mermaid
graph TB
    OrderService["📦 Order Service"]
    OrderDB["🗄️ Order DB<br/>(PostgreSQL)"]
    OutboxTable["📤 Outbox Table<br/>(sent=false index)"]
    RelayService["🚀 Relay Service<br/>(3 pods)"]
    KafkaOrderTopic["📬 Kafka<br/>orders.events"]
    PaymentService["💳 Payment Service<br/>(subscriber)"]
    Monitoring["📊 Relay Metrics<br/>(lag, throughput)"]

    OrderService -->|1. INSERT order| OrderDB
    OrderService -->|2. INSERT event<br/>sent=false| OutboxTable
    Note over OrderService,OutboxTable: Single transaction<br/>guaranteed delivery

    RelayService -->|3. Poll 100ms| OutboxTable
    OutboxTable -->|4. unsent events| RelayService
    RelayService -->|5. Batch publish| KafkaOrderTopic
    KafkaOrderTopic -->|6. ACK| RelayService
    RelayService -->|7. UPDATE sent=true| OutboxTable
    OutboxTable -->|8. OK| RelayService
    RelayService -->|9. Metrics| Monitoring

    PaymentService -->|Subscribe| KafkaOrderTopic
    KafkaOrderTopic -->|Events pushed| PaymentService

    style RelayService fill:#4A90E2,color:#fff
    style OutboxTable fill:#F5A623,color:#000
    style KafkaOrderTopic fill:#50E3C2,color:#000
```
