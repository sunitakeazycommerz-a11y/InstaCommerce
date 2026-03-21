# Location Ingestion Service - Ingestion Flowchart

```mermaid
flowchart TD
    A["Incoming Location<br/>HTTP POST or WS"] --> B["Parse JSON"]
    B --> C["Extract rider_id,<br/>lat, lng, accuracy"]
    C --> D{Valid<br/>Coordinates?}
    D -->|Out of bounds| E["Return 400<br/>Bad Request"]
    D -->|OK| F["Normalize<br/>Precision"]
    F --> G{Rider<br/>Verified?}
    G -->|No| H["Return 401<br/>Unauthorized"]
    G -->|Yes| I["Check H3<br/>Geofence"]
    I --> J["Log Zone<br/>if applicable"]
    J --> K["Set in Redis<br/>rider:location:{id}"]
    K --> L{Redis<br/>Success?}
    L -->|Fail| M["Log Error"]
    M --> N["Continue<br/>Async"]
    L -->|OK| O["Add to Kafka<br/>Batcher"]
    O --> P{Batch<br/>Ready?}
    P -->|Not Ready| Q["Wait for size<br/>or timeout"]
    Q --> P
    P -->|Ready| R["Flush Batch<br/>to Kafka"]
    R --> S{Kafka<br/>Success?}
    S -->|Fail| T["Retry with<br/>backoff"]
    T --> R
    S -->|OK| U["Emit metrics<br/>ingest_counter"]
    U --> V["Return 200 OK"]
    E --> W["HTTP Response"]
    H --> W
    V --> W
    N --> W
```

## Flow Details

1. **JSON Parsing**: Validate request structure
2. **Coordinate Extraction**: rider_id, latitude, longitude, accuracy_ms, timestamp
3. **Bounds Check**: Latitude [-90, 90], Longitude [-180, 180]
4. **Precision Normalization**: Round to configurable decimals (e.g., 6 = ~0.1m)
5. **Rider Verification**: Check if rider_id is known (optional auth)
6. **Geofence Check**: H3 cell computation and zone lookup (optional, log-only)
7. **Redis Set**: Store latest position with TTL (configurable, default 1 hour)
8. **Kafka Batch**: Add to accumulator, flush on size or timeout
9. **Metrics**: Record ingestion counter and latency
10. **Response**: Return 200 OK for successful ingestion
