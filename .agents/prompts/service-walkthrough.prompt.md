---
name: service-walkthrough
description: End-to-end architectural and code walkthrough for an entire microservice in InstaCommerce.
---

# Microservice Architecture & Code Walkthrough Prompt

Conduct an end-to-end, multi-plane walkthrough of `services/$SERVICE_NAME`.

## Execution Steps:
1. **Service Inventory Check:**
   - Run `./scripts/lsp-walkthrough service $SERVICE_NAME` to inspect directory topology, source files, and exposed endpoints.
2. **Business Context & Domain Responsibilities:**
   - Define bounded context, business domain, and interactions with upstream/downstream services.
3. **Core APIs & Ingress Flow:**
   - Walk through controller / HTTP handler methods with line references and parameter schemas.
4. **Domain Model & Persistence Invariants:**
   - Walk through entities, state machines, Flyway database migrations, and tables.
5. **Event-Driven Architecture:**
   - Document transactional outbox schemas, Kafka topics consumed/produced, and Debezium CDC interactions.
6. **Resilience & Production Readiness:**
   - Circuit breakers, retry policies, health probes, and Prometheus metrics.
