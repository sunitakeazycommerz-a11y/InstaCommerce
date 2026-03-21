# Payment Service - Comprehensive Documentation

## Overview
**Ownership**: Platform Team - Money Path Tier 1
**Language**: Java 21 / Spring Boot 4.0
**Default Port**: 8080
**Status**: Tier 1 Critical
**SLO**: 99.95% availability, P99 < 1.5s

---

## Endpoints
- `POST /payments/authorize` - Authorize with payment gateway
- `POST /payments/{id}/capture` - Capture authorized payment
- `POST /payments/{id}/void` - Void authorization
- `GET /payments/{id}` (admin only) - Retrieve payment details

---

## Database Schema
**Name**: `payments` | **Migrations**: Flyway V1-V10

### Core Tables
```sql
-- Payments
id (UUID), order_id, user_id, amount_cents, currency, status (AUTHORIZED, CAPTURED, VOIDED, REFUNDED, FAILED)
idempotency_key (UNIQUE), created_at, updated_at, version (optimistic lock)

-- Ledger (debit/credit)
id, payment_id, amount_cents, operation (AUTHORIZE, CAPTURE, VOID, REFUND), timestamp

-- Outbox
Same CDC pattern as order-service
```

---

## Kafka Events
**Topic**: `payments.events`

**Events Published**:
- PaymentAuthorized - Authorization successful
- PaymentCaptured - Funds captured
- PaymentVoided - Authorization voided
- PaymentRefunded - Refund processed
- PaymentFailed - Payment declined/error

---

## Resilience Configuration
```yaml
resilience4j:
  circuitbreaker:
    instances:
      paymentGateway:
        failureRateThreshold: 50%
        waitDurationInOpenState: 30s
        slidingWindowSize: 20
  retry:
    instances:
      paymentGateway:
        maxAttempts: 3
        waitDuration: 1s
  timelimiter:
    instances:
      paymentGateway:
        timeoutDuration: 5s
```

---

## Key Features
- **Stripe Integration**: Payment processing via Stripe API
- **Idempotency**: Captured in idempotency_key for duplicate detection
- **Ledger Integrity**: Debit/credit validation ensures balance
- **Stale Payment Recovery**: Background job recovers stuck AUTHORIZED payments
- **Webhook Handling**: Incoming payment status updates from Stripe
- **Optimistic Locking**: Version field prevents concurrent updates

---

## Recovery Jobs
- `StakePendingRecovery` - Captures stuck AUTHORIZED payments (5-min cron)
- `StalePendingRefundRecovery` - Processes stuck REFUNDING payments (10-min cron)
- `LedgerIntegrityVerification` - Validates debit/credit balance (15-min cron)

---

## Deployment
- Port: 8080
- Replicas: 3
- CPU: 500m request / 1500m limit
- Memory: 512Mi request / 1Gi limit
- Namespace: money-path

---

## Health & Monitoring
- Liveness: `/actuator/health/live`
- Readiness: `/actuator/health/ready` (checks DB + Stripe connectivity)
- Metrics: `/actuator/prometheus`
- Circuit Breaker State: Exported via Prometheus

---

## Documentation Files
- [High-Level Design](diagrams/hld.md)
- [Low-Level Architecture](diagrams/lld.md)
- [Database ERD](diagrams/erd.md)
- [Request/Response Flows](diagrams/flowchart.md)
- [Sequence Diagrams](diagrams/sequence.md)
- [REST API Contract](implementation/api.md)
- [Kafka Events](implementation/events.md)
- [Database Details](implementation/database.md)
- [Resilience & Retries](implementation/resilience.md)
- [Deployment Runbook](runbook.md)
