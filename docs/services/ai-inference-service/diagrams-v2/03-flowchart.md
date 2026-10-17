# AI Inference Service - Request Flowcharts

## Complete Inference Request Flow

```mermaid
flowchart TD
    A["🌐 HTTP Request<br/>POST /predict/{model}"]
    B["Parse request body<br/>(PredictionRequest)"]
    C{{"Request<br/>valid?"}}
    D["❌ Return 400<br/>ValidationError"]
    E["Extract model_name<br/>and version hint"]
    F["Check rate limit<br/>(per client, per model)"]
    G{{"Rate limit<br/>OK?"}}
    H["❌ Return 429<br/>RateLimitExceeded"]
    I["Build cache key<br/>(hash: model + features)"]
    J["Check InferenceCache<br/>(LRU, 1000 entries)"]
    K{{"Cache<br/>hit?"}}
    L["✅ Return cached prediction<br/>(~2ms)"]
    M["Resolve model version<br/>(ModelRegistry)"]
    N{{"Model<br/>exists?"}}
    O["❌ Return 404<br/>ModelNotFound"]
    P["Fetch features<br/>(FeatureStoreClient)"]
    Q{{"Features<br/>fetched?"}}
    R["Use degraded features<br/>(request-only + defaults)"]
    S["Transform features<br/>(normalize, encode)"]
    T["Validate feature schema"]
    U{{"Schema<br/>valid?"}}
    V["❌ Return 400<br/>FeatureValidationError"]
    W["Load model<br/>(from ModelCache or GCS)"]
    X{{"Model<br/>loaded?"}}
    Y["❌ Return 503<br/>ModelLoadError"]
    Z["Run ML inference<br/>(model.predict)"]
    AA{{"Inference<br/>succeeded?"}}
    AB["Invoke RuleFallback<br/>(deterministic rules)"]
    AC["Post-process score<br/>(calibrate, clip)"]
    AD["Store in cache<br/>(TTL: 300s)"]
    AE["Log prediction<br/>(for monitoring)"]
    AF["✅ Return 200 OK<br/>PredictionResponse"]

    A --> B --> C
    C -->|No| D
    C -->|Yes| E
    E --> F --> G
    G -->|No| H
    G -->|Yes| I
    I --> J --> K
    K -->|Yes| L
    K -->|No| M --> N
    N -->|No| O
    N -->|Yes| P --> Q
    Q -->|No| R
    Q -->|Yes| S
    R --> S
    S --> T --> U
    U -->|No| V
    U -->|Yes| W --> X
    X -->|No| Y
    X -->|Yes| Z --> AA
    AA -->|No| AB
    AA -->|Yes| AC
    AB --> AC
    AC --> AD --> AE --> AF

    style L fill:#7ED321,color:#000,stroke:#333,stroke-width:2px
    style AF fill:#7ED321,color:#000,stroke:#333,stroke-width:2px
    style D fill:#FF6B6B,color:#fff
    style H fill:#FF6B6B,color:#fff
    style O fill:#FF6B6B,color:#fff
    style V fill:#FF6B6B,color:#fff
    style Y fill:#FF6B6B,color:#fff
    style AB fill:#F5A623,color:#000
    style R fill:#F5A623,color:#000
```

## ETA Prediction Flow

```mermaid
flowchart TD
    A["Client: POST /predict/eta<br/>{store_id, user_location, items}"]
    B["✅ Request validated"]
    C["Check cache<br/>Key: eta:{store}:{location_hash}"]
    D{{"Cache<br/>hit?"}}
    E["Return cached ETA<br/>(TTL: 60s for ETA)"]
    F["Fetch features from Redis:<br/>- store_prep_time<br/>- rider_availability<br/>- traffic_index"]
    G["Extract request features:<br/>- distance_km<br/>- current_hour<br/>- day_of_week"]
    H["Merge & transform features<br/>(45 total features)"]
    I["Load ETA model<br/>(LightGBM)"]
    J{{"Model<br/>available?"}}
    K["Invoke RuleFallback:<br/>eta = distance * 3 + store_prep + 5"]
    L["Run LightGBM.predict()<br/>(~10ms)"]
    M["Post-process:<br/>- Clip to [5, 120] mins<br/>- Add buffer (10%)"]
    N["Build response:<br/>{eta_minutes, confidence, breakdown}"]
    O["Cache for 60s"]
    P["Return 200 OK"]

    A --> B --> C --> D
    D -->|Yes| E --> P
    D -->|No| F
    F --> G --> H --> I --> J
    J -->|No| K
    J -->|Yes| L
    K --> M
    L --> M
    M --> N --> O --> P

    style E fill:#52C41A,color:#fff,stroke:#333,stroke-width:2px
    style P fill:#7ED321,color:#000,stroke:#333,stroke-width:2px
    style K fill:#F5A623,color:#000
    style L fill:#4A90E2,color:#fff
```

## Fraud Scoring Flow

```mermaid
flowchart TD
    A["Client: POST /predict/fraud<br/>{user_id, order_details, payment}"]
    B["✅ Request validated"]
    C["NO CACHE for fraud<br/>(always fresh)"]
    D["Fetch features from Redis:<br/>- device_fingerprint<br/>- velocity_signals<br/>- account_age_days"]
    E["Fetch features from BigQuery:<br/>- historical_chargebacks<br/>- fraud_flags_count"]
    F["Extract request features:<br/>- order_value<br/>- payment_method<br/>- ip_geo_location"]
    G["Merge all features<br/>(120 total features)"]
    H{{"All critical<br/>features present?"}}
    I["🚨 Return high risk (0.9)<br/>if critical features missing"]
    J["Load Fraud model<br/>(XGBoost)"]
    K["Run XGBoost.predict()<br/>(~15ms)"]
    L{{"Score ><br/>threshold?"}}
    M["Invoke rule checks:<br/>- IP blacklist?<br/>- Velocity limit?<br/>- Device ban?"]
    N["Combine ML + rule scores"]
    O["Calibrate score<br/>(Platt scaling)"]
    P["Build response:<br/>{risk_score, risk_level,<br/>risk_factors[], action}"]
    Q["Return 200 OK"]

    A --> B --> C --> D
    D --> E --> F --> G --> H
    H -->|No| I --> Q
    H -->|Yes| J --> K --> L
    L -->|Yes| M
    L -->|No| O
    M --> N --> O --> P --> Q

    style Q fill:#7ED321,color:#000,stroke:#333,stroke-width:2px
    style I fill:#FF6B6B,color:#fff
    style K fill:#4A90E2,color:#fff
    style M fill:#F5A623,color:#000
```

## Product Ranking Flow

```mermaid
flowchart TD
    A["Client: POST /predict/ranking<br/>{user_id, item_ids[], context}"]
    B["✅ Request validated"]
    C["Check cache<br/>Key: ranking:{user}:{items_hash}"]
    D{{"Cache<br/>hit?"}}
    E["Return cached ranking<br/>(TTL: 300s)"]
    F["Fetch user features:<br/>- preferences<br/>- purchase_history<br/>- browsing_signals"]
    G["Fetch item features (batch):<br/>- popularity_score<br/>- margin<br/>- stock_level"]
    H["Build pairwise features<br/>(user × item matrix)"]
    I["Load Ranking model<br/>(LambdaMART)"]
    J{{"Model<br/>available?"}}
    K["Invoke RuleFallback:<br/>Sort by popularity × margin"]
    L["Run LambdaMART.predict()<br/>(~25ms for 50 items)"]
    M["Score all items"]
    N["Sort by relevance score DESC"]
    O["Apply business rules:<br/>- Boost promoted items<br/>- Ensure diversity"]
    P["Build response:<br/>{ranked_items[], scores[]}"]
    Q["Cache for 300s"]
    R["Return 200 OK"]

    A --> B --> C --> D
    D -->|Yes| E --> R
    D -->|No| F
    F --> G --> H --> I --> J
    J -->|No| K --> N
    J -->|Yes| L --> M --> N
    N --> O --> P --> Q --> R

    style E fill:#52C41A,color:#fff,stroke:#333,stroke-width:2px
    style R fill:#7ED321,color:#000,stroke:#333,stroke-width:2px
    style K fill:#F5A623,color:#000
    style L fill:#4A90E2,color:#fff
    style O fill:#9013FE,color:#fff
```

## Demand Forecasting Flow

```mermaid
flowchart TD
    A["Client: POST /predict/demand<br/>{store_id, sku_ids[], horizon_days}"]
    B["✅ Request validated"]
    C["Check cache<br/>Key: demand:{store}:{skus_hash}:{horizon}"]
    D{{"Cache<br/>hit?"}}
    E["Return cached forecast<br/>(TTL: 3600s / 1hr)"]
    F["Fetch historical data:<br/>- past 90 days sales<br/>- seasonality patterns"]
    G["Fetch external signals:<br/>- promotions calendar<br/>- weather forecast<br/>- local events"]
    H["Build time series features<br/>(60 features per SKU)"]
    I["Load Demand model<br/>(Prophet + TFT ensemble)"]
    J{{"Model<br/>available?"}}
    K["Invoke RuleFallback:<br/>7-day moving average"]
    L["Run Prophet baseline"]
    M["Run TFT for adjustments"]
    N["Ensemble predictions<br/>(weighted average)"]
    O["Generate confidence intervals<br/>(10th, 50th, 90th percentile)"]
    P["Build response:<br/>{forecasts[], confidence_intervals[]}"]
    Q["Cache for 1 hour"]
    R["Return 200 OK"]

    A --> B --> C --> D
    D -->|Yes| E --> R
    D -->|No| F
    F --> G --> H --> I --> J
    J -->|No| K --> O
    J -->|Yes| L --> M --> N --> O
    O --> P --> Q --> R

    style E fill:#52C41A,color:#fff,stroke:#333,stroke-width:2px
    style R fill:#7ED321,color:#000,stroke:#333,stroke-width:2px
    style K fill:#F5A623,color:#000
    style N fill:#4A90E2,color:#fff
```

## Shadow & A/B Inference Flow

```mermaid
flowchart TD
    A["Prediction Request"]
    B["Resolve primary model<br/>(production version)"]
    C["Check A/B experiment<br/>(experiment_config)"]
    D{{"A/B<br/>enabled?"}}
    E["Run primary model<br/>(serve response)"]
    F["Determine traffic split<br/>(e.g., 90/10)"]
    G{{"Route to<br/>challenger?"}}
    H["Run challenger model<br/>(serve response)"]
    I["Check shadow mode<br/>(shadow_config)"]
    J{{"Shadow<br/>enabled?"}}
    K["Log primary result"]
    L["Run shadow model<br/>(async, log-only)"]
    M["Compare predictions<br/>(drift detection)"]
    N["Log comparison metrics:<br/>- prediction_diff<br/>- latency_diff"]
    O["Emit to Prometheus:<br/>shadow_inference_total<br/>prediction_drift_ratio"]
    P["Return primary response<br/>(shadow never served)"]

    A --> B --> C --> D
    D -->|No| E --> I
    D -->|Yes| F --> G
    G -->|No 90%| E
    G -->|Yes 10%| H --> I
    I --> J
    J -->|No| K --> P
    J -->|Yes| L --> M --> N --> O --> P

    style E fill:#7ED321,color:#000,stroke:#333,stroke-width:2px
    style H fill:#4A90E2,color:#fff
    style L fill:#9013FE,color:#fff
    style P fill:#7ED321,color:#000,stroke:#333,stroke-width:2px

    Note1["Shadow mode: log-only<br/>Never affects user response"]
    L -.-> Note1
```

## Feature Store Fetch Flow

```mermaid
flowchart TD
    A["FeatureStoreClient.fetch(request)"]
    B["Extract entity IDs<br/>(user_id, item_ids)"]

    subgraph Online["Online Features (Redis)"]
        C["Build Redis keys:<br/>user_features:{user_id}<br/>item_features:{item_id}"]
        D["MGET all keys<br/>(pipelined)"]
        E{{"Redis<br/>available?"}}
        F["Parse JSON features"]
        G["⚠️ Use empty/defaults"]
    end

    subgraph Offline["Offline Features (BigQuery)"]
        H["Check local cache<br/>(1hr TTL)"]
        I{{"Cache<br/>hit?"}}
        J["Return cached features"]
        K["Query BigQuery<br/>(batched, async)"]
        L{{"Query<br/>succeeded?"}}
        M["Cache results locally"]
        N["⚠️ Use stale cache<br/>or defaults"]
    end

    subgraph RequestOnly["Request-Only Features"]
        O["Extract from request:<br/>- timestamp<br/>- device_type<br/>- geo_hash"]
    end

    P["Merge all feature sources"]
    Q["Return FeatureVector"]

    A --> B
    B --> C --> D --> E
    E -->|Yes| F --> P
    E -->|No| G --> P

    B --> H --> I
    I -->|Yes| J --> P
    I -->|No| K --> L
    L -->|Yes| M --> P
    L -->|No| N --> P

    B --> O --> P
    P --> Q

    style F fill:#7ED321,color:#000
    style J fill:#7ED321,color:#000
    style G fill:#F5A623,color:#000
    style N fill:#F5A623,color:#000
    style Q fill:#4A90E2,color:#fff,stroke:#333,stroke-width:2px
```
