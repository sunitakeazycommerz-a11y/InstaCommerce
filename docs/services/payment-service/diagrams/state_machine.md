# Payment Service - State Machine

```mermaid
stateDiagram-v2
    [*] --> PENDING: authorize() called

    PENDING --> AUTHORIZED: Stripe charge created\n(PaymentAuthorizedEvent emitted)
    PENDING --> FAILED: Stripe API error or timeout\n(max 3 retries exceeded)

    AUTHORIZED --> CAPTURED: Capture called\n(OrderConfirmedEvent received)
    AUTHORIZED --> VOIDED: Void called\n(OrderCancelledEvent, <br/>payment not yet captured)

    CAPTURED --> REFUNDED: Refund called\n(OrderCancelledEvent, payment captured)
    CAPTURED --> REFUND_FAILED: Refund attempt failed\n(will retry)

    REFUND_FAILED --> REFUNDED: Refund retried & succeeded
    REFUND_FAILED --> REFUND_FAILED: Exponential backoff retry\n(1s, 2s, 4s, 8s)

    FAILED --> [*]: Terminal (no funds taken)
    VOIDED --> [*]: Terminal (charge voided before capture)

    REFUNDED --> [*]: Terminal (funds returned to customer)

    CAPTURED --> CAPTURED: Duplicate capture request\n(idempotency: return cached)

    note right of PENDING
        Max 5 min wait
        If timeout: FAILED
        Card auth pending at Stripe
    end note

    note right of AUTHORIZED
        Funds reserved at Stripe
        Not yet withdrawn from account
        Must capture within
        Stripe window (usually 7 days)
    end note

    note right of CAPTURED
        Funds withdrawn from
        customer account
        Scheduled for settlement
        (T+1 or T+2 depending on bank)
    end note

    note right of REFUND_FAILED
        Retry loop with backoff
        Max 5 retries over 1 hour
        If all fail: manual review
    end note
```

## State Transition Matrix

| From | To | Trigger | Event Emitted | Action |
|------|-----|---------|--------------|--------|
| PENDING | AUTHORIZED | Stripe succeed | PaymentAuthorizedEvent | Save charge_id, emit outbox event |
| PENDING | FAILED | Stripe fail x3 | PaymentAuthorizationFailedEvent | No funds taken; log failure |
| AUTHORIZED | CAPTURED | Capture API call | PaymentCapturedEvent | Call Stripe capture; update status |
| AUTHORIZED | VOIDED | Void API call | PaymentVoidedEvent | Call Stripe void; no funds taken |
| CAPTURED | REFUNDED | Refund API call | PaymentRefundedEvent | Call Stripe refund; emit event |
| CAPTURED | REFUND_FAILED | Refund fails | PaymentRefundFailedEvent | Schedule retry with backoff |
| REFUND_FAILED | REFUNDED | Retry succeeds | PaymentRefundedEvent | Success; emit event |

## Idempotency & Replay

- **PENDING state with duplicate authorize**: Query by `idempotency_key`; if exists, return cached payment_id (don't retry Stripe)
- **AUTHORIZED state with duplicate capture**: Query by `payment_id`; if already CAPTURED, return success response (don't call Stripe again)
- **Outbox replay**: If CDC fails to publish, Debezium connector retries; Kafka consumer handles duplicates via `event_id` deduplication

---

**Terminal States**: FAILED, VOIDED, REFUNDED (no further transitions)
**Idempotency Window**: 24 hours (server-side cache)
