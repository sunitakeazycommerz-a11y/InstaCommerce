# Notification Service

Event-driven notification delivery service for InstaCommerce. Consumes domain events from order, payment, fulfillment, and identity Kafka topics and delivers notifications via SMS, email, and push channels. Features a Mustache template engine, database-backed deduplication, user preference-based channel routing, and GDPR-compliant erasure handling.

## Architecture Overview

| Layer | Components |
|---|---|
| **Kafka Consumers** | `FulfillmentEventConsumer`, `OrderEventConsumer`, `PaymentEventConsumer`, `IdentityEventConsumer` |
| **Core Services** | `NotificationService`, `TemplateService`, `UserPreferenceService`, `DeduplicationService` |
| **Providers** | `SendGridEmailProvider` (email), `TwilioSmsProvider` (SMS), `LoggingProvider` (fallback) |
| **Infrastructure** | `RetryableNotificationSender`, `NotificationRetryJob`, `NotificationDlqPublisher`, `NotificationLogCleanupJob` |
| **REST Clients** | `RestUserDirectoryClient` → identity-service, `RestOrderLookupClient` → order-service |
| **GDPR** | `UserErasureService`, `AuditLogService` |

## Kafka Topics

| Topic | Consumer | Events | Purpose |
|---|---|---|---|
| `orders.events` | `OrderEventConsumer` | `OrderPlaced`, `OrderPacked`, `OrderDelivered` | Order lifecycle notifications |
| `fulfillment.events` | `FulfillmentEventConsumer` | `OrderDispatched` | Dispatch & delivery updates |
| `payments.events` | `PaymentEventConsumer` | `PaymentRefunded` | Refund confirmations |
| `identity.events` | `IdentityEventConsumer` | `UserErased` | GDPR right-to-erasure |
| `notifications.dlq` | — (produced to) | Failed notifications | Dead-letter queue |

All consumers use group ID `notification-service` with concurrency of 3 partitions.

## Template Registry

The `TemplateRegistry` maps event types to template definitions, each specifying which channels to deliver on:

| Event Type | Template ID | Channels | Email Subject |
|---|---|---|---|
| `OrderPlaced` | `order_confirmed` | EMAIL, SMS | Order confirmed |
| `OrderPacked` | `order_packed` | SMS | Order packed |
| `OrderDispatched` | `order_dispatched` | SMS, PUSH | Out for delivery |
| `OrderDelivered` | `order_delivered` | EMAIL, SMS | Order delivered |
| `PaymentRefunded` | `payment_refunded` | EMAIL | Payment refunded |

Templates are Mustache files stored at `src/main/resources/templates/{channel}/{templateId}.mustache`.

## Diagrams

### 1. Event-to-Notification Flow

```mermaid
flowchart TD
    subgraph Kafka Topics
        OT[orders.events]
        FT[fulfillment.events]
        PT[payments.events]
        IT[identity.events]
    end

    subgraph Consumers
        OC[OrderEventConsumer]
        FC[FulfillmentEventConsumer]
        PC[PaymentEventConsumer]
        IC[IdentityEventConsumer]
    end

    OT --> OC
    FT --> FC
    PT --> PC
    IT --> IC

    OC --> |EventEnvelope| NS[NotificationService.handleEvent]
    FC --> |EventEnvelope| NS
    PC --> |EventEnvelope| NS

    IC --> |UserErasedEvent| UES[UserErasureService.anonymizeUser]

    NS --> TR[TemplateRegistry.resolve]
    TR --> |No match| DROP([Ignored — no template])
    TR --> |TemplateDefinition| UID[Resolve userId from payload]
    UID --> |Missing userId| OLC[OrderLookupClient.findOrder]
    OLC --> |OrderSnapshot.userId| PREFS

    UID --> PREFS[UserPreferenceService.getPreferences]
    PREFS --> LOOP{For each channel in template}

    LOOP --> OPT{User opted out?}
    OPT --> |Yes| SKIP([SKIPPED — Opted out])
    OPT --> |No| RCPT[Resolve recipient]
    RCPT --> |Missing| SKIPR([SKIPPED — Missing recipient])
    RCPT --> |Found| DEDUP[DeduplicationService.createLog]
    DEDUP --> |Duplicate| DUP([Duplicate — ignored])
    DEDUP --> |New log| RENDER[TemplateService.render]
    RENDER --> SEND[RetryableNotificationSender.send]
    SEND --> PROVIDER[NotificationProvider.send]
    PROVIDER --> |Success| SENT[(Status: SENT)]
    PROVIDER --> |ProviderTemporaryException| RETRY[(Status: RETRY_PENDING)]
    PROVIDER --> |ProviderPermanentException| FAIL[(Status: FAILED)]
    FAIL --> DLQ[NotificationDlqPublisher → notifications.dlq]
```

### 2. Channel Routing Logic

```mermaid
flowchart TD
    EVENT[Incoming Event] --> TR[TemplateRegistry.resolve]
    TR --> TD[TemplateDefinition]
    TD --> CHANNELS["channels: e.g. EMAIL, SMS, PUSH"]

    CHANNELS --> FOREACH{For each channel}

    FOREACH --> EMAIL_CH[Channel: EMAIL]
    FOREACH --> SMS_CH[Channel: SMS]
    FOREACH --> PUSH_CH[Channel: PUSH]

    EMAIL_CH --> EP{emailOptOut?}
    EP --> |true| ES([SKIPPED])
    EP --> |false| ER[Resolve email from payload or UserDirectoryClient]
    ER --> |null| ESK([SKIPPED — no email])
    ER --> |found| EDELIV[SendGridEmailProvider]

    SMS_CH --> SP{smsOptOut?}
    SP --> |true| SS([SKIPPED])
    SP --> |false| SR[Resolve phone from payload or UserDirectoryClient]
    SR --> |null| SSK([SKIPPED — no phone → DLQ])
    SR --> |found| SDELIV[TwilioSmsProvider]

    PUSH_CH --> PP{pushOptOut?}
    PP --> |true| PS([SKIPPED])
    PP --> |false| PR[Resolve deviceToken from payload]
    PR --> |null| PSK([SKIPPED — no device token])
    PR --> |found| PDELIV[Push Provider]

    style ES fill:#f9f,stroke:#333
    style SS fill:#f9f,stroke:#333
    style PS fill:#f9f,stroke:#333
```

### 3. Template Rendering Pipeline

```mermaid
flowchart LR
    subgraph Input
        ET[eventType]
        PL[payload JSON]
        UC[UserContact]
        OS[OrderSnapshot]
    end

    ET --> BV[buildVariables]
    PL --> BV
    UC --> BV
    OS --> BV

    BV --> VARS["variables map:<br/>userName, orderId,<br/>totalFormatted, eta,<br/>riderName, refundAmount, ..."]

    VARS --> TS[TemplateService.render]

    subgraph TemplateService
        TS --> TP["templatePath(channel, templateId)<br/>→ classpath:templates/{channel}/{id}.mustache"]
        TP --> CACHE{templateCache hit?}
        CACHE --> |miss| LOAD[ResourceLoader.getResource<br/>→ load & cache]
        CACHE --> |hit| COMPILE
        LOAD --> COMPILE[Mustache.compiler.compile]
        COMPILE --> EXEC["template.execute(variables)"]
    end

    EXEC --> BODY[Rendered notification body]
```

### 4. Deduplication Mechanism

```mermaid
flowchart TD
    REQ[NotificationRequest<br/>eventId + channel] --> CS[DeduplicationService.createLog]

    CS --> NL["new NotificationLog<br/>eventId, channel, userId,<br/>templateId, recipient"]
    NL --> SAVE["logRepository.save(log)"]

    SAVE --> |Success| OK[Return NotificationLog<br/>→ proceed to send]
    SAVE --> |DataIntegrityViolationException| DUP["Unique constraint violated<br/>(event_id, channel)"]
    DUP --> NULL[Return null → skip]

    subgraph PostgreSQL
        UQ["UNIQUE (event_id, channel)<br/>on notification_log table"]
    end

    SAVE -.-> UQ
    DUP -.-> UQ

    subgraph Event ID Resolution
        RK[record.key] --> |non-blank| EID[eventId]
        ENV_ID[envelope.id] --> |fallback| EID
        AGG["aggregateId:eventType"] --> |fallback| EID
        OFFSET["eventType:partition:offset"] --> |fallback| EID
        EID --> |length > 64| HASH["SHA-256 → 64-char hex"]
    end
```

### 5. GDPR Erasure Handling

```mermaid
flowchart TD
    KT[identity.events topic] --> IC[IdentityEventConsumer]
    IC --> PARSE[Parse EventEnvelope]
    PARSE --> CHECK{eventType == UserErased?}
    CHECK --> |No| IGN([Ignore])
    CHECK --> |Yes| EXTRACT[Extract UserErasedEvent<br/>userId, erasedAt]

    EXTRACT --> |userId is null| WARN([Log warning & skip])
    EXTRACT --> |valid userId| UES[UserErasureService.anonymizeUser]

    UES --> TX["@Transactional"]

    TX --> ANON["notificationLogRepository<br/>.anonymizeByUserId(userId, REDACTED)"]
    ANON --> REDACT["Overwrite recipient, body, subject<br/>with [REDACTED]"]

    TX --> AUDIT[AuditLogService.log]
    AUDIT --> AL["AuditLog record:<br/>action = USER_ERASURE_APPLIED<br/>entityType = NotificationLog<br/>details = {updated: N, erasedAt: ...}"]

    subgraph Data After Erasure
        NL["notification_log:<br/>recipient = [REDACTED]<br/>PII fields cleared"]
        AUD["audit_log:<br/>Immutable erasure record<br/>with trace_id"]
    end

    REDACT --> NL
    AL --> AUD
```

### 6. Notification Lifecycle

```mermaid
stateDiagram-v2
    [*] --> PENDING : DeduplicationService.createLog

    PENDING --> SENT : Provider returns success
    PENDING --> RETRY_PENDING : ProviderTemporaryException<br/>(retryCount < 3)
    PENDING --> FAILED : ProviderPermanentException
    PENDING --> SKIPPED : Opted out / missing recipient /<br/>no provider configured

    RETRY_PENDING --> SENT : Retry succeeds
    RETRY_PENDING --> RETRY_PENDING : Retry fails temporarily<br/>(retryCount < 3)
    RETRY_PENDING --> FAILED : Retries exhausted (≥ 3)

    FAILED --> [*]
    SENT --> [*]
    SKIPPED --> [*]

    note right of RETRY_PENDING
        Backoff schedule:
        Attempt 1 → 5s
        Attempt 2 → 30s
        Attempt 3 → 5min
        Polled by NotificationRetryJob
        (fixedDelay: 5s)
    end note

    note right of FAILED
        Published to
        notifications.dlq
        via NotificationDlqPublisher
    end note

    note left of PENDING
        Persisted to notification_log
        with UNIQUE(event_id, channel)
    end note
```

## Retry & DLQ Strategy

1. **First attempt** — `RetryableNotificationSender.send()` dispatches asynchronously via `@Async("notificationExecutor")`.
2. **Temporary failure** — status set to `RETRY_PENDING` with `nextRetryAt` using exponential backoff (5 s → 30 s → 5 min).
3. **`NotificationRetryJob`** — scheduled poller (`fixedDelay: 5000 ms`) picks up `RETRY_PENDING` rows whose `nextRetryAt` has passed, in batches of 100.
4. **Exhausted retries** (≥ 3 attempts) or **permanent failure** — status set to `FAILED`, event published to `notifications.dlq` Kafka topic.
5. **`NotificationLogCleanupJob`** — daily cron (`0 0 3 * * *`) deletes logs older than 90 days.

## Database Schema

Managed by Flyway. Three migrations:

| Migration | Description |
|---|---|
| `V1__create_notification_log.sql` | `notification_log` table with `UNIQUE(event_id, channel)` for deduplication |
| `V2__create_audit_log.sql` | `audit_log` table for GDPR-compliant action tracking |
| `V3__add_retry_columns.sql` | Adds `retry_count`, `next_retry_at`, `event_type`, `subject`, `body` columns; partial index on `RETRY_PENDING` status |

## Observability

| Signal | Implementation |
|---|---|
| **Metrics** | Micrometer counters: `notification.sent`, `notification.failed`, `notification.skipped` exported via OTLP |
| **Tracing** | OpenTelemetry via `micrometer-tracing-bridge-otel`; trace IDs attached to audit logs |
| **Logging** | Structured JSON via `logstash-logback-encoder`; PII masked by `MaskingUtil` |
| **Health** | Spring Actuator liveness/readiness probes; readiness includes DB check |

## Configuration

Key properties in `application.yml` (all overridable via environment variables):

| Property | Default | Description |
|---|---|---|
| `notification.providers.sendgrid.api-key` | — | SendGrid API key (via Secret Manager) |
| `notification.providers.sendgrid.from-email` | `no-reply@instacommerce.com` | Sender email |
| `notification.providers.twilio.account-sid` | — | Twilio SID |
| `notification.providers.twilio.from-number` | — | Twilio sender number |
| `notification.identity.base-url` | `http://localhost:8081` | identity-service URL for user preferences |
| `notification.identity.preference-cache-ttl` | `60s` | TTL for cached user preferences |
| `notification.order.base-url` | `http://localhost:8085` | order-service URL for order lookups |
| `notification.delivery.default-eta-minutes` | `15` | Fallback ETA when not in event payload |
| `notification.dlq-topic` | `notifications.dlq` | Kafka DLQ topic |
| `notification.retry.batch-size` | `100` | Max rows per retry poll cycle |

## Tech Stack

- **Runtime**: Java 21, Spring Boot, Spring Kafka
- **Database**: PostgreSQL with Flyway migrations
- **Templates**: Mustache (`spring-boot-starter-mustache`)
- **Email**: SendGrid
- **SMS**: Twilio
- **Secrets**: Google Cloud Secret Manager
- **Observability**: Micrometer + OpenTelemetry, Logstash JSON logging
- **Container**: Multi-stage Docker build, JRE 21 Alpine, ZGC, non-root user

## Project Structure

```
src/main/java/com/instacommerce/notification/
├── config/                  # Properties, Kafka error handling, security, async config
├── consumer/                # Kafka consumers + EventEnvelope DTO
├── domain/
│   ├── model/               # NotificationLog, NotificationChannel, NotificationStatus, AuditLog
│   └── valueobject/         # TemplateId
├── dto/                     # NotificationRequest, NotificationResult
├── exception/               # TraceIdProvider
├── infrastructure/
│   ├── metrics/             # NotificationMetrics (Micrometer counters)
│   └── retry/               # RetryableNotificationSender, RetryJob, CleanupJob, DlqPublisher
├── provider/                # NotificationProvider SPI, SendGrid, Twilio, Logging implementations
├── repository/              # JPA repositories for notification_log and audit_log
├── service/                 # Core services: Notification, Template, Deduplication, UserPreference, Erasure
└── template/                # TemplateRegistry, TemplateDefinition
```
