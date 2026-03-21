# Wallet Loyalty - Sequence Diagrams

## Happy Path Flow

```mermaid
sequenceDiagram
    participant Client
    participant Service
    participant Cache
    participant DB
    participant Kafka

    Client->>Service: Request
    Service->>Cache: Check
    Cache-->>Service: Miss
    Service->>DB: Query
    DB-->>Service: Data
    Service->>Cache: Store
    Service->>Kafka: Event
    Service-->>Client: Response
```

## Error & Retry

```mermaid
sequenceDiagram
    participant Service
    participant DB
    participant Retry

    Service->>DB: Query
    DB-->>Service: Error
    Service->>Retry: Backoff 1s
    Service->>DB: Retry
    DB-->>Service: Success
```
