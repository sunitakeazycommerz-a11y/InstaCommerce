# Warehouse Service - Comprehensive Documentation

## Overview
**Ownership**: Platform Team - Store Ops
**Language**: Java 21 / Spring Boot 4.0
**Default Port**: 8090
**Status**: Tier 2 (Store/Warehouse Management)
**SLO**: 99.9% availability, P99 < 1.2s

---

## Endpoints
- `GET /stores/nearest` - Find nearest store by location
- `POST /stores/{id}/zones` - Create store zones (picking zones)
- `GET /stores/{id}/zones` - List zones
- `POST /stores/{id}/hours` - Set store hours
- `GET /stores/{id}/hours` - Get store hours

---

## Database Schema
**Name**: `warehouse` | **Migrations**: Flyway V1-V6

### Core Tables
```sql
-- Stores
id (UUID), store_id (VARCHAR, unique), location (geometry/PostGIS), radius_km, max_results

-- Store Zones
id (UUID), store_id, zone_code, zone_name, aisle, shelf_level

-- Store Hours
id (UUID), store_id, day_of_week, open_time, close_time

-- Caching (Redis-backed via Caffeine)
stores, store-zones, store-hours
```

---

## Cache Configuration
```yaml
spring:
  cache:
    type: caffeine
    caffeine:
      spec: maximumSize=2000,expireAfterWrite=300s
    cache-names:
      - stores
      - store-zones
      - store-hours
```

**Cache TTL**: 5 minutes

---

## Key Features
- **Geolocation**: Find nearest store within radius
- **Store Zones**: Organize picking zones (e.g., "Zone A - Aisle 1")
- **Store Hours**: Track operating hours (for ETA calculation)
- **Local Caching**: Caffeine cache for fast zone lookups
- **Radius-Based**: Configurable default radius (10km)

---

## Deployment
- Port: 8090
- Replicas: 3
- CPU: 500m request / 1500m limit
- Memory: 512Mi request / 1Gi limit
- Namespace: money-path

---

## Health & Monitoring
- Liveness: `/actuator/health/live`
- Readiness: `/actuator/health/ready` (checks DB)
- Metrics: `/actuator/prometheus`
- Key metric: `store_lookup_latency_ms` (should be <50ms with cache)

---

## Cache Invalidation
- No Kafka integration (read-only service primarily)
- Manual cache invalidation on zone/hours updates
- TTL-based expiration (5 minutes)

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
