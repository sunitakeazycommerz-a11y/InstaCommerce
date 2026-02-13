# Rider Fleet Service

> **Java · Spring Boot · Rider Lifecycle, Assignment & Earnings**

Manages the complete rider lifecycle — onboarding, activation, suspension — along with intelligent proximity-based assignment, real-time availability/location tracking, and granular earnings accounting. Events are published via the transactional outbox pattern and the service consumes fulfillment events to trigger automatic rider assignment.

## Architecture

```mermaid
graph LR
    RC[RiderController<br/>:8091/riders] --> RS[RiderService]
    ARC[AdminRiderController<br/>:8091/admin/riders] --> RS
    RAC[RiderAssignmentController<br/>:8091/riders/assign] --> RAS[RiderAssignmentService]
    RC --> RAVS[RiderAvailabilityService]
    RC --> RES[RiderEarningsService]
    RAS --> REPO[(PostgreSQL<br/>riders · rider_availability<br/>rider_assignments · rider_earnings)]
    RS --> REPO
    RAVS --> REPO
    RES --> REPO
    RS --> OB[OutboxService]
    RAS --> OB
    OB --> OBT[(outbox_events)]
    OBT -.->|CDC / Relay| K{{Kafka<br/>rider.events}}
    FK{{Kafka<br/>fulfillment.events}} --> FEC[FulfillmentEventConsumer]
    FEC --> RAS
```

## Rider Lifecycle State Machine

```mermaid
stateDiagram-v2
    [*] --> INACTIVE : createRider()
    INACTIVE --> ACTIVE : onboardRider() → activateRider()
    ACTIVE --> ON_DELIVERY : assignRider()
    ON_DELIVERY --> ACTIVE : delivery completed
    ACTIVE --> SUSPENDED : suspendRider()
    SUSPENDED --> ACTIVE : activateRider()
    ACTIVE --> BLOCKED : policy violation
    SUSPENDED --> BLOCKED : policy violation
    BLOCKED --> [*]
```

## Assignment Flow

```mermaid
flowchart TD
    A[AssignRider Request<br/>orderId, storeId, pickupLat/Lng] --> B{Order already<br/>assigned?}
    B -->|Yes| Z1[Return existing<br/>assignment]
    B -->|No| C[Find nearest available rider<br/>Haversine within 5 km radius]
    C --> D{Rider found?}
    D -->|No| Z2[Throw<br/>RiderNotAvailableException]
    D -->|Yes| E[Pessimistic lock<br/>FOR UPDATE SKIP LOCKED]
    E --> F[Set rider status<br/>ON_DELIVERY]
    F --> G[Set availability<br/>isAvailable = false]
    G --> H[Create RiderAssignment<br/>record]
    H --> I[Publish RiderAssigned<br/>via OutboxService]
    I --> J[Return assignment]
```

## Earnings Calculation

```mermaid
flowchart LR
    subgraph Per Delivery
        DF[Delivery Fee<br/>deliveryFeeCents]
        TIP[Customer Tip<br/>tipCents]
        INC[Platform Incentive<br/>incentiveCents]
    end
    DF --> SUM[Total Earning<br/>per order]
    TIP --> SUM
    INC --> SUM
    SUM --> AGG[Aggregated Summary<br/>sumDeliveryFees + sumTips<br/>+ sumIncentives<br/>+ deliveryCount]
    AGG --> RESP[EarningsSummary<br/>for date range]
```

## Availability Management

```mermaid
flowchart TD
    A[Rider toggles availability] --> B[Update RiderAvailability<br/>isAvailable flag]
    C[Rider sends location] --> D[Update currentLat/Lng<br/>+ lastUpdated timestamp]
    E[GET /riders/available?storeId=X] --> F[Query riders where<br/>isAvailable=true AND storeId=X]
    G[Assignment triggers] --> H[Set isAvailable=false<br/>+ rider status ON_DELIVERY]
    I[Delivery completed] --> J[Set isAvailable=true<br/>+ rider status ACTIVE]
```

## API Reference

### RiderController — `/riders`

| Method | Path | Auth | Description |
|--------|------|------|-------------|
| `POST` | `/{id}/availability` | RIDER, ADMIN | Toggle rider availability on/off |
| `POST` | `/{id}/location` | RIDER, ADMIN | Update rider GPS coordinates |
| `GET` | `/available?storeId={id}` | INTERNAL, ADMIN | List available riders for a store |
| `GET` | `/{id}` | RIDER, ADMIN | Get rider details |
| `GET` | `/{id}/earnings` | RIDER, ADMIN | Get earnings summary (query params: `from`, `to`) |

### RiderAssignmentController — `/riders`

| Method | Path | Auth | Description |
|--------|------|------|-------------|
| `POST` | `/assign` | INTERNAL | Assign nearest available rider to order |

**Request:**
```json
{
  "orderId": "uuid",
  "storeId": "uuid",
  "pickupLat": 12.9716,
  "pickupLng": 77.5946
}
```

**Response:**
```json
{
  "id": "uuid",
  "orderId": "uuid",
  "riderId": "uuid",
  "storeId": "uuid",
  "assignedAt": "2025-01-15T10:30:00Z"
}
```

### AdminRiderController — `/admin/riders`

| Method | Path | Auth | Description |
|--------|------|------|-------------|
| `POST` | `/` | ADMIN | Create a new rider |
| `GET` | `/` | ADMIN | List all riders |
| `GET` | `/{id}` | ADMIN | Get rider by ID |
| `POST` | `/{id}/activate` | ADMIN | Activate a rider |
| `POST` | `/{id}/suspend` | ADMIN | Suspend a rider |
| `POST` | `/{id}/onboard` | ADMIN | Onboard a rider |

**Create Rider Request:**
```json
{
  "name": "Ravi Kumar",
  "phone": "+919876543210",
  "email": "ravi@example.com",
  "vehicleType": "MOTORCYCLE",
  "licenseNumber": "KA01AB1234",
  "storeId": "uuid"
}
```

## Database Schema

```mermaid
erDiagram
    riders {
        uuid id PK
        varchar name
        varchar phone UK
        varchar email
        varchar vehicle_type
        varchar license_number
        varchar status
        decimal rating_avg
        int total_deliveries
        uuid store_id
        timestamp created_at
        timestamp updated_at
    }
    rider_availability {
        uuid id PK
        uuid rider_id UK
        boolean is_available
        decimal current_lat
        decimal current_lng
        uuid store_id
        timestamp last_updated
    }
    rider_assignments {
        uuid id PK
        uuid order_id UK
        uuid rider_id
        uuid store_id
        timestamp assigned_at
    }
    rider_earnings {
        uuid id PK
        uuid rider_id
        uuid order_id UK
        bigint delivery_fee_cents
        bigint tip_cents
        bigint incentive_cents
        timestamp earned_at
    }
    rider_ratings {
        uuid id PK
        uuid rider_id
        uuid order_id UK
        int rating
        varchar comment
        timestamp created_at
    }
    rider_shifts {
        uuid id PK
        uuid rider_id
        timestamp shift_start
        timestamp shift_end
        varchar status
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
    riders ||--o{ rider_assignments : "assigned via"
    riders ||--|| rider_availability : "has"
    riders ||--o{ rider_earnings : "earns"
    riders ||--o{ rider_ratings : "rated"
    riders ||--o{ rider_shifts : "works"
```

## Kafka Integration

| Direction | Topic | Group | Description |
|-----------|-------|-------|-------------|
| **Consume** | `fulfillment.events` | `rider-fleet-service` | Listens for `OrderPacked` → triggers `assignRider()` |
| **Produce** | `rider.events` (via outbox) | — | `RiderCreated`, `RiderActivated`, `RiderSuspended`, `RiderOnboarded`, `RiderAssigned` |

**Error handling:** Dead-letter topic (`*.DLT`) with 3 retries, 1-second backoff.

## Configuration

| Variable | Default | Description |
|----------|---------|-------------|
| `SERVER_PORT` | `8091` | HTTP listen port |
| `SPRING_DATASOURCE_URL` | — | PostgreSQL JDBC URL |
| `SPRING_KAFKA_BOOTSTRAP_SERVERS` | — | Kafka broker addresses |
| `RIDER_ASSIGNMENT_RADIUS_KM` | `5` | Max distance (km) for nearest-rider search |
| `JWT_PUBLIC_KEY` | — | RSA public key for token verification (GCP Secret Manager) |
| `OTEL_EXPORTER_OTLP_ENDPOINT` | `otel-collector.monitoring:4318` | OpenTelemetry collector |

### Caching

Caffeine cache: 1 000 entries max, 60-second TTL.

### Scheduled Jobs

| Job | Schedule | Lock | Description |
|-----|----------|------|-------------|
| `OutboxCleanupJob` | Every 6 hours | ShedLock | Deletes sent outbox events older than 7 days |

## Build & Run

```bash
# Local
./gradlew :services:rider-fleet-service:bootRun

# Docker
docker build -t rider-fleet-service .
docker run -p 8091:8091 rider-fleet-service
```

## Dependencies

- Java 21, Spring Boot 3, Spring Kafka
- PostgreSQL + Flyway migrations
- Resilience4j (circuit breakers)
- Caffeine (caching), ShedLock (distributed locking)
- JJWT 0.12.5 (JWT authentication)
- Micrometer + OTLP (tracing & metrics)
- GCP Secret Manager, Cloud SQL socket factory
