# Warehouse Service

> **Java · Spring Boot · Dark Store Management, Capacity Planning & Zones**

Manages dark store (warehouse) lifecycle, hourly order-capacity tracking, operating-hours enforcement with timezone awareness, and delivery-zone configuration with pincode-based store lookup. Events are published via the transactional outbox pattern. Nearest-store discovery uses Haversine distance with configurable radius.

## Architecture

```mermaid
graph LR
    SC[StoreController<br/>:8090/stores] --> SS[StoreService]
    SC --> CS[CapacityService]
    SC --> ZS[ZoneService]
    ASC[AdminStoreController<br/>:8090/admin/stores] --> SS
    ASC --> CS
    SS --> REPO[(PostgreSQL<br/>stores · store_hours<br/>store_capacity · store_zones)]
    CS --> REPO
    ZS --> REPO
    SS --> OB[OutboxService]
    OB --> OBT[(outbox_events)]
    OBT -.->|CDC / Relay| K{{Kafka<br/>store.events}}
    SS --> CACHE[(Caffeine Cache<br/>5 min TTL)]
    ZS --> CACHE
```

## Store Lifecycle

```mermaid
stateDiagram-v2
    [*] --> INACTIVE : createStore()
    INACTIVE --> ACTIVE : updateStatus(ACTIVE)
    ACTIVE --> MAINTENANCE : updateStatus(MAINTENANCE)
    ACTIVE --> TEMPORARILY_CLOSED : updateStatus(TEMPORARILY_CLOSED)
    MAINTENANCE --> ACTIVE : updateStatus(ACTIVE)
    TEMPORARILY_CLOSED --> ACTIVE : updateStatus(ACTIVE)
    ACTIVE --> INACTIVE : updateStatus(INACTIVE)
    INACTIVE --> [*] : deleteStore()
```

## Capacity Management

```mermaid
flowchart TD
    A[New order arrives] --> B[canAcceptOrder<br/>storeId, now]
    B --> C[Get StoreCapacity<br/>for current date + hour]
    C --> D{currentOrders <br/>< maxOrders?}
    D -->|Yes| E[incrementOrderCount<br/>UPSERT pattern]
    D -->|No| F[Reject — store at capacity]
    E --> G[Return true]

    H[Scheduled: daily 00:00] --> I[cleanupOldCapacityData]
    I --> J[Delete records<br/>older than 7 days]

    subgraph Capacity Tracking
        K[Per-store, per-date, per-hour<br/>currentOrders vs maxOrders<br/>derived from capacityOrdersPerHour]
    end
```

## Zone Configuration

```mermaid
flowchart TD
    A[Customer enters pincode] --> B[mapPincodeToStoreIds<br/>cached lookup]
    B --> C[Query store_zones<br/>WHERE pincode = ?]
    C --> D[Return list of<br/>servicing store UUIDs]

    E[GET /stores/id/zones] --> F[getStoreZones<br/>storeId]
    F --> G[Return zones with<br/>name, pincode, deliveryRadiusKm]

    H[GET /stores/id/delivery-radius] --> I[getDeliveryRadius<br/>storeId]
    I --> J[MAX deliveryRadiusKm<br/>across all zones]

    K[GET /stores/nearest] --> L[Haversine SQL query<br/>within radiusKm, limit 5]
    L --> M[Return nearest stores<br/>sorted by distance]
```

## API Reference

### StoreController — `/stores` (Public / Authenticated)

| Method | Path | Auth | Description |
|--------|------|------|-------------|
| `GET` | `/nearest?lat=&lng=&radiusKm=` | None | Find nearest stores (default 10 km, max 5 results) |
| `GET` | `/{id}` | None | Get store details |
| `GET` | `/{id}/capacity` | Authenticated | Get current hour capacity |
| `GET` | `/{id}/open` | Authenticated | Check if store is currently open (timezone-aware) |
| `GET` | `/by-pincode?pincode=` | Authenticated | Map pincode to store IDs |
| `GET` | `/by-city?city=` | Authenticated | List stores in a city |
| `GET` | `/{id}/zones` | Authenticated | List delivery zones for store |
| `GET` | `/{id}/delivery-radius` | Authenticated | Get max delivery radius (km) |

**Store Response:**
```json
{
  "id": "uuid",
  "name": "Indiranagar Dark Store",
  "address": "100 Feet Road, Indiranagar",
  "city": "Bangalore",
  "state": "Karnataka",
  "pincode": "560038",
  "latitude": 12.9716,
  "longitude": 77.6412,
  "timezone": "Asia/Kolkata",
  "status": "ACTIVE",
  "capacityOrdersPerHour": 50,
  "createdAt": "2025-01-01T00:00:00Z",
  "updatedAt": "2025-01-15T10:00:00Z"
}
```

**Capacity Response:**
```json
{
  "storeId": "uuid",
  "date": "2025-01-15",
  "hour": 10,
  "currentOrders": 32,
  "maxOrders": 50
}
```

### AdminStoreController — `/admin/stores` (ADMIN only)

| Method | Path | Auth | Description |
|--------|------|------|-------------|
| `POST` | `/` | ADMIN | Create a new store |
| `GET` | `/` | ADMIN | List all stores (optional `?status=` filter) |
| `GET` | `/{id}` | ADMIN | Get store details |
| `PATCH` | `/{id}/status?status=` | ADMIN | Update store status |
| `DELETE` | `/{id}` | ADMIN | Delete a store (soft) |
| `POST` | `/{id}/capacity/increment` | ADMIN | Increment current-hour order count |
| `GET` | `/{id}/can-accept` | ADMIN | Check if store can accept another order |

**Create Store Request:**
```json
{
  "name": "Koramangala Dark Store",
  "address": "80 Feet Road, Koramangala",
  "city": "Bangalore",
  "state": "Karnataka",
  "pincode": "560034",
  "latitude": 12.9352,
  "longitude": 77.6245,
  "timezone": "Asia/Kolkata",
  "capacityOrdersPerHour": 60
}
```

## Database Schema

```mermaid
erDiagram
    stores {
        uuid id PK
        varchar name
        varchar address
        varchar city
        varchar state
        varchar pincode
        decimal latitude "DECIMAL(10,8)"
        decimal longitude "DECIMAL(11,8)"
        varchar timezone
        varchar status
        int capacity_orders_per_hour
        timestamp created_at
        timestamp updated_at
        int version "optimistic lock"
    }
    store_hours {
        uuid id PK
        uuid store_id FK
        int day_of_week "0=Sun 6=Sat"
        time opens_at
        time closes_at
        boolean is_holiday
        timestamp created_at
        timestamp updated_at
    }
    store_capacity {
        uuid id PK
        uuid store_id FK
        date date
        int hour
        int current_orders
        int max_orders
        timestamp created_at
        timestamp updated_at
    }
    store_zones {
        uuid id PK
        uuid store_id FK
        varchar zone_name
        varchar pincode
        decimal delivery_radius_km "DECIMAL(5,2) default 5.00"
        timestamp created_at
        timestamp updated_at
    }
    outbox_events {
        uuid id PK
        varchar aggregate_type
        varchar aggregate_id
        varchar event_type
        jsonb payload
        timestamp created_at
        boolean sent
    }
    stores ||--o{ store_hours : "operates"
    stores ||--o{ store_capacity : "tracks"
    stores ||--o{ store_zones : "serves"
```

**Indexes:** `store_capacity(store_id, date, hour)` composite index. `stores` queried via Haversine native SQL for proximity lookups.

## Event Publishing (Outbox Pattern)

| Event | Trigger | Payload |
|-------|---------|---------|
| `StoreCreated` | `POST /admin/stores` | `{ storeId, name, city, status }` |
| `StoreStatusChanged` | `PATCH /admin/stores/{id}/status` | `{ storeId, previousStatus, newStatus }` |
| `StoreDeleted` | `DELETE /admin/stores/{id}` | `{ storeId, name }` |

Events are written to the `outbox_events` table within the same transaction as the business operation, then externalized by an outbox relay / CDC process.

## Configuration

| Variable | Default | Description |
|----------|---------|-------------|
| `SERVER_PORT` | `8090` | HTTP listen port |
| `SPRING_DATASOURCE_URL` | — | PostgreSQL JDBC URL |
| `WAREHOUSE_NEAREST_RADIUS_KM` | `10` | Default radius for nearest-store queries |
| `WAREHOUSE_NEAREST_MAX_RESULTS` | `5` | Max stores returned by nearest query |
| `JWT_PUBLIC_KEY` | — | RSA public key (GCP Secret Manager) |
| `OTEL_EXPORTER_OTLP_ENDPOINT` | `otel-collector.monitoring:4318` | OpenTelemetry collector |

### Caching

Caffeine cache with 3 namespaces: `stores`, `store-zones`, `store-hours` — 2 000 entries max, 5-minute write-expiry TTL.

### Scheduled Jobs

| Job | Schedule | Lock | Description |
|-----|----------|------|-------------|
| `CapacityService.cleanupOldCapacityData` | Daily 00:00 | ShedLock | Deletes capacity records older than 7 days |
| `OutboxCleanupJob.cleanupSentEvents` | Daily 03:00 | ShedLock | Deletes sent outbox events older than 30 days |

### Security

- **Public endpoints:** `GET /stores/nearest`, `GET /stores/{id}`
- **Authenticated:** All other `/stores/**` endpoints
- **Admin only:** All `/admin/**` endpoints
- **CORS:** `http://localhost:3000`, `https://*.instacommerce.dev`

## Build & Run

```bash
# Local
./gradlew :services:warehouse-service:bootRun

# Docker
docker build -t warehouse-service .
docker run -p 8090:8090 warehouse-service
```

## Dependencies

- Java 21, Spring Boot 3 (Web, Data JPA, Security, Validation, Actuator, Cache)
- PostgreSQL + Flyway migrations
- Caffeine 3.1.8 (multi-namespace caching)
- ShedLock 5.12.0 (distributed scheduling)
- JJWT 0.12.5 (JWT authentication)
- Micrometer + OTLP (tracing & metrics)
- GCP Secret Manager, Cloud SQL socket factory
- Testcontainers (PostgreSQL integration tests)
