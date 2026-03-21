# AI Inference Service - Request Sequence

```mermaid
sequenceDiagram
    actor Client as Caller Service
    participant API as FastAPI Endpoint
    participant CACHE as InferenceCache
    participant REG as ModelRegistry
    participant FS as FeatureStoreClient
    participant ENGINE as InferenceEngine
    participant FALLBACK as RuleBasedFallback
    participant SHADOW as ShadowRunner
    participant PROM as Prometheus

    Client ->> API: POST /predict
    activate API

    API ->> CACHE: get(cache_key)
    activate CACHE
    CACHE -->> API: Optional[result]
    deactivate CACHE

    alt Cache Hit
        API ->> PROM: cache_hit_counter++
        API -->> Client: 200 + cached result
    else Cache Miss
        API ->> REG: resolve_model_config(model_name)
        activate REG
        REG -->> API: ModelConfig
        deactivate REG

        API ->> FS: get_features(feature_keys)
        activate FS
        FS -->> API: Dict[feature_name, value]
        deactivate FS

        alt Features Available
            API ->> ENGINE: predict_with_weights(config, features)
            activate ENGINE
            ENGINE ->> ENGINE: apply_bias_and_weights()
            ENGINE -->> API: float score
            deactivate ENGINE
        else Features Unavailable
            API ->> FALLBACK: fallback_score(model_name)
            activate FALLBACK
            FALLBACK -->> API: float score
            deactivate FALLBACK
        end

        API ->> CACHE: put(cache_key, result)
        activate CACHE
        CACHE ->> CACHE: expire_old_entries()
        deactivate CACHE

        API ->> PROM: latency_histogram.observe(elapsed)
        API ->> PROM: inference_counter++

        alt Shadow Sample Rate Hit
            API ->> SHADOW: async execute_shadow_inference()
            activate SHADOW
            SHADOW ->> PROM: shadow_inference_counter++
            deactivate SHADOW
        end

        API -->> Client: 200 + prediction result
    end

    deactivate API
```

## Sequence Patterns

- **Cache-First**: Check in-memory LRU before fetching features or running inference
- **Feature Retrieval**: Blocking call to FeatureStoreClient (Redis or BigQuery)
- **Fallback Graceful**: If features unavailable, use rule-based scoring
- **Shadow Async**: A/B model execution does not block main response
- **Metrics Emission**: All paths emit latency and counters to Prometheus
