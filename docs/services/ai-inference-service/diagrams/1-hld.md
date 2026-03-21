# AI Inference Service - High-Level Design

```mermaid
flowchart TD
    subgraph Callers
        BFF["mobile-bff-service"]
        CHECKOUT["checkout-orchestrator-service"]
        CATALOG["catalog-service"]
        ORCH["ai-orchestrator-service"]
        SEARCH["search-service"]
    end

    subgraph AIINF["ai-inference-service :8101"]
        direction TB
        API["FastAPI Endpoints<br/>/predict, /predict/batch"]
        REG["ModelRegistry<br/>(versioned weights)"]
        CACHE["InferenceCache<br/>(LRU+TTL)"]
        ENGINE["Inference Engine<br/>(7 models)"]
        SHADOW["Shadow Runner<br/>(A/B routing)"]
        FS_CLIENT["FeatureStoreClient<br/>(Redis/BigQuery/none)"]
        FALLBACK["RuleBasedFallback"]
    end

    subgraph External
        REDIS["Redis<br/>Online Features"]
        BQ["BigQuery<br/>Offline Features"]
        PROM["Prometheus<br/>:8101/metrics"]
    end

    BFF -->|"HTTP POST"| API
    CHECKOUT -->|"HTTP POST"| API
    CATALOG -->|"HTTP POST"| API
    ORCH -->|"HTTP POST"| API
    SEARCH -->|"HTTP POST"| API

    API --> REG
    REG --> ENGINE
    API --> CACHE
    CACHE -->|"miss"| ENGINE
    ENGINE --> FALLBACK
    ENGINE --> SHADOW
    ENGINE --> FS_CLIENT
    FS_CLIENT --> REDIS
    FS_CLIENT --> BQ
    ENGINE --> PROM
    SHADOW --> PROM

    API -->|"response"| BFF
    API -->|"response"| CHECKOUT
```

## Key Characteristics

- **Request Pattern**: All inbound traffic is read-only HTTP POST via FastAPI
- **7 Prediction Models**: ETA, Fraud, Ranking, Demand, Personalization, CLV, Dynamic Pricing
- **In-Memory LRU Cache**: Per-request memoization with configurable TTL (default 300s)
- **Feature Store Integration**: Pluggable (Redis online, BigQuery offline, or request-only)
- **Shadow Mode**: Per-model A/B routing for canary deployment
- **Fallbacks**: Deterministic rule-based scoring if ML artifacts unavailable
- **Observability**: Per-model counters, latency histograms, cache metrics
