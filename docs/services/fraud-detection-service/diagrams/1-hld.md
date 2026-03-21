# Fraud Detection Service - High-Level Design

```mermaid
flowchart TD
    subgraph Inbound["Inbound Sources"]
        REST["REST API<br/>/fraud/check"]
        ORDER_KAFKA["Kafka: order.events<br/>(velocity signals)"]
        PAYMENT_KAFKA["Kafka: payment.events<br/>(failure tracking)"]
    end

    subgraph FDS["fraud-detection-service :8090"]
        direction TB
        CTRL["FraudController<br/>(check endpoint)"]
        SCORING["FraudScoringService<br/>(orchestrator)"]
        RULES["RuleEvaluationService<br/>(VELOCITY, AMOUNT, DEVICE, GEO, PATTERN)"]
        VELOCITY["VelocityService<br/>(time-window counters)"]
        BLOCKLIST["BlocklistService<br/>(entity blocklist)"]
        CACHE["Rule Cache<br/>(Caffeine)"]
        OUTBOX["OutboxService<br/>(transactional outbox)"]
        ADMIN["AdminFraudController<br/>(rule CRUD)"]
    end

    subgraph Database["PostgreSQL"]
        RULES_TBL["fraud_rules<br/>(cached)"]
        SIGNALS_TBL["fraud_signals"]
        VELOCITY_TBL["velocity_counters"]
        BLOCKLIST_TBL["blocked_entities"]
        OUTBOX_TBL["outbox_events"]
    end

    subgraph Kafka["Kafka Topics"]
        FRAUD_EVENTS["fraud.events<br/>(via outbox relay)"]
        ORDER_EVENTS["order.events"]
        PAYMENT_EVENTS["payment.events"]
    end

    subgraph Observability["Observability"]
        PROM["Prometheus<br/>Metrics"]
        LOGS["Structured Logs<br/>(JSON)"]
    end

    REST --> CTRL
    ORDER_KAFKA --> VELOCITY
    PAYMENT_KAFKA --> VELOCITY

    CTRL --> SCORING
    SCORING --> BLOCKLIST
    SCORING --> RULES
    RULES --> VELOCITY
    RULES --> CACHE
    VELOCITY --> VELOCITY_TBL
    BLOCKLIST --> BLOCKLIST_TBL
    RULES --> RULES_TBL
    SCORING --> OUTBOX
    OUTBOX --> OUTBOX_TBL
    OUTBOX --> FRAUD_EVENTS

    ADMIN --> CACHE
    CACHE --> RULES_TBL
    SCORING --> SIGNALS_TBL

    SCORING --> PROM
    SCORING --> LOGS
```

## Key Characteristics

- **Multi-Stage Pipeline**: Blocklist → Velocity → Rules → Score → Action
- **5 Rule Types**: VELOCITY (time windows), AMOUNT, DEVICE, GEO/Haversine, PATTERN
- **Risk Levels**: LOW (0-25) → MEDIUM (26-50) → HIGH (51-75) → CRITICAL (76-100)
- **Actions**: ALLOW, FLAG, REVIEW, BLOCK (from risk level)
- **Velocity Tracking**: UPSERT 1h/24h time-windowed counters
- **Rule Cache**: Caffeine in-memory with invalidation on admin updates
- **Outbox Pattern**: Transactional fraud.events publishing via CDC relay
- **Observability**: Prometheus per-rule metrics + structured JSON logging
