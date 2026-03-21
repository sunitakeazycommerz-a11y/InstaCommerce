# AI Inference Service - Request Flowchart

```mermaid
flowchart TD
    A["Incoming HTTP POST<br/>/predict or /predict/batch"] --> B["Parse RequestBody"]
    B --> C{Model Registered?}
    C -->|No| D["Return 400<br/>Bad Request"]
    C -->|Yes| E["Generate CacheKey"]
    E --> F{Cache Enabled?}
    F -->|Yes| G{CacheHit?}
    G -->|Yes| H["Emit CacheHit<br/>metric"]
    H --> I["Return cached<br/>result"]
    G -->|No| J["Emit CacheMiss<br/>metric"]
    J --> K["Select Model<br/>Version"]
    F -->|No| K

    K --> L{WeightsAvailable?}
    L -->|No| M["Use RuleBasedFallback"]
    M --> N["Score transaction"]
    N --> O["Cache result"]
    L -->|Yes| P["Resolve FeatureKeys"]
    P --> Q["Fetch Features<br/>from FeatureStore"]
    Q --> R{FeaturesOK?}
    R -->|No| M
    R -->|Yes| S["Linear/ML Inference<br/>Apply weights + bias"]
    S --> O

    O --> T{ShadowSample?}
    T -->|Yes| U["Async: ExecuteShadow<br/>for alternate model"]
    U --> V["RecordShadowMetrics"]
    T -->|No| V

    V --> W["RecordInference<br/>latency + model"]
    W --> X["Return 200<br/>with prediction + version"]
    D --> Y["HTTP Response"]
    I --> Y
    X --> Y
```

## Flow Details

1. **Request Parsing**: Validate model name, features, version preference
2. **Cache Check**: If enabled, check LRU cache (key = model+features hash)
3. **Model Resolution**: Fetch active or specified version from registry
4. **Feature Retrieval**: Call FeatureStoreClient (Redis/BigQuery/none)
5. **Inference**: Linear regression or ML model application
6. **Fallback**: Rule-based scoring if weights/features unavailable
7. **Shadow Mode**: Optionally run alternate model asynchronously
8. **Metrics**: Emit Prometheus counters and histograms
9. **Response**: Return JSON with score, model version, confidence
