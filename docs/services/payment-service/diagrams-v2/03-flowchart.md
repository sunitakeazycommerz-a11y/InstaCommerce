# Payment Service - Request Flowcharts

## Complete Authorization Flow

```mermaid
flowchart TD
    A["🌐 HTTP Request<br/>POST /payments/authorize"]
    B["Extract Authorization<br/>header (JWT)"]
    C{{"JWT<br/>valid?"}}
    D["❌ Return 401<br/>Unauthorized"]
    E["Extract correlation_id<br/>from X-Correlation-Id header"]
    F["Validate request body<br/>(order_id, amount, currency, payment_method)"]
    G{{"Request<br/>valid?"}}
    H["❌ Return 400<br/>Bad Request"]
    I["Generate idempotency_key<br/>(order_id + 'authorize')"]
    J["Check idempotency<br/>(Redis cache first, then DB)"]
    K{{"Duplicate<br/>request?"}}
    L["Return existing<br/>PaymentResponse"]
    M["Validate currency<br/>(USD, EUR, GBP, INR)"]
    N{{"Currency<br/>supported?"}}
    O["❌ Return 400<br/>InvalidCurrencyException"]
    P["Create Payment entity<br/>status: AUTHORIZE_PENDING"]
    Q["Save Payment to DB<br/>(within transaction)"]
    R["Call StripePaymentGateway<br/>.authorize()"]
    S{{"Stripe API<br/>success?"}}
    T["Update Payment<br/>status: AUTHORIZED<br/>pspReference: pi_xxx"]
    U["Update Payment<br/>status: FAILED<br/>Store error details"]
    V["Create ledger entries<br/>DEBIT customer_receivables<br/>CREDIT merchant_payables"]
    W["Create outbox event<br/>(PaymentAuthorized/PaymentFailed)"]
    X["Create audit log entry<br/>(AUTHORIZE action)"]
    Y["Cache idempotency key<br/>(Redis, TTL: 24h)"]
    Z["✅ Return 200 OK<br/>PaymentResponse"]

    A --> B --> C
    C -->|No| D
    C -->|Yes| E
    E --> F --> G
    G -->|No| H
    G -->|Yes| I
    I --> J --> K
    K -->|Yes| L --> Z
    K -->|No| M --> N
    N -->|No| O
    N -->|Yes| P
    P --> Q --> R --> S
    S -->|Yes| T --> V
    S -->|No| U --> V
    V --> W --> X --> Y --> Z

    style T fill:#7ED321,color:#000,stroke:#333,stroke-width:2px
    style U fill:#FF6B6B,color:#fff,stroke:#333,stroke-width:2px
    style Z fill:#7ED321,color:#000,stroke:#333,stroke-width:2px
    style D fill:#FF6B6B,color:#fff
    style H fill:#FF6B6B,color:#fff
    style O fill:#FF6B6B,color:#fff
```

## Capture Flow

```mermaid
flowchart TD
    A["🌐 HTTP Request<br/>POST /payments/{id}/capture"]
    B["✅ JWT validated"]
    C["Lookup Payment by ID"]
    D{{"Payment<br/>found?"}}
    E["❌ Return 404<br/>PaymentNotFoundException"]
    F["Check current status"]
    G{{"Status ==<br/>AUTHORIZED?"}}
    H["❌ Return 409<br/>PaymentInvalidStateException"]
    I["Validate capture amount<br/>(optional partial capture)"]
    J{{"Amount <=<br/>authorized_cents?"}}
    K["❌ Return 400<br/>InvalidCaptureAmountException"]
    L["Generate idempotency_key<br/>(payment_id + 'capture' + amount)"]
    M["Check idempotency"]
    N{{"Duplicate?"}}
    O["Return existing response"]
    P["Set status: CAPTURE_PENDING<br/>Lock record (OCC version)"]
    Q["Call StripePaymentGateway<br/>.capture(pspReference, amount)"]
    R{{"Stripe<br/>success?"}}
    S["Update status: CAPTURED<br/>Set captured_cents"]
    T["Revert to AUTHORIZED<br/>Log capture failure"]
    U["Create ledger entries<br/>DEBIT merchant_payables<br/>CREDIT merchant_revenue"]
    V["Check if partial capture<br/>release remaining auth"]
    W{{"Partial<br/>capture?"}}
    X["Create release ledger entries<br/>for remaining amount"]
    Y["Create outbox event<br/>(PaymentCaptured)"]
    Z["✅ Return 200 OK"]

    A --> B --> C --> D
    D -->|No| E
    D -->|Yes| F --> G
    G -->|No| H
    G -->|Yes| I --> J
    J -->|No| K
    J -->|Yes| L --> M --> N
    N -->|Yes| O --> Z
    N -->|No| P --> Q --> R
    R -->|Yes| S --> U --> V --> W
    R -->|No| T --> Z
    W -->|Yes| X --> Y
    W -->|No| Y --> Z

    style S fill:#7ED321,color:#000,stroke:#333,stroke-width:2px
    style T fill:#FF6B6B,color:#fff
    style Z fill:#7ED321,color:#000,stroke:#333,stroke-width:2px
```

## Void Flow

```mermaid
flowchart TD
    A["🌐 HTTP Request<br/>POST /payments/{id}/void"]
    B["✅ JWT validated"]
    C["Lookup Payment by ID"]
    D{{"Payment<br/>found?"}}
    E["❌ Return 404"]
    F["Check current status"]
    G{{"Status ==<br/>AUTHORIZED?"}}
    H["❌ Return 409<br/>Cannot void captured payment"]
    I["Set status: VOID_PENDING"]
    J["Call StripePaymentGateway<br/>.void(pspReference)"]
    K{{"Stripe<br/>success?"}}
    L["Update status: VOIDED"]
    M["Revert to AUTHORIZED<br/>Log void failure"]
    N["Create ledger entries<br/>(reverse authorization)<br/>CREDIT customer_receivables<br/>DEBIT merchant_payables"]
    O["Create outbox event<br/>(PaymentVoided)"]
    P["✅ Return 200 OK"]

    A --> B --> C --> D
    D -->|No| E
    D -->|Yes| F --> G
    G -->|No| H
    G -->|Yes| I --> J --> K
    K -->|Yes| L --> N --> O --> P
    K -->|No| M --> P

    style L fill:#7ED321,color:#000,stroke:#333,stroke-width:2px
    style M fill:#FF6B6B,color:#fff
```

## Refund Flow

```mermaid
flowchart TD
    A["🌐 HTTP Request<br/>POST /refunds"]
    B["✅ JWT validated"]
    C["Validate request<br/>(payment_id, amount_cents, reason)"]
    D{{"Request<br/>valid?"}}
    E["❌ Return 400"]
    F["Lookup Payment by ID"]
    G{{"Payment<br/>found?"}}
    H["❌ Return 404"]
    I["Check payment status"]
    J{{"Status in<br/>[CAPTURED,<br/>PARTIALLY_REFUNDED]?"}}
    K["❌ Return 409<br/>Cannot refund uncaptured payment"]
    L["Calculate refundable amount<br/>= captured_cents - refunded_cents"]
    M{{"Requested <=<br/>refundable?"}}
    N["❌ Return 400<br/>RefundExceedsChargeException"]
    O["Generate idempotency_key<br/>(payment_id + 'refund' + amount)"]
    P["Check existing refund<br/>with same idempotency_key"]
    Q{{"Duplicate?"}}
    R["Return existing RefundResponse"]
    S["Create Refund entity<br/>status: PENDING"]
    T["Save Refund to DB"]
    U["Call StripePaymentGateway<br/>.refund(pspReference, amount)"]
    V{{"Stripe<br/>success?"}}
    W["Update Refund: COMPLETED<br/>Set psp_refund_id"]
    X["Update Refund: FAILED"]
    Y["Update Payment.refunded_cents<br/>Set status: REFUNDED or<br/>PARTIALLY_REFUNDED"]
    Z["Create ledger entries<br/>DEBIT merchant_revenue<br/>CREDIT customer_receivables"]
    AA["Create outbox event<br/>(PaymentRefunded)"]
    AB["✅ Return 200 OK<br/>RefundResponse"]

    A --> B --> C --> D
    D -->|No| E
    D -->|Yes| F --> G
    G -->|No| H
    G -->|Yes| I --> J
    J -->|No| K
    J -->|Yes| L --> M
    M -->|No| N
    M -->|Yes| O --> P --> Q
    Q -->|Yes| R --> AB
    Q -->|No| S --> T --> U --> V
    V -->|Yes| W --> Y --> Z --> AA --> AB
    V -->|No| X --> AB

    style W fill:#7ED321,color:#000,stroke:#333,stroke-width:2px
    style X fill:#FF6B6B,color:#fff
    style AB fill:#7ED321,color:#000,stroke:#333,stroke-width:2px
```

## Webhook Processing Flow

```mermaid
flowchart TD
    A["📨 Stripe Webhook<br/>POST /webhooks/stripe"]
    B["Extract Stripe-Signature header"]
    C["Extract raw request body"]
    D["WebhookSignatureVerifier<br/>Verify HMAC-SHA256"]
    E{{"Signature<br/>valid?"}}
    F["❌ Return 401<br/>Log: WEBHOOK_SIGNATURE_INVALID"]
    G["Parse JSON payload<br/>Extract event_id, event_type"]
    H["Check ProcessedWebhookEvent<br/>(deduplication by event_id)"]
    I{{"Already<br/>processed?"}}
    J["Return 200 OK<br/>(idempotent success)"]
    K["Route by event_type"]
    
    subgraph handlers["Event Handlers"]
        L1["payment_intent.succeeded"]
        L2["payment_intent.payment_failed"]
        L3["charge.refunded"]
        L4["charge.dispute.created"]
        L5["refund.updated"]
    end

    M["Extract payment reference<br/>from event data"]
    N["Lookup Payment by pspReference"]
    O{{"Payment<br/>found?"}}
    P["Log: WEBHOOK_PAYMENT_NOT_FOUND<br/>Return 200 OK (ignore)"]
    Q["Begin transaction"]
    R["Update Payment/Refund state"]
    S["Create ledger entries<br/>(if state change)"]
    T["Save ProcessedWebhookEvent<br/>(event_id, processed_at)"]
    U["Create outbox event"]
    V["Commit transaction"]
    W["✅ Return 200 OK"]

    A --> B --> C --> D --> E
    E -->|No| F
    E -->|Yes| G --> H --> I
    I -->|Yes| J
    I -->|No| K --> handlers
    handlers --> M --> N --> O
    O -->|No| P
    O -->|Yes| Q --> R --> S --> T --> U --> V --> W

    style D fill:#7ED321,color:#000,stroke:#333,stroke-width:2px
    style F fill:#FF6B6B,color:#fff
    style W fill:#7ED321,color:#000,stroke:#333,stroke-width:2px
```

## Reconciliation Job Flow

```mermaid
flowchart TD
    A["⏰ Scheduled Job<br/>StalePendingPaymentRecoveryJob"]
    B["Acquire ShedLock<br/>(stale-payment-recovery)"]
    C{{"Lock<br/>acquired?"}}
    D["Skip execution<br/>(another instance running)"]
    E["Query payments WHERE<br/>status IN (AUTHORIZE_PENDING,<br/>CAPTURE_PENDING, VOID_PENDING)<br/>AND created_at < now - 30min"]
    F{{"Stale payments<br/>found?"}}
    G["Log: No stale payments"]
    H["For each stale payment"]
    I["Query Stripe API<br/>GET /payment_intents/{pspReference}"]
    J["Compare local vs PSP status"]
    K{{"Status<br/>mismatch?"}}
    L["Log: Payment in sync"]
    M["Update local status<br/>to match PSP"]
    N["Create corrective<br/>ledger entries"]
    O["Create outbox event<br/>(PaymentReconciled)"]
    P["Emit metric<br/>payment_reconciliation_corrections_total"]
    Q["Release ShedLock"]
    R["✅ Job completed"]

    A --> B --> C
    C -->|No| D
    C -->|Yes| E --> F
    F -->|No| G --> Q
    F -->|Yes| H --> I --> J --> K
    K -->|No| L --> H
    K -->|Yes| M --> N --> O --> P --> H
    H -->|Done| Q --> R

    style M fill:#F5A623,color:#000,stroke:#333,stroke-width:2px
    style P fill:#9013FE,color:#fff
```

## Ledger Integrity Verification Flow

```mermaid
flowchart TD
    A["⏰ Scheduled Job<br/>LedgerIntegrityVerificationJob<br/>(hourly)"]
    B["Acquire ShedLock"]
    C["Query all payments<br/>with ledger entries"]
    D["For each payment"]
    E["Calculate SUM(DEBIT)<br/>for payment_id"]
    F["Calculate SUM(CREDIT)<br/>for payment_id"]
    G{{"DEBIT ==<br/>CREDIT?"}}
    H["✅ Payment balanced"]
    I["❌ Imbalance detected!"]
    J["Log: LEDGER_IMBALANCE<br/>payment_id, debit_sum, credit_sum"]
    K["Emit alert metric<br/>ledger_imbalance_detected"]
    L["Send PagerDuty alert<br/>(if production)"]
    M["Mark for manual review"]
    N["Continue to next payment"]
    O["Release ShedLock"]
    P["Report summary<br/>total_checked, imbalances_found"]

    A --> B --> C --> D
    D --> E --> F --> G
    G -->|Yes| H --> N
    G -->|No| I --> J --> K --> L --> M --> N
    N --> D
    D -->|Done| O --> P

    style I fill:#FF6B6B,color:#fff,stroke:#333,stroke-width:2px
    style L fill:#FF6B6B,color:#fff
    style H fill:#7ED321,color:#000
```
