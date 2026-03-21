# Payment-Webhook-Service - ER Diagram & Database Schema

```sql
CREATE TABLE webhook_events (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    stripe_event_id VARCHAR(255) NOT NULL UNIQUE,
    request_id UUID NOT NULL DEFAULT gen_random_uuid(),
    event_type VARCHAR(100) NOT NULL,
    event_payload JSONB NOT NULL,
    received_at TIMESTAMP NOT NULL DEFAULT NOW(),
    processed BOOLEAN NOT NULL DEFAULT FALSE,
    processed_at TIMESTAMP,
    error_message TEXT,

    CONSTRAINT webhook_valid_event_type CHECK (event_type IN (
        'charge.succeeded', 'charge.failed', 'charge.refunded',
        'charge.dispute.created', 'charge.dispute.evidence.submitted',
        'payment_method.attached', 'payment_intent.payment_failed'
    ))
);

CREATE INDEX idx_webhook_events_stripe_id ON webhook_events(stripe_event_id);
CREATE INDEX idx_webhook_events_received_at ON webhook_events(received_at DESC);
CREATE INDEX idx_webhook_events_processed ON webhook_events(processed, processed_at);
```

## Data Flow

```
Stripe API
    ↓
Payment-Webhook Service (POST /webhooks/stripe)
    ↓ [Signature validation]
    ↓ [Event parsing]
    ↓ [Handler execution (idempotent)]
    ↓
webhook_events table
    ↓ [Debezium CDC]
    ↓
outbox_events table
    ↓ [CDC CDC publishing]
    ↓
Kafka: webhook.events topic
    ↓
Downstream consumers:
  - payment-service (verify charge)
  - order-service (audit trail)
  - notification-service (receipts)
```

## Webhook Event Examples

```json
{
  "charge.succeeded": {
    "id": "evt_1ABC123",
    "type": "charge.succeeded",
    "data": {
      "object": {
        "id": "ch_1ABC123",
        "amount": 29599,
        "currency": "usd",
        "status": "succeeded"
      }
    }
  },
  "charge.failed": {
    "id": "evt_2ABC123",
    "type": "charge.failed",
    "data": {
      "object": {
        "id": "ch_2ABC123",
        "amount": 29599,
        "failure_code": "card_declined"
      }
    }
  }
}
```

---

**Schema Notes**:
- stripe_event_id is UNIQUE: prevents accidental duplicates at DB level
- processed flag: tracks CDC consumption
- error_message: logs handler failures for debugging
- CHECK constraint: validates event_type against known events
