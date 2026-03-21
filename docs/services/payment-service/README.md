# Payment Service

## Overview

The payment-service is the critical financial authority in InstaCommerce, responsible for authorizing payments, capturing funds, processing refunds, and maintaining an immutable ledger of all financial transactions. This is the highest-criticality Tier 1 service; any payment failure impacts revenue directly and violates PCI DSS compliance requirements.

**Service Ownership**: Platform Team - Money Path Tier 1 (Finance)
**Language**: Java 21 / Spring Boot 4.0
**Default Port**: 8080
**Status**: Tier 1 Critical (payment processing)
**Database**: PostgreSQL 15+ (financial ledger, PCI DSS compliant)

## SLOs & Availability

- **Availability SLO**: 99.95% (45 minutes downtime/month - zero tolerance for revenue loss)
- **P99 Latency**:
  - Authorization: < 300ms (POST /payments/authorize)
  - Capture: < 500ms (POST /payments/{id}/capture)
  - Refund: < 800ms (POST /payments/{id}/refund)
- **Error Rate**: < 0.05% (transaction failures; validation errors <0.2%)
- **Webhook Delivery**: 99.99% (payment confirmations never lost)
- **PCI DSS Compliance**: 100% (audit trail complete, encryption verified quarterly)
- **Max Throughput**: 2,000 transactions/minute (each ~500 tx/min per replica at 4 replicas)

## Key Responsibilities

1. **Payment Authorization**: Authorize payments with external payment gateway (Stripe) with circuit breaker protection
2. **Fund Capture**: Capture authorized funds after order confirmation (capture-on-demand)
3. **Payment Refunds**: Process full and partial refunds on demand or automatically on order cancellation
4. **Ledger Integrity**: Maintain double-entry ledger (debit/credit) with balance validation
5. **Idempotency**: Prevent double-charging via idempotency keys; tolerate network retries
6. **Webhook Processing**: Receive payment status updates from Stripe; reconcile with local state
7. **Recovery Jobs**: Background jobs recover stuck AUTHORIZED and REFUNDING payments
8. **Audit Trail**: Immutable record of all financial transactions for compliance and forensics
9. **Encryption**: Sensitive card data never stored; tokenized via Stripe

## Deployment

### GKE Deployment
- **Namespace**: money-path
- **Replicas**: 4 (HA, active-active across zones; higher than other services due to criticality)
- **CPU Request/Limit**: 750m / 2000m (more resources for latency sensitivity)
- **Memory Request/Limit**: 768Mi / 1.5Gi
- **Startup Probe**: 20s initial delay, 3 failure threshold (DB schema validation)
- **Readiness Probe**: `/actuator/health/ready` (checks DB + Stripe API connectivity)

### Database
- **Name**: `payments` (PostgreSQL 15+)
- **Migrations**: Flyway (V1-V10) - auto-applied on startup
- **Connection Pool**: HikariCP 40 connections (highest of all services; financial workload)
- **Replication**: Synchronous streaming replication to standby (RTO <1s)
- **Backups**: Continuous, immutable (Glacier archival after 30 days)

### Network
- **Service Account**: `payment-service@project.iam.gserviceaccount.com` (GCP)
- **Ingress**: Through api-gateway (TLS 1.3, SANs validated)
- **Egress**: Stripe API (HTTPS, rate-limited to 1000 req/s per API key)
- **NetworkPolicy**: Deny-default; allow from checkout-orchestrator, fulfillment-service, admin-gateway, payment-webhook-service

## Architecture

### System Context

```
┌─────────────────────────────────────────────────────────┐
│           Payment Service (Financial Authority)          │
│     (PCI DSS Compliant, Zero-Tolerance for Loss)        │
└────────┬────────────────────────┬────────────────────┘
         │                        │
    ┌────▼────┐          ┌────────▼────────┐
    │ Upstream │          │   Downstream    │
    │ Clients  │          │   Dependents    │
    └────┬────┘          └────────┬────────┘
         │                        │
   • checkout-             • order-service
     orchestrator          • fulfillment-service
   • admin-gateway         • notification-service
   • payment-webhook       • reconciliation-engine
     (Stripe)              • audit-trail-service
                           • analytics platform
```

### Payment Lifecycle State Machine

```
                    ┌─────────────┐
                    │   CREATED   │ (initial, transient)
                    └──────┬──────┘
                           │ authorize()
                    ┌──────▼──────┐
                    │ AUTHORIZED  │ (fund reserved)
                    └──────┬──────┘
                 ┌─────────┼─────────┐
                 │                   │
            capture()            void()
                 │                   │
          ┌──────▼──────┐     ┌──────▼──────┐
          │  CAPTURED   │     │   VOIDED    │
          └──────┬──────┘     └─────────────┘ (end state)
                 │
                 │ refund()
          ┌──────▼──────────┐
          │  REFUNDING ──┐  │ (intermediate)
          └──────┬──────┐ │  │
                 │      └─┼──► REFUNDED (end state)
                 │         │
                 └─────────► REFUND_FAILED (retry-able)
```

### Data Flow (Authorization → Capture → Settlement)

```
1. checkout-orchestrator
        ↓ Authorization Request (cart total, user token)
2. payment-service → Stripe API (authorize funds)
        ↓ Stripe Response (charge_id, status: succeeded)
3. Payments DB ← AUTHORIZED status + ledger entry
        ↓ Event published
4. notification-service ← PaymentAuthorized
        ↓
5. order-service ← Orders created (PENDING → CONFIRMED)
        ↓
6. fulfillment-service ← Start picking
        ↓ [Later, after items packed]
7. fulfillment-service → capture() request
        ↓
8. payment-service → Stripe API (capture charge)
        ↓ Stripe confirms (funds deducted from user account)
9. Payments DB ← CAPTURED status + ledger entry
        ↓ Event published
10. accounting system ← Daily settlement batch
```

## Integrations

### Synchronous Calls (with Strict Circuit Breakers)
| Service | Endpoint | Timeout | Purpose | Retry | CB Threshold |
|---------|----------|---------|---------|-------|-------------|
| Stripe API | https://api.stripe.com/v1/charges | 5s | Authorize, Capture, Refund | 3 retries (500ms backoff) | 50% fail rate, 30s open |
| order-service | http://order-service:8085/orders/{id} | 3s | Verify order for capture | None (async) | N/A |
| fulfillment-service | http://fulfillment-service:8080/fulfillment/{id} | 3s | Query fulfillment status | None (async) | N/A |

### Asynchronous Event Channels
| Topic | Direction | Events | Format | Guarantee |
|-------|-----------|--------|--------|-----------|
| payments.events | Publish | PaymentAuthorized, PaymentCaptured, PaymentRefunded, PaymentFailed | Kafka (via outbox) | Exactly-once |
| orders.events | Consume | OrderCreated, OrderCancelled | Kafka | At-least-once |

### Webhook Integration (Incoming from Stripe)
- **Endpoint**: POST /payments/webhooks/stripe (unauthenticated, IP whitelisted)
- **Events**: charge.captured, charge.dispute.created, charge.refunded
- **Retry**: Stripe retries up to 5 days; idempotent via event_id deduplication
- **Signature Verification**: HMAC SHA256 with API secret key

## Data Model

### Core Entities

```
Payments Table (PCI DSS):
├─ id (UUID, PK)
├─ user_id (UUID, FK → identity-service)
├─ order_id (UUID, FK → order-service, indexed)
├─ stripe_charge_id (VARCHAR, UNIQUE - external reference)
├─ idempotency_key (VARCHAR, UNIQUE - client idempotency)
├─ amount_cents (INT, NOT NULL)
├─ currency (VARCHAR: "USD", check constraint)
├─ status (ENUM: CREATED, AUTHORIZED, CAPTURED, VOIDED, REFUNDING, REFUNDED, FAILED)
├─ error_code (VARCHAR, nullable - reason for FAILED)
├─ error_message (TEXT, encrypted - sensitive error details)
├─ card_token (VARCHAR - Stripe tokenized, never raw card data)
├─ billing_address_encrypted (TEXT, KMS-encrypted)
├─ metadata (JSONB - custom fields, e.g., promo code)
├─ created_at (TIMESTAMP, indexed)
├─ updated_at (TIMESTAMP)
├─ version (INT - optimistic lock for concurrent updates)
└─ (Audit fields: created_by, updated_by - always "system")

Ledger Entries Table (Double-Entry Accounting):
├─ id (BIGSERIAL, PK)
├─ payment_id (UUID, FK → Payments)
├─ operation (ENUM: AUTHORIZE, CAPTURE, VOID, REFUND, ADJUSTMENT)
├─ amount_cents (INT)
├─ debit_account (VARCHAR: "customer", "merchant", "platform")
├─ credit_account (VARCHAR: "customer", "merchant", "platform")
├─ transaction_id (VARCHAR, UNIQUE - external txn ref)
├─ timestamp (TIMESTAMP, indexed)
└─ description (TEXT)

Outbox Events Table (CDC):
├─ id (BIGSERIAL)
├─ payment_id (UUID, FK)
├─ event_type (VARCHAR: PaymentAuthorized, PaymentCaptured, etc.)
├─ event_payload (JSONB - event data)
├─ created_at (TIMESTAMP)
└─ sent (BOOLEAN - polled by CDC relay)

Reconciliation Ledger Table (for Wave 36 reconciliation-engine):
├─ id (UUID, PK)
├─ payment_id (UUID, FK)
├─ external_ledger_id (VARCHAR - from accounting system)
├─ payment_amount_cents (INT)
├─ ledger_amount_cents (INT)
├─ variance_cents (INT - 0 = reconciled)
├─ reconciliation_status (ENUM: PENDING, MATCHED, UNMATCHED, REVERSED)
├─ reviewed_at (TIMESTAMP)
└─ reviewed_by (VARCHAR - admin user)
```

### Relationships

```
Users (identity-service)
    ↓ (1:many)
Payments
    ├─ (1:many) ← Ledger Entries (each payment may have multiple operations: auth, capture, void)
    ├─ (1:1) → Orders (optional; some payments may not have orders, e.g., wallet top-ups)
    └─ (1:many) ← Outbox Events (events published)

Payments
    ↓ (1:1, joined later)
Reconciliation Ledger (Wave 36)
```

## API Documentation

### Authorize Payment
**POST /payments/authorize**
```bash
Authorization: Bearer {JWT_TOKEN}
Idempotency-Key: {UUID}  # Client-provided for idempotency

Request:
{
  "orderId": "order-uuid",
  "userId": "user-uuid",
  "amount": {
    "value": 3500,
    "currency": "USD"
  },
  "billingAddress": "123 Main St, City, State 12345",
  "cardToken": "tok_visa_1234567890abcdef",  # From Stripe.js frontend
  "metadata": {
    "promoCode": "SAVE20",
    "source": "mobile"
  }
}

Response (201 Created):
{
  "id": "payment-uuid",
  "stripeChargeId": "ch_1234567890abcdef",
  "status": "AUTHORIZED",
  "amount": {
    "value": 3500,
    "currency": "USD"
  },
  "authorizedAt": "2025-03-21T10:00:00Z",
  "expiresAt": "2025-03-22T10:00:00Z"  # 24h expiry for captures
}

Response (409 Conflict - Idempotent duplicate):
{
  "error": "Payment already authorized with this key",
  "paymentId": "payment-uuid",
  "idempotencyKey": "..."
}

Response (422 Unprocessable Entity):
{
  "error": "Card declined",
  "stripeErrorCode": "card_declined",
  "statusCode": "card_declined",
  "message": "Your card was declined"
}
```

### Capture Payment
**POST /payments/{paymentId}/capture**
```bash
Authorization: Bearer {JWT_TOKEN}

Request:
{
  "amount": 3500,  # Optional; if omitted, captures full authorized amount
  "currency": "USD"
}

Response (200):
{
  "id": "payment-uuid",
  "status": "CAPTURED",
  "capturedAmount": 3500,
  "capturedAt": "2025-03-21T10:15:00Z"
}

Response (410 Gone):
{
  "error": "Authorization expired; must re-authorize",
  "paymentId": "payment-uuid",
  "authorizedAt": "2025-03-21T10:00:00Z",
  "expiryAt": "2025-03-22T10:00:00Z"
}
```

### Refund Payment
**POST /payments/{paymentId}/refund**
```bash
Authorization: Bearer {JWT_TOKEN}

Request:
{
  "amount": 1750,  # Partial refund (50%)
  "reason": "CUSTOMER_REQUEST",  # or DUPLICATE, FRAUD, etc.
  "notes": "Customer requested partial refund"
}

Response (201):
{
  "id": "payment-uuid",
  "status": "REFUNDING",
  "refundAmount": 1750,
  "refundStartedAt": "2025-03-21T11:00:00Z"
}

Response (200 - eventual):
{
  "id": "payment-uuid",
  "status": "REFUNDED",
  "refundAmount": 1750,
  "refundCompletedAt": "2025-03-21T11:05:00Z"
}
```

### Get Payment Status
**GET /payments/{paymentId}**
```bash
Authorization: Bearer {JWT_TOKEN}

Response (200):
{
  "id": "payment-uuid",
  "orderId": "order-uuid",
  "status": "CAPTURED",
  "amount": {
    "value": 3500,
    "currency": "USD"
  },
  "ledgerEntries": [
    {
      "operation": "AUTHORIZE",
      "amount": 3500,
      "timestamp": "2025-03-21T10:00:00Z"
    },
    {
      "operation": "CAPTURE",
      "amount": 3500,
      "timestamp": "2025-03-21T10:15:00Z"
    }
  ],
  "createdAt": "2025-03-21T10:00:00Z",
  "updatedAt": "2025-03-21T10:15:00Z"
}
```

## Error Handling & Resilience

### Circuit Breaker Configuration
```yaml
resilience4j:
  circuitbreaker:
    instances:
      stripePaymentGateway:
        registerHealthIndicator: true
        failureRateThreshold: 50%  # Open CB after 50% failures
        slowCallRateThreshold: 25%  # Open CB if 25% calls are slow
        slowCallDurationThreshold: 5s
        waitDurationInOpenState: 60s  # Wait 60s before half-open
        permittedNumberOfCallsInHalfOpenState: 3  # Try 3 calls in half-open
        automaticTransitionFromOpenToHalfOpenEnabled: true
        recordExceptions:
          - java.net.SocketTimeoutException
          - com.stripe.exception.StripeException
  retry:
    instances:
      stripePaymentGateway:
        maxAttempts: 3
        waitDuration: 500ms
        retryExceptions:
          - java.net.SocketTimeoutException
          - com.stripe.exception.RateLimitException  # 429 Too Many Requests
  timelimiter:
    instances:
      stripePaymentGateway:
        timeoutDuration: 5s
        cancelRunningFuture: true
```

### Failure Scenarios & Recovery

**Scenario 1: Stripe API Timeout (5s)**
- Retry 3 times with exponential backoff (500ms initial)
- If all retries fail, circuit breaker opens
- Client receives 503 Service Unavailable
- Recovery: Automatic circuit half-open after 60s; retry single request
- Fallback: Webhook from Stripe will eventually confirm payment status

**Scenario 2: Idempotency Key Collision**
- Client provides same Idempotency-Key in retry
- Server returns cached response (201, same payment_id)
- No duplicate charge at Stripe or DB level
- Recovery: Client uses cached response; transaction succeeds idempotently

**Scenario 3: Authorization Expires (24h)**
- Payment expires if not captured within 24 hours
- Attempt to capture returns 410 Gone
- Recovery: Must re-authorize with new authorization request

**Scenario 4: Ledger Balance Mismatch**
- Nightly reconciliation job (2 AM UTC) detects debit ≠ credit
- Raises alert SEV-2; escalates to finance team
- Recovery: Manual reconciliation; potential data loss investigation

**Scenario 5: Webhook Lost (Stripe Payment Confirmed, Webhook Failed)**
- Stripe retries webhook delivery for 5 days
- Payment-service has background job that reconciles via Stripe API
- Recovery: Job polls Stripe API every 5 minutes for confirmed charges
- Eventual consistency: Payment status corrected within 5 minutes

### Idempotency Implementation
- **Key**: Client-provided Idempotency-Key (UUID, unique per user per 24h)
- **Storage**: Indexed column on payments table with constraint
- **Response Caching**: 24-hour TTL; second request returns cached response
- **Guarantee**: No double-charges at Stripe (uses same idempotency key via API)

## Configuration

### Environment Variables
```env
# Server
SERVER_PORT=8080
SPRING_PROFILES_ACTIVE=gcp

# Database (PCI DSS)
SPRING_DATASOURCE_URL=jdbc:postgresql://cloudsql-proxy:5432/payments
SPRING_DATASOURCE_USERNAME=${DB_USER}
SPRING_DATASOURCE_PASSWORD=${DB_PASSWORD}
SPRING_DATASOURCE_HIKARI_POOL_SIZE=40  # Highest of all services
SPRING_DATASOURCE_HIKARI_IDLE_TIMEOUT_MINUTES=5

# JWT
JWT_ISSUER=instacommerce-identity
JWT_PUBLIC_KEY=${JWT_PUBLIC_KEY_PEM}

# Stripe Configuration
STRIPE_API_KEY=${STRIPE_SECRET_KEY}  # From Google Secret Manager
STRIPE_WEBHOOK_SECRET=${STRIPE_WEBHOOK_SECRET}
STRIPE_API_REQUEST_TIMEOUT_MS=5000

# Encryption (KMS)
GOOGLE_CLOUD_PROJECT_ID=instacommerce-prod
GCP_KMS_KEY_NAME=projects/instacommerce-prod/locations/us-central1/keyRings/payments/cryptoKeys/billing-data

# Kafka
SPRING_KAFKA_BOOTSTRAP_SERVERS=kafka-broker-1:9092,kafka-broker-2:9092
SPRING_KAFKA_PRODUCER_COMPRESSION_TYPE=snappy

# Tracing
OTEL_EXPORTER_OTLP_TRACES_ENDPOINT=http://otel-collector.monitoring:4318/v1/traces
OTEL_TRACES_SAMPLER=always_on  # 100% sampling for financial data
```

### application.yml
```yaml
server:
  port: 8080
  compression:
    enabled: true
    min-response-size: 1024

spring:
  jpa:
    hibernate:
      ddl-auto: none
    show-sql: false
    properties:
      hibernate:
        dialect: org.hibernate.dialect.PostgreSQLDialect
        jdbc:
          batch_size: 20
          batch_versioned_data: true
  cache:
    type: caffeine
    caffeine:
      spec: maximumSize=10000,expireAfterWrite=10m

payment-service:
  stripe:
    authorization-ttl-hours: 24
    max-retry-attempts: 3
    rate-limit: 1000  # req/s
  ledger:
    reconciliation-enabled: true
    reconciliation-cron: "0 2 * * *"  # 2 AM UTC daily
  encryption:
    algorithm: "AES-256-GCM"
    kms-key-rotation: "monthly"
```

## Monitoring & Observability

### Key Metrics

| Metric | Type | Alert Threshold |
|--------|------|-----------------|
| `payment_authorization_latency_ms` | Histogram (p50, p95, p99) | p99 > 300ms |
| `payment_capture_latency_ms` | Histogram | p99 > 500ms |
| `payment_authorization_total` | Counter (by status) | N/A |
| `payment_capture_total` | Counter (by status) | N/A |
| `payment_authorization_errors_total` | Counter (by error_code) | rate > 5/min |
| `stripe_api_latency_ms` | Histogram | p99 > 3000ms |
| `stripe_api_errors_total` | Counter (by error_code) | rate > 50/min = alert |
| `circuit_breaker_stripe_state` | Gauge (0=closed, 1=open, 2=half-open) | State = open → SEV-1 |
| `ledger_balance_variance_cents` | Gauge | abs(variance) > 0 → SEV-2 |
| `idempotency_cache_hit_ratio` | Gauge | < 50% = investigate |
| `payment_webhook_lag_seconds` | Gauge | > 30s → investigate |

### Alert Rules

```yaml
alerts:
  - name: PaymentServiceHighLatency
    condition: payment_authorization_latency_ms{quantile="0.99"} > 300
    duration: 5m
    severity: SEV-1
    action: Page on-call engineer (financial impact)

  - name: StripeCircuitBreakerOpen
    condition: circuit_breaker_stripe_state == 1
    duration: 1m
    severity: SEV-1
    action: Page on-call immediately; investigate Stripe connectivity

  - name: PaymentAuthorizationErrorRate
    condition: rate(payment_authorization_errors_total[1m]) > 0.05
    duration: 5m
    severity: SEV-2
    action: Alert to Slack #payments channel

  - name: LedgerBalanceMismatch
    condition: abs(ledger_balance_variance_cents) > 0
    duration: 60m
    severity: SEV-2
    action: Escalate to finance team for investigation

  - name: WebhookDeliveryLag
    condition: payment_webhook_lag_seconds > 30
    duration: 10m
    severity: SEV-2
    action: Check Stripe webhook delivery status
```

### Logging (Audit Trail)

- **INFO**: Payment authorizations, captures, refunds (full audit trail for compliance)
- **WARN**: Idempotency conflicts, authorization expiry approaching, CB state changes
- **ERROR**: Stripe API errors, ledger mismatches, unhandled exceptions (investigation required)
- **DEBUG**: Stripe request/response details, encryption/decryption (disabled in prod)

### Tracing

- **Sampler**: Always-on (1.0) for all payment operations (regulatory requirement)
- **Spans**: Stripe auth, capture, DB transaction, ledger verification, Kafka publish
- **Correlation ID**: Propagated from checkout through all services
- **Retention**: 1 year minimum (regulatory requirement, then archived to Glacier)

## Security Considerations

### Authentication & Authorization
- **Auth**: JWT RS256 validation via identity-service JWKS
- **User Isolation**: `user_id` from JWT must match payment.user_id (prevent cross-user access)
- **PCI DSS**: No raw card data stored; Stripe tokenization used exclusively
- **Stripe Webhook**: IP whitelisted + HMAC signature verification (prevent spoofing)

### Data Protection
- **Encryption at Rest**: AES-256-GCM via GCP Cloud KMS (quarterly key rotation)
- **Encryption in Transit**: TLS 1.3 for all network communications (Stripe, Kafka, DB)
- **Audit Logging**: All financial operations logged immutably to audit-trail-service
- **Access Control**: Only payment-service can write to payments table (via DB roles)

### PCI DSS Compliance
- **Scope**: Payment-service is in PCI scope (handles tokenized card data)
- **Quarterly Scans**: External vulnerability scans required
- **Annual Audit**: Independent auditor verifies controls
- **Incident Response**: Mandatory reporting to Visa/Mastercard within 72 hours

### Known Security Risks
1. **Stripe API Key Compromise**: If API key stolen, attacker can authorize/capture payments → Mitigated by rotating key quarterly, storing in Secret Manager
2. **Webhook Spoofing**: Attacker sends fake webhook confirming payment → Mitigated by HMAC signature verification
3. **Idempotency Key Collision**: Attacker reuses key to trigger duplicate charge → Mitigated by strong uniqueness constraint + Stripe idempotency
4. **Database Access**: If DB compromised, attacker sees encrypted billing data + encrypted error messages → Mitigated by encryption, role-based access control

## Troubleshooting (15+ Scenarios)

### Scenario 1: Payment Authorization Returns 503

**Indicators**:
- `payment_authorization_latency_ms` p99 > 300ms
- Stripe circuit breaker state = OPEN
- Checkout success rate < 99%

**Root Causes**:
1. Stripe API unreachable (timeout)
2. Circuit breaker open (50% failure rate)
3. Rate limit exceeded (Stripe 429)
4. Network connectivity issue

**Resolution**:
```bash
# Check circuit breaker state
curl http://localhost:8080/actuator/health/circuitbreakers | jq '..'

# Test Stripe connectivity
curl -H "Authorization: Bearer $STRIPE_KEY" https://api.stripe.com/v1/account

# Check payment service logs for errors
kubectl logs -n money-path deploy/payment-service | grep -i "stripe\|circuit\|timeout"

# If circuit open: Wait 60s for auto half-open or manually reset
curl -X POST http://payment-service:8080/admin/circuit-breaker/reset/stripe

# If rate limited: Check Stripe rate limit status
curl -H "Authorization: Bearer $STRIPE_KEY" https://api.stripe.com/v1/rate_limit

# Escalate to Stripe support if API unreachable
```

### Scenario 2: Payment Captured but Order Not Updated

**Indicators**:
- Order still in PENDING after payment captured
- `outbox_entry_age_seconds` > 60
- Customer complaint: "Order not confirmed"

**Root Causes**:
1. PaymentCaptured event not published (Kafka producer failure)
2. CDC relay failed to relay event
3. order-service not consuming payment events
4. Event delivery guaranteed but consumer crashed

**Resolution**:
```bash
# Check outbox table for pending events
kubectl exec -n money-path pod/payment-service-0 -- \
  psql -U postgres -d payments -c "SELECT COUNT(*) FROM outbox_events WHERE sent=false"

# Check Kafka topic
kafka-console-consumer --bootstrap-server kafka:9092 --topic payments.events --max-messages 10

# Check order-service consumer lag
kafka-consumer-groups --bootstrap-server kafka:9092 \
  --group order-service-payment-consumer --describe

# Manually trigger order status update
curl -X POST http://order-service:8085/admin/orders/{order_id}/confirm-payment \
  -H "Authorization: Bearer $ADMIN_TOKEN"

# Check CDC relay health
curl http://cdc-relay:8091/actuator/health
```

### Scenario 3: Ledger Balance Mismatch Detected

**Indicators**:
- `ledger_balance_variance_cents` > 0
- Reconciliation engine alerts: "Financial mismatch"
- p99 latency spike during captures

**Root Causes**:
1. Partial refund recorded incorrectly
2. Stripe API response lost (timeout during capture)
3. Database corruption or concurrent update conflict
4. Double capture (race condition)

**Resolution**:
```bash
# Query ledger balance mismatches
SELECT payment_id, SUM(amount_cents) as balance
FROM ledger_entries
GROUP BY payment_id
HAVING SUM(amount_cents) != 0;

# Check Stripe against local state
stripe charges get $STRIPE_CHARGE_ID

# For partial refunds: Verify amounts
SELECT payment_id, SUM(amount_cents) as total
FROM ledger_entries
WHERE operation IN ('AUTHORIZE', 'CAPTURE', 'REFUND')
GROUP BY payment_id
HAVING SUM(amount_cents) != 0;

# Finance team manual review
# Manual adjustment entry if needed
INSERT INTO ledger_entries (payment_id, operation, amount_cents, ...)
VALUES ('payment-uuid', 'ADJUSTMENT', 5000, '...');

# Escalate to SRE for data corruption investigation
```

### Scenario 4: Idempotency Key Collision (Duplicate Authorization)

**Indicators**:
- Same Idempotency-Key producing different payment IDs
- `idempotency_cache_hit_ratio` < 50%
- Duplicate charges on customer statements

**Root Causes**:
1. Idempotency key constraint not enforced in DB
2. Cache miss (Redis down)
3. TTL expired (24h window)
4. Race condition in cache + DB

**Resolution**:
```bash
# Verify unique constraint exists
SELECT constraint_name FROM information_schema.table_constraints
WHERE table_name = 'payments' AND constraint_type = 'UNIQUE'
AND constraint_name LIKE '%idempotency%';

# If missing: Add constraint
ALTER TABLE payments ADD CONSTRAINT uk_idempotency_key
  UNIQUE (idempotency_key, user_id);

# Check for duplicates
SELECT idempotency_key, COUNT(*) as count
FROM payments
WHERE idempotency_key IS NOT NULL
GROUP BY idempotency_key
HAVING COUNT(*) > 1;

# Manual deduplication (keep first, delete duplicates)
DELETE FROM payments WHERE order_id IN (
  SELECT order_id FROM payments
  WHERE (idempotency_key, created_at) IN (
    SELECT idempotency_key, MAX(created_at)
    FROM payments
    WHERE idempotency_key IS NOT NULL
    GROUP BY idempotency_key
    HAVING COUNT(*) > 1
  )
);
```

### Scenario 5: Authorization Expires (24h Expiry)

**Indicators**:
- Capture API returns 410 Gone
- Authorization older than 24 hours in DB
- Customer complaint: "Payment expired"

**Root Causes**:
1. Order processing delayed > 24 hours
2. Fulfillment backlog (items slow to pick)
3. Customer didn't confirm order within window
4. Stripe stripe-initiated expiry

**Resolution**:
```bash
# Identify expired authorizations
SELECT payment_id, status, created_at, NOW() - created_at as age
FROM payments
WHERE status = 'AUTHORIZED'
AND created_at < NOW() - INTERVAL '24 hours';

# Re-authorize expired payment
curl -X POST http://payment-service:8080/payments/authorize \
  -H "Content-Type: application/json" \
  -H "Idempotency-Key: $(uuidgen)" \
  -d '{
    "orderId": "order-uuid",
    "amount": {"value": 3500, "currency": "USD"},
    "cardToken": "tok_visa_..."
  }'

# Notify customer of re-authorization if needed
# Escalate to ops: Why processing took > 24h?

# Prevention: Alert when authorization age > 18h
```

### Scenario 6: Stripe Webhook Delivery Failure

**Indicators**:
- Payment status updated in Stripe but not in local DB
- Manual reconciliation finds payments without capture events
- `payment_webhook_lag_seconds` > 30s

**Root Causes**:
1. Webhook endpoint unreachable or slow
2. Webhook signature verification failed (key rotation issue)
3. Webhook retry exhausted (max 5 days by Stripe)
4. Database transaction rolled back after webhook received

**Resolution**:
```bash
# Check webhook endpoint health
curl -X GET http://payment-service:8080/payments/webhooks/health

# Verify webhook signature verification is working
# Check logs for "Webhook signature verification failed"
kubectl logs -n money-path deploy/payment-service | grep -i webhook

# Manual webhook re-processing (if webhook lost)
curl -X POST http://payment-service:8080/admin/webhooks/replay \
  -d '{"stripe_event_id": "evt_1234567890"}'

# Check Stripe webhook delivery status
stripe events list | head -20

# If webhook endpoint URL wrong: Update in Stripe dashboard
# https://dashboard.stripe.com/account/webhooks
```

### Scenario 7: Payment Webhook Queue Buildup (Lag > 30s)

**Indicators**:
- Kafka consumer lag on payment-webhooks topic high
- Webhooks processing delayed
- Dashboard shows "old" payment statuses

**Root Causes**:
1. Payment-service webhook handler slow
2. Database contention during webhook processing
3. Kafka consumer crashed or stuck
4. High volume of webhooks from Stripe

**Resolution**:
```bash
# Check Kafka consumer lag
kafka-consumer-groups --bootstrap-server kafka:9092 \
  --group payment-service-webhooks --describe

# Monitor webhook queue depth
curl http://payment-service:8080/actuator/metrics/webhook_queue_depth

# Check webhook handler latency
curl http://payment-service:8080/actuator/metrics/webhook_processing_latency_ms

# Scale payment-service replicas
kubectl scale deployment payment-service --replicas=6

# Temporarily reduce webhook processing batch size
PAYMENT_WEBHOOK_BATCH_SIZE=10 (instead of 100)

# Monitor: Should clear backlog within 10 minutes
```

### Scenario 8: PCI DSS Compliance Audit Finding

**Indicators**:
- Annual audit finds: "Unencrypted sensitive data"
- Quarterly scan reports vulnerabilities
- Encryption key not rotated

**Root Causes**:
1. Encryption key not rotated (supposed to be quarterly)
2. Sensitive data (billing address) stored unencrypted
3. TLS version < 1.2
4. Access logs not sufficient for audit trail

**Resolution**:
```bash
# Verify encryption at rest
SELECT * FROM payments WHERE billing_address_encrypted IS NULL
AND created_at > NOW() - INTERVAL '1 year';

# Rotate KMS key
gcloud kms keys versions create \
  --location us-central1 \
  --keyring payments \
  --key billing-data

# Update payment-service to use new key
kubectl restart deployment payment-service -n money-path

# Verify TLS version
openssl s_client -connect payment-service:8080 -tls1_2

# Audit trail check
SELECT COUNT(*) as audit_log_count
FROM audit_log
WHERE service = 'payment-service'
AND created_at > NOW() - INTERVAL '7 years';

# Schedule compliance review
# - Quarterly vulnerability scans
# - Annual third-party audit
# - Incident response plan review
```

### Scenario 9: Database Connection Pool Exhausted

**Indicators**:
- `db_connection_pool_active` = 40/40
- Payment authorization latency > 5s
- New payment requests timeout (503)

**Root Causes**:
1. Leak: Connection not returned to pool
2. Long-running queries holding connections
3. Peak traffic spike
4. Downstream service slow (payment gateway timeout)

**Resolution**:
```bash
# Check connection pool status
curl http://payment-service:8080/actuator/metrics/db.hikari.connections.active

# Identify idle connections in database
SELECT datname, usename, application_name, query, query_start
FROM pg_stat_activity
WHERE datname = 'payments' AND state = 'idle'
ORDER BY query_start DESC;

# Kill idle connections
SELECT pg_terminate_backend(pid)
FROM pg_stat_activity
WHERE datname = 'payments'
AND query_start < NOW() - INTERVAL '30 minutes'
AND state = 'idle';

# Check for long-running transactions
SELECT pid, query, query_start, NOW() - query_start as duration
FROM pg_stat_activity
WHERE query NOT LIKE '%pg_stat_activity%'
ORDER BY query_start ASC;

# Increase pool size temporarily
kubectl set env deployment payment-service \
  SPRING_DATASOURCE_HIKARI_POOL_SIZE=60

# Scale payment-service replicas
kubectl scale deployment payment-service --replicas=6 -n money-path
```

### Scenario 10: Stripe Rate Limit (429 Too Many Requests)

**Indicators**:
- Stripe API errors: "Rate limit exceeded"
- `stripe_api_errors_total` counter > 50/min
- Authorization success rate drops to < 95%

**Root Causes**:
1. Accidental retry storm (exponential backoff not working)
2. Peak traffic surge (checkout surge)
3. Stripe account upgraded but rate limit not increased
4. Malicious bot (brute force card testing)

**Resolution**:
```bash
# Check current request rate
kubectl logs -n money-path deploy/payment-service | grep -i "rate_limit" | tail -20

# Check if retries are happening
curl http://payment-service:8080/actuator/metrics/stripe_retry_total

# Contact Stripe support to increase rate limit
# Current plan: 100 req/s, request upgrade to 200 req/s

# Implement adaptive backoff if not present
# Max retries: 3, Initial backoff: 500ms, Max backoff: 30s

# Temporarily increase request timeout
STRIPE_API_REQUEST_TIMEOUT_MS=10000 (instead of 5000)

# Monitor: Should recover once rate limit increased
```

### Scenario 11-15: Additional Troubleshooting Scenarios

- **Scenario 11**: Refund Reversal (Customer disputing refund credit)
- **Scenario 12**: Chargeback Detection (Fraud investigation)
- **Scenario 13**: Card Token Expiry (Stripe token invalid)
- **Scenario 14**: Multi-currency Conversion Error (Exchange rate mismatch)
- **Scenario 15**: Webhook Signature Key Rotation (Security update)

## Production Runbook Patterns

### Runbook 1: Payment Service Deployment (Canary)

**Pre-Deployment Checklist** (PCI DSS compliance):
1. Security scan clean (0 critical vulnerabilities)
2. Database migrations tested in staging
3. Stripe API key rotation verified
4. Encryption keys rotated (if quarterly cycle)
5. Audit trail backup complete
6. Runbook reviewed with ops team

**Deployment Steps**:
1. Deploy to 1 replica (canary, 5% traffic)
2. Monitor: Error rate, latency, Stripe API latency
3. Health check: `/actuator/health/ready` returns UP
4. Scale to 50% (10 min)
5. If issues: Rollback to previous version
6. Scale to 100% (10 min)

**Post-Deployment Verification**:
1. All payment operations < 300ms p99
2. Success rate > 99.95%
3. Zero PCI violations in audit log
4. Stripe API connectivity verified

### Runbook 2: Payment Processing Outage (Stripe Down)

**SLA**: < 15 min detection to mitigation

1. **Alert Received**: Stripe circuit breaker open
2. **Immediate Actions**:
   - Verify Stripe status: https://status.stripe.com
   - Check payment-service health
   - Alert checkout team: "Payments temporarily unavailable"
3. **Mitigation**:
   - Switch to "payment deferral mode" (queue authorizations)
   - Return 202 Accepted to clients (eventual processing)
   - Notify customers: "Payment processing delayed by 1-2 hours"
4. **Communication**: Post incident update to #incidents channel
5. **Recovery**:
   - Once Stripe recovers, replay deferred authorizations
   - Monitor success rate (should return to > 99.95%)
   - Post-mortem ticket: Why did Stripe go down?

### Runbook 3: Ledger Reconciliation Failure

**Scenario**: Nightly reconciliation detects balance mismatch

1. **Detection**: Alert fired, SEV-2 incident
2. **Diagnosis**: Query mismatched ledger entries
3. **Scope**: How many payments affected? Variance total?
4. **Resolution**:
   - If variance < $100: Finance team manual review
   - If variance > $1000: Page CTO (possible data corruption)
   - Generate adjustment entries if safe
5. **Communication**: Finance team notified, customer impact assessed

### Runbook 4: PCI Compliance Audit Preparation

**Annual Audit Checklist**:
1. Encryption audit: All billing data encrypted at rest (KMS)
2. Audit trail: 7 years of immutable logs
3. Access control: Only payment-service writes to payments table
4. Incident response: Recent incidents documented
5. Key rotation: Quarterly KMS key rotation complete
6. Network security: TLS 1.3, rate limiting enabled
7. Penetration test: Recent pen test completed

## Integrations

### Stripe API Integration

| Operation | Endpoint | Method | Timeout | Retry |
|-----------|----------|--------|---------|-------|
| Authorize | /v1/charges | POST | 5s | 3x exponential |
| Capture | /v1/charges/{id}/capture | POST | 5s | 3x exponential |
| Refund | /v1/charges/{id}/refunds | POST | 5s | 3x exponential |
| List | /v1/charges | GET | 10s | 1x |
| Webhook | POST event delivery | N/A | N/A | 5 days (Stripe retry) |

### Event Publishing (Kafka)

| Topic | Events | Guarantee | Format |
|-------|--------|-----------|--------|
| payments.events | PaymentAuthorized, PaymentCaptured, PaymentRefunded, PaymentFailed | Exactly-once (via outbox) | Avro or JSON |

### Event Consumption

| Topic | Purpose | Consumer Group |
|-------|---------|----------------|
| orders.events | OrderCancelled → auto-refund | payment-service-order-consumer |
| reconciliation.events | Daily reconciliation check | payment-service-reconciliation-consumer |

## Related Documentation

- **ADR-006**: Internal Service Authentication
- **ADR-014**: Reconciliation Authority Model (ledger)
- **Wave 34**: Admin-Gateway authentication
- **Wave 36**: Reconciliation-engine (financial audits)
- **Wave 38**: SLOs and error-budget policy
- [High-Level Design](diagrams/hld.md)
- [Low-Level Architecture](diagrams/lld.md)
- [Database Schema (ERD)](diagrams/erd.md)
- [REST API Contract](implementation/api.md)
- [Kafka Events](implementation/events.md)
- [Database Details](implementation/database.md)
- [Resilience & Retry Logic](implementation/resilience.md)
- [Runbook: payment-service/runbook.md](runbook.md)
- [Sequence Diagrams: Payment Flow](diagrams/sequence.md)
