# AI Inference Service - Low-Level Design

```mermaid
classDiagram
    class Settings {
        -log_level: str
        -cache_enabled: bool
        -cache_ttl_seconds: float
        -cache_max_items: int
        -shadow_models: Dict[str, str]
        -shadow_sample_rate: float
        -feature_store_backend: str
        -redis_url: str
        -bigquery_project: str
    }

    class ModelRegistry {
        -entries: Dict[str, ModelEntry]
        +get_model_entry(name: str) ModelEntry
        +get_active_version(name: str) ModelConfig
        +resolve_model_config(name: str, version: str) ModelConfig
    }

    class ModelEntry {
        -default_version: str
        -versions: Dict[str, ModelConfig]
    }

    class ModelConfig {
        -version: str
        -bias: float
        -weights: Dict[str, float]
    }

    class InferenceCache {
        -cache: OrderedDict
        -ttl_map: Dict
        -max_items: int
        +get(key: str) Optional
        +put(key: str, value: Any)
        +clear_expired()
    }

    class InferenceEngine {
        -registry: ModelRegistry
        -cache: InferenceCache
        -fs_client: FeatureStoreClient
        +predict_eta(request) Dict
        +predict_fraud(request) Dict
        +predict_ranking(request) Dict
        +predict_demand(request) Dict
        +predict_personalization(request) Dict
        +predict_clv(request) Dict
        +predict_dynamic_pricing(request) Dict
    }

    class FeatureStoreClient {
        -backend: str
        -redis_client: Optional
        -bq_client: Optional
        +get_features(feature_keys) Dict
        +get_from_redis(keys) Dict
        +get_from_bigquery(table, keys) Dict
    }

    class ShadowRunner {
        -sample_rate: float
        +should_run_shadow() bool
        +execute_shadow_inference(model_name, request) Task
        +emit_shadow_metrics(model_name, result)
    }

    class MetricsCollector {
        -inference_counter: Counter
        -latency_histogram: Histogram
        -cache_hit_counter: Counter
        -cache_miss_counter: Counter
        +record_inference(model, latency)
        +record_cache_event(hit_miss)
    }

    ModelRegistry --> ModelEntry
    ModelEntry --> ModelConfig
    InferenceEngine --> ModelRegistry
    InferenceEngine --> InferenceCache
    InferenceEngine --> FeatureStoreClient
    InferenceEngine --> ShadowRunner
    InferenceEngine --> MetricsCollector
    Settings --> InferenceEngine
```

## Component Responsibilities

| Component | Responsibility |
|-----------|-----------------|
| **Settings** | Runtime configuration from environment variables |
| **ModelRegistry** | Manages versioned model weights; version resolution |
| **InferenceCache** | LRU in-memory cache with TTL expiry |
| **InferenceEngine** | Orchestrates prediction across 7 model families |
| **FeatureStoreClient** | Pluggable feature retrieval (Redis/BigQuery/none) |
| **ShadowRunner** | A/B testing for new model versions |
| **MetricsCollector** | Prometheus metrics emission |
