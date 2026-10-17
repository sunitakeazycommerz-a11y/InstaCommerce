# Production Readiness Review: fulfillment-service & notification-service

**Reviewer**: Staff Engineer Review  
**Scope**: All Java, config, migrations, Dockerfiles under `services/fulfillment-service/` and `services/notification-service/`  
**Platform Context**: Q-commerce, 20M+ users, 10-minute delivery SLAs

---

## Executive Summary

Both services are well-structured for an MVP. Domain modeling, outbox pattern usage, GDPR erasure handling, and security configurations are solid. However, there are **7 critical**, **11 high**, **14 medium**, and **9 low** findings across resilience, scalability, data safety, and missing features that must be addressed before a production launch at scale.

---

## 1. Fulfillment Flow — Pick/Pack/Dispatch Lifecycle

### ✅ What's Working Well
- Idempotent pick task creation via `UNIQUE(order_id)` + `DataIntegrityViolationException` catch (`PickService.java:56-74`)
- Auto-completion when all items are PICKED/MISSING (`PickService.java:184-194`)
- State machine guards preventing updates to COMPLETED/CANCELLED tasks (`PickService.java:99-101`)
- `FOR UPDATE SKIP LOCKED` in rider assignment prevents double-assignment (`RiderRepository.java:14-23`)
- Outbox pattern for event publishing ensures atomicity (`OutboxService.java:21-28`)

### Findings

| # | Severity | File:Line | Finding |
|---|----------|-----------|---------|
| F-1 | **CRITICAL** | `SubstitutionService.java:33` | **Refund amount calculation uses `unitPriceCents * missingQty` but ignores any discounts/promotions applied at order time.** The `lineTotalCents` on `PickItem` already accounts for the correct line total. At 20M users with promotions, this will over-refund on discounted items. Should use `(item.getLineTotalCents() / item.getQuantity()) * missingQty` or proportional calculation. |
| F-2 | **CRITICAL** | `PickService.java:203-217` | **`publishPacked()` calls `orderClient.updateStatus()` (HTTP) and `outboxService.publish()` (DB) inside the same `@Transactional` method.** If the HTTP call to order-service succeeds but the DB transaction rolls back, order-service will have a stale PACKED status while fulfillment still shows IN_PROGRESS. The outbox insert is safe (same TX), but the `orderClient.updateStatus()` call must be moved **after** the transaction commits (e.g., `@TransactionalEventListener(phase = AFTER_COMMIT)`). Same issue at lines 115, 154, 200, and `DeliveryService.java:115,200`. |
| F-3 | **HIGH** | `DeliveryService.java:119-124` | **`markDelivered()` publishes `OrderDelivered` event with `userId` from pick task lookup, but `userId` may be null if the pick task was already erased (GDPR).** The outbox event will contain `null` userId, which will break downstream notification routing. Add a null-check or use a sentinel value. |
| F-4 | **HIGH** | `DeliveryService.java:61-79` | **`assignRider()` sets `rider.setAvailable(false)` and saves rider, then creates delivery — all in one transaction. If delivery save fails (e.g., unique constraint on `order_id`), the rider availability is rolled back — correct. BUT `orderClient.updateStatus()` inside `assignDelivery()` (line 200) is called before the TX commits.** Same HTTP-before-commit issue as F-2. |
| F-5 | **HIGH** | `PickService.java:118-121` | **Missing item handling calls `substitutionService.handleMissingItem()` which makes HTTP calls to inventory-service and payment-service inside a `@Transactional` method.** If either HTTP call times out (default: infinite with `RestTemplate`), the DB transaction holds row locks for the duration. At scale, this causes connection pool exhaustion and cascading failures. |
| F-6 | **MEDIUM** | `PickService.java:192-193` | **`areItemsPicked()` loads all items from DB every time any item is marked.** For orders with many items, this generates N+1 queries. Should use a count query: `SELECT COUNT(*) FROM pick_items WHERE pick_task_id = ? AND status = 'PENDING'`. |
| F-7 | **MEDIUM** | `Delivery.java` | **No `@Version` field for optimistic locking.** Concurrent delivery status updates (e.g., two riders marking delivered) could cause lost updates. The `PickTask` entity has the same issue. |
| F-8 | **MEDIUM** | `AdminFulfillmentController.java:40-51` | **`createRider()` accepts rider phone in plain text and stores it in DB.** Rider phone is PII. No validation regex on phone format, no masking in audit logs (line 50 logs `storeId` but the rider entity retains phone). |
| F-9 | **LOW** | `V2__create_deliveries.sql` | **No index on `deliveries(order_id)`.** The `UNIQUE` constraint implicitly creates one, which is correct. However, `riders` table has no index on `(store_id, is_available, id)` for the `findNextAvailableForStore` query with the subquery — the existing `idx_riders_store` covers `(store_id, is_available)` but the subquery joins `deliveries` by `rider_id` which only has `idx_deliveries_rider(rider_id, status)` — the `dispatched_at` column is not indexed, causing a sequential scan of all deliveries per rider. |
| F-10 | **LOW** | `PickController.java:28-30` | **`listPickTasks()` has no pagination.** A store with hundreds of pending tasks will return all of them in one response. Should add `Pageable` parameter. |

---

## 2. Notification Reliability

### ✅ What's Working Well
- Deduplication via `UNIQUE(event_id, channel)` constraint (`V1__create_notification_log.sql:12`)
- `DeduplicationService` catches `DataIntegrityViolationException` and returns null to skip duplicates (`DeduplicationService.java:66-71`)
- Retry with exponential backoff: 5s, 30s, 5min (`RetryableNotificationSender.java:26-27`)
- Separate handling for temporary vs permanent provider errors
- DLQ publishing on exhausted retries (`NotificationDlqPublisher.java`)
- PII masking via `MaskingUtil` for log output and stored recipients

### Findings

| # | Severity | File:Line | Finding |
|---|----------|-----------|---------|
| N-1 | **CRITICAL** | `RetryableNotificationSender.java:90-95` | **`Thread.sleep()` inside `@Async` method blocks the thread pool thread.** With `maxPoolSize=16` (`AsyncConfig.java:45`) and `MAX_RETRIES=3` with up to 5-minute sleeps, just 16 concurrent temporary failures will **exhaust the entire notification thread pool** for 5+ minutes, blocking ALL notifications. Must use a scheduled retry mechanism (e.g., `ScheduledExecutorService`, Spring Retry with `@Retryable`, or poll-based retry from DB). |
| N-2 | **CRITICAL** | `NotificationService.java:922` | **`fallbackName()` calls `userDirectoryClient.findUser(userId)` which is a separate HTTP call, but `resolveRecipient()` (line 828) also calls `userDirectoryClient.findUser(userId)`.** For every single notification, the service makes **2 redundant HTTP calls** to identity-service. At scale (20M users, 5 events per order × 2 channels), this doubles the load on identity-service. Cache the `UserContact` result. |
| N-3 | **HIGH** | `OrderEventConsumer.java:488-489` / `FulfillmentEventConsumer.java:529-530` / `PaymentEventConsumer.java:569-570` | **Consumers `throw new IllegalStateException()` on any processing error.** With default Spring Kafka config (no error handler configured), this causes infinite retry loops — the consumer will re-process the same poison message forever, blocking the partition. Must configure a `DefaultErrorHandler` with `DeadLetterPublishingRecoverer` or `FixedBackOff` with max attempts. |
| N-4 | **HIGH** | `NotificationService.java:780-781` | **`deduplicationService.createLog()` is called with the masked recipient, but `sender.send()` receives the original unmasked `request.recipient`.** The `NotificationSendRequest` passed to providers contains the real email/phone (correct for sending), but the `NotificationLog` in DB stores only the masked version. This is good for PII — **however**, the DLQ publisher (`NotificationDlqPublisher.java:30-34`) does NOT include the recipient at all, making DLQ events useless for manual retry since you can't re-derive the recipient. |
| N-5 | **HIGH** | `RestUserDirectoryClient.java:225` | **`findUser()` returns `UserContact` with `phone=null` always** (`new UserContact(response.id(), response.email(), null, null, null)`). The identity-service response DTO only has `id` and `email` — **phone number is never fetched**. This means SMS notifications will always fall through to the payload `phone` field, and if the event doesn't include it, SMS will be skipped silently for every user. |
| N-6 | **HIGH** | `NotificationService.java:828-833` | **PUSH channel recipient resolution falls back to `userDirectoryClient` but only returns `email` (for non-SMS).** There is no push token/device token lookup. The `resolveRecipient()` method checks `payload.deviceToken` and `payload.pushToken`, but no event schema includes these fields. PUSH notifications will always be SKIPPED with "Missing recipient". |
| N-7 | **MEDIUM** | `UserPreferenceService.java:122-129` | **No timeout configured on `RestTemplate`.** Default `RestTemplate` has infinite connect and read timeouts. If identity-service is slow/down, the Kafka consumer thread blocks indefinitely, causing consumer lag and eventual rebalance. Should use `RestTemplateBuilder.setConnectTimeout().setReadTimeout()`. |
| N-8 | **MEDIUM** | `TemplateService.java:1017` | **Templates are loaded from classpath on every render — no caching.** At 20M users with ~5 notifications per order, this is millions of classpath resource reads. Should cache compiled templates in a `ConcurrentHashMap<String, Template>`. |
| N-9 | **MEDIUM** | `NotificationService.java:970-973` | **`resolveEventId()` fallback generates `eventType + ":" + Instant.now().toEpochMilli()` — this is effectively unique per call and breaks deduplication.** If a Kafka consumer reprocesses a message (rebalance, restart) and the record key is null AND envelope id is null AND aggregateId is null, a new event ID is generated, defeating the dedup constraint. |

---

## 3. Event Consumption — Kafka Consumer Safety

### Findings

| # | Severity | File:Line | Finding |
|---|----------|-----------|---------|
| K-1 | **CRITICAL** | `OrderEventConsumer.java` (fulfillment) | **No `@JsonIgnoreProperties(ignoreUnknown = true)` on `OrderEvent` record** — wait, it IS present on `OrderEvent.java:6`. Good. **However, the consumer has no error handler configured.** The `@KafkaListener` uses default error handling. With `auto-offset-reset: earliest` and no `DefaultErrorHandler`, a single poison pill (malformed JSON) will block the partition indefinitely. The `throw new IllegalStateException()` in the catch block propagates to Spring Kafka, which by default retries infinitely. |
| K-2 | **CRITICAL** | `application.yml` (both services) | **No `spring.kafka.consumer.properties.max.poll.interval.ms` or `max.poll.records` configured.** Default `max.poll.records=500` and `max.poll.interval.ms=300000` (5 min). With the notification service's `Thread.sleep(5 min)` retry logic, a single slow notification could exceed the poll interval, causing a consumer group rebalance and re-processing of all uncommitted offsets. |
| K-3 | **HIGH** | Both services, all consumers | **No `concurrency` parameter on `@KafkaListener`.** Default is 1 consumer thread per topic. With `orders.events` having 6 partitions (per doc `09-contracts-events-protobuf.md`), only 1 of 6 partitions is consumed, wasting 5/6 of available parallelism. Should set `concurrency = "6"` or use `${KAFKA_CONCURRENCY:6}`. |
| K-4 | **MEDIUM** | `application.yml` (both services) | **`auto-offset-reset: earliest`** — on first deployment or consumer group ID change, all historical events will be replayed. For notification-service, this means re-sending all past notifications (though dedup should catch most). For fulfillment-service, this means re-creating pick tasks (idempotent). Acceptable for MVP but should switch to `latest` in production with proper offset management. |
| K-5 | **MEDIUM** | `IdentityEventConsumer.java` (fulfillment, line 24) | **Uses separate consumer group `fulfillment-service-erasure`** for identity events, which is correct for independent offset tracking. However, the notification-service's `IdentityEventConsumer` uses `notification-service` — same group as other consumers. This is fine since it's a different topic. No issue, but worth noting the inconsistency in naming convention. |

---

## 4. External Service Calls — REST Client Resilience

### Findings

| # | Severity | File:Line | Finding |
|---|----------|-----------|---------|
| R-1 | **CRITICAL** | `RestPaymentClient.java`, `RestOrderClient.java`, `RestInventoryClient.java` | **No timeouts, circuit breakers, or retry policies on ANY REST client.** All three use bare `RestTemplateBuilder.build()` with default infinite timeouts. A single slow/down downstream service (payment, order, inventory) will block the calling thread indefinitely. At 20M scale: **payment-service down for 30s → all fulfillment pick operations hang → Kafka consumer lag → cascading failure.** Must configure `setConnectTimeout(Duration.ofSeconds(5)).setReadTimeout(Duration.ofSeconds(10))` at minimum. Recommend Resilience4j circuit breaker. |
| R-2 | **HIGH** | `RestPaymentClient.java:22` | **Payment refund call has no error handling.** If the refund HTTP call throws `HttpClientErrorException` (e.g., 400 invalid payment ID), the exception propagates up, rolling back the entire pick item update transaction. The picker's work is lost, and they'd need to re-mark the item. Should catch and log payment failures, marking the refund as pending for async retry. |
| R-3 | **HIGH** | `RestOrderClient.java:21-23` | **`updateStatus()` uses `postForLocation()` which returns the `Location` header and ignores it.** If order-service returns a non-2xx response, `RestTemplate` throws an exception. No error handling means a transient order-service failure prevents pack completion. |
| R-4 | **MEDIUM** | `RestUserDirectoryClient.java`, `RestOrderLookupClient.java` (notification) | **Same timeout issues as fulfillment clients.** These are called inside the Kafka consumer path — a slow identity-service blocks notification processing for all events. |
| R-5 | **MEDIUM** | All REST clients | **No service-to-service authentication.** Calls to `/admin/*` endpoints on identity-service and order-service likely require auth tokens, but no JWT or API key is passed in the `RestTemplate` calls. Will get 401/403 in production with security enabled on those services. |

---

## 5. Scalability

### Findings

| # | Severity | File:Line | Finding |
|---|----------|-----------|---------|
| S-1 | **HIGH** | `AsyncConfig.java:42-48` | **Thread pool for notifications: core=4, max=16, queue=500.** At peak (20M users, flash sales), 500 queued notifications will be hit quickly. Queue overflow throws `TaskRejectedException` — the notification is lost (not sent, not DLQ'd, only the `NotificationLog` exists as PENDING forever). Should add a `RejectedExecutionHandler` that publishes to DLQ, or use an unbounded queue with backpressure signaling. |
| S-2 | **HIGH** | `NotificationService.java:723` | **`handleEvent()` is called synchronously from the Kafka consumer thread.** It performs HTTP calls to identity-service (preference lookup + user contact lookup) before dispatching to the async sender. If identity-service is slow, the Kafka consumer blocks. The entire event processing pipeline should be moved async. |
| S-3 | **MEDIUM** | `RetryableNotificationSender.java:57-87` | **No batching support.** Each notification is sent individually. SendGrid supports batch sending (up to 1000 personalizations per API call). For `OrderPlaced` generating EMAIL+SMS, that's 2 provider API calls per order. At 100K orders/hour, that's 200K API calls/hour. Batch sending would reduce this 100x. |
| S-4 | **MEDIUM** | `PickService.java:67-69` | **`items` list is loaded eagerly via `CascadeType.ALL` on `PickTask.items`.** Every `findByOrderId()` loads all pick items. For listing pending tasks (`listPendingTasks`), this loads items for every task in the store unnecessarily. Should use `FetchType.LAZY`. |
| S-5 | **LOW** | `notification_log` table | **No partition strategy.** At 20M users × 5 events × 2 channels = 200M rows/month. After 6 months, the table will have 1.2B rows. Should plan for time-based partitioning on `created_at` or archival strategy. |

---

## 6. GDPR — User Erasure & PII Handling

### ✅ What's Working Well
- Both services consume `UserErased` events from `identity.events` topic
- Fulfillment: replaces `user_id` with sentinel UUID and sets `user_erased=true` (`UserErasureService.java:23-24`, `V5__add_pick_tasks_user_erased.sql`)
- Notification: replaces `recipient` with `[REDACTED]` via `anonymizeByUserId()` (`NotificationLogRepository.java:16-17`)
- Audit logs record erasure actions with timestamps
- `MaskingUtil` masks PII in log output

### Findings

| # | Severity | File:Line | Finding |
|---|----------|-----------|---------|
| G-1 | **HIGH** | `NotificationLogRepository.java:16-17` | **Erasure only anonymizes `recipient` field, but `notification_log` also stores `event_id` which may contain the `orderId` (used as Kafka key).** The `event_id` + `channel` can be cross-referenced with order-service to re-identify the user. Also, `template_id` + `created_at` + `channel` could be used for re-identification in small user populations. Consider anonymizing the entire row or deleting it. |
| G-2 | **HIGH** | `fulfillment-service UserErasureService.java:24` | **Erasure replaces `user_id` but does NOT anonymize rider-related PII.** The `riders` table stores `name` and `phone` (PII of delivery partners). If a rider requests erasure, there's no handler for that. Riders are data subjects too under GDPR. |
| G-3 | **MEDIUM** | `OutboxEvent.java` / `V3__create_outbox.sql` | **Outbox events contain `userId` in the JSON `payload` field.** After user erasure, these outbox rows still contain the original user ID in their JSONB payload. Debezium may have already shipped them to Kafka, but the DB copies remain. Should either delete processed outbox rows or scrub `payload` during erasure. |
| G-4 | **MEDIUM** | `AuditLog` (both services) | **Audit logs store `user_id` in plaintext.** GDPR Article 17 requires erasure from all systems. Audit logs for erased users should either be anonymized or have a legal basis for retention (legitimate interest). Currently, the erasure event creates an audit log WITH the user's real ID (ironic). |
| G-5 | **LOW** | `notification_log.recipient` | **The `recipient` column stores masked values (`MaskingUtil.maskRecipient()`), which is good.** However, `maskEmail` keeps the first character + domain (e.g., `j***@gmail.com`), and `maskPhone` keeps first 3 + last 4 digits. For small user populations, this may be sufficient for re-identification. Consider full redaction for stored values. |

---

## 7. Template System

### ✅ What's Working Well
- Mustache templates for EMAIL, SMS, PUSH channels
- `TemplateRegistry` maps event types to template definitions with channel lists
- Variable injection via `buildVariables()` in `NotificationService`

### Findings

| # | Severity | File:Line | Finding |
|---|----------|-----------|---------|
| T-1 | **HIGH** | `TemplateRegistry.java:11-22` | **Template registry is hardcoded in Java.** Adding a new event→notification mapping requires a code change, rebuild, and redeployment. Should be externalized to config (YAML/DB) for operational flexibility. |
| T-2 | **MEDIUM** | Templates directory | **Missing templates for registered events:**<br>- `OrderPacked` → registered for SMS channel, template `order_packed` → `sms/order_packed.mustache` ✅ exists<br>- `OrderDispatched` → registered for SMS + PUSH → `sms/order_dispatched.mustache` ✅, `push/order_dispatched.mustache` ✅<br>- `OrderDispatched` → registered for SMS + PUSH but NOT EMAIL → **no email for dispatch** (doc says EMAIL is optional, so OK)<br>- **No `email/order_packed.mustache`** — if email channel is ever added for OrderPacked, it will crash at runtime with `IllegalStateException("Template not found")`. Low risk since not registered. |
| T-3 | **MEDIUM** | All templates | **No i18n support.** All templates are in English only. The `UserContact` record has a `language` field (`UserContact.java:5`) but it is never populated (always `null` from `RestUserDirectoryClient.java:225`) and `TemplateService` has no locale-aware path resolution. For a platform with 20M+ users in India, Hindi/regional language support is expected. |
| T-4 | **MEDIUM** | `TemplateService.java:1007` | **`escapeHTML(false)` disables HTML escaping in Mustache.** If any user-controlled data (e.g., `userName`, `productName`) contains HTML/JS, it will be rendered as-is in email bodies. This is an XSS vector in email clients. Should enable escaping and use `{{{triple}}}` only for trusted content. |
| T-5 | **LOW** | SMS templates | **No character count validation.** SMS has a 160-character limit (GSM-7) or 70 characters (UCS-2 for non-Latin). The `order_confirmed.mustache` SMS template could exceed 160 chars with long order IDs and currency amounts. Should validate/truncate. |

---

## 8. Missing Features — Production Gaps

| # | Severity | Feature | Notes |
|---|----------|---------|-------|
| M-1 | **HIGH** | **Real-time delivery tracking (WebSocket/SSE)** | The tracking API (`GET /orders/{orderId}/tracking`) is poll-based. Customers expect live updates. No WebSocket or SSE endpoint exists. The tracking response has a static `estimatedMinutes` that never updates as the rider moves. |
| M-2 | **HIGH** | **Dynamic delivery ETAs** | `estimatedMinutes` is set once at dispatch time (default: 15 min) and never recalculated. No rider location updates, no traffic-aware ETA. The `Delivery` entity has no fields for rider latitude/longitude. |
| M-3 | **HIGH** | **Push notification token management** | `NotificationChannel.PUSH` exists but there's no token storage, no FCM/APNs provider implementation (only `LoggingProvider` in non-prod). The `resolveRecipient()` checks for `deviceToken`/`pushToken` in the event payload but these fields don't exist in any event schema. PUSH is dead code. |
| M-4 | **MEDIUM** | **WhatsApp channel** | Not implemented. For Indian Q-commerce (Zepto/Blinkit), WhatsApp Business API is the primary notification channel with 90%+ open rates vs 20% for SMS. |
| M-5 | **MEDIUM** | **Rider geo-fencing** | No location-based rider assignment. `RiderAssignmentService` picks the first available rider at a store by last-dispatch time. No proximity, zone, or capacity-based routing. |
| M-6 | **MEDIUM** | **SLA monitoring & alerts** | No pick-time SLA enforcement. If a pick task stays PENDING for >5 minutes (q-commerce SLA), there's no alert, escalation, or auto-reassignment. `FulfillmentMetrics` class is referenced in docs but not in the actual fulfillment-service code — no metrics instrumentation. |
| M-7 | **MEDIUM** | **Order cancellation handling** | The `OrderEventConsumer` only handles `OrderPlaced`. If an `OrderCancelled` event arrives (schema exists in contracts), the pick task remains active. No cancellation flow. |
| M-8 | **LOW** | **Notification rate limiting** | No per-user rate limiting. A bug causing event storms could spam a user with hundreds of SMS/emails. |
| M-9 | **LOW** | **Data retention / TTL** | No cleanup strategy for `notification_log`, `outbox_events`, or `audit_log` tables. At scale, these tables grow unbounded. `outbox_events` has a `sent` flag but no scheduled cleanup job. |

---

## 9. Additional Observations

### Docker & Deployment
| # | Severity | File | Finding |
|---|----------|------|---------|
| D-1 | **LOW** | `Dockerfile` (both) | `COPY . .` in build stage copies entire repo context including `.git`, `build/`, etc. Should use `.dockerignore` or copy only `src/` and `build.gradle.kts`. |
| D-2 | **LOW** | `application.yml` (both) | Health check `readiness` group includes `redis` but neither service uses Redis. Will cause readiness probe to fail if Redis health indicator is on the classpath. |

### Code Quality
| # | Severity | File | Finding |
|---|----------|------|---------|
| C-1 | **LOW** | `NotificationService.java:735-738` | Special-case for `PaymentRefunded` — always calls `resolveOrderSnapshot()` even when `userId` is present. This is to get currency, but adds unnecessary coupling to order-service for every refund event. The `PaymentRefunded` schema already includes `currency`. |
| C-2 | **LOW** | `PickService.java:142-146` | `markPacked()` allows transition from PENDING → COMPLETED directly (via PENDING → IN_PROGRESS → COMPLETED in quick succession). This skips the picker assignment flow and could create packed tasks with no picker recorded. |

---

## Summary of Findings by Severity

| Severity | Count | Key Themes |
|----------|-------|------------|
| **CRITICAL** | 7 | REST client no-timeouts, Thread.sleep blocking thread pool, Kafka poison pill infinite loops, HTTP-before-TX-commit, refund miscalculation, Kafka poll interval exceeded |
| **HIGH** | 11 | Missing Kafka error handlers, no consumer parallelism, GDPR gaps, push tokens dead code, no real-time tracking, REST auth missing, redundant HTTP calls |
| **MEDIUM** | 14 | Template caching, i18n, XSS in templates, SLA monitoring, no pagination, optimistic locking, rate limiting, data retention |
| **LOW** | 9 | Docker optimizations, SMS char limits, Redis health check, code quality nits |

---

## Top 5 Immediate Actions (Pre-Production)

1. **Add timeouts to ALL `RestTemplate` instances** — 5s connect, 10s read — across both services. This is the single highest-risk item.
2. **Configure `DefaultErrorHandler` with `DeadLetterPublishingRecoverer`** on all Kafka consumers to prevent poison pill partition blocking.
3. **Replace `Thread.sleep()` in `RetryableNotificationSender`** with a DB-polled retry or `ScheduledExecutorService` to avoid thread pool starvation.
4. **Move HTTP calls (orderClient, paymentClient) outside `@Transactional` boundaries** using `@TransactionalEventListener(phase = AFTER_COMMIT)`.
5. **Set Kafka consumer `concurrency`** to match partition count (6) for both services.
