# AI Inference Service - ER Diagram & Data Model

## Core Data Model (Logical)

```mermaid
erDiagram
    MODELS ||--o{ MODEL_VERSIONS : "has versions"
    MODEL_VERSIONS ||--o{ PREDICTIONS : "generates"
    PREDICTIONS }o--|| FEATURE_VECTORS : "uses"
    FEATURE_VECTORS }o--o{ FEATURES : "contains"
    MODELS ||--o{ MODEL_CONFIGS : "configured by"
    MODEL_VERSIONS ||--o{ EXPERIMENTS : "tested in"
    PREDICTIONS ||--o{ PREDICTION_LOGS : "logged to"

    MODELS {
        string model_id PK "Unique model identifier (eta, fraud, ranking)"
        string model_name "Human-readable name"
        string model_type "lightgbm, xgboost, torch, prophet"
        string description "Model purpose and use case"
        string owner_team "ML team owning model"
        timestamp created_at "Model registration date"
        boolean is_active "Currently serving traffic"
    }

    MODEL_VERSIONS {
        string version_id PK "Semantic version (v3.2.1)"
        string model_id FK "Parent model"
        string artifact_path "GCS path to weights"
        string status "shadow, canary, production, deprecated"
        int feature_count "Number of input features"
        float validation_auc "Evaluation AUC/RMSE"
        float p99_latency_ms "Measured P99 latency"
        timestamp trained_at "Training completion time"
        timestamp deployed_at "Production deployment time"
        json hyperparameters "Training hyperparameters"
    }

    PREDICTIONS {
        uuid prediction_id PK "Unique prediction ID"
        string model_id FK "Model used"
        string version_id FK "Version used"
        string request_id "Client request correlation ID"
        float score "Prediction output"
        float confidence "Model confidence"
        boolean is_fallback "Used rule fallback"
        boolean is_cached "Served from cache"
        int latency_ms "Inference latency"
        timestamp created_at "Prediction timestamp"
    }

    FEATURE_VECTORS {
        uuid vector_id PK "Unique vector ID"
        uuid prediction_id FK "Associated prediction"
        string entity_type "user, item, store"
        string entity_id "Entity identifier"
        json features "Feature key-value pairs"
        string source "redis, bigquery, request"
        int feature_count "Number of features"
        timestamp fetched_at "Feature fetch time"
    }

    FEATURES {
        string feature_name PK "Feature identifier"
        string feature_type "numeric, categorical, embedding"
        string source "redis, bigquery, request"
        string description "Feature description"
        float fill_value "Default for missing"
        string transformation "z-score, one-hot, etc."
        json schema "Validation schema"
    }

    MODEL_CONFIGS {
        string config_id PK "Config identifier"
        string model_id FK "Model reference"
        json feature_list "Required features"
        json preprocessing "Transformation config"
        json fallback_rules "Rule-based fallback"
        float cache_ttl_seconds "Cache duration"
        json sla_targets "Latency targets"
    }

    EXPERIMENTS {
        string experiment_id PK "Experiment identifier"
        string model_id FK "Model being tested"
        string control_version FK "Control model version"
        string treatment_version FK "Treatment model version"
        float traffic_split "Treatment traffic %"
        string status "draft, active, completed"
        timestamp started_at "Experiment start"
        timestamp ended_at "Experiment end"
        json results "Statistical results"
    }

    PREDICTION_LOGS {
        uuid log_id PK "Log entry ID"
        uuid prediction_id FK "Prediction reference"
        string log_level "INFO, WARN, ERROR"
        string event_type "CACHE_HIT, FALLBACK, etc."
        json metadata "Additional context"
        timestamp logged_at "Log timestamp"
    }
```

## Redis Cache Schema (Online Features)

```mermaid
graph TB
    Redis["🔴 Redis Cluster<br/>(Online Feature Store)"]

    subgraph UserFeatures["User Features"]
        UF_Key["user_features:{user_id}<br/>Type: JSON Hash<br/>TTL: 1 hour"]
        UF_Value["{<br/>  recent_orders: 5,<br/>  avg_basket_value: 45.50,<br/>  favorite_categories: ['grocery'],<br/>  session_count_7d: 12,<br/>  last_order_hours_ago: 48<br/>}"]
    end

    subgraph ItemFeatures["Item Features"]
        IF_Key["item_features:{item_id}<br/>Type: JSON Hash<br/>TTL: 15 minutes"]
        IF_Value["{<br/>  view_count_24h: 1500,<br/>  purchase_rate: 0.12,<br/>  stock_level: 85,<br/>  price: 12.99,<br/>  category: 'beverages'<br/>}"]
    end

    subgraph StoreFeatures["Store Features"]
        SF_Key["store_features:{store_id}<br/>Type: JSON Hash<br/>TTL: 5 minutes"]
        SF_Value["{<br/>  prep_time_minutes: 8,<br/>  active_riders: 12,<br/>  current_orders: 45,<br/>  avg_wait_time: 3.5<br/>}"]
    end

    subgraph InferenceCache["Inference Cache"]
        IC_Key["inference:{model}:{hash}<br/>Type: JSON<br/>TTL: varies by model"]
        IC_Value["{<br/>  score: 25.5,<br/>  confidence: 0.92,<br/>  model_version: 'v3.2.1',<br/>  cached_at: 1711000000<br/>}"]
    end

    subgraph RateLimits["Rate Limits"]
        RL_Key["rate_limit:{client}:{model}<br/>Type: Counter<br/>TTL: 60 seconds"]
        RL_Value["Current count: 45<br/>Limit: 100/minute"]
    end

    Redis --> UserFeatures
    Redis --> ItemFeatures
    Redis --> StoreFeatures
    Redis --> InferenceCache
    Redis --> RateLimits

    UF_Key --> UF_Value
    IF_Key --> IF_Value
    SF_Key --> SF_Value
    IC_Key --> IC_Value
    RL_Key --> RL_Value

    style Redis fill:#FF6B6B,color:#fff,stroke:#333,stroke-width:2px
    style UF_Value fill:#E8F5E9,color:#000
    style IF_Value fill:#E8F5E9,color:#000
    style SF_Value fill:#E8F5E9,color:#000
    style IC_Value fill:#E3F2FD,color:#000
    style RL_Value fill:#FFF3E0,color:#000
```

## BigQuery Offline Feature Tables

```mermaid
graph TB
    BQ["📊 BigQuery<br/>(Offline Feature Store)"]

    subgraph UserHistorical["user_historical_features"]
        UH_Schema["user_id STRING PK<br/>ltv_30d FLOAT64<br/>ltv_90d FLOAT64<br/>order_frequency FLOAT64<br/>avg_basket_size FLOAT64<br/>churn_probability FLOAT64<br/>preferred_payment STRING<br/>updated_at TIMESTAMP"]
    end

    subgraph ItemHistorical["item_historical_features"]
        IH_Schema["item_id STRING PK<br/>total_sales_30d INT64<br/>return_rate FLOAT64<br/>avg_rating FLOAT64<br/>review_count INT64<br/>seasonality_score FLOAT64<br/>updated_at TIMESTAMP"]
    end

    subgraph FraudSignals["fraud_historical_signals"]
        FH_Schema["user_id STRING PK<br/>chargeback_count_90d INT64<br/>dispute_count_90d INT64<br/>fraud_flags_count INT64<br/>avg_order_velocity FLOAT64<br/>device_change_count INT64<br/>updated_at TIMESTAMP"]
    end

    subgraph DemandHistory["demand_history"]
        DH_Schema["store_id STRING<br/>sku_id STRING<br/>date DATE<br/>quantity_sold INT64<br/>revenue FLOAT64<br/>promotions BOOLEAN<br/>PK (store_id, sku_id, date)"]
    end

    BQ --> UserHistorical
    BQ --> ItemHistorical
    BQ --> FraudSignals
    BQ --> DemandHistory

    style BQ fill:#4A90E2,color:#fff,stroke:#333,stroke-width:2px
    style UH_Schema fill:#E8F5E9,color:#000
    style IH_Schema fill:#E8F5E9,color:#000
    style FH_Schema fill:#FFEBEE,color:#000
    style DH_Schema fill:#FFF3E0,color:#000
```

## Prometheus Metrics Schema

```
AI Inference Service Metrics
├─ inference_request_duration_seconds{model, version, status}
│  └ Histogram: Request latency distribution
│     Buckets: 0.01, 0.025, 0.05, 0.1, 0.25, 0.5, 1.0, 2.5
│
├─ inference_requests_total{model, version, status}
│  └ Counter: Total prediction requests
│     status: success, error, fallback, rate_limited
│
├─ model_inference_duration_seconds{model, version}
│  └ Histogram: Pure model.predict() latency
│     Excludes feature fetch, cache lookup
│
├─ feature_store_latency_seconds{source, operation}
│  └ Histogram: Feature fetch latency
│     source: redis, bigquery
│     operation: get, mget, query
│
├─ feature_store_errors_total{source, error_type}
│  └ Counter: Feature store failures
│     error_type: timeout, connection, parse
│
├─ cache_operations_total{model, operation}
│  └ Counter: Cache hits/misses
│     operation: hit, miss, store, evict
│
├─ cache_hit_ratio{model}
│  └ Gauge: Cache hit percentage (0-1)
│     Updated every 60s
│
├─ fallback_invocations_total{model, reason}
│  └ Counter: Rule fallback activations
│     reason: model_error, timeout, missing_features
│
├─ model_load_duration_seconds{model, version}
│  └ Histogram: Cold model load time
│     Includes GCS download + deserialization
│
├─ models_loaded_total{model}
│  └ Gauge: Currently loaded model count
│     Per model family
│
├─ shadow_inference_total{model, shadow_version}
│  └ Counter: Shadow inference executions
│
├─ prediction_drift_ratio{model}
│  └ Gauge: Shadow vs primary prediction drift
│     |shadow - primary| / primary
│
├─ experiment_assignments_total{experiment_id, variant}
│  └ Counter: A/B experiment assignments
│
└─ rate_limit_rejections_total{model, client}
   └ Counter: Rate limit exceeded events
```

## Model Artifact Storage (GCS)

```mermaid
graph TB
    GCS["☁️ Google Cloud Storage<br/>(Model Artifacts)"]

    subgraph ETAModel["gs://instacommerce-models/eta/"]
        ETA_V1["v3.1.0/<br/>├── model.pkl (45MB)<br/>├── config.yaml<br/>├── features.json<br/>└── metrics.json"]
        ETA_V2["v3.2.1/ (current)<br/>├── model.pkl (48MB)<br/>├── config.yaml<br/>├── features.json<br/>└── metrics.json"]
        ETA_V3["v3.3.0-rc1/ (canary)<br/>├── model.pkl (50MB)<br/>├── config.yaml<br/>├── features.json<br/>└── metrics.json"]
    end

    subgraph FraudModel["gs://instacommerce-models/fraud/"]
        Fraud_V1["v2.5.0/ (current)<br/>├── model.pkl (120MB)<br/>├── config.yaml<br/>├── features.json<br/>├── thresholds.yaml<br/>└── metrics.json"]
    end

    subgraph RankingModel["gs://instacommerce-models/ranking/"]
        Rank_V1["v4.1.0/ (current)<br/>├── model.pkl (200MB)<br/>├── config.yaml<br/>├── features.json<br/>└── metrics.json"]
    end

    GCS --> ETAModel
    GCS --> FraudModel
    GCS --> RankingModel

    style GCS fill:#4A90E2,color:#fff,stroke:#333,stroke-width:2px
    style ETA_V2 fill:#7ED321,color:#000
    style ETA_V3 fill:#F5A623,color:#000
    style Fraud_V1 fill:#7ED321,color:#000
    style Rank_V1 fill:#7ED321,color:#000
```

## Observability Pipeline

```mermaid
graph LR
    AIInference["🧠 AI Inference Service"]

    subgraph metrics["📊 Metrics Pipeline"]
        MPrometheus["Prometheus<br/>:9090"]
        MGrafana["Grafana<br/>:3000"]
        MAlerting["Alertmanager"]
    end

    subgraph logs["📝 Logging Pipeline"]
        LStructured["Structured Logs<br/>(JSON)"]
        LElasticsearch["Elasticsearch"]
        LKibana["Kibana"]
    end

    subgraph traces["📈 Tracing Pipeline"]
        TOpenTelemetry["OpenTelemetry SDK"]
        TJaeger["Jaeger<br/>Query UI"]
    end

    subgraph mlops["🤖 ML Observability"]
        Drift["Prediction Drift<br/>Detector"]
        FeatureMonitor["Feature Distribution<br/>Monitor"]
        ModelPerf["Model Performance<br/>Tracker"]
    end

    AIInference -->|/metrics| MPrometheus
    AIInference -->|stdout| LStructured
    AIInference -->|spans| TOpenTelemetry

    MPrometheus --> MGrafana
    MPrometheus --> MAlerting
    MPrometheus --> Drift
    MPrometheus --> FeatureMonitor
    MPrometheus --> ModelPerf

    LStructured --> LElasticsearch
    LElasticsearch --> LKibana

    TOpenTelemetry --> TJaeger

    style AIInference fill:#4A90E2,color:#fff,stroke:#333,stroke-width:2px
    style MPrometheus fill:#9013FE,color:#fff
    style MGrafana fill:#9013FE,color:#fff
    style LElasticsearch fill:#F5A623,color:#000
    style TJaeger fill:#52C41A,color:#fff
    style Drift fill:#FF6B6B,color:#fff
```

## No Persistent Database (Stateless Service)

```mermaid
graph TB
    AIInference["🧠 AI Inference Service<br/>(Stateless)"]

    subgraph NotOwned["❌ Does NOT Own Database"]
        Note1["No PostgreSQL tables"]
        Note2["No persistent state"]
        Note3["No write transactions"]
    end

    subgraph CacheOnly["✅ Cache Only (Ephemeral)"]
        Redis["Redis Cache<br/>- Feature cache<br/>- Inference cache<br/>- Rate limit counters"]
        InMemory["In-Memory<br/>- Model weights<br/>- LRU cache<br/>- Config cache"]
    end

    subgraph ExternalSources["External Data Sources"]
        FeatureStore["Feature Store<br/>(Redis + BigQuery)<br/>- User features<br/>- Item features<br/>- Historical signals"]
        ModelStore["Model Artifacts<br/>(GCS/S3)<br/>- Model weights<br/>- Configs"]
    end

    subgraph ObservabilityStore["Observability (Write-only)"]
        Prometheus["Prometheus<br/>(metrics)"]
        ELK["ELK Stack<br/>(logs)"]
        Jaeger["Jaeger<br/>(traces)"]
    end

    AIInference --> NotOwned
    AIInference --> CacheOnly
    AIInference --> ExternalSources
    AIInference --> ObservabilityStore

    style AIInference fill:#4A90E2,color:#fff,stroke:#333,stroke-width:2px
    style NotOwned fill:#FFEBEE,color:#000
    style CacheOnly fill:#E8F5E9,color:#000
    style ExternalSources fill:#E3F2FD,color:#000
    style ObservabilityStore fill:#FFF3E0,color:#000
```

## Request/Response Data Structures

```mermaid
graph TB
    subgraph Request["PredictionRequest"]
        ReqFields["model: string (required)<br/>version: string (optional, default 'latest')<br/>user_id: string (optional)<br/>item_ids: string[] (optional)<br/>store_id: string (optional)<br/>context: object (optional)<br/>request_id: string (optional)"]
    end

    subgraph Response["PredictionResponse"]
        RespFields["prediction_id: uuid<br/>model: string<br/>version: string<br/>score: float | float[]<br/>confidence: float (optional)<br/>is_fallback: boolean<br/>is_cached: boolean<br/>latency_ms: int<br/>metadata: object"]
    end

    subgraph ETAResponse["ETA Response Example"]
        ETAFields["{<br/>  prediction_id: 'abc-123',<br/>  model: 'eta',<br/>  version: 'v3.2.1',<br/>  score: 25.5,<br/>  confidence: 0.92,<br/>  breakdown: {<br/>    prep_time: 8,<br/>    travel_time: 15,<br/>    buffer: 2.5<br/>  }<br/>}"]
    end

    subgraph FraudResponse["Fraud Response Example"]
        FraudFields["{<br/>  prediction_id: 'def-456',<br/>  model: 'fraud',<br/>  version: 'v2.5.0',<br/>  score: 0.28,<br/>  risk_level: 'LOW',<br/>  action: 'ALLOW',<br/>  risk_factors: [<br/>    'new_device',<br/>    'high_value_order'<br/>  ]<br/>}"]
    end

    Request --> Response
    Response --> ETAResponse
    Response --> FraudResponse

    style Request fill:#E3F2FD,color:#000
    style Response fill:#E8F5E9,color:#000
    style ETAResponse fill:#FFF3E0,color:#000
    style FraudFields fill:#FFEBEE,color:#000
```
