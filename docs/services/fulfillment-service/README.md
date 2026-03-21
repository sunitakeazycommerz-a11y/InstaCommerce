# Fulfillment Service - Comprehensive Documentation

## Overview
**Ownership**: Platform Team - Money Path Tier 1
**Language**: Java 21 / Spring Boot 4.0
**Default Port**: 8080
**Status**: Tier 1 Critical
**SLO**: 99.9% availability, P99 < 1.2s

---

## Endpoints
- `POST /pick/{orderId}` - Start picking process
- `POST /pick/{orderId}/complete` - Mark items picked
- `POST /delivery/{orderId}/assign` - Assign to rider
- `POST /delivery/{orderId}/update-status` - Update delivery status
- `GET /pick/pending` - Get pending pick jobs
- `GET /delivery/active` - Get active deliveries

---

## Database Schema
**Name**: `fulfillment` | **Migrations**: Flyway V1-V8

### Core Tables
```sql
-- Pick Jobs
id (UUID), order_id, status (PENDING, IN_PROGRESS, COMPLETED, FAILED), created_at

-- Pick Items
id, pick_job_id, order_item_id, product_id, quantity, status (PENDING, PICKED, NOT_AVAILABLE)

-- Deliveries
id, order_id, rider_id, status (ASSIGNED, IN_PROGRESS, DELIVERED, FAILED), eta_minutes

-- Outbox (CDC)
Same pattern for event publishing
```

---

## Kafka Events
**Topics**:
- `fulfillment.events` - Published events
- Consumes: `orders.events` (OrderCreated, OrderCancelled)

**Events Published**:
- PickingStarted - Started picking items
- ItemPicked - Item successfully picked
- ItemNotAvailable - Item out of stock
- PickingCompleted - All items picked
- DeliveryAssigned - Rider assigned
- DeliveryInProgress - Delivery started
- DeliveryCompleted - Order delivered

---

## Resilience Configuration
```yaml
resilience4j:
  circuitbreaker:
    instances:
      paymentService:
        failureRateThreshold: 50%
        waitDurationInOpenState: 30s
      inventoryService:
        failureRateThreshold: 50%
        waitDurationInOpenState: 30s
      orderService:
        failureRateThreshold: 50%
        waitDurationInOpenState: 30s
      warehouseService:
        failureRateThreshold: 50%
        waitDurationInOpenState: 30s
  retry:
    instances:
      paymentService:
        maxAttempts: 3
        waitDuration: 500ms
```

---

## Key Features
- **Event-Driven**: Consumes OrderCreated to start picking
- **Inventory Integration**: Queries inventory for item availability
- **Warehouse Location**: Stores specify pick locations
- **Rider Assignment**: Assigns orders to nearby riders (5km radius)
- **Status Tracking**: Tracks picking → packing → delivery
- **ETA Calculation**: Default 15 min ETA, updated by routing service

---

## Choreography
- Consumes `orders.events` (OrderCreated → start picking)
- Consumes `orders.events` (OrderCancelled → stop picking, release inventory)
- Publishes `fulfillment.events` (PickingStarted, PickingCompleted, DeliveryInProgress)

---

## Deployment
- Port: 8080
- Replicas: 3
- CPU: 500m request / 1500m limit
- Memory: 512Mi request / 1Gi limit
- Namespace: money-path

---

## Health & Monitoring
- Liveness: `/actuator/health/live`
- Readiness: `/actuator/health/ready` (checks DB, Kafka)
- Metrics: `/actuator/prometheus`
- Key metric: `picking_latency_seconds` (should be <300s)

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
