# Dispatch Optimizer Service - All 7 Diagrams

## 1. High-Level Design

```mermaid
graph TB
    Order["📦 Order"]
    Dispatcher["🎯 Dispatch Optimizer<br/>(Go + ML)"]
    RiderSvc["🏍️ Rider Fleet Service"]
    LocationSvc["📍 Location Service"]
    ML["🤖 ML Model Store"]
    MetricsSvc["📊 Stream Processor"]

    Order -->|Assign rider| Dispatcher
    Dispatcher -->|Get available riders| RiderSvc
    Dispatcher -->|Get locations| LocationSvc
    Dispatcher -->|Load model| ML
    Dispatcher -->|Publish assignments| MetricsSvc

    style Dispatcher fill:#4A90E2,color:#fff
    style ML fill:#F5A623,color:#000
```

## 2. Low-Level Design

```mermaid
graph TD
    Request["POST /dispatch/optimize<br/>{orders[]}"]
    Validate["Validate order IDs"]
    FetchRiders["Query available riders<br/>status=ACTIVE, load < 5"]
    FetchLocations["Get rider locations<br/>from Redis GEO"]
    CalculateDistances["Haversine distances<br/>per order-rider pair"]
    LoadMLModel["Load assignment model<br/>from Redis cache"]
    RunOptimizer["Run ML inference<br/>Predict best assignments"]
    CalculateScore["Assign confidence scores<br/>0-1 range"]
    SelectAssignments["Greedy selection<br/>by score"]
    PersistAssignments["Save to database"]
    PublishEvent["Publish AssignmentCompleted"]
    Response["Return assignments"]

    Request --> Validate
    Validate --> FetchRiders
    FetchRiders --> FetchLocations
    FetchLocations --> CalculateDistances
    CalculateDistances --> LoadMLModel
    LoadMLModel --> RunOptimizer
    RunOptimizer --> CalculateScore
    CalculateScore --> SelectAssignments
    SelectAssignments --> PersistAssignments
    PersistAssignments --> PublishEvent
    PublishEvent --> Response

    style RunOptimizer fill:#7ED321,color:#000
    style CalculateScore fill:#7ED321,color:#000
```

## 3. Flowchart - Rider Assignment

```mermaid
flowchart TD
    A["Request: Optimize<br/>10 pending orders"]
    B["Fetch available riders<br/>(status=ACTIVE)"]
    C{{"Riders >= orders?"}}
    D["Get rider locations"]
    E["Calculate distance<br/>matrix (10 x N)"]
    F["Get rider load factors<br/>(0-1, current capacity)"]
    G["Load ML model<br/>(XGBoost)"]
    H["Run inference<br/>predict best pair"]
    I["Compute score for<br/>each assignment"]
    J["Sort by score DESC"]
    K["Greedy assignment<br/>avoid conflicts"]
    L["Persist to database"]
    M["Publish to Kafka<br/>assignments.completed"]
    N["Return 200 OK<br/>assignments"]
    O["Return 400 error<br/>insufficient riders"]

    A --> B
    B --> C
    C -->|No| O
    C -->|Yes| D
    D --> E
    E --> F
    F --> G
    G --> H
    H --> I
    I --> J
    J --> K
    K --> L
    L --> M
    M --> N

    style A fill:#4A90E2,color:#fff
    style H fill:#F5A623,color:#000
    style N fill:#7ED321,color:#000
```

## 4. Sequence - Assignment Flow

```mermaid
sequenceDiagram
    participant OrderSvc as Order Service
    participant Dispatcher as Dispatch Optimizer
    participant RiderSvc as Rider Fleet Service
    participant LocationCache as Redis (Rider Locations)
    participant MLCache as Redis (ML Models)
    participant DB as PostgreSQL
    participant Kafka as Kafka

    OrderSvc->>Dispatcher: POST /dispatch/optimize<br/>{order_ids: [1,2,3]}
    Dispatcher->>RiderSvc: GET /riders?status=ACTIVE
    RiderSvc-->>Dispatcher: [rider1, rider2, rider3, rider4]
    Dispatcher->>LocationCache: GEOHASH retrieval
    LocationCache-->>Dispatcher: Coordinates for each
    Dispatcher->>Dispatcher: Haversine calc<br/>all distances
    Dispatcher->>MLCache: GET model:dispatch_v2
    MLCache-->>Dispatcher: XGBoost model (ONNX)
    Dispatcher->>Dispatcher: Run inference<br/>output: scores[]
    Dispatcher->>Dispatcher: Sort & assign<br/>greedily
    Dispatcher->>DB: INSERT assignments
    DB-->>Dispatcher: OK
    Dispatcher->>Kafka: Publish AssignmentsOptimized
    Kafka-->>Dispatcher: ACK
    Dispatcher-->>OrderSvc: 200 OK {assignments}

    Note over OrderSvc,Kafka: Total: 100-200ms
```

## 5. State Machine

```mermaid
stateDiagram-v2
    [*] --> REQUEST_RECEIVED
    REQUEST_RECEIVED --> VALIDATING
    VALIDATING --> FETCHING_RIDERS
    FETCHING_RIDERS --> FETCHING_LOCATIONS
    FETCHING_LOCATIONS --> CALCULATING_DISTANCES
    CALCULATING_DISTANCES --> LOADING_MODEL
    LOADING_MODEL --> RUNNING_INFERENCE
    RUNNING_INFERENCE --> SCORING
    SCORING --> SELECTING
    SELECTING --> PERSISTING
    PERSISTING --> PUBLISHING
    PUBLISHING --> RESPONSE_SENT
    RESPONSE_SENT --> [*]

    VALIDATING --> ERROR_INVALID
    ERROR_INVALID --> [*]

    note right of RUNNING_INFERENCE
        ML model execution
        XGBoost or similar
        GPU accelerated optional
    end note

    note right of SELECTING
        Greedy algorithm
        Avoid conflicts
        Maximize total score
    end note
```

## 6. ER - Assignment & Metrics

```mermaid
erDiagram
    ASSIGNMENTS {
        uuid id PK
        uuid order_id FK
        uuid rider_id FK
        float confidence_score "0-1"
        timestamp created_at
    }

    RIDERS {
        uuid id PK
        string status "ACTIVE, BUSY, OFFLINE"
        integer current_load "0-5 deliveries"
    }

    ASSIGNMENTS ||--o{ RIDERS : assigned_to
```

## 7. End-to-End

```mermaid
graph TB
    Orders["📦 Orders<br/>(pending assignment)"]
    LB["⚖️ Load Balancer"]
    Dispatcher["🎯 Dispatch Optimizer<br/>(3 pods, 2 CPU, 4GB)"]
    Redis["⚡ Redis<br/>Locations + Models"]
    ML["🤖 TensorFlow Model<br/>dispatch_v2.pb"]
    DB["🗄️ PostgreSQL"]
    Kafka["📬 Kafka"]
    Monitoring["📊 Prometheus"]

    Orders -->|POST /optimize| LB
    LB --> Dispatcher
    Dispatcher -->|Fetch| Redis
    Dispatcher -->|Load| ML
    Dispatcher -->|Compute| Dispatcher
    Dispatcher -->|Persist| DB
    Dispatcher -->|Publish| Kafka
    Dispatcher -->|Metrics| Monitoring

    style Dispatcher fill:#4A90E2,color:#fff
    style ML fill:#F5A623,color:#000
```
