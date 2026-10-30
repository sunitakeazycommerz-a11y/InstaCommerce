# InstaCommerce Observability Platform

Authoritative documentation for platform observability, telemetry ingestion, profiling, distributed tracing, and automated alerting across the 30 microservices of the InstaCommerce monorepo.

---

## 1. Architectural Topology

```mermaid
flowchart TD
    subgraph Microservices["30 Production Microservices"]
        direction TB
        Java["20 Java / Spring Boot Services<br/>Micrometer + OTLP Tracing"]
        Go["8 Go Microservices<br/>go-shared/observability + Prometheus client"]
        Python["2 Python AI Services<br/>FastAPI Instrumentator + OTel SDK"]
    end

    subgraph ZeroCode["Kernel Level Zero-Code Profiling"]
        Beyla["eBPF Beyla DaemonSet<br/>grafana/beyla:1.4.0<br/>Ports: 80, 443, 8080-8099, 9090"]
    end

    subgraph TelemetryIngress["Telemetry Ingestion & Processing"]
        OTel["OpenTelemetry Collector<br/>Tail-Sampling Processor (6 Policies)<br/>Memory Limiter (75% limit, 20% spike)"]
        Prom["Prometheus Server<br/>Scrapes /metrics & /actuator/prometheus"]
    end

    subgraph StorageVisualization["Storage & Visualization"]
        Tempo["Grafana Tempo / Jaeger<br/>Distributed Traces"]
        Grafana["Grafana Dashboards<br/>Golden Signals & SLO Burn Rates"]
        CloudLogging["Google Cloud Logging<br/>Structured JSON Logs"]
        Alertmanager["Prometheus Alertmanager<br/>PagerDuty & Slack Pager"]
    end

    Java -->|OTLP gRPC :4317| OTel
    Go -->|OTLP HTTP :4318 / Prometheus Scrape| OTel
    Python -->|OTLP HTTP :4318| OTel
    ZeroCode -->|Kernel Socket RED Metrics & Traces| OTel

    Microservices -->|Direct /metrics scrape| Prom
    OTel -->|Sampled Traces| Tempo
    OTel -->|Remote Write Metrics| Prom
    Microservices -->|stdout / stderr| CloudLogging

    Prom --> Grafana
    Prom --> Alertmanager
    Tempo --> Grafana
```

---

## 2. Components

### 2.1 eBPF Network Profiler (`grafana/beyla`)
- **Manifest:** [`deploy/helm/templates/observability/ebpf-network-profiler.yaml`](../../deploy/helm/templates/observability/ebpf-network-profiler.yaml)
- **Deployment Mode:** Kubernetes DaemonSet in `monitoring` namespace.
- **Role:** Hooks Linux kernel network sockets (`k8s.pod.name`) via eBPF probes. Provides zero-overhead HTTP/gRPC latency, error rates, and connection duration without altering application binaries.
- **Target Monitored Ports:** `80, 443, 8080, 8081, 8090, 8091, 8092, 8093, 8094, 8095, 8096, 8097, 8098, 8099, 9090`.

### 2.2 OpenTelemetry Collector & Tail-Sampling Engine
- **Manifest:** [`deploy/helm/templates/observability/otel-collector.yaml`](../../deploy/helm/templates/observability/otel-collector.yaml)
- **Architecture:** Multi-policy `tail_sampling` processor evaluating trace trees after a 10-second decision window.
- **Deterministic Sampling Rules:**
  1. **HTTP 5xx Errors (`error-http-status-filter`):** 100% sample rate for `http.status_code` in $[500, 599]$.
  2. **Error Spans (`error-status-code-filter`):** 100% sample rate when status code is `ERROR`.
  3. **Slow Traces (`high-latency-filter`):** 100% sample rate when total span latency $> 2000$ ms.
  4. **VIP Customer Traffic (`vip-customer-filter`):** 100% sample rate for `customer_tier` in `["vip", "VIP"]`.
  5. **Low-Latency Search Traffic (`search-probabilistic-filter`):** 0.1% probabilistic sample rate for fast `search-service` calls.
  6. **Baseline Traffic (`default-probabilistic-filter`):** 5.0% probabilistic sample rate for all remaining healthy traffic.

---

## 3. Alerts & Service-Level Objectives

- **SLO Definitions:** [`../slos/service-slos.md`](../slos/service-slos.md)
- **Prometheus Alert Rules:** [`slo-alerts.md`](slo-alerts.md) and [`monitoring/prometheus-rules.yaml`](../../monitoring/prometheus-rules.yaml)
- **Grafana Dashboard JSON:** [`grafana-slo-dashboard.json`](grafana-slo-dashboard.json)
- **Incident Response Runbooks:** Located in [`../runbooks/`](../runbooks/) and per-service [`../services/*/runbook.md`](../services/).
