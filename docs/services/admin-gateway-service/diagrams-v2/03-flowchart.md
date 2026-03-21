# Admin Gateway - Request Flowcharts

## Complete JWT Authentication & Authorization Flow

```mermaid
flowchart TD
    A["🌐 HTTP Request<br/>GET /api/admin/dashboard"]
    B["Extract Authorization<br/>header value"]
    C{{"Authorization<br/>header<br/>present?"}}"
    D{{"Starts with<br/>Bearer ?"}}
    E["Extract JWT token<br/>(remove 'Bearer ' prefix)"]
    F["Parse JWT header<br/>(decode base64)"]
    G{{"Extract kid<br/>present in<br/>header?"}}"
    H["Lookup kid in JWKS<br/>(check 5-min cache)"]
    I{{"kid found<br/>in JWKS?"}}"
    J["Fetch public key<br/>from JWKS entry"]
    K["Verify RS256 signature<br/>(using public key)"]
    L{{"Signature<br/>valid?"}}"
    M["Parse JWT payload<br/>(decode base64)"]
    N["Extract aud claim"]
    O{{"aud ==<br/>instacommerce-admin?"}}"
    P["Extract exp claim<br/>(expiration time)"]
    Q{{"exp > now()?"}}"
    R["Extract roles array"]
    S{{"ROLE_ADMIN<br/>in roles?"}}"
    T["✅ Set SecurityContext<br/>(principal, authorities)"]
    U["Log: JWT_VALIDATED_SUCCESS"]
    V["Check rate limit<br/>(per user_id)"]
    W{{"Rate limit<br/>exceeded?"}}"
    X["Route to controller"]
    Y["Execute endpoint handler"]
    Z["Return 200 OK"]

    Err1["❌ Return 401<br/>(Missing header)"]
    Err2["❌ Return 401<br/>(Invalid format)"]
    Err3["❌ Return 401<br/>(kid not in JWKS)"]
    Err4["❌ Return 401<br/>(Signature invalid)"]
    Err5["❌ Return 403<br/>(Wrong audience)"]
    Err6["❌ Return 401<br/>(Token expired)"]
    Err7["❌ Return 403<br/>(No ADMIN role)"]
    Err8["❌ Return 429<br/>(Rate limited)"]

    A --> B --> C
    C -->|No| Err1
    C -->|Yes| D
    D -->|No| Err2
    D -->|Yes| E
    E --> F --> G
    G -->|No| Err3
    G -->|Yes| H
    H --> I
    I -->|No| Err3
    I -->|Yes| J
    J --> K --> L
    L -->|No| Err4
    L -->|Yes| M
    M --> N --> O
    O -->|No| Err5
    O -->|Yes| P
    P --> Q
    Q -->|No| Err6
    Q -->|Yes| R
    R --> S
    S -->|No| Err7
    S -->|Yes| T
    T --> U
    U --> V --> W
    W -->|Yes| Err8
    W -->|No| X
    X --> Y --> Z

    style T fill:#7ED321,color:#000,stroke:#333,stroke-width:2px
    style Z fill:#7ED321,color:#000,stroke:#333,stroke-width:2px
    style Err1 fill:#FF6B6B,color:#fff
    style Err2 fill:#FF6B6B,color:#fff
    style Err3 fill:#FF6B6B,color:#fff
    style Err4 fill:#FF6B6B,color:#fff
    style Err5 fill:#FF6B6B,color:#fff
    style Err6 fill:#FF6B6B,color:#fff
    style Err7 fill:#FF6B6B,color:#fff
    style Err8 fill:#FF6B6B,color:#fff
```

## Dashboard Aggregation Query Flowchart

```mermaid
flowchart TD
    A["Client: GET /api/admin/dashboard"]
    B["✅ JWT validated & authorized"]
    C["Check Redis cache<br/>Key: dashboard_summary"]
    D{{"Cache<br/>hit?"}}"
    E["Return cached response<br/>(TTL: 5 min)"]
    F["Cache miss - fetch from services"]
    G["🚀 Launch 3 parallel gRPC calls:<br/>1. PaymentService.GetPaymentStats<br/>2. FulfillmentService.GetFulfillmentMetrics<br/>3. OrderService.GetOrderStats"]
    H["Wait for all 3 to complete<br/>(max timeout: 300ms per call)"]
    I{{"All calls<br/>succeeded?"}}"
    J["Aggregate responses<br/>into DashboardDTO"]
    K["Calculate metrics:<br/>- Payment SLO %<br/>- Fulfillment avg latency<br/>- Order p99"]
    L["Cache response<br/>(TTL: 5 min)"]
    M["Build JSON response"]
    N["Return 200 OK<br/>with dashboard data"]

    ErrTimeout["⏱️ Timeout on service call"]
    ErrFallback["Use last cached response<br/>or default values"]
    ErrLog["Log: DASHBOARD_PARTIAL_FAILURE"]
    ErrReturn["Return 206 Partial Content<br/>or 200 with degraded data"]

    A --> B --> C
    C --> D
    D -->|Yes| E --> N
    D -->|No| F
    F --> G
    G --> H --> I
    I -->|Yes| J
    I -->|No| ErrTimeout
    J --> K --> L --> M --> N
    ErrTimeout --> ErrFallback
    ErrFallback --> ErrLog
    ErrLog --> ErrReturn

    style E fill:#52C41A,color:#fff,stroke:#333,stroke-width:2px
    style N fill:#7ED321,color:#000,stroke:#333,stroke-width:2px
    style J fill:#4A90E2,color:#fff
    style ErrReturn fill:#F5A623,color:#000,stroke:#333,stroke-width:2px
```

## Feature Flags Query Flowchart

```mermaid
flowchart TD
    A["Client: GET /api/admin/flags"]
    B["✅ JWT validated & authorized"]
    C["Check Redis cache<br/>Key: admin:flags:active"]
    D{{"Cache<br/>hit?"}}"
    E["Return cached flags<br/>(TTL: 30s)<br/>Include ETag header"]
    F["Cache miss<br/>- fetch from Flag Service"]
    G["Call FlagService REST<br/>GET /flags?status=active"]
    H{{"Call<br/>successful?"}}"
    I["Transform response<br/>to FeatureFlagDTO list"]
    J["Filter by<br/>admin-queryable flags only"]
    K["Sort by name ASC"]
    L["Cache for 30s<br/>(Redis)"]
    M["Add metadata:<br/>- cached: false<br/>- timestamp: now<br/>- ttl_remaining: 30"]
    N["Return 200 OK<br/>with flags list"]

    ErrService["❌ Flag Service down"]
    ErrFallback["Return cached flags<br/>or empty list"]
    ErrDegrade["Set cached: true<br/>+ warning: stale_data"]

    A --> B --> C
    C --> D
    D -->|Yes, fresh| E --> N
    D -->|No| F
    F --> G --> H
    H -->|Yes| I
    H -->|No| ErrService
    I --> J --> K --> L --> M --> N
    ErrService --> ErrFallback
    ErrFallback --> ErrDegrade

    style E fill:#52C41A,color:#fff,stroke:#333,stroke-width:2px
    style N fill:#7ED321,color:#000,stroke:#333,stroke-width:2px
    style L fill:#F5A623,color:#000
    style ErrDegrade fill:#F5A623,color:#000
```

## Reconciliation Runs Query Flowchart

```mermaid
flowchart TD
    A["Client: GET /api/admin/reconciliation<br/>?days=7&page=1"]
    B["✅ JWT validated & authorized"]
    C["Validate query params:<br/>- days: 1-90 (default 7)<br/>- page: >= 1<br/>- limit: 1-100 (default 20)"]
    D{{"Params<br/>valid?"}}"
    E["❌ Return 400<br/>Bad Request"]
    F["Calculate date range<br/>from_date = now - days"]
    G["Check cache<br/>Key: reconciliation:runs:{from_date}:{page}"]
    H{{"Cache<br/>hit?"}}"
    I["Return cached runs<br/>(TTL: 10 min)"]
    J["Call Reconciliation Service<br/>(gRPC)<br/>ListReconciliationRuns(from_date, to_date, page, limit)"]
    K{{"Call<br/>succeeded?"}}"
    L["Process response:<br/>- Filter by status<br/>- Calculate metrics:<br/>  * success_rate = successful/total<br/>  * avg_duration_ms<br/>  * auto_fix_rate"]
    M["Pagination:<br/>- total_count<br/>- current_page<br/>- has_next"]
    N["Sort by timestamp DESC"]
    O["Cache for 10 min"]
    P["Build response DTO"]
    Q["Return 200 OK<br/>with runs list"]

    ErrService["⚠️ Reconciliation Service timeout"]
    ErrFallback["Retry with circuit breaker"]
    ErrGraceful["Return 503 or cached data"]

    A --> B --> C --> D
    D -->|No| E
    D -->|Yes| F
    F --> G --> H
    H -->|Yes| I --> Q
    H -->|No| J --> K
    K -->|Yes| L
    K -->|No| ErrService
    L --> M --> N --> O --> P --> Q
    ErrService --> ErrFallback --> ErrGraceful

    style Q fill:#7ED321,color:#000,stroke:#333,stroke-width:2px
    style I fill:#52C41A,color:#fff,stroke:#333,stroke-width:2px
    style E fill:#FF6B6B,color:#fff
    style ErrGraceful fill:#F5A623,color:#000
```

## Payment Status Query Flowchart

```mermaid
flowchart TD
    A["Client: GET /api/admin/payments<br/>?status=completed&limit=50"]
    B["✅ JWT validated & authorized"]
    C["Validate status param:<br/>allowed: completed, failed,<br/>pending, all"]
    D{{"Status<br/>valid?"}}"
    E["❌ Return 400<br/>Bad Request"]
    F["Check Redis cache<br/>Key: admin:payments:{status}"]
    G{{"Cache<br/>hit?"}}"
    H["Return cached stats<br/>(TTL: 5 min)"]
    I["Call Payment Service (gRPC)<br/>GetPaymentStats(status, limit=50)"]
    J{{"Call<br/>succeeded?"}}"
    K["Aggregate response:<br/>- total_revenue<br/>- transaction_count<br/>- error_rate %<br/>- avg_latency_ms<br/>- p99_latency_ms"]
    L["Categorize by payment method:<br/>- card<br/>- upi<br/>- wallet"]
    M["Add time-series data:<br/>(last 24 hours by hour)"]
    N["Cache for 5 min"]
    O["Build PaymentStatsDTO"]
    P["Return 200 OK<br/>with payment stats"]

    ErrService["⚠️ Payment Service timeout<br/>or error"]
    ErrFallback["Return stale cached data<br/>or aggregate from local metrics"]
    ErrDegrade["Include warning:<br/>data may be stale"]

    A --> B --> C --> D
    D -->|No| E
    D -->|Yes| F --> G
    G -->|Yes| H --> P
    G -->|No| I --> J
    J -->|Yes| K
    J -->|No| ErrService
    K --> L --> M --> N --> O --> P
    ErrService --> ErrFallback --> ErrDegrade

    style P fill:#7ED321,color:#000,stroke:#333,stroke-width:2px
    style H fill:#52C41A,color:#fff,stroke:#333,stroke-width:2px
    style K fill:#4A90E2,color:#fff
    style ErrDegrade fill:#F5A623,color:#000
```
