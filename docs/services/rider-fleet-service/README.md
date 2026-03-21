# Rider Fleet Service - Comprehensive Documentation

## Overview
**Ownership**: Platform Team - Logistics
**Language**: Java 21 / Spring Boot 4.0
**Default Port**: 8091
**Status**: Tier 1 Critical (Delivery Path)
**SLO**: 99.9% availability, P99 < 1.5s

---

## Endpoints
- `POST /riders/assign` - Assign order to nearby rider
- `POST /riders/{id}/location` - Update rider location
- `POST /riders/{id}/status` - Update rider status
- `GET /riders/available` - List available riders
- `POST /riders/recovery/stuck` - Recovery job for stuck riders (admin)

---

## Database Schema
**Name**: `rider_fleet` | **Migrations**: Flyway V1-V8

### Core Tables
```sql
-- Riders
id (UUID), phone, status (AVAILABLE, ASSIGNED, ON_DELIVERY, OFF_DUTY), current_order_id, version (optimistic lock)

-- Rider Locations
id, rider_id, latitude, longitude, updated_at, current_zone

-- Assignment History
id, rider_id, order_id, assigned_at, completed_at, status

-- Outbox (CDC)
Same pattern for event publishing
```

---

## Kafka Events
**Topics**:
- `rider.events` - Published events
- `rider.location.updates` - Real-time location stream

**Events Published**:
- RiderAssigned - Order assigned to rider
- DeliveryStarted - Rider started delivery
- DeliveryCompleted - Order delivered
- RiderLocationUpdated - Location changed

---

## Resilience Configuration
```yaml
resilience4j:
  circuitbreaker:
    instances:
      dispatchOptimizer:
        failureRateThreshold: 50%
        waitDurationInOpenState: 30s

rider-fleet:
  recovery:
    stuck-rider-enabled: false  # Can enable after @Version wave
    stuck-threshold-minutes: 60
    stuck-rider-cron: 0 */10 * * * *
  dispatch:
    optimizer-enabled: false  # Optional, integrates with dispatch-optimizer
    optimizer-base-url: http://dispatch-optimizer-service:8102
```

---

## Key Features
- **Rider Assignment**: Find nearest rider within 5km radius
- **Location Tracking**: Real-time location updates via location.updates topic
- **Status Management**: AVAILABLE → ASSIGNED → ON_DELIVERY → AVAILABLE
- **Optimistic Locking**: @Version prevents concurrent state conflicts
- **Recovery Jobs**: Stuck rider detection (after Wave 30 @Version fix)
- **Cache**: Caffeine cache for rider availability (1000 entries, 60s TTL)

---

## Deployment
- Port: 8091
- Replicas: 3
- CPU: 500m request / 1500m limit
- Memory: 512Mi request / 1Gi limit
- Namespace: money-path

---

## Health & Monitoring
- Liveness: `/actuator/health/live`
- Readiness: `/actuator/health/ready` (checks DB, Kafka)
- Metrics: `/actuator/prometheus`
- Key metric: `assignment_latency_ms` (should be <500ms)

---

## Recovery Features
**Stuck Rider Recovery** (Wave 30+):
- Detects riders stuck on same order >60 min
- Triggers manual reconciliation or auto-reassignment
- Cron: Every 10 minutes
- Batch size: 50 riders per run

---

## Documentation Files
- [High-Level Design](diagrams/hld.md)
- [Low-Level Architecture](diagrams/lld.md)
- [Database ERD](diagrams/erd.md)
- [Request/Response Flows](diagrams/flowchart.md)
- [Sequence Diagrams](diagrams/sequence.md)
- [REST API Contract](implementation/api.md)
- [Kafka Events](implementation/events.md)
- [Database Details](implementation/database.md)
- [Resilience & Retries](implementation/resilience.md)
- [Deployment Runbook](runbook.md)
