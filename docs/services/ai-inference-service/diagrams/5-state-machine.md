# AI Inference Service - Model Lifecycle State Machine

```mermaid
stateDiagram-v2
    [*] --> Unregistered

    Unregistered --> Registered: weights_loaded
    Registered --> Active: is_default_version
    Active --> Shadow: shadow_mode_enabled
    Active --> Inference: request_received

    Shadow --> ShadowInference: execute_async
    ShadowInference --> ShadowMetrics: inference_complete
    ShadowMetrics --> Shadow: metrics_recorded

    Inference --> FeatureFetch: features_required
    Inference --> RuleFallback: no_features_required

    FeatureFetch --> FeatureSuccess: features_available
    FeatureSuccess --> ModelInference: apply_weights

    FeatureFetch --> FeatureFail: features_unavailable
    FeatureFail --> RuleFallback

    ModelInference --> Cached: cache_enabled
    RuleFallback --> Cached

    Cached --> CacheStore: result_computed
    CacheStore --> CacheExpiry: ttl_start
    CacheExpiry --> Inactive: ttl_expired

    CacheStore --> Response: request_complete
    Response --> [*]

    Inactive --> Active: cache_miss_refresh

    note right of Registered
        Registry loaded from disk
        or memory with defaults
    end note

    note right of Active
        Model is ready to serve
        inference requests
    end note

    note right of Shadow
        Alternate model version
        runs for A/B comparison
    end note

    note right of Cached
        Result stored in LRU
        cache with TTL tracking
    end note
```

## State Transitions

- **Registered→Active**: Model weight fully loaded and version is default
- **Active→Inference**: HTTP request routed to model
- **FeatureFetch→RuleFallback**: Features unavailable, use rules
- **ModelInference→Cached**: Result stored in LRU with TTL
- **CacheExpiry→Inactive**: TTL expired, entry removed on next eviction
- **Inactive→Active**: On cache miss, re-fetch and re-infer
