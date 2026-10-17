# AI Inference Service - Sequence Diagrams

## Complete Inference Request Sequence

```mermaid
sequenceDiagram
    participant Client as Client<br/>(BFF, Checkout)
    participant ALB as AWS ALB<br/>(TLS)
    participant API as FastAPI<br/>/predict/{model}
    participant Auth as Auth Middleware
    participant Cache as InferenceCache<br/>(LRU+TTL)
    participant Registry as ModelRegistry
    participant FS as FeatureStoreClient
    participant Redis as Redis<br/>(Online Features)
    participant BQ as BigQuery<br/>(Offline Features)
    participant Engine as InferenceEngine
    participant Model as ML Model

    Client->>ALB: 1. HTTPS POST /predict/eta<br/>{user_id, store_id, items}
    ALB->>API: 2. Forward (decrypted)

    API->>Auth: 3. Validate request
    Auth->>Auth: 4. Check JWT / API key
    Auth-->>API: 5. ✅ Authorized

    API->>API: 6. Parse PredictionRequest<br/>validate schema

    API->>Cache: 7. Build cache key<br/>hash(model + features)
    Cache-->>API: 8. Check LRU cache

    alt Cache HIT
        Cache-->>API: 9a. Return cached prediction
        API-->>Client: 9b. Return 200 OK<br/>(~5ms total)
    else Cache MISS
        API->>Registry: 10. Get model version

        Registry->>Registry: 11. Resolve version<br/>(latest, specific, canary)

        alt Model in memory
            Registry-->>API: 12a. Return cached model
        else Model not loaded
            Registry->>Registry: 12b. Load from GCS
            Registry-->>API: 12c. Return model wrapper
        end

        API->>FS: 13. Fetch features<br/>(user_id, store_id)

        par Online Features
            FS->>Redis: 14a. MGET user_features, store_features
            Redis-->>FS: 15a. Return JSON features (~3ms)
        and Offline Features (if needed)
            FS->>BQ: 14b. Query historical features
            BQ-->>FS: 15b. Return features (~50ms, cached)
        end

        FS->>FS: 16. Merge & transform features
        FS-->>API: 17. Return FeatureVector

        API->>Engine: 18. Run inference<br/>(features, model)

        Engine->>Model: 19. model.predict(features)

        alt ML Success
            Model-->>Engine: 20a. Raw prediction
        else ML Failure
            Engine->>Engine: 20b. Invoke RuleFallback
        end

        Engine->>Engine: 21. Post-process<br/>(calibrate, clip)
        Engine-->>API: 22. PredictionResult

        API->>Cache: 23. Store in cache<br/>(TTL: 300s)
        Cache-->>API: 24. Cached

        API->>API: 25. Build PredictionResponse

        API-->>ALB: 26. Return 200 OK
        ALB-->>Client: 27. Return prediction
    end

    Note over Client: Total latency (cache miss): ~50-150ms<br/>Total latency (cache hit): ~5ms
```

## ETA Prediction Sequence

```mermaid
sequenceDiagram
    participant BFF as mobile-bff-service
    participant API as AI Inference API
    participant Cache as InferenceCache
    participant FS as FeatureStoreClient
    participant Redis as Redis
    participant Model as ETA Model<br/>(LightGBM)
    participant Fallback as RuleFallback

    BFF->>API: 1. POST /predict/eta<br/>{store_id, user_location, items}

    API->>Cache: 2. Check cache<br/>Key: eta:{store}:{location_hash}

    alt Cache HIT (TTL: 60s)
        Cache-->>API: 3a. Return cached ETA
        API-->>BFF: 3b. Return {eta_minutes: 25}
    else Cache MISS
        API->>FS: 4. Fetch features

        FS->>Redis: 5. MGET store_prep_time,<br/>rider_availability, traffic_index
        Redis-->>FS: 6. Return features (~3ms)

        FS->>FS: 7. Extract request features:<br/>distance_km, current_hour, weather

        FS->>FS: 8. Transform 45 features
        FS-->>API: 9. Return FeatureVector

        API->>Model: 10. Run LightGBM.predict()

        alt Model Available
            Model->>Model: 11. Compute prediction (~10ms)
            Model-->>API: 12a. eta_raw = 22.5
        else Model Error
            API->>Fallback: 12b. Invoke fallback
            Fallback->>Fallback: 13. eta = distance*3 + prep + 5
            Fallback-->>API: 14. eta_raw = 24.0
        end

        API->>API: 15. Post-process:<br/>clip [5, 120], add buffer 10%
        API->>API: 16. eta_final = 25 minutes

        API->>Cache: 17. Cache for 60s
        Cache-->>API: 18. Cached

        API-->>BFF: 19. Return {eta_minutes: 25,<br/>confidence: 0.85, is_fallback: false}
    end

    Note over API: ETA P99 latency: ~30ms
```

## Fraud Scoring Sequence

```mermaid
sequenceDiagram
    participant Checkout as checkout-orchestrator
    participant API as AI Inference API
    participant FS as FeatureStoreClient
    participant Redis as Redis
    participant BQ as BigQuery
    participant Model as Fraud Model<br/>(XGBoost)
    participant Rules as Rule Engine

    Checkout->>API: 1. POST /predict/fraud<br/>{user_id, order, payment}

    Note over API: NO CACHE for fraud<br/>(always fresh evaluation)

    API->>FS: 2. Fetch features

    par Online Features
        FS->>Redis: 3a. MGET device_fingerprint,<br/>velocity_signals, account_age
        Redis-->>FS: 4a. Return features (~3ms)
    and Offline Features
        FS->>BQ: 3b. Query historical_chargebacks,<br/>fraud_flags_count
        BQ-->>FS: 4b. Return features (~20ms cached)
    end

    FS->>FS: 5. Merge 120 features
    FS-->>API: 6. Return FeatureVector

    API->>API: 7. Validate critical features

    alt Critical Features Missing
        API-->>Checkout: 8a. Return {risk_score: 0.9,<br/>action: "BLOCK"}
    else All Features Present
        API->>Model: 8b. Run XGBoost.predict()
        Model->>Model: 9. Compute risk score (~15ms)
        Model-->>API: 10. ml_score = 0.35

        API->>Rules: 11. Apply rule checks
        Rules->>Rules: 12. Check IP blacklist,<br/>velocity limits, device bans
        Rules-->>API: 13. rule_adjustments

        API->>API: 14. Combine scores:<br/>final = ml_score * 0.7 + rule * 0.3

        API->>API: 15. Calibrate (Platt scaling)
        API->>API: 16. final_score = 0.28

        API->>API: 17. Determine action:<br/>< 0.3 = ALLOW<br/>0.3-0.7 = REVIEW<br/>> 0.7 = BLOCK

        API-->>Checkout: 18. Return {risk_score: 0.28,<br/>risk_level: "LOW", action: "ALLOW",<br/>risk_factors: ["new_device"]}
    end

    Note over API: Fraud P99 latency: ~45ms
```

## Product Ranking Sequence

```mermaid
sequenceDiagram
    participant Catalog as catalog-service
    participant API as AI Inference API
    participant Cache as InferenceCache
    participant FS as FeatureStoreClient
    participant Redis as Redis
    participant Model as Ranking Model<br/>(LambdaMART)

    Catalog->>API: 1. POST /predict/ranking<br/>{user_id, item_ids[50], context}

    API->>Cache: 2. Check cache<br/>Key: ranking:{user}:{items_hash}

    alt Cache HIT (TTL: 300s)
        Cache-->>API: 3a. Return cached ranking
        API-->>Catalog: 3b. Return ranked_items[]
    else Cache MISS
        API->>FS: 4. Fetch user features

        FS->>Redis: 5. MGET user_preferences,<br/>purchase_history, browsing_signals
        Redis-->>FS: 6. Return user features (~3ms)

        API->>FS: 7. Fetch item features (batch)

        FS->>Redis: 8. MGET item_features:{id} × 50
        Redis-->>FS: 9. Return item features (~5ms)

        FS->>FS: 10. Build pairwise features<br/>(user × item matrix, 80 features each)
        FS-->>API: 11. Return FeatureMatrix

        API->>Model: 12. Run LambdaMART.predict()
        Model->>Model: 13. Score all 50 items (~25ms)
        Model-->>API: 14. relevance_scores[]

        API->>API: 15. Sort by score DESC

        API->>API: 16. Apply business rules:<br/>- Boost promoted (1.2x)<br/>- Ensure category diversity<br/>- Penalize out-of-stock (0.5x)

        API->>Cache: 17. Cache for 300s
        Cache-->>API: 18. Cached

        API-->>Catalog: 19. Return {ranked_items[],<br/>scores[], ranking_metadata}
    end

    Note over API: Ranking P99 latency: ~50ms
```

## Model Loading & Caching Sequence

```mermaid
sequenceDiagram
    participant API as FastAPI Handler
    participant Registry as ModelRegistry
    participant Cache as ModelCache<br/>(in-memory)
    participant Config as ModelConfig
    participant GCS as GCS/S3<br/>(Model Storage)
    participant Metrics as Prometheus

    API->>Registry: 1. get_model("eta", version="latest")

    Registry->>Cache: 2. Check model cache

    alt Model in Cache
        Cache-->>Registry: 3a. Return ModelWrapper
        Registry-->>API: 3b. Return model (~0ms)
    else Model Not Cached
        Registry->>Config: 4. Load config.yaml

        Config-->>Registry: 5. Return model config:<br/>- type: lightgbm<br/>- path: gs://models/eta/v3/<br/>- features: [...]

        Registry->>Registry: 6. Resolve version<br/>(latest → v3.2.1)

        Registry->>GCS: 7. Download model weights<br/>(model.pkl, ~50MB)

        GCS-->>Registry: 8. Return weights file

        Registry->>Registry: 9. Load model into memory<br/>(joblib.load / torch.load)

        Registry->>Registry: 10. Wrap in ModelWrapper<br/>(unified predict interface)

        Registry->>Cache: 11. Store in ModelCache

        Registry->>Metrics: 12. Emit metrics:<br/>model_load_duration_seconds{model="eta"}

        Registry-->>API: 13. Return ModelWrapper
    end

    Note over Registry: Cold load: ~2-5s<br/>Hot load (cached): ~0ms
```

## Shadow Inference Sequence

```mermaid
sequenceDiagram
    participant API as FastAPI Handler
    participant Primary as Primary Model<br/>(v3.2.1)
    participant Shadow as Shadow Model<br/>(v3.3.0-rc1)
    participant Logger as ExperimentLogger
    participant Metrics as Prometheus

    API->>API: 1. Check shadow_config<br/>shadow_enabled: true

    API->>Primary: 2. Run primary inference
    Primary->>Primary: 3. model.predict(features)
    Primary-->>API: 4. primary_result = 25.0

    API->>API: 5. Shadow is async (non-blocking)

    par Shadow Inference (async)
        API->>Shadow: 6. Run shadow inference
        Shadow->>Shadow: 7. model.predict(features)
        Shadow-->>Logger: 8. shadow_result = 24.2
    end

    API-->>API: 9. Return primary_result<br/>(shadow never served)

    Logger->>Logger: 10. Compare predictions:<br/>diff = |25.0 - 24.2| = 0.8<br/>drift_ratio = 0.8/25.0 = 3.2%

    Logger->>Metrics: 11. Emit metrics:<br/>shadow_inference_total{model="eta"}<br/>prediction_drift_ratio{model="eta"}

    Logger->>Logger: 12. Log comparison:<br/>{primary: 25.0, shadow: 24.2,<br/>diff: 0.8, latency_diff: -2ms}

    Note over Shadow: Shadow inference:<br/>- Never affects response<br/>- Used for drift detection<br/>- Validates new model versions
```

## A/B Experiment Routing Sequence

```mermaid
sequenceDiagram
    participant Client as Client
    participant API as FastAPI Handler
    participant Router as ABRouter
    participant Control as Control Model<br/>(v3.2.1, 90%)
    participant Treatment as Treatment Model<br/>(v3.3.0, 10%)
    participant Logger as ExperimentLogger

    Client->>API: 1. POST /predict/eta<br/>{user_id: "u123", ...}

    API->>Router: 2. Route request<br/>(experiment: "eta_v3.3_test")

    Router->>Router: 3. Hash user_id<br/>bucket = hash("u123") % 100

    alt Control Group (bucket < 90)
        Router-->>API: 4a. Route to control
        API->>Control: 5a. Run control model
        Control-->>API: 6a. result = 25.0
        API->>Logger: 7a. Log {variant: "control", result: 25.0}
    else Treatment Group (bucket >= 90)
        Router-->>API: 4b. Route to treatment
        API->>Treatment: 5b. Run treatment model
        Treatment-->>API: 6b. result = 24.5
        API->>Logger: 7b. Log {variant: "treatment", result: 24.5}
    end

    Logger->>Logger: 8. Store for analysis:<br/>- experiment_id<br/>- user_id<br/>- variant<br/>- prediction<br/>- timestamp

    API-->>Client: 9. Return prediction<br/>(with experiment metadata)

    Note over Router: A/B routing:<br/>- Consistent hashing (sticky)<br/>- Same user = same variant<br/>- Enables causal analysis
```

## Health Check & Readiness Sequence

```mermaid
sequenceDiagram
    participant K8s as Kubernetes
    participant API as FastAPI<br/>(/health/*)
    participant Registry as ModelRegistry
    participant Redis as Redis
    participant BQ as BigQuery

    loop Every 10s (liveness)
        K8s->>API: GET /health/live
        API->>API: Check process alive
        API-->>K8s: 200 OK {status: "alive"}
    end

    loop Every 30s (readiness)
        K8s->>API: GET /health/ready

        API->>Registry: Check critical models loaded
        Registry-->>API: {eta: loaded, fraud: loaded}

        API->>Redis: PING
        Redis-->>API: PONG (~1ms)

        API->>BQ: SELECT 1
        BQ-->>API: OK (~50ms)

        alt All Checks Pass
            API-->>K8s: 200 OK {status: "ready",<br/>models_loaded: 7,<br/>redis: "connected",<br/>bigquery: "connected"}
        else Any Check Fails
            API-->>K8s: 503 Service Unavailable<br/>{status: "not_ready",<br/>failed_checks: ["redis"]}
        end
    end

    Note over K8s: Failed readiness:<br/>- Pod removed from service<br/>- No traffic routed<br/>- Auto-recovery attempts
```
