# Dispatch Optimizer Service - High-Level Design (HLD)

## Overview

Dispatch Optimizer assigns orders to riders using ML model that considers location, availability, capacity, and zone balance.

```mermaid
graph TB
    subgraph "Fulfillment Pipeline"
        Fulfillment["Fulfillment Service<br/>OrderPacked ready"]
    end

    subgraph "Dispatch Optimizer Service"
        API["REST API :8087"]
        Optimizer["Optimization Engine<br/>ML Model"]
        RiderMgr["Rider Manager<br/>Location + availability"]
        Solver["Route Solver<br/>Assignment optimization"]
    end

    subgraph "External Services"
        RiderSvc["Rider Fleet Service<br/>GET /riders/available"]
        LocationStream["Kafka<br/>rider.location.updates"]
    end

    subgraph "Infrastructure"
        RedisGeo["Redis GEO<br/>Rider positions"]
    end

    Fulfillment -->|POST /assign| API
    API --> Optimizer
    Optimizer --> Solver
    Optimizer --> RiderMgr
    RiderMgr -->|Query| RiderSvc
    LocationStream -->|Stream| RedisGeo
    RiderMgr -->|Query| RedisGeo

    style Optimizer fill:#f3e5f5
    style Solver fill:#f3e5f5
```

## ML Model Features

- Distance to rider
- Rider current load
- Zone balance
- Historical acceptance rate
- Delivery success rate

## SLO Targets

- Availability: 99%
- Assignment latency: <100ms
- Successful assignment rate: >95%

