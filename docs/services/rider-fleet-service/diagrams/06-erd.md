# Rider Fleet Service - Entity-Relationship Diagram (ERD)

```mermaid
erDiagram
    RIDERS ||--o{ RIDER_LOCATIONS : has
    RIDERS ||--o{ ASSIGNMENT_HISTORY : has

    RIDERS {
        uuid id PK
        string phone UK
        string status "AVAILABLE|ASSIGNED|ON_DELIVERY|OFF_DUTY"
        uuid current_order_id
        decimal current_lat
        decimal current_long
        timestamp created_at
        timestamp updated_at
        bigint version "optimistic lock"
    }

    RIDER_LOCATIONS {
        uuid id PK
        uuid rider_id FK
        decimal latitude
        decimal longitude
        timestamp updated_at
    }

    ASSIGNMENT_HISTORY {
        uuid id PK
        uuid rider_id FK
        uuid order_id
        timestamp assigned_at
        timestamp completed_at
        string status "ACCEPTED|REJECTED|COMPLETED|FAILED"
    }
```

## Indexes

```sql
CREATE INDEX idx_riders_status ON riders(status);
CREATE INDEX idx_riders_phone ON riders(phone);
CREATE INDEX idx_riders_current_order_id ON riders(current_order_id);
CREATE INDEX idx_locations_rider_id ON rider_locations(rider_id);
CREATE INDEX idx_locations_updated_at ON rider_locations(updated_at DESC);
CREATE INDEX idx_assignments_rider_id ON assignment_history(rider_id);
CREATE INDEX idx_assignments_order_id ON assignment_history(order_id);
CREATE INDEX idx_assignments_assigned_at ON assignment_history(assigned_at DESC);
```

## Concurrency Control

```markdown
## Optimistic Locking (@Version)

- Each rider has a version column (bigint)
- On UPDATE, check WHERE id=? AND version=current_version
- If match: Update succeeds, version incremented
- If no match: 0 rows updated, throw OptimisticLockException

## Scenario

1. Rider v1 loaded from DB
2. Another request updates Rider to v2
3. First request tries to save v1 (incremented to v2)
4. WHERE clause fails (version already 2)
5. HTTP 409 Conflict returned

## Benefits

- No row locks needed (better concurrency)
- Fail-fast on conflicts
- Application handles retry logic
```

## Version Tracking

```mermaid
graph TD
    A["Initial load<br/>version=1"] --> B["Dispatch assigns<br/>status=ASSIGNED"]
    B -->|UPDATE + v1 check| C["version incremented<br/>to 2"]
    D["Location update<br/>concurrent"] -->|Tries to use v1| E["WHERE...version=1<br/>FAILS"]
    E -->|Location not updated| F["Retry from fresh<br/>load v2"]
    F -->|Update location| G["version incremented<br/>to 3"]
    C --> H["Rider accepts<br/>status=ON_DELIVERY"]
    H -->|UPDATE + v2 check| I["version incremented<br/>to 4"]
```
