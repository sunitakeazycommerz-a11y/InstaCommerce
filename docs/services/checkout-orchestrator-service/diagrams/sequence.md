# Checkout Orchestrator Service - Sequence Diagrams

## Standard Checkout Flow (Happy Path)

```mermaid
sequenceDiagram
    participant Client
    participant CO as checkout-orchestrator
    participant DB as PostgreSQL
    participant TS as Temporal Server
    participant Cart as cart-service
    participant Inventory as inventory-service
    participant Payment as payment-service
    participant Pricing as pricing-service
    participant Order as order-service

    Client->>CO: POST /checkout {request, Idempotency-Key: XYZ}
    CO->>CO: Validate JWT principal
    CO->>DB: SELECT * FROM checkout_idempotency_keys WHERE key=XYZ
    DB-->>CO: null (not found)

    CO->>TS: startWorkflow(workflowId, CheckoutRequest)
    TS-->>CO: workflowHandle (in progress)

    TS->>Cart: ValidateCartActivity
    Cart-->>TS: CartValidationResult

    TS->>Inventory: ReserveInventoryActivity
    Inventory-->>TS: ReservationId

    TS->>Payment: AuthorizePaymentActivity
    Payment-->>TS: PaymentAuthorizationId

    TS->>Pricing: CalculateFinalPriceActivity
    Pricing-->>TS: FinalPrice

    TS->>Order: CreateOrderActivity
    Order-->>TS: OrderId (SUCCESS)

    CO->>DB: INSERT INTO checkout_idempotency_keys (key, response, expires_at)
    DB-->>CO: OK

    CO-->>Client: 200 OK {orderId, status: COMPLETED, total}
```

## Duplicate Request During In-Flight Workflow

```mermaid
sequenceDiagram
    participant Client1
    participant Client2
    participant CO as checkout-orchestrator
    participant DB as PostgreSQL
    participant TS as Temporal Server

    Client1->>CO: POST /checkout {key: ABC, userId: 123}
    CO->>DB: findByIdempotencyKey(ABC)
    DB-->>CO: null
    CO->>TS: startWorkflow(ABC, request)
    TS-->>CO: handle1 (IN_PROGRESS)
    CO->>DB: INSERT idempotency (ABC, cached=null, expires=+30min)
    DB-->>CO: OK (optimistic)

    Note over TS: Workflow executing...

    Client2->>CO: POST /checkout {key: ABC, userId: 123} [1ms later]
    CO->>DB: findByIdempotencyKey(ABC)
    DB-->>CO: CheckoutIdempotencyKey(expires_at > now, but cached=null)
    CO->>TS: startWorkflow(ABC, request) [DUPLICATE ATTEMPT]
    TS-->>CO: WorkflowExecutionAlreadyStarted exception
    CO->>TS: queryWorkflow(ABC) [Get status]
    TS-->>CO: status=IN_PROGRESS
    CO-->>Client2: 202 Accepted {workflowId: ABC, status: CHECKOUT_ALREADY_IN_PROGRESS}

    Note over TS: Workflow completes...

    TS-->>CO: CheckoutResponse {orderId, status: COMPLETED}
    CO->>DB: UPDATE checkout_idempotency_keys SET checkout_response=? WHERE key=ABC
    DB-->>CO: 1 row updated

    CO-->>Client1: 200 OK {orderId, status: COMPLETED}

    Client2->>CO: GET /checkout/ABC/status
    CO-->>Client2: {status: COMPLETED, orderId: ...}
```

## Circuit Breaker Activation

```mermaid
sequenceDiagram
    participant CO as checkout-orchestrator
    participant CB as Resilience4j<br/>CircuitBreaker
    participant Cart as cart-service
    participant Workflow as Temporal

    Note over CB: CLOSED state (healthy)

    loop 10 calls with failures
        Workflow->>CB: validateCart()
        CB->>Cart: GET /cart/{id}
        Cart-->>CB: 503 Service Unavailable
        CB-->>Workflow: RestClientException
    end

    Note over CB: Failure count = 10<br/>Failure rate = 100% > 50% threshold<br/>State transition...

    Workflow->>CB: validateCart() [call #11]
    CB-->>Workflow: CallNotPermittedException<br/>(OPEN state)
    Workflow-->>CO: Activity failed

    Note over CB: waitDurationInOpenState = 30s<br/>System waiting...

    Note over CB: 30 seconds pass<br/>State transition...

    Workflow->>CB: validateCart() [call #12]
    CB->>Cart: GET /cart/{id} [HALF_OPEN - trial call]
    Cart-->>CB: 200 OK
    CB-->>Workflow: Success

    Note over CB: permittedNumberOfCallsInHalfOpenState = 5<br/>Reset sliding window<br/>Back to CLOSED
```

## Payment Authorization with Retries

```mermaid
sequenceDiagram
    participant Workflow as Temporal Workflow
    participant Resilience as Resilience4j<br/>Retry
    participant Payment as payment-service

    Note over Resilience: maxAttempts: 3, waitDuration: 1s

    Workflow->>Resilience: authorizePayment(request)

    Resilience->>Payment: POST /payment/authorize
    Payment-->>Resilience: IOException (network timeout)
    Resilience-->>Workflow: retry attempt 1/3

    Note over Resilience: Wait 1 second...

    Resilience->>Payment: POST /payment/authorize [retry 2]
    Payment-->>Resilience: 500 Internal Server Error
    Resilience-->>Workflow: retry attempt 2/3

    Note over Resilience: Wait 1 second...

    Resilience->>Payment: POST /payment/authorize [retry 3]
    Payment-->>Resilience: 200 OK {authId: PAY-123}
    Resilience-->>Workflow: Success
    Workflow->>Workflow: Continue to next activity
```
