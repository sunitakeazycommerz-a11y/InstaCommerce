# Dispatch Optimizer Service - End-to-End Flow

```mermaid
graph TB
    subgraph "1. Order Ready for Dispatch"
        A["Fulfillment Service<br/>OrderPacked event"]
        B["Order ready at store"]
        C["No rider assigned yet"]
    end

    subgraph "2. Dispatch Request"
        D["Fulfillment Service<br/>POST /assign"]
        E["Payload: order_id<br/>customer_lat, customer_long<br/>store_lat, store_long"]
    end

    subgraph "3. Candidate Query"
        F["Dispatch Optimizer<br/>receives request"]
        G["Redis GEORADIUS<br/>5km around store"]
        H["Get candidate riders<br/>nearby"]
    end

    subgraph "4. Rider Metrics"
        I["Query Rider Fleet Service<br/>GET /riders/available"]
        J["Fetch rider metrics:<br/>- current load<br/>- status<br/>- zone"]
    end

    subgraph "5. Feature Engineering"
        K["For each candidate:"]
        L["- Haversine distance"]
        M["- Current load (0-5)"]
        N["- Success rate"]
        O["- Zone balance"]
        P["- Time of day"]
    end

    subgraph "6. ML Scoring"
        Q["Load ML model"]
        R["Run inference<br/>on feature vectors"]
        S["Generate scores<br/>0.0-1.0"]
    end

    subgraph "6b. Fallback Path"
        T["ML timeout<br/>after 5s"]
        U["Fallback: greedy<br/>nearest rider"]
    end

    subgraph "7. Selection & Cache"
        V["Sort candidates<br/>by score"]
        W["Select top scorer"]
        X["Cache assignment<br/>TTL=1 hour"]
    end

    subgraph "8. Response & Notification"
        Y["HTTP 200<br/>AssignmentResponse"]
        Z["Rider Fleet Service<br/>notify rider"]
        AA["Rider accepts<br/>assignment"]
    end

    subgraph "9. Optional Rebalancing"
        AB["Periodic rebalance<br/>POST /rebalance"]
        AC["Check if reassignment<br/>would improve"]
        AD["Update if better<br/>assignment found"]
    end

    A --> B
    B --> C
    C --> D
    D --> E
    E --> F
    F --> G
    G --> H
    H --> I
    I --> J
    J --> K
    K --> L
    K --> M
    K --> N
    K --> O
    K --> P
    L --> Q
    M --> Q
    N --> Q
    O --> Q
    P --> Q
    Q --> R
    R --> S
    S --> V
    Q -->|timeout| T
    T --> U
    U -->|Score| S
    V --> W
    W --> X
    X --> Y
    Y --> Z
    Z --> AA

    AB --> AC
    AC --> AD
```

## Performance & SLA

```mermaid
timeline
    title Dispatch Optimizer Latency
    section Candidate Query
        5ms : GEORADIUS Redis query
    section Rider Metrics
        20ms : RPC to Rider Fleet Service
    section Feature Calc
        5ms : Distance calculations
        5ms : Load metrics aggregation
    section ML Inference
        50-100ms : Model scoring
    section Selection
        2ms : Sort and select
    section Response
        5ms : Serialize, return
    section Total
        ~95-140ms : P99 target <100ms
    section Fallback
        ~50-70ms : Greedy selection
```

## Assignment Lifecycle

```mermaid
graph TD
    A["UNASSIGNED"] -->|POST /assign| B["ASSIGNING"]
    B -->|ML success| C["ASSIGNED"]
    B -->|ML timeout| D["GREEDY_ASSIGN"]
    D -->|Selected| C
    C -->|Rider pickup| E["IN_DELIVERY"]
    E -->|Delivery done| F["DELIVERED"]

    C -->|POST /rebalance| G["REASSIGNING"]
    G -->|Better found| H["REASSIGNED"]
    G -->|No improvement| C
    H --> E

    C -->|Order cancelled| I["CANCELLED"]
    I --> [*]
```

## Model Refresh Strategy

```mermaid
graph TD
    A["ML Model v1<br/>Deployed"] -->|1 month| B["Collect feedback<br/>delivery outcomes"]
    B -->|Training data| C["Retrain model<br/>offline"]
    C -->|Validation| D["A/B test<br/>v1 vs v2"]
    D -->|v2 better| E["ML Model v2<br/>Deploy"]
    E -->|Replace frozen model| F["New inference<br/>scoring"]
    F --> A
    D -->|v1 better| G["Keep v1"]
    G --> A
```
