# Wave 40 Phase 3: Grafana SLO Observability Implementation Guide

**Status**: 📋 PREP → 🚀 EXECUTION (Week 3: March 25-31)
**Owner**: Platform & Reliability Team
**Timeline**: 7 days
**Target**: 31 Grafana dashboards + 50+ alert rules deployed

---

## Executive Summary

Wave 40 Phase 3 delivers production-grade observability for all 28 InstaCommerce services through:

- **31 Grafana Dashboards**: Service-specific health dashboards (28) + platform overviews (3)
- **50+ Prometheus Alert Rules**: Multi-window burn-rate detection (fast/medium/slow) + custom service rules
- **Loki Log Aggregation**: Structured logging pipeline with service-level filtering
- **Error Budget Tracking**: Real-time SLO compliance with monthly reset mechanism
- **On-Call Integration**: AlertManager → PagerDuty/Slack with escalation workflows

### Key Metrics by Phase

| Phase | Objective | Deliverables | Owner | ETA |
|-------|-----------|--------------|-------|-----|
| **3A** | Infrastructure setup | Prometheus config, Grafana provisioning | Platform | Mar 27 |
| **3B** | Dashboard development | 31 dashboards, panel templates | Architects | Mar 29 |
| **3C** | Alert rules deployment | 50+ rules, silence policies | SRE | Mar 30 |
| **3D** | Team enablement | Training docs, runbooks, on-call handoff | Leadership | Mar 31 |

**Success Criteria**:
- ✅ 31 dashboards deployed and team-accessible
- ✅ 50+ alert rules in PROD (AlertManager)
- ✅ <30s alert delivery to Slack/PagerDuty
- ✅ Team trained on dashboard interpretation + alert response
- ✅ Error budget policy documented + enforced

---

## Part 1: Observability Architecture

### 1.1 Prometheus Stack Overview

#### Scrape Configuration Strategy

**Scrape Targets**: 4 tiers

```yaml
# prometheus/prometheus.yml (MASTER CONFIG)
global:
  scrape_interval: 15s
  scrape_timeout: 10s
  evaluation_interval: 15s
  external_labels:
    cluster: production
    environment: prod
    region: multi-region

# Tier 1: Kubernetes Metrics (kubelet + cAdvisor)
scrape_configs:
  - job_name: 'kubernetes-nodes'
    scheme: https
    tls_config:
      ca_file: /var/run/secrets/kubernetes.io/serviceaccount/ca.crt
    bearer_token_file: /var/run/secrets/kubernetes.io/serviceaccount/token
    kubernetes_sd_configs:
      - role: node
    relabel_configs:
      - action: labelmap
        regex: __meta_kubernetes_node_label_(.+)
      - target_label: __address__
        replacement: kubernetes.default.svc:443
      - source_labels: [__meta_kubernetes_node_name]
        regex: (.+)
        target_label: __metrics_path__
        replacement: /api/v1/nodes/${1}/proxy/metrics
    metric_relabel_configs:
      - source_labels: [__name__]
        regex: 'container_(cpu_usage|memory_usage|fs_usage|network_).*'
        action: keep

  # Tier 2: Service Endpoints (Java/Go exporters)
  - job_name: 'service-metrics'
    kubernetes_sd_configs:
      - role: pod
        namespaces:
          names: [production, staging]
    relabel_configs:
      - source_labels: [__meta_kubernetes_pod_label_monitoring]
        action: keep
        regex: 'true'
      - source_labels: [__meta_kubernetes_pod_container_port_number]
        regex: '9090|8081|9091'
        action: keep
      - action: labelmap
        regex: __meta_kubernetes_pod_label_(.+)
      - source_labels: [__meta_kubernetes_namespace]
        target_label: namespace
      - source_labels: [__meta_kubernetes_pod_name]
        target_label: pod
      - source_labels: [__meta_kubernetes_pod_container_name]
        target_label: container
    scrape_interval: 15s
    scrape_timeout: 10s

  # Tier 3: Istio Service Mesh
  - job_name: 'istio-mesh'
    kubernetes_sd_configs:
      - role: endpoint
        namespaces:
          names: [istio-system, production]
    relabel_configs:
      - source_labels: [__meta_kubernetes_service_name, __meta_kubernetes_endpoint_port_name]
        action: keep
        regex: prometheus-.*; 9090
      - action: labelmap
        regex: __meta_kubernetes_service_label_(.+)

  # Tier 4: External Services (managed databases, queues)
  - job_name: 'external-services'
    static_configs:
      - targets: ['postgres-managed.example.com:9187']
        labels:
          service: postgresql
          environment: prod
      - targets: ['redis-managed.example.com:9121']
        labels:
          service: redis
          environment: prod
      - targets: ['kafka-managed.example.com:9308']
        labels:
          service: kafka
          environment: prod
    scheme: https
    tls_config:
      insecure_skip_verify: false

alerting:
  alertmanagers:
    - static_configs:
        - targets: ['alertmanager:9093']

# Remote storage for long-term archival
remote_write:
  - url: "https://prometheus-long-term.example.com:19291/api/v1/write"
    queue_config:
      capacity: 100000
      max_shards: 200
      max_samples_per_send: 10000
      batch_send_wait: 5s
    metadata_config:
      send: true
      send_interval: 1m
```

#### Retention Strategy

| Tier | Local Retention | Remote Retention | Use Case |
|------|-----------------|------------------|----------|
| Hot (metrics) | 15 days | 1 year | Active dashboards, alerts |
| Warm (archive) | N/A | 7 years | Compliance, audit, historical trends |
| Cold (cold storage) | N/A | Glacier | Regulatory hold, litigation support |

```yaml
# prometheus/prometheus-deployment.yaml
containers:
  - name: prometheus
    image: prom/prometheus:v2.50.0
    args:
      - '--config.file=/etc/prometheus/prometheus.yml'
      - '--storage.tsdb.path=/prometheus'
      - '--storage.tsdb.retention.time=15d'  # Hot retention
      - '--storage.tsdb.retention.size=50GB'  # Max disk before rollover
      - '--query.timeout=2m'
      - '--query.max-concurrency=20'
    resources:
      requests:
        memory: 8Gi
        cpu: 4000m
      limits:
        memory: 16Gi
        cpu: 8000m
    volumeMounts:
      - name: prometheus-storage
        mountPath: /prometheus
volumes:
  - name: prometheus-storage
    persistentVolumeClaim:
      claimName: prometheus-pvc-prod
---
apiVersion: v1
kind: PersistentVolumeClaim
metadata:
  name: prometheus-pvc-prod
spec:
  accessModes:
    - ReadWriteOnce
  resources:
    requests:
      storage: 100Gi
  storageClassName: fast-ssd
```

### 1.2 Grafana Dashboard Strategy

#### Multi-Tier Dashboard Architecture

**Tier 1: Executive Overview** (Role: VP/Director)
- Platform health score (0-100)
- SLO compliance status (% by service)
- Revenue impact of outages
- Top 5 burning services

**Tier 2: Platform Operations** (Role: SRE/Ops)
- Cross-service dependency map
- Error rate heatmap by service
- Latency percentile rankings
- Infrastructure utilization

**Tier 3: Service Teams** (Role: Dev Lead)
- Service-specific health (28 dashboards)
- Business metrics + technical metrics
- Error categories + stack traces
- Dependency status

**Tier 4: On-Call** (Role: On-call Engineer)
- Alert status feed
- Incident timeline
- Escalation path
- Runbook links

```yaml
# grafana/provisioning/dashboards/dashboard-config.yaml
apiVersion: 1
providers:
  - name: 'auto-provisioning'
    orgId: 1
    type: file
    disableDeletion: false
    editable: true
    options:
      path: /etc/grafana/provisioning/dashboards

# Datasources
  - name: 'prometheus-prod'
    orgId: 1
    type: prometheus
    url: http://prometheus:9090
    access: proxy
    isDefault: true
    editable: true

  - name: 'loki-prod'
    orgId: 1
    type: loki
    url: http://loki:3100
    access: proxy
    editable: true

  - name: 'postgres-metrics'
    orgId: 1
    type: postgres
    url: postgres-readonly.prod.svc.cluster.local:5432
    database: observability
    user: grafana_ro
    secureJsonData:
      password: ${DB_PASSWORD}
```

### 1.3 AlertManager Configuration

```yaml
# alertmanager/alertmanager.yml
global:
  resolve_timeout: 5m
  slack_api_url: '${SLACK_WEBHOOK_URL}'
  pagerduty_url: 'https://events.pagerduty.com/v2/enqueue'

templates:
  - '/etc/alertmanager/templates/*.tmpl'

route:
  receiver: 'default'
  group_by: ['alertname', 'cluster', 'service']
  group_wait: 10s
  group_interval: 10s
  repeat_interval: 4h

  routes:
    # Tier 1: Critical (immediate page)
    - match:
        severity: critical
        slo_tier: tier1
      receiver: 'pd-immediate'
      group_wait: 5s
      repeat_interval: 5m
      continue: true

    # Tier 2: High (page after 5 min)
    - match:
        severity: high
        slo_tier: tier2
      receiver: 'pd-high'
      group_wait: 5m
      repeat_interval: 15m

    # Tier 3: Medium (Slack only)
    - match:
        severity: medium
      receiver: 'slack-incidents'
      group_wait: 10s
      repeat_interval: 30m

    # Non-actionable (summary only)
    - match:
        severity: low
      receiver: 'slack-observability'
      group_wait: 1h
      repeat_interval: 24h

inhibit_rules:
  # Don't alert on high if critical is already firing
  - source_match:
      severity: 'critical'
    target_match:
      severity: 'high'
    equal: ['alertname', 'service', 'cluster']

  # Don't alert on medium if high is firing
  - source_match:
      severity: 'high'
    target_match:
      severity: 'medium'
    equal: ['alertname', 'service', 'cluster']

receivers:
  - name: 'default'
    slack_configs:
      - channel: '#incidents'
        title: '{{ .GroupLabels.alertname }}'
        text: '{{ range .Alerts }}{{ .Annotations.description }}{{ end }}'

  - name: 'pd-immediate'
    pagerduty_configs:
      - service_key: '${PD_SERVICE_KEY_TIER1}'
        description: '{{ .GroupLabels.alertname }} - {{ .CommonAnnotations.summary }}'
        client: 'Prometheus'
        client_url: 'https://prometheus.prod.example.com'
        details:
          firing: '{{ template "pagerduty.default.instances" .Alerts.Firing }}'

  - name: 'pd-high'
    pagerduty_configs:
      - service_key: '${PD_SERVICE_KEY_TIER2}'
        description: '{{ .GroupLabels.alertname }}'
        client: 'Prometheus'
        client_url: 'https://prometheus.prod.example.com'

  - name: 'slack-incidents'
    slack_configs:
      - channel: '#sre-incidents'
        send_resolved: true

  - name: 'slack-observability'
    slack_configs:
      - channel: '#observability'
        send_resolved: false
```

### 1.4 Loki Logs Aggregation

```yaml
# loki/loki-config.yaml
auth_enabled: false

ingester:
  chunk_idle_period: 3m
  chunk_retain_period: 1m
  max_chunk_age: 2h
  chunk_encoding: snappy
  chunk_size_target: 262144
  max_streams_per_user: 10000
  lifecycler:
    ring:
      kvstore:
        store: inmemory
      replication_factor: 3

limits_config:
  enforce_metric_name: false
  reject_old_samples: true
  reject_old_samples_max_age: 168h
  ingestion_rate_mb: 256
  ingestion_burst_size_mb: 512
  max_cache_freshness_per_query: 10m

schema_config:
  configs:
    - from: 2024-01-01
      store: boltdb-shipper
      object_store: s3
      schema: v12
      index:
        prefix: loki_index_
        period: 24h

server:
  http_listen_port: 3100
  grpc_listen_port: 9096
  log_level: info

storage_config:
  boltdb_shipper:
    active_index_directory: /loki/index
    shared_store: s3
    cache_location: /loki/boltdb-cache
  s3:
    s3: s3://loki-bucket/loki
    endpoint: s3.amazonaws.com
    region: us-east-1
    insecure: false

chunk_store_config:
  max_look_back_period: 0s

table_manager:
  poll_interval: 10m
  retention_deletes_enabled: true
  retention_period: 2160h  # 90 days

# Promtail agent config (runs on each node)
---
apiVersion: v1
kind: ConfigMap
metadata:
  name: promtail-config
  namespace: loki
data:
  promtail.yaml: |
    clients:
      - url: http://loki:3100/loki/api/v1/push
    positions:
      filename: /tmp/positions.yaml
    scrape_configs:
      - job_name: kubernetes-pods
        kubernetes_sd_configs:
          - role: pod
        relabel_configs:
          - action: labelmap
            regex: __meta_kubernetes_pod_label_(.+)
          - source_labels: [__meta_kubernetes_namespace]
            target_label: namespace
          - source_labels: [__meta_kubernetes_pod_name]
            target_label: pod
          - source_labels: [__meta_kubernetes_pod_container_name]
            target_label: container
        pipeline_stages:
          - json:
              expressions:
                timestamp: timestamp
                level: level
                service: service
                trace_id: trace_id
          - timestamp:
              source: timestamp
              format: Unix
          - labels:
              level:
              service:
              trace_id:
```

---

## Part 2: SLO Definitions (Wave 38 Reference)

### 2.1 All 28 Services SLO Targets

#### Payment Tier (Highest SLO)

| Service | Availability | Latency (p99) | Error Rate | Freshness | Notes |
|---------|-------------|---------------|-----------|-----------|-------|
| Payment | 99.95% | <300ms | <0.05% | Real-time | PCI-DSS compliance, revenue-critical |
| Wallet | 99.90% | <250ms | <0.1% | <1s | Financial accuracy required |
| Checkout | 99.90% | <500ms | <0.1% | <5s | End-of-funnel conversion |

#### Order Tier (High SLO)

| Service | Availability | Latency (p99) | Error Rate | Freshness | Notes |
|---------|-------------|---------------|-----------|-----------|-------|
| Order | 99.90% | <500ms | <0.1% | <1s | Order creation/tracking |
| Fulfillment | 99.50% | <2000ms | <0.5% | 95% ±15min ETA | Dark store ops |
| Warehouse | 99.50% | <1000ms | <0.5% | <5min inventory | Stock accuracy |
| Inventory | 99.70% | <300ms | <0.15% | <2s | Real-time stock |

#### Search & Discovery (Medium-High SLO)

| Service | Availability | Latency (p99) | Error Rate | Freshness | Notes |
|---------|-------------|---------------|-----------|-----------|-------|
| Search | 99.90% | <200ms | <0.05% | <5min | ElasticSearch latency budget |
| Catalog | 99.70% | <500ms | <0.1% | <2h | Product data |
| Pricing | 99.90% | <250ms | <0.05% | <1min | Dynamic pricing accuracy |

#### Cart & Rider (Medium SLO)

| Service | Availability | Latency (p99) | Error Rate | Freshness | Notes |
|---------|-------------|---------------|-----------|-----------|-------|
| Cart | 99.70% | <300ms | <0.1% | <1s | Session persistence |
| Rider | 99.50% | <1000ms | <0.5% | <30s | Real-time delivery tracking |
| Routing | 99.90% | <400ms | <0.05% | <2min | ETA optimization |

#### Platform & Infrastructure (Medium SLO)

| Service | Availability | Latency (p99) | Error Rate | Freshness | Notes |
|---------|-------------|---------------|-----------|-----------|-------|
| Notification | 99.70% | <500ms | <0.2% | <2min | Eventual consistency OK |
| Admin-Gateway | 99.50% | <800ms | <0.2% | N/A | Internal use |
| Config-FeatureFlag | 99.95% | <50ms | <0.01% | <30s | Cache hit required |
| Identity | 99.90% | <100ms | <0.05% | <1s | Auth critical path |

#### Analytics & Background (Lower SLO)

| Service | Availability | Latency (p99) | Error Rate | Freshness | Notes |
|---------|-------------|---------------|-----------|-----------|-------|
| Dispatch | 99.00% | <5000ms | <1.0% | <10min | Batch operations |
| Stream-Processor | 99.00% | <10000ms | <2.0% | <5min | Eventual consistency |
| CDC-Consumer | 99.00% | <30000ms | <2.0% | <1h | Audit trail only |
| Location-Ingestion | 99.00% | <2000ms | <1.0% | <10min | Non-critical enrichment |
| Reconciliation | 98.00% | <60000ms | <5.0% | Daily batch | Audit trail, low-freq |
| Mobile-BFF | 99.70% | <600ms | <0.2% | <5s | Aggregation service |
| CTO-Service | 99.50% | <1000ms | <0.5% | <5min | Internal compute |
| Audit-Service | 98.50% | <5000ms | <1.0% | <1h | Compliance trail |
| Relay-Service | 99.50% | <500ms | <0.5% | <1min | Event forwarding |
| AI-Recommendation | 99.00% | <2000ms | <1.0% | <1h | Batch recommendations |
| AI-Fraud-Detection | 99.50% | <1500ms | <0.5% | <5min | Real-time ML scoring |

### 2.2 Error Budget Calculations

```
Error Budget = (1 - SLO) × Total Seconds in Month

Example (Payment Service - 99.95% SLO):
  Allowed downtime = (1 - 0.9995) × 2,592,000s = 1,296s ≈ 21.6 minutes/month
  Budget alerts at:
    - 50% consumed: 10.8 min down (pre-incident)
    - 75% consumed: 16.2 min down (medium severity)
    - 95% consumed: 20.5 min down (high severity)
    - 100% consumed: SLO violated (post-incident analysis)

Payment Service Monthly Budget:
  99.95% SLO = 21.6 min downtime/month
  99.90% SLO = 43.2 min downtime/month
  99.70% SLO = 129.6 min downtime/month
  99.50% SLO = 216 min downtime/month
  99.00% SLO = 432 min downtime/month
  98.00% SLO = 864 min downtime/month
```

### 2.3 Burn Rate Windows

**Multi-Window Alert Strategy** (prevents false positives + catches real issues)

```yaml
# Burn rate definition: Rate at which error budget is consumed

# Fast Burn (5-minute window) - immediate action
# Alert if consuming >100% budget in 5 min = spending monthly budget in ~30 hours
# Severity: CRITICAL → PagerDuty immediate page

# Medium Burn (30-minute window) - investigate within 5 min
# Alert if consuming >10% budget in 30 min = on pace to exceed in ~300 hours
# Severity: HIGH → Page after 5 min

# Slow Burn (6-hour window) - track trends
# Alert if consuming >1% budget in 6 hours = on pace to exceed in ~300 days
# Severity: MEDIUM → Slack notification only
```

---

## Part 3: Dashboard Specifications (31 Dashboards)

### 3.1 Service Health Dashboards (28 × Services)

**Template: Service-Specific Dashboard (payment-service example)**

```json
{
  "dashboard": {
    "title": "Payment Service - SLO Health",
    "uid": "payment-slo-health",
    "timezone": "UTC",
    "version": 1,
    "panels": [
      {
        "id": 1,
        "title": "SLO Status (99.95%)",
        "type": "stat",
        "targets": [
          {
            "expr": "100 * (1 - (increase(payment_errors_total[30d]) / increase(payment_requests_total[30d])))",
            "refId": "A",
            "legendFormat": "Availability %"
          }
        ],
        "thresholds": {
          "mode": "absolute",
          "steps": [
            {"color": "red", "value": 0},
            {"color": "orange", "value": 99.7},
            {"color": "green", "value": 99.95}
          ]
        },
        "unit": "percent",
        "decimals": 2
      },
      {
        "id": 2,
        "title": "Error Rate (5m burn)",
        "type": "graph",
        "targets": [
          {
            "expr": "rate(payment_errors_total[5m]) / rate(payment_requests_total[5m])",
            "refId": "A"
          }
        ],
        "alert": {
          "name": "payment_fast_burn_5m",
          "condition": "A",
          "evaluator": {"type": "gt", "params": [0.0005]},
          "frequency": "1m",
          "handler": 1,
          "message": "Payment fast burn: >0.05% error rate in 5m"
        }
      },
      {
        "id": 3,
        "title": "Latency p99",
        "type": "graph",
        "targets": [
          {
            "expr": "histogram_quantile(0.99, rate(payment_request_duration_seconds_bucket[5m]))",
            "refId": "A",
            "legendFormat": "{{ le }}"
          }
        ],
        "thresholds": [
          {"value": 0.3, "color": "red", "fill": true, "line": true}
        ],
        "unit": "s"
      },
      {
        "id": 4,
        "title": "Error Categories",
        "type": "piechart",
        "targets": [
          {
            "expr": "topk(5, increase(payment_errors_total{error_type=~\".+\"}[5m]))",
            "refId": "A"
          }
        ]
      },
      {
        "id": 5,
        "title": "Dependency Status",
        "type": "status-panel",
        "targets": [
          {
            "expr": "up{job='wallet-service'}",
            "refId": "Wallet"
          },
          {
            "expr": "up{job='identity-service'}",
            "refId": "Identity"
          },
          {
            "expr": "up{job='postgres-managed'}",
            "refId": "PostgreSQL"
          },
          {
            "expr": "up{job='kafka-managed'}",
            "refId": "Kafka"
          }
        ]
      },
      {
        "id": 6,
        "title": "Infrastructure Utilization",
        "type": "graph",
        "targets": [
          {
            "expr": "rate(container_cpu_usage_seconds_total{pod=~'payment-.*'}[5m]) * 100",
            "refId": "A",
            "legendFormat": "CPU %"
          },
          {
            "expr": "container_memory_usage_bytes{pod=~'payment-.*'} / 1024 / 1024 / 1024",
            "refId": "B",
            "legendFormat": "Memory GB"
          }
        ]
      },
      {
        "id": 7,
        "title": "Requests/sec (5m avg)",
        "type": "stat",
        "targets": [
          {
            "expr": "rate(payment_requests_total[5m])",
            "refId": "A"
          }
        ],
        "unit": "rps"
      },
      {
        "id": 8,
        "title": "Recent Errors (Loki)",
        "type": "logs",
        "targets": [
          {
            "expr": "{service=\"payment\", level=\"ERROR\"}",
            "refId": "A"
          }
        ]
      }
    ]
  }
}
```

**Service-Specific Customizations** (by tier):

| Service | Additional Panels | Custom Metrics | Alerts |
|---------|------------------|-----------------|--------|
| Payment | PCI compliance score, transaction volume, fraud score distribution | payment_fraud_rate, payment_pci_dss_violations | SLO + custom: fraud spike, compliance breach |
| Checkout | Funnel drop-off, cart abandonment | checkout_funnel_step, checkout_cart_abandonment | SLO + funnel regression |
| Search | Query latency percentiles, cache hit rate | search_query_latency, search_cache_hit_ratio | SLO + cache degradation |
| Fulfillment | ETA accuracy, pick/pack times, dark-store utilization | fulfillment_eta_accuracy, fulfillment_pick_time | SLO + ETA accuracy breach |
| Warehouse | Inventory accuracy, capacity utilization, stockout rate | warehouse_accuracy, warehouse_capacity, warehouse_stockout | SLO + accuracy degradation |
| Rider | Real-time location updates, delivery time | rider_delivery_time, rider_location_update_lag | SLO + location lag |
| Reconciliation | Mismatch rate, fix time, audit trail completeness | reconciliation_mismatch_rate, audit_trail_gap | SLO + audit trail gap |

### 3.2 Platform Overview Dashboards (3)

#### Dashboard 1: Executive Health Scorecard

```json
{
  "dashboard": {
    "title": "Platform Executive Scorecard",
    "uid": "exec-scorecard",
    "panels": [
      {
        "id": 1,
        "title": "Platform Health Score (0-100)",
        "type": "gauge",
        "targets": [
          {
            "expr": "100 - ((100 - avg(slo_compliance{tier='tier1'})) * 0.5 + (100 - avg(slo_compliance{tier='tier2'})) * 0.3 + (100 - avg(slo_compliance{tier='tier3'})) * 0.2)",
            "refId": "A"
          }
        ],
        "unit": "short",
        "thresholds": {
          "steps": [
            {"color": "red", "value": 0},
            {"color": "orange", "value": 90},
            {"color": "green", "value": 95}
          ]
        }
      },
      {
        "id": 2,
        "title": "SLO Compliance by Tier",
        "type": "table",
        "targets": [
          {
            "expr": "slo_compliance",
            "refId": "A",
            "format": "table"
          }
        ]
      },
      {
        "id": 3,
        "title": "Revenue Impact (24h)",
        "type": "stat",
        "targets": [
          {
            "expr": "sum(revenue_lost_due_to_errors)",
            "refId": "A"
          }
        ],
        "unit": "currencyUSD"
      },
      {
        "id": 4,
        "title": "Top 5 Burning Services",
        "type": "table",
        "targets": [
          {
            "expr": "topk(5, (1 - slo_compliance) / (1 - slo_target))",
            "refId": "A"
          }
        ]
      },
      {
        "id": 5,
        "title": "Error Budget Status (28 services)",
        "type": "heatmap",
        "targets": [
          {
            "expr": "((1 - slo_compliance) / (1 - slo_target)) * 100",
            "refId": "A",
            "legendFormat": "{{ service }}"
          }
        ],
        "thresholds": [
          {"value": 0, "color": "green"},
          {"value": 50, "color": "yellow"},
          {"value": 100, "color": "red"}
        ]
      }
    ]
  }
}
```

#### Dashboard 2: Financial SLO Dashboard

```json
{
  "dashboard": {
    "title": "Financial Impact & Error Budget",
    "uid": "financial-slo",
    "panels": [
      {
        "id": 1,
        "title": "Monthly Error Budget Consumption (%)",
        "type": "bargauge",
        "targets": [
          {
            "expr": "((increase(errors_total[30d])) / (increase(requests_total[30d])) / (1 - slo_target)) * 100",
            "refId": "A",
            "legendFormat": "{{ service }}"
          }
        ]
      },
      {
        "id": 2,
        "title": "Revenue Lost by Service (YTD)",
        "type": "table",
        "targets": [
          {
            "expr": "sum by (service) (revenue_lost_by_service)",
            "refId": "A"
          }
        ]
      },
      {
        "id": 3,
        "title": "Cost of Downtime vs. Investment",
        "type": "graph",
        "targets": [
          {
            "expr": "cumsum(downtime_minutes) * cost_per_minute_lost",
            "refId": "Cost"
          },
          {
            "expr": "cumsum(reliability_investment)",
            "refId": "Investment"
          }
        ]
      },
      {
        "id": 4,
        "title": "SLO Reset Timeline (Monthly)",
        "type": "timeline",
        "targets": [
          {
            "expr": "time() % 2592000",
            "refId": "A"
          }
        ]
      }
    ]
  }
}
```

#### Dashboard 3: Operational Real-Time Dashboard

```json
{
  "dashboard": {
    "title": "Operational Real-Time Status",
    "uid": "ops-realtime",
    "refresh": "5s",
    "panels": [
      {
        "id": 1,
        "title": "Active Incidents",
        "type": "alertlist",
        "targets": [],
        "options": {
          "showOptions": "current",
          "maxItems": 10
        }
      },
      {
        "id": 2,
        "title": "Cross-Service Dependency Map",
        "type": "nodeGraph",
        "targets": [
          {
            "expr": "avg by (service_from, service_to) (rate(requests_total{le='+Inf'}[5m]))",
            "refId": "A"
          }
        ]
      },
      {
        "id": 3,
        "title": "Error Rate Heatmap (28 services, 1h)",
        "type": "heatmap",
        "targets": [
          {
            "expr": "rate(errors_total[5m]) / rate(requests_total[5m])",
            "refId": "A",
            "legendFormat": "{{ service }}"
          }
        ]
      },
      {
        "id": 4,
        "title": "Infrastructure Hotspots",
        "type": "table",
        "targets": [
          {
            "expr": "topk(10, rate(container_throttled_cycles_total[5m]) or rate(container_last_seen[5m]))",
            "refId": "A"
          }
        ]
      }
    ]
  }
}
```

---

## Part 4: Alert Rules (50+)

### 4.1 Multi-Window Burn-Rate Rules (Base Template)

```yaml
# prometheus/rules/slo_burn_rate_rules.yaml
groups:
  - name: slo_burn_rates
    interval: 1m
    rules:
      # ============================================
      # TIER 1 SERVICES (Payment, Checkout, Wallet)
      # ============================================

      # Payment Service: 99.95% SLO = 21.6 min/month downtime
      - alert: PaymentFastBurn5m
        expr: |
          (
            1 - (
              increase(payment_errors_total[5m]) +
              increase(payment_timeouts_total[5m]) +
              increase(payment_failures_total[5m])
            ) / increase(payment_requests_total[5m])
          ) < 0.9995 - (0.9995 - 0.9970) * 100  # 100% budget burn in 5m
        for: 1m
        labels:
          severity: critical
          slo_tier: tier1
          service: payment
          burn_window: fast
        annotations:
          summary: "Payment service fast burn (5m)"
          description: "Payment consuming 100% monthly error budget in 5 minutes. Current availability: {{ $value | humanizePercentage }}"
          runbook_url: "https://wiki.example.com/runbooks/payment/fast-burn"
          dashboard_url: "https://grafana.example.com/d/payment-slo-health"

      - alert: PaymentMediumBurn30m
        expr: |
          (
            1 - (
              increase(payment_errors_total[30m]) +
              increase(payment_timeouts_total[30m]) +
              increase(payment_failures_total[30m])
            ) / increase(payment_requests_total[30m])
          ) < 0.9970  # ~10% burn = 30h to exceed budget
        for: 5m
        labels:
          severity: high
          slo_tier: tier1
          service: payment
          burn_window: medium
        annotations:
          summary: "Payment service medium burn (30m)"
          description: "Payment at {{ $value | humanizePercentage }}, pacing to exceed SLO in ~300 hours"

      - alert: PaymentSlowBurn6h
        expr: |
          (
            1 - (
              increase(payment_errors_total[6h]) +
              increase(payment_timeouts_total[6h]) +
              increase(payment_failures_total[6h])
            ) / increase(payment_requests_total[6h])
          ) < 0.9900  # ~1% burn
        for: 30m
        labels:
          severity: medium
          slo_tier: tier1
          service: payment
          burn_window: slow
        annotations:
          summary: "Payment service slow burn (6h)"
          description: "Payment trending at {{ $value | humanizePercentage }} - monitor for increasing issues"

      # Checkout Service: 99.90% SLO = 43.2 min/month downtime
      - alert: CheckoutFastBurn5m
        expr: |
          (
            1 - (
              increase(checkout_errors_total[5m]) +
              increase(checkout_timeouts_total[5m])
            ) / increase(checkout_requests_total[5m])
          ) < 0.9880  # 100% budget burn = >2% error rate
        for: 1m
        labels:
          severity: critical
          slo_tier: tier1
          service: checkout
          burn_window: fast

      - alert: CheckoutMediumBurn30m
        expr: |
          (
            1 - (
              increase(checkout_errors_total[30m]) +
              increase(checkout_timeouts_total[30m])
            ) / increase(checkout_requests_total[30m])
          ) < 0.9950

      - alert: CheckoutSlowBurn6h
        expr: |
          (
            1 - (
              increase(checkout_errors_total[6h]) +
              increase(checkout_timeouts_total[6h])
            ) / increase(checkout_requests_total[6h])
          ) < 0.9850

      # ============================================
      # TIER 2 SERVICES (Order, Search, Routing)
      # ============================================

      - alert: OrderFastBurn5m
        expr: |
          (
            1 - increase(order_errors_total[5m]) / increase(order_requests_total[5m])
          ) < 0.9880
        for: 1m
        labels:
          severity: high
          slo_tier: tier2
          service: order
          burn_window: fast

      - alert: OrderMediumBurn30m
        expr: |
          (
            1 - increase(order_errors_total[30m]) / increase(order_requests_total[30m])
          ) < 0.9950
        for: 5m
        labels:
          severity: medium
          slo_tier: tier2
          service: order
          burn_window: medium

      - alert: SearchFastBurn5m
        expr: |
          (
            1 - increase(search_errors_total[5m]) / increase(search_requests_total[5m])
          ) < 0.9988  # 99.90% SLO, but <200ms latency budget
        for: 1m
        labels:
          severity: high
          slo_tier: tier2
          service: search
          burn_window: fast

      - alert: RoutingFastBurn5m
        expr: |
          (
            1 - increase(routing_errors_total[5m]) / increase(routing_requests_total[5m])
          ) < 0.9988
        for: 1m
        labels:
          severity: high
          slo_tier: tier2
          service: routing
          burn_window: fast

      # ============================================
      # TIER 3 SERVICES (Analytics, Batch)
      # Larger burn windows, lower severity
      # ============================================

      - alert: DispatchFastBurn30m
        expr: |
          (
            1 - increase(dispatch_errors_total[30m]) / increase(dispatch_requests_total[30m])
          ) < 0.9900  # 99.00% SLO
        for: 5m
        labels:
          severity: medium
          slo_tier: tier3
          service: dispatch
          burn_window: fast

      - alert: ReconciliationFastBurn6h
        expr: |
          (
            1 - increase(reconciliation_errors_total[6h]) / increase(reconciliation_requests_total[6h])
          ) < 0.9800  # 98.00% SLO
        for: 30m
        labels:
          severity: low
          slo_tier: tier3
          service: reconciliation
          burn_window: slow

      # ============================================
      # LATENCY-BASED SLO RULES
      # ============================================

      - alert: PaymentLatencyBreach
        expr: |
          histogram_quantile(0.99, rate(payment_request_duration_seconds_bucket[5m])) > 0.3
        for: 2m
        labels:
          severity: high
          slo_tier: tier1
          service: payment
          metric: latency_p99
        annotations:
          summary: "Payment p99 latency exceeds SLO (>300ms)"
          description: "Current p99: {{ $value | humanizeDuration }}"

      - alert: SearchLatencyBreach
        expr: |
          histogram_quantile(0.99, rate(search_request_duration_seconds_bucket[5m])) > 0.2
        for: 2m
        labels:
          severity: medium
          slo_tier: tier2
          service: search
          metric: latency_p99

      - alert: CheckoutLatencyBreach
        expr: |
          histogram_quantile(0.99, rate(checkout_request_duration_seconds_bucket[5m])) > 0.5
        for: 2m
        labels:
          severity: high
          slo_tier: tier1
          service: checkout
          metric: latency_p99

      # ============================================
      # ERROR CATEGORY SPIKES
      # ============================================

      - alert: PaymentFraudScoreSpike
        expr: |
          rate(payment_fraud_score[5m]) > 0.15
        for: 1m
        labels:
          severity: high
          service: payment
          error_type: fraud
        annotations:
          summary: "Payment fraud score spike (>15%)"
          description: "Current fraud rate: {{ $value | humanizePercentage }}"

      - alert: PaymentTimeoutSpike
        expr: |
          rate(payment_timeouts_total[5m]) > 0.001
        for: 2m
        labels:
          severity: high
          service: payment
          error_type: timeout

      - alert: OrderProcessingQueueBacklog
        expr: |
          order_processing_queue_size > 10000
        for: 5m
        labels:
          severity: medium
          service: order
          error_type: backlog

      # ============================================
      # DEPENDENCY HEALTH
      # ============================================

      - alert: PaymentServiceDependencyDown
        expr: |
          (up{job="wallet-service"} == 0) or (up{job="postgres-managed"} == 0)
        for: 1m
        labels:
          severity: critical
          service: payment
        annotations:
          summary: "Payment dependency DOWN"
          description: "{{ $labels.job }} is unreachable"

      - alert: SearchElasticsearchConnection
        expr: |
          elasticsearch_cluster_health_status{cluster="prod"} > 1  # yellow/red
        for: 2m
        labels:
          severity: high
          service: search
        annotations:
          summary: "ElasticSearch cluster unhealthy"
          description: "Cluster status: {{ $value }}"

      - alert: FulfillmentDarkStoreDown
        expr: |
          up{job="dark-store-api"} == 0
        for: 1m
        labels:
          severity: critical
          service: fulfillment
        annotations:
          summary: "Dark store API unavailable"
          description: "Fulfillment cannot access dark store"

      # ============================================
      # INFRASTRUCTURE CONSTRAINTS
      # ============================================

      - alert: HighCPUUtilization
        expr: |
          rate(container_cpu_usage_seconds_total{pod=~"(payment|checkout|search)-.*"}[5m]) * 100 > 85
        for: 3m
        labels:
          severity: medium
          metric: cpu_utilization
        annotations:
          summary: "{{ $labels.pod }} CPU >85%"

      - alert: HighMemoryUtilization
        expr: |
          container_memory_usage_bytes{pod=~"(payment|checkout|search)-.*"} / container_memory_limit_bytes > 0.85
        for: 2m
        labels:
          severity: medium
          metric: memory_utilization

      - alert: DiskSpaceRunningOut
        expr: |
          (node_filesystem_avail_bytes / node_filesystem_size_bytes) * 100 < 10
        for: 5m
        labels:
          severity: high
          metric: disk_usage

      # ============================================
      # DATABASE HEALTH
      # ============================================

      - alert: PostgresConnectionPoolExhausted
        expr: |
          postgresql_pg_stat_statements_connections > 90
        for: 2m
        labels:
          severity: high
          service: postgres
        annotations:
          summary: "PostgreSQL connection pool >90%"

      - alert: PostgresQueryLockWait
        expr: |
          postgresql_locks_all_total{mode=~"AccessExclusive|ExclusiveLock"} > 5
        for: 1m
        labels:
          severity: high
          service: postgres

      - alert: RedisMemoryUsageHigh
        expr: |
          redis_memory_used_bytes / redis_memory_max_bytes > 0.85
        for: 2m
        labels:
          severity: medium
          service: redis

      - alert: KafkaConsumerLag
        expr: |
          kafka_consumer_lag > 100000
        for: 5m
        labels:
          severity: medium
          service: kafka
        annotations:
          summary: "Kafka consumer lag >100k messages"

      # ============================================
      # BUSINESS LOGIC ERRORS
      # ============================================

      - alert: CheckoutFunnelDropoff
        expr: |
          (1 - (increase(checkout_completed_total[1h]) / increase(checkout_initiated_total[1h]))) > 0.20
        for: 30m
        labels:
          severity: medium
          service: checkout
          metric: funnel_dropoff
        annotations:
          summary: "Checkout funnel drop-off >20%"
          description: "Completion rate: {{ $value | humanizePercentage }}"

      - alert: WarehouseAccuracyBreach
        expr: |
          warehouse_inventory_accuracy < 0.95
        for: 1h
        labels:
          severity: high
          service: warehouse
          metric: accuracy_slo
        annotations:
          summary: "Warehouse inventory accuracy <95%"

      - alert: FulfillmentETAAccuracyBreach
        expr: |
          fulfillment_eta_accuracy_within_15min < 0.95
        for: 1h
        labels:
          severity: medium
          service: fulfillment
          metric: eta_accuracy_slo
        annotations:
          summary: "ETA accuracy outside 95% ±15min SLO"

      - alert: RiderLocationUpdateLag
        expr: |
          histogram_quantile(0.99, rider_location_update_lag_seconds) > 30
        for: 5m
        labels:
          severity: high
          service: rider
          metric: location_lag_p99

      # ============================================
      # ALERTMANAGER HEALTH
      # ============================================

      - alert: AlertmanagerNotFiringAlerts
        expr: |
          increase(alertmanager_alerts_received_total[5m]) == 0
        for: 10m
        labels:
          severity: high
          component: alertmanager
        annotations:
          summary: "AlertManager receiving no alerts"

      - alert: PrometheusScrapeFailed
        expr: |
          up == 0
        for: 5m
        labels:
          severity: high
          component: prometheus
        annotations:
          summary: "{{ $labels.job }} scrape failed"
```

### 4.2 Custom Service-Specific Rules

```yaml
# prometheus/rules/custom_service_rules.yaml
groups:
  - name: payment_custom
    interval: 1m
    rules:
      - alert: PaymentPCIDSSViolation
        expr: payment_pci_dss_violations > 0
        for: 1m
        labels:
          severity: critical
          service: payment
          compliance: pci_dss
        annotations:
          summary: "PCI DSS violation detected"
          description: "{{ $value }} violations recorded"

      - alert: PaymentChargeback RateHigh
        expr: rate(payment_chargebacks_total[24h]) / rate(payment_successful_total[24h]) > 0.05
        for: 1h
        labels:
          severity: high
          service: payment
        annotations:
          summary: "Payment chargeback rate >5%"

  - name: fulfillment_custom
    interval: 1m
    rules:
      - alert: DarkStorePickTimeExceeded
        expr: histogram_quantile(0.95, fulfillment_pick_time_seconds) > 900  # 15 min
        for: 30m
        labels:
          severity: medium
          service: fulfillment
        annotations:
          summary: "Dark store pick time exceeding SLA"

      - alert: FulfillmentCityCapacityExceeded
        expr: fulfillment_active_orders / fulfillment_capacity_per_city > 0.95
        for: 10m
        labels:
          severity: medium
          service: fulfillment

  - name: search_custom
    interval: 1m
    rules:
      - alert: SearchIndexStale
        expr: (time() - search_index_last_update_timestamp) > 300
        for: 5m
        labels:
          severity: medium
          service: search
        annotations:
          summary: "Search index stale (>5 min)"

      - alert: SearchCacheHitRateLow
        expr: rate(search_cache_hits_total[5m]) / (rate(search_cache_hits_total[5m]) + rate(search_cache_misses_total[5m])) < 0.80
        for: 10m
        labels:
          severity: low
          service: search

  - name: reconciliation_custom
    interval: 1m
    rules:
      - alert: ReconciliationMismatchRateHigh
        expr: increase(reconciliation_mismatches_total[24h]) / 1000 > 0.01
        for: 1h
        labels:
          severity: medium
          service: reconciliation
        annotations:
          summary: "Reconciliation mismatch rate >1%"

      - alert: AuditTrailGap
        expr: (time() - audit_trail_last_event_timestamp) > 3600
        for: 5m
        labels:
          severity: high
          service: reconciliation
          compliance: audit_trail
        annotations:
          summary: "Audit trail not updating (>1h gap)"
```

---

## Part 5: Deployment Steps

### 5.1 Phase 3A: Infrastructure Setup (March 25-27)

**Step 1: Prometheus Helm Chart Deployment**

```bash
# Add Prometheus community Helm repo
helm repo add prometheus-community https://prometheus-community.github.io/helm-charts
helm repo update

# Create monitoring namespace
kubectl create namespace monitoring

# Deploy Prometheus with custom values
cat > prometheus-values.yaml <<EOF
prometheus:
  prometheusSpec:
    image:
      repository: prom/prometheus
      tag: v2.50.0
    retention: 15d
    retentionSize: 50GB
    storageSpec:
      volumeClaimTemplate:
        spec:
          storageClassName: fast-ssd
          accessModes: [ "ReadWriteOnce" ]
          resources:
            requests:
              storage: 100Gi
    resources:
      requests:
        cpu: 4000m
        memory: 8Gi
      limits:
        cpu: 8000m
        memory: 16Gi
    serviceMonitorSelector:
      matchLabels:
        release: prometheus
    additionalScrapeConfigs: |
      - job_name: 'external-services'
        static_configs:
          - targets: ['postgres.prod:9187', 'redis.prod:9121', 'kafka.prod:9308']

grafana:
  enabled: true
  adminPassword: ${GRAFANA_PASSWORD}
  persistence:
    enabled: true
    size: 10Gi
  datasources:
    datasources.yaml:
      apiVersion: 1
      datasources:
        - name: Prometheus
          type: prometheus
          url: http://prometheus:9090
          isDefault: true
        - name: Loki
          type: loki
          url: http://loki:3100

alertmanager:
  enabled: true
  config:
    global:
      resolve_timeout: 5m
    route:
      receiver: 'default'
      group_wait: 10s
      group_interval: 10s
    receivers:
      - name: 'default'
        slack_configs:
          - api_url: ${SLACK_WEBHOOK}
            channel: '#incidents'

EOF

helm install prometheus prometheus-community/kube-prometheus-stack \
  -n monitoring \
  -f prometheus-values.yaml
```

**Step 2: Loki Deployment**

```bash
helm repo add grafana https://grafana.github.io/helm-charts
helm repo update

cat > loki-values.yaml <<EOF
loki:
  config:
    auth_enabled: false
    ingester:
      chunk_idle_period: 3m
      chunk_retain_period: 1m
      max_chunk_age: 2h
    limits_config:
      enforce_metric_name: false
      reject_old_samples: true
      ingestion_rate_mb: 256
    schema_config:
      configs:
        - from: 2024-01-01
          store: boltdb-shipper
          object_store: s3
          schema: v12
    storage_config:
      boltdb_shipper:
        active_index_directory: /loki/index
        shared_store: s3
      s3:
        s3: s3://loki-bucket/loki
        endpoint: s3.amazonaws.com
        region: us-east-1

persistence:
  enabled: true
  size: 50Gi
  storageClassName: fast-ssd

EOF

helm install loki grafana/loki \
  -n monitoring \
  -f loki-values.yaml
```

**Step 3: Deploy Promtail (Log Agent)**

```bash
cat > promtail-values.yaml <<EOF
config:
  clients:
    - url: http://loki:3100/loki/api/v1/push
  scrape_configs:
    - job_name: kubernetes-pods
      kubernetes_sd_configs:
        - role: pod
      relabel_configs:
        - action: labelmap
          regex: __meta_kubernetes_pod_label_(.+)
        - source_labels: [__meta_kubernetes_namespace]
          target_label: namespace
        - source_labels: [__meta_kubernetes_pod_name]
          target_label: pod

serviceMonitor:
  enabled: true

rbac:
  pspEnabled: true

EOF

helm install promtail grafana/promtail \
  -n monitoring \
  -f promtail-values.yaml
```

### 5.2 Phase 3B: Dashboard Development (March 28-29)

**Step 1: Create ConfigMaps for 31 Dashboards**

```bash
# Convert JSON dashboard to ConfigMap
for service in payment checkout wallet order search catalog pricing fulfillment warehouse inventory rider routing dispatch notification admin-gateway config-feature-flag identity mobile-bff cto audit cdc reconciliation relay stream location-ingestion ai-recommendation ai-fraud-detection; do
  cat > dashboard-${service}.yaml <<EOF
apiVersion: v1
kind: ConfigMap
metadata:
  name: dashboard-${service}
  namespace: monitoring
  labels:
    grafana_dashboard: "1"
data:
  ${service}-slo-health.json: |
    {
      "dashboard": {
        "title": "${service} - SLO Health",
        "uid": "${service}-slo",
        ...
      }
    }
EOF
  kubectl apply -f dashboard-${service}.yaml
done
```

**Step 2: Platform Overview Dashboards**

```bash
kubectl apply -f - <<EOF
apiVersion: v1
kind: ConfigMap
metadata:
  name: dashboard-platform-executive
  namespace: monitoring
  labels:
    grafana_dashboard: "1"
data:
  platform-executive-scorecard.json: |
    $(cat /path/to/executive-scorecard.json)
---
apiVersion: v1
kind: ConfigMap
metadata:
  name: dashboard-financial-slo
  namespace: monitoring
  labels:
    grafana_dashboard: "1"
data:
  financial-slo-dashboard.json: |
    $(cat /path/to/financial-slo.json)
---
apiVersion: v1
kind: ConfigMap
metadata:
  name: dashboard-operations-realtime
  namespace: monitoring
  labels:
    grafana_dashboard: "1"
data:
  operations-realtime.json: |
    $(cat /path/to/operations-realtime.json)
EOF
```

**Step 3: Verify Dashboards Load**

```bash
# Port forward Grafana
kubectl port-forward -n monitoring svc/prometheus-grafana 3000:80

# Check at http://localhost:3000/dashboards
# Default credentials: admin / ${GRAFANA_PASSWORD}
```

### 5.3 Phase 3C: Alert Rules Deployment (March 30)

**Step 1: Create PrometheusRule CRDs**

```bash
kubectl apply -f - <<EOF
apiVersion: monitoring.coreos.com/v1
kind: PrometheusRule
metadata:
  name: slo-burn-rates
  namespace: monitoring
spec:
  groups:
    - name: slo_burn_rates
      interval: 1m
      rules:
        $(cat /path/to/prometheus-rules.yaml | sed 's/^/        /')
---
apiVersion: monitoring.coreos.com/v1
kind: PrometheusRule
metadata:
  name: custom-service-rules
  namespace: monitoring
spec:
  groups:
    - name: payment_custom
      interval: 1m
      rules:
        $(cat /path/to/custom-rules.yaml | sed 's/^/        /')
EOF
```

**Step 2: Configure AlertManager Secrets**

```bash
# Create secrets for integrations
kubectl create secret generic alertmanager-config \
  -n monitoring \
  --from-literal=SLACK_WEBHOOK_URL=${SLACK_WEBHOOK_URL} \
  --from-literal=PD_SERVICE_KEY_TIER1=${PD_SERVICE_KEY_TIER1} \
  --from-literal=PD_SERVICE_KEY_TIER2=${PD_SERVICE_KEY_TIER2} \
  --dry-run=client -o yaml | kubectl apply -f -

# Verify AlertManager is routing alerts
kubectl logs -n monitoring -l app.kubernetes.io/name=alertmanager -f | grep "routing"
```

**Step 3: Test Alert Firing**

```bash
# Generate test alert
kubectl exec -n monitoring prometheus-0 -- \
  promtool query instant 'ALERTS{alertname="PaymentFastBurn5m"}'

# Verify AlertManager receives it
kubectl logs -n monitoring -l app.kubernetes.io/name=alertmanager | grep "PaymentFastBurn5m"
```

### 5.4 Phase 3D: Team Enablement (March 31)

**Step 1: Deploy Runbook Repository**

```bash
# Create runbook ConfigMap
kubectl apply -f - <<EOF
apiVersion: v1
kind: ConfigMap
metadata:
  name: runbooks
  namespace: monitoring
data:
  payment-fast-burn-runbook.md: |
    # Payment Fast Burn Response (5m)

    ## Symptoms
    - Payment error rate >0.05% for 5 min
    - Revenue impact: ~$5k/min

    ## Response Steps
    1. Acknowledge alert in PagerDuty (5 min SLA)
    2. Check Payment Service health dashboard
    3. Correlate with dependency status (Wallet, Identity, DB)
    4. If DB issue: escalate to DBA
    5. If service issue: start incident bridge

    ## Escalation
    - T+0 min: On-call engineer (@backend-lead)
    - T+5 min: Payment team lead (@payment-lead)
    - T+15 min: VP Engineering (@vp-eng)

    ## Rollback
    \`\`\`
    kubectl rollout undo deployment/payment-service -n production
    \`\`\`

  search-latency-breach-runbook.md: |
    # Search Latency Breach (p99 >200ms)
    ...
EOF
```

**Step 2: Training Session Recording**

```markdown
# Dashboard Walkthrough Checklist

## Part 1: Executive Scorecard (5 min)
- [ ] Platform Health Score calculation
- [ ] SLO Compliance by Tier
- [ ] Revenue Impact widget
- [ ] Top 5 Burning Services table

## Part 2: Service Health Dashboard (10 min)
- [ ] SLO Status gauge (target vs. actual)
- [ ] Error Rate graphs (5m/30m/6h windows)
- [ ] Latency percentiles (p50/p90/p99)
- [ ] Error categories pie chart
- [ ] Dependency status panel
- [ ] Infrastructure utilization

## Part 3: On-Call Dashboard (5 min)
- [ ] Alert list (active incidents)
- [ ] Dependency map (drill-down from alert)
- [ ] Error rate heatmap (48-hour view)
- [ ] Quick links to runbooks

## Part 4: Alert Response Procedures (10 min)
- [ ] Alert severity mapping (critical/high/medium/low)
- [ ] Multi-window burn rates explained
- [ ] Escalation paths (5 min/1h/24h)
- [ ] False positive suppression
- [ ] Silence policies (maintenance windows)

## Part 5: On-Call Handoff (5 min)
- [ ] Error budget status for each service
- [ ] Known issues or maintenance windows
- [ ] Recent incidents and resolutions
- [ ] Team contact info
```

---

## Part 6: Metric Naming Conventions

### Standard Format: `<service>_<resource>_<operation>_<unit>`

```
Examples:
- payment_requests_total (counter: total requests processed)
- payment_errors_total (counter: errors by error_type label)
- payment_request_duration_seconds (histogram: latency distribution)
- payment_fraud_score (gauge: 0-1 fraud probability)
- payment_pci_dss_violations (counter: compliance violations)

Labels (all metrics):
- service: [service-name] (mandatory)
- method: [GET/POST/DELETE] (for API)
- status: [2xx/4xx/5xx] (for HTTP)
- error_type: [timeout/auth/validation/dependency] (for errors)
- tier: [tier1/tier2/tier3] (service criticality)
- namespace: [production/staging] (environment)
- pod: [pod-id] (infrastructure)

Business Metrics:
- checkout_completed_total (counter: successful checkouts)
- checkout_cart_abandonment (gauge: abandonment %)
- fulfillment_eta_accuracy_within_15min (gauge: 0-1)
- warehouse_inventory_accuracy (gauge: 0-1)
- payment_fraud_score (gauge: 0-1, per transaction)
- search_index_freshness_seconds (gauge: staleness)

Infrastructure Metrics (auto-exported):
- container_cpu_usage_seconds_total (cAdvisor)
- container_memory_usage_bytes (cAdvisor)
- network_receive_bytes_total (cAdvisor)
```

---

## Part 7: Data Retention Policy

```yaml
# Data Lifecycle Management

tier: hot
  retention: 15 days
  location: Prometheus local storage
  use_cases:
    - Active dashboards (viewed daily)
    - Alert evaluation (1m resolution)
    - Current incident investigation
  sizing: 50 GB (rate: ~3.5 GB/day)

tier: warm
  retention: 90 days
  location: S3 (via remote_write to long-term storage)
  use_cases:
    - Historical trends (weekly reports)
    - Month-over-month comparisons
    - Root cause analysis (RCA)
  sizing: 300 GB (cumulative)
  cost: $15/month (at $0.05/GB)

tier: cold
  retention: 7 years
  location: S3 Glacier (immutable)
  use_cases:
    - Regulatory compliance (PCI DSS)
    - Litigation holds
    - Audit trails (non-repudiation)
  sizing: 2+ TB
  cost: $50/month (at $0.004/GB + retrieval fees)

# Deletion Policy
- Delete all PII after 90 days (GDPR compliance)
- Retain financial audit trails for 7 years
- Retain security/fraud data for 3 years minimum
- Anonymize customer identifiers in warm storage

# Archive Strategy
- Daily snapshot to S3 (11 PM UTC)
- Weekly verification + integrity check
- Monthly rollup to Glacier (300d+ old data)
- Quarterly compliance audit
```

---

## Part 8: Cost Analysis & Projection

### Prometheus Infrastructure Costs

```
On-Premises (Self-Hosted) - K8s Cluster:
  - Compute: 4 CPU × $0.25/hour = $876/month
  - Memory: 16 GB × $0.10/hour = $144/month
  - Storage: 100 GB SSD × $0.10/GB = $10/month
  Total: ~$1,030/month (Prometheus only)

Managed Services (AWS):
  - Managed Prometheus (28 services × 50 metrics): $0.50/million samples
  - Estimated samples: 28 × 50 × 60 × 24 × 30 = 6.048B/month
  - Cost: $3,024/month
  - Plus data transfer: ~$500/month
  Total: ~$3,524/month

Cost Comparison: Self-hosted is 70% cheaper IF on existing K8s cluster
```

### Grafana Costs

```
Self-Hosted OSS: $0 (open-source license)
  - Infrastructure: 2 CPU × $0.25 + 4 GB × $0.10 = $50/month

Managed Grafana Cloud:
  - Pro tier: $299/month (includes 50GB logs)
  - Additional logs: $0.50/GB beyond 50GB
  - Estimated logs: 28 services × 100MB/hour = ~66GB/month
  - Additional cost: (66-50) × $0.50 = $8/month
  Total: ~$307/month

Recommendation: Self-hosted for cost efficiency
```

### Loki Costs

```
Self-Hosted Storage Backend:
  - S3 bucket: 60GB/month × $0.023 = $1.38
  - Compute (3 replicas): 3 × 1 CPU × $0.25 = $180/month
  - Memory: 3 × 2GB × $0.10 = $60/month
  Total: ~$241/month

Managed Loki (Grafana Cloud):
  - Logs ingestion: $0.50/GB
  - Estimated: 60GB/month = $30/month
  Total: ~$30/month (much cheaper!)

Recommendation: Use Grafana Loki for logs, self-host Prometheus
```

### Alerting Costs

```
PagerDuty Tier-1 Team:
  - Basic: $149/month (5 users)
  - High-urgency incident: +$19/month per user
  - Estimated team: 10 users × $19 = $190/month
  Total: ~$339/month

Slack:
  - Free tier sufficient for incident channels
  - No additional cost

Total Monitoring Stack (Recommended):
  - Prometheus (self-hosted): $1,030/month
  - Grafana (self-hosted): $50/month
  - Loki (managed): $30/month
  - PagerDuty: $339/month
  - Total: ~$1,449/month

  - Savings vs. managed: ~$2,500/month (63% cheaper)
```

### ROI Calculation

```
Upfront Costs:
  - Prometheus setup + tuning: 80 hours × $150/hr = $12,000
  - Dashboard development: 120 hours × $150/hr = $18,000
  - Alert rules + runbooks: 60 hours × $150/hr = $9,000
  - Team training: 40 hours × $150/hr = $6,000
  Total: ~$45,000

Monthly Costs:
  - Infrastructure: $1,449/month
  - Maintenance (2 FTE @ 20%): $8,000/month
  Total: ~$9,449/month

Benefits (Conservative):
  - MTTR reduction: 40% (avg 45 min → 27 min)
    → Avoided downtime per year: 50 incidents × 18 min × $5k/min = $7.5M
  - Error budget utilization: +15% (better planning)
    → Increased reliability investment: +$2M/year revenue
  - Compliance automation: -30% audit costs
    → Saved: $150k/year

Break-even: ~3 weeks
12-month ROI: $7.65M benefit / $45k upfront = 170x
```

---

## Part 9: Success Metrics & KPIs

### Phase 3 Delivery Metrics

| Metric | Target | Current | Status |
|--------|--------|---------|--------|
| Dashboards deployed | 31 | 0 | 📋 Mar 29 |
| Alert rules deployed | 50+ | 0 | 📋 Mar 30 |
| Alert delivery SLA (<30s) | 100% | TBD | 🚀 Day 1 |
| Team trained | 28 people | 0 | 📋 Mar 31 |
| Runbooks complete | 28 services | 0 | 📋 Mar 31 |
| SLO compliance tracking | 100% services | 0 | ✅ Week 1 |

### Production Health Metrics

| Metric | Target | Method |
|--------|--------|--------|
| **Alert Accuracy** | >95% true positives | Track false positives in Slack #incidents |
| **MTTR Impact** | -40% reduction | Before/after incident resolution time |
| **Error Budget Visibility** | 100% team | Weekly scorecard shared to all leads |
| **On-Call Experience** | NPS >8 | Monthly survey |
| **Runbook Usage** | 80% of incidents | Track clicks from AlertManager annotations |

---

## Phase 3 Timeline & Milestones

```
Week 3 (March 25-31): Full Deployment

Mar 25 (Wed):
  ✅ 08:00 - Kick-off meeting (all teams)
  ✅ 10:00 - Prometheus/Loki infrastructure deployment
  ✅ 14:00 - Custom scrape configs testing
  ✅ 16:00 - Verification + smoke tests

Mar 26 (Thu):
  ✅ 08:00 - Dashboard development (batch 1: payment/order/checkout)
  ✅ 14:00 - Platform overview dashboards
  ✅ 16:00 - Team access + sandbox testing

Mar 27 (Fri):
  ✅ 08:00 - Dashboard development (batch 2: remaining 25 services)
  ✅ 14:00 - Dashboard review + refinement
  ✅ 17:00 - Week 1 checkpoint

Mar 28 (Sat - Optional):
  📋 10:00 - Dashboard polish + performance tuning
  📋 14:00 - Integration testing

Mar 29 (Sun - Optional):
  📋 10:00 - Final dashboard QA
  📋 16:00 - Production deployment readiness

Mar 30 (Mon):
  🚀 08:00 - Deploy alert rules to production
  🚀 10:00 - AlertManager routing verification
  🚀 14:00 - Silence policy setup (maintenance windows)
  🚀 16:00 - Test incident + response workflow

Mar 31 (Tue):
  📖 08:00 - Team training session #1 (executives)
  📖 10:00 - Team training session #2 (SRE/Platform)
  📖 13:00 - Team training session #3 (service teams)
  📖 15:00 - On-call handoff checklist review
  📖 17:00 - Wave 40 Phase 3 complete! 🎉

Contingency:
  - Delay alert deployment to Apr 1 if needed
  - Extend training to full week (Apr 1-5)
  - Schedule post-launch review (Apr 7)
```

---

## Part 10: Rollback & Contingency Plans

### If Dashboards Fail to Load

```bash
# Check ConfigMap creation
kubectl get configmaps -n monitoring | grep dashboard

# Verify Grafana provisioning
kubectl logs -n monitoring -l app.kubernetes.io/name=grafana | tail -20

# Rollback to previous Grafana version
helm rollback prometheus monitoring -n monitoring

# Manually upload dashboard via Grafana UI
curl -X POST http://grafana:3000/api/dashboards/db \
  -H "Authorization: Bearer ${GRAFANA_TOKEN}" \
  -d @dashboard.json
```

### If Alerts Don't Fire

```bash
# Check PrometheusRule CRD
kubectl get prometheusrules -n monitoring

# Verify Prometheus scrape config
kubectl port-forward -n monitoring prometheus-0 9090
# Check http://localhost:9090/config for scrape targets

# Test alert rule manually
kubectl exec -n monitoring prometheus-0 -- \
  promtool rule-files /etc/prometheus/rules/*.yaml

# Check AlertManager routing
kubectl logs -n monitoring alertmanager-0 | grep "routing"

# Manually trigger test alert
kubectl exec -n monitoring prometheus-0 -- \
  amtool alert add PaymentTestAlert severity=critical
```

### If Performance Degraded

```bash
# Increase Prometheus resources
kubectl patch statefulset prometheus -n monitoring --type='json' \
  -p='[{"op": "replace", "path": "/spec/template/spec/containers/0/resources/requests/cpu", "value":"8000m"}]'

# Reduce scrape frequency temporarily
kubectl edit prometheus -n monitoring
# Change scrapeInterval: 15s → 30s

# Reduce retention
kubectl patch prometheus -n monitoring --type merge \
  -p='{"spec":{"retention":"7d"}}'

# Check metrics cardinality
# Query: count({__name__=~".+"}) to see total series
```

---

## Appendix: Quick Reference

### Dashboard Access URLs
- Executive Scorecard: `https://grafana.prod.example.com/d/exec-scorecard`
- Payment Health: `https://grafana.prod.example.com/d/payment-slo-health`
- Operational Real-Time: `https://grafana.prod.example.com/d/ops-realtime`

### Alert Channels
- Critical: `#incidents` (instant) + PagerDuty
- High: `#sre-incidents` (5 min page)
- Medium: `#observability` (Slack only)
- Low: `#observability` (24h digest)

### Escalation Matrix
- P1 (critical): On-call → Manager → VP (5 min SLA)
- P2 (high): On-call → Team lead (1 hour SLA)
- P3 (medium): Team lead (24 hour SLA)

### Key PromQL Queries
```promql
# SLO compliance
100 * (1 - increase(errors[30d]) / increase(requests[30d]))

# Burn rate (5m)
rate(errors[5m]) / rate(requests[5m])

# P99 latency
histogram_quantile(0.99, rate(request_duration_bucket[5m]))

# Error categories
topk(5, increase(errors_total{error_type=~".+"}[1h]))
```

### Maintenance Windows
```yaml
# Create silence
amtool silence add -c "payment-db-upgrade" alertname=PaymentFastBurn5m start=$(date -u +%Y-%m-%dT%H:%M:%S) end=$(date -u -d '+4 hours' +%Y-%m-%dT%H:%M:%S)

# List active silences
amtool silence query

# Remove silence
amtool silence expire <silence-id>
```

---

## Sign-Off

**Wave 40 Phase 3: Grafana SLO Observability Implementation**

- **Owner**: Platform & Reliability Team
- **Status**: 📋 PREP (Ready for execution)
- **Timeline**: March 25-31, 2026 (7 days)
- **Deliverables**: 31 dashboards, 50+ alerts, team training
- **Success Criteria**: All metrics deployed and team trained by Mar 31

**Next Phase**: Wave 40 Phase 4 - Governance Forums Activation (April 4)

---

**Document Version**: 1.0
**Last Updated**: 2026-03-21
**Next Review**: After Phase 3 completion (2026-04-01)
