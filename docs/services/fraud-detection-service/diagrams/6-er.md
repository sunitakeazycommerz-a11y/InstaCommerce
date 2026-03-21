# Fraud Detection Service - Data Model

```mermaid
erDiagram
    FRAUD_CHECK_REQUEST ||--o{ BLOCKLIST_CHECK : initiates
    FRAUD_CHECK_REQUEST ||--o{ RULE_EVALUATION : triggers
    BLOCKLIST_CHECK ||--o{ BLOCKED_ENTITY : looks_up
    RULE_EVALUATION ||--o{ FRAUD_RULE : applies

    FRAUD_CHECK_REQUEST {
        string request_id PK
        uuid order_id
        uuid customer_id
        decimal amount
        string device_id
        string ip_address
        string merchant_category
        timestamp created_at
    }

    BLOCKED_ENTITY {
        string entity_id PK
        string entity_type
        string reason
        timestamp blocked_at
        timestamp expires_at
    }

    FRAUD_RULE {
        uuid rule_id PK
        string rule_type
        string name
        json condition
        int impact_score
        boolean enabled
        timestamp created_at
    }

    RULE_EVALUATION {
        string request_id FK
        uuid rule_id FK
        int score_contribution
        boolean triggered
        timestamp evaluated_at
    }

    VELOCITY_COUNTER {
        string counter_key PK
        string window_type
        int count
        timestamp window_start
        timestamp window_end
        timestamp updated_at
    }

    FRAUD_SIGNAL {
        string signal_id PK
        string request_id FK
        uuid order_id
        uuid customer_id
        int score
        string risk_level
        string action
        json rule_triggers
        timestamp created_at
    }

    OUTBOX_EVENT {
        uuid event_id PK
        string event_type
        string signal_id FK
        json payload
        boolean published
        timestamp created_at
        timestamp published_at
    }

    METRICS_RECORD {
        string request_id FK
        int score
        int latency_ms
        int rules_triggered
        timestamp recorded_at
    }

    FRAUD_CHECK_REQUEST ||--o{ VELOCITY_COUNTER : increments
    RULE_EVALUATION ||--o{ VELOCITY_COUNTER : reads
    FRAUD_CHECK_REQUEST ||--o{ FRAUD_SIGNAL : creates
    FRAUD_SIGNAL ||--o{ OUTBOX_EVENT : publishes_via
    FRAUD_CHECK_REQUEST ||--o{ METRICS_RECORD : generates
```

## Key Entities

| Entity | Purpose |
|--------|---------|
| **FRAUD_CHECK_REQUEST** | Incoming fraud check request |
| **BLOCKED_ENTITY** | Customer/payment method blocklist entry |
| **FRAUD_RULE** | Rule definition (cached in Caffeine) |
| **RULE_EVALUATION** | Per-rule scoring result |
| **VELOCITY_COUNTER** | Time-windowed transaction count (1h, 24h) |
| **FRAUD_SIGNAL** | Fraud check result + decision |
| **OUTBOX_EVENT** | Transactional fraud.events publishing |
| **METRICS_RECORD** | Latency and trigger metrics |
