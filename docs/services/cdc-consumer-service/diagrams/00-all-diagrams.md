# CDC Consumer Service - All 7 Diagrams

## 1. High-Level Design

```mermaid
graph TB
    PaymentDB["💳 Payment Service DB<br/>(PostgreSQL WAL)"]
    Debezium["🔌 Debezium CDC<br/>(Logical Decoding)"]
    Kafka["📬 Kafka Topic<br/>(reconciliation.cdc)"]
    CDCConsumer["🔄 CDC Consumer<br/>(Go)"]
    BigQuery["📊 BigQuery<br/>(Data Lake)"]
    Monitoring["📈 Monitoring"]

    PaymentDB -->|WAL stream| Debezium
    Debezium -->|Publish envelope| Kafka
    Kafka -->|Consume| CDCConsumer
    CDCConsumer -->|Parse & aggregate| CDCConsumer
    CDCConsumer -->|Stream| BigQuery
    CDCConsumer -->|Metrics| Monitoring

    style CDCConsumer fill:#4A90E2,color:#fff
    style BigQuery fill:#7ED321,color:#000
```

## 2. Low-Level Design

```mermaid
graph TD
    KafkaMsg["Kafka Message<br/>(Debezium envelope)"]
    Deserialize["Deserialize JSON<br/>(envelope format)"]
    ParseOp["Parse operation<br/>(INSERT, UPDATE, DELETE)"]
    DeduplicateCheck["Check idempotency<br/>(offset, partition)"]
    TransformRecord["Transform to BigQuery<br/>schema"]
    BatchAccumulate["Accumulate 1000 records<br/>or 10sec timeout"]
    StreamInsert["Stream INSERT<br/>to BigQuery"]
    UpdateLag["Update consumer lag<br/>metrics"]
    MarkOffset["Commit Kafka offset"]

    KafkaMsg --> Deserialize
    Deserialize --> ParseOp
    ParseOp --> DeduplicateCheck
    DeduplicateCheck --> TransformRecord
    TransformRecord --> BatchAccumulate
    BatchAccumulate --> StreamInsert
    StreamInsert --> UpdateLag
    UpdateLag --> MarkOffset

    style StreamInsert fill:#7ED321,color:#000
    style MarkOffset fill:#F5A623,color:#000
```

## 3. Flowchart - Payment Event Processing

```mermaid
flowchart TD
    A["Kafka: Payment Transaction<br/>INSERT detected"]
    B["Consume message"]
    C["Parse Debezium envelope<br/>{before, after, source}"]
    D["Extract: amount, user_id, status"]
    E["Check duplicate<br/>by offset, partition, key"]
    F{{"Already processed?"}}
    G["Transform to BigQuery format<br/>{timestamp, amount, ...}"]
    H["Batch with others<br/>(100ms window)"]
    I["Stream INSERT rows<br/>to BigQuery"]
    J["Verify insertion"]
    K["Commit Kafka offset"]
    L["Discard duplicate"]

    A --> B
    B --> C
    C --> D
    D --> E
    E --> F
    F -->|Yes| L
    F -->|No| G
    G --> H
    H --> I
    I --> J
    J --> K

    style A fill:#4A90E2,color:#fff
    style I fill:#7ED321,color:#000
    style K fill:#52C41A,color:#fff
```

## 4. Sequence - CDC Event Flow

```mermaid
sequenceDiagram
    participant PaymentDB as Payment DB
    participant WAL as PostgreSQL WAL
    participant Debezium as Debezium
    participant Kafka as Kafka
    participant CDC as CDC Consumer
    participant BigQuery as BigQuery

    PaymentDB->>WAL: INSERT payment_ledger
    WAL-->>Debezium: WAL segment
    Debezium->>Debezium: Decode with logical decoding
    Debezium->>Kafka: Topic: reconciliation.cdc<br/>{before: null, after: {id, amount}}
    Kafka-->>CDC: Consume message
    CDC->>CDC: Parse envelope
    CDC->>CDC: Deduplicate check
    CDC->>CDC: Batch 100 events (or 10s)
    CDC->>BigQuery: tabledata.insertAll
    BigQuery-->>CDC: Success
    CDC->>Kafka: Commit offset
    Kafka-->>CDC: OK

    Note over PaymentDB,Kafka: CDC lag: ~1-2 seconds
```

## 5. State Machine

```mermaid
stateDiagram-v2
    [*] --> CONSUMING
    CONSUMING --> PARSING
    PARSING --> DEDUPLICATING
    DEDUPLICATING --> DUPLICATE_DETECTED
    DEDUPLICATING --> TRANSFORMING
    DUPLICATE_DETECTED --> DISCARDED: [*]
    TRANSFORMING --> BATCHING
    BATCHING --> INSERTING
    INSERTING --> COMMITTING
    COMMITTING --> COMPLETE
    COMPLETE --> [*]

    INSERTING --> BQ_ERROR: Insert fails
    BQ_ERROR --> RETRY: Retry 3x
    RETRY --> BQ_ERROR
    RETRY --> INSERTING
    BQ_ERROR --> DLQ: Max retries<br/>[*]

    note right of DEDUPLICATING
        Track (offset, partition, key)
        1hr sliding window
    end note

    note right of INSERTING
        Batch streaming inserts
        BigQuery best practice
    end note
```

## 6. ER - CDC Processing State

```mermaid
erDiagram
    CDC_OFFSETS {
        string partition PK
        integer offset "Latest committed"
        timestamp updated_at
    }

    DEDUP_CACHE {
        string message_key PK "(offset, partition, key)"
        timestamp expires_at "1hr TTL"
    }
```

## 7. End-to-End

```mermaid
graph TB
    PaymentTxn["💳 Payment Transaction<br/>(INSERT payment_ledger)"]
    PostgreSQL["🗄️ PostgreSQL<br/>(Primary)"]
    WAL["📝 Write-Ahead Log<br/>(wal_level=logical)"]
    Debezium["🔌 Debezium Connector<br/>(logical_decoding_slot)"]
    Kafka["📬 Kafka<br/>(reconciliation.cdc)"]
    CDCConsumer["🔄 CDC Consumer<br/>(Go, 3 instances)"]
    DedupCache["💾 Redis<br/>(dedup state)"]
    BigQuery["📊 BigQuery<br/>(payments_raw table)"]
    DLQ["⚠️ Dead Letter Queue<br/>(failed inserts)"]

    PaymentTxn -->|1. Write| PostgreSQL
    PostgreSQL -->|2. WAL entry| WAL
    WAL -->|3. Decode| Debezium
    Debezium -->|4. Publish| Kafka
    Kafka -->|5. Consume| CDCConsumer
    CDCConsumer -->|6. Check dedup| DedupCache
    CDCConsumer -->|7. Batch 100| CDCConsumer
    CDCConsumer -->|8. Stream INSERT| BigQuery
    BigQuery -->|9. ACK| CDCConsumer
    CDCConsumer -->|10. Commit offset| Kafka
    CDCConsumer -->|11. On failure| DLQ

    style CDCConsumer fill:#4A90E2,color:#fff
    style BigQuery fill:#7ED321,color:#000
```
