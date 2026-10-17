# Payment Service - ER Diagram & Storage Schema

## Core Entity Relationships

```mermaid
erDiagram
    PAYMENTS ||--o{ REFUNDS : "has"
    PAYMENTS ||--o{ LEDGER_ENTRIES : "records"
    REFUNDS ||--o{ LEDGER_ENTRIES : "records"
    PAYMENTS ||--o{ OUTBOX_EVENTS : "publishes"
    REFUNDS ||--o{ OUTBOX_EVENTS : "publishes"
    PAYMENTS ||--o{ AUDIT_LOGS : "tracks"
    REFUNDS ||--o{ AUDIT_LOGS : "tracks"

    PAYMENTS {
        uuid id PK "Primary key (UUID v4)"
        uuid order_id FK "Reference to Order Service"
        bigint amount_cents "Authorized amount in cents"
        bigint captured_cents "Captured amount (0 initially)"
        bigint refunded_cents "Total refunded amount"
        varchar currency "ISO 4217 (USD, EUR, GBP, INR)"
        payment_status status "AUTHORIZE_PENDING, AUTHORIZED, etc."
        varchar psp_reference "Stripe PaymentIntent ID (pi_xxx)"
        varchar idempotency_key "Unique per authorize request"
        varchar payment_method "card, upi, wallet"
        jsonb metadata "Additional metadata"
        timestamp created_at "Creation timestamp (UTC)"
        timestamp updated_at "Last update timestamp"
        bigint version "OCC version for locking"
    }

    REFUNDS {
        uuid id PK "Primary key (UUID v4)"
        uuid payment_id FK "Reference to payments.id"
        bigint amount_cents "Refund amount in cents"
        varchar reason "Refund reason (optional)"
        varchar psp_refund_id "Stripe Refund ID (re_xxx)"
        varchar idempotency_key "Unique per refund request"
        refund_status status "PENDING, COMPLETED, FAILED"
        timestamp created_at "Creation timestamp"
        timestamp updated_at "Last update timestamp"
        bigint version "OCC version"
    }

    LEDGER_ENTRIES {
        bigserial id PK "Auto-increment ID"
        uuid payment_id FK "Reference to payments.id"
        ledger_entry_type entry_type "DEBIT or CREDIT"
        bigint amount_cents "Entry amount"
        varchar account "Account name (e.g., customer_receivables)"
        varchar reference_type "PAYMENT, REFUND, VOID"
        varchar reference_id "Source entity ID"
        varchar description "Human-readable description"
        timestamp created_at "Entry timestamp"
    }

    OUTBOX_EVENTS {
        uuid id PK "Primary key (UUID v4)"
        varchar aggregate_type "PAYMENT, REFUND"
        varchar aggregate_id "Payment/Refund ID"
        varchar event_type "PaymentAuthorized, PaymentCaptured, etc."
        jsonb payload "Event payload JSON"
        timestamp created_at "Creation timestamp"
        boolean sent "Relay status (Debezium)"
        uuid event_id "Standard envelope: event_id"
        varchar schema_version "v1, v2, etc."
        varchar source_service "payment-service"
        varchar correlation_id "Request correlation ID"
        timestamp event_timestamp "Event timestamp"
    }

    AUDIT_LOGS {
        uuid id PK "Primary key"
        uuid entity_id FK "Payment or Refund ID"
        varchar entity_type "PAYMENT, REFUND"
        varchar action "AUTHORIZE, CAPTURE, VOID, REFUND"
        varchar actor "User ID or SYSTEM"
        varchar actor_type "USER, SYSTEM, WEBHOOK"
        jsonb old_state "State before action"
        jsonb new_state "State after action"
        varchar correlation_id "Request correlation ID"
        timestamp created_at "Audit timestamp"
    }

    PROCESSED_WEBHOOK_EVENTS {
        varchar event_id PK "Stripe event ID (evt_xxx)"
        varchar event_type "payment_intent.succeeded, etc."
        timestamp processed_at "When processed"
        uuid payment_id FK "Associated payment (optional)"
    }

    SHEDLOCK {
        varchar name PK "Lock name"
        timestamp lock_until "Lock expiry"
        timestamp locked_at "Lock acquisition time"
        varchar locked_by "Instance identifier"
    }
```

## Payments Table Schema

```sql
CREATE TYPE payment_status AS ENUM (
    'AUTHORIZE_PENDING',
    'AUTHORIZED',
    'CAPTURE_PENDING',
    'CAPTURED',
    'VOID_PENDING',
    'VOIDED',
    'PARTIALLY_REFUNDED',
    'REFUNDED',
    'FAILED',
    'DISPUTED'
);

CREATE TABLE payments (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    order_id UUID NOT NULL,
    amount_cents BIGINT NOT NULL CHECK (amount_cents > 0),
    captured_cents BIGINT NOT NULL DEFAULT 0 CHECK (captured_cents >= 0),
    refunded_cents BIGINT NOT NULL DEFAULT 0 CHECK (refunded_cents >= 0),
    currency VARCHAR(3) NOT NULL,
    status payment_status NOT NULL DEFAULT 'AUTHORIZE_PENDING',
    psp_reference VARCHAR(255),
    idempotency_key VARCHAR(255) NOT NULL,
    payment_method VARCHAR(50),
    metadata JSONB,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    version BIGINT NOT NULL DEFAULT 0,
    
    CONSTRAINT payments_idempotency_key_unique UNIQUE (idempotency_key),
    CONSTRAINT payments_refunded_lte_captured CHECK (refunded_cents <= captured_cents),
    CONSTRAINT payments_captured_lte_amount CHECK (captured_cents <= amount_cents)
);

-- Indexes for common queries
CREATE INDEX idx_payments_order_id ON payments(order_id);
CREATE INDEX idx_payments_status ON payments(status);
CREATE INDEX idx_payments_psp_reference ON payments(psp_reference);
CREATE INDEX idx_payments_created_at ON payments(created_at);
CREATE INDEX idx_payments_status_created_at ON payments(status, created_at) 
    WHERE status IN ('AUTHORIZE_PENDING', 'CAPTURE_PENDING', 'VOID_PENDING');
```

## Ledger Entries Table Schema

```sql
CREATE TYPE ledger_entry_type AS ENUM ('DEBIT', 'CREDIT');

CREATE TABLE ledger_entries (
    id BIGSERIAL PRIMARY KEY,
    payment_id UUID NOT NULL REFERENCES payments(id),
    entry_type ledger_entry_type NOT NULL,
    amount_cents BIGINT NOT NULL CHECK (amount_cents > 0),
    account VARCHAR(100) NOT NULL,
    reference_type VARCHAR(50) NOT NULL,
    reference_id VARCHAR(255),
    description TEXT,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    
    CONSTRAINT ledger_entries_dedup UNIQUE (payment_id, entry_type, account, reference_type, reference_id)
);

-- Indexes for ledger queries
CREATE INDEX idx_ledger_entries_payment_id ON ledger_entries(payment_id);
CREATE INDEX idx_ledger_entries_account ON ledger_entries(account);
CREATE INDEX idx_ledger_entries_created_at ON ledger_entries(created_at);

-- View for ledger balance by payment
CREATE VIEW payment_ledger_balances AS
SELECT 
    payment_id,
    SUM(CASE WHEN entry_type = 'DEBIT' THEN amount_cents ELSE 0 END) AS total_debits,
    SUM(CASE WHEN entry_type = 'CREDIT' THEN amount_cents ELSE 0 END) AS total_credits,
    SUM(CASE WHEN entry_type = 'DEBIT' THEN amount_cents ELSE 0 END) -
    SUM(CASE WHEN entry_type = 'CREDIT' THEN amount_cents ELSE 0 END) AS balance
FROM ledger_entries
GROUP BY payment_id;
```

## Redis Cache Schema & Keys

```mermaid
graph TB
    Redis["Redis<br/>Cache Cluster"]

    IdempotencyKey["🔑 idempotency:{key}<br/>Type: String<br/>TTL: 24 hours<br/>Value: payment_id (UUID)"]

    PaymentCache["💳 payment:{id}<br/>Type: JSON<br/>TTL: 5 min<br/>Value: PaymentResponse"]

    RateLimit["🚦 rate_limit:{client_id}<br/>Type: Integer Counter<br/>TTL: 1 minute<br/>Value: request count"]

    CircuitBreaker["🔴 circuit:{gateway}<br/>Type: Hash<br/>TTL: none (persistent)<br/>Fields: state, failure_count, last_failure"]

    Redis --> IdempotencyKey
    Redis --> PaymentCache
    Redis --> RateLimit
    Redis --> CircuitBreaker

    style Redis fill:#F5A623,color:#000,stroke:#333,stroke-width:2px
```

## Account Structure (Double-Entry Bookkeeping)

```mermaid
graph TB
    subgraph Accounts["Ledger Accounts"]
        CustomerReceivables["customer_receivables<br/>(Asset)<br/>What customer owes"]
        MerchantPayables["merchant_payables<br/>(Liability)<br/>What we owe merchant"]
        MerchantRevenue["merchant_revenue<br/>(Revenue)<br/>Settled funds"]
        RefundPayables["refund_payables<br/>(Liability)<br/>Pending refunds"]
    end

    subgraph Operations["Operations"]
        Auth["Authorization"]
        Cap["Capture"]
        Void["Void"]
        Ref["Refund"]
    end

    Auth -->|"DEBIT +1000"| CustomerReceivables
    Auth -->|"CREDIT +1000"| MerchantPayables

    Cap -->|"DEBIT +1000"| MerchantPayables
    Cap -->|"CREDIT +1000"| MerchantRevenue

    Void -->|"CREDIT +1000"| CustomerReceivables
    Void -->|"DEBIT +1000"| MerchantPayables

    Ref -->|"DEBIT +500"| MerchantRevenue
    Ref -->|"CREDIT +500"| CustomerReceivables

    style CustomerReceivables fill:#4A90E2,color:#fff
    style MerchantRevenue fill:#7ED321,color:#000
    style MerchantPayables fill:#F5A623,color:#000
```

## Ledger Entry Examples

```
┌─────────────────────────────────────────────────────────────────────────┐
│  Authorization: $100.00 (10000 cents)                                   │
├─────────────────────────────────────────────────────────────────────────┤
│  Entry 1: DEBIT   customer_receivables    +10000 (Asset ↑)             │
│  Entry 2: CREDIT  merchant_payables       +10000 (Liability ↑)          │
│                                                                         │
│  Balance Check: DEBIT(10000) = CREDIT(10000) ✅                        │
└─────────────────────────────────────────────────────────────────────────┘

┌─────────────────────────────────────────────────────────────────────────┐
│  Capture: $100.00 (10000 cents)                                         │
├─────────────────────────────────────────────────────────────────────────┤
│  Entry 3: DEBIT   merchant_payables       +10000 (Liability ↓)         │
│  Entry 4: CREDIT  merchant_revenue        +10000 (Revenue ↑)           │
│                                                                         │
│  Balance Check: DEBIT(20000) = CREDIT(20000) ✅                        │
└─────────────────────────────────────────────────────────────────────────┘

┌─────────────────────────────────────────────────────────────────────────┐
│  Partial Refund: $30.00 (3000 cents)                                    │
├─────────────────────────────────────────────────────────────────────────┤
│  Entry 5: DEBIT   merchant_revenue        +3000  (Revenue ↓)           │
│  Entry 6: CREDIT  customer_receivables    +3000  (Asset ↓)             │
│                                                                         │
│  Balance Check: DEBIT(23000) = CREDIT(23000) ✅                        │
│  Net Revenue: 10000 - 3000 = 7000 cents ($70.00)                       │
└─────────────────────────────────────────────────────────────────────────┘
```

## Outbox Event Schema (Debezium CDC)

```mermaid
graph TB
    subgraph OutboxTable["outbox_events Table"]
        Event["Event Record"]
        Columns["id: UUID<br/>aggregate_type: PAYMENT<br/>aggregate_id: payment_id<br/>event_type: PaymentCaptured<br/>payload: {JSON}<br/>sent: false<br/>event_id: UUID<br/>schema_version: v1<br/>source_service: payment-service<br/>correlation_id: req-123<br/>event_timestamp: 2024-01-15T10:30:00Z"]
    end

    subgraph Debezium["Debezium CDC"]
        Connector["PostgreSQL Connector<br/>(outbox.EventRouter)"]
        Transform["Outbox Transform<br/>(extract payload)"]
    end

    subgraph Kafka["Kafka"]
        Topic["payment.events<br/>(topic)"]
        Message["Key: payment_id<br/>Value: event payload<br/>Headers: event_type, correlation_id"]
    end

    OutboxTable --> Debezium
    Debezium --> Kafka

    style Debezium fill:#FF6B6B,color:#fff
    style Topic fill:#231F20,color:#fff
```

## Prometheus Metrics Schema

```
Payment Service Metrics
├─ payment_authorizations_total{status, currency, payment_method}
│  └ Counter: Total authorization attempts
│
├─ payment_captures_total{status, partial}
│  └ Counter: Total capture attempts
│     - partial: true/false
│
├─ payment_refunds_total{status, reason}
│  └ Counter: Total refund attempts
│
├─ payment_operation_duration_ms{operation, status}
│  └ Histogram: Operation latency
│     - operation: authorize, capture, void, refund
│     - buckets: 10, 50, 100, 200, 500, 1000, 2000
│
├─ stripe_api_calls_total{endpoint, status_code}
│  └ Counter: Stripe API call count
│
├─ stripe_api_latency_ms{endpoint}
│  └ Histogram: Stripe API latency
│
├─ ledger_entries_total{entry_type, account}
│  └ Counter: Ledger entries created
│
├─ ledger_balance_imbalance_total
│  └ Counter: Integrity violations detected
│
├─ circuit_breaker_state{gateway}
│  └ Gauge: 0=CLOSED, 1=OPEN, 2=HALF_OPEN
│
├─ webhook_events_processed_total{event_type, outcome}
│  └ Counter: Webhooks processed
│     - outcome: success, duplicate, invalid_signature
│
└─ outbox_events_pending_gauge
   └ Gauge: Unsent outbox events count
```

## Observability Pipeline

```mermaid
graph LR
    PaymentService["Payment Service"]

    subgraph Metrics["📊 Metrics Pipeline"]
        Prometheus["Prometheus<br/>/actuator/prometheus"]
        Grafana["Grafana<br/>Dashboards"]
        Alertmanager["Alertmanager"]
    end

    subgraph Logs["📝 Logging Pipeline"]
        Logback["Logback<br/>(JSON structured)"]
        ELK["ELK Stack"]
    end

    subgraph Traces["📈 Tracing Pipeline"]
        OTEL["OpenTelemetry<br/>Instrumentation"]
        Jaeger["Jaeger"]
    end

    subgraph Events["📬 Event Pipeline"]
        Outbox["Outbox Events"]
        Debezium["Debezium CDC"]
        Kafka["Kafka<br/>payment.events"]
    end

    PaymentService -->|Micrometer| Prometheus
    Prometheus --> Grafana
    Prometheus --> Alertmanager

    PaymentService -->|JSON logs| Logback
    Logback --> ELK

    PaymentService -->|Spans| OTEL
    OTEL --> Jaeger

    PaymentService -->|INSERT| Outbox
    Outbox --> Debezium
    Debezium --> Kafka

    style PaymentService fill:#4A90E2,color:#fff
    style Kafka fill:#231F20,color:#fff
    style Alertmanager fill:#FF6B6B,color:#fff
```
