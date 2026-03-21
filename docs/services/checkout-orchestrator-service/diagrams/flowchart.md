# Checkout Orchestrator Service - Request/Response Flows

## Happy Path: Successful Checkout

```mermaid
flowchart TD
    A["Client: POST /checkout<br/>(with Idempotency-Key)"]
    B["Controller: Validate JWT"]
    C{"Idempotency Key<br/>in cache?"}
    D["Return cached<br/>response 200 OK"]
    E["Check not expired"]
    F{"Expired?"}
    G["Delete expired<br/>entry"]
    H["Start Temporal<br/>Workflow"]
    I["Execute Cart<br/>Activity"]
    J["Execute Inventory<br/>Activity"]
    K["Execute Payment<br/>Activity"]
    L["Execute Pricing<br/>Activity"]
    M["Execute Order<br/>Activity"]
    N["Cache response<br/>in DB"]
    O["Return 200 OK<br/>with OrderId"]

    A --> B
    B --> C
    C -->|YES| E
    E --> F
    F -->|YES| G
    G --> H
    F -->|NO| D
    C -->|NO| H
    H --> I
    I --> J
    J --> K
    K --> L
    L --> M
    M --> N
    N --> O

    style O fill:#90EE90
    style H fill:#FFE5B4
```

## Error Paths

### Path 1: Duplicate Workflow (WorkflowExecutionAlreadyStarted)

```mermaid
flowchart TD
    A["Same Idempotency-Key<br/>arrives within<br/>workflow timeout"]
    B["First request still<br/>in progress"]
    C["WorkflowExecutionAlreadyStarted<br/>exception thrown"]
    D["Query workflow status"]
    E["Return 202 Accepted<br/>with existing workflowId"]

    A --> B
    B --> C
    C --> D
    D --> E

    style E fill:#FFD700
```

### Path 2: Circuit Breaker Open

```mermaid
flowchart TD
    A["Upstream service<br/>failure rate > 50%"]
    B["Circuit Breaker<br/>enters OPEN state"]
    C["waitDurationInOpenState<br/>= 30 seconds"]
    D["Incoming request<br/>to that service"]
    E["Immediate rejection<br/>CallNotPermittedException"]
    F["Return 503<br/>Service Unavailable"]

    A --> B
    B --> C
    C --> D
    D --> E
    E --> F

    style F fill:#FF6B6B
```

### Path 3: Timeout (5 minute execution window)

```mermaid
flowchart TD
    A["Workflow execution<br/>start"]
    B["Activity execution<br/>in progress"]
    C["300 seconds elapsed<br/>(5 minutes)"]
    D["WorkflowExecutionTimeout<br/>exception"]
    E["Compensation logic<br/>(if any)"]
    F["Return 504<br/>Gateway Timeout"]

    A --> B
    B --> C
    C --> D
    D --> E
    E --> F

    style F fill:#FF6B6B
```

## Failure Recovery

```mermaid
flowchart TD
    A["Activity fails<br/>(e.g., IOException)"]
    B["Resilience4j Retry<br/>maxAttempts: 3"]
    C{"Retry<br/>successful?"}
    D["Continue workflow"]
    E{"After 3 attempts,<br/>still failing?"}
    F["Circuit breaker<br/>tracks failure"]
    G["Workflow fails<br/>with error"]

    A --> B
    B --> C
    C -->|YES| D
    C -->|NO| E
    E -->|Failure rate > 50%| F
    E -->|YES| G
    F --> G

    style G fill:#FF6B6B
```

## Concurrent Request Scenario

```mermaid
sequenceDiagram
    participant Client1 as Client 1
    participant Client2 as Client 2
    participant Controller as CheckoutController
    participant DB as PostgreSQL
    participant Workflow as Temporal Workflow

    Client1->>Controller: POST /checkout<br/>(idempotencyKey: ABC)
    Client2->>Controller: POST /checkout<br/>(idempotencyKey: ABC)
    par Parallel Processing
        Controller->>DB: findByIdempotencyKey(ABC)
        Controller->>DB: findByIdempotencyKey(ABC)
    end
    DB-->>Controller: null (not found)
    DB-->>Controller: null (not found)
    par Start Workflows
        Controller->>Workflow: startWorkflow (ABC-1)
        Controller->>Workflow: startWorkflow (ABC-2)
    end
    note over Workflow: Both workflows execute in parallel<br/>Cache will store response from first completion
```
