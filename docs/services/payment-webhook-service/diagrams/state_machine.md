# Payment-Webhook-Service - State Machine, ER, End-to-End

## State Machine

```mermaid
stateDiagram-v2
    [*] --> RECEIVED: Webhook POST received

    RECEIVED --> VERIFIED: HMAC-SHA256 valid
    RECEIVED --> REJECTED: Signature invalid\nReturn 403

    VERIFIED --> PROCESSED: Event parsed and handled
    VERIFIED --> UNKNOWN: Event type unknown\nLog & return 200 OK

    PROCESSED --> ACKNOWLEDGED: Handler success<br/>200 OK sent to Stripe
    PROCESSED --> ERROR: Handler failure\nLog error, return 200 OK anyway

    ACKNOWLEDGED --> [*]: Webhook fully processed\nCDC publishes to Kafka

    ERROR --> [*]: Webhook acknowledged\n(Stripe doesn't retry at app level)

    UNKNOWN --> [*]: Webhook acknowledged\n(don't break on new event types)

    REJECTED --> [*]: Return 403\nStripe retries later

    note right of RECEIVED
        HTTP POST received
        Minimal processing:
        validate signature
    end note

    note right of VERIFIED
        Signature match via HMAC
        Continue to handler
    end note

    note right of PROCESSED
        Handler runs
        Can fail safely (idempotent)
        Event logged to outbox
    end note

    note right of ACKNOWLEDGED
        Return 200 OK immediately
        Handler output async queued
        CDC picks up for Kafka
    end note
```

## ER Diagram

```mermaid
erDiagram
    WEBHOOK_EVENTS ||--o{ OUTBOX_EVENTS: emits
    WEBHOOK_EVENTS {
        uuid id PK
        string stripe_event_id UK
        string event_type
        jsonb event_payload
        timestamp received_at
        boolean processed
        timestamp processed_at
    }
    OUTBOX_EVENTS {
        bigserial id PK
        uuid webhook_event_id FK
        string event_type
        jsonb payload
        timestamp created_at
        boolean published
        timestamp published_at
    }
```

## End-to-End: Webhook → Payment Update

```mermaid
sequenceDiagram
    participant Customer
    participant Stripe as Stripe Dashboard
    participant WebhookAPI as Payment-Webhook<br/>Service
    participant Kafka
    participant PaymentSvc as Payment Service
    participant OrderSvc as Order Service

    rect rgb(255, 240, 200)
        Note over Customer,Stripe: Customer's bank<br/>declines charge
    end

    Stripe->>Stripe: Attempt charge<br/>Status: failed

    Stripe->>WebhookAPI: POST /webhooks/stripe<br/>{type: charge.failed,<br/>charge_id: ch_xxx,<br/>failure_code: card_declined}

    activate WebhookAPI
    WebhookAPI->>WebhookAPI: Validate signature (HMAC-SHA256)
    WebhookAPI->>WebhookAPI: Parse charge.failed event
    WebhookAPI->>WebhookAPI: Insert to webhook_events<br/>(processed=false)
    WebhookAPI-->>Stripe: 200 OK (immediate response)
    deactivate WebhookAPI

    rect rgb(200, 220, 255)
        Note over WebhookAPI,Kafka: CDC async publish
    end

    WebhookAPI->>Kafka: [Async] Publish WebhookProcessedEvent<br/>(charge.failed)

    Kafka->>PaymentSvc: Topic: webhook.events
    PaymentSvc->>PaymentSvc: Receive charge.failed webhook
    PaymentSvc->>PaymentSvc: Look up Payment by stripe_charge_id
    PaymentSvc->>PaymentSvc: Update status → FAILED<br/>(if not already)
    PaymentSvc->>PaymentSvc: Emit PaymentFailedEvent

    Kafka->>OrderSvc: Topic: webhook.events
    OrderSvc->>OrderSvc: Log webhook receipt\n(audit trail)

    Kafka->>OrderSvc: [Also] PaymentFailedEvent<br/>(from payment-service)
    OrderSvc->>OrderSvc: Cancel order<br/>Emit OrderCancelledEvent<br/>Notify customer: "Payment declined"

    OrderSvc-->>Customer: SMS/Email: "Payment failed<br/>Try again or use different card"

    rect rgb(240, 248, 255)
        Note over WebhookAPI,PaymentSvc: Webhook ensures:<br/>1. Stripe events captured<br/>2. Asynchronous propagation<br/>3. Multiple consumers notified<br/>4. Payment service stays in sync
    end
```

---

**Key Principles**:
1. **Fast response**: Return 200 OK within 100ms
2. **Idempotency**: stripe_event_id deduplication ensures safety
3. **Async processing**: CDC handles downstream Kafka publishing
4. **Resilience**: Return 200 OK even if handler fails; Stripe won't retry on 2xx
