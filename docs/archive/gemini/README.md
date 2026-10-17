# InstaCommerce System Documentation Portal

Welcome to the comprehensive, production-grade technical documentation for the **InstaCommerce** Q-Commerce monorepo. This directory houses deep-dive architecture, service design, operational runbooks, and implementation guides for all 30 microservices across Java, Go, and Python.

---

## 📚 Documentation Index

| Document | Topic & Scope | Focus & Architecture |
|---|---|---|
| 🏛️ [**Architecture Overview**](architecture-overview.md) | High-Level Design (HLD), Six Architectural Planes, C4 Level 1 & 2 diagrams, communication patterns, and NFRs. | Core Platform Topologies |
| 📋 [**Service Catalog**](service-catalog.md) | Comprehensive inventory of all 30 microservices + `go-shared`, detailing ports, databases, endpoints, entities, and status across 9 clusters. | Microservices Specification |
| ⚡ [**Checkout Saga & Temporal Workflows**](checkout-saga.md) | Deep dive into `checkout-orchestrator-service`: Temporal workflow engine, 8-step execution flow, backward compensations, and workflow-stable idempotency keys. | Distributed Sagas & Orchestration |
| 🔄 [**Order Lifecycle & State Machine**](order-lifecycle.md) | The lifecycle of an order: JPA aggregate root, 8-stage state machine transitions (`OrderStateMachine`), outbox events, fulfillment progress, and user cancellation. | Domain State Machine |
| 💳 [**Payment System & Ledger**](payment-system.md) | Two-phase payment capture (Authorize → Capture on Delivery), the Three-Phase Pattern (PSP outside DB TX), double-entry ledger bookkeeping, and circuit breakers. | Payments & Financial Accounting |
| 📡 [**Data Flow & Event Architecture**](data-flow-and-events.md) | Canonical `EventEnvelope` spec, 18 Kafka topics, transactional outbox pattern, Debezium CDC pipeline to BigQuery, and schema evolution. | Event Streaming & CDC |
| 🔒 [**Security Architecture**](security-architecture.md) | Defense-in-depth, RS256 JWT auth flow, internal mTLS via Istio, `InternalServiceAuthFilter` (timing-attack resistant), RBAC, webhooks, and GCP Secret Manager. | Security & Supply Chain |
| 🤖 [**ML & AI Platform**](ml-and-ai-platform.md) | Dual-layer Feast Feature Store (Redis + BigQuery), ONNX Runtime serving (`ai-inference-service`) with 3-tier fallback & shadow mode, and LangGraph orchestrator. | AI, MLOps & Real-Time Inference |
| 🐹 [**Go Services Architecture**](go-services-architecture.md) | The high-throughput async plane: `go-shared` library, CDC consumer, outbox relay, location telemetry (Uber H3), and dispatch optimizer. | Low-Latency Go Microservices |
| 🗄️ [**Database Schema Guide**](database-schema-guide.md) | Database-per-service isolation (17 PostgreSQL DBs), Flyway migrations, ER diagrams, outbox/shedlock tables, indexes, and connection tuning. | Persistence & Data Dictionary |
| 🚀 [**Deployment & Infrastructure Guide**](deployment-guide.md) | Local environment (`docker-compose`), GitHub Actions CI/CD matrix builds, Helm charts (`values-dev` / `values-prod`), ArgoCD GitOps, and Terraform GCP modules. | Cloud & Kubernetes Operations |
| 📊 [**Monitoring & Observability**](monitoring-and-observability.md) | Prometheus alerting rules (`instacommerce-slos`), Grafana dashboard inventory, health check standards, SLI/SLO targets, and on-call routing. | SRE Observability & Telemetry |
| 🛠️ [**Developer Onboarding & Workflow**](development-guide.md) | Step-by-step local setup, building and testing Java/Go/Python services, adding new contracts/migrations, coding standards, and PR workflows. | Engineering Workflow |

---

## 🏗️ Quick Platform Snapshot

- **Monorepo Scale:** ~2.73M lines of code across 1,090 Java files, 5,185 Go files, and 39 Python files.
- **Java Fleet:** 20 microservices built on Java 21, Spring Boot 4.0.0, Spring Cloud GCP 6.0.0, and Jackson 3.1.0.
- **Go Fleet:** 8 services + `go-shared` utilizing Go 1.22 for zero-allocation async event streaming, CDC data warehousing, and rider telemetry.
- **Python Fleet:** 2 services on Python 3.13 / FastAPI powering LangGraph agentic reasoning and sub-20ms ONNX model inference.
- **Data & ML:** dbt + Airflow + BigQuery lakehouse with Feast feature store feeding real-time models.
- **Orchestration:** Temporal.io coordinating mission-critical distributed sagas with compensation guarantees.
- **Deployment:** GitOps continuous delivery via ArgoCD syncing Helm charts to Google Kubernetes Engine (GKE).
