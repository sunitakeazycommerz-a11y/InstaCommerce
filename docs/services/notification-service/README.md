# Notification Service

**Multi-channel notification delivery engine with outbox relay support (Wave 33 Track B), dead-letter queue handling for failed sends, and comprehensive audit logging.**

| Property | Value |
|----------|-------|
| **Module** | `:services:notification-service` |
| **Port** | `8089` |
| **Runtime** | Spring Boot 4.0 · Java 21 |
| **Database** | PostgreSQL 15+ (notification_log, audit_log, outbox_events) |
| **Messaging** | Kafka consumer (orders.events, payments.events, fulfillment.events, identity.events) |
| **Channels** | Email, SMS, Push notifications |
| **Auth** | JWT RS256 + service-to-service |
| **Owner** | Communications Team |
| **SLO** | P50: 100ms (send), 99.5% availability, <5min email delivery |

---

## Service Role & Ownership

**Owns:**
- `notification_log` table — sent notifications with status tracking
- `audit_log` table — administrative actions
- `notification_templates` table — email/SMS/push templates
- `outbox_events` table — transactional outbox for notification events (Wave 33)

**Does NOT own:**
- Email/SMS gateway (→ external providers: SendGrid, Twilio)
- Push notification provider (→ external: Firebase Cloud Messaging)
- User preferences (→ `identity-service`)

**Consumes:**
- `orders.events` — OrderCreated, OrderCancelled
- `payments.events` — PaymentCompleted, PaymentFailed
- `fulfillment.events` — OrderShipped, OrderDelivered
- `identity.events` — UserCreated (welcome email)

**Publishes:**
- `notification.events` — NotificationSent, NotificationFailed (via outbox, Wave 33)

---

## Core Notification Types

### Order Notifications

**OrderCreated**
- Trigger: `OrderCreated` event consumed
- Template: "Order Confirmation"
- Channels: Email, Push
- Payload: Order ID, items, total, estimated delivery

**OrderCancelled**
- Trigger: `OrderCancelled` event
- Template: "Order Cancellation"
- Channels: Email, Push
- Payload: Order ID, cancellation reason

### Payment Notifications

**PaymentCompleted**
- Trigger: `PaymentCompleted` event
- Template: "Payment Confirmation"
- Channels: Email
- Payload: Transaction ID, amount, confirmation number

**PaymentFailed**
- Trigger: `PaymentFailed` event
- Template: "Payment Failed - Retry"
- Channels: Email, Push, SMS
- Payload: Error reason, retry link

### Fulfillment Notifications

**OrderShipped**
- Trigger: `OrderShipped` event
- Template: "Order Shipped"
- Channels: Email, Push, SMS
- Payload: Tracking URL, carrier, estimated delivery date

**OrderDelivered**
- Trigger: `OrderDelivered` event
- Template: "Order Delivered"
- Channels: Email, Push
- Payload: Delivery timestamp, rating prompt

---

## Database Schema

### Notification Log

```sql
notification_log:
  id              UUID PRIMARY KEY
  recipient_id    UUID NOT NULL (user_id)
  recipient_email VARCHAR(255)
  channel         ENUM('EMAIL', 'SMS', 'PUSH')
  template_id     UUID NOT NULL
  subject         VARCHAR(255)
  body            TEXT
  payload         JSONB
  status          ENUM('PENDING', 'SENT', 'FAILED', 'BOUNCED')
  provider_id     VARCHAR(255) (external provider's ID)
  provider_response JSONB (error details if FAILED)
  retry_count     INT NOT NULL DEFAULT 0
  max_retries     INT NOT NULL DEFAULT 3
  sent_at         TIMESTAMP
  created_at      TIMESTAMP NOT NULL DEFAULT now()

Indexes:
  - recipient_id, created_at (user notification history)
  - status, created_at (pending notifications for retry)
  - provider_id (track via external provider)
```

### Notification Templates

```sql
notification_templates:
  id              UUID PRIMARY KEY
  name            VARCHAR(255) NOT NULL UNIQUE
  subject_template TEXT
  body_template   TEXT (with {{placeholders}})
  channels        VARCHAR[] ARRAY['EMAIL', 'SMS', 'PUSH']
  max_retries     INT DEFAULT 3
  created_at      TIMESTAMP NOT NULL
  updated_at      TIMESTAMP NOT NULL
```

### Outbox Events (Wave 33)

```sql
outbox_events:
  id              UUID PRIMARY KEY
  notification_id UUID NOT NULL (FK)
  event_type      VARCHAR(100)
  payload         JSONB
  created_at      TIMESTAMP NOT NULL DEFAULT now()
  published_at    TIMESTAMP (NULL until CDC)
```

---

## Kafka Events

### Consumed Topics

**orders.events**
```
OrderCreated, OrderCancelled, OrderConfirmed
→ Trigger order notifications
```

**payments.events**
```
PaymentCompleted, PaymentFailed, PaymentRefunded
→ Trigger payment status notifications
```

**fulfillment.events**
```
OrderShipped, OrderDelivered, DeliveryException
→ Trigger shipment notifications
```

**identity.events**
```
UserCreated
→ Send welcome email
```

### Error Handling & DLQ (Wave 33 Track B)

**Failed Notification Handling:**

```
1. Kafka message received
2. Parse and validate
3. Query user notification preferences
4. Attempt send (email/SMS/push)

If send fails:
  - Retry with exponential backoff (3 attempts)
  - After 3 failures:
    → INSERT into notification.events.DLT (dead-letter topic)
    → Log ERROR to notification_log (status = FAILED)
    → Alert monitoring team

If partially successful (e.g., email sent, SMS failed):
  - Update notification_log with partial status
  - Publish to notification.events.DLT for manual investigation
```

**DLT Topic:** `notification.events.DLT`
- Retention: 30 days
- Manual review: On-call team investigates root cause

### Published Topics (Wave 33)

**notification.events** (via outbox relay)
```
NotificationSent: {
  notificationId, userId, channel, templateName,
  sentAt, providerId
}

NotificationFailed: {
  notificationId, userId, channel, templateName,
  reason, retryCount, errorDetails
}
```

---

## Resilience Configuration

### Retry Logic

```yaml
notification:
  retry:
    maxAttempts: 3
    backoffMultiplier: 2.0
    initialDelayMs: 1000
    maxDelayMs: 30000
```

Exponential backoff: 1s → 2s → 4s → fail → DLT

### Circuit Breaker (per provider)

```yaml
resilience4j:
  circuitbreaker:
    instances:
      emailProvider:
        failureRateThreshold: 50%
        slowCallRateThreshold: 80%
        slowCallDurationThreshold: 5000ms
        waitDurationInOpenState: 60s
```

If SendGrid has 50%+ failures for 60s, circuit opens → fallback to logging

### Rate Limiting

- Email: 100 msgs/sec per recipient (SendGrid limit)
- SMS: 10 msgs/sec per phone number (Twilio limit)
- Push: 1000 msgs/sec (Firebase limit)

Implemented via Resilience4j rate limiter per channel.

---

## Testing

```bash
./gradlew :services:notification-service:test
./gradlew :services:notification-service:integrationTest
```

Test coverage:
- Template rendering with placeholders
- Retry logic and exponential backoff
- Circuit breaker state transitions
- DLT forwarding on exhausted retries
- Multi-channel dispatch
- Duplicate detection (idempotency)

---

## Deployment

```bash
export NOTIFICATION_DB_URL=jdbc:postgresql://localhost:5432/notification
export KAFKA_BOOTSTRAP_SERVERS=localhost:9092
export SENDGRID_API_KEY=<key>
export TWILIO_ACCOUNT_SID=<sid>
export TWILIO_AUTH_TOKEN=<token>
export FCM_PROJECT_ID=<project>

./gradlew :services:notification-service:bootRun
```

### Kubernetes

```bash
kubectl apply -f k8s/notification-service/deployment.yaml
```

Secrets configured via GCP Secret Manager (sm:// notation in application.yml)

---

## Observability

**Metrics:**
- `notification.sent.count` — notifications sent by channel
- `notification.failed.count` — failed notifications
- `notification.retry.count` — retries triggered
- `notification.provider.latency_ms` — external provider response time
- `notification.dlq.count` — messages sent to DLT

**Health Checks:**
- Liveness: `/actuator/health/live`
- Readiness: `/actuator/health/ready` (DB + Kafka consumer group)

---

## Known Limitations

1. No notification preferences UI (hard-coded per event type)
2. No rich HTML templates (plain text only)
3. No A/B testing of notification copy
4. No engagement tracking (opens, clicks)
5. No batch scheduling (immediate send)

**Roadmap (Wave 34+):**
- User notification preference center
- HTML email templates with CSS inlining
- Delivery time optimization (batch at off-peak)
- Click/open tracking via pixel + links
- In-app notification center

