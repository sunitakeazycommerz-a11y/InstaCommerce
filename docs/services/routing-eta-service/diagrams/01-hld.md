# Routing ETA Service - High-Level Design (HLD)

```mermaid
graph TB
    subgraph "Clients"
        Mobile["Mobile App"]
        Fulfillment["Fulfillment Service"]
        BFF["Mobile BFF"]
    end

    subgraph "Routing ETA Service"
        API["REST API :8086"]
        ETAEngine["ETA Calculation<br/>Distance + Traffic"]
        RiderTracking["Rider Tracking<br/>Location Stream"]
        GeoIndex["Redis Geo Index"]
    end

    subgraph "External"
        LocationStream["Kafka<br/>rider.location.updates"]
        PostgreSQL["PostgreSQL<br/>Delivery data"]
    end

    Mobile -->|GET /eta| API
    Fulfillment -->|Batch ETA| API
    BFF -->|Real-time ETA| API
    API --> ETAEngine
    API --> GeoIndex
    LocationStream -->|Consume| RiderTracking
    RiderTracking --> PostgreSQL
    GeoIndex -->|Redis GEO| PostgreSQL

    style GeoIndex fill:#ffccbc
```

## Key Features

- Distance calculation: Haversine formula
- Traffic data: Historical averages
- Rider tracking: Real-time location indexing
- Batch ETA: Optimize multiple orders
- SLO: 99%, <200ms P99

