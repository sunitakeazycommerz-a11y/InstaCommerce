# Search Service - Low-Level Design

## Component Architecture

```mermaid
graph LR
    subgraph "REST API Layer"
        SC["SearchController<br/>-searchService<br/>-trendingService<br/>+search()<br/>+autocomplete()<br/>+trending()"]
    end

    subgraph "Business Logic Layer"
        SS["SearchService<br/>-searchDocumentRepository<br/>-trendingService<br/>+search()<br/>+autocomplete()<br/>-buildFacets()<br/>@Cacheable"]

        TS["TrendingService<br/>-trendingQueryRepository<br/>+recordQuery()<br/>+getTrending()"]
    end

    subgraph "Kafka Consumer Layer"
        CEC["CatalogEventConsumer<br/>-searchIndexService<br/>+handleCatalogEvent()<br/>-handleProductUpsert()<br/>-handleProductDelisted()"]

        IEC["InventoryEventConsumer<br/>-searchIndexService<br/>+handleInventoryEvent()<br/>-handleStockChanged()"]
    end

    subgraph "Index Management Layer"
        SIS["SearchIndexService<br/>-searchDocumentRepository<br/>+upsertDocument()<br/>+deleteDocument()<br/>+updateStock()"]
    end

    subgraph "Repository/DAO Layer"
        SDR["SearchDocumentRepository<br/>@Query fullTextSearch<br/>@Query autocomplete<br/>@Query facetByBrand<br/>@Query facetByCategory"]

        TQR["TrendingQueryRepository<br/>@Query findTopByHitCount<br/>+save()"]
    end

    subgraph "Database Layer"
        PG["PostgreSQL<br/>search_documents table<br/>trending_queries table<br/>shedlock table<br/>GIN Indexes<br/>Triggers"]
    end

    subgraph "Infrastructure"
        CACHE["Caffeine Cache<br/>searchResults<br/>autocomplete<br/>10000 entries<br/>300s TTL"]
    end

    SC --> SS
    SC --> TS

    SS --> SDR
    SS --> TS
    SS --> CACHE

    TS --> TQR

    CEC --> SIS
    IEC --> SIS

    SIS --> SDR

    SDR --> PG
    TQR --> PG

    SS -.-> CACHE
```

## Data Flow Diagrams

### Search Query Flow

```mermaid
sequenceDiagram
    participant Client as "Client<br/>(BFF/Mobile)"
    participant SC as "SearchController"
    participant Cache as "Caffeine Cache"
    participant SS as "SearchService"
    participant Repo as "SearchDocumentRepository"
    participant DB as "PostgreSQL"

    Client->>SC: GET /search?query=milk&page=0

    SC->>SS: search(query, brand, category, ...)

    SS->>Cache: Check cache<br/>key = milk_null_null_0_20

    alt Cache Hit
        Cache-->>SS: Return cached results
    else Cache Miss
        SS->>Repo: fullTextSearch(query, ...)
        Repo->>DB: SELECT * FROM search_documents<br/>WHERE search_vector @@ to_tsquery(...)
        DB-->>Repo: Page<Object[]>
        Repo-->>SS: Page<Object[]>

        SS->>SS: buildFacets(query)
        Repo->>DB: SELECT brand, COUNT(*)<br/>FROM search_documents WHERE ...
        DB-->>Repo: Facet results

        SS->>Cache: Store results<br/>300s TTL
    end

    SS-->>SC: SearchResponse
    SC-->>Client: HTTP 200 + JSON
```

### Index Update Flow

```mermaid
sequenceDiagram
    participant Catalog as "catalog-service"
    participant Kafka as "Kafka<br/>catalog.events"
    participant CEC as "CatalogEventConsumer"
    participant SIS as "SearchIndexService"
    participant Repo as "SearchDocumentRepository"
    participant DB as "PostgreSQL"

    Catalog->>Kafka: Publish ProductUpdated<br/>{productId, name, price, ...}

    Kafka->>CEC: Poll message

    CEC->>CEC: Deserialize envelope<br/>Extract eventType, payload

    alt ProductCreated/ProductUpdated
        CEC->>SIS: upsertDocument(productId, name, ...)
        SIS->>Repo: save(searchDocument)
        Repo->>DB: INSERT ... ON CONFLICT UPDATE

        DB->>DB: TRIGGER: recalculate search_vector
        Note over DB: setweight(to_tsvector('english', name), 'A')<br/>setweight(to_tsvector('english', brand), 'B')

        DB-->>Repo: Rows affected
        Repo-->>SIS: SearchDocument
        SIS-->>CEC: Success
    else ProductDeactivated/ProductDelisted
        CEC->>SIS: deleteDocument(productId)
        SIS->>Repo: deleteByProductId(productId)
        Repo->>DB: DELETE FROM search_documents WHERE product_id = ?
        DB-->>Repo: Rows affected
        Repo-->>SIS: Success
        SIS-->>CEC: Success
    end
```

### Autocomplete Prediction Flow

```mermaid
sequenceDiagram
    participant Client as "Client<br/>(Mobile App)"
    participant SC as "SearchController"
    participant Cache as "Caffeine Cache"
    participant SS as "SearchService"
    participant Repo as "SearchDocumentRepository"
    participant DB as "PostgreSQL"

    Client->>SC: GET /search/autocomplete?prefix=mir

    SC->>SS: autocomplete(prefix='mir', limit=10)

    alt Prefix length < 2
        Note over SS: Skip autocomplete
        SS-->>SC: Empty list
    else Prefix length >= 2
        SS->>Cache: Check cache<br/>key = mir_10

        alt Cache Hit
            Cache-->>SS: Return cached results
        else Cache Miss
            SS->>SS: escapeLikePattern(prefix)<br/>'mir%' pattern

            Repo->>DB: SELECT name, brand, product_id<br/>FROM search_documents<br/>WHERE name ILIKE 'mir%'<br/>LIMIT 10

            DB-->>Repo: List<Object[]>
            Repo-->>SS: Results

            SS->>Cache: Store results<br/>300s TTL
        end
    end

    SS-->>SC: List<AutocompleteResult>
    SC-->>Client: HTTP 200 + JSON array
```

## State Management

### Distributed Locks (ShedLock)

```mermaid
graph LR
    Job1["Scheduled Job 1<br/>@Scheduled: 5min"]
    Job2["Scheduled Job 2<br/>@Scheduled: 1hour"]
    Job3["Scheduled Job 3<br/>@Scheduled: daily"]

    ShedLock["ShedLock<br/>(Distributed Lock)"]

    DB[("PostgreSQL<br/>shedlock table")]

    Job1 -->|Acquire lock| ShedLock
    Job2 -->|Acquire lock| ShedLock
    Job3 -->|Acquire lock| ShedLock

    ShedLock -->|INSERT/UPDATE| DB

    Note over ShedLock: Prevents concurrent execution<br/>across multiple replicas<br/>Lock held per job, released on completion
```

### Cache Invalidation

```mermaid
graph LR
    Update["Product Updated<br/>in Catalog"]
    KafkaEvent["ProductUpdated<br/>Event on Kafka"]
    Consumer["CatalogEventConsumer"]
    IndexService["SearchIndexService"]
    Repository["SearchDocumentRepository"]

    DBTrigger["PostgreSQL<br/>TRIGGER"]
    CacheInvalidate["Cache Hit:<br/>Automatically invalid<br/>on next query<br/>TTL: 300s"]

    Update -->|Outbox/CDC| KafkaEvent
    KafkaEvent --> Consumer
    Consumer --> IndexService
    IndexService --> Repository
    Repository -->|Upsert| DBTrigger

    DBTrigger -.->|Triggers tsvector<br/>recalculation| CacheInvalidate

    Note over CacheInvalidate: Cache eviction is TTL-based<br/>No explicit invalidation call
```

## Error Handling

```mermaid
graph TD
    A["Kafka Message<br/>Received"]

    B{Deserialize<br/>Successful?}
    B -->|Yes| C{Event Type<br/>Recognized?}
    B -->|No| D["Log Error<br/>Skip Message<br/>Offset committed"]

    C -->|Yes| E{Database<br/>Operation<br/>Success?}
    C -->|No| F["Log Debug<br/>Ignore Unknown<br/>Offset committed"]

    E -->|Success| G["Offset<br/>Committed"]
    E -->|Failure| H["Retry #1<br/>Exponential Backoff"]

    H -->|Success| G
    H -->|Failure| I["Retry #2"]

    I -->|Success| G
    I -->|Failure| J["Retry #3"]

    J -->|Success| G
    J -->|Failure| K["Send to DLT<br/>catalog.events.DLT<br/>Log ERROR"]

    K --> L["Alert On-call<br/>Investigate DLT"]

    style D fill:#ff9999
    style F fill:#ffcc99
    style K fill:#ff6666
    style L fill:#ff6666
```

## Performance Characteristics

| Operation | Latency (p50) | Latency (p99) | Notes |
|-----------|---------------|---------------|-------|
| Search (cached) | 5ms | 20ms | Caffeine hits |
| Search (DB query) | 45ms | 150ms | tsvector GIN index |
| Autocomplete (cached) | 3ms | 10ms | Caffeine hits |
| Autocomplete (DB query) | 30ms | 80ms | ILIKE without trigram |
| Index update (insert) | 50ms | 200ms | Trigger overhead |
| Trending fetch | 10ms | 30ms | Small table, in-memory |

