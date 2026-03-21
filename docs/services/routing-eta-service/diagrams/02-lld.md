# Routing ETA Service - Low-Level Design (LLD)

```mermaid
graph TB
    subgraph "HTTP Layer"
        ETACtrl["ETAController<br/>GET /eta<br/>POST /eta/batch"]
    end

    subgraph "Service Layer"
        ETASvc["ETAService<br/>- calculateETA()<br/>- batchETA()"]
        LocationSvc["LocationService<br/>- getRiderLocation()"]
    end

    subgraph "Calculation Engine"
        HaversineCalc["Haversine<br/>Distance Calc"]
        TrafficModel["Traffic Model<br/>Historical avg"]
        ETAFormula["ETA = (distance / avg_speed)<br/>+ pickup_time"]
    end

    subgraph "Data Layer"
        RedisGeo["Redis<br/>GEOADD/GEORADIUS<br/>rider locations"]
        PostgreSQL["PostgreSQL<br/>delivery records<br/>eta cache"]
    end

    subgraph "Streaming"
        KafkaConsumer["Kafka Consumer<br/>rider.location.updates"]
        LocationUpdater["Location Updater<br/>pushes to Redis"]
    end

    ETACtrl --> ETASvc
    ETASvc --> HaversineCalc
    ETASvc --> TrafficModel
    HaversineCalc --> ETAFormula
    TrafficModel --> ETAFormula
    ETASvc --> LocationSvc
    LocationSvc --> RedisGeo
    KafkaConsumer --> LocationUpdater
    LocationUpdater --> RedisGeo
```

## Distance Formula

```markdown
## Haversine Formula

Distance = 2 * R * arcsin(sqrt(
    sin((lat2-lat1)/2)^2 +
    cos(lat1) * cos(lat2) * sin((long2-long1)/2)^2
))

where R = 6371 km (Earth radius)

Time = distance / average_speed + 5 min (pickup + security)
```

## Traffic Model

```mermaid
graph TD
    A["Historical traffic data"] --> B["Peak hours: 10-14, 18-21"]
    A --> C["Off-peak: 2x speed"]
    B --> D["Peak: 20km/hr avg"]
    C --> E["Off-peak: 40km/hr avg"]
    D --> F["Current time?"]
    E --> F
    F --> G["Select avg_speed"]
    G --> H["Calculate ETA"]
```
