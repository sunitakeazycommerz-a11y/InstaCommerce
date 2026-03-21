# Search Service - Sequence, State Machine, End-to-End

## Sequence Diagram: Search Query with Caching

```mermaid
sequenceDiagram
    participant Client
    participant BFF as mobile-bff
    participant Search as search-service
    participant Redis as Redis Cache
    participant ES as Elasticsearch
    participant DB as PostgreSQL

    Client->>BFF: GET /search?q=tomato
    BFF->>Search: Call /search?q=tomato&filters=...
    activate Search

    Search->>Redis: GET search_results:tomato:filters_hash
    Redis-->>Search: cache miss (or expired)

    Search->>ES: Query: match(name, description) tomato
    activate ES
    ES-->>Search: Results: [{id, name, store_id, inventory}]
    deactivate ES

    Search->>Search: Post-process: filter by store availability
    Search->>Search: Rank by: relevance + inventory count
    Search->>Redis: SET search_results:tomato (TTL: 5min)
    Redis-->>Search: Cached

    Search-->>BFF: { results: [...], query_time_ms: 45 }
    deactivate Search

    BFF->>BFF: Format for mobile UI
    BFF-->>Client: JSON response

    == Next identical query (within 5 min) ==

    Client->>BFF: GET /search?q=tomato (identical)
    BFF->>Search: Call /search
    Search->>Redis: GET search_results:tomato
    Redis-->>Search: Hit (cached)
    Search-->>BFF: Cached results (from Redis)
    BFF-->>Client: Fast response (<10ms)
```

## State Machine: Indexing Status

```mermaid
stateDiagram-v2
    [*] --> PRODUCT_INDEXED: ProductCreatedEvent received

    PRODUCT_INDEXED --> INDEXED: ES indexing succeeds\n(replica synced)
    PRODUCT_INDEXED --> PENDING_RETRY: ES indexing fails

    PENDING_RETRY --> INDEXED: Retry succeeds\n(exponential backoff)
    PENDING_RETRY --> DLT: Max retries exceeded\n(routed to Dead-Letter Topic)

    INDEXED --> INDEXED: ProductUpdatedEvent\n(re-index same ID)

    DLT --> [*]: Manual review required\n(SRE investigates)

    INDEXED --> DELETED: ProductDeletedEvent received
    DELETED --> [*]: Removed from ES

    note right of PRODUCT_INDEXED
        Just received from Kafka
        Product info captured
    end note

    note right of INDEXED
        Successfully in Elasticsearch
        Cache updated
        Query-able
    end note

    note right of PENDING_RETRY
        Temporary network issue
        Retry with backoff (1s, 2s, 4s)
    end note

    note right of DLT
        After 5 retries over 1 hour
        Escalate to manual review
    end note
```

## End-to-End: Catalog Update → Search Available

```mermaid
sequenceDiagram
    participant Vendor as Vendor Portal
    participant Catalog as catalog-service
    participant Kafka as Kafka
    participant Search as search-service
    participant Redis as Redis
    participant ES as Elasticsearch
    participant Customer as Customer App

    rect rgb(255, 240, 200)
        Note over Vendor,Catalog: T+0: New Product Listed
    end

    Vendor->>Catalog: Create new product<br/>(name: "Organic Tomatoes",<br/>sku: "TOMATO-01")
    Catalog->>Catalog: Save to PostgreSQL
    Catalog->>Kafka: Emit ProductCreatedEvent

    rect rgb(200, 220, 255)
        Note over Search,Redis: T+1-100ms: Index Update
    end

    Kafka->>Search: ProductCreatedEvent consumed<br/>(batch consumer)
    Search->>Search: Transform to SearchDoc<br/>(tokens, metadata, store_location)
    Search->>ES: Bulk index request
    ES->>ES: Index document<br/>(invert tokenize)<br/>Update replica shards
    ES-->>Search: Success (all shards)
    Search->>Redis: Invalidate search cache<br/>(for product category)
    Search->>Kafka: Emit SearchIndexUpdatedEvent

    rect rgb(220, 255, 220)
        Note over Search,Customer: T+200ms: Searchable
    end

    Customer->>Customer: Types "tomato"<br/>in app search
    Customer->>Search: GET /search?q=tomato
    Search->>ES: Query for "tomato"
    ES-->>Search: Returns new product\n(ranking: high relevance,<br/>in stock)
    Search->>Redis: Cache results
    Search-->>Customer: Results include<br/>"Organic Tomatoes"

    Customer->>Customer: Clicks product<br/>Adds to cart<br/>Checks out
```

---

**Search SLO**: <200ms p99 latency; 99% availability
**Indexing SLO**: New products searchable within 500ms
**Cache TTL**: 5 minutes (balance freshness vs hit rate)
