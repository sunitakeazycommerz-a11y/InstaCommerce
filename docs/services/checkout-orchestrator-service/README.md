# Checkout Orchestrator Service

## Overview

The checkout-orchestrator-service is the orchestration layer for the critical order checkout saga in InstaCommerce. It implements a long-running Temporal workflow that coordinates multiple distributed systems (cart, inventory, payment, pricing, order) to ensure atomic order creation.

**Service Ownership**: Platform Team - Money Path Tier 1
**Language**: Java 21 / Spring Boot 4.0
**Default Port**: 8089
**Status**: Tier 1 Critical (Tier 1 of money-path cluster)

## SLOs & Availability

- **Availability SLO**: 99.95% (45 minutes downtime/month)
- **P99 Latency**: < 2.5s per checkout operation
- **Error Rate**: < 0.1%
- **Max Throughput**: 10,000 checkouts/minute (horizontal scaling at 3 replicas)

## Key Responsibilities

1. **Checkout Workflow Orchestration**: Temporal-based saga for atomicity across services
2. **Idempotency**: DB-backed idempotency key tracking (30-min TTL) for durable duplicate detection
3. **Status Querying**: Expose workflow status via gRPC/REST queries
4. **Circuit Breaker Resilience**: Resilience4j protection against cascading failures

## Deployment

### GKE Deployment
- **Namespace**: money-path
- **Replicas**: 3 (HA)
- **CPU Request/Limit**: 500m / 1500m
- **Memory Request/Limit**: 512Mi / 1Gi

### Database
- **Name**: `checkout` (PostgreSQL)
- **Driver**: jdbc:postgresql
- **Migrations**: Flyway (V1, V2)

## Integrations

### Synchronous Clients (Circuit Breakers)
| Service | Endpoint | Timeout | Purpose |
|---------|----------|---------|---------|
| cart-service | http://cart-service:8084 | 10s | Cart validation |
| inventory-service | http://inventory-service:8083 | 10s | Stock availability |
| payment-service | http://payment-service:8080 | 10s | Payment authorization |
| pricing-service | http://pricing-service:8087 | 10s | Final pricing calculation |
| order-service | http://order-service:8080 | 10s | Order creation |

### Async Dependencies
- **Temporal Server**: localhost:7233 (namespace: instacommerce)

## Key Features

1. **Idempotency**: Checkout requests with same Idempotency-Key return cached response
2. **Workflow Status**: Query in-flight checkout status via `GET /checkout/{workflowId}/status`
3. **Distributed Saga**: Multi-step checkout with automatic compensation on failure
4. **User Isolation**: Cannot checkout for another user (principal validation)

## Health & Monitoring

- **Liveness Probe**: `/actuator/health/live`
- **Readiness Probe**: `/actuator/health/ready` (depends on DB connectivity)
- **Metrics**: Prometheus at `/actuator/prometheus`
- **Tracing**: OTEL collector (sampling=1.0)

## Security

- **Auth**: JWT token validation (configurable issuer)
- **RBAC**: User principal must match checkout user_id
- **Secrets**: All credentials stored in Google Secret Manager

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
