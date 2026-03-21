# AI Inference Service - Data Model

```mermaid
erDiagram
    SETTINGS ||--o{ MODEL_ENTRY : defines
    MODEL_ENTRY ||--|{ MODEL_VERSION : contains
    MODEL_VERSION ||--o{ MODEL_WEIGHTS : uses

    INFERENCE_REQUEST ||--o{ CACHE_KEY : generates
    CACHE_KEY ||--o{ CACHE_ENTRY : maps_to
    CACHE_ENTRY ||--o{ MODEL_RESULT : contains

    INFERENCE_REQUEST ||--o{ FEATURE_KEYS : specifies
    FEATURE_KEYS ||--o{ FEATURE_STORE_ROW : fetches_from

    FEATURE_STORE_ROW ||--o{ FEATURE_VALUE : contains
    MODEL_RESULT ||--o{ SCORE : produces

    MODEL_VERSION ||--o{ SHADOW_CONFIG : has
    MODEL_RESULT ||--o{ SHADOW_RESULT : creates_when_enabled

    METRICS_RECORD ||--o{ PROMETHEUS_GAUGE : writes_to
    METRICS_RECORD ||--o{ PROMETHEUS_HISTOGRAM : writes_to

    SETTINGS {
        string log_level
        boolean cache_enabled
        float cache_ttl_seconds
        int cache_max_items
        string feature_store_backend
        string redis_url
        string bigquery_project
    }

    MODEL_ENTRY {
        string name PK
        string default_version
    }

    MODEL_VERSION {
        string model_name FK
        string version PK
        float bias
        string file_path
    }

    MODEL_WEIGHTS {
        string model_name FK
        string version FK
        string feature_name PK
        float weight
    }

    INFERENCE_REQUEST {
        string model_name
        string request_id PK
        json features
        string version_preference
        boolean execute_shadow
    }

    CACHE_KEY {
        string request_id FK
        string hash_value PK
    }

    CACHE_ENTRY {
        string cache_key PK
        json result
        timestamp created_at
        timestamp expires_at
    }

    FEATURE_KEYS {
        string request_id FK
        string key PK
    }

    FEATURE_STORE_ROW {
        string feature_name PK
        string backend
        timestamp last_updated
    }

    FEATURE_VALUE {
        string feature_store_row FK
        string name PK
        float value
    }

    MODEL_RESULT {
        string request_id PK
        float score
        string model_version
        float confidence
        float latency_ms
    }

    SHADOW_CONFIG {
        string model_name FK
        string version FK
        float sample_rate
        string alternate_version
    }

    SHADOW_RESULT {
        string request_id FK
        string shadow_model
        float shadow_score
        float latency_ms
    }

    METRICS_RECORD {
        string model_name
        string event_type
        float latency_ms
        timestamp recorded_at
    }

    PROMETHEUS_GAUGE {
        string metric_name PK
        float value
    }

    PROMETHEUS_HISTOGRAM {
        string metric_name PK
        float buckets
    }
```

## Key Entities

| Entity | Purpose |
|--------|---------|
| **SETTINGS** | Runtime configuration |
| **MODEL_ENTRY** | Model family definition |
| **MODEL_VERSION** | Specific version with weights |
| **MODEL_WEIGHTS** | Individual feature weights |
| **INFERENCE_REQUEST** | Incoming prediction request |
| **CACHE_ENTRY** | Memoized result with TTL |
| **FEATURE_VALUE** | Retrieved feature data |
| **MODEL_RESULT** | Prediction output |
| **SHADOW_RESULT** | A/B test result |
