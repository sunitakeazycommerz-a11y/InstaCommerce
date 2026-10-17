# 07 — Notification Service (notification-service)

> **Bounded Context**: Customer Messaging (Email, SMS, Push)  
> **Runtime**: Java 21 + Spring Boot 4.0.x on GKE  
> **Datastore**: Cloud SQL for PostgreSQL 15 (notification log)  
> **Providers**: SendGrid (email), Twilio (SMS) — sandbox mode  
> **Port (local)**: 8088

---

## 1. Architecture Overview

```
  Kafka topics:                           
    orders.events          ─┐              
    fulfillment.events     ─┤              
    payments.events        ─┘              
                            │              
                            ▼              
  ┌──────────────────────────────────────┐
  │        notification-service           │
  │                                       │
  │  ┌────────────────┐                   │
  │  │ EventConsumer   │  Kafka listener  │
  │  └───────┬────────┘                   │
  │          │                            │
  │  ┌───────▼────────┐                   │
  │  │ NotificationSvc │                  │
  │  │ (route + render)│                  │
  │  └───────┬────────┘                   │
  │          │                            │
  │  ┌───────▼────────┐                   │
  │  │ TemplateEngine  │ (Mustache/       │
  │  │                 │  Thymeleaf)      │
  │  └───────┬────────┘                   │
  │          │                            │
  │  ┌───────▼──────────────────────────┐ │
  │  │  ProviderAdapter (strategy)      │ │
  │  │  ├── EmailProvider (SendGrid)    │ │
  │  │  ├── SmsProvider (Twilio)        │ │
  │  │  └── PushProvider (FCM) (future) │ │
  │  └───────┬──────────────────────────┘ │
  │          │                            │
  │  ┌───────▼────────┐                   │
  │  │ PostgreSQL      │                  │
  │  │ notification_log│                  │
  │  └────────────────┘                   │
  └──────────────────────────────────────┘
```

---

## 2. Package Structure

```
com.instacommerce.notification
├── NotificationServiceApplication.java
├── config/
│   ├── KafkaConsumerConfig.java
│   ├── ProviderConfig.java         # SendGrid/Twilio credentials from Secret Manager
│   └── RetryConfig.java            # Retry policies
├── consumer/
│   ├── OrderEventConsumer.java     # orders.events
│   ├── FulfillmentEventConsumer.java
│   └── PaymentEventConsumer.java
├── dto/
│   ├── NotificationRequest.java    # {userId, channel, templateId, variables}
│   └── NotificationResult.java
├── domain/
│   ├── model/
│   │   ├── NotificationLog.java    # Persist delivery attempts
│   │   ├── NotificationChannel.java # enum: EMAIL, SMS, PUSH
│   │   └── NotificationStatus.java  # enum: PENDING, SENT, FAILED, SKIPPED
│   └── valueobject/
│       └── TemplateId.java
├── service/
│   ├── NotificationService.java    # Orchestrates: resolve user prefs → render → send
│   ├── TemplateService.java        # Render templates with variables
│   ├── UserPreferenceService.java  # Check opt-out (calls identity or local cache)
│   └── DeduplicationService.java   # Prevent duplicate sends (by event_id)
├── provider/
│   ├── NotificationProvider.java   # Interface
│   ├── SendGridEmailProvider.java
│   ├── TwilioSmsProvider.java
│   └── LoggingProvider.java        # For local dev: just logs the message
├── template/
│   ├── templates/                   # Mustache/Thymeleaf files
│   │   ├── order_confirmed.html
│   │   ├── order_dispatched.html
│   │   ├── order_delivered.html
│   │   └── payment_refunded.html
│   └── TemplateRegistry.java       # Maps event_type → template_id → channel
├── repository/
│   └── NotificationLogRepository.java
├── exception/
│   └── GlobalExceptionHandler.java
└── infrastructure/
    ├── retry/
    │   └── RetryableNotificationSender.java  # Exponential backoff
    └── metrics/
        └── NotificationMetrics.java
```

---

## 3. Event → Notification Mapping

| Event | Channel | Template | Variables |
|-------|---------|----------|-----------|
| OrderPlaced | EMAIL + SMS | order_confirmed | userName, orderId, total, eta |
| OrderPacked | SMS | order_packed | orderId |
| OrderDispatched | SMS + PUSH | order_dispatched | orderId, riderName, eta |
| OrderDelivered | EMAIL + SMS | order_delivered | orderId, deliveredAt |
| PaymentRefunded | EMAIL | payment_refunded | orderId, refundAmount |

---

## 4. Database Schema

```sql
CREATE TABLE notification_log (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id         UUID         NOT NULL,
    event_id        VARCHAR(64)  NOT NULL,   -- Kafka event ID for dedup
    channel         VARCHAR(10)  NOT NULL,    -- EMAIL, SMS, PUSH
    template_id     VARCHAR(50)  NOT NULL,
    recipient       VARCHAR(255) NOT NULL,    -- email or phone (masked in logs)
    status          VARCHAR(20)  NOT NULL DEFAULT 'PENDING',
    provider_ref    VARCHAR(255),
    attempts        INT          NOT NULL DEFAULT 0,
    last_error      TEXT,
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),
    sent_at         TIMESTAMPTZ,
    CONSTRAINT uq_notification_dedup UNIQUE (event_id, channel)
);
```

---

## 5. Retry & Dead Letter

```java
@Component
@RequiredArgsConstructor
public class RetryableNotificationSender {
    
    private static final int MAX_RETRIES = 3;
    private static final Duration[] BACKOFF = {
        Duration.ofSeconds(5), Duration.ofSeconds(30), Duration.ofMinutes(5)
    };
    
    private final NotificationProvider provider;
    private final NotificationLogRepository logRepo;
    
    public void send(NotificationLog notification) {
        for (int attempt = 0; attempt < MAX_RETRIES; attempt++) {
            try {
                String providerRef = provider.send(notification);
                notification.setStatus("SENT");
                notification.setProviderRef(providerRef);
                notification.setSentAt(Instant.now());
                logRepo.save(notification);
                return;
            } catch (ProviderTemporaryException e) {
                notification.setAttempts(attempt + 1);
                notification.setLastError(e.getMessage());
                logRepo.save(notification);
                sleep(BACKOFF[attempt]);
            } catch (ProviderPermanentException e) {
                notification.setStatus("FAILED");
                notification.setLastError(e.getMessage());
                logRepo.save(notification);
                return; // Don't retry permanent failures (e.g. invalid phone)
            }
        }
        notification.setStatus("FAILED");
        logRepo.save(notification);
        // Emit NotificationFailed metric
    }
}
```

---

## 6. Template Example (Mustache)

### order_confirmed.html
```html
<h2>Order Confirmed! 🎉</h2>
<p>Hi {{userName}},</p>
<p>Your order <strong>#{{orderId}}</strong> has been placed successfully.</p>
<p>Order total: ₹{{totalFormatted}}</p>
<p>Expected delivery: {{eta}}</p>
<p>Track your order in the app.</p>
```

### order_confirmed (SMS text)
```
Hi {{userName}}, your order #{{orderId}} (₹{{totalFormatted}}) is confirmed! ETA: {{eta}}. Track in app.
```

---

## 7. Agent Instructions (CRITICAL)

### MUST DO
1. Deduplicate by `(event_id, channel)` — UNIQUE constraint prevents sending same notification twice.
2. Check user opt-out preference before sending marketing messages.
3. Mask PII in logs: log email as `u***@example.com`, phone as `+91****1234`.
4. Use `LoggingProvider` in local/test profiles — never call real SMS/email providers in tests.
5. Retry only on temporary failures (network, rate limit); permanent failures (invalid address) stop immediately.

### MUST NOT DO
1. Do NOT send real messages in non-production environments.
2. Do NOT store full phone/email in notification_log — the recipient field is for delivery, mask it in any log output.
3. Do NOT block Kafka consumer on slow provider calls — use async sending.

### DEFAULTS
- Max retries: 3
- Backoff: 5s, 30s, 5min
- DLQ topic: `notifications.dlq` (for events that fail all retries)
- Local dev: `LoggingProvider` only
