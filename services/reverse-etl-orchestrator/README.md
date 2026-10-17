# Reverse ETL Orchestrator Service (`reverse-etl-orchestrator`)

> Orchestrates data synchronization between analytical data stores (BigQuery / data mesh data products) and operational systems (PostgreSQL, Redis) across InstaCommerce.

---

## Overview

The `reverse-etl-orchestrator` manages reverse ETL pipelines, subscription configurations, schema transformations, and sink activations for the InstaCommerce data mesh platform.

### Key Capabilities
- **Subscription Management**: Tracks downstream services subscribing to curated data products.
- **Pipeline Orchestration**: Triggers and schedules synchronization batches from analytical datasets into operational caching and transactional tables.
- **Observability & Health**: Exposes Prometheus metrics and Kubernetes liveness/readiness endpoints.

---

## Architecture & Ports

| Endpoint | Default Address / Port | Description |
|---|---|---|
| **API** | `:8080` (or `API_ADDR`) | REST API for managing subscriptions and sinks |
| **Health** | `:8081` (or `HEALTH_ADDR`) | `/health`, `/health/live`, `/health/ready` |
| **Metrics** | `:9090` (or `METRICS_ADDR`) | `/metrics` Prometheus scrape endpoint |

---

## Local Development & Testing

```bash
cd services/reverse-etl-orchestrator
go test ./...
go build ./cmd/server
```

---

## CI & Deployment

- **CI Matrix**: Configured in `.github/workflows/ci.yml` under `all_go_services`.
- **Helm Deployment**: Configured in `deploy/helm/values.yaml` under `services.reverse-etl-orchestrator`.

