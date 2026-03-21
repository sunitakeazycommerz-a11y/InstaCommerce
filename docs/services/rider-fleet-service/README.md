# Rider Fleet Service

## Overview

The rider-fleet-service manages the complete lifecycle of delivery rider availability, skill assignments, and capacity constraints. It serves as the authoritative source for rider state, matching riders to orders based on geographic proximity, skill matrix requirements, and real-time availability. This is a Tier 1 critical service with extreme latency sensitivity for order assignment operations.

**Service Ownership**: Platform Team - Logistics
**Language**: Java 21 / Spring Boot 4.0
**Default Port**: 8097
**Status**: Tier 1 Critical (Delivery Path Authority)
**Database**: PostgreSQL 15+ (rider state, location index, assignment history)

## SLOs & Availability

- **Availability SLO**: 99.9% (43 minutes downtime/month)
- **P99 Latency**:
  - Assignment query: < 200ms (POST /riders/assign - critical path)
  - Location update: < 100ms (POST /riders/{id}/location)
  - Availability fetch: < 150ms (GET /riders/available)
- **Error Rate**: < 0.1% (must not block order flow)
- **Max Throughput**: 10,000 assignment queries/minute (peak delivery windows)
- **Geographic SLA**: 95% of available riders within 5km radius at any time

## Key Responsibilities

1. **Rider State Management**: Track rider status (AVAILABLE, ASSIGNED, ON_DELIVERY, OFF_DUTY, UNAVAILABLE)
2. **Availability Tracking**: Real-time rider availability with skill matrix evaluation
3. **Geographic Service Areas**: Manage service zone assignments and capacity per zone
4. **Skill Assignment**: Evaluate rider skills (bike, scooter, car, stairs-access, large-items, premium) against order requirements
5. **Capacity Management**: Enforce max orders per shift and weight limits per vehicle type
6. **Location Indexing**: Maintain GIS index for proximity queries (within 5km radius)
7. **WebSocket Support**: Real-time status updates to dispatch systems
8. **Stuck Rider Recovery**: Detect and remediate riders stuck in ASSIGNED state > 60 minutes

## Deployment

### GKE Deployment
- **Namespace**: delivery
- **Replicas**: 3 (HA, load-balanced)
- **CPU Request/Limit**: 500m / 2000m
- **Memory Request/Limit**: 512Mi / 1Gi
- **Startup Probe**: 20s initial delay

### Database
- **Name**: `rider_fleet` (PostgreSQL 15+)
- **Migrations**: Flyway (V1-V8)
- **Connection Pool**: HikariCP 40 connections
- **Idle Timeout**: 5 minutes

### Network
- **Ingress**: Internal + WebSocket support
- **Egress**: To dispatch-optimizer, routing-eta, location-ingestion services
- **NetworkPolicy**: Deny-default; allow from checkout-orchestrator, dispatch-optimizer

## Architecture

### Rider State Machine

```
OFF_DUTY (rider offline)
    ↓ (rider logs in)
AVAILABLE (eligible for assignment)
    ↓ (order assigned)
ASSIGNED (order matched, awaiting confirmation)
    ├─ 30s timeout → AVAILABLE (if no confirmation)
    ↓ (rider confirms pickup)
ON_DELIVERY (order being delivered)
    ├─ [Delivery Complete] → AVAILABLE
    └─ [Delivery Failed] → AVAILABLE (with incident flag)
    ↑
UNAVAILABLE (rider temporarily blocked)
    └─ (e.g., low rating, failed delivery, system flag)
```

### Skill Matrix Evaluation

Riders have multi-dimensional skills affecting assignment eligibility:
- **Vehicle Type**: BIKE, SCOOTER, CAR, TRUCK
- **Delivery Class**: STANDARD, PREMIUM, FRAGILE, OVERSIZED, COLD_CHAIN
- **Special Capabilities**: STAIRS_ACCESS, LARGE_ITEMS, SIGNATURE_REQUIRED, PHOTO_PROOF
- **Rating Threshold**: Order requires rider rating >= threshold (e.g., 4.5 stars for premium)
- **Location Coverage**: Rider's zone + surrounding zones within service boundary

**Skill Compatibility Logic**:
```java
order.skills.stream()
  .allMatch(orderSkill -> rider.skills.contains(orderSkill) &&
                         rider.rating >= orderSkill.minRating &&
                         rider.zone.canService(order.location))
```

### Capacity Constraints

Each rider has hard limits enforced at assignment:
- **Max Orders Per Shift**: 30 (vehicle type dependent: BIKE=20, CAR=30, TRUCK=50)
- **Weight Limit**: Vehicle-dependent (BIKE=5kg, SCOOTER=10kg, CAR=50kg, TRUCK=500kg)
- **Max Active Deliveries**: 3 (batch delivery support)
- **Service Hours**: Time-zone aware shift windows (e.g., 9 AM - 6 PM local)

### Geographic Service Areas

Riders are assigned to zones with multi-zone coverage:
```
Zone (SF Downtown)
├─ Capacity: 100 riders max
├─ Service Boundary: Geo-polygon with lat/lon
├─ Peak Zones: Adjacent zones for surge assignments
├─ ETA Index: GIS index for proximity queries (PostGIS)
└─ Assignment Weight: Primary (100%), Secondary (50% surge)
```

## Integrations

### Synchronous Calls (Critical Path)
| Service | Endpoint | Timeout | Purpose | SLA |
|---------|----------|---------|---------|-----|
| dispatch-optimizer | http://dispatch-optimizer-service:8102/optimize | 500ms | Route optimization for assignment | P99 < 200ms |
| routing-eta | http://routing-eta-service:8101/eta | 300ms | ETA calculation for rider | P99 < 100ms |
| location-ingestion | http://location-ingestion-service:8093/subscribe | 5s | Subscribe to rider location stream | Async |

### Asynchronous Events
| Topic | Direction | Events |
|-------|-----------|--------|
| rider.events | Publish | RiderAssigned, RiderAvailable, RiderUnavailable, RiderLocationUpdated, DeliveryStarted, DeliveryCompleted, SkillMatrixUpdated |
| rider.location.updates | Consume | Location stream (GIS coordinates) |
| dispatch.events | Consume | AssignmentRejected (recovery trigger) |

### WebSocket Connections
- **Endpoint**: ws://rider-fleet-service:8097/ws/riders
- **Purpose**: Real-time availability & skill updates to dispatch console
- **Heartbeat**: 30s ping/pong
- **Max Connections**: 1000 concurrent

## Endpoints

### Public (Unauthenticated)
- `GET /actuator/health` - Liveness probe
- `GET /actuator/metrics` - Prometheus metrics

### Assignment API (Tier 1 Critical)
- `POST /riders/assign` - Find & assign nearest available rider (locked to 200ms SLA)
- `GET /riders/available?lat={lat}&lng={lng}&radius=5000` - List available riders within radius
- `GET /riders/by-skill?skill=STAIRS_ACCESS` - Query riders by skill capability

### Rider Management
- `POST /riders/register` - Register new rider with skills
- `PUT /riders/{id}/skills` - Update rider skill matrix
- `PUT /riders/{id}/status` - Update rider status (AVAILABLE, OFF_DUTY, UNAVAILABLE)
- `POST /riders/{id}/location` - Update rider GPS location (real-time)
- `GET /riders/{id}` - Fetch rider details + current assignment

### Admin Operations
- `POST /riders/recovery/stuck` - Trigger stuck rider recovery job
- `POST /riders/{id}/force-release` - Force release rider from stuck assignment
- `GET /riders/analytics/utilization` - Zone-wise utilization metrics
- `GET /riders/analytics/capacity` - Per-zone capacity planning data

### Example Requests

```bash
# Assign rider to order
curl -X POST http://rider-fleet-service:8097/riders/assign \
  -H "Authorization: Bearer $JWT_TOKEN" \
  -H "Content-Type: application/json" \
  -d '{
    "orderId": "order-uuid",
    "pickupLat": 37.7749,
    "pickupLng": -122.4194,
    "requiredSkills": ["STANDARD"],
    "maxDistanceKm": 5,
    "preferredZone": "SF_DOWNTOWN"
  }'

# Update rider location
curl -X POST http://rider-fleet-service:8097/riders/rider-123/location \
  -H "Content-Type: application/json" \
  -d '{
    "latitude": 37.7849,
    "longitude": -122.4094,
    "accuracy": 10,
    "bearing": 45
  }'

# Get available riders near coordinate
curl -X GET "http://rider-fleet-service:8097/riders/available?lat=37.7749&lng=-122.4194&radius=5000" \
  -H "Authorization: Bearer $JWT_TOKEN"
```

## Real-Time Status Updates via WebSocket

Dispatch systems receive live updates on rider availability:

```javascript
// Client-side
const ws = new WebSocket('ws://rider-fleet-service:8097/ws/riders');
ws.onmessage = (event) => {
  const update = JSON.parse(event.data);
  // update.type: 'RIDER_AVAILABLE', 'RIDER_ASSIGNED', 'LOCATION_UPDATE'
  // update.riderId, update.status, update.location
};
```

**Message Types**:
- `RIDER_AVAILABLE` - Rider transitioned to AVAILABLE
- `RIDER_ASSIGNED` - Rider assigned to order
- `LOCATION_UPDATE` - Rider location changed (throttled to 1/sec per rider)
- `SKILL_UPDATED` - Rider skill matrix changed (admin update)

## Data Model

### Core Entities

```
Riders Table:
├─ id (UUID, PK)
├─ phone (VARCHAR, unique)
├─ status (ENUM: AVAILABLE, ASSIGNED, ON_DELIVERY, OFF_DUTY, UNAVAILABLE)
├─ current_order_id (UUID, nullable)
├─ vehicle_type (ENUM: BIKE, SCOOTER, CAR, TRUCK)
├─ zone_id (UUID, FK → zones)
├─ rating (DECIMAL 4.2, 0.0-5.0)
├─ completed_deliveries (INT)
├─ active_deliveries (INT)
├─ max_orders_per_shift (INT)
├─ weight_capacity_kg (INT)
├─ updated_at (TIMESTAMP)
├─ version (INT, optimistic lock)
└─ (Index on: status, zone_id, rating, updated_at)

Rider_Skills Table (Many-to-Many):
├─ id (UUID, PK)
├─ rider_id (UUID, FK)
├─ skill (ENUM: STAIRS_ACCESS, LARGE_ITEMS, FRAGILE, COLD_CHAIN, etc.)
├─ certified_at (TIMESTAMP)
└─ (Unique constraint: (rider_id, skill))

Rider_Locations Table (Real-Time Index):
├─ id (UUID, PK)
├─ rider_id (UUID, FK, indexed)
├─ latitude (DECIMAL 8.6)
├─ longitude (DECIMAL 9.6)
├─ accuracy (INT, meters)
├─ bearing (INT, degrees)
├─ updated_at (TIMESTAMP, indexed)
├─ current_zone (UUID, FK)
└─ (PostGIS index for proximity queries)

Assignment_History Table (Audit):
├─ id (UUID, PK)
├─ rider_id (UUID, FK)
├─ order_id (UUID, FK)
├─ assignment_type (ENUM: AUTO, MANUAL, SURGE)
├─ assigned_at (TIMESTAMP)
├─ confirmed_at (TIMESTAMP, nullable)
├─ completion_status (ENUM: COMPLETED, FAILED, TIMEOUT)
├─ completed_at (TIMESTAMP, nullable)
├─ delivery_duration_seconds (INT, nullable)
└─ (Index on: rider_id, assigned_at)

Service_Zones Table:
├─ id (UUID, PK)
├─ name (VARCHAR)
├─ geo_boundary (POLYGON, PostGIS)
├─ max_capacity (INT)
├─ current_utilization (INT)
├─ peak_zones (ARRAY of zone_ids)
└─ (Index on: geo_boundary for GIS queries)

Outbox Table (CDC):
├─ id (BIGSERIAL, PK)
├─ aggregate_type (VARCHAR)
├─ aggregate_id (UUID)
├─ event_type (VARCHAR)
├─ payload (JSONB)
├─ created_at (TIMESTAMP)
└─ (Index on: created_at, aggregate_type)
```

## Configuration

### Environment Variables
```env
SERVER_PORT=8097
SPRING_PROFILES_ACTIVE=gcp

SPRING_DATASOURCE_URL=jdbc:postgresql://cloudsql-proxy:5432/rider_fleet
SPRING_DATASOURCE_HIKARI_POOL_SIZE=40
SPRING_DATASOURCE_HIKARI_IDLE_TIMEOUT_MINUTES=5

JWT_ISSUER=instacommerce-identity
JWT_PUBLIC_KEY=${JWT_PUBLIC_KEY_PEM}

KAFKA_BOOTSTRAP_SERVERS=kafka-broker-1:9092,kafka-broker-2:9092
KAFKA_TOPIC_RIDER_EVENTS=rider.events
KAFKA_TOPIC_LOCATION_UPDATES=rider.location.updates

RIDER_FLEET_STUCK_RIDER_ENABLED=true
RIDER_FLEET_STUCK_RIDER_THRESHOLD_MINUTES=60
RIDER_FLEET_STUCK_RIDER_CRON=0 */10 * * * *
RIDER_FLEET_MAX_ASSIGNMENT_LATENCY_MS=200
RIDER_FLEET_GIS_PROXIMITY_RADIUS_KM=5

OTEL_TRACES_SAMPLER=always_on
OTEL_EXPORTER_OTLP_TRACES_ENDPOINT=http://otel-collector.monitoring:4318/v1/traces
```

### application.yml
```yaml
rider-fleet:
  assignment:
    max-distance-km: 5
    max-latency-ms: 200  # Strict SLA for assignment operations
    search-radius-default-km: 5
  capacity:
    max-orders-per-shift: 30
    max-active-deliveries: 3
    weight-limit-kg:
      BIKE: 5
      SCOOTER: 10
      CAR: 50
      TRUCK: 500
  recovery:
    stuck-rider-enabled: true
    stuck-threshold-minutes: 60
    stuck-rider-cron: "0 */10 * * * *"
    batch-size: 50
  skill-matrix:
    min-rating-for-premium: 4.5
    min-rating-for-fragile: 4.2
  zones:
    peak-window-start-hour: 11
    peak-window-end-hour: 14
    surge-enabled: true

spring:
  datasource:
    hikari:
      pool-size: 40
      idle-timeout: 5m
  jpa:
    properties:
      hibernate:
        jdbc:
          batch_size: 25
  kafka:
    producer:
      acks: all
      retries: 3
```

## Monitoring & Alerts

### Key Metrics (20+ total)

| Metric | Type | Alert Threshold |
|--------|------|-----------------|
| `rider_assignment_latency_ms` | Histogram (p50, p95, p99) | p99 > 200ms |
| `rider_assignment_total` | Counter (by status) | N/A |
| `rider_assignment_success_rate` | Gauge | < 95% = SEV-2 |
| `rider_available_count` | Gauge (by zone) | < 10% capacity = surge |
| `rider_stuck_count` | Gauge | > 0 = SEV-2 |
| `rider_location_updates_total` | Counter | N/A |
| `rider_location_latency_ms` | Histogram | p99 > 100ms |
| `rider_skill_evaluation_time_ms` | Histogram | p99 > 50ms |
| `rider_capacity_utilization_pct` | Gauge (by zone) | > 80% = scale alert |
| `dispatch_optimizer_call_latency_ms` | Histogram | p99 > 500ms |
| `dispatch_optimizer_error_rate` | Gauge | > 1% = circuit break |
| `routing_eta_call_latency_ms` | Histogram | p99 > 300ms |
| `websocket_connections_active` | Gauge | > 900 = scale |
| `websocket_message_queue_depth` | Gauge | > 1000 = drop rate increase |
| `db_connection_pool_active` | Gauge | > 36/40 = contention |
| `db_connection_pool_pending` | Gauge | > 5 = scale |
| `kafka_producer_error_rate` | Gauge | > 0.5% = alert |
| `rider_rating_distribution` | Histogram | N/A |
| `zone_capacity_planning_ratio` | Gauge | > 0.9 = forecast alert |
| `stuck_rider_recovery_duration_ms` | Histogram | p99 > 5000ms |

### Alerting Rules

```yaml
alerts:
  - name: RiderAssignmentSlowdown
    condition: histogram_quantile(0.99, rider_assignment_latency_ms) > 200
    duration: 5m
    severity: SEV-1
    action: Page on-call; investigate dispatch-optimizer or routing-eta

  - name: LowAvailableRiders
    condition: rider_available_count < (rider_fleet:capacity * 0.1)
    duration: 10m
    severity: SEV-2
    action: Trigger surge pricing; alert operations

  - name: StuckRidersDetected
    condition: rider_stuck_count > 0
    duration: 2m
    severity: SEV-2
    action: Execute recovery job; investigate assignment logic

  - name: DispatchOptimizerCircuitOpen
    condition: dispatch_optimizer_error_rate > 0.01
    duration: 1m
    severity: SEV-2
    action: Fall back to simple distance-based assignment

  - name: WebSocketQueueBuildup
    condition: websocket_message_queue_depth > 1000
    duration: 5m
    severity: SEV-3
    action: Monitor; consider message batching

  - name: ZoneOverCapacity
    condition: rider_capacity_utilization_pct > 0.85
    duration: 15m
    severity: SEV-3
    action: Forecast alert for next peak window
```

### Logging

- **WARN**: Assignment timeouts, skill matrix evaluation failures, stuck rider detection
- **INFO**: Rider status transitions, assignment success, zone rebalancing events
- **ERROR**: Database connection pool exhaustion, Kafka producer errors, dispatch service failures
- **DEBUG**: Skill evaluation details, GIS proximity queries, WebSocket message flow

## Security Considerations

### Threat Mitigations

1. **Rider Data Privacy**: Location data encrypted in transit; access controlled by JWT scopes
2. **Assignment Fairness**: Randomized tie-breaking prevents algorithmic discrimination
3. **Rating Manipulation**: Immutable audit trail for rating changes (compliance)
4. **Skill Certification**: Manual verification workflow prevents self-certification
5. **Zone-Based Isolation**: Riders cannot request assignments outside their zones

### Known Risks

- **Compromised location stream**: Real-time location exposed if Kafka unencrypted
- **GIS index poisoning**: Malicious coordinates could degrade proximity performance
- **Database lock contention**: High assignment concurrency could cause 503 errors
- **WebSocket hijacking**: Unencrypted WS connections vulnerable to MITM attacks (mitigated by TLS required in production)

## Troubleshooting

### Scenario 1: Assignment Timeout (p99 > 200ms)

**Root Causes**:
1. Dispatch-optimizer circuit open (downstream service failing)
2. Routing-eta service slow (ETA calculations backing up)
3. Database lock contention on Riders table
4. GIS proximity query slow (large radius, many riders in zone)

**Resolution**:
```bash
# Check dispatch-optimizer health
curl http://dispatch-optimizer-service:8102/actuator/health

# Check routing-eta latency
curl http://routing-eta-service:8101/actuator/metrics/http_server_requests_seconds_max

# Analyze slow GIS queries
SELECT query, calls, mean_time FROM pg_stat_statements
WHERE query LIKE '%ST_%' AND mean_time > 100
ORDER BY mean_time DESC LIMIT 10;

# Temporarily increase search radius to reduce result set
RIDER_FLEET_GIS_PROXIMITY_RADIUS_KM=10 (instead of 5)
```

### Scenario 2: Stuck Riders Not Recovering

**Root Causes**:
1. Recovery job disabled (`RIDER_FLEET_STUCK_RIDER_ENABLED=false`)
2. Stuck rider threshold too high (riders actually waiting)
3. Force-release API failing due to optimistic lock conflict
4. Event publishing failing for RiderAvailable events

**Resolution**:
```bash
# Verify recovery job enabled
echo $RIDER_FLEET_STUCK_RIDER_ENABLED

# Check last job execution
SELECT * FROM scheduled_job_log
WHERE job_name = 'RecoveryStuckRiders'
ORDER BY executed_at DESC LIMIT 5;

# Manual recovery if needed
curl -X POST http://rider-fleet-service:8097/admin/riders/recovery/stuck \
  -H "Authorization: Bearer $ADMIN_TOKEN"

# Check for stuck riders
SELECT * FROM riders
WHERE status = 'ASSIGNED'
AND updated_at < NOW() - INTERVAL '60 minutes';
```

### Scenario 3: WebSocket Connections Failing

**Root Causes**:
1. Message queue depth > 1000 (producer lag)
2. Rider location updates not flowing (Kafka consumer lag)
3. Client-side reconnection storm (exponential backoff not working)
4. TLS certificate issues (if using WSS)

**Resolution**:
```bash
# Check Kafka consumer lag
kafka-consumer-groups --bootstrap-server kafka-broker-1:9092 \
  --group rider-fleet-location-consumer --describe

# Monitor message queue
curl http://rider-fleet-service:8097/actuator/metrics/websocket_message_queue_depth

# Verify TLS certificate expiry
openssl s_client -connect rider-fleet-service:8097 -tls1_2 < /dev/null | \
  openssl x509 -noout -dates
```

### Scenario 4: Zone Capacity Exceeded (>85% utilization)

**Root Causes**:
1. Peak demand window exceeding rider supply
2. Riders offline unexpectedly (network issues)
3. Assignment logic not utilizing adjacent zones for surge
4. Shift schedules misaligned with peak windows

**Resolution**:
```bash
# Check current zone utilization
curl http://rider-fleet-service:8097/admin/analytics/capacity

# Identify low-utilization zones
SELECT zone_id, current_utilization, max_capacity,
       (current_utilization::float / max_capacity * 100) as pct
FROM service_zones
ORDER BY pct DESC;

# Enable surge assignment to adjacent zones
RIDER_FLEET_SURGE_ENABLED=true

# Manually trigger rider shift extension
UPDATE riders SET off_duty_time = off_duty_time + INTERVAL '30 minutes'
WHERE zone_id = 'sf-downtown-zone-1' AND status = 'AVAILABLE';
```

### Scenario 5: GIS Proximity Queries Slow (>1 second)

**Root Causes**:
1. PostGIS index fragmented or missing
2. Rider location table bloated (excessive updates without VACUUM)
3. Radius too large (searching 10km instead of 5km)
4. Concurrent queries causing index lock contention

**Resolution**:
```bash
# Rebuild PostGIS index
REINDEX INDEX rider_locations_geo_idx;

# Full table vacuum and analyze
VACUUM ANALYZE rider_locations;

# Check index usage
SELECT schemaname, tablename, indexname, idx_scan, idx_tup_read, idx_tup_fetch
FROM pg_stat_user_indexes
WHERE tablename = 'rider_locations'
ORDER BY idx_scan DESC;

# Monitor index fragmentation
SELECT current_database, schemaname, tablename, indexname,
       ROUND(100.0 * (pg_relation_size(idx) - pg_relation_size(idx, 'main')) / pg_relation_size(idx), 2) AS fragmentation_pct
FROM (SELECT * FROM pg_stat_user_indexes WHERE tablename = 'rider_locations') si
JOIN (SELECT oid::regclass AS idx, pg_relation_size(oid) FROM pg_class WHERE reltype = 0) ps ON (si.idx)::regclass = ps.idx;
```

### Scenario 6: Assignment Success Rate Drops Below 95%

**Root Causes**:
1. Insufficient riders in service area
2. High proportion of riders not meeting skill requirements
3. Capacity constraints (max orders per shift) blocking assignments
4. Skill matrix evaluation too strict

**Resolution**:
```bash
# Analyze assignment failures
SELECT failure_reason, COUNT(*) as count
FROM assignment_attempts
WHERE status = 'FAILED'
AND created_at > NOW() - INTERVAL '1 hour'
GROUP BY failure_reason
ORDER BY count DESC;

# Check rider skill distribution
SELECT skill, COUNT(DISTINCT rider_id) as rider_count
FROM rider_skills
GROUP BY skill
ORDER BY rider_count DESC;

# Relax skill requirements for non-critical orders
POST /admin/riders/skill-requirements/relax
{
  "skill": "STAIRS_ACCESS",
  "optional": true
}
```

## Related Documentation

- **ADR-012**: Per-Service Token Scoping (auth strategy)
- **ADR-014**: Reconciliation Authority Model (audit trail compliance)
- **Runbook**: rider-fleet-service/runbook.md
- **High-Level Design**: diagrams/hld.md
- **Low-Level Architecture**: diagrams/lld.md
- **Database ERD**: diagrams/erd.md
- **REST API Contract**: implementation/api.md
- **Kafka Events**: implementation/events.md
- **Resilience & Retries**: implementation/resilience.md
