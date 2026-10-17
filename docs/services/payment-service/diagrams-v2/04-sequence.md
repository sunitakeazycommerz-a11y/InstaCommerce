# Payment Service - Sequence Diagrams

## Complete Authorization Sequence

```mermaid
sequenceDiagram
    participant Client as Checkout Orchestrator<br/>(Temporal)
    participant ALB as AWS ALB<br/>(TLS)
    participant PS as Payment Service
    participant Auth as JwtAuthFilter
    participant Redis as Redis Cache
    participant DB as PostgreSQL
    participant Stripe as Stripe API
    participant Kafka as Kafka<br/>(payment.events)

    Client->>ALB: 1. POST /payments/authorize<br/>{order_id, amount_cents, currency, payment_method}
    ALB->>PS: 2. Forward (decrypted)
    PS->>Auth: 3. Validate JWT
    Auth-->>PS: 4. ✅ Valid (principal, authorities)

    PS->>PS: 5. Generate idempotency_key<br/>(order_id + 'authorize')

    PS->>Redis: 6. GET idempotency:{key}
    alt Cache HIT (duplicate)
        Redis-->>PS: 7a. Return cached payment_id
        PS->>DB: 8a. SELECT * FROM payments WHERE id = payment_id
        DB-->>PS: 9a. Return Payment entity
        PS-->>Client: 10a. Return 200 OK (existing payment)
    else Cache MISS
        Redis-->>PS: 7b. null
        PS->>PS: 8. Validate currency (USD, EUR, GBP, INR)
        PS->>PS: 9. Create Payment entity<br/>status: AUTHORIZE_PENDING

        PS->>DB: 10. BEGIN TRANSACTION
        PS->>DB: 11. INSERT INTO payments (...)
        DB-->>PS: 12. Payment saved (id: uuid)

        PS->>Stripe: 13. POST /v1/payment_intents<br/>{amount, currency, payment_method, confirm: true}
        Note over PS,Stripe: Stripe latency: ~300-400ms p99

        alt Stripe Success
            Stripe-->>PS: 14a. {id: pi_xxx, status: requires_capture}
            PS->>PS: 15a. Update status: AUTHORIZED<br/>pspReference: pi_xxx
            PS->>DB: 16a. UPDATE payments SET status='AUTHORIZED'
            PS->>DB: 17a. INSERT INTO ledger_entries<br/>(DEBIT customer_receivables, CREDIT merchant_payables)
            PS->>DB: 18a. INSERT INTO outbox_events<br/>(PaymentAuthorized)
            PS->>DB: 19a. COMMIT
            PS->>Redis: 20a. SET idempotency:{key} = payment_id (TTL: 24h)
            PS-->>Client: 21a. Return 200 OK<br/>{payment_id, status: AUTHORIZED}
        else Stripe Failure
            Stripe-->>PS: 14b. {error: {code: card_declined}}
            PS->>PS: 15b. Update status: FAILED
            PS->>DB: 16b. UPDATE payments SET status='FAILED'
            PS->>DB: 17b. INSERT INTO outbox_events (PaymentFailed)
            PS->>DB: 18b. COMMIT
            PS-->>Client: 19b. Return 402 Payment Required<br/>{error_code: card_declined}
        end
    end

    Note over PS,Kafka: Debezium CDC relays outbox_events to Kafka
```

## Capture Sequence

```mermaid
sequenceDiagram
    participant Client as Checkout Orchestrator
    participant PS as Payment Service
    participant DB as PostgreSQL
    participant Stripe as Stripe API

    Client->>PS: 1. POST /payments/{id}/capture<br/>{amount_cents (optional)}

    PS->>DB: 2. SELECT * FROM payments<br/>WHERE id = {id} FOR UPDATE
    
    alt Payment not found
        DB-->>PS: 3a. null
        PS-->>Client: 4a. Return 404 Not Found
    else Payment found
        DB-->>PS: 3b. Payment (status: AUTHORIZED)
        
        PS->>PS: 4. Validate status == AUTHORIZED
        
        alt Invalid state
            PS-->>Client: 5a. Return 409 Conflict<br/>{current_status, message}
        else Valid state
            PS->>PS: 5b. Validate capture_amount <= authorized_cents
            
            PS->>PS: 6. Set status: CAPTURE_PENDING
            PS->>DB: 7. UPDATE payments SET status='CAPTURE_PENDING'

            PS->>Stripe: 8. POST /v1/payment_intents/{pi_xxx}/capture<br/>{amount_to_capture}
            Note over PS,Stripe: Stripe latency: ~200ms p99

            alt Capture Success
                Stripe-->>PS: 9a. {status: succeeded}
                PS->>PS: 10a. Update status: CAPTURED<br/>captured_cents = amount
                PS->>DB: 11a. UPDATE payments SET status='CAPTURED',<br/>captured_cents={amount}
                
                PS->>PS: 12a. Check if partial capture
                alt Partial capture (amount < authorized)
                    PS->>DB: 13a. INSERT ledger: release remaining auth
                end
                
                PS->>DB: 14a. INSERT INTO ledger_entries<br/>(DEBIT merchant_payables, CREDIT merchant_revenue)
                PS->>DB: 15a. INSERT INTO outbox_events (PaymentCaptured)
                PS->>DB: 16a. COMMIT
                PS-->>Client: 17a. Return 200 OK<br/>{status: CAPTURED, captured_cents}
            else Capture Failure
                Stripe-->>PS: 9b. {error: capture_failed}
                PS->>PS: 10b. Revert status: AUTHORIZED
                PS->>DB: 11b. UPDATE payments SET status='AUTHORIZED'
                PS->>DB: 12b. COMMIT
                PS-->>Client: 13b. Return 500 Error<br/>{error: capture_failed}
            end
        end
    end
```

## Refund Sequence

```mermaid
sequenceDiagram
    participant Client as Support Dashboard
    participant PS as Payment Service
    participant RefundSvc as RefundService
    participant DB as PostgreSQL
    participant Stripe as Stripe API
    participant Kafka as Kafka

    Client->>PS: 1. POST /refunds<br/>{payment_id, amount_cents, reason}

    PS->>RefundSvc: 2. processRefund(request)
    
    RefundSvc->>DB: 3. SELECT * FROM payments<br/>WHERE id = {payment_id}
    
    DB-->>RefundSvc: 4. Payment (status: CAPTURED,<br/>captured_cents: 10000, refunded_cents: 0)

    RefundSvc->>RefundSvc: 5. Validate status in [CAPTURED, PARTIALLY_REFUNDED]
    RefundSvc->>RefundSvc: 6. Calculate refundable = captured - refunded<br/>= 10000 - 0 = 10000
    RefundSvc->>RefundSvc: 7. Validate requested_amount <= refundable

    RefundSvc->>RefundSvc: 8. Generate idempotency_key<br/>(payment_id + 'refund' + amount)

    RefundSvc->>DB: 9. SELECT * FROM refunds<br/>WHERE idempotency_key = {key}
    
    alt Existing refund found
        DB-->>RefundSvc: 10a. Existing Refund
        RefundSvc-->>Client: 11a. Return existing RefundResponse
    else No existing refund
        DB-->>RefundSvc: 10b. null
        
        RefundSvc->>DB: 11b. BEGIN TRANSACTION
        RefundSvc->>DB: 12b. INSERT INTO refunds<br/>(payment_id, amount_cents, status: PENDING)
        DB-->>RefundSvc: 13b. Refund created (id: uuid)

        RefundSvc->>Stripe: 14b. POST /v1/refunds<br/>{payment_intent: pi_xxx, amount: 5000}
        Note over RefundSvc,Stripe: Stripe refund latency: ~300ms p99

        alt Stripe Success
            Stripe-->>RefundSvc: 15a. {id: re_xxx, status: succeeded}
            RefundSvc->>DB: 16a. UPDATE refunds SET status='COMPLETED',<br/>psp_refund_id='re_xxx'
            RefundSvc->>DB: 17a. UPDATE payments SET<br/>refunded_cents = refunded_cents + 5000,<br/>status = PARTIALLY_REFUNDED
            RefundSvc->>DB: 18a. INSERT INTO ledger_entries<br/>(DEBIT merchant_revenue, CREDIT customer_receivables)
            RefundSvc->>DB: 19a. INSERT INTO outbox_events (PaymentRefunded)
            RefundSvc->>DB: 20a. COMMIT
            RefundSvc-->>Client: 21a. Return 200 OK<br/>{refund_id, status: COMPLETED}
        else Stripe Failure
            Stripe-->>RefundSvc: 15b. {error: refund_failed}
            RefundSvc->>DB: 16b. UPDATE refunds SET status='FAILED'
            RefundSvc->>DB: 17b. INSERT INTO outbox_events (RefundFailed)
            RefundSvc->>DB: 18b. COMMIT
            RefundSvc-->>Client: 19b. Return 422 Error
        end
    end

    Note over DB,Kafka: Debezium CDC relays outbox to Kafka
```

## Stripe Webhook Processing Sequence

```mermaid
sequenceDiagram
    participant Stripe as Stripe API
    participant ALB as AWS ALB
    participant WH as WebhookController
    participant Verifier as WebhookSignatureVerifier
    participant Processor as WebhookEventProcessor
    participant DB as PostgreSQL

    Stripe->>ALB: 1. POST /webhooks/stripe<br/>Headers: Stripe-Signature: t=...,v1=...
    ALB->>WH: 2. Forward webhook

    WH->>WH: 3. Extract raw body (bytes)
    WH->>WH: 4. Extract Stripe-Signature header

    WH->>Verifier: 5. verify(signature, body, secret)
    Verifier->>Verifier: 6. Extract timestamp (t=)
    Verifier->>Verifier: 7. Check timestamp freshness<br/>(< 5 minutes)
    Verifier->>Verifier: 8. Compute HMAC-SHA256<br/>(timestamp.body, secret)
    Verifier->>Verifier: 9. Compare with v1= signature

    alt Signature Invalid
        Verifier-->>WH: 10a. false
        WH-->>ALB: 11a. Return 401 Unauthorized
        ALB-->>Stripe: 12a. 401
    else Signature Valid
        Verifier-->>WH: 10b. true
        WH->>WH: 11b. Parse JSON body<br/>Extract event_id, event_type

        WH->>Processor: 12b. process(event)

        Processor->>DB: 13b. SELECT * FROM processed_webhook_events<br/>WHERE event_id = {stripe_event_id}
        
        alt Already processed
            DB-->>Processor: 14a. Existing event
            Processor-->>WH: 15a. Success (idempotent)
        else Not yet processed
            DB-->>Processor: 14b. null
            
            Processor->>Processor: 15b. Route by event_type

            alt payment_intent.succeeded
                Processor->>DB: 16a. UPDATE payments SET status='AUTHORIZED'<br/>WHERE psp_reference = {pi_xxx}
            else charge.captured
                Processor->>DB: 16b. UPDATE payments SET status='CAPTURED'
            else charge.refunded
                Processor->>DB: 16c. UPDATE refunds SET status='COMPLETED'
            else charge.dispute.created
                Processor->>DB: 16d. UPDATE payments SET status='DISPUTED'
            end

            Processor->>DB: 17. INSERT INTO processed_webhook_events<br/>(event_id, event_type, processed_at)
            Processor->>DB: 18. INSERT INTO outbox_events (state change event)
            Processor->>DB: 19. COMMIT

            Processor-->>WH: 20. Success
        end

        WH-->>ALB: 21. Return 200 OK
        ALB-->>Stripe: 22. 200 OK
    end

    Note over Stripe,DB: Stripe will retry webhook if non-2xx (up to 3 days)
```

## Stale Payment Recovery Sequence

```mermaid
sequenceDiagram
    participant Scheduler as Spring Scheduler
    participant Job as StalePendingPaymentRecoveryJob
    participant ShedLock as ShedLock<br/>(PostgreSQL)
    participant DB as PostgreSQL
    participant Stripe as Stripe API

    Scheduler->>Job: 1. Trigger (every 5 min)

    Job->>ShedLock: 2. Try acquire lock<br/>(stale-payment-recovery, lockAtMostFor: 15min)
    
    alt Lock not acquired
        ShedLock-->>Job: 3a. false (another instance running)
        Job-->>Scheduler: 4a. Skip execution
    else Lock acquired
        ShedLock-->>Job: 3b. true

        Job->>DB: 4b. SELECT * FROM payments<br/>WHERE status IN ('AUTHORIZE_PENDING', 'CAPTURE_PENDING')<br/>AND created_at < NOW() - INTERVAL '30 minutes'<br/>LIMIT 100

        loop For each stale payment
            DB-->>Job: 5. Payment (psp_reference: pi_xxx, status: PENDING)
            
            Job->>Stripe: 6. GET /v1/payment_intents/{pi_xxx}
            Stripe-->>Job: 7. {status: requires_capture | succeeded | canceled}

            Job->>Job: 8. Compare local vs Stripe status

            alt Status mismatch
                Job->>DB: 9a. BEGIN TRANSACTION
                Job->>DB: 10a. UPDATE payments SET status = {stripe_status}
                Job->>DB: 11a. INSERT corrective ledger entries (if needed)
                Job->>DB: 12a. INSERT INTO audit_logs (RECONCILIATION action)
                Job->>DB: 13a. INSERT INTO outbox_events (PaymentReconciled)
                Job->>DB: 14a. COMMIT
                Job->>Job: 15a. Emit metric: payment_reconciliation_corrections_total
            else Status matches
                Job->>Job: 9b. Log: Payment already in sync
            end
        end

        Job->>ShedLock: 16. Release lock
        Job-->>Scheduler: 17. Job completed
    end
```

## Checkout Integration Sequence (E2E)

```mermaid
sequenceDiagram
    participant User as Mobile App
    participant BFF as Mobile BFF
    participant Checkout as Checkout Orchestrator<br/>(Temporal)
    participant Cart as Cart Service
    participant Inventory as Inventory Service
    participant Payment as Payment Service
    participant Stripe as Stripe API
    participant Order as Order Service
    participant Kafka as Kafka

    User->>BFF: 1. POST /checkout<br/>{cart_id, payment_method_id}
    BFF->>Checkout: 2. StartCheckoutWorkflow(cart_id)

    Checkout->>Cart: 3. GetCart(cart_id)
    Cart-->>Checkout: 4. {items, total_amount}

    Checkout->>Inventory: 5. ReserveInventory(items)
    Inventory-->>Checkout: 6. ✅ Reserved

    Checkout->>Payment: 7. POST /payments/authorize<br/>{order_id, amount, payment_method}
    Payment->>Stripe: 8. Create PaymentIntent
    Stripe-->>Payment: 9. {status: requires_capture}
    Payment-->>Checkout: 10. {payment_id, status: AUTHORIZED}

    Checkout->>Order: 11. CreateOrder(items, payment_id)
    Order-->>Checkout: 12. {order_id}

    Note over Checkout: Order confirmed, ready for fulfillment

    Checkout->>Payment: 13. POST /payments/{id}/capture
    Payment->>Stripe: 14. Capture PaymentIntent
    Stripe-->>Payment: 15. {status: succeeded}
    Payment-->>Checkout: 16. {status: CAPTURED}
    Payment->>Kafka: 17. PaymentCaptured event

    Checkout-->>BFF: 18. CheckoutResult(order_id)
    BFF-->>User: 19. 200 OK {order_id, confirmation}

    Note over Kafka,Order: Async: Order Service consumes PaymentCaptured
```
