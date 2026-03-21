# Reconciliation Engine - All 7 Diagrams

## 1. High-Level Design

```mermaid
graph TB
    CDC["🔌 Debezium CDC<br/>(payment_ledger)"]
    Scheduler["⏰ Daily Scheduler<br/>(2 AM UTC)"]
    ReconciliationEngine["⚖️ Reconciliation Engine<br/>(Go)"]
    PostgreSQL["🗄️ PostgreSQL<br/>(reconciliation schema)"]
    Kafka["📬 Kafka<br/>(reconciliation events)"]
    API["🌐 HTTP API<br/>(status queries)"]

    CDC -->|Stream| ReconciliationEngine
    Scheduler -->|Trigger| ReconciliationEngine
    ReconciliationEngine -->|Read/Write| PostgreSQL
    ReconciliationEngine -->|Publish| Kafka
    API -->|Query| PostgreSQL

    style ReconciliationEngine fill:#4A90E2,color:#fff
    style PostgreSQL fill:#7ED321,color:#000
```

## 2. Low-Level Design

```mermaid
graph TD
    DailyTrigger["Scheduler trigger<br/>(2 AM UTC)"]
    AcquireLock["Atomic lock<br/>reconciliation_runs"]
    FetchLedger["Fetch payment_ledger<br/>previous day"]
    FetchStatement["Fetch bank statement<br/>(API)"]
    Reconcile["Compare amounts<br/>(ledger vs bank)"]
    FindMismatches["Identify mismatches"]
    AutoFix["Apply auto-fix rules<br/>(small amounts < $1)"]
    ManualReview["Mark for manual review<br/>(> $1 mismatch)"]
    PublishEvents["Publish ReconciliationCompleted"]
    UpdateMetrics["Update metrics<br/>(success rate, duration)"]
    ReleaseLock["Release lock"]

    DailyTrigger --> AcquireLock
    AcquireLock --> FetchLedger
    FetchLedger --> FetchStatement
    FetchStatement --> Reconcile
    Reconcile --> FindMismatches
    FindMismatches --> AutoFix
    AutoFix --> ManualReview
    ManualReview --> PublishEvents
    PublishEvents --> UpdateMetrics
    UpdateMetrics --> ReleaseLock

    style Reconcile fill:#7ED321,color:#000
    style PublishEvents fill:#50E3C2,color:#000
```

## 3. Flowchart - Daily Reconciliation

```mermaid
flowchart TD
    A["Scheduler: 2 AM UTC"]
    B["Acquire distributed lock<br/>(prevent concurrent runs)"]
    C{{"Lock acquired?"}}
    D["Fetch payment_ledger<br/>date = yesterday"]
    E["Sum amounts:<br/>ledger_total = $1,000,000"]
    F["Fetch bank statement<br/>yesterday's transactions"]
    G["Sum amounts:<br/>bank_total = $999,999.50"]
    H["Compare:<br/>diff = $0.50"]
    I{{"diff == 0?"}}
    J{{"diff < $1?"}}
    K["Manual review flag"]
    L["Auto-fix rule applied"]
    M["INSERT reconciliation_mismatches"]
    N["INSERT reconciliation_fixes"]
    O["Publish ReconciliationCompleted"]
    P["Release lock"]
    Q["Sleep, retry in 5min"]
    R["SLA: 4 hours"]

    A --> B
    B --> C
    C -->|No| Q
    C -->|Yes| D
    D --> E
    E --> F
    F --> G
    G --> H
    H --> I
    I -->|Yes| R
    I -->|No| J
    J -->|Yes| L
    J -->|No| K
    K --> M
    L --> N
    M --> O
    N --> O
    O --> P

    style A fill:#4A90E2,color:#fff
    style R fill:#7ED321,color:#000
    style L fill:#52C41A,color:#fff
```

## 4. Sequence - Reconciliation Flow

```mermaid
sequenceDiagram
    participant Scheduler as Scheduler<br/>(2 AM)
    participant ReconciliationEngine as Reconciliation Engine
    participant Lock as PostgreSQL Lock<br/>(ShedLock)
    participant LedgerDB as Payment Ledger<br/>(CDC sourced)
    participant BankAPI as Bank API<br/>(Settlement)
    participant DB as Reconciliation DB
    participant Kafka as Kafka

    Scheduler->>ReconciliationEngine: Trigger reconciliation
    ReconciliationEngine->>Lock: SELECT * FROM shedlock<br/>FOR UPDATE NOWAIT
    Lock-->>ReconciliationEngine: Acquired
    ReconciliationEngine->>LedgerDB: SELECT SUM(amount)<br/>WHERE date = yesterday
    LedgerDB-->>ReconciliationEngine: ledger_total = $1M
    ReconciliationEngine->>BankAPI: GET /settlement?date=yesterday
    BankAPI-->>ReconciliationEngine: bank_total = $999,999.50
    ReconciliationEngine->>ReconciliationEngine: diff = $0.50
    ReconciliationEngine->>DB: INSERT reconciliation_runs
    ReconciliationEngine->>DB: INSERT reconciliation_mismatches
    DB-->>ReconciliationEngine: OK
    ReconciliationEngine->>DB: INSERT reconciliation_fixes<br/>(auto-fix: $0.50 adjustment)
    ReconciliationEngine->>Kafka: Publish ReconciliationCompleted
    Kafka-->>ReconciliationEngine: ACK
    ReconciliationEngine->>Lock: Unlock

    Note over Scheduler,Lock: Total duration: ~2 minutes
```

## 5. State Machine

```mermaid
stateDiagram-v2
    [*] --> SCHEDULED
    SCHEDULED --> LOCK_ACQUIRED: Acquire ShedLock
    LOCK_ACQUIRED --> FETCHING_LEDGER
    FETCHING_LEDGER --> FETCHING_BANK
    FETCHING_BANK --> COMPARING
    COMPARING --> MATCHED
    COMPARING --> MISMATCHED
    MATCHED --> COMPLETED
    MISMATCHED --> AUTO_FIX_CHECK
    AUTO_FIX_CHECK --> AUTO_FIXABLE
    AUTO_FIX_CHECK --> MANUAL_REVIEW
    AUTO_FIXABLE --> AUTO_FIXED
    AUTO_FIXED --> COMPLETED
    MANUAL_REVIEW --> PENDING_APPROVAL
    PENDING_APPROVAL --> APPROVED
    PENDING_APPROVAL --> REJECTED
    APPROVED --> COMPLETED
    REJECTED --> COMPLETED
    COMPLETED --> PUBLISHED
    PUBLISHED --> [*]

    LOCK_ACQUIRED --> RETRY: Lock timeout
    RETRY --> [*]

    note right of AUTO_FIXABLE
        Amount < $1
        Auto-fix enabled
    end note

    note right of MANUAL_REVIEW
        Amount >= $1
        Requires approval
    end note
```

## 6. ER - Reconciliation Schema

```mermaid
erDiagram
    RECONCILIATION_RUNS {
        uuid id PK
        date reconciliation_date
        bigint ledger_total "in cents"
        bigint bank_total "in cents"
        bigint difference
        string status "COMPLETED, PENDING, FAILED"
        timestamp created_at
        timestamp completed_at
    }

    RECONCILIATION_MISMATCHES {
        uuid id PK
        uuid run_id FK
        bigint amount_diff "in cents"
        string category "AUTO_FIXABLE, MANUAL_REVIEW"
        string reason "Description"
    }

    RECONCILIATION_FIXES {
        uuid id PK
        uuid mismatch_id FK
        string fix_type "AUTO_FIX, MANUAL_ADJUSTMENT"
        string status "APPLIED, PENDING_APPROVAL, REJECTED"
    }

    RECONCILIATION_RUNS ||--o{ RECONCILIATION_MISMATCHES : contains
    RECONCILIATION_MISMATCHES ||--o{ RECONCILIATION_FIXES : has_fix
```

## 7. End-to-End

```mermaid
graph TB
    PaymentSvc["💳 Payment Service<br/>(payment_ledger table)"]
    Debezium["🔌 Debezium CDC<br/>(captures changes)"]
    KafkaReconciliation["📬 Kafka<br/>(reconciliation.cdc topic)"]
    ReconciliationEngine["⚖️ Reconciliation Engine<br/>(Go, 1 replica)"]
    ShedLock["🔒 PostgreSQL ShedLock<br/>(distributed lock)"]
    ReconciliationDB["🗄️ PostgreSQL<br/>(reconciliation schema<br/>V1-V3 migrations)"]
    BankAPI["🏦 Bank API<br/>(settlement data)"]
    ReconciliationEventKafka["📬 Kafka<br/>(reconciliation.events)"]
    AdminAPI["🌐 Admin API<br/>GET /runs<br/>GET /mismatches"]
    Monitoring["📊 Prometheus<br/>(duration, success rate)"]

    PaymentSvc -->|1. Payment insert| Debezium
    Debezium -->|2. CDC event| KafkaReconciliation
    ReconciliationEngine -->|3. Consume| KafkaReconciliation
    ReconciliationEngine -->|4. Acquire lock| ShedLock
    ReconciliationEngine -->|5. Query ledger| PaymentSvc
    ReconciliationEngine -->|6. Fetch statement| BankAPI
    ReconciliationEngine -->|7. Compare & fix| ReconciliationEngine
    ReconciliationEngine -->|8. Persist| ReconciliationDB
    ReconciliationEngine -->|9. Publish event| ReconciliationEventKafka
    ReconciliationEngine -->|10. Metrics| Monitoring
    AdminAPI -->|Query runs| ReconciliationDB
    AdminAPI -->|Query mismatches| ReconciliationDB

    style ReconciliationEngine fill:#4A90E2,color:#fff
    style ReconciliationDB fill:#7ED321,color:#000
    style ShedLock fill:#F5A623,color:#000

    classDef sla fill:#52C41A,color:#fff
    class 7 sla
```
