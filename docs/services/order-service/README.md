# Order Service

## Overview

The order-service manages the complete order lifecycle in InstaCommerce, serving as the source of truth for order data. It handles order creation, status tracking, fulfillment coordination, and provides query APIs for customers and internal services.

**Service Ownership**: Platform Team - Money Path Tier 1
**Language**: Java 21 / Spring Boot 4.0
**Default Port**: 8085 (also responds on 8080)
**Status**: Tier 1 Critical (Tier 1 of money-path cluster)

## SLOs & Availability

- **Availability SLO**: 99.95% (45 minutes downtime/month)
- **P99 Latency**: < 500ms for reads, < 2s for order creation
- **Error Rate**: < 0.1%
- **Max Throughput**: 5,000 orders/minute (horizontal scaling at 3 replicas)

## Key Responsibilities

1. **Order Creation**: Persist orders from checkout orchestrator via Temporal activities
2. **Order Status Tracking**: Maintain status history, state transitions
3. **Order Queries**: Expose order details, status, history to customers and services
4. **Cancellation**: Support order cancellation by customer (before fulfillment)
5. **Event Publishing**: Outbox + CDC pattern for reliable OrderCreated, OrderStatusChanged events
6. **Audit Trail**: Track order modifications for compliance

## Deployment

### GKE Deployment
- **Namespace**: money-path
- **Replicas**: 3 (HA)
- **CPU Request/Limit**: 500m / 1500m
- **Memory Request/Limit**: 512Mi / 1Gi

### Database
- **Name**: `orders` (PostgreSQL)
- **Driver**: jdbc:postgresql
- **Migrations**: Flyway (V1-V11)
- **CDC Relay**: Wired via outbox-relay-service

## Integrations

### Synchronous Clients
| Service | Endpoint | Purpose |
|---------|----------|---------|
| checkout-orchestrator | Internal Temporal Activity | Order creation |
| fulfillment-service | Order status events | Fulfillment tracking |
| inventory-service | Query reservations | Stock verification |
| payment-service | Payment lookup | Payment reconciliation |

### Async Event Channels
- **Outbox Table**: CDC-captured by Debezium
- **Kafka Topic**: orders.events (OrderCreated, OrderStatusChanged, OrderCancelled)
- **Consumers**: fulfillment-service, notification-service, reconciliation-engine

## Key Features

1. **Distributed Saga**: Checkout flow creates orders atomically via Temporal
2. **Outbox Pattern**: Guaranteed event publishing via PostgreSQL + CDC
3. **Status History**: Immutable audit log of all status transitions
4. **Cancellation**: User-initiated cancellation (soft constraint: before packing)
5. **Idempotency**: Order creation keyed on checkout idempotency key (UNIQUE constraint)
6. **User Isolation**: Cannot view/cancel another user's orders

## Health & Monitoring

- **Liveness Probe**: `/actuator/health/live`
- **Readiness Probe**: `/actuator/health/ready` (depends on DB, Kafka readiness)
- **Metrics**: Prometheus at `/actuator/prometheus`
- **Tracing**: OTEL collector (sampling=1.0)

## Security

- **Auth**: JWT token validation (user_id must match order.user_id for reads/updates)
- **RBAC**: Customers can only view/modify own orders; admins have full access
- **Encryption**: Sensitive fields (delivery address) stored encrypted in DB

## Documentation Index

- [High-Level Design](diagrams/hld.md)
- [Low-Level Architecture](diagrams/lld.md)
- [Database Schema (ERD)](diagrams/erd.md)
- [Request/Response Flows](diagrams/flowchart.md)
- [Sequence Diagrams](diagrams/sequence.md)
- [REST API Contract](implementation/api.md)
- [Kafka Events](implementation/events.md)
- [Database Details](implementation/database.md)
- [Resilience & Retry Logic](implementation/resilience.md)
- [Deployment & Runbook](runbook.md)
