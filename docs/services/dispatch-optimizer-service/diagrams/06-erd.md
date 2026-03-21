# Dispatch Optimizer Service - Entity-Relationship Diagram (ERD)

```mermaid
erDiagram
    ASSIGNMENTS ||--o{ ASSIGNMENT_HISTORY : tracks

    ASSIGNMENTS {
        uuid id PK
        uuid order_id UK
        uuid rider_id
        decimal assignment_score
        timestamp assigned_at
        timestamp updated_at
    }

    ASSIGNMENT_HISTORY {
        uuid id PK
        uuid assignment_id FK
        uuid old_rider_id
        uuid new_rider_id
        string reason "REBALANCE|CANCELLATION|REASSIGN"
        timestamp created_at
    }
```

## Assignment Cache

```sql
CREATE TABLE assignments (
    id UUID PRIMARY KEY,
    order_id UUID NOT NULL UNIQUE,
    rider_id UUID NOT NULL,
    assignment_score DECIMAL(5, 2),  -- 0-1.0 score
    assigned_at TIMESTAMP DEFAULT NOW(),
    updated_at TIMESTAMP DEFAULT NOW()
);

CREATE TABLE assignment_history (
    id UUID PRIMARY KEY,
    assignment_id UUID NOT NULL REFERENCES assignments(id),
    old_rider_id UUID,
    new_rider_id UUID,
    reason VARCHAR(50),  -- REBALANCE, CANCELLATION
    created_at TIMESTAMP DEFAULT NOW()
);

CREATE INDEX idx_assignments_rider_id ON assignments(rider_id);
CREATE INDEX idx_assignments_assigned_at ON assignments(assigned_at DESC);
```

## ML Model Schema

```markdown
## Model Features

- distance_km: Haversine distance (0-10 km)
- rider_current_load: Orders on rider (0-5)
- rider_success_rate: Delivery success % (0-100)
- zone_utilization: Zone rider count vs capacity
- time_of_day: Hour (0-23) for traffic patterns
- delivery_distance: Distance to customer from store

## Model Output

- score: 0.0 to 1.0 ranking

## Training Data

- Historical assignments (1 month)
- Labels: delivery_time, success/failure
- Features: 20+ input signals
- Frozen at deploy time (no online learning)
```

## Scoring Data Structure

```mermaid
graph TD
    A["Candidate Rider"] --> B["Feature Vector"]
    B --> C["distance: 2.5 km"]
    B --> D["current_load: 2/5"]
    B --> E["success_rate: 98%"]
    B --> F["zone_util: 0.8"]
    B --> G["time_hour: 14"]

    H["ML Model Inference"] -->|Vector in| I["Neural Network<br/>4 hidden layers"]
    I -->|Prediction| J["Score: 0.92"]

    K["All Candidates"] -->|Scored| L["Ranking"]
    L -->|Sorted DESC| M["Select rank 1"]
```
