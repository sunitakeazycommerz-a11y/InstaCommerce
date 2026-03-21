# Payment Service - End-to-End Payment Flow

## Complete Payment Lifecycle: Authorization → Settlement

```mermaid
sequenceDiagram
    participant Customer
    participant Checkout as Checkout<br/>Orchestrator
    participant Payment as Payment<br/>Service
    participant Stripe as Stripe<br/>API
    participant Kafka as Kafka
    participant Reconciliation as Reconciliation<br/>Engine
    participant Finance as Finance<br/>(Settlement)

    Customer->>Checkout: Confirm order (cart items, address, payment method)
    Checkout->>Checkout: Calculate final amount (with taxes,<br/>promos, delivery fees)

    Checkout->>Payment: POST /payments/authorize<br/>{order_id, amount_cents,<br/>currency, idempotency_key, card_token}
    activate Payment

    Payment->>Payment: Validate request<br/>Check idempotency_key

    Payment->>Stripe: POST /v1/payment_intents<br/>/v1/charges... (authorize)
    activate Stripe
    rect rgb(255, 240, 200)
        Note over Stripe: Stripe Processes <br/>Authorization<br/>1. Tokenize card<br/>2. Check fraud rules<br/>3. Reserve funds
    end
    Stripe-->>Payment: { charge_id, status: succeeded,<br/>risk_level: low }
    deactivate Stripe

    Payment->>Payment: Persist Payment entity<br/>(status: AUTHORIZED,<br/>charge_id from Stripe)

    Payment->>Payment: Emit PaymentAuthorizedEvent<br/>(to outbox table)

    Payment-->>Checkout: 200 OK { payment_id, status: AUTHORIZED }
    deactivate Payment

    rect rgb(200, 220, 255)
        Note over Payment,Kafka: CDC (Continuous Propagation)<br/>Debezium polls outbox every 100ms
    end

    Payment->>Kafka: Outbox → CDC → Kafka<br/>(PaymentAuthorizedEvent published)

    Checkout->>Checkout: Verify order can proceed<br/>with authorized payment

    rect rgb(220, 255, 220)
        Note over Checkout: Order Processing<br/>Inventory reserved<br/>Fulfillment assigned<br/>Delivery scheduled
    end

    Note over Checkout,Payment: Order progresses to delivery...

    Checkout->>Checkout: Customer delivery confirmed<br/>(DeliveryConfirmedEvent)

    Checkout->>Payment: [Implicit] OrderConfirmedEvent<br/>(order_id, final_amount)
    activate Payment

    Payment->>Payment: Lookup payment by order_id
    Payment->>Payment: Check status = AUTHORIZED

    Payment->>Stripe: POST /v1/charges/{charge_id}/capture<br/>{amount_to_capture}
    activate Stripe
    rect rgb(255, 240, 200)
        Note over Stripe: Stripe Captures Charge<br/>1. Settle with payment processor<br/>2. Queue for deposit
    end
    Stripe-->>Payment: { status: succeeded, id: charge_id }
    deactivate Stripe

    Payment->>Payment: Update Payment status → CAPTURED<br/>Emit PaymentCapturedEvent

    Payment->>Kafka: PaymentCapturedEvent → Kafka

    deactivate Payment

    rect rgb(240, 248, 255)
        Note over Kafka,Reconciliation: Settlement Pipeline (T+1)<br/>Reconciliation engine receives<br/>PaymentCapturedEvent via Kafka
    end

    Reconciliation->>Reconciliation: Consume PaymentCapturedEvent
    Reconciliation->>Kafka: Fetch daily payment ledger<br/>(payments.events topic)
    Reconciliation->>Payment: Query /payments?date=today&status=CAPTURED
    activate Payment
    Payment-->>Reconciliation: List of all captured payments
    deactivate Payment

    Reconciliation->>Reconciliation: Aggregate captured payments by bank<br/>Calculate settlement amount

    Reconciliation->>Finance: [Daily] POST /settlements<br/>{date, bank_id, amount, payment_ids[]}

    Finance->>Finance: Generate ACH/Wire transfer<br/>to bank account (T+1 business day)
    Finance->>Stripe: Reconcile: compare captured vs paid

    alt Settlement Success
        Finance->>Kafka: SettlementExecutedEvent<br/>(confirmation #, deposit date)
    else Mismatch Detected
        Finance->>Reconciliation: [Alert] PaymentMissmatchEvent
        Reconciliation->>Reconciliation: Create mismatch record<br/>(manual review queue)
    end

    == Alternative: Customer Cancellation / Refund ==

    Customer->>Checkout: Cancel order (within 2 min)
    Checkout->>Checkout: Emit OrderCancelledEvent (before capture)
    Checkout->>Payment: OrderCancelledEvent received
    activate Payment

    Payment->>Payment: Check payment status
    alt Status = AUTHORIZED
        Payment->>Stripe: POST /v1/charges/{charge_id}/void
        Stripe-->>Payment: {status: voided}
        Payment->>Payment: Update status → VOIDED
        Payment->>Kafka: PaymentVoidedEvent
    else Status = CAPTURED
        Payment->>Stripe: POST /v1/charges/{charge_id}/refund
        Stripe-->>Payment: {status: refunded, refund_id}
        Payment->>Payment: Update status → REFUNDED
        Payment->>Kafka: PaymentRefundedEvent
    end
    deactivate Payment

    Payment->>Customer: SMS/Email: "Refund processed"

    rect rgb(144, 238, 144)
        Note over Reconciliation,Finance: Refund Settlement<br/>Appears as negative line item<br/>in T+2 settlement
    end
```

## Key Checkpoints & SLOs

| Checkpoint | Duration | Condition |
|-----------|----------|-----------|
| Authorization | <300ms p99 | Stripe succeeds on 1st attempt |
| OOB propagation (Kafka publish) | <1s | Debezium polls & publishes |
| Capture (post-delivery) | <300ms p99 | Stripe succeeds |
| Settlement initiation | T+0 (daily at 2 AM UTC) | All captured payments aggregated |
| Deposit (bank) | T+1 | ACH usually clears by 9 AM |

## Resilience & Compensation

```
Authorization Failure:
  → Retry 3x with exponential backoff (1s, 2s, 4s)
  → If all fail: Emit PaymentAuthFailedEvent
  → Checkout orchestrator cancels order
  → Customer sees "Payment declined; try again or use different card"

Capture Failure (refund):
  → Emit PaymentCaptureFailed
  → Retry every 30s up to 5 times
  → If still failing after 5 retries: Escalate to Finance team
  → Manual review: Force void & issue goodwill refund if necessary

Refund Failure (cancellation):
  → Emit PaymentRefundFailed
  → Retry with exponential backoff (1s, 2s, 4s, 8s, 16s)
  → After 5 retries: Escalate to Finance
  → Manual refund issued (out-of-band)
```

---

**End-to-End SLA**: 99.95% authorization success; <300ms p99 latency
**Settlement SLA**: 95% confirmed deposit within T+1 business day
**Refund SLA**: 100% refunds initiated within 5 minutes of order cancellation
