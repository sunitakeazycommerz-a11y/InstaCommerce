# 10 — Security, Compliance & Operational Runbooks

> **Scope**: PCI-DSS compliance, GDPR data erasure, secret management, audit logging, chaos testing, SRE runbooks, production readiness checklist  
> **Audience**: All service agents + platform/SRE agent

---

## 1. Security Architecture

```
                     Internet
                        │
                  ┌─────▼──────┐
                  │ Cloud Armor │  WAF: OWASP Top 10 rules
                  │ (DDoS/WAF)  │  Rate limiting: 1000 req/min/IP
                  └─────┬──────┘
                        │
                  ┌─────▼──────┐
                  │ Istio       │  TLS termination (Let's Encrypt)
                  │ Ingress GW  │  
                  └─────┬──────┘
                        │
          ┌─────────────▼─────────────┐
          │  Istio Service Mesh       │
          │  • mTLS STRICT (all pods) │
          │  • AuthorizationPolicies  │
          │  • RequestAuthentication  │
          │    (JWT validation)       │
          └─────────────┬─────────────┘
                        │
          ┌─────────────▼─────────────┐
          │  Application Layer        │
          │  • Spring Security        │
          │  • Input validation       │
          │  • RBAC enforcement       │
          │  • Audit logging          │
          └───────────────────────────┘
```

---

## 2. PCI-DSS Compliance (SAQ-A Level)

Since we use Stripe Elements (tokenization on client-side), we never handle raw card data. We target **SAQ-A** compliance.

### Checklist

| # | Requirement | Implementation | Status |
|---|-------------|---------------|--------|
| 1 | Never store PAN, CVV, or magnetic stripe data | Stripe tokenizes on client; server only sees `pm_xxx` token | ✅ |
| 2 | Encrypt cardholder data in transit | TLS 1.3 everywhere (Istio mTLS + Cloud Armor HTTPS-only) | ✅ |
| 3 | Maintain a vulnerability management program | Trivy container scanning in CI; Dependabot for dependencies | ✅ |
| 4 | Implement strong access control | RBAC + Workload Identity + least-privilege IAM | ✅ |
| 5 | Monitor and test networks | OpenTelemetry tracing + Prometheus alerting + audit logs | ✅ |
| 6 | Maintain an information security policy | This document; regular review cycle | ✅ |

### Payment Service Specific Rules

```java
// NEVER log or store these fields
// payment_method_id (pm_xxx) — store only as reference, never log
// Never store: card number, CVV, expiry, cardholder name
// Stripe webhook payloads: mask any card.last4 in structured logs

@Component
public class PciLoggingFilter implements Filter {
    private static final Set<String> REDACT_FIELDS = Set.of(
        "card_number", "cvv", "cvc", "expiry", "pan"
    );
    
    // Intercept and redact any PCI-sensitive fields from log output
}
```

---

## 3. GDPR Compliance

### Data Categories per Service

| Service | Personal Data Stored | Lawful Basis |
|---------|---------------------|-------------|
| identity-service | Email, phone, hashed password, name | Contract performance |
| order-service | Shipping address, order history | Contract performance |
| payment-service | Stripe customer ID (no card data) | Contract performance |
| notification-service | Email, phone (for delivery) | Legitimate interest |
| fulfillment-service | Shipping address (denormalized) | Contract performance |

### Right to Erasure (Article 17) — Implementation

```java
// UserDeletionSaga — orchestrated by identity-service
// Triggered by: DELETE /api/v1/users/me

@Component
@RequiredArgsConstructor
public class UserDeletionService {
    
    private final UserRepository userRepository;
    private final OutboxService outboxService;
    
    @Transactional
    public void initiateErasure(UUID userId) {
        // 1. Anonymize user record (don't delete — referential integrity)
        User user = userRepository.findById(userId)
            .orElseThrow(() -> new NotFoundException("User not found"));
        
        user.setEmail("deleted-" + userId + "@anonymized.local");
        user.setPhone(null);
        user.setFirstName("DELETED");
        user.setLastName("USER");
        user.setPasswordHash("[REDACTED]");
        user.setActive(false);
        user.setDeletedAt(Instant.now());
        userRepository.save(user);
        
        // 2. Invalidate all refresh tokens
        refreshTokenRepository.deleteAllByUserId(userId);
        
        // 3. Publish event for downstream services to anonymize their data
        outboxService.publish("User", userId.toString(), "UserErased",
            Map.of("userId", userId, "erasedAt", Instant.now()));
        
        // 4. Log audit event
        auditLogService.log(userId, "USER_ERASURE_INITIATED", "GDPR Art.17 request");
    }
}
```

### Downstream Handler (every service)

```java
@KafkaListener(topics = "identity.events", groupId = "order-service-erasure")
public void handleUserErased(String payload) {
    UserErasedEvent event = objectMapper.readValue(payload, UserErasedEvent.class);
    // Anonymize orders: replace name/address with "[REDACTED]"
    orderRepository.anonymizeByUserId(event.getUserId());
}
```

### Data Retention Policy

| Data | Retention | Action After |
|------|-----------|-------------|
| Active user data | While account active | Anonymize on deletion |
| Order records | 7 years (legal requirement) | Anonymize PII, keep transaction data |
| Payment records | 7 years | Keep Stripe refs, anonymize user details |
| Audit logs | 2 years | Archive to cold storage, then delete |
| Notification logs | 90 days | Hard delete |
| Kafka events | 7-30 days (topic retention) | Auto-deleted by Kafka |

---

## 4. Secret Management (GCP Secret Manager)

### Secrets Inventory

| Secret Name | Service | Content |
|-------------|---------|---------|
| `jwt-rsa-private-key` | identity-service | RSA-2048 private key for JWT signing |
| `jwt-rsa-public-key` | all services | RSA public key for JWT verification |
| `stripe-api-key` | payment-service | Stripe secret key (sk_test_xxx) |
| `stripe-webhook-secret` | payment-service | Webhook endpoint secret (whsec_xxx) |
| `db-password-identity` | identity-service | PostgreSQL password |
| `db-password-catalog` | catalog-service | PostgreSQL password |
| `db-password-inventory` | inventory-service | PostgreSQL password |
| `db-password-order` | order-service | PostgreSQL password |
| `db-password-payment` | payment-service | PostgreSQL password |
| `db-password-fulfillment` | fulfillment-service | PostgreSQL password |
| `db-password-notification` | notification-service | PostgreSQL password |
| `sendgrid-api-key` | notification-service | SendGrid API key |
| `twilio-auth-token` | notification-service | Twilio auth token |

### Access Pattern (Spring Boot + Workload Identity)

```yaml
# application-gcp.yml
spring:
  config:
    import: sm://  # Spring Cloud GCP Secret Manager integration

  datasource:
    password: ${sm://db-password-order}

jwt:
  public-key: ${sm://jwt-rsa-public-key}
```

### Terraform IAM Binding

```hcl
# Each service account can only access its own secrets
resource "google_secret_manager_secret_iam_member" "order_db_password" {
  secret_id = google_secret_manager_secret.db_password_order.id
  role      = "roles/secretmanager.secretAccessor"
  member    = "serviceAccount:${google_service_account.order_service.email}"
}
```

---

## 5. Audit Logging

### Audit Log Schema (every service)

```sql
CREATE TABLE audit_log (
    id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id     UUID,
    action      VARCHAR(100)  NOT NULL,   -- e.g., 'USER_LOGIN', 'ORDER_PLACED', 'REFUND_ISSUED'
    entity_type VARCHAR(50),              -- e.g., 'User', 'Order', 'Payment'
    entity_id   VARCHAR(100),
    details     JSONB,                    -- Additional context
    ip_address  VARCHAR(45),
    user_agent  TEXT,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_audit_user_id ON audit_log(user_id);
CREATE INDEX idx_audit_action ON audit_log(action);
CREATE INDEX idx_audit_created_at ON audit_log(created_at);
```

### Audit Service Implementation

```java
@Service
@RequiredArgsConstructor
public class AuditLogService {
    
    private final AuditLogRepository repository;
    
    @Async
    public void log(UUID userId, String action, String entityType, 
                    String entityId, Map<String, Object> details) {
        AuditLog entry = AuditLog.builder()
            .userId(userId)
            .action(action)
            .entityType(entityType)
            .entityId(entityId)
            .details(details)
            .ipAddress(RequestContextHolder.getIpAddress())
            .userAgent(RequestContextHolder.getUserAgent())
            .build();
        repository.save(entry);
    }
}
```

### Events That MUST Be Audited

| Event | Action Code | Service |
|-------|------------|---------|
| User login (success/fail) | USER_LOGIN_SUCCESS / USER_LOGIN_FAILED | identity |
| User registration | USER_REGISTERED | identity |
| Password change | PASSWORD_CHANGED | identity |
| User deletion/erasure | USER_ERASURE_INITIATED | identity |
| Order placed | ORDER_PLACED | order |
| Order cancelled | ORDER_CANCELLED | order |
| Payment authorized | PAYMENT_AUTHORIZED | payment |
| Payment captured | PAYMENT_CAPTURED | payment |
| Refund issued | REFUND_ISSUED | payment |
| Admin product update | PRODUCT_UPDATED | catalog |
| Stock adjustment (manual) | STOCK_ADJUSTED | inventory |

---

## 6. Input Validation Standards

### Every REST endpoint MUST validate:

```java
// Use Jakarta Bean Validation on ALL request DTOs

public record CreateProductRequest(
    @NotBlank @Size(max = 200)
    String name,
    
    @NotBlank @Pattern(regexp = "^[A-Z0-9\\-]{3,20}$", message = "SKU must be alphanumeric uppercase")
    String sku,
    
    @NotNull @DecimalMin("0.01") @DecimalMax("999999.99")
    BigDecimal price,
    
    @NotNull
    UUID categoryId,
    
    @Size(max = 2000)
    String description
) {}

// Controller MUST use @Valid
@PostMapping
public ResponseEntity<?> create(@Valid @RequestBody CreateProductRequest request) { ... }
```

### SQL Injection Prevention
- **NEVER** use string concatenation for SQL queries
- Use Spring Data JPA named parameters or `@Query` with `:paramName`
- Use `PreparedStatement` for native queries

### XSS Prevention
- All user-generated content stored as-is, escaped on output
- Content-Type: `application/json` (not HTML) — inherently safe for API responses
- Notification templates: use Mustache/Thymeleaf auto-escaping

---

## 7. Rate Limiting Configuration

### Per-Service Rate Limits

| Endpoint | Limit | Window | Strategy |
|----------|-------|--------|----------|
| POST /api/v1/auth/login | 5 | 1 min | Per IP |
| POST /api/v1/auth/register | 3 | 1 min | Per IP |
| POST /api/v1/checkout | 10 | 1 min | Per User |
| GET /api/v1/products | 100 | 1 min | Per IP |
| POST /api/v1/cart/* | 30 | 1 min | Per User |
| * (global fallback) | 200 | 1 min | Per IP |

### Implementation (Resilience4j)

```yaml
# application.yml
resilience4j:
  ratelimiter:
    instances:
      login:
        limit-for-period: 5
        limit-refresh-period: 60s
        timeout-duration: 0s    # Fail immediately
      register:
        limit-for-period: 3
        limit-refresh-period: 60s
        timeout-duration: 0s
```

---

## 8. SRE Runbooks

### Runbook 1: Stuck Orders (Order in PENDING > 10 minutes)

```
DETECTION:
  Alert: "OrderStuckInPending" — orders table has rows with status=PENDING and 
         created_at < now() - interval '10 minutes'

DIAGNOSIS:
  1. Check Temporal UI (port 8233) — find workflow by orderId
  2. Look at workflow history for failed activities
  3. Common causes:
     a) Inventory reservation timeout → Check inventory-service logs
     b) Payment authorization failure → Check payment-service logs + Stripe dashboard
     c) Temporal worker crash → Check temporal worker pod status

RESOLUTION:
  Option A (Workflow still running): Let it complete or time out naturally
  Option B (Workflow failed): 
    - If saga compensated successfully → Order should be FAILED, notify customer
    - If saga compensation also failed → Manual intervention:
      1. Check if inventory was reserved: SELECT * FROM reservations WHERE order_id = ?
      2. Check if payment was authorized: SELECT * FROM payments WHERE order_id = ?
      3. Manually release reservation if needed
      4. Manually void payment authorization if needed
      5. Update order status to FAILED
  Option C (Temporal workflow not found):
    - Checkout endpoint may have failed before starting workflow
    - No cleanup needed — nothing was reserved/charged
```

### Runbook 2: Kafka Consumer Lag > 1000

```
DETECTION:
  Alert: "KafkaConsumerLag" — consumer lag metric > 1000 for 10 minutes

DIAGNOSIS:
  1. Check which consumer group is lagging (Kafka UI on port 8090)
  2. Check consumer pod CPU/memory — may need HPA scale-up
  3. Check for processing errors in consumer logs
  4. Check if downstream dependency is slow (e.g., DB, external API)

RESOLUTION:
  1. If consumer is healthy but slow: Increase consumer pod replicas (HPA should handle)
  2. If consumer is crash-looping: Check logs for exception, fix and redeploy
  3. If downstream DB is slow: Check Cloud SQL CPU/connections, consider scaling up
  4. If event processing is failing: Messages will be retried; check DLQ for poison pills
  5. If lag is due to rebalancing: Wait 5-10 minutes for rebalance to settle

PREVENTION:
  - Set consumer max.poll.records=50 (not too high)
  - Ensure consumer processing is idempotent (safe to retry)
  - Monitor consumer lag as key SLI
```

### Runbook 3: Payment Reconciliation Mismatch

```
DETECTION:
  Daily reconciliation job compares:
    SUM(payments WHERE status='CAPTURED') vs Stripe dashboard

DIAGNOSIS:
  1. Query payments table: 
     SELECT status, count(*), sum(amount) FROM payments 
     WHERE captured_at::date = CURRENT_DATE - 1 GROUP BY status;
  2. Compare with Stripe: https://dashboard.stripe.com/test/payments
  3. Look for discrepancies in webhook delivery (Stripe webhook logs)

RESOLUTION:
  1. Missing capture in our DB but exists in Stripe:
     - Webhook may have failed; re-trigger from Stripe dashboard
     - Or manually update payment status + create ledger entry
  2. Capture in our DB but not in Stripe:
     - Very rare; check if Stripe API returned success
     - May be a test/sandbox artifact
  3. Amount mismatch:
     - Check for partial captures or refunds
     - Verify currency conversion (we use INR only for MVP)
```

### Runbook 4: Database Connection Pool Exhaustion

```
DETECTION:
  Alert: hikaricp_connections_active == hikaricp_connections_max for > 2 minutes
  Symptom: Requests timing out, "Connection is not available" in logs

DIAGNOSIS:
  1. Check active queries: 
     SELECT pid, query, state, wait_event_type, now()-query_start AS duration 
     FROM pg_stat_activity WHERE datname='order_db' ORDER BY duration DESC;
  2. Look for long-running transactions or deadlocks
  3. Check if a migration is running

RESOLUTION:
  1. Kill long-running queries: SELECT pg_terminate_backend(<pid>);
  2. If deadlock: Will auto-resolve; check logs for deadlock graph
  3. If pool too small: Increase HikariCP maximumPoolSize (but check Cloud SQL max_connections)
  4. If connection leak: Check for missing @Transactional or unclosed connections
     - Enable HikariCP leak detection: spring.datasource.hikari.leak-detection-threshold=30000
```

---

## 9. Chaos Testing Guidelines

### Tests to Run Before Production

| Test | Tool | What It Validates |
|------|------|-------------------|
| Kill a service pod | `kubectl delete pod` | Kubernetes restarts it; Istio retries in-flight requests |
| Inject 500ms latency to DB | Istio fault injection | Circuit breaker opens; fallback response returned |
| Kill Kafka broker | Stop Kafka container | Producers buffer; consumers reconnect on recovery |
| Full Temporal worker crash | Kill temporal-worker pod | Workflow resumes on new worker (durable execution) |
| Simulate payment timeout | Mock Stripe with 30s delay | Temporal activity timeout fires; saga compensates |
| Redis crash | Stop Redis | Cart reads fail gracefully; returns empty cart with error message |
| Exhaust DB connections | Load test + small pool | HikariCP rejects fast; circuit breaker trips; 503 returned |

### Istio Fault Injection Example

```yaml
apiVersion: networking.istio.io/v1beta1
kind: VirtualService
metadata:
  name: inventory-fault-test
  namespace: instacommerce
spec:
  hosts:
    - inventory-service
  http:
    - fault:
        delay:
          percentage:
            value: 50        # 50% of requests
          fixedDelay: 3s     # 3 second delay
        abort:
          percentage:
            value: 10        # 10% of requests
          httpStatus: 503
      route:
        - destination:
            host: inventory-service
```

---

## 10. Production Readiness Checklist

### Before Go-Live — Every Service MUST Have:

| # | Item | Verified By |
|---|------|------------|
| 1 | All Flyway migrations applied and tested | Integration test with Testcontainers |
| 2 | Health endpoints: `/actuator/health/readiness` and `/actuator/health/liveness` | k8s probes |
| 3 | Structured JSON logging (no plaintext logs) | Logback config review |
| 4 | OpenTelemetry traces propagated (traceId in all logs) | Manual trace verification in Grafana |
| 5 | Prometheus metrics exported at `/actuator/prometheus` | Grafana dashboard check |
| 6 | Rate limiting configured on public endpoints | Load test verification |
| 7 | Circuit breaker configured for all external calls | Chaos test |
| 8 | Input validation on ALL request DTOs (`@Valid`) | Unit tests |
| 9 | No secrets in code, config files, or Docker images | CI secret scanning (gitleaks) |
| 10 | Docker image runs as non-root | Dockerfile review |
| 11 | Resource requests and limits set | Helm values review |
| 12 | HPA configured with appropriate thresholds | Load test |
| 13 | PDB configured (`maxUnavailable: 1`) | Helm template |
| 14 | Graceful shutdown configured (`server.shutdown=graceful`) | Config review |
| 15 | Idempotency keys on all create/mutation endpoints | Integration test |
| 16 | Outbox pattern for all Kafka events (no direct publishing) | Code review |
| 17 | Minimum 70% test coverage (line) | JaCoCo enforcement |
| 18 | Load test passing at 2x expected traffic | k6/Gatling results |
| 19 | Runbook written for common failure scenarios | This document |
| 20 | Alerting rules deployed (error rate, latency, consumer lag) | Prometheus rules review |

---

## 11. Security Headers (Istio EnvoyFilter)

```yaml
apiVersion: networking.istio.io/v1alpha3
kind: EnvoyFilter
metadata:
  name: security-headers
  namespace: instacommerce
spec:
  configPatches:
    - applyTo: HTTP_FILTER
      match:
        context: SIDECAR_INBOUND
      patch:
        operation: INSERT_BEFORE
        value:
          name: envoy.filters.http.header_to_metadata
    - applyTo: ROUTE_CONFIGURATION
      patch:
        operation: MERGE
        value:
          response_headers_to_add:
            - header:
                key: X-Content-Type-Options
                value: nosniff
            - header:
                key: X-Frame-Options
                value: DENY
            - header:
                key: Strict-Transport-Security
                value: max-age=31536000; includeSubDomains
            - header:
                key: X-XSS-Protection
                value: "1; mode=block"
            - header:
                key: Content-Security-Policy
                value: "default-src 'self'"
```

---

## 12. Agent Instructions (CRITICAL)

### MUST DO
1. Every REST controller MUST use `@Valid` on request body parameters.
2. Every service MUST have an `audit_log` table and log security-relevant actions.
3. ALL database passwords MUST come from Secret Manager — NEVER hardcode.
4. ALL services MUST use `@JsonIgnoreProperties(ignoreUnknown = true)` on incoming DTOs.
5. Payment service MUST verify Stripe webhook signatures before processing.
6. GDPR erasure MUST anonymize (not delete) to preserve referential integrity.
7. Use `server.shutdown=graceful` with `spring.lifecycle.timeout-per-shutdown-phase=30s`.

### MUST NOT DO
1. Do NOT log PII (email, phone, address) at INFO level — only at DEBUG with masking.
2. Do NOT store credit card numbers, CVV, or expiry dates anywhere — ever.
3. Do NOT use HTTP (non-TLS) for any communication, even internal (Istio mTLS enforced).
4. Do NOT grant `roles/owner` or `roles/editor` to service accounts — use least-privilege IAM.
5. Do NOT use `*` imports or `SELECT *` in production code.
6. Do NOT disable CSRF protection without Istio/JWT protection in place.

### DEFAULTS
- Password hashing: BCrypt (strength 12)
- JWT algorithm: RS256 with RSA-2048 keys
- TLS: 1.3 minimum (enforced by Istio)
- Session: Stateless (JWT-based, no server-side sessions)
- CORS: Restrict to known frontend origins only
- Audit log retention: 2 years
- Graceful shutdown timeout: 30 seconds

### ESCALATION TRIGGERS
- If a security vulnerability is found in a dependency → Patch immediately, don't wait for sprint
- If PCI-sensitive data is accidentally logged → Incident response: purge logs, rotate keys, notify
- If GDPR erasure request fails midway → Manual completion required within 30 days
- If secrets are committed to git → Rotate ALL affected secrets immediately
