# 05 — Payment Service & Refunds (payment-service)

> **Bounded Context**: Payment Processing, Refunds, Ledger  
> **Runtime**: Java 21 + Spring Boot 4.0.x on GKE  
> **Primary datastore**: Cloud SQL for PostgreSQL 15  
> **PSP**: Stripe (test mode for MVP; abstracted behind interface)  
> **Port (local)**: 8086 | **gRPC port**: 9086  
> **⚠️ PCI Note**: This service NEVER stores raw PAN/CVV. Only PSP tokens.

---

## 1. Architecture Overview

```
  ┌──────────────────────────────────────────┐
  │   Temporal Workflow (CheckoutWorkflow)    │
  │   calls: authorize → capture             │
  └──────────┬──────────────────┬────────────┘
             │                  │
   ┌─────────▼────────┐  ┌─────▼────────────┐
   │PaymentController  │  │WebhookController │
   │ (internal only)   │  │ (PSP callbacks)  │
   └─────────┬────────┘  └─────┬────────────┘
             │                  │
   ┌─────────▼──────────────────▼────────────┐
   │           PaymentService                 │
   │  authorize │ capture │ void │ refund     │
   └─────────┬────────────────────────────────┘
             │                  │
   ┌─────────▼────────┐  ┌─────▼────────────┐
   │ PaymentGateway    │  │ LedgerService    │
   │ (PSP abstraction) │  │ (double-entry)   │
   └─────────┬────────┘  └─────┬────────────┘
             │                  │
   ┌─────────▼────────┐  ┌─────▼────────────┐
   │  Stripe SDK       │  │  PostgreSQL       │
   │  (or mock)        │  │  payments, ledger │
   └──────────────────┘  └──────────────────┘
```

---

## 2. Package Structure

```
com.instacommerce.payment
├── PaymentServiceApplication.java
├── config/
│   ├── SecurityConfig.java           # Only internal/admin access
│   ├── StripeConfig.java             # Stripe API key from Secret Manager
│   └── WebhookConfig.java           # Webhook endpoint security
├── controller/
│   ├── PaymentController.java        # POST /payments/authorize, /capture, /void
│   ├── RefundController.java         # POST /payments/refund
│   └── WebhookController.java       # POST /payments/webhook (PSP callbacks)
├── dto/
│   ├── request/
│   │   ├── AuthorizeRequest.java     # {orderId, amountCents, currency, idempotencyKey, paymentMethod}
│   │   ├── CaptureRequest.java       # {paymentId}
│   │   ├── VoidRequest.java          # {paymentId}
│   │   └── RefundRequest.java        # {paymentId, amountCents, reason}
│   ├── response/
│   │   ├── PaymentResponse.java      # {paymentId, status, pspReference}
│   │   ├── RefundResponse.java       # {refundId, status, amountCents}
│   │   └── ErrorResponse.java
│   └── mapper/
│       └── PaymentMapper.java
├── domain/
│   ├── model/
│   │   ├── Payment.java              # Aggregate Root
│   │   ├── PaymentStatus.java        # enum: AUTHORIZED, CAPTURED, VOIDED, REFUNDED, FAILED
│   │   ├── Refund.java
│   │   ├── LedgerEntry.java          # Double-entry
│   │   └── OutboxEvent.java
│   └── valueobject/
│       └── Money.java
├── service/
│   ├── PaymentService.java           # Orchestrates auth/capture/void/refund
│   ├── RefundService.java
│   ├── LedgerService.java            # Double-entry accounting
│   └── OutboxService.java
├── gateway/
│   ├── PaymentGateway.java           # Interface (PSP abstraction)
│   ├── StripePaymentGateway.java     # Stripe implementation
│   └── MockPaymentGateway.java       # For local dev / tests
├── webhook/
│   ├── WebhookSignatureVerifier.java # Stripe signature check
│   └── WebhookEventHandler.java      # Process async PSP events
├── repository/
│   ├── PaymentRepository.java
│   ├── RefundRepository.java
│   ├── LedgerEntryRepository.java
│   └── OutboxEventRepository.java
├── exception/
│   ├── PaymentDeclinedException.java
│   ├── PaymentNotFoundException.java
│   ├── DuplicatePaymentException.java
│   ├── RefundExceedsChargeException.java
│   └── GlobalExceptionHandler.java
└── infrastructure/
    └── metrics/
        └── PaymentMetrics.java
```

---

## 3. Database Schema

### V1__create_payments.sql
```sql
CREATE TYPE payment_status AS ENUM ('AUTHORIZED', 'CAPTURED', 'VOIDED', 'PARTIALLY_REFUNDED', 'REFUNDED', 'FAILED');

CREATE TABLE payments (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    order_id        UUID            NOT NULL,
    amount_cents    BIGINT          NOT NULL,
    captured_cents  BIGINT          NOT NULL DEFAULT 0,
    refunded_cents  BIGINT          NOT NULL DEFAULT 0,
    currency        VARCHAR(3)      NOT NULL DEFAULT 'INR',
    status          payment_status  NOT NULL DEFAULT 'AUTHORIZED',
    psp_reference   VARCHAR(255),            -- Stripe PaymentIntent ID
    idempotency_key VARCHAR(64)     NOT NULL,
    payment_method  VARCHAR(50),             -- 'card', 'upi', 'wallet'
    metadata        JSONB,
    created_at      TIMESTAMPTZ     NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ     NOT NULL DEFAULT now(),
    version         BIGINT          NOT NULL DEFAULT 0,
    CONSTRAINT uq_payment_idempotency UNIQUE (idempotency_key),
    CONSTRAINT chk_amount_positive CHECK (amount_cents > 0),
    CONSTRAINT chk_refund_le_captured CHECK (refunded_cents <= captured_cents)
);

CREATE INDEX idx_payments_order ON payments (order_id);
CREATE INDEX idx_payments_psp   ON payments (psp_reference);
```

### V2__create_refunds.sql
```sql
CREATE TABLE refunds (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    payment_id      UUID        NOT NULL REFERENCES payments(id),
    amount_cents    BIGINT      NOT NULL,
    reason          VARCHAR(255),
    psp_refund_id   VARCHAR(255),
    idempotency_key VARCHAR(64) NOT NULL,
    status          VARCHAR(20) NOT NULL DEFAULT 'PENDING',  -- PENDING, COMPLETED, FAILED
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uq_refund_idempotency UNIQUE (idempotency_key),
    CONSTRAINT chk_refund_positive CHECK (amount_cents > 0)
);

CREATE INDEX idx_refunds_payment ON refunds (payment_id);
```

### V3__create_ledger.sql
```sql
CREATE TYPE ledger_entry_type AS ENUM ('DEBIT', 'CREDIT');

CREATE TABLE ledger_entries (
    id              BIGSERIAL PRIMARY KEY,
    payment_id      UUID            NOT NULL REFERENCES payments(id),
    entry_type      ledger_entry_type NOT NULL,
    amount_cents    BIGINT          NOT NULL,
    account         VARCHAR(50)     NOT NULL,  -- 'customer_receivable', 'merchant_payable', 'refund'
    reference_type  VARCHAR(30)     NOT NULL,  -- 'AUTHORIZATION', 'CAPTURE', 'REFUND', 'VOID'
    reference_id    VARCHAR(255),
    description     TEXT,
    created_at      TIMESTAMPTZ     NOT NULL DEFAULT now()
);

-- Double-entry invariant: SUM of debits = SUM of credits for each payment
CREATE INDEX idx_ledger_payment ON ledger_entries (payment_id);
```

---

## 4. PSP Abstraction (Gateway Pattern)

```java
public interface PaymentGateway {
    
    GatewayAuthResult authorize(GatewayAuthRequest request);
    GatewayCaptureResult capture(String pspReference, long amountCents);
    GatewayVoidResult voidAuth(String pspReference);
    GatewayRefundResult refund(String pspReference, long amountCents, String idempotencyKey);
}

@Component
@Profile("!test")
@RequiredArgsConstructor
public class StripePaymentGateway implements PaymentGateway {
    
    @Value("${stripe.api-key}")
    private String apiKey;
    
    @Override
    public GatewayAuthResult authorize(GatewayAuthRequest request) {
        Stripe.apiKey = apiKey;
        
        PaymentIntentCreateParams params = PaymentIntentCreateParams.builder()
            .setAmount(request.getAmountCents())
            .setCurrency(request.getCurrency().toLowerCase())
            .setCaptureMethod(PaymentIntentCreateParams.CaptureMethod.MANUAL)  // auth only
            .setIdempotencyKey(request.getIdempotencyKey())
            .build();
        
        RequestOptions options = RequestOptions.builder()
            .setIdempotencyKey(request.getIdempotencyKey())
            .build();
        
        PaymentIntent intent = PaymentIntent.create(params, options);
        
        return GatewayAuthResult.builder()
            .pspReference(intent.getId())
            .status(mapStatus(intent.getStatus()))
            .build();
    }
    // ... capture, void, refund implementations
}

@Component
@Profile("test")
public class MockPaymentGateway implements PaymentGateway {
    // Returns success for all calls; allows injecting failures via test config
}
```

---

## 5. Idempotent Authorization Flow

```java
@Service
@RequiredArgsConstructor
@Transactional
public class PaymentService {
    
    private final PaymentRepository paymentRepo;
    private final PaymentGateway gateway;
    private final LedgerService ledger;
    private final OutboxService outbox;

    public PaymentResponse authorize(AuthorizeRequest request) {
        // 1. Idempotency check
        Optional<Payment> existing = paymentRepo.findByIdempotencyKey(request.getIdempotencyKey());
        if (existing.isPresent()) {
            return PaymentMapper.toResponse(existing.get()); // return same result
        }
        
        // 2. Call PSP
        GatewayAuthResult result = gateway.authorize(GatewayAuthRequest.builder()
            .amountCents(request.getAmountCents())
            .currency(request.getCurrency())
            .idempotencyKey(request.getIdempotencyKey())
            .build());
        
        if (!result.isSuccess()) {
            throw new PaymentDeclinedException(result.getDeclineReason());
        }
        
        // 3. Persist payment
        Payment payment = Payment.builder()
            .orderId(request.getOrderId())
            .amountCents(request.getAmountCents())
            .currency(request.getCurrency())
            .status(PaymentStatus.AUTHORIZED)
            .pspReference(result.getPspReference())
            .idempotencyKey(request.getIdempotencyKey())
            .paymentMethod(request.getPaymentMethod())
            .build();
        paymentRepo.save(payment);
        
        // 4. Ledger entry (debit customer receivable)
        ledger.record(payment.getId(), LedgerEntryType.DEBIT, 
            request.getAmountCents(), "customer_receivable", "AUTHORIZATION");
        
        return PaymentMapper.toResponse(payment);
    }
    
    public void capture(UUID paymentId) {
        Payment payment = paymentRepo.findById(paymentId)
            .orElseThrow(() -> new PaymentNotFoundException(paymentId));
        
        if (payment.getStatus() == PaymentStatus.CAPTURED) return; // idempotent
        
        gateway.capture(payment.getPspReference(), payment.getAmountCents());
        
        payment.setStatus(PaymentStatus.CAPTURED);
        payment.setCapturedCents(payment.getAmountCents());
        paymentRepo.save(payment);
        
        ledger.record(payment.getId(), LedgerEntryType.CREDIT,
            payment.getAmountCents(), "merchant_payable", "CAPTURE");
        
        outbox.publish("Payment", payment.getId().toString(),
            "PaymentCaptured", Map.of("orderId", payment.getOrderId(), "amount", payment.getAmountCents()));
    }
    
    public void voidAuth(UUID paymentId) {
        Payment payment = paymentRepo.findById(paymentId)
            .orElseThrow(() -> new PaymentNotFoundException(paymentId));
        
        if (payment.getStatus() == PaymentStatus.VOIDED) return; // idempotent
        
        gateway.voidAuth(payment.getPspReference());
        
        payment.setStatus(PaymentStatus.VOIDED);
        paymentRepo.save(payment);
        
        // Reverse ledger
        ledger.record(payment.getId(), LedgerEntryType.CREDIT,
            payment.getAmountCents(), "customer_receivable", "VOID");
    }
}
```

---

## 6. Webhook Handling (Stripe)

```java
@RestController
@RequestMapping("/payments/webhook")
@RequiredArgsConstructor
public class WebhookController {

    @Value("${stripe.webhook-secret}")
    private String webhookSecret;
    
    private final WebhookEventHandler handler;

    @PostMapping
    public ResponseEntity<String> handleWebhook(
            @RequestBody String payload,
            @RequestHeader("Stripe-Signature") String sigHeader) {
        
        Event event;
        try {
            event = Webhook.constructEvent(payload, sigHeader, webhookSecret);
        } catch (SignatureVerificationException e) {
            return ResponseEntity.status(400).body("Invalid signature");
        }
        
        handler.handle(event);
        return ResponseEntity.ok("OK");
    }
}
```

---

## 7. REST API

| Method | Path | Auth | Description |
|--------|------|------|-------------|
| POST | /payments/authorize | Internal | Authorize payment |
| POST | /payments/{id}/capture | Internal | Capture authorized payment |
| POST | /payments/{id}/void | Internal | Void authorization |
| POST | /payments/{id}/refund | Internal/Admin | Refund (full/partial) |
| POST | /payments/webhook | Public (sig verified) | PSP webhooks |
| GET | /payments/{id} | Internal/Admin | Get payment details |

**⚠️ These endpoints are NOT exposed to end-users via API Gateway. Only internal services and admin can call them.**

---

## 8. GCP Guidelines

### Secret Manager
- `stripe-api-key` (sk_test_*): Secret Manager, accessed via Workload Identity
- `stripe-webhook-secret` (whsec_*): Secret Manager

### application.yml (prod)
```yaml
spring:
  application:
    name: payment-service
  datasource:
    url: jdbc:postgresql:///${DB_NAME}?cloudSqlInstance=${CLOUD_SQL_INSTANCE}&socketFactory=com.google.cloud.sql.postgres.SocketFactory
    username: ${DB_USER}
    password: ${sm://payment-db-password}

stripe:
  api-key: ${sm://stripe-api-key}
  webhook-secret: ${sm://stripe-webhook-secret}

# No external endpoints exposed; Istio AuthorizationPolicy restricts to internal sources
```

### Network Policy
```yaml
apiVersion: security.istio.io/v1
kind: AuthorizationPolicy
metadata:
  name: payment-service-access
spec:
  selector:
    matchLabels:
      app: payment-service
  rules:
    - from:
        - source:
            principals: ["cluster.local/ns/default/sa/order-service"]
            principals: ["cluster.local/ns/default/sa/fulfillment-service"]
      to:
        - operation:
            methods: ["POST", "GET"]
    - from: []   # allow webhook from external (Stripe IPs)
      to:
        - operation:
            paths: ["/payments/webhook"]
```

---

## 9. Observability & Error Codes

### Metrics
| Metric | Type | Labels |
|--------|------|--------|
| `payment_authorize_total` | Counter | status={success,declined,error} |
| `payment_capture_total` | Counter | status |
| `payment_refund_total` | Counter | status |
| `payment_psp_latency_seconds` | Histogram | operation |
| `payment_webhook_received_total` | Counter | event_type |

### Error Codes
| HTTP | Code | When |
|------|------|------|
| 404 | PAYMENT_NOT_FOUND | ID miss |
| 409 | DUPLICATE_PAYMENT | Same idempotency key |
| 422 | PAYMENT_DECLINED | PSP declined |
| 422 | REFUND_EXCEEDS_CHARGE | Refund > captured |
| 500 | PSP_ERROR | PSP network/system error |

---

## 10. Agent Instructions (CRITICAL)

### MUST DO
1. Abstract PSP behind `PaymentGateway` interface — never call Stripe SDK directly from service layer.
2. Every authorization MUST use idempotency key (sent to PSP and stored in DB).
3. `capture()` and `voidAuth()` MUST be idempotent (check status before PSP call).
4. Webhook MUST verify Stripe signature before processing.
5. Ledger entries: every financial operation creates a double-entry pair.
6. Refund checks: sum of refunded_cents must never exceed captured_cents.
7. Payment endpoints are internal-only — enforce via Istio AuthorizationPolicy.
8. Use `MockPaymentGateway` for tests (activated via Spring `@Profile("test")`).

### MUST NOT DO
1. Do NOT store PAN, CVV, or full card numbers. Only PSP tokens (psp_reference).
2. Do NOT expose payment endpoints in API Gateway to end-users.
3. Do NOT use float/double for money.
4. Do NOT retry on `PaymentDeclinedException` — it's terminal.
5. Do NOT skip webhook signature verification.

### DEFAULTS
- Local dev: use `MockPaymentGateway` (always returns success)
- Currency: INR
- Local DB: `jdbc:postgresql://localhost:5432/payments`
