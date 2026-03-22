# Order Service - Flowcharts

## Order Creation Flow

```mermaid
flowchart TD
    A["🌐 POST /workflow/orders<br/>CreateOrderCommand"]
    B["Extract JWT from header"]
    C{{"JWT<br/>valid?"}}
    D["Parse CreateOrderCommand"]
    E["Validate request body<br/>(items, amounts, storeId)"]
    F{{"Validation<br/>passed?"}}
    G["Check idempotency key<br/>in orders table"]
    H{{"Duplicate<br/>found?"}}
    I["Return existing order ID<br/>(idempotent response)"]
    J{{"Quote ID<br/>provided?"}}
    K["Validate pricing quote<br/>(PricingQuoteClient)"]
    L{{"Quote<br/>valid?"}}
    M["❌ Return 400<br/>Price quote validation failed"]
    N["Create Order entity<br/>status = PENDING"]
    O["Create OrderItem entities<br/>for each cart item"]
    P["Calculate line totals<br/>qty × unitPriceCents"]
    Q["Save Order + Items<br/>(PostgreSQL transaction)"]
    R["Record status history<br/>null → PENDING"]
    S["Publish OrderCreated event<br/>(OutboxService)"]
    T["✅ Return 201 Created<br/>{orderId: UUID}"]

    Err1["❌ Return 401<br/>Unauthorized"]
    Err2["❌ Return 400<br/>Bad Request"]

    A --> B --> C
    C -->|No| Err1
    C -->|Yes| D
    D --> E --> F
    F -->|No| Err2
    F -->|Yes| G --> H
    H -->|Yes| I
    H -->|No| J
    J -->|Yes| K --> L
    J -->|No| N
    L -->|No| M
    L -->|Yes| N
    N --> O --> P --> Q --> R --> S --> T

    style T fill:#7ED321,color:#000,stroke:#333,stroke-width:2px
    style I fill:#52C41A,color:#fff
    style Err1 fill:#FF6B6B,color:#fff
    style Err2 fill:#FF6B6B,color:#fff
    style M fill:#FF6B6B,color:#fff
    style S fill:#50E3C2,color:#000
```

## Order Cancellation Flow (User-Initiated)

```mermaid
flowchart TD
    A["🌐 POST /orders/{id}/cancel<br/>{reason: string}"]
    B["Extract user ID from JWT"]
    C["Fetch order by ID + userId<br/>(ownership check)"]
    D{{"Order<br/>found?"}}
    E["❌ Return 404<br/>Order not found"]
    F["Get current status"]
    G{{"Status ==<br/>CANCELLED?"}}
    H["✅ Return 204<br/>(already cancelled)"]
    I{{"Status ∈<br/>{PENDING, PLACED}?"}}
    J["❌ Return 409 Conflict<br/>User cancellation only allowed<br/>before packing starts"]
    K["Validate state transition<br/>OrderStateMachine.validate"]
    L["Set status = CANCELLED"]
    M["Set cancellation_reason"]
    N["Save order"]
    O["Record status history<br/>previous → CANCELLED"]
    P["Log audit entry<br/>ORDER_CANCELLED"]
    Q["Publish OrderCancelled event<br/>(triggers refund via Kafka)"]
    R["✅ Return 204 No Content"]

    A --> B --> C --> D
    D -->|No| E
    D -->|Yes| F --> G
    G -->|Yes| H
    G -->|No| I
    I -->|No| J
    I -->|Yes| K --> L --> M --> N --> O --> P --> Q --> R

    style R fill:#7ED321,color:#000,stroke:#333,stroke-width:2px
    style H fill:#52C41A,color:#fff
    style E fill:#FF6B6B,color:#fff
    style J fill:#FF6B6B,color:#fff
    style Q fill:#FF6B6B,color:#000
```

## Order Cancellation Flow (Admin/System)

```mermaid
flowchart TD
    A["🌐 Admin/System Cancel<br/>cancelOrder(orderId, reason, changedBy)"]
    B["Fetch order by ID"]
    C{{"Order<br/>found?"}}
    D["❌ Throw OrderNotFoundException"]
    E["Get current status"]
    F{{"Status ==<br/>CANCELLED?"}}
    G["✅ Return early<br/>(idempotent)"]
    H["Validate state transition<br/>from current → CANCELLED"]
    I{{"Transition<br/>allowed?"}}
    J["❌ Throw InvalidOrderStateException"]
    K["Set status = CANCELLED"]
    L["Set cancellation_reason"]
    M["Save order"]
    N["Record status history"]
    O["Log audit entry"]
    P["Publish OrderCancelled event"]
    Q["✅ Complete"]

    A --> B --> C
    C -->|No| D
    C -->|Yes| E --> F
    F -->|Yes| G
    F -->|No| H --> I
    I -->|No| J
    I -->|Yes| K --> L --> M --> N --> O --> P --> Q

    style Q fill:#7ED321,color:#000,stroke:#333,stroke-width:2px
    style G fill:#52C41A,color:#fff
    style D fill:#FF6B6B,color:#fff
    style J fill:#FF6B6B,color:#fff
```

## Order Status Update Flow

```mermaid
flowchart TD
    A["updateOrderStatus<br/>(orderId, newStatus, changedBy, note)"]
    B["Fetch order by ID"]
    C{{"Order<br/>found?"}}
    D["❌ Throw OrderNotFoundException"]
    E["Get current status"]
    F{{"current ==<br/>newStatus?"}}
    G["✅ Return early<br/>(no-op)"]
    H["Validate state transition<br/>OrderStateMachine.validate(current, new)"]
    I{{"Transition<br/>allowed?"}}
    J["❌ Throw InvalidOrderStateException"]
    K["Update order.status = newStatus"]
    L["Save order"]
    M["Record status history<br/>from → to"]
    N{{"newStatus ==<br/>PLACED?"}}
    O["Log audit: ORDER_PLACED"]
    P["Publish OrderPlaced event<br/>(full order payload)"]
    Q{{"newStatus ==<br/>CANCELLED?"}}
    R["Log audit: ORDER_CANCELLED"]
    S["Publish OrderStatusChanged event"]
    T["✅ Complete"]

    A --> B --> C
    C -->|No| D
    C -->|Yes| E --> F
    F -->|Yes| G
    F -->|No| H --> I
    I -->|No| J
    I -->|Yes| K --> L --> M --> N
    N -->|Yes| O --> P --> S
    N -->|No| Q
    Q -->|Yes| R --> S
    Q -->|No| S --> T

    style T fill:#7ED321,color:#000,stroke:#333,stroke-width:2px
    style G fill:#52C41A,color:#fff
    style D fill:#FF6B6B,color:#fff
    style J fill:#FF6B6B,color:#fff
    style P fill:#50E3C2,color:#000
    style S fill:#50E3C2,color:#000
```

## Fulfillment Event Processing Flow

```mermaid
flowchart TD
    A["Kafka Consumer<br/>receives fulfillment event"]
    B["Parse event payload<br/>{orderId, targetStatus}"]
    C["advanceLifecycleFromFulfillment<br/>(orderId, targetStatus, changedBy, note)"]
    D["Fetch order by ID"]
    E{{"Order<br/>found?"}}
    F["❌ Throw OrderNotFoundException"]
    G["Get current status"]
    H{{"current ∈<br/>{CANCELLED, FAILED,<br/>DELIVERED}?"}}
    I["Log: Ignoring event<br/>for terminal state"]
    J{{"current ==<br/>PENDING?"}}
    K["❌ Throw InvalidOrderStateException<br/>Cannot apply fulfillment event<br/>while PENDING"]
    L["Check if already at<br/>or past target status"]
    M{{"Already at<br/>target?"}}
    N["Log: Ignoring stale event"]
    O["Calculate progression path<br/>to target status"]
    P["For each status in path:<br/>call updateOrderStatus"]
    Q["✅ Order advanced to target"]

    A --> B --> C --> D --> E
    E -->|No| F
    E -->|Yes| G --> H
    H -->|Yes| I
    H -->|No| J
    J -->|Yes| K
    J -->|No| L --> M
    M -->|Yes| N
    M -->|No| O --> P --> Q

    style Q fill:#7ED321,color:#000,stroke:#333,stroke-width:2px
    style I fill:#F5A623,color:#000
    style N fill:#F5A623,color:#000
    style F fill:#FF6B6B,color:#fff
    style K fill:#FF6B6B,color:#fff
```

## Get Order Details Flow

```mermaid
flowchart TD
    A["🌐 GET /orders/{id}"]
    B["Extract principal from JWT"]
    C["Check if user has ADMIN role"]
    D{{"Is<br/>Admin?"}}
    E["Fetch order by ID only<br/>(admin can see all)"]
    F["Fetch order by ID + userId<br/>(ownership check)"]
    G{{"Order<br/>found?"}}
    H["❌ Return 404<br/>Order not found"]
    I["Fetch status history<br/>ordered by createdAt ASC"]
    J["Map Order + History<br/>to OrderResponse DTO"]
    K["Include:<br/>- order details<br/>- items list<br/>- status timeline<br/>- delivery info"]
    L["✅ Return 200 OK<br/>with OrderResponse"]

    A --> B --> C --> D
    D -->|Yes| E --> G
    D -->|No| F --> G
    G -->|No| H
    G -->|Yes| I --> J --> K --> L

    style L fill:#7ED321,color:#000,stroke:#333,stroke-width:2px
    style H fill:#FF6B6B,color:#fff
```

## List Orders Flow (Pagination)

```mermaid
flowchart TD
    A["🌐 GET /orders<br/>?page=0&size=20&sort=createdAt,desc"]
    B["Extract userId from JWT"]
    C["Sanitize pagination params<br/>max size = 100"]
    D["Query OrderRepository<br/>findByUserId(userId, pageable)"]
    E["Map results to<br/>OrderSummaryResponse DTOs"]
    F["Build Page response<br/>- content: List<OrderSummary><br/>- totalElements<br/>- totalPages<br/>- currentPage"]
    G["✅ Return 200 OK<br/>with paginated results"]

    A --> B --> C --> D --> E --> F --> G

    style G fill:#7ED321,color:#000,stroke:#333,stroke-width:2px
```

## Idempotency Check Flow

```mermaid
flowchart TD
    A["createOrder(command)"]
    B["Extract idempotencyKey<br/>from command"]
    C["Query: findByIdempotencyKey<br/>(idempotencyKey)"]
    D{{"Existing<br/>order found?"}}
    E["Return existing order.id<br/>(idempotent response)"]
    F["Proceed with<br/>order creation"]
    G["Save new order with<br/>idempotencyKey (unique)"]
    H["Return new order.id"]

    A --> B --> C --> D
    D -->|Yes| E
    D -->|No| F --> G --> H

    note1["UNIQUE constraint on<br/>idempotency_key column<br/>prevents race conditions"]
    G -.-> note1

    style E fill:#52C41A,color:#fff
    style H fill:#7ED321,color:#000
```

## Pricing Quote Validation Flow

```mermaid
flowchart TD
    A["Order creation with<br/>quoteId + quoteToken"]
    B{{"quoteId<br/>provided?"}}
    C["Skip quote validation<br/>(legacy flow)"]
    D["Call PricingQuoteClient<br/>.validateQuote()"]
    E["Send: quoteId, quoteToken,<br/>totalCents, subtotalCents,<br/>discountCents"]
    F["Pricing Service validates:<br/>- Quote not expired<br/>- Token matches<br/>- Amounts match quote"]
    G{{"Quote<br/>valid?"}}
    H["✅ Proceed with<br/>order creation"]
    I["❌ Throw IllegalStateException<br/>Price quote validation failed"]

    A --> B
    B -->|No| C --> H
    B -->|Yes| D --> E --> F --> G
    G -->|Yes| H
    G -->|No| I

    style H fill:#7ED321,color:#000,stroke:#333,stroke-width:2px
    style I fill:#FF6B6B,color:#fff
```
