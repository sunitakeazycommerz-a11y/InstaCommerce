# Inventory Service - High-Level Design (HLD)

## Overview

Inventory Service manages stock levels, reservations, and stock adjustments with row-level locking for concurrent access.

```mermaid
graph TB
    subgraph "Clients"
        Checkout["Checkout Service"]
        Fulfillment["Fulfillment Service"]
        Admin["Admin Portal"]
    end

    subgraph "Inventory Service"
        API["REST API :8083"]
        ReservationEngine["Reservation Engine<br/>TTL-based"]
        StockManager["Stock Manager<br/>Adjustments"]
        Scheduler["Expiry Scheduler<br/>Cleanup"]
    end

    subgraph "Data"
        PostgreSQL["PostgreSQL<br/>Stock + Reservations<br/>Row-level locks"]
        Kafka["Kafka<br/>inventory.events"]
    end

    Checkout -->|Reserve stock| API
    Fulfillment -->|Release reservation| API
    Admin -->|Adjust stock| API
    API --> ReservationEngine
    API --> StockManager
    ReservationEngine --> PostgreSQL
    StockManager --> PostgreSQL
    Scheduler -->|Cleanup expired| PostgreSQL
    PostgreSQL --> Kafka

    style ReservationEngine fill:#e8f5e9
    style Scheduler fill:#e8f5e9
```

## Key Features

- Row-level locking (SELECT...FOR UPDATE)
- TTL-based reservations (5 min auto-expire)
- Low-stock alerts
- Store-based stock tracking
- Concurrent reservation support (60 DB connections)
- SLO: 99.95% availability, <800ms P99

