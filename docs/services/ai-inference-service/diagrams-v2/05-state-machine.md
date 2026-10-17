# AI Inference Service - State Machine Diagrams

## Model Deployment State Machine

```mermaid
stateDiagram-v2
    [*] --> TRAINING: ML team triggers training<br/>(ml/train/)

    TRAINING --> TRAINED: Training complete<br/>(model artifacts generated)

    TRAINED --> VALIDATING: Run evaluation gates<br/>(ml/eval/evaluate.py)

    VALIDATING --> VALIDATION_FAILED: ❌ Metrics below threshold<br/>(AUC, RMSE, latency)
    VALIDATING --> VALIDATED: ✅ All gates passed

    VALIDATION_FAILED --> TRAINING: Retrain with<br/>adjusted hyperparameters

    VALIDATED --> REGISTERING: Register model version<br/>(upload to GCS)

    REGISTERING --> REGISTERED: Model artifacts stored<br/>in model registry

    REGISTERED --> SHADOW_TESTING: Deploy as shadow<br/>(log-only, 0% traffic)

    SHADOW_TESTING --> SHADOW_FAILED: ❌ Drift detected<br/>or latency regression
    SHADOW_TESTING --> SHADOW_PASSED: ✅ Shadow metrics OK<br/>(7-day observation)

    SHADOW_FAILED --> REGISTERED: Rollback to previous

    SHADOW_PASSED --> CANARY: Promote to canary<br/>(5-10% traffic)

    CANARY --> CANARY_FAILED: ❌ Error rate spike<br/>or SLO breach
    CANARY --> CANARY_PASSED: ✅ Canary metrics OK<br/>(24-48hr observation)

    CANARY_FAILED --> SHADOW_TESTING: Rollback to shadow

    CANARY_PASSED --> ROLLING_OUT: Gradual rollout<br/>(10% → 50% → 100%)

    ROLLING_OUT --> PRODUCTION: ✅ 100% traffic<br/>new model is primary

    PRODUCTION --> DEPRECATED: Newer version<br/>reaches production

    DEPRECATED --> ARCHIVED: After 30 days<br/>(remove from cache)

    ARCHIVED --> [*]

    note right of SHADOW_TESTING
        Shadow mode:
        - Runs alongside primary
        - Predictions logged only
        - Never served to users
        - Drift detection active
    end note

    note right of CANARY
        Canary deployment:
        - Sticky user assignment
        - Real traffic subset
        - Full monitoring
        - Quick rollback (<5min)
    end note
```

## Inference Request State Machine

```mermaid
stateDiagram-v2
    [*] --> RECEIVED: HTTP POST /predict/{model}

    RECEIVED --> PARSING: Parse request body

    PARSING --> PARSE_ERROR: ❌ Invalid JSON
    PARSING --> PARSED: ✅ Valid request

    PARSE_ERROR --> ERROR_RESPONSE: Return 400

    PARSED --> VALIDATING: Validate schema<br/>(required fields, types)

    VALIDATING --> VALIDATION_ERROR: ❌ Schema violation
    VALIDATING --> VALIDATED: ✅ Schema valid

    VALIDATION_ERROR --> ERROR_RESPONSE: Return 400

    VALIDATED --> AUTH_CHECK: Check authorization

    AUTH_CHECK --> AUTH_FAILED: ❌ Invalid token
    AUTH_CHECK --> AUTHORIZED: ✅ Token valid

    AUTH_FAILED --> ERROR_RESPONSE: Return 401

    AUTHORIZED --> RATE_LIMIT_CHECK: Check rate limit

    RATE_LIMIT_CHECK --> RATE_LIMITED: ❌ Limit exceeded
    RATE_LIMIT_CHECK --> RATE_OK: ✅ Within limit

    RATE_LIMITED --> ERROR_RESPONSE: Return 429

    RATE_OK --> CACHE_CHECK: Build cache key<br/>check LRU cache

    CACHE_CHECK --> CACHE_HIT: ✅ Found in cache
    CACHE_CHECK --> CACHE_MISS: Cache miss

    CACHE_HIT --> SERVING: Return cached result

    CACHE_MISS --> MODEL_RESOLVE: Resolve model version

    MODEL_RESOLVE --> MODEL_NOT_FOUND: ❌ Model doesn't exist
    MODEL_RESOLVE --> MODEL_FOUND: ✅ Model resolved

    MODEL_NOT_FOUND --> ERROR_RESPONSE: Return 404

    MODEL_FOUND --> FEATURE_FETCH: Fetch features<br/>(Redis + BigQuery)

    FEATURE_FETCH --> FEATURE_TIMEOUT: ⚠️ Feature store timeout
    FEATURE_FETCH --> FEATURES_READY: ✅ Features fetched

    FEATURE_TIMEOUT --> DEGRADED_FEATURES: Use request-only<br/>+ defaults

    FEATURES_READY --> TRANSFORMING: Transform features
    DEGRADED_FEATURES --> TRANSFORMING

    TRANSFORMING --> TRANSFORMED: Features ready

    TRANSFORMED --> INFERRING: Run ML inference

    INFERRING --> INFERENCE_ERROR: ⚠️ Model error
    INFERRING --> INFERENCE_OK: ✅ Prediction made

    INFERENCE_ERROR --> FALLBACK: Invoke rule fallback

    FALLBACK --> FALLBACK_OK: ✅ Fallback score

    INFERENCE_OK --> POST_PROCESSING: Calibrate, clip
    FALLBACK_OK --> POST_PROCESSING

    POST_PROCESSING --> PROCESSED: Final score ready

    PROCESSED --> CACHING: Store in cache

    CACHING --> CACHED: Cached with TTL

    CACHED --> SERVING: Build response

    SERVING --> SUCCESS_RESPONSE: Return 200 OK

    SUCCESS_RESPONSE --> [*]
    ERROR_RESPONSE --> [*]

    note right of CACHE_HIT
        Cache hit saves:
        - ~50-150ms latency
        - Feature store calls
        - Model inference
    end note

    note right of FALLBACK
        Fallback provides:
        - Graceful degradation
        - Deterministic behavior
        - Always returns score
    end note
```

## Model Loading State Machine

```mermaid
stateDiagram-v2
    [*] --> COLD: Model not in memory<br/>(first request or evicted)

    COLD --> RESOLVING: Request for model<br/>(model_name, version)

    RESOLVING --> CONFIG_LOAD: Load model config<br/>(config.yaml)

    CONFIG_LOAD --> CONFIG_ERROR: ❌ Config not found
    CONFIG_LOAD --> CONFIG_LOADED: ✅ Config loaded

    CONFIG_ERROR --> [*]: Return ModelNotFoundError

    CONFIG_LOADED --> VERSION_RESOLVE: Resolve version<br/>(latest, specific, canary)

    VERSION_RESOLVE --> VERSION_ERROR: ❌ Version not found
    VERSION_RESOLVE --> VERSION_RESOLVED: ✅ Version resolved

    VERSION_ERROR --> [*]: Return ModelNotFoundError

    VERSION_RESOLVED --> DOWNLOADING: Download from GCS<br/>(model.pkl, ~50MB)

    DOWNLOADING --> DOWNLOAD_ERROR: ❌ GCS error<br/>(network, permissions)
    DOWNLOADING --> DOWNLOADED: ✅ Weights downloaded

    DOWNLOAD_ERROR --> RETRY: Retry with backoff<br/>(max 3 attempts)

    RETRY --> DOWNLOADING: Attempt retry
    RETRY --> LOAD_FAILED: ❌ Retries exhausted

    LOAD_FAILED --> [*]: Return ModelLoadError

    DOWNLOADED --> LOADING: Load into memory<br/>(joblib.load / torch.load)

    LOADING --> LOAD_ERROR: ❌ Corrupted weights
    LOADING --> LOADED: ✅ Model loaded

    LOAD_ERROR --> [*]: Return ModelLoadError

    LOADED --> WRAPPING: Wrap in ModelWrapper<br/>(unified interface)

    WRAPPING --> WRAPPED: Wrapper created

    WRAPPED --> CACHING: Store in ModelCache

    CACHING --> HOT: Model ready<br/>(in-memory)

    HOT --> SERVING: Serve predictions<br/>(~0ms lookup)

    SERVING --> HOT: Continue serving

    HOT --> EVICTED: Memory pressure<br/>or model update

    EVICTED --> COLD: Model removed<br/>from cache

    note right of HOT
        Hot model:
        - Zero load latency
        - Direct predict() calls
        - Stays until evicted
    end note

    note right of DOWNLOADING
        Cold load latency:
        - ~2-5s (depends on size)
        - First request waits
        - Subsequent requests fast
    end note
```

## Feature Store Client State Machine

```mermaid
stateDiagram-v2
    [*] --> IDLE: FeatureStoreClient initialized

    IDLE --> FETCHING: fetch_features(entity_ids)

    FETCHING --> REDIS_FETCH: Fetch online features

    REDIS_FETCH --> REDIS_CONNECTED: ✅ Redis available

    REDIS_CONNECTED --> REDIS_QUERY: MGET user_features, item_features

    REDIS_QUERY --> REDIS_SUCCESS: ✅ Features returned
    REDIS_QUERY --> REDIS_TIMEOUT: ⚠️ Redis timeout

    REDIS_SUCCESS --> REDIS_PARSE: Parse JSON features

    REDIS_PARSE --> ONLINE_READY: Online features ready

    REDIS_TIMEOUT --> ONLINE_DEGRADED: Use empty/defaults<br/>for online features

    REDIS_FETCH --> REDIS_DISCONNECTED: ❌ Redis unavailable

    REDIS_DISCONNECTED --> ONLINE_DEGRADED

    ONLINE_READY --> BQ_CHECK: Need offline features?
    ONLINE_DEGRADED --> BQ_CHECK

    BQ_CHECK --> LOCAL_CACHE: Check local cache<br/>(1hr TTL)

    LOCAL_CACHE --> CACHE_HIT: ✅ Cached offline features
    LOCAL_CACHE --> CACHE_MISS: Cache miss

    CACHE_HIT --> OFFLINE_READY: Offline features ready

    CACHE_MISS --> BQ_QUERY: Query BigQuery

    BQ_QUERY --> BQ_SUCCESS: ✅ Query returned
    BQ_QUERY --> BQ_TIMEOUT: ⚠️ BQ timeout

    BQ_SUCCESS --> BQ_CACHE: Cache locally (1hr)

    BQ_CACHE --> OFFLINE_READY

    BQ_TIMEOUT --> STALE_CACHE: Use stale cache<br/>if available

    STALE_CACHE --> OFFLINE_DEGRADED: ⚠️ Stale/default features

    OFFLINE_READY --> MERGING: Merge all sources
    OFFLINE_DEGRADED --> MERGING

    MERGING --> TRANSFORMING: Apply transformations

    TRANSFORMING --> COMPLETE: Return FeatureVector

    COMPLETE --> IDLE

    note right of ONLINE_DEGRADED
        Graceful degradation:
        - Request-only features used
        - Model still makes prediction
        - May be less accurate
    end note

    note right of STALE_CACHE
        Stale is better than none:
        - Use cached if <24hr old
        - Log staleness warning
        - Track degraded metrics
    end note
```

## Circuit Breaker State Machine (Feature Store)

```mermaid
stateDiagram-v2
    [*] --> CLOSED: Normal operation<br/>Redis/BQ healthy

    CLOSED --> CALL_SUCCESS: Request succeeds

    CALL_SUCCESS --> RESET_COUNTER: error_count = 0

    RESET_COUNTER --> CLOSED

    CLOSED --> CALL_FAILURE: Request fails<br/>(timeout, error)

    CALL_FAILURE --> INCREMENT: error_count += 1

    INCREMENT --> CHECK_THRESHOLD: Check failure threshold

    CHECK_THRESHOLD --> CLOSED: error_count < 5
    CHECK_THRESHOLD --> OPEN: error_count >= 5<br/>or error_rate > 50%

    OPEN --> FAST_FAIL: All requests<br/>fail immediately

    FAST_FAIL --> USE_FALLBACK: Return default features<br/>or cached values

    USE_FALLBACK --> EMIT_METRICS: circuit_breaker_open_total

    EMIT_METRICS --> WAIT: Wait timeout<br/>(30 seconds)

    WAIT --> HALF_OPEN: Try test request

    HALF_OPEN --> TEST_SUCCESS: ✅ Test passed

    HALF_OPEN --> TEST_FAILURE: ❌ Test failed

    TEST_SUCCESS --> CLOSED: Service recovered

    TEST_FAILURE --> OPEN: Extend backoff<br/>(60s, 120s, ...)

    note right of OPEN
        Open state:
        - No calls to service
        - Use cached/default
        - Reduces load on failing service
    end note

    note right of HALF_OPEN
        Half-open probing:
        - Single test request
        - If OK → close
        - If fail → reopen
    end note
```

## A/B Experiment State Machine

```mermaid
stateDiagram-v2
    [*] --> DRAFT: Experiment created<br/>(not active)

    DRAFT --> CONFIGURING: Set parameters<br/>(variants, traffic split)

    CONFIGURING --> CONFIGURED: Config complete<br/>(control 90%, treatment 10%)

    CONFIGURED --> REVIEW: Submit for review

    REVIEW --> REJECTED: ❌ Review failed<br/>(invalid config, risk)

    REJECTED --> CONFIGURING: Fix issues

    REVIEW --> APPROVED: ✅ Review passed

    APPROVED --> STARTING: Enable experiment

    STARTING --> ACTIVE: Experiment live<br/>(routing traffic)

    ACTIVE --> ROUTING: Route each request<br/>(hash user_id)

    ROUTING --> CONTROL: 90% → control model
    ROUTING --> TREATMENT: 10% → treatment model

    CONTROL --> LOGGING: Log prediction + variant
    TREATMENT --> LOGGING

    LOGGING --> ACTIVE

    ACTIVE --> ANALYZING: Periodic analysis<br/>(statistical significance)

    ANALYZING --> ACTIVE: Continue<br/>(not significant yet)

    ANALYZING --> WINNER_FOUND: ✅ Significant result<br/>(p < 0.05)

    WINNER_FOUND --> DECISION: Review results

    DECISION --> ROLLOUT: Winner = treatment<br/>→ promote to 100%

    DECISION --> ROLLBACK: Winner = control<br/>→ stop treatment

    ROLLOUT --> COMPLETED: Treatment is new default

    ROLLBACK --> COMPLETED: Control remains

    COMPLETED --> ARCHIVED: After analysis period

    ARCHIVED --> [*]

    note right of ACTIVE
        Active experiment:
        - Sticky user assignment
        - Same user = same variant
        - Full metrics collection
    end note

    note right of ANALYZING
        Statistical analysis:
        - Sample size check
        - P-value calculation
        - Effect size estimation
    end note
```

## Inference Cache Entry State Machine

```mermaid
stateDiagram-v2
    [*] --> MISS: Cache key not found

    MISS --> COMPUTING: Compute prediction<br/>(features + model)

    COMPUTING --> COMPUTED: Prediction ready

    COMPUTED --> STORING: Store in LRU cache

    STORING --> FRESH: Entry created<br/>(TTL starts)

    FRESH --> SERVING: Serve from cache

    SERVING --> FRESH: Still within TTL

    FRESH --> STALE: TTL approaching<br/>(refresh window)

    STALE --> BACKGROUND_REFRESH: Trigger async refresh

    BACKGROUND_REFRESH --> FRESH: ✅ Refresh succeeded

    BACKGROUND_REFRESH --> STALE: ⚠️ Refresh failed<br/>(serve stale)

    STALE --> EXPIRED: TTL exceeded

    EXPIRED --> EVICTED: Remove from cache

    EVICTED --> MISS

    FRESH --> LRU_EVICTED: Cache full<br/>(LRU eviction)

    LRU_EVICTED --> MISS

    note right of FRESH
        Cache entry TTLs:
        - ETA: 60s (volatile)
        - Fraud: NO CACHE
        - Ranking: 300s
        - Demand: 3600s
    end note

    note right of STALE
        Stale-while-revalidate:
        - Serve stale immediately
        - Refresh in background
        - User gets fast response
    end note
```

## Service Health State Machine

```mermaid
stateDiagram-v2
    [*] --> STARTING: Container starts

    STARTING --> INITIALIZING: Load dependencies<br/>(FastAPI, models)

    INITIALIZING --> LOADING_MODELS: Pre-warm critical models<br/>(ETA, Fraud)

    LOADING_MODELS --> MODELS_LOADED: ✅ Critical models ready

    LOADING_MODELS --> MODELS_PARTIAL: ⚠️ Some models failed<br/>(continue with available)

    MODELS_LOADED --> CONNECTING: Connect to Redis, BQ

    MODELS_PARTIAL --> CONNECTING

    CONNECTING --> CONNECTED: ✅ All connections OK

    CONNECTING --> PARTIAL_CONNECTED: ⚠️ Some connections failed

    CONNECTED --> READY: Service ready<br/>(/health/ready → 200)

    PARTIAL_CONNECTED --> DEGRADED: Service degraded<br/>(/health/ready → 200,<br/>degraded: true)

    READY --> SERVING: Accept traffic

    DEGRADED --> SERVING: Accept traffic<br/>(with fallbacks)

    SERVING --> READY: All systems healthy
    SERVING --> DEGRADED: Dependency failure

    READY --> UNHEALTHY: Critical failure<br/>(process crash, OOM)

    DEGRADED --> UNHEALTHY

    UNHEALTHY --> NOT_READY: /health/ready → 503

    NOT_READY --> DRAINING: K8s removes from service

    DRAINING --> TERMINATING: Graceful shutdown<br/>(finish in-flight)

    TERMINATING --> [*]

    note right of READY
        Ready state:
        - All models loaded
        - All connections OK
        - Accept full traffic
    end note

    note right of DEGRADED
        Degraded state:
        - Some features unavailable
        - Fallbacks active
        - Still serves requests
    end note
```
