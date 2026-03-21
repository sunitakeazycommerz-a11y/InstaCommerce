# Payment-Webhook-Service - LLD (Low-Level Design)

```mermaid
graph TB
    subgraph External["Stripe"]
        StripeEvents["Event: charge.succeeded<br/>charge.failed<br/>charge.refunded"]
    end

    subgraph WebhookSvc["Payment-Webhook-Service"]
        Endpoint["WebhookController<br/>POST /webhooks/stripe"]
        Validator["SignatureValidator<br/>(HMAC-SHA256)"]
        Parser["EventParser<br/>(type detection)"]
        Handler["EventHandlers<br/>(charge.succeeded,<br/>charge.failed, etc.)"]
        DB["WebhookDB<br/>webhook_events table"]
        Outbox["Outbox Publisher"]
    end

    subgraph Events["Events"]
        Kafka["Kafka:<br/>webhook.events<br/>payment.notifications"]
    end

    StripeEvents -->|POST with<br/>X-Stripe-Signature| Endpoint
    Endpoint -->|validate| Validator
    Validator -->|trusted event| Parser
    Parser -->|parse<br/>JSON| Handler
    Handler -->|query payment| DB
    Handler -->|emit<br/>WebhookProcessedEvent| Outbox
    Outbox -->|CDC| Kafka

    style Endpoint fill:#FFE5B4
    style Validator fill:#FF9999
    style Kafka fill:#95E1D3
```

## Key Components

### WebhookController
- Endpoint: `POST /webhooks/stripe`
- Validates `X-Stripe-Signature` header
- Returns 200 OK for acknowledged events
- Returns 403 Forbidden for invalid signatures

### SignatureValidator
- HMAC-SHA256 verification
- Uses Stripe signing secret from environment
- Prevents man-in-the-middle attacks
- Stateless (can be called from any instance)

### EventParser & Handlers
- Detects event type (charge.succeeded, charge.failed, etc.)
- Routes to appropriate handler
- Each handler idempotent (event_id deduplication)
- Can safely process duplicate webhooks

### Webhook Schema
```sql
CREATE TABLE webhook_events (
    id UUID PRIMARY KEY,
    stripe_event_id VARCHAR(255) UNIQUE NOT NULL,
    event_type VARCHAR(100) NOT NULL,
    event_payload JSONB NOT NULL,
    received_at TIMESTAMP DEFAULT NOW(),
    processed BOOLEAN DEFAULT FALSE,
    processed_at TIMESTAMP
);
```

---

**SLO**: <100ms to return 200 OK; <5s to process and emit Kafka event
