# Fulfillment Service

## Overview

The fulfillment-service orchestrates the complete fulfillment lifecycle for orders—from picking items in warehouse to assigning riders for delivery. It acts as the operational authority for warehouse management and last-mile delivery coordination, ensuring orders are picked accurately, packed, and handed to riders for timely delivery.

**Service Ownership**: Platform Team - Money Path Tier 1
**Language**: Java 21 / Spring Boot 4.0
**Default Port**: 8080
**Status**: Tier 1 Critical (operational fulfillment)
**Database**: PostgreSQL 15+ (fulfillment tracking)

## SLOs & Availability

- **Availability SLO**: 99.5% (43 minutes downtime/month - operational tolerance)
- **P99 Latency**:
  - Picking assignment: < 2s (POST /pick/{orderId})
  - Delivery assignment: < 2s (POST /delivery/{orderId}/assign)
  - Status queries: < 500ms (GET endpoints)
- **Error Rate**: < 0.5% (failed assignments, missing rider data)
- **ETA Accuracy**: 95% within ±15 minutes (when ETA provided)
- **Max Throughput**: 1,000 orders/minute (warehouse & rider capacity)

## Key Responsibilities

1. **Pick Job Creation**: Consume OrderCreated events; create picking tasks for warehouse staff
2. **Item Picking Management**: Track individual item picking status; handle out-of-stock scenarios
3. **Pick Cancellation**: Release picks when orders cancelled (pre-packing)
4. **Rider Assignment**: Assign picked/packed orders to available riders based on location proximity
5. **ETA Tracking**: Aggregate rider ETAs from routing-service; communicate to customer
6. **Status Updates**: Receive delivery status updates from riders in real-time
7. **Rider Reassignment**: Re-assign orders if rider becomes unavailable (e.g., accident, vehicle breakdown)
8. **Event Publishing**: Publish fulfillment events (PickingStarted, DeliveryAssigned, DeliveryCompleted) to downstream systems

## Deployment

### GKE Deployment
- **Namespace**: money-path
- **Replicas**: 3 (HA, active-active across warehouse zones)
- **CPU Request/Limit**: 500m / 1500m
- **Memory Request/Limit**: 512Mi / 1Gi
- **Readiness Probe**: `/actuator/health/ready` (checks DB + Kafka)

### Database
- **Name**: `fulfillment` (PostgreSQL 15+)
- **Migrations**: Flyway (V1-V8)
- **Connection Pool**: HikariCP 25 connections
- **Replicas**: Read replicas for analytics queries

### Network
- **Service Account**: `fulfillment-service@project.iam.gserviceaccount.com`
- **Ingress**: Through api-gateway
- **Egress**: To order-service, inventory-service, warehouse-service, rider-fleet-service
- **NetworkPolicy**: Deny-default; allow from checkout-orchestrator, admin-gateway

## Architecture

### System Context

```
┌──────────────────────────────────────────────────────────┐
│            Fulfillment Service (Ops Authority)            │
│     (Picking, Packing, Rider Assignment, Delivery)       │
└────────┬────────────────────────┬────────────────────┘
         │                        │
    ┌────▼────┐          ┌────────▼────────┐
    │ Upstream │          │   Downstream    │
    │ Clients  │          │   Dependents    │
    └────┬────┘          └────────┬────────┘
         │                        │
   • orders.events       • notification-service
   • admin-gateway       • analytics platform
   • warehouse-ops       • rider-app
```

### Fulfillment Workflow State Machine

```
ORDER PICKING PHASE:
  PENDING (awaiting picking)
    ↓ warehouse staff starts
  IN_PROGRESS (items being picked)
    ├─ [SUCCESS] ↓
    │ COMPLETED (all items picked)
    │   ↓
    │ PACKING (warehouse staff packs box)
    │   ↓
    │ PACKED (ready for rider pickup)
    │
    └─ [FAIL] ↓
      PARTIAL (some items not available)
        ↓
      CANCELLED (order cancelled, items returned to shelves)

DELIVERY PHASE:
  ASSIGNED (rider assigned)
    ↓
  IN_TRANSIT (rider en route)
    ↓
  DELIVERED (customer received)
    ├─ [SUCCESS]
    │ Completed
    └─ [FAIL]
      FAILED (delivery failed, will retry)
```

### Picking & Delivery Flow

```
1. order-service
        ↓ OrderCreated event
2. fulfillment-service → Create pick job
        ↓ (async, warehouse staff action)
3. warehouse-ops → Pick items (scan barcodes)
        ↓
4. fulfillment-service ← PickingCompleted
        ↓ PublishPickingCompleted event
5. notification-service ← Notify customer "order packed"
        ↓
6. fulfillment-service → Assign to nearby rider
        ↓ (via dispatch-optimizer-service)
7. rider-app ← Delivery assigned notification
        ↓ (rider accepts, en route)
8. rider-location-ingestion → Real-time location updates
        ↓
9. fulfillment-service ← Location updates → ETA calculation
        ↓
10. notification-service ← ETA update
        ↓
11. rider-app ← Delivery completed (photo, signature)
        ↓
12. fulfillment-service ← DeliveryCompleted
        ↓ PublishDeliveryCompleted event
13. order-service ← Status update (ORDER delivered)
        ↓
14. analytics-platform ← Fulfillment metrics
```

## Integrations

### Synchronous Calls (Circuit Breakers)
| Service | Endpoint | Timeout | Purpose | Retry |
|---------|----------|---------|---------|-------|
| inventory-service | http://inventory-service:8083/inventory/{productId} | 5s | Check item availability | 2 retries |
| warehouse-service | http://warehouse-service:8090/stores/{id}/zones | 3s | Get picking zone location | 1 retry |
| dispatch-optimizer | http://dispatch-optimizer:8080/optimize | 10s | Assign to best rider | 3 retries |
| rider-fleet-service | http://rider-fleet:8092/riders/available | 5s | Query available riders | 2 retries |
| order-service | http://order-service:8085/orders/{id} | 3s | Update order status | None (async via Kafka) |

### Asynchronous Event Channels
| Topic | Direction | Events | Format |
|-------|-----------|--------|--------|
| orders.events | Consume | OrderCreated, OrderCancelled | Kafka |
| fulfillment.events | Publish | PickingStarted, ItemPicked, PickingCompleted, DeliveryAssigned, DeliveryInProgress, DeliveryCompleted | Kafka (via outbox) |
| location.events | Consume | RiderLocationUpdated (real-time coordinates) | Kafka |

## Data Model

### Core Entities

```
Pick Jobs Table:
├─ id (UUID, PK)
├─ order_id (UUID, FK → order-service, indexed)
├─ status (ENUM: PENDING, IN_PROGRESS, COMPLETED, PARTIAL, CANCELLED)
├─ warehouse_id (UUID, FK → warehouse-service)
├─ zone_id (VARCHAR - picking zone, e.g., "Zone A Aisle 1")
├─ assigned_to_staff_id (UUID, optional - staff member picking)
├─ started_at (TIMESTAMP)
├─ completed_at (TIMESTAMP)
└─ (Audit fields)

Pick Items Table:
├─ id (UUID, PK)
├─ pick_job_id (UUID, FK → Pick Jobs)
├─ order_item_id (UUID - reference to order item)
├─ product_id (UUID)
├─ quantity_required (INT)
├─ quantity_picked (INT)
├─ status (ENUM: PENDING, PICKED, NOT_AVAILABLE)
├─ scanned_at (TIMESTAMP)
└─ barcode_scan_id (VARCHAR - audit trail)

Deliveries Table:
├─ id (UUID, PK)
├─ order_id (UUID, FK, indexed)
├─ pick_job_id (UUID, FK)
├─ rider_id (UUID, FK → rider-fleet-service)
├─ status (ENUM: ASSIGNED, IN_TRANSIT, DELIVERED, FAILED)
├─ assigned_at (TIMESTAMP)
├─ picked_up_at (TIMESTAMP)
├─ delivered_at (TIMESTAMP)
├─ eta_minutes (INT - estimated minutes until delivery)
├─ distance_km (FLOAT - delivery distance)
├─ delivery_address_encrypted (TEXT)
├─ delivery_photo_url (VARCHAR - proof of delivery)
└─ signature_data (BYTEA - digital signature)

Outbox Events Table:
├─ id (BIGSERIAL, PK)
├─ pick_job_id (UUID, FK)
├─ event_type (VARCHAR: PickingStarted, ItemPicked, PickingCompleted, etc.)
├─ event_payload (JSONB)
├─ created_at (TIMESTAMP)
└─ sent (BOOLEAN)
```

## API Documentation

### Create Pick Job
**POST /pick/{orderId}**
```bash
Authorization: Bearer {JWT_TOKEN}

Request:
{
  "warehouseId": "warehouse-uuid",
  "items": [
    {
      "orderItemId": "item-1",
      "productId": "prod-uuid",
      "quantity": 2
    }
  ]
}

Response (201):
{
  "id": "pick-job-uuid",
  "orderId": "order-uuid",
  "status": "PENDING",
  "items": [...],
  "createdAt": "2025-03-21T10:00:00Z"
}
```

### Update Pick Status
**PUT /pick/{pickJobId}/items/{itemId}/status**
```bash
Authorization: Bearer {JWT_TOKEN}

Request:
{
  "status": "PICKED",
  "quantity": 2,
  "scanCode": "barcode-scan-id"
}

Response (200):
{
  "id": "item-uuid",
  "status": "PICKED",
  "quantity": 2,
  "pickedAt": "2025-03-21T10:05:00Z"
}
```

### Complete Pick Job
**POST /pick/{pickJobId}/complete**
```bash
Response (200):
{
  "id": "pick-job-uuid",
  "status": "COMPLETED",
  "completedAt": "2025-03-21T10:15:00Z",
  "totalDuration": "15 minutes"
}
```

### Assign Delivery
**POST /delivery/{pickJobId}/assign**
```bash
Request:
{
  "algorithm": "nearest",  # or "lowest-cost", "balanced"
  "constraints": {
    "maxDistanceKm": 15,
    "minRating": 4.5
  }
}

Response (201):
{
  "id": "delivery-uuid",
  "orderId": "order-uuid",
  "riderId": "rider-uuid",
  "riderName": "John Doe",
  "riderPhone": "+1-555-0100",
  "etaMinutes": 23,
  "status": "ASSIGNED",
  "assignedAt": "2025-03-21T10:20:00Z"
}
```

### Update Delivery Status
**PUT /delivery/{deliveryId}/status**
```bash
Request:
{
  "status": "DELIVERED",
  "location": {
    "latitude": 37.7749,
    "longitude": -122.4194
  },
  "photoUrl": "s3://proof-of-delivery/...",
  "notes": "Left at door as requested"
}

Response (200):
{
  "id": "delivery-uuid",
  "status": "DELIVERED",
  "deliveredAt": "2025-03-21T10:42:00Z"
}
```

## Error Handling & Resilience

### Circuit Breaker Strategy
```yaml
resilience4j:
  circuitbreaker:
    instances:
      inventoryService:
        failureRateThreshold: 50%
        waitDurationInOpenState: 30s
      dispatchOptimizer:
        failureRateThreshold: 60%  # More lenient; optimization is best-effort
        waitDurationInOpenState: 60s
      riderFleetService:
        failureRateThreshold: 40%
        waitDurationInOpenState: 20s
  retry:
    instances:
      inventoryService:
        maxAttempts: 3
        waitDuration: 500ms
      dispatchOptimizer:
        maxAttempts: 2
        waitDuration: 1s
```

### Failure Scenarios

**Scenario 1: Item Not Available**
- Inventory-service returns item unavailable
- Fulfillment marks item as NOT_AVAILABLE
- Pick job status → PARTIAL
- Publishes ItemNotAvailable event
- Order-service receives event; notifies customer of delay
- Recovery: Manual warehouse approval to substitute similar item or cancel

**Scenario 2: Dispatch Optimizer Fails**
- Circuit breaker opens after 60% failures
- Fallback: Use simple nearest-rider assignment algorithm
- Recovery: Automatic half-open after 60s; monitor optimizer service

**Scenario 3: Rider Assignment Fails**
- No available riders within radius
- Fulfillment returns 503 Service Unavailable
- Client (warehouse) retries later
- Recovery: Wait for more riders to come online; automated retry via scheduled job

**Scenario 4: Delivery Status Lost**
- Rider location/delivery completion not received
- Background job checks rider app every 5 minutes for status
- Recovery: Eventual consistency within 5 minutes

## Configuration

### Environment Variables
```env
SERVER_PORT=8080
SPRING_PROFILES_ACTIVE=gcp

SPRING_DATASOURCE_URL=jdbc:postgresql://cloudsql-proxy:5432/fulfillment
SPRING_DATASOURCE_HIKARI_POOL_SIZE=25

JWT_ISSUER=instacommerce-identity
JWT_PUBLIC_KEY=${JWT_PUBLIC_KEY_PEM}

KAFKA_BOOTSTRAP_SERVERS=kafka-broker-1:9092,kafka-broker-2:9092

OTEL_EXPORTER_OTLP_TRACES_ENDPOINT=http://otel-collector.monitoring:4318/v1/traces
```

### application.yml
```yaml
fulfillment:
  picking:
    max-items-per-job: 100
    zone-optimization: true
  delivery:
    rider-radius-km: 15
    max-concurrent-assignments: 5
    eta-update-interval-seconds: 30
  circuit-breaker:
    dispatch-wait-duration: 60s
    inventory-wait-duration: 30s
```

## Monitoring & Observability

### Key Metrics

| Metric | Type | Alert |
|--------|------|-------|
| `pick_job_duration_minutes` | Histogram (p50, p95, p99) | p99 > 20 min |
| `pick_job_completion_rate` | Gauge (%) | < 95% for 1 hour |
| `items_not_available_total` | Counter | rate > 10/min = investigate |
| `delivery_assignment_latency_ms` | Histogram | p99 > 2000ms |
| `rider_assignment_success_rate` | Gauge (%) | < 90% = alert |
| `delivery_eta_accuracy` | Gauge (%) | < 80% within ±15min |
| `delivery_completion_time_minutes` | Histogram | p99 > 60 min |
| `circuit_breaker_dispatch_state` | Gauge | state = open → check optimizer |

### Alert Rules
```yaml
alerts:
  - name: HighPickJobFailureRate
    condition: (1 - pick_job_completion_rate) > 0.05
    duration: 10m
    severity: SEV-2
    action: Alert warehouse ops team

  - name: DispatchOptimizerDown
    condition: circuit_breaker_dispatch_state == 1
    duration: 5m
    severity: SEV-2
    action: Alert on-call; investigate optimizer service

  - name: DeliveryRiderAssignmentFailing
    condition: rider_assignment_success_rate < 0.9
    duration: 5m
    severity: SEV-2
    action: Alert fulfillment team; check rider availability
```

## Security Considerations

- **Auth**: JWT RS256 validation
- **User Isolation**: Warehouse staff can only manage assigned zones
- **Data Protection**: Delivery addresses encrypted; customer photos secure
- **Audit Trail**: All pick/delivery status changes logged

## Troubleshooting

### Issue: Pick Job Stuck in PENDING
**Possible Causes**:
1. Warehouse staff not accepting job
2. Item not available (incorrect inventory)
3. System error

**Resolution**: Manual warehouse approval; check inventory synchronization

### Issue: Rider Assignment Fails (503)
**Possible Causes**:
1. No riders available in radius
2. Dispatch optimizer down

**Resolution**: Wait for riders; check circuit breaker status

### Issue: Delivery ETA Inaccurate
**Possible Causes**:
1. Traffic conditions not accounted for
2. Rider location not updating in real-time

**Resolution**: Check location-ingestion service; validate routing-service

## Operational Runbooks

See [runbook.md](runbook.md) for:
- Pre-deployment checklist
- Deployment procedures
- Scaling operations
- Incident response (picking bottlenecks, rider availability crisis)

## Related Documentation

- [High-Level Design](diagrams/hld.md)
- [Low-Level Architecture](diagrams/lld.md)
- [Database Schema (ERD)](diagrams/erd.md)
- [REST API Contract](implementation/api.md)
- [Kafka Events](implementation/events.md)
- [Database Details](implementation/database.md)
- [Resilience & Retry Logic](implementation/resilience.md)

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
