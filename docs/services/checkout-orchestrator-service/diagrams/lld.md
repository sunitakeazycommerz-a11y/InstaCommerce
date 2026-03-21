# Checkout Orchestrator Service - Low-Level Design

## Components

### 1. CheckoutController
**File**: src/main/java/com/instacommerce/checkout/controller/CheckoutController.java

**Endpoints**:
- `POST /checkout` - Initiate checkout
- `GET /checkout/{workflowId}/status` - Query workflow status

**Key Logic**:
- Idempotency-Key validation (30-min TTL cache)
- WorkflowOptions setup (5-min execution timeout)
- Duplicate detection with `WorkflowExecutionAlreadyStarted` exception handling
- Response serialization to JSON for cache storage

### 2. CheckoutWorkflow (Temporal)
**Interface**: Defines workflow contract

**Methods**:
- `checkout(CheckoutRequest): CheckoutResponse` - Execute saga
- `getStatus(): String` - Query current status

**Saga Steps**:
1. ValidateCartActivity
2. ReserveInventoryActivity
3. AuthorizePaymentActivity
4. CalculateFinalPriceActivity
5. CreateOrderActivity

### 3. CheckoutIdempotencyKeyRepository
**Entity**: CheckoutIdempotencyKey

**Fields**:
- `idempotencyKey` (String, unique)
- `checkoutResponse` (Text JSON)
- `expiresAt` (TIMESTAMPTZ)
- `createdAt` (TIMESTAMPTZ)

**Operations**:
- findByIdempotencyKey()
- save()
- delete()

### 4. Resilience4j Circuit Breakers

**Instances**:
```
cartService:
  - slidingWindowSize: 20 calls
  - failureRateThreshold: 50%
  - waitDurationInOpenState: 30s
  - permittedNumberOfCallsInHalfOpenState: 5

(Similar config for inventoryService, orderService, paymentService, pricingService)
```

## State Machine

```
PENDING ─→ IN_PROGRESS ─→ COMPLETED
  ↓         (activities)      ↓
  ├─────────────────────── FAILED
  └─────────────── TIMEOUT (5 min)
```

## Key State Transitions

1. **CheckoutRequest arrives**
   - Validate JWT principal
   - Check idempotency key in DB
   - If hit: return cached response (COMPLETED)
   - If miss: proceed to IN_PROGRESS

2. **Workflow Execution**
   - Each activity is retryable
   - Circuit breakers apply per service
   - Activity timeout: 2s per call

3. **Completion**
   - Cache response in PostgreSQL
   - Return CheckoutResponse to client
   - TTL: 30 minutes

## Activity Calls (Temporal)

```
CheckoutWorkflow
├── ValidateCartActivity
│   └── GET /cart/{cartId}
├── ReserveInventoryActivity
│   └── POST /inventory/reserve
├── AuthorizePaymentActivity
│   └── POST /payment/authorize
├── CalculateFinalPriceActivity
│   └── GET /pricing/calculate
└── CreateOrderActivity
    └── POST /order/create
```

## Database Schema (Minimal)

```sql
-- checkout_idempotency_keys
id              UUID PRIMARY KEY
idempotency_key VARCHAR(255) UNIQUE NOT NULL
checkout_response TEXT NOT NULL
created_at      TIMESTAMPTZ DEFAULT now()
expires_at      TIMESTAMPTZ NOT NULL

-- shedlock (for distributed locking)
name            VARCHAR(64) PRIMARY KEY
lock_until      TIMESTAMPTZ NOT NULL
locked_at       TIMESTAMPTZ DEFAULT now()
locked_by       VARCHAR(255) NOT NULL
```

## Error Handling Strategy

| Error Type | Behavior |
|------------|----------|
| ValidationException | Return 400 Bad Request immediately |
| CircuitBreakerOpenException | Return 503 Service Unavailable |
| WorkflowExecutionAlreadyStarted | Return 202 Accepted with existing workflowId |
| TimeoutException | Return 504 Gateway Timeout after 5 minutes |
| IOException | Retry 3x via Resilience4j, then circuit breaker |
