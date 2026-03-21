# Payment Service - Sequence Diagram

```mermaid
sequenceDiagram
    participant Checkout as Checkout Orchestrator
    participant Payment as Payment Service
    participant Stripe as Stripe API
    participant PaymentDB as Payment DB
    participant Outbox as Outbox Table
    participant CDC as Debezium CDC
    participant Kafka as Kafka

    Checkout->>Payment: POST /authorize<br/>(order_id, amount, card_token,<br/>idempotency_key)
    activate Payment

    Payment->>PaymentDB: Query by idempotency_key
    PaymentDB-->>Payment: existing_payment or null
    alt Duplicate (idempotency)
        Payment-->>Checkout: 200 OK {payment_id, status: AUTHORIZED}
    else New payment
        Payment->>Payment: Validate request payload
        Payment->>Stripe: POST /v1/payment_intents<br/>(amount, currency, metadata)
        activate Stripe
        Stripe->>Stripe: Tokenize card<br/>Create charge record
        Stripe-->>Payment: {charge_id, status: succeeded}
        deactivate Stripe

        Payment->>PaymentDB: INSERT payment_records<br/>(id, order_id, charge_id,<br/>status: AUTHORIZED, ...)
        PaymentDB-->>Payment: Inserted

        Payment->>Outbox: INSERT outbox_events<br/>(aggregate_id: payment_id,<br/>event_type: PaymentAuthorizedEvent,<br/>payload: {...})
        Outbox-->>Payment: Inserted

        Payment-->>Checkout: 200 OK {payment_id, status: AUTHORIZED}
    end
    deactivate Payment

    rect rgb(200, 220, 255)
        Note over CDC,Kafka: CDC (Continuous)<br/>Polls outbox every 100ms
    end

    CDC->>Outbox: SELECT * FROM outbox_events<br/>WHERE published = false
    Outbox-->>CDC: [{aggregate_id, event_type, payload}]
    CDC->>Kafka: PRODUCE to payment.events<br/>topic: PaymentAuthorizedEvent
    Kafka-->>CDC: Acknowledged

    CDC->>Outbox: UPDATE outbox_events<br/>SET published = true<br/>WHERE id = {...}
    Outbox-->>CDC: Updated

    == Order Confirmation (async) ==

    loop When order confirms delivery
        Checkout->>Payment: Implicit trigger:<br/>OrderConfirmedEvent consumed
        Payment->>Payment: Look up payment by order_id
        Payment->>Payment: Check status == AUTHORIZED
        Payment->>Stripe: POST /v1/charges/{charge_id}/capture<br/>(amount_to_capture)
        activate Stripe
        Stripe-->>Payment: {status: succeeded}
        deactivate Stripe

        Payment->>PaymentDB: UPDATE payment_records<br/>SET status = CAPTURED, updated_at = NOW()
        PaymentDB-->>Payment: Updated

        Payment->>Outbox: INSERT outbox_events<br/>(PaymentCapturedEvent)
        Outbox-->>Payment: Inserted

        CDC->>Outbox: Poll (async)
        CDC->>Kafka: PRODUCE PaymentCapturedEvent
    end

    == Order Cancellation (refund) ==

    loop If order cancelled
        Checkout->>Payment: OrderCancelledEvent<br/>(received via Kafka consumer)
        activate Payment
        Payment->>PaymentDB: SELECT * WHERE order_id = ?<br/>AND status IN (AUTHORIZED, CAPTURED)
        PaymentDB-->>Payment: payment_records

        alt AUTHORIZED
            Payment->>Stripe: POST /v1/charges/{charge_id}/void
        else CAPTURED
            Payment->>Stripe: POST /v1/charges/{charge_id}/refund
        end

        activate Stripe
        Stripe-->>Payment: {status: succeeded}
        deactivate Stripe

        Payment->>PaymentDB: UPDATE status = VOIDED or REFUNDED
        PaymentDB-->>Payment: Updated

        Payment->>Outbox: INSERT PaymentRefundedEvent
        Outbox-->>Payment: Inserted
        deactivate Payment

        CDC->>Outbox: Poll
        CDC->>Kafka: PRODUCE PaymentRefundedEvent
    end
```

## Latency Targets

| Operation | p50 | p95 | p99 |
|-----------|-----|-----|-----|
| Authorize (Stripe) | 150ms | 300ms | 500ms |
| Total authorize response (incl. DB) | 180ms | 350ms | 600ms |
| Capture (Stripe) | 100ms | 250ms | 400ms |
| Refund (Stripe) | 120ms | 280ms | 450ms |

---

**SLO**: 99.95% authorization success; <300ms p99 latency; <0.05% error rate
