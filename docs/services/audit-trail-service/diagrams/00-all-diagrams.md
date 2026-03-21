# Audit Trail Service - All 7 Diagrams

## 1. High-Level Design

```mermaid
graph TB
    Kafka["📬 14 Kafka Topics<br/>(from all domains)"]
    AuditSvc["📋 Audit Trail Service<br/>(Java Spring Boot)"]
    PartitionedDB["🗄️ PostgreSQL<br/>(Range partitioned by date)"]
    Search["🔍 Elasticsearch<br/>(Full-text index)"]
    AdminPortal["👨‍💼 Admin Portal<br/>(Search, export)"]

    Kafka -->|Consume| AuditSvc
    AuditSvc -->|Persist| PartitionedDB
    AuditSvc -->|Index| Search
    AdminPortal -->|Query| AuditSvc
    AdminPortal -->|Full-text| Search

    style AuditSvc fill:#4A90E2,color:#fff
    style PartitionedDB fill:#7ED321,color:#000
```

## 2. Low-Level Design

```mermaid
graph TD
    KafkaEvent["Kafka Event<br/>(from domain service)"]
    EventParser["Parse envelope"]
    DeduplicateCheck["Check idempotent ID<br/>deduplicate cache"]
    EnrichEvent["Add metadata<br/>(tenant, region)"]
    RLSFilter["Row-Level Security<br/>(filter by tenant)"]
    InsertDB["INSERT audit_logs<br/>(partitioned table)"]
    IndexES["Index to Elasticsearch"]
    Response["Event processed"]

    KafkaEvent --> EventParser
    EventParser --> DeduplicateCheck
    DeduplicateCheck --> EnrichEvent
    EnrichEvent --> RLSFilter
    RLSFilter --> InsertDB
    InsertDB --> IndexES
    IndexES --> Response

    style InsertDB fill:#7ED321,color:#000
    style IndexES fill:#F5A623,color:#000
```

## 3. Flowchart - Audit Log Search

```mermaid
flowchart TD
    A["Request: GET /audit/logs<br/>?user_id=&event_type=&from=&to="]
    B["Authenticate admin"]
    C["Apply tenant filter<br/>(RLS)"]
    D["Build query"]
    E{{"Use Elasticsearch?"}}
    F["Query PostgreSQL<br/>with indexes"]
    G["Full-text search<br/>Elasticsearch"]
    H["Apply pagination"]
    I["Apply sorting"]
    J["Audit query itself"]
    K["Return 200 OK"]

    A --> B
    B --> C
    C --> D
    D --> E
    E -->|Yes| G
    E -->|No| F
    F --> H
    G --> H
    H --> I
    I --> J
    J --> K

    style A fill:#4A90E2,color:#fff
    style G fill:#F5A623,color:#000
    style K fill:#7ED321,color:#000
```

## 4. Sequence - Log Search

```mermaid
sequenceDiagram
    participant Admin as Admin User
    participant Portal as Admin Portal
    participant AuditSvc as Audit Service
    participant ES as Elasticsearch
    participant DB as PostgreSQL

    Admin->>Portal: GET /audit/logs<br/>?user_id=user123
    Portal->>AuditSvc: GET /logs<br/>with JWT
    AuditSvc->>AuditSvc: Authenticate<br/>Extract tenant
    AuditSvc->>ES: Query<br/>user_id:user123
    ES-->>AuditSvc: Results (1000 hits)
    AuditSvc->>DB: Verify results<br/>against authoritative DB
    DB-->>AuditSvc: Confirmed
    AuditSvc-->>Portal: 200 OK {logs}
    Portal-->>Admin: Display results

    Note over Admin,DB: Latency: 50-200ms
```

## 5. State Machine

```mermaid
stateDiagram-v2
    [*] --> INGESTING
    INGESTING --> DEDUPLICATING
    DEDUPLICATING --> ENRICHING
    ENRICHING --> PERSISTING_DB
    PERSISTING_DB --> INDEXING_ES
    INDEXING_ES --> COMPLETE

    DEDUPLICATING --> DUPLICATE: Already seen
    DUPLICATE --> DISCARDED: [*]

    PERSISTING_DB --> DB_ERROR: Insert fails
    DB_ERROR --> [*]

    INDEXING_ES --> ES_ERROR: Index fails
    ES_ERROR --> [*]

    COMPLETE --> [*]

    note right of PERSISTING_DB
        Range partition by date
        Auto-partition creation
    end note

    note right of INDEXING_ES
        5min refresh interval
        Full-text analysis
    end note
```

## 6. ER - Audit Schema

```mermaid
erDiagram
    AUDIT_LOGS {
        uuid id PK
        uuid tenant_id FK "Multi-tenant"
        string event_type "USER_CREATED, etc."
        json payload "Event data"
        string user_id "Who did it"
        string resource "What was it"
        timestamp created_at "Partitioned by date"
        string region "Geographic region"
    }
```

## 7. End-to-End

```mermaid
graph TB
    DomainSvc["📦 Domain Service<br/>(user-created event)"]
    Kafka["📬 Kafka Topic<br/>(audit.events)"]
    Partition["🔢 Partition 0"]
    AuditConsumer["🔄 Audit Consumer"]
    DedupeCache["💾 Dedup Cache<br/>(Redis)"]
    PartitionedDB["🗄️ PostgreSQL<br/>Q1-2025, Q2-2025"]
    ES["🔍 Elasticsearch"]
    AdminQuery["👨‍💼 Admin Query<br/>GET /logs"]

    DomainSvc -->|Publish| Kafka
    Kafka -->|Partition| Partition
    Partition -->|Consume| AuditConsumer
    AuditConsumer -->|Check| DedupeCache
    DedupeCache -->|Dedup| AuditConsumer
    AuditConsumer -->|INSERT| PartitionedDB
    AuditConsumer -->|Index| ES
    AdminQuery -->|Search| ES
    ES -->|Verify| PartitionedDB

    style AuditConsumer fill:#4A90E2,color:#fff
    style PartitionedDB fill:#7ED321,color:#000
```
