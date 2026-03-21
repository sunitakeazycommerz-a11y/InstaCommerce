# Search Service - High-Level Deployment

```mermaid
graph TB
    subgraph "Edge Layer (Client Requests)"
        BFF["mobile-bff-service<br/>Port: 8097"]
        AGW["admin-gateway-service<br/>Port: 8099"]
    end

    subgraph "Read Plane - Kubernetes Pod"
        SC["search-service<br/>Port: 8086<br/>Replicas: 3<br/>CPU: 500m | Memory: 1Gi"]
        SC_CACHE["Caffeine Cache<br/>searchResults<br/>autocomplete<br/>trending"]
    end

    subgraph "Data & Infrastructure Layer"
        PG[("PostgreSQL<br/>search DB<br/>Pool: 50 conn<br/>Replicas: 1 Primary<br/>+ 1 Standby")]
        KF[("Kafka Cluster<br/>Brokers: 3<br/>Partitions: 3+<br/>Replication: 3")]
        DLT[("Dead-Letter Topics<br/>catalog.events.DLT<br/>inventory.events.DLT")]
    end

    subgraph "Kafka Topics"
        CATALOG["catalog.events<br/>Partitions: 6<br/>Retention: 7d<br/>Group: search-service"]
        INVENTORY["inventory.events<br/>Partitions: 3<br/>Retention: 7d<br/>Group: search-service"]
    end

    subgraph "Observability"
        OTEL["OpenTelemetry<br/>Collector<br/>:4318"]
        PROM["Prometheus<br/>scrape_interval: 15s"]
        GRFN["Grafana"]
    end

    BFF -->|REST<br/>GET /search<br/>GET /autocomplete<br/>GET /trending| SC
    AGW -->|Catalog Admin| PG

    SC --> SC_CACHE
    SC --> PG

    PG -->|CDC / Outbox Relay| KF

    CATALOG -->|Poll:<br/>100 msgs/sec max| SC
    INVENTORY -->|Poll:<br/>50 msgs/sec max| SC

    SC -.->|DLQ on failure<br/>3 retries exhausted| DLT
    DLT --> KF

    SC -->|OTLP Traces<br/>Metrics| OTEL
    SC -->|Prometheus<br/>Scrape| PROM
    PROM --> GRFN

    sc_health["Health Checks<br/>Liveness: /live<br/>Readiness: /ready"]
    sc_health -.-> SC

    style SC fill:#4CAF50,stroke:#2E7D32,color:#fff
    style PG fill:#2196F3,stroke:#1565C0,color:#fff
    style KF fill:#FF9800,stroke:#E65100,color:#fff
```

## Service Topology

- **Replicas:** 3 (spread across AZs in asia-south1)
- **CPU Request:** 500m | Limit: 1000m
- **Memory Request:** 1Gi | Limit: 2Gi
- **Storage:** PostgreSQL persistent volume, 50GB provisioned

## Network Policy

```yaml
Egress:
  - PostgreSQL (search DB, port 5432)
  - Kafka brokers (ports 9092, 9093)
  - OpenTelemetry collector (port 4318)

Ingress:
  - mobile-bff-service (port 8086)
  - admin-gateway-service (port 8086)
  - Istio sidecar mesh

DNS:
  - search-service.default.svc.cluster.local
```

## Service Mesh (Istio)

- **Protocol:** HTTP/2
- **mTLS:** STRICT (certificate-based)
- **Circuit Breaker:** Half-open after 50% errors
- **Retry Policy:** Max retries = 3, backoff exponential
- **Timeout:** 30s (per request)
- **Rate Limiting:** 10,000 RPS per replica

