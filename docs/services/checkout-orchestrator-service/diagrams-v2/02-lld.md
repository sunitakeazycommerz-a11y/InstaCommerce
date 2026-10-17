# Checkout Orchestrator - Low-Level Design

## Component Architecture

```mermaid
graph TB
    HTTPRequest["🌐 HTTP Request\nPOST /checkout\n(JWT + Idempotency-Key)"]

    subgraph Controller["Controller Layer"]
        DispatcherServlet["DispatcherServlet"]
        JwtFilter["🔐 JwtAuthenticationFilter\n(validates JWT)"]
        CheckoutController["CheckoutController\n(@RestController)"]
        IdempotencyCheck["IdempotencyKeyRepository\n(PostgreSQL lookup)"]
    end

    subgraph Temporal["Temporal Integration"]
        WorkflowClient["WorkflowClient\n(gRPC to Temporal)"]
        WorkflowOptions["WorkflowOptions\n- workflowId\n- taskQueue\n- timeout"]
        WorkflowStub["CheckoutWorkflow stub"]
    end

    subgraph Workflow["Workflow Execution"]
        WorkflowImpl["CheckoutWorkflowImpl"]
        SagaCoordinator["Saga\n(parallelCompensation=false)"]
        StatusTracker["currentStatus\n(workflow state)"]
        PaymentKeys["PaymentOperationKeys\n(idempotency key derivation)"]
    end

    subgraph Activities["Activity Stubs"]
        CartStub["CartActivity stub\n10s timeout, 3 retries"]
        PricingStub["PricingActivity stub\n10s timeout, 3 retries"]
        InventoryStub["InventoryActivity stub\n15s timeout, 3 retries"]
        PaymentStub["PaymentActivity stub\n30s/45s timeout, 3 retries"]
        OrderStub["OrderActivity stub\n15s timeout, 3 retries"]
        CouponStub["CouponActivity stub\n10s timeout, 3 retries"]
    end

    subgraph ActivityImpls["Activity Implementations"]
        CartImpl["CartActivityImpl\n(RestTemplate)"]
        PricingImpl["PricingActivityImpl"]
        InventoryImpl["InventoryActivityImpl"]
        PaymentImpl["PaymentActivityImpl"]
        OrderImpl["OrderActivityImpl"]
        CouponImpl["CouponActivityImpl"]
    end

    HTTPRequest --> DispatcherServlet
    DispatcherServlet --> JwtFilter
    JwtFilter --> CheckoutController
    CheckoutController --> IdempotencyCheck
    CheckoutController --> WorkflowClient

    WorkflowClient --> WorkflowOptions
    WorkflowOptions --> WorkflowStub
    WorkflowStub --> WorkflowImpl

    WorkflowImpl --> SagaCoordinator
    WorkflowImpl --> StatusTracker
    WorkflowImpl --> PaymentKeys

    WorkflowImpl --> CartStub
    WorkflowImpl --> PricingStub
    WorkflowImpl --> InventoryStub
    WorkflowImpl --> PaymentStub
    WorkflowImpl --> OrderStub
    WorkflowImpl --> CouponStub

    CartStub --> CartImpl
    PricingStub --> PricingImpl
    InventoryStub --> InventoryImpl
    PaymentStub --> PaymentImpl
    OrderStub --> OrderImpl
    CouponStub --> CouponImpl

    style WorkflowImpl fill:#4f46e5,color:#fff,stroke:#312e81,stroke-width:2px
    style SagaCoordinator fill:#dc2626,color:#fff
    style CheckoutController fill:#0ea5e9,color:#fff
    style PaymentStub fill:#f59e0b,color:#000
```

## Class Diagram

```mermaid
classDiagram
    direction LR

    class CheckoutOrchestratorApplication {
        +main(String[] args)
    }

    class CheckoutController {
        -WorkflowClient workflowClient
        -String taskQueue
        -IdempotencyKeyRepository idempotencyKeyRepository
        -ObjectMapper objectMapper
        +initiateCheckout(CheckoutRequest, String, String) ResponseEntity
        +getCheckoutStatus(String workflowId) ResponseEntity
    }

    class CheckoutWorkflow {
        <<interface>>
        +checkout(CheckoutRequest) CheckoutResponse
        +getStatus() String
    }

    class CheckoutWorkflowImpl {
        -String currentStatus
        -PaymentOperationKeys paymentKeys
        -CartActivity cartActivity
        -PricingActivity pricingActivity
        -InventoryActivity inventoryActivity
        -PaymentActivity paymentActivity
        -OrderActivity orderActivity
        -CouponActivity couponActivity
        +checkout(CheckoutRequest) CheckoutResponse
        +getStatus() String
        -compensatePayment(...)
    }

    class PaymentOperationKeys {
        -String workflowId
        +authorize() String
        +capture(String paymentId) String
        +voidOp(String paymentId) String
        +refund(String paymentId) String
    }

    class CartActivity {
        <<interface>>
        +validateCart(String userId) CartValidationResult
        +clearCart(String userId)
    }

    class PricingActivity {
        <<interface>>
        +calculatePrice(PricingRequest) PricingResult
    }

    class InventoryActivity {
        <<interface>>
        +reserveStock(List~CartItem~) InventoryReservationResult
        +releaseStock(String reservationId)
        +confirmStock(String reservationId)
    }

    class PaymentActivity {
        <<interface>>
        +authorizePayment(long, String, String) PaymentAuthResult
        +capturePayment(String, String)
        +voidPayment(String, String)
        +refundPayment(String, long, String)
    }

    class OrderActivity {
        <<interface>>
        +createOrder(OrderCreateRequest) OrderCreationResult
        +cancelOrder(String orderId)
    }

    class CouponActivity {
        <<interface>>
        +redeemCoupon(String, String, String, long)
        +unredeemCoupon(String, String, String)
    }

    CheckoutController --> CheckoutWorkflow : starts via WorkflowClient
    CheckoutWorkflowImpl ..|> CheckoutWorkflow
    CheckoutWorkflowImpl --> PaymentOperationKeys
    CheckoutWorkflowImpl --> CartActivity
    CheckoutWorkflowImpl --> PricingActivity
    CheckoutWorkflowImpl --> InventoryActivity
    CheckoutWorkflowImpl --> PaymentActivity
    CheckoutWorkflowImpl --> OrderActivity
    CheckoutWorkflowImpl --> CouponActivity
```

## Activity Timeout Configuration

```mermaid
graph TB
    subgraph CartConfig["CartActivity Config"]
        Cart_STC["StartToCloseTimeout: 10s"]
        Cart_Retry["MaxAttempts: 3"]
        Cart_Backoff["Backoff: 1s × 2.0"]
        Cart_DNR["DoNotRetry: —"]
    end

    subgraph PricingConfig["PricingActivity Config"]
        Pricing_STC["StartToCloseTimeout: 10s"]
        Pricing_Retry["MaxAttempts: 3"]
        Pricing_Backoff["Backoff: 1s × 2.0"]
        Pricing_DNR["DoNotRetry: —"]
    end

    subgraph InventoryConfig["InventoryActivity Config"]
        Inv_STC["StartToCloseTimeout: 15s"]
        Inv_Retry["MaxAttempts: 3"]
        Inv_Backoff["Backoff: 1s × 2.0"]
        Inv_DNR["DoNotRetry:\nInsufficientStockException"]
    end

    subgraph PaymentConfig["PaymentActivity Config"]
        Pay_STC["StartToCloseTimeout: 30s"]
        Pay_S2C["ScheduleToCloseTimeout: 45s"]
        Pay_Retry["MaxAttempts: 3"]
        Pay_Backoff["Backoff: 2s × 2.0"]
        Pay_DNR["DoNotRetry:\nPaymentDeclinedException"]
    end

    subgraph OrderConfig["OrderActivity Config"]
        Order_STC["StartToCloseTimeout: 15s"]
        Order_Retry["MaxAttempts: 3"]
        Order_Backoff["Backoff: 1s × 2.0"]
        Order_DNR["DoNotRetry: —"]
    end

    subgraph CouponConfig["CouponActivity Config"]
        Coupon_STC["StartToCloseTimeout: 10s"]
        Coupon_Retry["MaxAttempts: 3"]
        Coupon_Backoff["Backoff: 1s × 2.0"]
        Coupon_DNR["DoNotRetry:\nInvalidCouponException\nCouponNotFoundException"]
    end

    WorkflowTimeout["Workflow Execution Timeout: 5 minutes"]

    WorkflowTimeout --> CartConfig
    WorkflowTimeout --> PricingConfig
    WorkflowTimeout --> InventoryConfig
    WorkflowTimeout --> PaymentConfig
    WorkflowTimeout --> OrderConfig
    WorkflowTimeout --> CouponConfig

    style PaymentConfig fill:#dc2626,color:#fff,stroke:#991b1b,stroke-width:2px
    style InventoryConfig fill:#f59e0b,color:#000
    style WorkflowTimeout fill:#4f46e5,color:#fff
```

## Data Types (DTOs)

```mermaid
classDiagram
    class CheckoutRequest {
        +String userId
        +String paymentMethodId
        +String couponCode
        +String deliveryAddressId
    }

    class CheckoutResponse {
        +String orderId
        +String status
        +long totalCents
        +int estimatedDeliveryMinutes
        +success() CheckoutResponse$
        +failed(String reason) CheckoutResponse$
    }

    class CartValidationResult {
        +String userId
        +List~CartItem~ items
        +boolean valid
        +String storeId
    }

    class CartItem {
        +String productId
        +int quantity
        +long unitPriceCents
    }

    class PricingRequest {
        +String userId
        +List~CartItem~ items
        +String storeId
        +String couponCode
        +String deliveryAddressId
    }

    class PricingResult {
        +long subtotalCents
        +long discountCents
        +long deliveryFeeCents
        +long totalCents
        +String currency
        +String quoteId
        +String quoteToken
    }

    class InventoryReservationResult {
        +String reservationId
        +boolean reserved
    }

    class PaymentAuthResult {
        +String paymentId
        +boolean authorized
        +String declineReason
    }

    class OrderCreateRequest {
        +String userId
        +String storeId
        +List~CartItem~ items
        +long subtotalCents
        +long discountCents
        +long deliveryFeeCents
        +long totalCents
        +String currency
        +String couponCode
        +String reservationId
        +String paymentId
        +String deliveryAddressId
        +String paymentMethodId
        +String quoteId
        +String quoteToken
    }

    class OrderCreationResult {
        +String orderId
        +int estimatedDeliveryMinutes
    }

    CartValidationResult --> CartItem
    PricingRequest --> CartItem
    OrderCreateRequest --> CartItem
```

## Temporal Worker Configuration

```mermaid
graph TB
    subgraph TemporalConfig["TemporalConfig @Configuration"]
        WfServiceStubs["@Bean workflowServiceStubs()\n→ WorkflowServiceStubs"]
        WfClient["@Bean workflowClient()\n→ WorkflowClient"]
        WorkerFactory["@Bean workerFactory()\n→ WorkerFactory"]
    end

    subgraph WorkerSetup["Worker Setup"]
        Worker["Worker\ntaskQueue: CHECKOUT_ORCHESTRATOR_TASK_QUEUE"]
        RegisterWf["registerWorkflowImplementationTypes(\nCheckoutWorkflowImpl.class)"]
        RegisterAct["registerActivitiesImplementations(\nactivityBeans...)"]
    end

    subgraph Connection["Temporal Connection"]
        Target["target: temporal:7233\n(or localhost:7233)"]
        Namespace["namespace: default"]
        TLS["TLS: optional"]
    end

    TemporalConfig --> WorkerSetup
    WorkerSetup --> Connection

    WfServiceStubs --> Target
    WfClient --> Namespace
    WorkerFactory --> Worker
    Worker --> RegisterWf
    Worker --> RegisterAct

    style Worker fill:#6366f1,color:#fff
    style TemporalConfig fill:#0ea5e9,color:#fff
```

## REST Client Configuration

```mermaid
graph TB
    subgraph RestClientConfig["RestClientConfig @Configuration"]
        direction LR
        CartRT["@Bean cartRestTemplate()"]
        PricingRT["@Bean pricingRestTemplate()"]
        InventoryRT["@Bean inventoryRestTemplate()"]
        PaymentRT["@Bean paymentRestTemplate()"]
        OrderRT["@Bean orderRestTemplate()"]
    end

    subgraph ServiceURLs["Service Base URLs (from application.yml)"]
        CartURL["cart.service.url\n= http://cart-service:8084"]
        PricingURL["pricing.service.url\n= http://pricing-service:8087"]
        InventoryURL["inventory.service.url\n= http://inventory-service:8083"]
        PaymentURL["payment.service.url\n= http://payment-service:8086"]
        OrderURL["order.service.url\n= http://order-service:8085"]
    end

    subgraph CommonConfig["Common RestTemplate Config"]
        ConnTimeout["connectionTimeout: 5s"]
        ReadTimeout["readTimeout: 30s"]
        Interceptor["ServiceAuthInterceptor\n(X-Internal-Service header)"]
        ErrorHandler["ResponseErrorHandler"]
    end

    RestClientConfig --> ServiceURLs
    RestClientConfig --> CommonConfig

    CartRT --> CartURL
    PricingRT --> PricingURL
    InventoryRT --> InventoryURL
    PaymentRT --> PaymentURL
    OrderRT --> OrderURL

    style RestClientConfig fill:#0ea5e9,color:#fff
```

## Idempotency Key Derivation

```mermaid
graph TB
    ClientKey["Client Idempotency-Key\n(header)"]
    WorkflowId["workflowId =\ncheckout-{userId}-{idempotencyKey}"]

    subgraph PaymentKeys["PaymentOperationKeys"]
        AuthKey["authorize() →\nworkflowId + ':pay:auth'"]
        CaptureKey["capture(paymentId) →\nworkflowId + ':pay:' + paymentId + ':capture'"]
        VoidKey["voidOp(paymentId) →\nworkflowId + ':pay:' + paymentId + ':void'"]
        RefundKey["refund(paymentId) →\nworkflowId + ':pay:' + paymentId + ':refund'"]
    end

    subgraph Stability["Key Stability Guarantees"]
        NoActivityId["❌ Never uses activityId\n(changes on retry)"]
        WorkflowOnly["✅ Derived from workflowId only\n(stable across retries)"]
        PaymentIdPost["✅ paymentId added post-auth\n(returned by payment-service)"]
    end

    ClientKey --> WorkflowId
    WorkflowId --> PaymentKeys

    AuthKey --> Stability
    CaptureKey --> Stability
    VoidKey --> Stability
    RefundKey --> Stability

    style PaymentKeys fill:#dc2626,color:#fff,stroke:#991b1b,stroke-width:2px
    style Stability fill:#dcfce7,color:#000,stroke:#22c55e
```

## Error Handling & Exception Mapping

```mermaid
graph TB
    subgraph Exceptions["Exception Types"]
        Validation["MethodArgumentNotValidException\nConstraintViolationException\nHttpMessageNotReadableException"]
        Auth["JwtAuthenticationException"]
        Forbidden["ForbiddenException\n(JWT sub ≠ request userId)"]
        NotFound["WorkflowNotFoundException"]
        Conflict["WorkflowExecutionAlreadyStarted"]
        WorkflowFail["WorkflowException"]
        Timeout["ResourceAccessException"]
    end

    subgraph Responses["HTTP Responses"]
        R400["400 VALIDATION_ERROR"]
        R401["401 TOKEN_INVALID"]
        R403["403 FORBIDDEN"]
        R404["404 WORKFLOW_NOT_FOUND"]
        R409["409 CHECKOUT_ALREADY_IN_PROGRESS"]
        R500["500 CHECKOUT_WORKFLOW_FAILED"]
        R503["503 DOWNSTREAM_UNAVAILABLE"]
    end

    subgraph Handler["GlobalExceptionHandler"]
        TraceId["TraceIdProvider\n(MDC, X-B3-TraceId, traceparent)"]
        ErrorBody["{\n  code, message,\n  traceId, timestamp\n}"]
    end

    Validation --> R400
    Auth --> R401
    Forbidden --> R403
    NotFound --> R404
    Conflict --> R409
    WorkflowFail --> R500
    Timeout --> R503

    R400 --> Handler
    R401 --> Handler
    R403 --> Handler
    R404 --> Handler
    R409 --> Handler
    R500 --> Handler
    R503 --> Handler

    Handler --> TraceId
    Handler --> ErrorBody

    style R400 fill:#fef3c7,color:#000
    style R401 fill:#fecaca,color:#000
    style R403 fill:#fecaca,color:#000
    style R409 fill:#e0e7ff,color:#000
    style R500 fill:#dc2626,color:#fff
    style R503 fill:#f59e0b,color:#000
```

## Latency Budget Analysis

```
┌─────────────────────────────────────────────────────────────────┐
│  Checkout Workflow Latency Budget (P99 target: <5s)             │
├─────────────────────────────────────────────────────────────────┤
│ Controller overhead (JWT, idempotency check):     ~50ms         │
│ Temporal workflow start:                          ~100ms        │
│                                                                 │
│ ① Cart validation (cart-service):                 ~200ms p99    │
│ ② Pricing calculation (pricing-service):          ~300ms p99    │
│ ③ Inventory reservation (inventory-service):      ~200ms p99    │
│ ④ Payment authorization (payment-service + PSP):  ~1500ms p99   │
│ ⑤ Order creation (order-service):                 ~300ms p99    │
│ ⑥ Stock confirm + Payment capture:                ~800ms p99    │
│ ⑦ Cart clear (best effort):                       ~100ms        │
│                                                                 │
│ Temporal history persistence:                     ~100ms        │
│                                                                 │
│ TOTAL P99 (single attempt):                       ~3650ms       │
│ BUFFER (5s target):                               ~1350ms       │
│ STATUS:                                           ✅ WITHIN SLO │
│                                                                 │
│ NOTE: With retries at max backoff, worst case approaches 5min   │
│       workflow execution timeout                                │
└─────────────────────────────────────────────────────────────────┘
```
