# Routing ETA Service - All 7 Diagrams

## 1. High-Level Design

```mermaid
graph TB
    DeliveryApp["📱 Delivery App"]
    Router["🗺️ Routing ETA Service<br/>(Java Spring Boot + PostGIS)"]
    LocationSvc["📍 Location Service<br/>(Rider positions)"]
    TrafficAPI["🚗 Traffic API<br/>(External)"]
    GeoCache["📍 Redis Geo Cache<br/>(Rider locations)"]
    DB["🗄️ PostgreSQL + PostGIS"]

    DeliveryApp -->|Calculate ETA| Router
    Router -->|Fetch rider location| LocationSvc
    Router -->|Get traffic data| TrafficAPI
    Router -->|Lookup geo index| GeoCache
    Router -->|Store geo data| DB

    style Router fill:#4A90E2,color:#fff
    style GeoCache fill:#F5A623,color:#000
    style DB fill:#7ED321,color:#000
```

## 2. Low-Level Design - ETA Calculation

```mermaid
graph TD
    Request["POST /api/routing/eta<br/>{origin, destination}"]
    Validate["Validate coordinates"]
    LookupRiders["Query Redis GEO<br/>GEORADIUS within 5km"]
    FetchLocations["Get rider locations"]
    CalcDistances["Calculate Haversine<br/>distance for each rider"]
    FetchTraffic["Fetch traffic factor<br/>from Traffic API"]
    EstimateSpeed["EstimatedSpeed =<br/>baseSpeed * trafficFactor"]
    CalcETAs["ETA = distance / speed"]
    SelectBestRider["Sort by ETA<br/>Select shortest"]
    Response["Return 200 OK"]

    Request --> Validate
    Validate --> LookupRiders
    LookupRiders --> FetchLocations
    FetchLocations --> CalcDistances
    CalcDistances --> FetchTraffic
    FetchTraffic --> EstimateSpeed
    EstimateSpeed --> CalcETAs
    CalcETAs --> SelectBestRider
    SelectBestRider --> Response

    style CalcDistances fill:#7ED321,color:#000
    style EstimateSpeed fill:#F5A623,color:#000
```

## 3. Flowchart - ETA Query Read Path

```mermaid
flowchart TD
    A["Request: POST /routing/eta"]
    B["Parse origin & destination"]
    C["Validate geocoordinates"]
    D{{"Valid?"}}
    E["Query Redis GEO index<br/>nearby riders < 5km"]
    F{{"Riders found?"}}
    G["For each rider:<br/>Calculate distance<br/>Haversine formula"]
    H["Fetch traffic conditions<br/>from external API"]
    I["Estimate speed:<br/>base 40km/h * traffic_factor"]
    J["Calculate ETA<br/>= distance / speed"]
    K["Sort by ETA<br/>Select min"]
    L["Return 200 OK<br/>{rider_id, eta_minutes}"]
    M["Return 400<br/>Invalid coordinates"]
    N["Return 404<br/>No riders available"]

    A --> B
    B --> C
    C --> D
    D -->|No| M
    D -->|Yes| E
    E --> F
    F -->|No| N
    F -->|Yes| G
    G --> H
    H --> I
    I --> J
    J --> K
    K --> L

    style A fill:#4A90E2,color:#fff
    style G fill:#F5A623,color:#000
    style L fill:#7ED321,color:#000
```

## 4. Sequence - ETA Calculation

```mermaid
sequenceDiagram
    participant App as Delivery App
    participant Router as Routing ETA
    participant GeoCache as Redis GEO
    participant LocationSvc as Location Service
    participant TrafficAPI as Traffic API
    participant DB as PostgreSQL+PostGIS

    App->>Router: POST /routing/eta<br/>{origin: [28.6, 77.2], dest: [28.7, 77.3]}
    Router->>Router: Validate coordinates
    Router->>GeoCache: GEORADIUS 28.6,77.2 5km
    GeoCache-->>Router: [rider1, rider2, rider3]
    Router->>Router: For each rider:<br/>Haversine(origin, rider_location)
    Router->>Router: distance1=2.3km, distance2=3.1km, distance3=4.2km
    Router->>TrafficAPI: GET /traffic?route=origin->dest
    TrafficAPI-->>Router: traffic_factor=0.8 (20% congestion)
    Router->>Router: speed = 40 * 0.8 = 32 km/h
    Router->>Router: ETA1 = 2.3/32 = 4.3 min
    Router->>Router: ETA2 = 3.1/32 = 5.8 min
    Router->>Router: ETA3 = 4.2/32 = 7.9 min
    Router->>Router: Select min (rider1, 4.3 min)
    Router-->>App: 200 OK {rider_id: rider1, eta: 4.3}

    Note over App,DB: Total latency: 50-100ms
```

## 5. State Machine - Rider Availability

```mermaid
stateDiagram-v2
    [*] --> ONLINE: Rider logged in
    ONLINE --> ACTIVE: Accepted delivery
    ACTIVE --> IN_TRANSIT: Started delivery
    IN_TRANSIT --> REACHED: Arrived at destination
    REACHED --> COMPLETED: Marked complete
    COMPLETED --> ONLINE: Back to accepting
    ONLINE --> OFFLINE: Logged out
    OFFLINE --> [*]

    note right of ONLINE
        Available in geo index
        GEO radius queries include
    end note

    note right of IN_TRANSIT
        Location updated every 10s
        ETA recalculated
    end note
```

## 6. ER - Geo & Traffic Data

```mermaid
erDiagram
    RIDER_LOCATIONS {
        uuid rider_id PK
        point location "PostGIS POINT"
        timestamp last_updated "10s refresh"
        string status "ONLINE, BUSY, OFFLINE"
    }

    DELIVERY_ROUTES {
        uuid delivery_id PK
        point origin
        point destination
        float distance_km
        integer base_eta_minutes
    }

    TRAFFIC_CONDITIONS {
        string zone "Grid cell ID"
        float congestion_factor "0-1, 1=free"
        timestamp recorded_at
    }

    RIDER_LOCATIONS ||--o{ DELIVERY_ROUTES : serves
```

## 7. End-to-End - Complete ETA Flow

```mermaid
graph TB
    User["👤 User<br/>(Delivery App)"]
    Mobile["📱 Mobile<br/>(Pickup: 28.6°N, 77.2°E<br/>Dropoff: 28.7°N, 77.3°E)"]
    LB["⚖️ Load Balancer"]
    Router["🗺️ ETA Service<br/>(Pod1, Pod2, Pod3)"]
    Redis["📍 Redis GEO Cache<br/>rider1: [28.5, 77.1]<br/>rider2: [28.65, 77.25]<br/>rider3: [28.72, 77.35]"]
    TrafficAPI["🚗 Google Maps<br/>Traffic API"]
    PostgreSQL["🗄️ PostgreSQL<br/>with PostGIS"]
    Kafka["📬 Kafka<br/>(eta_calculated)"]

    User -->|1. Tap Pickup/Dropoff| Mobile
    Mobile -->|2. POST /routing/eta| LB
    LB -->|3. Route request| Router
    Router -->|4. GEORADIUS query| Redis
    Redis -->|5. Return nearby riders| Router
    Router -->|6. Haversine calc| Router
    Router -->|7. Query traffic| TrafficAPI
    TrafficAPI -->|8. traffic_factor=0.8| Router
    Router -->|9. Calculate ETAs| Router
    Router -->|10. Publish event| Kafka
    Router -->|11. Log to audit| PostgreSQL
    Kafka -->|event: eta_calculated| Kafka
    Router -->|12. 200 OK {eta: 5min}| Mobile
    Mobile -->|13. Show "5 min arrival"| User

    style Router fill:#4A90E2,color:#fff
    style Redis fill:#F5A623,color:#000
    style TrafficAPI fill:#50E3C2,color:#000
```
