# Dispatch Optimizer Service - Low-Level Design (LLD)

```mermaid
graph TB
    subgraph "HTTP Layer"
        AssignCtrl["AssignmentController<br/>POST /assign<br/>GET /assignment/{orderId}"]
    end

    subgraph "Service Layer"
        AssignSvc["AssignmentService<br/>- assign()<br/>- rebalance()"]
        RiderSvc["RiderService<br/>- getAvailableRiders()<br/>- getRiderMetrics()"]
    end

    subgraph "ML Layer"
        ModelLoader["ML Model Loader"]
        FeatureEng["Feature Engineering<br/>- distance<br/>- load<br/>- zone_balance"]
        Predictor["Predictor<br/>Rank riders"]
        Selector["Selector<br/>Pick best"]
    end

    subgraph "Data Layer"
        RedisGeo["Redis GEO<br/>rider positions"]
        RiderClient["Rider Fleet<br/>Service Client"]
        PostgreSQL["PostgreSQL<br/>assignment cache"]
    end

    AssignCtrl --> AssignSvc
    AssignSvc --> ModelLoader
    AssignSvc --> FeatureEng
    FeatureEng --> RedisGeo
    FeatureEng --> RiderClient
    ModelLoader --> Predictor
    Predictor --> FeatureEng
    Predictor --> Selector
    Selector --> PostgreSQL
```

## Assignment Algorithm

```markdown
## Score Calculation

Score(rider) = (
    Distance_weight * (1 / distance_km) +
    Load_weight * availability +
    Zone_balance_weight * zone_factor +
    Success_rate_weight * acceptance_rate
)

Steps:
1. Query Redis GEO for nearby riders
2. Fetch metrics from Rider Fleet Service
3. Calculate distance using Haversine
4. Generate feature vector
5. Run ML model prediction
6. Select top-ranked rider
7. Fallback to greedy if ML fails
```

## Fallback Strategy

```mermaid
graph TD
    A["ML Model<br/>prediction"] -->|Timeout >5s| B["Fallback to<br/>greedy"]
    A -->|Success| C["Return prediction"]
    B -->|Distance-based| D["Select nearest<br/>available"]
    D -->|HTTP 200| E["AssignmentResponse"]
    C -->|HTTP 200| E
```
