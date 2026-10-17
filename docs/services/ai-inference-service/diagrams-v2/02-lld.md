# AI Inference Service - Low-Level Design

## Component Architecture

```mermaid
graph TB
    HTTPRequest["🌐 HTTP Request<br/>POST /predict/{model}"]

    subgraph FastAPILayer["FastAPI Application"]
        Router["APIRouter<br/>(/predict, /health, /metrics)"]
        AuthMiddleware["🔐 Auth Middleware<br/>(JWT validation)"]
        RateLimiter["⏱️ Rate Limiter<br/>(per client, per model)"]
        MetricsMiddleware["📊 Metrics Middleware<br/>(Prometheus)"]
    end

    subgraph ModelRegistry["ModelRegistry (Singleton)"]
        VersionResolver["VersionResolver<br/>(resolve model version)"]
        ModelLoader["ModelLoader<br/>(load weights from GCS)"]
        ModelCache["ModelCache<br/>(in-memory, lazy load)"]
        ModelConfig["ModelConfig<br/>(config.yaml per model)"]
    end

    subgraph FeatureLayer["Feature Preprocessing"]
        FeatureStoreClient["FeatureStoreClient"]
        RedisClient["Redis Client<br/>(<5ms online)"]
        BQClient["BigQuery Client<br/>(batch offline)"]
        FeatureTransformer["FeatureTransformer<br/>(normalize, encode)"]
        FeatureValidator["FeatureValidator<br/>(schema validation)"]
    end

    subgraph InferenceEngine["Inference Engine"]
        MLInference["MLInference<br/>(LightGBM, XGBoost, etc.)"]
        RuleFallback["RuleFallback<br/>(deterministic rules)"]
        PostProcessor["PostProcessor<br/>(calibration, clipping)"]
    end

    subgraph CachingLayer["Caching Layer"]
        InferenceCache["InferenceCache<br/>(LRU, 1000 entries)"]
        CacheKeyBuilder["CacheKeyBuilder<br/>(hash features)"]
        TTLManager["TTLManager<br/>(300s default)"]
    end

    subgraph ShadowMode["Shadow & A/B"]
        ShadowRunner["ShadowRunner<br/>(log-only inference)"]
        ABRouter["ABRouter<br/>(traffic split)"]
        ExperimentLogger["ExperimentLogger<br/>(metrics + logs)"]
    end

    Response["📤 Response<br/>PredictionResponse"]

    HTTPRequest --> Router
    Router --> AuthMiddleware
    AuthMiddleware --> RateLimiter
    RateLimiter --> MetricsMiddleware

    MetricsMiddleware --> VersionResolver
    VersionResolver --> ModelLoader
    ModelLoader --> ModelCache
    ModelLoader --> ModelConfig

    MetricsMiddleware --> CacheKeyBuilder
    CacheKeyBuilder --> InferenceCache

    InferenceCache -->|Cache HIT| Response
    InferenceCache -->|Cache MISS| FeatureStoreClient

    FeatureStoreClient --> RedisClient
    FeatureStoreClient --> BQClient
    RedisClient --> FeatureTransformer
    BQClient --> FeatureTransformer
    FeatureTransformer --> FeatureValidator

    FeatureValidator --> MLInference
    FeatureValidator --> RuleFallback

    MLInference --> PostProcessor
    RuleFallback --> PostProcessor

    PostProcessor --> ShadowRunner
    ShadowRunner --> ABRouter
    ABRouter --> ExperimentLogger

    PostProcessor --> InferenceCache
    PostProcessor --> Response

    style Router fill:#4A90E2,color:#fff,stroke:#333,stroke-width:2px
    style InferenceCache fill:#9013FE,color:#fff
    style MLInference fill:#7ED321,color:#000,stroke:#333,stroke-width:2px
    style RuleFallback fill:#F5A623,color:#000
    style FeatureStoreClient fill:#FF6B6B,color:#fff
```

## ModelRegistry Implementation

```mermaid
graph TD
    A["ModelRegistry.get_model(model_name, version)"]
    B["Check ModelCache<br/>(in-memory dict)"]
    C{{"Model in<br/>cache?"}}
    D["Return cached model<br/>(~0ms)"]
    E["Load ModelConfig<br/>(config.yaml)"]
    F["Resolve version<br/>(latest, specific, canary)"]
    G{{"Version<br/>exists?"}}
    H["❌ ModelNotFoundError<br/>(return 404)"]
    I["Determine model type<br/>(lightgbm, xgboost, torch, etc.)"]
    J["Load weights from GCS<br/>(or local path)"]
    K{{"Load<br/>successful?"}}
    L["❌ ModelLoadError<br/>(return 503)"]
    M["Wrap in ModelWrapper<br/>(unified interface)"]
    N["Store in ModelCache<br/>(with metadata)"]
    O["Log: MODEL_LOADED<br/>(model_name, version, load_time_ms)"]
    P["Return ModelWrapper"]

    A --> B --> C
    C -->|Yes| D
    C -->|No| E
    E --> F --> G
    G -->|No| H
    G -->|Yes| I
    I --> J --> K
    K -->|No| L
    K -->|Yes| M
    M --> N --> O --> P

    style D fill:#7ED321,color:#000,stroke:#333,stroke-width:2px
    style P fill:#7ED321,color:#000,stroke:#333,stroke-width:2px
    style H fill:#FF6B6B,color:#fff
    style L fill:#FF6B6B,color:#fff
    style N fill:#9013FE,color:#fff
```

## FeatureStoreClient Pipeline

```mermaid
graph TD
    Request["PredictionRequest<br/>(user_id, item_ids, context)"]

    subgraph FeatureFetch["Feature Fetching"]
        FSClient["FeatureStoreClient"]

        subgraph Online["Online (Redis)"]
            RedisGet["MGET user_features:{user_id}"]
            RedisGetItem["MGET item_features:{item_id}"]
            RedisParse["Parse JSON features"]
        end

        subgraph Offline["Offline (BigQuery)"]
            BQQuery["SELECT features FROM<br/>feature_store.user_features<br/>WHERE user_id = ?"]
            BQCache["Check local cache<br/>(1hr TTL)"]
        end

        subgraph RequestOnly["Request-Only"]
            ExtractRequest["Extract from request:<br/>- timestamp<br/>- device_type<br/>- geo_location"]
        end
    end

    subgraph Transform["Feature Transformation"]
        Merge["Merge all feature sources"]
        FillMissing["Fill missing values<br/>(default or mean)"]
        Normalize["Normalize numeric features<br/>(z-score, min-max)"]
        Encode["Encode categoricals<br/>(one-hot, target encode)"]
        Validate["Validate schema<br/>(check required, types)"]
    end

    FeatureVector["📊 Feature Vector<br/>(numpy array / dict)"]

    Request --> FSClient
    FSClient --> RedisGet
    FSClient --> RedisGetItem
    FSClient --> BQQuery
    FSClient --> ExtractRequest

    RedisGet --> RedisParse
    RedisGetItem --> RedisParse
    BQQuery --> BQCache

    RedisParse --> Merge
    BQCache --> Merge
    ExtractRequest --> Merge

    Merge --> FillMissing
    FillMissing --> Normalize
    Normalize --> Encode
    Encode --> Validate
    Validate --> FeatureVector

    style FSClient fill:#4A90E2,color:#fff,stroke:#333,stroke-width:2px
    style RedisGet fill:#FF6B6B,color:#fff
    style BQQuery fill:#4A90E2,color:#fff
    style FeatureVector fill:#7ED321,color:#000,stroke:#333,stroke-width:2px
```

## InferenceEngine Decision Flow

```mermaid
graph TD
    Features["Feature Vector"]

    ModelCheck{{"ML Model<br/>available?"}}

    subgraph MLPath["ML Inference Path"]
        LoadModel["Load model from registry"]
        RunML["model.predict(features)"]
        MLScore["Raw ML score"]
    end

    subgraph FallbackPath["Rule Fallback Path"]
        RuleEngine["RuleEngine"]
        EvalRules["Evaluate deterministic rules"]
        RuleScore["Rule-based score"]
    end

    subgraph PostProcess["Post-Processing"]
        Calibrate["Calibrate score<br/>(Platt scaling)"]
        Clip["Clip to valid range<br/>[0, 1] or [0, ∞)"]
        AddMetadata["Add metadata:<br/>- model_version<br/>- inference_time_ms<br/>- is_fallback"]
    end

    Output["PredictionResponse"]

    Features --> ModelCheck
    ModelCheck -->|Yes| LoadModel
    LoadModel --> RunML
    RunML --> MLScore
    MLScore --> Calibrate

    ModelCheck -->|No / Error| RuleEngine
    RuleEngine --> EvalRules
    EvalRules --> RuleScore
    RuleScore --> Calibrate

    Calibrate --> Clip
    Clip --> AddMetadata
    AddMetadata --> Output

    style MLScore fill:#7ED321,color:#000,stroke:#333,stroke-width:2px
    style RuleScore fill:#F5A623,color:#000,stroke:#333,stroke-width:2px
    style Output fill:#4A90E2,color:#fff,stroke:#333,stroke-width:2px
```

## Per-Model Configurations

```mermaid
graph TB
    subgraph ETAModel["⏱️ ETA Model"]
        ETA_Type["Type: LightGBM"]
        ETA_Features["Features: 45<br/>- distance_km<br/>- traffic_index<br/>- weather_code<br/>- store_prep_time"]
        ETA_Output["Output: minutes (float)"]
        ETA_Fallback["Fallback: distance * 3 + 15"]
        ETA_SLA["SLA: P99 < 20ms"]
    end

    subgraph FraudModel["🛡️ Fraud Model"]
        Fraud_Type["Type: XGBoost"]
        Fraud_Features["Features: 120<br/>- device_fingerprint<br/>- velocity_signals<br/>- payment_history<br/>- geo_anomaly"]
        Fraud_Output["Output: risk_score [0,1]"]
        Fraud_Fallback["Fallback: rule-based thresholds"]
        Fraud_SLA["SLA: P99 < 30ms"]
    end

    subgraph RankingModel["📊 Ranking Model"]
        Ranking_Type["Type: LambdaMART"]
        Ranking_Features["Features: 80<br/>- user_preferences<br/>- item_popularity<br/>- recency_score<br/>- margin_score"]
        Ranking_Output["Output: relevance scores[]"]
        Ranking_Fallback["Fallback: popularity sort"]
        Ranking_SLA["SLA: P99 < 50ms"]
    end

    subgraph DemandModel["📈 Demand Model"]
        Demand_Type["Type: Prophet + TFT"]
        Demand_Features["Features: 60<br/>- historical_sales<br/>- seasonality<br/>- promotions<br/>- external_events"]
        Demand_Output["Output: demand_forecast[]"]
        Demand_Fallback["Fallback: moving average"]
        Demand_SLA["SLA: P99 < 100ms"]
    end

    style ETAModel fill:#E3F2FD,color:#000
    style FraudModel fill:#FFEBEE,color:#000
    style RankingModel fill:#E8F5E9,color:#000
    style DemandModel fill:#FFF3E0,color:#000
```

## Metrics Collection Pipeline

```mermaid
graph LR
    Request["🌐 HTTP Request"]

    subgraph Metrics["Prometheus Metrics"]
        M1["⏱️ inference_request_duration_seconds<br/>{model, version, status}"]
        M2["📊 inference_requests_total<br/>{model, version, status}"]
        M3["💾 cache_hits_total<br/>{model}"]
        M4["💾 cache_misses_total<br/>{model}"]
        M5["🔴 feature_store_latency_seconds<br/>{source: redis/bigquery}"]
        M6["⚠️ fallback_invocations_total<br/>{model, reason}"]
        M7["🔬 shadow_inferences_total<br/>{model, shadow_version}"]
        M8["📦 model_load_duration_seconds<br/>{model, version}"]
    end

    subgraph Latency["Latency Breakdown"]
        L1["Auth: ~2ms"]
        L2["Cache check: ~1ms"]
        L3["Feature fetch: ~5-50ms"]
        L4["Inference: ~5-100ms"]
        L5["Post-process: ~2ms"]
        L6["Serialization: ~1ms"]
    end

    Request --> L1 --> L2 --> L3 --> L4 --> L5 --> L6

    L1 -.-> M2
    L2 -.-> M3
    L2 -.-> M4
    L3 -.-> M5
    L4 -.-> M1
    L4 -.-> M6
    L4 -.-> M7

    style M1 fill:#9013FE,color:#fff
    style M2 fill:#9013FE,color:#fff
    style M5 fill:#FF6B6B,color:#fff
    style M6 fill:#F5A623,color:#000
```

## SLO: P99 Latency Targets

```
┌─────────────────────────────────────────────────────────────┐
│  AI Inference Service Request Timeline (per model)          │
├─────────────────────────────────────────────────────────────┤
│ Request Parsing & Validation:          ~2ms                 │
│ Auth (JWT validation):                 ~3ms                 │
│ Cache Key Generation:                  ~1ms                 │
│ Cache Lookup (LRU in-memory):          ~0.5ms               │
│ Subtotal (overhead):                   ~6.5ms               │
│                                                             │
│ Feature Store (Redis online):          ~5ms (p99)           │
│ Feature Store (BigQuery, if needed):   ~50ms (p99, cached)  │
│ Feature Transformation:                ~3ms                 │
│                                                             │
│ Model Inference (varies by model):                          │
│   - ETA (LightGBM):                    ~10ms p99            │
│   - Fraud (XGBoost):                   ~15ms p99            │
│   - Ranking (LambdaMART):              ~25ms p99            │
│   - Demand (Prophet+TFT):              ~80ms p99            │
│   - Personalization (Two-Tower):       ~20ms p99            │
│   - CLV (BG/NBD):                      ~30ms p99            │
│   - Dynamic Pricing (Bandit):          ~15ms p99            │
│                                                             │
│ Post-Processing (calibration):         ~2ms                 │
│ Response Serialization:                ~1ms                 │
│                                                             │
│ TOTAL P99 (ETA, fastest):              ~30ms                │
│ TOTAL P99 (Demand, slowest):           ~140ms               │
│ TARGET:                                <200ms all models    │
│ STATUS:                                ✅ WITHIN SLO        │
└─────────────────────────────────────────────────────────────┘
```

## Error Handling & Resilience

```mermaid
graph TD
    E1["Model Not Found"]
    E2["Feature Store Timeout"]
    E3["Model Inference Error"]
    E4["Invalid Request"]
    E5["Rate Limit Exceeded"]

    E1 --> E1_Handle["Return 404<br/>ModelNotFoundError"]
    E1_Handle --> E1_Log["Log: MODEL_NOT_FOUND<br/>(model_name, requested_version)"]

    E2 --> E2_Handle["Use cached features<br/>or request-only features"]
    E2_Handle --> E2_Fallback["Continue with degraded features"]
    E2_Fallback --> E2_Log["Log: FEATURE_STORE_TIMEOUT<br/>Increment feature_store_errors_total"]

    E3 --> E3_Handle["Invoke RuleFallback"]
    E3_Handle --> E3_Return["Return fallback score<br/>with is_fallback=true"]
    E3_Return --> E3_Log["Log: ML_INFERENCE_ERROR<br/>Increment fallback_invocations_total"]

    E4 --> E4_Handle["Return 400<br/>ValidationError"]
    E4_Handle --> E4_Log["Log: INVALID_REQUEST<br/>(validation errors)"]

    E5 --> E5_Handle["Return 429<br/>RateLimitExceeded"]
    E5_Handle --> E5_Log["Log: RATE_LIMIT_EXCEEDED<br/>(client_id, model)"]
    E5_Log --> E5_Retry["Include Retry-After header"]

    style E1_Handle fill:#FF6B6B,color:#fff
    style E2_Fallback fill:#F5A623,color:#000
    style E3_Return fill:#F5A623,color:#000
    style E4_Handle fill:#FF6B6B,color:#fff
    style E5_Handle fill:#FF6B6B,color:#fff
```
