# Inventory Service - Comprehensive Documentation

## Overview
**Ownership**: Platform Team - Money Path Tier 1
**Language**: Java 21 / Spring Boot 4.0
**Default Port**: 8083
**Status**: Tier 1 Critical
**SLO**: 99.95% availability, P99 < 800ms

---

## Endpoints
- `POST /inventory/reserve` - Reserve stock for order
- `POST /inventory/release` - Release reservation
- `GET /inventory/{productId}` - Check stock level
- `POST /inventory/{productId}/adjust` - Admin stock adjustment
- `POST /inventory/expiry-check` - Trigger reservation expiry job

---

## Database Schema
**Name**: `inventory` | **Migrations**: Flyway V1-V12

### Core Tables
```sql
-- Stock
id (UUID), product_id, store_id, quantity_available, quantity_reserved, low_stock_threshold

-- Reservations
id (UUID), order_id, product_id, store_id, quantity, reserved_at, expires_at (5 min TTL), released

-- Outbox (CDC)
Same pattern for event publishing
```

---

## Kafka Events
**Topic**: `inventory.events`

**Events Published**:
- StockReserved - Reservation created
- StockReleased - Reservation released
- StockAdjusted - Admin adjusted stock
- LowStockAlert - Stock below threshold
- ReservationExpired - TTL expired

---

## Resilience Configuration
```yaml
inventory:
  lock-timeout-ms: 2000  # Row-level locking timeout
  low-stock-threshold: 10

reservation:
  ttl-minutes: 5  # Reservations expire after 5 minutes
  expiry-check-interval-ms: 30000  # Check every 30 seconds
```

---

## Key Features
- **Row-Level Locking**: Pessimistic locking via SELECT...FOR UPDATE
- **Reservations**: TTL-based, auto-expire after 5 minutes
- **Low Stock Alerts**: Event published when stock < threshold
- **Store-Based**: Stock tracked per location (store_id)
- **Concurrency**: High-throughput concurrent reservations
- **Connection Pool**: 60 connections (high concurrency support)

---

## Deployment
- Port: 8083
- Replicas: 3
- CPU: 500m request / 1500m limit
- Memory: 512Mi request / 1Gi limit
- Namespace: money-path

---

## Health & Monitoring
- Liveness: `/actuator/health/live`
- Readiness: `/actuator/health/ready` (checks DB)
- Metrics: `/actuator/prometheus`
- Key metric: `reservation_latency_ms` (should be <100ms)

---

## Concurrency Notes
- Highest connection pool size (60 min-idle: 15) due to concurrent reservations
- Pessimistic locking (SELECT...FOR UPDATE) ensures correctness
- Lock timeout: 2 seconds (fail fast on contention)

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
