# Payment-Webhook-Service - End-to-End Webhook Processing

```
Timeline: Customer places order → Stripe charges card → Webhook updates InstaCommerce

T+0:      Checkout orchestrator calls payment-service
          → payment-service authorizes charge with Stripe
          → PaymentAuthorizedEvent emitted
          → Outbox → CDC → Kafka

T+1 sec:  Payment reaches status=AUTHORIZED in InstaCommerce
          Order progresses to fulfillment

T+10 sec: User receives delivery
          → DeliveryConfirmedEvent emitted by fulfillment

T+11 sec: payment-service receives implicit capture command
          → Calls Stripe API: capture charge
          → PaymentCapturedEvent emitted

T+12 sec: Stripe processes capture
          → Charge status changed to succeeded
          → Stripe webhook event queued: 'charge.succeeded'

T+13 sec: Payment-webhook-service receives Stripe webhook
          → POST /webhooks/stripe
          → Signature validated (HMAC-SHA256)
          → Event parsed: type = 'charge.succeeded'
          → WebhookProcessedEvent emitted to outbox
          → 200 OK returned (fast response)

T+14 sec: CDC picks up webhook_events entry
          → Publishes WebhookProcessedEvent to Kafka
          → payment-service consumer verifies charge
          → order-service logs for audit
          → notification-service optionally sends receipt

T+15 sec: All services in sync
          → Payment settled (charge captured)
          → Order marked complete
          → Customer notified
```

## Error Scenarios

### Scenario 1: Charge Fails (Card Declined)

```
Stripe decline
  ↓
Stripe webhook: 'charge.failed'
  ↓
Payment-webhook receives event
  → Validates signature
  → Calls charge.failed handler
  → Emits WebhookProcessedEvent
  ↓
Kafka: WebhookProcessedEvent
  ↓
payment-service: Updates Payment status=FAILED
order-service: Cancels order, issues refund request
notification-service: Sends "Payment failed" SMS
```

### Scenario 2: Duplicate Webhook (Stripe Retries)

```
Payment-webhook receives same charge.succeeded event (retry)
  ↓
Query webhook_events by stripe_event_id
  ↓
Found (already processed)
  ↓
Return cached 200 OK (idempotent)
  ↓
No duplicate Kafka event emitted
```

### Scenario 3: Webhook Delay (Network Issue)

```
Payment-service captures charge (T+11)
  ↓
Stripe processes capture (T+12)
  ↓
Stripe webhook delayed due to network (T+20 instead of T+13)
  ↓
Payment-webhook eventually receives event (late)
  ↓
Handle idempotently (already in Payment DB from capture)
  ↓
Verify consistency, emit confirmation
```

---

**SLO**: 99.95% webhook events processed within 1 second
**Idempotency Window**: 24 hours (webhook_events.stripe_event_id unique constraint)
**Retry Policy**: Stripe retries on non-2xx responses; we always return 200 OK for acknowledged events
