# Admin Gateway - End-to-End Flow

## Complete User Journey

```mermaid
graph LR
    A["User Action"] --> B["Request<br/>API"]
    B --> C["Validate<br/>Input"]
    C --> D["Check<br/>Cache"]
    D --> E["Process<br/>Logic"]
    E --> F["Store<br/>Result"]
    F --> G["Publish<br/>Event"]
    G --> H["Return<br/>Response"]
    H --> I["Update<br/>UI"]
    
    style A fill:#e1f5ff
    style B fill:#b3e5fc
    style C fill:#81d4fa
    style D fill:#4fc3f7
    style E fill:#29b6f6
    style F fill:#03a9f4
    style G fill:#039be5
    style H fill:#0288d1
    style I fill:#01579b
```

## Latency Breakdown

| Stage | Latency |
|-------|---------|
| API gateway | ~5ms |
| Validation | ~10ms |
| Cache check | ~5ms |
| Processing | ~200ms |
| Storage | ~30ms |
| Event publish | ~20ms |
| **Total (p99)** | **~270ms** |

## SLO Metrics

- **Availability**: 99.9%
- **Latency p99**: <500ms
- **Error rate**: <0.1%
- **Cache hit rate**: >90%
