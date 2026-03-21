# Order Service - Request/Response Flows

## Order Creation Flow (Checkout Saga)

```mermaid
flowchart TD
    A["Checkout Orchestrator<br/>Temporal Activity"]
    B["Call POST /orders<br/>(via RestTemplate)"]
    C["OrderController<br/>validateAuth"]
    D["OrderService<br/>createOrder"]
    E["Persist Order<br/>+ OrderItems<br/>+ OutboxEvent<br/>(1 transaction)"]
    F{"Transaction<br/>success?"}
    G["Rollback"]
    H["Return 201 Created<br/>with OrderId"]
    I["CDC detects outbox<br/>row insert"]
    J["Publish orders.events<br/>to Kafka"]
    K["Fulfillment consumer<br/>receives OrderCreated"]
    L["Start picking"]

    A --> B
    B --> C
    C --> D
    D --> E
    E --> F
    F -->|YES| H
    F -->|NO| G
    H --> I
    I --> J
    J --> K
    K --> L

    style H fill:#90EE90
    style L fill:#90EE90
    style G fill:#FF6B6B
```

## Order Query Flow (Customer)

```mermaid
flowchart TD
    A["Customer: GET /orders/{id}"]
    B["OrderController"]
    C["Validate JWT<br/>Extract principal"]
    D{"principal ==<br/>order.user_id?"}
    E["Return 403<br/>Forbidden"]
    F["Query from DB"]
    G["Return 200<br/>OrderResponse"]

    A --> B
    B --> C
    C --> D
    D -->|NO| E
    D -->|YES| F
    F --> G

    style G fill:#90EE90
    style E fill:#FF6B6B
```

## Order Cancellation Flow

```mermaid
flowchart TD
    A["Customer: POST /orders/{id}/cancel"]
    B["Validate JWT"]
    C["Load order"]
    D{"Status in<br/>PENDING, PLACED?"}
    E["Return 400<br/>Cannot cancel"]
    F["Update status<br/>to CANCELLED"]
    G["Publish<br/>OrderCancelled event"]
    H["CDC captures"]
    I["Fulfillment receives<br/>cancellation"]
    J["Release inventory<br/>reservation"]

    A --> B
    B --> C
    C --> D
    D -->|NO| E
    D -->|YES| F
    F --> G
    G --> H
    H --> I
    I --> J

    style J fill:#90EE90
    style E fill:#FF6B6B
```

## Outbox/CDC Flow

```mermaid
flowchart TD
    A["Order persisted<br/>to DB"]
    B["Outbox row inserted<br/>same transaction"]
    C["Flyway migration<br/>creates trigger"]
    D["Debezium connector<br/>polls outbox"]
    E{"New unsent<br/>rows?"}
    F["Batch publish<br/>to Kafka"]
    G["Update sent = true"]
    H["Kafka broker<br/>persists"]
    I["Consumer groups<br/>receive events"]

    A --> B
    B --> C
    C --> D
    D --> E
    E -->|YES| F
    F --> G
    G --> H
    H --> I
    E -->|NO| D

    style I fill:#90EE90
```

## Failure Scenarios

### Scenario: Duplicate Order Creation

```mermaid
flowchart TD
    A["First checkout<br/>POST /orders"]
    B["Check idempotency_key<br/>constraint"]
    C["Persist order"]
    D["Second checkout<br/>same idempotency_key"]
    E["UNIQUE constraint<br/>violation"]
    F["Return 409 Conflict<br/>or 200 if cache hit"]

    A --> B
    B --> C
    C --> D
    D --> E
    E --> F

    style F fill:#FFD700
```

### Scenario: Cancellation After Packing

```mermaid
flowchart TD
    A["Customer: POST /orders/{id}/cancel"]
    B["Check order.status"]
    C{"Status ==<br/>PACKED+?"}
    D["Return 400<br/>Too late to cancel"]
    E["Allow cancellation<br/>but marked soft constraint"]

    A --> B
    B --> C
    C -->|YES| D
    C -->|NO| E

    style D fill:#FF6B6B
    style E fill:#90EE90
```
