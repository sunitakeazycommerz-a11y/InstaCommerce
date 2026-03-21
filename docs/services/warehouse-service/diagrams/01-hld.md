# Warehouse Service - High-Level Design (HLD)

## Overview

Warehouse Service provides store location management, zone configuration, and geographic lookups for the fulfillment pipeline.

```mermaid
graph TB
    subgraph "API Clients"
        Admin["Admin Portal"]
        Fulfillment["Fulfillment Service"]
        Mobile["Mobile App"]
    end

    subgraph "Warehouse Service"
        API["REST API :8090"]
        StoreOps["Store Operations"]
        ZoneOps["Zone Management"]
        CacheLayer["Cache Layer<br/>Caffeine/Redis"]
    end

    subgraph "External Services"
        GeoDB["PostGIS Database<br/>Geospatial Queries"]
    end

    Admin -->|Create/Update stores| API
    Fulfillment -->|Get store coords| API
    Mobile -->|Find nearby stores| API
    API --> StoreOps
    API --> ZoneOps
    StoreOps --> CacheLayer
    ZoneOps --> CacheLayer
    CacheLayer -->|Cache miss| GeoDB
    GeoDB -->|PostGIS| CacheLayer

    style CacheLayer fill:#fff59d
```

## Data Model

```mermaid
graph LR
    Store["Store<br/>(lat, long, radius)"]
    Zone["Zone<br/>(aisle, shelf)"]
    Hours["Hours<br/>(open, close)"]

    Store -->|1:N| Zone
    Store -->|1:N| Hours

    Store -->|PostGIS| Geo["Geospatial Index"]
```

## Key Responsibilities

| Component | Purpose |
|-----------|---------|
| Store Management | CRUD stores, maintain location data |
| Zone Management | Create/list picking zones (aisles, shelves) |
| Geographic Lookup | Find nearest store(s) within radius |
| Hours Management | Store operating hours tracking |
| Cache Coordination | Caffeine L1 + optional Redis L2 |

## SLO Targets

- Availability: 99.9%
- Store Lookup Latency: <50ms (with cache)
- Cache Hit Ratio: >95%
- Geospatial Query: <100ms
