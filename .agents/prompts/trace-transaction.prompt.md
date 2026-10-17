---
name: trace-transaction
description: Trace a distributed transaction or business workflow across microservice boundaries in InstaCommerce.
---

# Distributed Transaction Tracing Prompt

Trace the end-to-end execution flow of transaction `$TRANSACTION_NAME` (e.g. Checkout Saga, Payment Capture on Delivery, Order Cancellation).

## Trace Structure:
1. **Initiation & Ingress:**
   - Entrypoint API call or event trigger.
2. **Orchestration / Saga Flow:**
   - Temporal workflow state progression and activity execution steps.
   - For each activity: downstream service called, idempotency key strategy, and timeout/retry policy.
3. **Database Mutations & Outbox Rows:**
   - Record-level SQL operations and transactional outbox entries per service.
4. **Failure Modes & Compensations:**
   - If Step N fails, trace the exact LIFO rollback compensations.
   - Smart payment handling (void uncaptured authorizations vs. refund captured settled payments).
5. **Observability:**
   - Trace IDs (`traceparent`), Kafka offsets, and alert thresholds.
