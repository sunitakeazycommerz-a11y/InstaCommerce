# Payment Service - State Machine Diagrams

## Payment Lifecycle State Machine (Complete)

```mermaid
stateDiagram-v2
    [*] --> AUTHORIZE_PENDING: POST /payments/authorize<br/>Payment created

    AUTHORIZE_PENDING --> AUTHORIZED: Stripe authorization<br/>successful<br/>PaymentAuthorized event
    AUTHORIZE_PENDING --> FAILED: Stripe authorization<br/>failed<br/>(card_declined, insufficient_funds)<br/>PaymentFailed event

    AUTHORIZED --> CAPTURE_PENDING: POST /payments/{id}/capture<br/>Capture initiated

    CAPTURE_PENDING --> CAPTURED: Stripe capture<br/>successful<br/>PaymentCaptured event
    CAPTURE_PENDING --> AUTHORIZED: Stripe capture<br/>failed<br/>(retry possible)

    AUTHORIZED --> VOID_PENDING: POST /payments/{id}/void<br/>Void initiated

    VOID_PENDING --> VOIDED: Stripe void<br/>successful<br/>PaymentVoided event
    VOID_PENDING --> AUTHORIZED: Stripe void<br/>failed<br/>(retry possible)

    CAPTURED --> PARTIALLY_REFUNDED: Partial refund<br/>completed<br/>PaymentRefunded event

    PARTIALLY_REFUNDED --> PARTIALLY_REFUNDED: Additional<br/>partial refund

    PARTIALLY_REFUNDED --> REFUNDED: Final refund<br/>completes total<br/>PaymentFullyRefunded event

    CAPTURED --> REFUNDED: Full refund<br/>completed<br/>PaymentRefunded event

    CAPTURED --> DISPUTED: Stripe dispute<br/>webhook received<br/>PaymentDisputed event

    PARTIALLY_REFUNDED --> DISPUTED: Dispute on<br/>partially refunded payment

    DISPUTED --> CAPTURED: Dispute won<br/>Funds returned
    DISPUTED --> REFUNDED: Dispute lost<br/>Chargeback applied

    VOIDED --> [*]
    REFUNDED --> [*]
    FAILED --> [*]

    note right of AUTHORIZE_PENDING
        Hold funds on customer card
        No settlement yet
        pspReference set
    end note

    note right of AUTHORIZED
        Funds held but not settled
        Can be captured or voided
        Auth typically valid 7 days
    end note

    note right of CAPTURED
        Funds transferred to merchant
        Can be refunded (full/partial)
        Ledger: merchant_revenue credited
    end note

    note right of VOIDED
        Authorization released
        No funds transferred
        Ledger entries reversed
    end note

    note right of DISPUTED
        Customer disputed charge
        Funds may be held
        Requires evidence submission
    end note
```

## Valid State Transitions

```mermaid
graph LR
    subgraph InitialStates["Initial States"]
        AP["AUTHORIZE_PENDING"]
    end

    subgraph AuthorizedStates["Authorization States"]
        AUTH["AUTHORIZED"]
        CP["CAPTURE_PENDING"]
        VP["VOID_PENDING"]
    end

    subgraph TerminalStates["Terminal States"]
        CAPTURED["CAPTURED"]
        VOIDED["VOIDED"]
        FAILED["FAILED"]
        PR["PARTIALLY_REFUNDED"]
        REF["REFUNDED"]
        DISP["DISPUTED"]
    end

    AP -->|"gateway.authorize()<br/>success"| AUTH
    AP -->|"gateway.authorize()<br/>failed"| FAILED

    AUTH -->|"capture()"| CP
    AUTH -->|"void()"| VP

    CP -->|"gateway.capture()<br/>success"| CAPTURED
    CP -->|"gateway.capture()<br/>failed"| AUTH

    VP -->|"gateway.void()<br/>success"| VOIDED
    VP -->|"gateway.void()<br/>failed"| AUTH

    CAPTURED -->|"partial refund"| PR
    CAPTURED -->|"full refund"| REF
    CAPTURED -->|"dispute webhook"| DISP

    PR -->|"additional refund"| PR
    PR -->|"final refund"| REF
    PR -->|"dispute webhook"| DISP

    DISP -->|"dispute won"| CAPTURED
    DISP -->|"dispute lost"| REF

    style AP fill:#F5A623,color:#000
    style AUTH fill:#4A90E2,color:#fff
    style CAPTURED fill:#7ED321,color:#000
    style VOIDED fill:#9013FE,color:#fff
    style FAILED fill:#FF6B6B,color:#fff
    style REF fill:#50E3C2,color:#000
    style DISP fill:#FF6B6B,color:#fff
```

## Invalid State Transition Rules

```mermaid
graph TB
    subgraph rules["State Transition Validation"]
        R1["❌ FAILED → any<br/>(terminal state)"]
        R2["❌ VOIDED → any<br/>(terminal state)"]
        R3["❌ REFUNDED → any<br/>(terminal state)"]
        R4["❌ CAPTURED → AUTHORIZED<br/>(cannot reverse capture)"]
        R5["❌ AUTHORIZED → REFUNDED<br/>(must capture first)"]
        R6["❌ AUTHORIZE_PENDING → CAPTURED<br/>(must authorize first)"]
        R7["❌ VOID_PENDING → CAPTURED<br/>(conflicting operations)"]
        R8["❌ CAPTURE_PENDING → VOIDED<br/>(conflicting operations)"]
    end

    subgraph enforcement["Enforcement"]
        E1["PaymentService<br/>validates state<br/>before operation"]
        E2["PaymentInvalidStateException<br/>thrown on violation"]
        E3["HTTP 409 Conflict<br/>returned to client"]
    end

    R1 --> E1
    R4 --> E1
    E1 --> E2
    E2 --> E3

    style R1 fill:#FF6B6B,color:#fff
    style R4 fill:#FF6B6B,color:#fff
    style E3 fill:#F5A623,color:#000
```

## Refund Status State Machine

```mermaid
stateDiagram-v2
    [*] --> PENDING: POST /refunds<br/>Refund initiated

    PENDING --> COMPLETED: Stripe refund<br/>successful<br/>psp_refund_id set

    PENDING --> FAILED: Stripe refund<br/>failed<br/>(refund_failed, charge_expired)

    COMPLETED --> [*]
    FAILED --> [*]

    note right of PENDING
        Refund created locally
        Awaiting Stripe confirmation
        May take up to 5-10 days
        for bank processing
    end note

    note right of COMPLETED
        Refund processed by Stripe
        Funds released to customer
        (actual bank credit may take days)
    end note

    note right of FAILED
        Refund could not be processed
        Manual intervention required
        Original charge remains
    end note
```

## Webhook Event State Transitions

```mermaid
stateDiagram-v2
    [*] --> RECEIVED: Stripe webhook<br/>POST /webhooks/stripe

    RECEIVED --> SIGNATURE_VERIFIED: HMAC signature<br/>validated

    RECEIVED --> REJECTED: Invalid signature<br/>Return 401

    SIGNATURE_VERIFIED --> DEDUPLICATED: Check event_id<br/>in processed_webhook_events

    DEDUPLICATED --> ALREADY_PROCESSED: Event exists<br/>Return 200 OK (idempotent)

    DEDUPLICATED --> PROCESSING: New event<br/>Route to handler

    PROCESSING --> STATE_UPDATED: Payment/Refund<br/>state transitioned

    STATE_UPDATED --> LEDGER_UPDATED: Ledger entries<br/>recorded (if applicable)

    LEDGER_UPDATED --> EVENT_PERSISTED: ProcessedWebhookEvent<br/>saved

    EVENT_PERSISTED --> OUTBOX_WRITTEN: Outbox event<br/>created for downstream

    OUTBOX_WRITTEN --> COMMITTED: Transaction<br/>committed

    COMMITTED --> ACKNOWLEDGED: Return 200 OK<br/>to Stripe

    ACKNOWLEDGED --> [*]
    ALREADY_PROCESSED --> [*]
    REJECTED --> [*]

    note right of PROCESSING
        Handlers:
        - payment_intent.succeeded
        - payment_intent.payment_failed
        - charge.captured
        - charge.refunded
        - charge.dispute.created
        - refund.updated
    end note
```

## Ledger Entry State Flow

```mermaid
stateDiagram-v2
    [*] --> AUTHORIZATION_HOLD: Payment authorized<br/>DEBIT customer_receivables<br/>CREDIT merchant_payables

    AUTHORIZATION_HOLD --> CAPTURED_SETTLED: Capture successful<br/>DEBIT merchant_payables<br/>CREDIT merchant_revenue

    AUTHORIZATION_HOLD --> RELEASED: Void successful<br/>CREDIT customer_receivables<br/>DEBIT merchant_payables<br/>(reverse original)

    AUTHORIZATION_HOLD --> PARTIAL_RELEASE: Partial capture<br/>Release remaining auth

    PARTIAL_RELEASE --> CAPTURED_SETTLED

    CAPTURED_SETTLED --> PARTIALLY_REFUNDED: Partial refund<br/>DEBIT merchant_revenue<br/>CREDIT customer_receivables

    PARTIALLY_REFUNDED --> PARTIALLY_REFUNDED: Additional refund

    PARTIALLY_REFUNDED --> FULLY_REFUNDED: Final refund<br/>completes total

    CAPTURED_SETTLED --> FULLY_REFUNDED: Full refund

    RELEASED --> [*]
    FULLY_REFUNDED --> [*]

    note right of AUTHORIZATION_HOLD
        Invariant:
        SUM(DEBIT) = SUM(CREDIT)
        for payment_id
    end note

    note right of CAPTURED_SETTLED
        Merchant can access funds
        Revenue recognized
    end note
```

## Circuit Breaker State Machine (Stripe Gateway)

```mermaid
stateDiagram-v2
    [*] --> CLOSED: Gateway healthy<br/>normal operation

    CLOSED --> CALL_SUCCESS: Stripe call succeeds
    CALL_SUCCESS --> CLOSED: Reset failure counter

    CLOSED --> CALL_FAILURE: Stripe call fails<br/>(timeout, 5xx, rate limit)

    CALL_FAILURE --> INCREMENT: failure_count++

    INCREMENT --> CLOSED: failure_count < threshold<br/>(threshold = 5)

    INCREMENT --> OPEN: failure_count >= threshold<br/>OR error_rate > 50%

    OPEN --> FAIL_FAST: Reject all calls<br/>immediately

    FAIL_FAST --> METRICS: Emit<br/>circuit_breaker_open_total

    METRICS --> WAIT: waitDurationInOpenState<br/>= 60 seconds

    WAIT --> HALF_OPEN: Timer expires<br/>Allow 1-2 test calls

    HALF_OPEN --> TEST_CALL: Send probe to Stripe

    TEST_CALL --> CLOSED: ✅ Probe succeeds<br/>Circuit closes

    TEST_CALL --> OPEN: ❌ Probe fails<br/>Extend wait period

    note right of CLOSED
        Normal operation
        All calls go to Stripe
        Failure counter tracks
        recent failures
    end note

    note right of OPEN
        Fast fail mode
        All calls rejected
        Return cached/default response
        Reduce load on Stripe
    end note

    note right of HALF_OPEN
        Testing recovery
        1-2 probe requests
        Decide to close or reopen
    end note
```

## Idempotency Key Lifecycle

```mermaid
stateDiagram-v2
    [*] --> GENERATED: Client request arrives<br/>Generate key: order_id + operation

    GENERATED --> CACHE_CHECK: Check Redis<br/>GET idempotency:{key}

    CACHE_CHECK --> CACHE_HIT: Key exists in Redis<br/>Return cached payment_id

    CACHE_HIT --> DB_LOOKUP: Lookup payment by ID

    DB_LOOKUP --> RETURN_EXISTING: Return existing<br/>PaymentResponse

    CACHE_CHECK --> CACHE_MISS: Key not in Redis

    CACHE_MISS --> DB_CHECK: Check payments table<br/>WHERE idempotency_key = key

    DB_CHECK --> DB_HIT: Payment exists

    DB_HIT --> CACHE_BACKFILL: Write to Redis<br/>(cache was evicted)

    CACHE_BACKFILL --> RETURN_EXISTING

    DB_CHECK --> DB_MISS: Payment not found<br/>Process new request

    DB_MISS --> PROCESSING: Create payment<br/>Execute operation

    PROCESSING --> SUCCESS: Operation completed

    SUCCESS --> PERSIST_CACHE: SET idempotency:{key}<br/>TTL: 24 hours

    PERSIST_CACHE --> PERSIST_DB: Payment saved<br/>with idempotency_key

    PERSIST_DB --> RETURN_NEW: Return new<br/>PaymentResponse

    RETURN_EXISTING --> [*]
    RETURN_NEW --> [*]

    note right of GENERATED
        Key format:
        authorize: order_id:authorize
        capture: payment_id:capture:amount
        refund: payment_id:refund:amount
    end note

    note right of PERSIST_CACHE
        Redis TTL: 24 hours
        DB stores key permanently
        Handles cache eviction
    end note
```

## Payment Status Metric States

```mermaid
graph TB
    subgraph MetricDimensions["Metric Dimensions"]
        StatusGauge["payment_status_gauge<br/>{status=..., currency=...}"]
        TransitionCounter["payment_state_transitions_total<br/>{from_status=..., to_status=..., success=...}"]
        LatencyHistogram["payment_operation_duration_ms<br/>{operation=..., status=...}"]
    end

    subgraph SLOTracking["SLO Tracking"]
        AuthSLO["Authorization SLO<br/>p99 < 500ms<br/>success_rate > 99%"]
        CaptureSLO["Capture SLO<br/>p99 < 300ms<br/>success_rate > 99.9%"]
        RefundSLO["Refund SLO<br/>p99 < 400ms<br/>success_rate > 99%"]
    end

    subgraph Alerts["Alert Rules"]
        HighFailure["🔴 SEV-1: failure_rate > 5%<br/>in 5 min window"]
        ElevatedLatency["🟠 SEV-2: p99 > 750ms<br/>in 15 min window"]
        DisputeSpike["🟡 SEV-3: dispute_rate > 0.1%"]
    end

    StatusGauge --> AuthSLO
    TransitionCounter --> CaptureSLO
    LatencyHistogram --> RefundSLO

    AuthSLO --> HighFailure
    CaptureSLO --> ElevatedLatency
    LatencyHistogram --> DisputeSpike

    style HighFailure fill:#FF6B6B,color:#fff
    style ElevatedLatency fill:#F5A623,color:#000
    style DisputeSpike fill:#FFC107,color:#000
```
