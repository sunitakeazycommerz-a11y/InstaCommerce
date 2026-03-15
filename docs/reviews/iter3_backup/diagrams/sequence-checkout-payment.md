# Sequence Diagrams: Checkout, Payment, Webhook, Reconciliation & Notifications

> **Scope** – Covers the full checkout saga (happy path + rollback paths), payment
> auth/capture lifecycle, PSP webhook ingestion, reconciliation-engine cycle,
> inventory reservation/confirm, and customer notification dispatch.
>
> **Implementation grounding** – All participant names, method calls, state
> transitions, idempotency key formats, Kafka topic names, HTTP endpoints, and
> Temporal workflow/activity config are taken directly from the source code in
> `services/checkout-orchestrator-service`, `services/payment-service`,
> `services/payment-webhook-service`, `services/reconciliation-engine`,
> `services/inventory-service`, `services/order-service`, and
> `services/notification-service`.

---

## Table of Contents

1. [Happy Path – End-to-End Checkout](#1-happy-path--end-to-end-checkout)
2. [Idempotency Key Lineage](#2-idempotency-key-lineage)
3. [Failure Path A – Payment Declined (no capture)](#3-failure-path-a--payment-declined-no-capture)
4. [Failure Path B – Order Creation Fails After Payment Auth](#4-failure-path-b--order-creation-fails-after-payment-auth)
5. [Failure Path C – Capture Uncertain / Post-Capture Compensation](#5-failure-path-c--capture-uncertain--post-capture-compensation)
6. [PSP Webhook Ingestion](#6-psp-webhook-ingestion)
7. [Reconciliation Cycle & Pending-State Recovery](#7-reconciliation-cycle--pending-state-recovery)
8. [Customer Notification Dispatch](#8-customer-notification-dispatch)

---

## Single-Authority Rules

| Concern | Single Authority |
|---------|-----------------|
| Idempotency of the checkout request | `CheckoutController` – DB row in `checkout_idempotency_keys` (TTL 30 min) |
| Workflow state & saga compensation | Temporal `CheckoutWorkflow` (durable, replayed on crash) |
| Payment status transitions | `PaymentService` via `PaymentTransactionHelper` – pending states written inside a `REQUIRES_NEW` TX before any PSP call |
| Inventory count | `ReservationService` – `PESSIMISTIC_WRITE` lock on `stock_items` sorted by `product_id` (consistent lock order prevents deadlock) |
| Idempotency at the PSP boundary | Per-operation key: `{workflowId}-payment[-{paymentId}]-{op}[-{activityId}]` |
| Webhook dedup (single node) | Go `IdempotencyStore` (in-memory TTL map; production upgrade: Redis `SET NX EX`) |
| Ledger truth for reconciliation | `reconciliation-engine` `LedgerStore` (file-backed; production upgrade: DB) vs PSP export |

---

## 1. Happy Path – End-to-End Checkout

Covers: cart validation → pricing → inventory reservation → payment auth →
order creation → inventory confirm → payment capture → cart clear → outbox →
Kafka → order placed event → customer notification.

```mermaid
sequenceDiagram
    autonumber

    participant App as Mobile App
    participant BFF as MobileBFF
    participant CO as CheckoutOrchestrator<br/>(CheckoutController)
    participant TW as Temporal<br/>(CheckoutWorkflowImpl)
    participant Cart as CartService
    participant Pricing as PricingService
    participant Inv as InventoryService<br/>(ReservationService)
    participant Pay as PaymentService<br/>(PaymentTransactionHelper)
    participant PSP as Payment Gateway<br/>(Stripe/Razorpay/PhonePe)
    participant Ord as OrderService
    participant Relay as Debezium/OutboxRelay
    participant K1 as Kafka<br/>payments.events
    participant K2 as Kafka<br/>orders.events
    participant K3 as Kafka<br/>inventory.events
    participant Notif as NotificationService

    App->>BFF: POST /checkout (JWT, cart, paymentMethodId,<br/>deliveryAddressId, couponCode)
    BFF->>CO: POST /checkout + Idempotency-Key: <client-uuid>

    Note over CO: DB lookup: checkout_idempotency_keys<br/>where key = <client-uuid>
    CO->>CO: Key absent → workflowId = "checkout-{userId}-{key}"

    CO->>TW: workflowClient.start(CheckoutWorkflow.checkout,<br/>WorkflowOptions{id=workflowId, execTimeout=5min})

    Note over TW: Saga.Options{parallelCompensation=false,<br/>continueWithError=true}

    %% Step 1 – Cart
    TW->>Cart: CartActivity.validateCart(userId)<br/>[timeout=10s, maxAttempts=3, backoff=2x initial=1s]
    Cart-->>TW: CartValidationResult{valid=true, items, storeId}

    %% Step 2 – Pricing
    TW->>Pricing: PricingActivity.calculatePrice(PricingRequest)<br/>[timeout=10s, maxAttempts=3]
    Pricing-->>TW: PricingResult{totalCents, subtotalCents,<br/>discountCents, deliveryFeeCents, currency}

    %% Step 3 – Inventory reserve
    TW->>Inv: POST /api/inventory/reservations<br/>InventoryActivity.reserveStock(items)<br/>[timeout=15s, maxAttempts=3, DoNotRetry=InsufficientStockException]
    Note over Inv: PESSIMISTIC_WRITE lock on stock_items<br/>sorted by product_id (deadlock-safe)<br/>idempotencyKey = request.idempotencyKey
    Inv->>Inv: stock_items.reserved += qty (per product)
    Inv->>Inv: reservations INSERT (status=PENDING, expiresAt=now+TTL)
    Inv->>Inv: outbox_events INSERT (StockReserved)
    Inv-->>TW: ReserveResponse{reservationId, expiresAt}
    TW->>TW: saga.addCompensation(releaseStock, reservationId)

    %% Step 4 – Payment auth
    Note over TW: authKey = "{workflowId}-payment"
    TW->>Pay: POST /payments/authorize<br/>PaymentActivity.authorizePayment(amountCents,<br/>paymentMethodId, authKey)<br/>[scheduleToClose=45s, startToClose=30s,<br/>maxAttempts=3, backoff=2x initial=2s,<br/>DoNotRetry=PaymentDeclinedException]
    Note over Pay: TX-1 (REQUIRES_NEW):<br/>findByIdempotencyKey(authKey) → absent<br/>INSERT payments (status=AUTHORIZE_PENDING)
    Pay->>PSP: gateway.authorize(amountCents, currency,<br/>idempotencyKey=authKey, paymentMethod)
    PSP-->>Pay: GatewayAuthResult{success=true, pspReference}
    Note over Pay: TX-2 (REQUIRES_NEW):<br/>UPDATE payments SET status=AUTHORIZED,<br/>psp_reference={ref}<br/>ledger: debit customer_receivable,<br/>credit authorization_hold<br/>outbox_events INSERT (PaymentAuthorized)
    Pay-->>TW: PaymentAuthResult{authorized=true, paymentId}
    TW->>TW: paymentAuthorized=true<br/>captureKeyPrefix = authKey + "-" + paymentId

    %% Step 5 – Order create
    TW->>Ord: POST /api/orders<br/>OrderActivity.createOrder(OrderCreateRequest)<br/>[timeout=15s, maxAttempts=3]
    Note over Ord: findByIdempotencyKey(key) → absent<br/>INSERT orders (status=PENDING)<br/>outbox_events INSERT (OrderCreated)
    Ord-->>TW: OrderCreationResult{orderId, estimatedDeliveryMinutes}
    TW->>TW: saga.addCompensation(cancelOrder, orderId)

    %% Step 6a – Confirm inventory
    TW->>Inv: POST /api/inventory/reservations/{id}/confirm<br/>InventoryActivity.confirmStock(reservationId)
    Note over Inv: PESSIMISTIC_WRITE lock<br/>stock_items: onHand -= qty, reserved -= qty<br/>reservations: status=CONFIRMED<br/>outbox_events INSERT (StockConfirmed)
    Inv-->>TW: 200 OK

    %% Step 6b – Capture payment
    Note over TW: captureKey = captureKeyPrefix + "-capture"
    TW->>Pay: POST /payments/{paymentId}/capture<br/>PaymentActivity.capturePayment(paymentId, captureKey)
    Note over Pay: TX-3 (REQUIRES_NEW):<br/>UPDATE payments SET status=CAPTURE_PENDING
    Pay->>PSP: gateway.capture(pspReference, amountCents, captureKey)
    PSP-->>Pay: GatewayCaptureResult{success=true}
    Note over Pay: TX-4 (REQUIRES_NEW):<br/>UPDATE payments SET status=CAPTURED,<br/>captured_cents=amount<br/>ledger: debit authorization_hold,<br/>credit revenue<br/>outbox_events INSERT (PaymentCaptured)
    Pay-->>TW: 200 OK
    TW->>TW: paymentCaptured=true

    %% Step 7 – Clear cart (best-effort)
    TW->>Cart: CartActivity.clearCart(userId)
    Note over TW: Failure swallowed – checkout not rolled back
    Cart-->>TW: 200 OK

    TW-->>CO: CheckoutResponse{success=true, orderId, totalCents,<br/>estimatedDeliveryMinutes}
    CO->>CO: INSERT checkout_idempotency_keys<br/>(key, serializedResponse, expiresAt=now+30min)
    CO-->>BFF: 200 OK {orderId, totalCents}
    BFF-->>App: 200 OK

    %% Async – Outbox relay
    Relay->>Relay: Debezium CDC reads outbox_events<br/>(payment-service, order-service, inventory-service)
    Relay->>K1: Publish PaymentAuthorized + PaymentCaptured<br/>(key=paymentId)
    Relay->>K2: Publish OrderCreated + OrderStatusChanged(PLACED)<br/>(key=orderId)
    Relay->>K3: Publish StockReserved + StockConfirmed<br/>(key=reservationId)

    %% Order-service state transition to PLACED
    Note over Ord: OrderEventConsumer or internal trigger<br/>updateOrderStatus(PLACED) →<br/>outbox: OrderPlaced (full payload)
    Relay->>K2: Publish OrderPlaced (key=orderId)

    %% Notification
    Notif->>K2: @KafkaListener(orders.events, groupId=notification-service)
    Note over Notif: Envelope parsed → eventType=OrderPlaced<br/>TemplateRegistry.resolve(OrderPlaced)<br/>deduplication by eventId<br/>userPreferenceService.getPreferences(userId)<br/>→ EMAIL + SMS channels
    Notif->>Notif: Render ORDER_CONFIRMATION template<br/>{orderId, totalFormatted, eta}
    Notif->>App: Email: "Your order {orderId} is confirmed – {total}"
    Notif->>App: SMS: "Order placed. Delivery in ~{eta} mins"
```

---

## 2. Idempotency Key Lineage

Illustrates how a single client `Idempotency-Key` header propagates through all
layers and how sub-keys prevent double-charges on Temporal retries.

```mermaid
sequenceDiagram
    autonumber
    participant App as Client
    participant CO as CheckoutController
    participant TW as CheckoutWorkflowImpl
    participant PayAct as PaymentActivityImpl
    participant Pay as PaymentService

    App->>CO: POST /checkout<br/>Idempotency-Key: ik-abc123

    Note over CO: workflowId = "checkout-{userId}-ik-abc123"<br/>DB-backed: checkout_idempotency_keys row<br/>(idempotency_key, response_json, expires_at)

    CO->>TW: workflow.checkout(request)<br/>workflowId stored as Temporal workflow ID

    Note over TW: authKey = workflowId + "-payment"<br/>= "checkout-user1-ik-abc123-payment"

    TW->>PayAct: authorizePayment(amount, methodId, authKey)

    Note over PayAct: resolveIdempotencyKey(authKey):<br/>activityId = Activity.getExecutionContext().getInfo().getActivityId()<br/>resolvedKey = authKey + "-" + activityId<br/>→ "checkout-user1-ik-abc123-payment-activity-7"<br/><br/>On Temporal retry of same activity:<br/>activityId is SAME → resolvedKey is SAME<br/>→ PSP deduplicates the charge

    PayAct->>Pay: POST /payments/authorize {idempotencyKey: resolvedKey}

    Note over Pay: TX (REQUIRES_NEW): findByIdempotencyKey(resolvedKey)<br/>present → return existing Payment (no PSP call)<br/>absent → INSERT AUTHORIZE_PENDING + call PSP

    Pay-->>TW: PaymentAuthResult{paymentId=pay-xyz}

    Note over TW: captureKeyPrefix = authKey + "-" + paymentId<br/>= "checkout-user1-ik-abc123-payment-pay-xyz"

    TW->>PayAct: capturePayment(paymentId,<br/>captureKeyPrefix + "-capture")

    Note over PayAct: resolvedCaptureKey =<br/>"checkout-user1-ik-abc123-payment-pay-xyz-capture-activity-9"

    Note over TW: Compensation keys (if needed):<br/>void:   captureKeyPrefix + "-void"<br/>refund: captureKeyPrefix + "-refund"<br/>Each suffixed with activityId at call time
```

---

## 3. Failure Path A – Payment Declined (No Capture)

The PSP hard-declines the authorization. `PaymentDeclinedException` is marked
`DoNotRetry` – Temporal does not retry. The saga compensates: inventory
reservation is released.

```mermaid
sequenceDiagram
    autonumber

    participant App as Mobile App
    participant CO as CheckoutOrchestrator
    participant TW as Temporal<br/>(CheckoutWorkflowImpl)
    participant Inv as InventoryService
    participant Pay as PaymentService
    participant PSP as Payment Gateway

    App->>CO: POST /checkout Idempotency-Key: ik-abc123
    CO->>TW: workflow.checkout(request)

    TW->>TW: validateCart ✓
    TW->>TW: calculatePrice ✓

    TW->>Inv: POST /api/inventory/reservations<br/>reserveStock(items)
    Inv-->>TW: {reservationId: res-001, status: PENDING}
    TW->>TW: saga.addCompensation(releaseStock, res-001)

    TW->>Pay: POST /payments/authorize<br/>idempotencyKey: "{wfId}-payment"
    Note over Pay: TX-1: INSERT payments(status=AUTHORIZE_PENDING)
    Pay->>PSP: gateway.authorize(...)
    PSP-->>Pay: GatewayAuthResult{success=false,<br/>declineReason="INSUFFICIENT_FUNDS"}
    Note over Pay: TX-2: UPDATE payments SET status=FAILED
    Pay-->>TW: throws PaymentDeclinedException<br/>(DoNotRetry – no Temporal retry)

    Note over TW: paymentAuthorized = false<br/>compensatePayment() is a no-op<br/>(paymentAuthorized=false → skip void/refund)

    TW->>TW: catch(Exception e) → saga.compensate()

    Note over TW: saga.compensate() calls registered compensations<br/>in reverse order (parallelCompensation=false)

    TW->>Inv: POST /api/inventory/reservations/res-001/cancel<br/>InventoryActivity.releaseStock(res-001)
    Note over Inv: UPDATE reservations SET status=CANCELLED<br/>UPDATE stock_items: reserved -= qty<br/>outbox_events INSERT (StockReleased)
    Inv-->>TW: 200 OK

    TW-->>CO: CheckoutResponse{success=false,<br/>message="Payment declined: INSUFFICIENT_FUNDS"}
    CO->>CO: INSERT checkout_idempotency_keys (failure response)
    CO-->>App: 200 OK {success: false, message: "Payment declined"}
```

---

## 4. Failure Path B – Order Creation Fails After Payment Auth

Payment is fully authorized but `OrderActivity.createOrder` fails after 3
Temporal retries. Compensation must void the authorized payment (not yet
captured) and release the inventory reservation.

```mermaid
sequenceDiagram
    autonumber

    participant TW as Temporal<br/>(CheckoutWorkflowImpl)
    participant Inv as InventoryService
    participant Pay as PaymentService
    participant PSP as Payment Gateway
    participant Ord as OrderService

    TW->>TW: validateCart ✓ / calculatePrice ✓

    TW->>Inv: reserveStock(items)
    Inv-->>TW: {reservationId: res-002}
    TW->>TW: saga.addCompensation(releaseStock, res-002)

    TW->>Pay: authorizePayment(amount, methodId, authKey)
    Note over Pay: status: AUTHORIZE_PENDING → AUTHORIZED<br/>PSP reference stored, PaymentAuthorized in outbox
    Pay-->>TW: {authorized=true, paymentId=pay-002}
    TW->>TW: paymentAuthorized=true<br/>captureKeyPrefix = authKey + "-pay-002"

    TW->>Ord: POST /api/orders<br/>createOrder(OrderCreateRequest)<br/>[maxAttempts=3, retries exhausted]
    Ord-->>TW: throws ApplicationException (e.g., DB unavailable)
    Note over TW: After 3 failed attempts → exception propagates

    %% Compensation starts
    Note over TW: catch(Exception) → compensatePayment() is called first

    Note over TW: paymentAuthorized=true, paymentCaptured=false,<br/>paymentCaptureAttempted=false<br/>→ branch: voidPayment()
    TW->>Pay: POST /payments/pay-002/void<br/>voidPayment(paymentId, captureKeyPrefix+"-void")
    Note over Pay: TX: UPDATE payments SET status=VOID_PENDING<br/>gateway.voidAuth(pspReference, voidKey)
    Pay->>PSP: voidAuth(pspReference, voidKey)
    PSP-->>Pay: GatewayVoidResult{success=true}
    Note over Pay: TX: UPDATE payments SET status=VOIDED<br/>outbox_events INSERT (PaymentVoided)
    Pay-->>TW: 200 OK

    %% saga.compensate() for inventory
    TW->>TW: saga.compensate()
    TW->>Inv: POST /api/inventory/reservations/res-002/cancel<br/>releaseStock(res-002)
    Note over Inv: UPDATE reservations SET status=CANCELLED<br/>stock_items.reserved -= qty<br/>outbox_events INSERT (StockReleased)
    Inv-->>TW: 200 OK

    TW-->>TW: CheckoutResponse{success=false,<br/>message="Order creation failed"}
```

---

## 5. Failure Path C – Capture Uncertain / Post-Capture Compensation

Captures the three sub-cases Temporal handles in `compensatePayment()`:

- **C1** Capture succeeded → post-capture failure (e.g., confirm inventory
  throws) → full refund issued.
- **C2** Capture was *attempted* but outcome is unknown (network timeout) →
  refund attempted first; if refund fails, fall back to void.
- **C3** Capture not yet attempted → void.

```mermaid
sequenceDiagram
    autonumber

    participant TW as CheckoutWorkflowImpl
    participant Inv as InventoryService
    participant Pay as PaymentService
    participant PSP as Payment Gateway

    %% === Sub-case C1: capture succeeded, later step fails ===
    rect rgb(235, 245, 255)
        Note over TW,PSP: SUB-CASE C1 – Capture succeeded, confirmStock throws

        TW->>Inv: confirmStock(res-003)
        Inv-->>TW: throws ReservationExpiredException
        TW->>TW: paymentCaptured=true (set before confirmStock was called)<br/>catch(Exception) → compensatePayment()
        Note over TW: paymentCaptured=true → REFUND branch<br/>refundKey = captureKeyPrefix + "-refund"
        TW->>Pay: POST /payments/pay-003/refund<br/>refundPayment(paymentId, amountCents, refundKey)
        Note over Pay: TX: INSERT refunds (status=PENDING)<br/>gateway.refund(pspReference, amount, refundKey)
        Pay->>PSP: refund(pspReference, amountCents, refundKey)
        PSP-->>Pay: GatewayRefundResult{success=true, refundId}
        Note over Pay: TX: UPDATE refunds SET status=COMPLETED<br/>ledger: debit revenue, credit customer_receivable<br/>outbox_events INSERT (PaymentRefunded)
        Pay-->>TW: 200 OK
        TW->>TW: saga.compensate() → releaseStock(res-003)
        Note over Inv: CONFIRMED reservation cannot be cancelled<br/>(ReservationStateException) – manual ops required
    end

    %% === Sub-case C2: capture attempted, outcome unknown ===
    rect rgb(255, 248, 220)
        Note over TW,PSP: SUB-CASE C2 – Capture attempted, network timeout

        TW->>Pay: capturePayment(paymentId, captureKey)
        Pay->>PSP: gateway.capture(...)
        PSP-->>Pay: [network timeout / no response]
        Note over Pay: TX: revertToAuthorized(paymentId)<br/>UPDATE payments SET status=AUTHORIZED
        Pay-->>TW: throws PaymentGatewayException
        TW->>TW: paymentCaptureAttempted=true,<br/>paymentCaptured=false
        Note over TW: compensatePayment():<br/>paymentCaptureAttempted=true → TRY refund first
        TW->>Pay: refundPayment(paymentId, amount, refundKey)
        Pay->>PSP: refund(pspReference, amount, refundKey)
        PSP-->>Pay: GatewayRefundResult{success=false,<br/>reason="payment_not_captured"}
        Pay-->>TW: throws PaymentGatewayException
        Note over TW: refund failed → FALL BACK to void
        TW->>Pay: voidPayment(paymentId, voidKey)
        Pay->>PSP: voidAuth(pspReference, voidKey)
        PSP-->>Pay: GatewayVoidResult{success=true}
        Note over Pay: UPDATE payments SET status=VOIDED<br/>outbox_events INSERT (PaymentVoided)
        Pay-->>TW: 200 OK
    end

    %% === Sub-case C3: not yet captured ===
    rect rgb(240, 255, 240)
        Note over TW,PSP: SUB-CASE C3 – Failed before capture attempt (e.g., order DB down)

        Note over TW: paymentAuthorized=true,<br/>paymentCaptured=false, paymentCaptureAttempted=false<br/>→ compensatePayment() → VOID directly
        TW->>Pay: voidPayment(paymentId, voidKey)
        Note over Pay: status: AUTHORIZED → VOID_PENDING → VOIDED
        Pay->>PSP: voidAuth(pspReference, voidKey)
        PSP-->>Pay: GatewayVoidResult{success=true}
        Pay-->>TW: 200 OK
    end
```

---

## 6. PSP Webhook Ingestion

The `payment-webhook-service` (Go) is the single ingestion point for all PSP
callbacks. It is **stateless and disposable** – durability is delegated to
Kafka. The `PaymentService` also has a `ProcessedWebhookEvent` table
(`processed_webhook_events`) for downstream dedup after Kafka consumption.

```mermaid
sequenceDiagram
    autonumber

    participant PSP as Payment Gateway<br/>(Stripe / Razorpay / PhonePe)
    participant WH as payment-webhook-service<br/>(Go: WebhookHandler)
    participant Dedup as IdempotencyStore<br/>(in-memory, TTL-based)
    participant K as Kafka<br/>payment.webhooks
    participant PaySvc as PaymentService<br/>(WebhookEventHandler)
    participant PayDB as payments DB<br/>(processed_webhook_events)
    participant OutboxK as Kafka<br/>payments.events

    PSP->>WH: POST /webhook/stripe<br/>Header: Stripe-Signature: t=<ts>,v1=<sig><br/>Body: {id: evt_xxx, type: payment_intent.succeeded, ...}

    Note over WH: r.Body = MaxBytesReader(w, r.Body, 1MiB)
    WH->>WH: io.ReadAll(body) → payload []byte

    WH->>WH: verifiers[Stripe].Verify(payload, sig)<br/>HMAC-SHA256 of "t.payload", compare v1<br/>Stripe: validate timestamp within 5min tolerance
    alt Signature invalid
        WH-->>PSP: 401 {"error":"invalid signature"}
    end

    WH->>Dedup: IsDuplicate(event.ID="evt_xxx")
    alt Duplicate (within TTL)
        Dedup-->>WH: true
        WH-->>PSP: 200 {"status":"duplicate"}
    end
    Dedup-->>WH: false
    WH->>Dedup: Mark("evt_xxx", TTL)

    WH->>WH: parseEvent(Stripe, payload) →<br/>WebhookEvent{ID, PSP, EventType, PaymentID,<br/>OrderID, AmountCents, Currency, Status, ReceivedAt}

    WH->>K: Publish(ctx, "payment.webhooks",<br/>key=event.ID, value=json(WebhookEvent))
    Note over K: OTEL trace context injected as Kafka headers
    K-->>WH: ack
    WH-->>PSP: 202 {"status":"accepted","event_id":"evt_xxx"}

    %% Downstream consumption (async)
    PaySvc->>K: consume "payment.webhooks"
    K-->>PaySvc: WebhookEvent{eventType=payment.captured,<br/>paymentId=pay-xxx, orderId=ord-xxx,<br/>amountCents=4999, status=succeeded}

    PaySvc->>PayDB: SELECT * FROM processed_webhook_events<br/>WHERE event_id = 'evt_xxx'
    alt Already processed
        PayDB-->>PaySvc: row found → skip
    end
    PayDB-->>PaySvc: absent

    PaySvc->>PaySvc: WebhookEventHandler.handle(event)<br/>Reconcile internal payment status:<br/>if payment.status ≠ CAPTURED and PSP says captured<br/>→ completeCaptured(paymentId, amountCents)
    PaySvc->>PayDB: INSERT processed_webhook_events<br/>(event_id, psp, event_type, payment_id, processed_at)
    PaySvc->>OutboxK: outbox_events INSERT (PaymentCaptured)<br/>→ Debezium → payments.events

    Note over WH,Dedup: Production note: IdempotencyStore is<br/>in-memory (single node only).<br/>Multi-node upgrade: Redis SET NX EX {ttl}.<br/>payment-service processed_webhook_events<br/>table provides durable cross-node dedup.
```

---

## 7. Reconciliation Cycle & Pending-State Recovery

The `reconciliation-engine` (Go) runs on a cron schedule (default every 5
minutes, configurable via `SCHEDULE` env). It detects three mismatch types and
surfaces CAPTURE_PENDING / AUTHORIZE_PENDING stuck states as
`missing_ledger_entry` mismatches (PSP shows `succeeded` but ledger is absent
or stale).

```mermaid
sequenceDiagram
    autonumber

    participant Cron as cron.Scheduler<br/>(robfig/cron)
    participant Rec as Reconciler.Run()
    participant PSPSrc as PSPSource<br/>(file/API export)
    participant Ledger as LedgerStore<br/>(internal ledger)
    participant FixReg as FixRegistry<br/>(fix-state.json)
    participant K as Kafka<br/>reconciliation.events
    participant Ops as Ops / Alerting

    Cron->>Rec: trigger Run(ctx) every 5 min<br/>[RECONCILIATION_SCHEDULE env]

    Note over Rec: atomic.Bool.CompareAndSwap(false, true)<br/>prevents concurrent runs

    Rec->>PSPSrc: Load(ctx) → []Transaction<br/>{id, amountCents, currency, status}
    PSPSrc-->>Rec: PSP export (N transactions)

    Rec->>Ledger: Load(ctx) → []LedgerEntry<br/>{id, amountCents, currency, type, source, updatedAt}
    Ledger-->>Rec: Internal ledger (M entries)

    Rec->>Rec: findMismatches(psp, ledger)<br/>— index ledger by ID<br/>— for each PSP txn:<br/>  • not in ledger → "missing_ledger_entry" (Fixable=true)<br/>  • currency differs → "currency_mismatch" (Fixable=false)<br/>  • amount differs → "amount_mismatch" (Fixable=true)<br/>— for each ledger entry not in PSP:<br/>  • "missing_psp_export" (Fixable=false)

    loop For each mismatch
        Rec->>K: Publish ReconciliationEvent{<br/>event_type="mismatch_found",<br/>run_id, transaction_id, mismatch_type, reason}

        alt Fixable (missing_ledger_entry or amount_mismatch)
            Rec->>FixReg: Exists(fixID = "{type}:{txnId}")
            alt Fix already applied
                FixReg-->>Rec: true → skip
            end
            FixReg-->>Rec: false

            alt missing_ledger_entry
                Rec->>Ledger: Upsert(LedgerEntry from PSP data,<br/>source="psp_export")
                Note over Rec: Recovers AUTHORIZE_PENDING / CAPTURE_PENDING<br/>stuck state: PSP shows payment succeeded<br/>but ledger has no entry
            else amount_mismatch
                Rec->>Ledger: Upsert(entry with PSP amount,<br/>source="reconciliation_adjustment")
            end

            Rec->>FixReg: Record(FixRecord{fixID, appliedAt,<br/>mismatch_type, transaction_id})
            Rec->>K: Publish ReconciliationEvent{event_type="fix_applied"}
            Rec->>Rec: counts.Fixed++
        else Not fixable (currency_mismatch or missing_psp_export)
            Rec->>K: Publish ReconciliationEvent{event_type="manual_review_required"}
            Rec->>Ops: Alert: manual review for txn {transaction_id}
            Rec->>Rec: counts.ManualReview++
        end
    end

    Rec->>Ledger: Persist(ctx) if ledger was updated
    Rec->>K: Publish ReconciliationEvent{event_type="summary",<br/>counts={mismatches, fixed, manual_review}}
    Rec->>Rec: running.Store(false)

    Note over K: Downstream consumers (fraud, finance)<br/>subscribe to reconciliation.events<br/>for audit and alerting pipelines

    %% Pending-state recovery detail
    rect rgb(255, 248, 220)
        Note over Rec,Ledger: PENDING-STATE RECOVERY DETAIL<br/><br/>Scenario: payment-service crashed after PSP ack<br/>but before TX-2 (AUTHORIZED write).<br/>payments row stays in AUTHORIZE_PENDING.<br/>PSP export shows the payment as succeeded.<br/><br/>Reconciler detects: PSP has entry, ledger absent<br/>→ missing_ledger_entry (Fixable=true)<br/>→ Upsert ledger entry from PSP<br/>→ Publish fix_applied event<br/><br/>Additional recovery: payment-service ShedLock job<br/>(OutboxCleanupJob / manual operator query) can<br/>detect rows stuck in *_PENDING > N minutes<br/>and re-query PSP status to resolve final state.<br/>Temporal activity retry guarantees the<br/>workflow re-drives capture after pod restart.
    end
```

---

## 8. Customer Notification Dispatch

`NotificationService` consumes `orders.events` and `payments.events` via
dedicated `@KafkaListener` components. Each event type is matched to a
template; deduplication prevents duplicate sends across pod restarts.

```mermaid
sequenceDiagram
    autonumber

    participant K1 as Kafka<br/>orders.events
    participant K2 as Kafka<br/>payments.events
    participant OEC as OrderEventConsumer<br/>(groupId=notification-service, concurrency=3)
    participant PEC as PaymentEventConsumer<br/>(groupId=notification-service, concurrency=3)
    participant NS as NotificationService
    participant Tmpl as TemplateRegistry
    participant Dedup as DeduplicationService<br/>(notification_logs)
    participant Pref as UserPreferenceService
    participant Dir as UserDirectoryClient
    participant Sender as RetryableNotificationSender
    participant DLQ as NotificationDlqPublisher
    participant Chan as Email / SMS / Push

    K1-->>OEC: ConsumerRecord{key=orderId, value=EventEnvelope{<br/>eventType="OrderPlaced", payload={orderId, userId,<br/>totalCents, currency, items, placedAt}}}

    OEC->>NS: handleEvent(record, envelope)

    NS->>Tmpl: templateRegistry.resolve("OrderPlaced")
    Tmpl-->>NS: TemplateDefinition{templateId="order_confirmation",<br/>channels=[EMAIL, SMS], subject="Order Confirmed"}

    NS->>NS: resolveEventId(record, envelope)<br/>= record.key() if present, else envelope.id()

    NS->>Dir: userDirectoryClient.findUser(userId) → UserContact{email, phone}
    Note over NS: Cached once per event – avoids N HTTP calls<br/>for multi-channel dispatch

    NS->>Pref: userPreferenceService.getPreferences(userId)
    Pref-->>NS: Preferences{emailOptIn=true, smsOptIn=true}

    loop For each channel in [EMAIL, SMS]
        NS->>NS: allowNotification(prefs, channel) → true

        NS->>NS: resolveRecipient(channel, payload, userContact)<br/>→ email or phone

        NS->>Dedup: createLog(NotificationRequest{eventId, eventType,<br/>userId, channel, templateId, recipient})<br/>SELECT FROM notification_logs WHERE<br/>event_id=? AND channel=?
        alt Already sent (duplicate eventId + channel)
            Dedup-->>NS: null → skip channel
        end
        Dedup-->>NS: new NotificationLog (status=PENDING)

        NS->>NS: templateService.render(channel, templateId, variables)<br/>variables: {orderId, totalFormatted="INR 49.99", eta="30 mins",<br/>userName}

        NS->>Sender: send(request, log, subject, body)
        Sender->>Chan: Deliver email or SMS
        alt Delivery success
            Chan-->>Sender: ack
            Sender->>Dedup: UPDATE notification_logs SET status=SENT
        else Delivery failure (after retries)
            Sender->>DLQ: dlqPublisher.publish(request, errorReason)
            Sender->>Dedup: UPDATE notification_logs SET status=FAILED,<br/>last_error=reason
        end
    end

    %% Payment refund notification
    K2-->>PEC: ConsumerRecord{eventType="PaymentRefunded",<br/>payload={refundId, paymentId, orderId,<br/>amountCents, currency, refundedAt}}

    PEC->>NS: handleEvent(record, envelope)
    NS->>NS: eventType="PaymentRefunded"<br/>→ resolveOrderSnapshot(payload) to get userId<br/>(orderId → orderLookupClient.findOrder())
    NS->>Tmpl: templateRegistry.resolve("PaymentRefunded")
    Tmpl-->>NS: TemplateDefinition{templateId="refund_confirmation",<br/>channels=[EMAIL], subject="Refund Processed"}
    NS->>NS: variables: {refundAmount="INR 49.99", orderId, userName}
    NS->>Sender: send(EMAIL, "Your refund of INR 49.99 has been processed")
    Sender->>Chan: Deliver email
    Chan-->>Sender: ack

    Note over OEC,Chan: Events that trigger notifications:<br/>OrderPlaced → EMAIL + SMS (order_confirmation)<br/>OrderDispatched → EMAIL + SMS (dispatch_update + eta + riderName)<br/>OrderDelivered → EMAIL + SMS (delivery_confirmation)<br/>PaymentRefunded → EMAIL (refund_confirmation)<br/><br/>PUSH channel: configured but skipped with WARN log<br/>until device token store is implemented
```

---

## Key Operational Notes

### Retry Budgets (Temporal Activities)

| Activity | `startToCloseTimeout` | `scheduleToCloseTimeout` | `maxAttempts` | Backoff | `doNotRetry` |
|---|---|---|---|---|---|
| `CartActivity.validateCart` | 10 s | — | 3 | 2×, init=1 s | — |
| `PricingActivity.calculatePrice` | 10 s | — | 3 | 2×, init=1 s | — |
| `InventoryActivity.reserveStock` | 15 s | — | 3 | 2×, init=1 s | `InsufficientStockException` |
| `PaymentActivity.authorizePayment` | 30 s | **45 s** | 3 | 2×, init=2 s | `PaymentDeclinedException` |
| `OrderActivity.createOrder` | 15 s | — | 3 | 2×, init=1 s | — |

### Payment Status State Machine

```
AUTHORIZE_PENDING ──(PSP success)──► AUTHORIZED ──(capture start)──► CAPTURE_PENDING
        │                                │                                    │
  (PSP decline/error)              (void start)                       (PSP success)
        │                                │                                    │
        ▼                                ▼                                    ▼
     FAILED                        VOID_PENDING                          CAPTURED
                                        │                                    │
                                  (PSP void ok)                      (refund issued)
                                        │                                    │
                                        ▼                                    ▼
                                     VOIDED                      PARTIALLY_REFUNDED / REFUNDED
```

`AUTHORIZE_PENDING` and `CAPTURE_PENDING` are written in a `REQUIRES_NEW`
transaction **before** the PSP call. If the service crashes after PSP ack but
before the completion transaction, the pending state is the durable signal for
recovery via the reconciliation engine or operator-driven PSP status query.

### Kafka Topic Ownership

| Topic | Producer | Key Consumers |
|---|---|---|
| `payments.events` | payment-service (Debezium outbox) | notification-service, wallet-loyalty-service, fraud-detection-service |
| `orders.events` | order-service (Debezium outbox) | notification-service, fulfillment-service, fraud-detection-service |
| `inventory.events` | inventory-service (Debezium outbox) | cdc-consumer-service, data-platform |
| `payment.webhooks` | payment-webhook-service | payment-service (WebhookEventHandler) |
| `reconciliation.events` | reconciliation-engine | finance/ops alerting, data-platform |
