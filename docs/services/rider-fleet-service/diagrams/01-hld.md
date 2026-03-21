# Rider Fleet Service - High-Level Design (HLD)

## Overview

Rider Fleet Service manages rider profiles, availability, location tracking, and assignment history.

```mermaid
graph TB
    subgraph "Clients"
        Dispatch["Dispatch Optimizer<br/>POST /assign"]
        Rider["Rider App<br/>Status updates"]
    end

    subgraph "Rider Fleet Service"
        API["REST API :8091"]
        RiderMgr["Rider Manager"]
        StatusMgr["Status Manager"]
        LocationMgr["Location Manager"]
        Recovery["Recovery Jobs"]
    end

    subgraph "External"
        PostgreSQL["PostgreSQL<br/>riders, locations"]
        Kafka["Kafka<br/>rider.events"]
    end

    Dispatch -->|Query/Update| API
    Rider -->|Location, Status| API
    API --> RiderMgr
    API --> StatusMgr
    API --> LocationMgr
    LocationMgr --> PostgreSQL
    RiderMgr --> PostgreSQL
    StatusMgr --> Kafka
    Recovery -->|Cleanup| PostgreSQL

    style Recovery fill:#ffebee
```

## Key Features

- Rider availability tracking (AVAILABLE, ASSIGNED, ON_DELIVERY, OFF_DUTY)
- Optimistic locking for concurrent assignments
- Real-time location stream via Kafka
- Stuck rider detection
- SLO: 99.9% availability, <1.5s P99

