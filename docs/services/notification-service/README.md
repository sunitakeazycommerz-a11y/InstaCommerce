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

## Notification Preferences Management

**User Preferences Per Channel:**

```
Email Preferences:
  - Receive order updates: ON/OFF
  - Receive payment confirmations: ON/OFF
  - Receive promotional: ON/OFF
  - Digest frequency: IMMEDIATE / DAILY / WEEKLY / OFF

SMS Preferences:
  - Receive urgent alerts: ON/OFF (order failed, payment issue)
  - Receive delivery updates: ON/OFF
  - Allow marketing: ON/OFF

Push Preferences:
  - Receive notifications: ON/OFF
  - In-app banner: ON/OFF
  - Badge count: ON/OFF
```

**Preference Storage:**
```sql
user_preferences:
  user_id, channel, notification_type, enabled, updated_at
```

**Flow:**
1. User updates preferences in settings
2. Identity-service publishes UserPreferencesUpdated
3. Notification-service consumes and updates cache (Redis)
4. Before sending any notification: Check preferences
5. If disabled: Skip send, log "skipped due to user preference"

## Template Versioning & A/B Testing

**Template Management:**

```
Email Template: "Order Shipped"
├── Version 1 (baseline)
│   Subject: "Your order has shipped!"
│   Body: "Order ID: {order_id}"
│
├── Version 2 (experiment_a, 50% traffic)
│   Subject: "Great news! Your order is on the way 📦"
│   Body: "Order ID: {order_id}\nTracking: {tracking_url}"
│   CTA: "Track package" (blue button)
│
└── Version 3 (experiment_b, 50% traffic)
    Subject: "Your order is heading to you"
    Body: "Order ID: {order_id}\nETA: {eta_date}"
    CTA: "View order" (green button)
```

**Versioning Logic:**
```java
// Select template version based on A/B test config
TemplateVersion version = abTestSelector.selectVersion(
  userId, templateName, cohorts=["variant_a", "variant_b"]
);
```

**Metrics Tracked per Variant:**
```
- Open rate (email: pixel tracking)
- Click-through rate (URL redirect tracking)
- Conversion rate (user action post-click)
- Unsubscribe rate
```

**Example Experiment:**
```
Week 1: A/B test emoji in subject line
Week 2: Analyze: Emoji version has 12% better open rate
Week 3: Roll out emoji version to 100%
Week 4: Update baseline; test new variant
```

## Delivery Retry Strategy

**Exponential Backoff + Jitter:**

```
Attempt 1: Immediate send
  ✓ Success: Mark SENT
  ✗ Fail: Log error, schedule Attempt 2

Attempt 2: Delay = 1s + random(0-100ms)
  ✓ Success: Mark SENT
  ✗ Fail: Log error, schedule Attempt 3

Attempt 3: Delay = 2s + random(0-100ms)
  ✓ Success: Mark SENT
  ✗ Fail: Log error, schedule Attempt 4 (if enabled)

Attempt 4: Delay = 4s + random(0-100ms)
  ✓ Success: Mark SENT
  ✗ Fail: Move to DLQ, alert ops

Total max time: ~7 seconds before DLQ
```

**Configuration:**
```yaml
notification:
  retry:
    maxAttempts: 3
    backoffMultiplier: 2.0
    initialDelayMs: 1000
    maxDelayMs: 30000
    jitterFactor: 0.1
```

## Provider Failover Strategy

**Email Failover Chain:**

```
1. SendGrid (primary, fastest)
   ↓ if failure rate > 5% for 60s:
2. Mailgun (secondary, more reliable)
   ↓ if both down:
3. Local queue + manual send later
```

**Configuration:**
```yaml
email:
  providers:
    - name: sendgrid
      priority: 1
      timeout: 3s
      failureThreshold: 5%
    - name: mailgun
      priority: 2
      timeout: 5s
      failureThreshold: 10%
```

**SMS Failover Chain:**

```
1. Twilio (primary, 99.9% uptime)
   ↓ if failure rate > 2% for 120s:
2. Nexmo (secondary)
   ↓ if both down:
3. Queue for manual retry (rare)
```

## Monitoring & Alerts (20+ Metrics per Channel)

### Key Metrics

| Metric | Type | Alert Threshold | Channel | Purpose |
|--------|------|-----------------|---------|---------|
| `notification_sent_total` | Counter | N/A | All | Sent volume |
| `notification_failed_total` | Counter | > 1% = alert | All | Failure tracking |
| `notification_latency_ms` | Histogram (p99) | > 500ms email | Email | Send speed |
| `notification_latency_ms` | Histogram (p99) | > 200ms sms | SMS | Send speed |
| `notification_latency_ms` | Histogram (p99) | > 100ms push | Push | Send speed |
| `email_send_success_rate` | Gauge | < 99% = alert | Email | Email health |
| `email_bounced_rate` | Gauge | > 0.5% = investigate | Email | Bounce rate |
| `email_open_rate` | Gauge | < 15% = investigate | Email | Engagement |
| `email_click_rate` | Gauge | < 2% = investigate | Email | CTR |
| `sms_send_success_rate` | Gauge | < 99.9% = alert | SMS | SMS health |
| `sms_delivery_rate` | Gauge | < 99% = investigate | SMS | Delivery confirmation |
| `push_send_success_rate` | Gauge | < 98% = alert | Push | Push health |
| `push_delivery_rate` | Gauge | < 95% = investigate | Push | Delivery rate |
| `push_app_crash_rate` | Gauge | > 1% = investigate | Push | App stability |
| `notification_provider_latency_ms` | Histogram | SendGrid p99 > 2s | Email | Provider performance |
| `notification_provider_latency_ms` | Histogram | Twilio p99 > 1s | SMS | Provider performance |
| `notification_dlq_messages` | Counter | > 0 = investigate | All | Dead-letter queue depth |
| `notification_retry_attempts` | Counter (by attempt) | N/A | All | Retry distribution |
| `notification_preferences_cache_hit_rate` | Gauge | < 90% = investigate | All | Cache efficiency |
| `notification_template_render_latency_ms` | Histogram | > 50ms | All | Template perf |
| `webhook_provider_latency_ms` | Histogram | > 1s | Webhook | Webhook latency |
| `user_preference_update_latency_ms` | Histogram | > 200ms | All | Preference sync |
| `notification_rate_limit_exceeded` | Counter | > 0 = investigate | All | Rate limiting issues |

### Alerting Rules

```yaml
alerts:
  - name: EmailSendFailure
    condition: rate(email_send_failures[5m]) > 0.01
    duration: 5m
    severity: SEV-2
    action: Check SendGrid health; verify API key

  - name: SMSDeliveryDown
    condition: sms_delivery_rate < 0.99
    duration: 10m
    severity: SEV-2
    action: Check Twilio health; verify phone numbers

  - name: PushNotificationCrash
    condition: push_app_crash_rate > 0.01
    duration: 5m
    severity: SEV-2
    action: Check FCM payload; possible malformed JSON

  - name: DLQDepthIncreasing
    condition: rate(notification_dlq_messages[1h]) > 0
    duration: 30m
    severity: SEV-3
    action: Manual review of failed notifications; update retry config

  - name: TemplateRenderSlow
    condition: histogram_quantile(0.99, notification_template_render_latency_ms) > 100
    duration: 10m
    severity: SEV-3
    action: Check template complexity; optimize Handlebars parsing
```

## Security Considerations

### Threat Mitigations

1. **XSS Prevention**: Template rendering escapes all user input (Handlebars escaping)
2. **Email Injection Prevention**: Recipient email validated; special characters sanitized
3. **SMS Injection Prevention**: Message body validated; GSM charset enforced
4. **PII Leakage Prevention**: Sensitive data (card numbers, etc.) not logged; token only
5. **Rate Limiting**: Per-user per-channel rate limiting (100 emails/day, etc.)
6. **Authentication**: Service-to-service calls validated via JWT

### Known Risks

- **Email Spoofing**: SendGrid domain can be spoofed (mitigated by SPF/DKIM/DMARC)
- **SMS Spoofing**: Twilio number could be spoofed externally (mitigated by verified sender)
- **Preference Bypass**: Admin could override user preferences (mitigated by audit log)
- **Template Injection**: Malicious template variable names (mitigated by allowlist)

## Troubleshooting (8+ Scenarios)

### Scenario 1: Email Send Failures Spike (50% failure rate)

**Indicators:**
- `email_send_success_rate` drops to 50%
- SendGrid provider returning errors
- Users not receiving order confirmations

**Root Causes:**
1. SendGrid API quota exceeded
2. Invalid API key or authentication
3. Network connectivity issue
4. SendGrid service degradation

**Resolution:**
```bash
# Check SendGrid health
curl https://status.sendgrid.com/api/v2/summary.json

# Verify API key
curl https://api.sendgrid.com/v3/mail/send \
  -H "Authorization: Bearer $SENDGRID_API_KEY" \
  -d '{"personalizations":[{"to":[{"email":"test@example.com"}]}]}'

# Check current email volume vs quota
curl http://notification-service:8089/admin/sendgrid/usage

# Switch to failover provider if needed
NOTIFICATION_EMAIL_PRIMARY_PROVIDER=mailgun (from sendgrid)

# Restart notification-service with new provider
kubectl rollout restart deployment/notification-service
```

### Scenario 2: SMS Delivery Not Confirmed (Message sent but no delivery receipt)

**Indicators:**
- `sms_delivery_rate` < 99%
- Kafka events show SMS sent
- Twilio doesn't send delivery webhook

**Root Causes:**
1. Twilio webhook URL unreachable
2. Phone number not valid (invalid format)
3. Twilio account not enabled for delivery receipts

**Resolution:**
```bash
# Check Twilio webhook configuration
curl https://api.twilio.com/2010-04-01/Accounts/{ACCOUNT_SID}/Messages \
  -u "$TWILIO_ACCOUNT_SID:$TWILIO_AUTH_TOKEN"

# Verify webhook endpoint
curl http://notification-service:8089/webhooks/twilio/delivery \
  -d 'test_delivery_receipt'

# Ensure delivery receipts enabled
# Twilio Console → Settings → Webhooks → Enable SMS delivery receipts

# Check phone number format
curl http://notification-service:8089/admin/sms/validate-number \
  -d '{"phoneNumber":"+919876543210"}'
```

### Scenario 3: Push Notification App Crash (FCM payload malformed)

**Indicators:**
- `push_app_crash_rate` spikes
- Users report app crash when receiving notification
- FCM returns 400 bad request

**Root Causes:**
1. Notification payload exceeds FCM size (4KB limit)
2. JSON malformed (missing quotes, bad escape)
3. Custom data keys too long (>1024 chars)

**Resolution:**
```bash
# Check recent FCM errors
SELECT COUNT(*), provider_response -> 'error' as error
FROM notification_log
WHERE channel = 'PUSH'
AND status = 'FAILED'
AND created_at > NOW() - INTERVAL '1 hour'
GROUP BY error;

# Validate push payload size
curl http://notification-service:8089/admin/push/validate-payload \
  -d '{"title":"...","body":"..."}'

# Simplify notification payload (reduce size)
-- Remove unnecessary fields from push data
-- Split large notifications into multiple sends

# Restart notification-service with validation
kubectl rollout restart deployment/notification-service
```

### Scenario 4: Email Template Rendering Timeout (Handlebars too complex)

**Indicators:**
- `notification_template_render_latency_ms` p99 > 500ms
- Email sends delayed > 2 seconds
- Timeouts in logs

**Root Causes:**
1. Template with complex loops (iterating large arrays)
2. Recursive template includes
3. Regular expression in template too expensive

**Resolution:**
```bash
# Identify slow template
SELECT template_id, COUNT(*) as count,
       AVG(render_latency_ms) as avg_latency,
       MAX(render_latency_ms) as max_latency
FROM notification_render_metrics
WHERE render_latency_ms > 100
GROUP BY template_id
ORDER BY max_latency DESC;

# Optimize template
-- Replace loops with pre-rendered HTML from backend
-- Remove conditional logic; pre-compute in service

# Test optimized template
curl http://notification-service:8089/admin/templates/test-render \
  -d '{
    "templateId": "order-confirmation",
    "context": {"order": {...}},
    "expectedLatencyMs": 50
  }'
```

### Scenario 5: User Preferences Not Respected (Opted-out users still receive)

**Indicators:**
- User complaint: "I turned off emails but still receiving"
- `notification_preferences_cache_hit_rate` low (cache stale)
- Emails sent despite disabled preference

**Root Causes:**
1. Preferences cache stale (TTL too long)
2. Preference update not published to Kafka
3. Race condition (old preference state used)

**Resolution:**
```bash
# Check user preference
curl http://notification-service:8089/admin/user/{userId}/preferences \
  -H "Authorization: Bearer $ADMIN_TOKEN"

# Force preference refresh
curl -X POST http://notification-service:8089/admin/preferences/refresh \
  -d '{"userId":"user-uuid"}'

# Reduce cache TTL
NOTIFICATION_PREFERENCE_CACHE_TTL=60 (from 300)

# Check Kafka consumer lag
kafka-consumer-groups --bootstrap-server kafka:9092 \
  --group notification-preference-consumer --describe

# Restart notification-service with new TTL
kubectl rollout restart deployment/notification-service
```

### Scenario 6: DLQ Backup (Thousands of failed notifications queued)

**Indicators:**
- `notification_dlq_messages` counter high
- DLQ topic has millions of messages
- No one processing DLQ

**Root Causes:**
1. Email provider down for extended period
2. DLQ consumer not running
3. Manual processing not being done

**Resolution:**
```bash
# Check DLQ depth
kafka-consumer-groups --bootstrap-server kafka:9092 \
  --group notification-dlq-consumer --describe

# Analyze sample messages
kafka-console-consumer --bootstrap-server kafka:9092 \
  --topic notification.events.DLT \
  --max-messages 10 | jq '.reason'

# Manually process DLQ
curl -X POST http://notification-service:8089/admin/dlq/reprocess \
  -d '{
    "limit": 1000,
    "notifyAfter": true
  }'

# Monitor reprocess
curl http://notification-service:8089/admin/dlq/status
```

### Scenario 7: Rate Limit Exceeded (Notification sending blocked)

**Indicators:**
- HTTP 429 Too Many Requests
- `notification_rate_limit_exceeded` counter increasing
- User: "I can only send 3 notifications/min"

**Root Causes:**
1. Rate limit threshold too low
2. Burst of notifications (legitimate, e.g., daily digest)
3. Retry loop causing amplification

**Resolution:**
```bash
# Check rate limit config
curl http://notification-service:8089/admin/config/rate-limits

# Adjust limits if needed
NOTIFICATION_EMAIL_RATE_LIMIT_PER_USER=100 (from 10, per min)
NOTIFICATION_SMS_RATE_LIMIT_PER_USER=20 (from 5, per min)

# For bulk operations, use bypass
curl -X POST http://notification-service:8089/admin/bulk-send \
  -H "X-Bypass-RateLimit: true" \
  -d '{"userIds":[...], "templateId":"daily-digest"}'

# Restart with new limits
kubectl rollout restart deployment/notification-service
```

### Scenario 8: Webhook Provider Down (Delivery receipts not received)

**Indicators:**
- No delivery confirmations for SMS/Push
- Webhook retry count high
- `webhook_provider_latency_ms` timeout spike

**Root Causes:**
1. Notification-service pod unreachable (network/firewall)
2. Webhook endpoint returns 500
3. DNS resolution failing

**Resolution:**
```bash
# Check webhook endpoint health
curl http://notification-service:8089/webhooks/twilio/delivery

# Check if pods are running
kubectl get pods -n services | grep notification

# Test webhook connectivity from Twilio/FCM perspective
telnet notification-service 8089

# Check firewall rules
kubectl get networkpolicies -n services | grep notification

# Verify DNS resolution
nslookup notification-service.services.svc.cluster.local

# Enable webhook logging
WEBHOOK_DEBUG_LOGGING=true

# Restart webhook handler
kubectl rollout restart deployment/notification-service
```

## Production Runbook Patterns

### Runbook 1: Email Provider Failover

**Scenario:** SendGrid down, switch to Mailgun

**SLA:** < 2 min failover

1. **Alert Received:** EmailSendFailure alert
2. **Verify:** SendGrid status page shows issue
3. **Failover:**
   - Set feature flag: `EMAIL_PRIMARY_PROVIDER=mailgun`
   - Restart pods: `kubectl rollout restart deployment/notification-service`
4. **Monitor:** Email success rate should recover within 2 min
5. **Rollback:** After SendGrid recovers, revert flag

### Runbook 2: Mass Email Retry (Failed batch)

**Scenario:** 10K emails failed due to provider quota

1. **Assessment:** Identify failed emails in DLQ
2. **Delay:** Wait 5 min (quota resets)
3. **Reprocess:** Batch retry DLQ messages
4. **Monitor:** Ensure success rate > 95%

## Related Documentation

- **ADR-012**: Notification Delivery Guarantees
- **Runbook**: notification-service/runbook.md

## Configuration

```env
SENDGRID_API_KEY=<key>
SENDGRID_FROM_EMAIL=noreply@instacommerce.com

TWILIO_ACCOUNT_SID=<sid>
TWILIO_AUTH_TOKEN=<token>
TWILIO_FROM_NUMBER=+1234567890

FCM_PROJECT_ID=<project>
FCM_CREDENTIALS_JSON=<json-key>

NOTIFICATION_EMAIL_RATE_LIMIT=10
NOTIFICATION_SMS_RATE_LIMIT=5
NOTIFICATION_PUSH_RATE_LIMIT=50
NOTIFICATION_PREFERENCE_CACHE_TTL=300
```

## Known Limitations

1. No notification preferences UI (hard-coded per event type)
2. No rich HTML templates (plain text only)
3. No A/B testing framework
4. No engagement tracking (opens, clicks)
5. No batch scheduling (immediate send)
6. No in-app notification center
7. No notification digest/grouping

**Roadmap (Wave 41+):**
- User notification preference center UI
- HTML email templates with CSS inlining
- A/B testing framework for variants
- Click/open tracking via pixel + links
- In-app notification center
- Batch notification delivery (off-peak scheduling)
- Notification history/archive

