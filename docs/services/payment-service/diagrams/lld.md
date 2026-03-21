# Payment Service - LLD (Low-Level Design)

## Component Architecture

```mermaid
graph TB
    subgraph PaymentService["Payment Service"]
        PaymentCtrl["PaymentController<br/>(REST: POST /payments/authorize)"]
        AuthSvc["AuthorizationService<br/>(authorize logic)"]
        CaptureSvc["CaptureService<br/>(capture logic)"]
        VoidSvc["VoidService<br/>(void logic)"]
        PaymentRepo["PaymentRepository<br/>(JPA: Payment entity)"]
        OutboxPub["OutboxPublisher<br/>(emit events)"]
        Circuit["Circuit Breaker<br/>(Stripe failures)"]
    end

    subgraph External["External"]
        StripeAPI["Stripe API<br/>(card processing)"]
    end

    subgraph Persistence["Persistence"]
        DB["PostgreSQL<br/>payment_records table"]
        OutboxTbl["outbox_events table"]
    end

    subgraph Events["Events"]
        Kafka["Kafka: payments.events"]
    end

    PaymentCtrl -->|validate| AuthSvc
    AuthSvc -->|guarded by| Circuit
    AuthSvc -->|POST /v1/payment_intents| StripeAPI
    AuthSvc -->|persist| PaymentRepo
    PaymentRepo -->|save Payment| DB
    AuthSvc -->|emit| OutboxPub
    OutboxPub -->|insert| OutboxTbl
    OutboxTbl -->|CDC → Debezium| Kafka

    PaymentCtrl -->|authorize_then_capture| CaptureSvc
    CaptureSvc -->|guarded by| Circuit
    CaptureSvc -->|POST /v1/charges/{id}/capture| StripeAPI
    CaptureSvc -->|update| PaymentRepo

    PaymentCtrl -->|void_authorization| VoidSvc
    VoidSvc -->|guarded by| Circuit
    VoidSvc -->|POST /v1/charges/{id}/refund| StripeAPI
    VoidSvc -->|update| PaymentRepo

    style Circuit fill:#FF9999
    style StripeAPI fill:#FF6B6B
    style PaymentCtrl fill:#FFE5B4
    style Kafka fill:#95E1D3
```

## Key Classes & Dependencies

```java
// PaymentController (REST endpoint)
@PostMapping("/authorize")
public PaymentResponse authorize(@Valid @RequestBody AuthorizeRequest req) {
    // 1. Validate request (idempotency_key, amount, card_token, etc.)
    // 2. Call authSvc.authorize(req)
    // 3. Return PaymentResponse { payment_id, status, stripe_charge_id }
}

// AuthorizationService (business logic)
public Payment authorize(AuthorizeRequest req) throws PaymentException {
    try {
        // 1. Duplicate check: query DB by idempotency_key
        if (exists) return existing; // Idempotency

        // 2. Create Stripe PaymentIntent with circuit breaker
        stripeResponse = circuitBreaker.executeSupplier(
            () -> stripeClient.createPaymentIntent(req)
        );

        // 3. Persist Payment entity (AUTHORIZED status)
        payment = paymentRepo.save(new Payment(
            id: UUID,
            idempotencyKey: req.idempotency_key,
            amountCents: req.amount_cents,
            stripeChargeId: stripeResponse.charge_id,
            status: AUTHORIZED,
            createdAt: now()
        ));

        // 4. Emit outbox event (consumed by CDC)
        outboxPublisher.emit(new PaymentAuthorizedEvent(
            payment_id: payment.id,
            amount: payment.amountCents,
            order_id: req.order_id
        ));

        return payment;
    } catch (StripeException | CircuitBreakerOpenException e) {
        // Circuit breaker or Stripe API down
        // Emit PaymentAuthorizationFailedEvent
        // Return 503 Service Unavailable; client will retry
    }
}
```

## Database Schema

```sql
CREATE TABLE payment_records (
    id UUID PRIMARY KEY,
    order_id UUID NOT NULL,
    idempotency_key VARCHAR(255) NOT NULL UNIQUE,
    amount_cents BIGINT NOT NULL,
    currency VARCHAR(3) DEFAULT 'USD',
    stripe_charge_id VARCHAR(255) UNIQUE,
    status VARCHAR(50) NOT NULL -- AUTHORIZED, CAPTURED, VOIDED, REFUNDED, FAILED
        CHECK (status IN ('AUTHORIZED', 'CAPTURED', 'VOIDED', 'REFUNDED', 'FAILED')),
    card_last_four VARCHAR(4),
    created_at TIMESTAMP NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMP NOT NULL DEFAULT NOW(),
    FOREIGN KEY (order_id) REFERENCES orders(id) ON DELETE CASCADE
);

CREATE TABLE outbox_events (
    id BIGSERIAL PRIMARY KEY,
    aggregate_id UUID NOT NULL, -- payment_id
    event_type VARCHAR(100) NOT NULL, -- PaymentAuthorizedEvent, etc.
    event_payload JSONB NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT NOW(),
    published BOOLEAN DEFAULT FALSE,
    published_at TIMESTAMP,
    CONSTRAINT outbox_uniq UNIQUE (aggregate_id, created_at, event_type)
);

CREATE INDEX idx_payment_records_idempotency ON payment_records(idempotency_key);
CREATE INDEX idx_payment_records_stripe_id ON payment_records(stripe_charge_id);
CREATE INDEX idx_outbox_events_published ON outbox_events(published, created_at);
```

## Error Handling & Resilience

| Error | Cause | Handling |
|-------|-------|----------|
| **Stripe 429 (rate limit)** | Too many requests | Circuit breaker engages; retry after wait (1s exponential backoff) |
| **Stripe 5xx** | Stripe down | Circuit breaker open; return 503; client retries later |
| **Network timeout** | Slow Stripe API | Timeout 5s; retry 3x; emit PaymentAuthorizationFailedEvent |
| **Duplicate idempotency_key** | Client retried same payment | Query by key; return cached result (idempotency) |
| **DB write failure** | PostgreSQL unavailable | Log error; return 500; alert; manual reconciliation |

---

**SLO**: Authorize call completes <300ms p99 (target); <500ms p99 acceptable
**Retry**: 3 attempts max; exponential backoff (1s, 2s, 4s)
**Circuit Breaker**: 50% error threshold, 30s open window
