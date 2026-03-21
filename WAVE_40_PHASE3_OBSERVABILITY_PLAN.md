# Wave 40 Phase 3: Observability (Grafana SLO Dashboards & Alerts)

## Executive Summary
**Objective**: Deploy production-grade SLO dashboards + multi-window burn-rate alerts
**Timeline**: Week 3 (March 31 - April 4)
**Coverage**: All 28 services with color-coded SLO compliance status
**Success Metric**: 100% service coverage, zero blind spots

---

## Dashboard Architecture

### 1. Executive Overview Dashboard
**Audience**: VP Eng, Product Leadership
**Refresh**: 1-minute

**Panels**:
1. **SLO Compliance Scorecard** (grid)
   - All 28 services: Green (>99%), Yellow (95-99%), Red (<95%)
   - Monthly error budget remaining (%)
   - Trend: 7-day cost (SLO drift)

2. **Critical Path Status** (bars)
   - Payment: Availability %, Latency p99, Error rate
   - Order: Availability %, Latency p99, Error rate
   - Fulfillment: Availability %, Latency p99, ETA accuracy

3. **Error Budget Burn Rate** (time series)
   - 5-min burn (fast)
   - 30-min burn (medium)
   - 6-hour burn (slow)
   - Monthly reset line

4. **P0 Incidents This Month** (stat)
   - Count + trend
   - MTTR (mean time to resolution)
   - Link to incident tracker

### 2. Service Deep-Dive Dashboard (Per Service)
**Audience**: Service Owners, On-Call
**Refresh**: 30-second

**Panels** (Example: payment-service):
1. **SLO Status** (gauge)
   - Current availability %
   - SLO target: 99.95%
   - Current month: X% (shade: green/yellow/red)

2. **Latency Tiers** (line chart)
   - p50 (median)
   - p95 (user-facing)
   - p99 (critical)
   - p99.9 (tail)
   - SLO target bands

3. **Error Rate** (line chart)
   - Requests/sec (gray area)
   - Error % (red line, target <0.05%)
   - Error categories (stacked: validation, timeout, internal_error, rate_limit)

4. **Burn Rate Multi-Window** (table)
   - 5-min burn (>10% = red)
   - 30-min burn (>5% = orange)
   - 6-hour burn (>1% = yellow)
   - Current availability %
   - Error budget remaining (days)

5. **Dependency Health** (status)
   - PostgreSQL: healthy
   - Redis: healthy
   - Kafka: healthy
   - gRPC services: list with status

6. **Alert Status** (list)
   - Active alerts (if any)
   - Time since last alert
   - Recent incidents (1 week)

---

## Prometheus Alert Rules

### Tier 1: Fast Burn (Page On-Call)

```prometheus
# Payment Service: >10% error in 5 min = SEV-1
- alert: PaymentServiceFastBurn
  expr: |
    (sum(rate(http_requests_total{service="payment-service", status=~"5.."}[5m])) /
     sum(rate(http_requests_total{service="payment-service"}[5m]))) > 0.1
  for: 2m
  labels:
    severity: critical
    service: payment-service
  annotations:
    summary: "Payment service error spike (>10% in 5min)"
    runbook: "docs/runbooks/payment-service-errors.md"

# Order Service: <500ms p99 latency miss
- alert: OrderServiceLatencySpikeP99
  expr: |
    histogram_quantile(0.99,
      sum(rate(http_request_duration_seconds_bucket{service="order-service"}[5m])) by (le)
    ) > 0.5
  for: 2m
  labels:
    severity: critical
```

### Tier 2: Medium Burn (Create Incident)

```prometheus
# Any service: >5% error in 30 min = SEV-2
- alert: ServiceMediumBurn
  expr: |
    (sum(rate(http_requests_total{status=~"5.."}[30m])) by (service) /
     sum(rate(http_requests_total[30m])) by (service)) > 0.05
  for: 5m
  labels:
    severity: warning
    service: "{{ $labels.service }}"
  annotations:
    summary: "{{ $labels.service }} elevated error rate (>5% over 30min)"
```

### Tier 3: Slow Burn (Review Post-Incident)

```prometheus
# Any service: >1% error over 6 hours = SEV-3
- alert: ServiceSlowBurn
  expr: |
    (sum(rate(http_requests_total{status=~"5.."}[6h])) by (service) /
     sum(rate(http_requests_total[6h])) by (service)) > 0.01
  for: 30m
  labels:
    severity: info
```

### Dependency Alerts

```prometheus
- alert: RedisUnavailable
  expr: redis_up == 0
  for: 1m
  annotations:
    summary: "Redis down - potential cache miss flood"

- alert: PostgreSQLReplicationLag
  expr: pg_replication_lag_bytes > 1000000000  # 1GB
  labels:
    severity: warning
  annotations:
    summary: "PostgreSQL replication lag >1GB - reconciliation may stall"

- alert: KafkaPartitionLeaderMissing
  expr: kafka_topic_partitions{state="leader"} == 0
  for: 2m
  labels:
    severity: critical
  annotations:
    summary: "Kafka partition lost leader - data loss risk"
```

---

## Dashboard Deployment

### Grafana Setup

```bash
# 1. Create namespace
kubectl create namespace observability

# 2. Deploy Grafana
helm install grafana grafana/grafana \
  --namespace observability \
  -f values/grafana.yaml \
  --set adminPassword=$(openssl rand -base64 12)

# 3. Import dashboards + alerts
kubectl apply -f grafana/dashboards/
kubectl apply -f grafana/alerts/

# 4. Create datasources (Prometheus)
kubectl apply -f grafana/datasources/prometheus.yaml

# 5. Enable state persistence
kubectl apply -f grafana/pvc.yaml
```

### Dashboard Files (JSON)

**Generated from**:
- `docs/observability/grafana-executive-dashboard.json` (2MB, 12 panels)
- `docs/observability/grafana-service-template.json` (500KB, 8 panels per service)
- `docs/observability/grafana-dark-stores-dashboard.json` (Phase 2 integration)

### Alert Notification Channels

```
1. **PagerDuty** (SEV-1 critical)
   - Trigger: Fast burn alerts
   - Escalation: 5 min (front-line) → 15 min (manager)
   - Routing: Via service CODEOWNERS

2. **Slack** (SEV-2 warnings)
   - Channel: #incidents-severity2
   - Format: Alert summary + dashboard link
   - Thread: Status updates

3. **Email** (SEV-3 info)
   - Recipients: Service owners, team lead
   - Digest: Daily (8 AM UTC)

4. **Webhook** (Custom)
   - Incident tracker auto-creation
   - SLO report generation
```

---

## SLO Definitions (All 28 Services)

### Money Path (4 services)

| Service | Availability | Latency p99 | Error Rate | Other |
|---------|--------------|-------------|-----------|-------|
| **payment-service** | 99.95% | <300ms | <0.05% | Settlement: daily by 2 AM |
| **order-service** | 99.9% | <500ms | <0.1% | Acknowledgment: <100ms |
| **checkout-orchestrator** | 99.9% | <1s | <0.1% | Compensation: <50ms |
| **fulfillment-service** | 99.5% | <2s | <0.1% | ETA accuracy: ±15min (95%) |

### Platform Services (8 services)

| Service | Availability | Latency p99 | Error Rate |
|---------|--------------|-------------|-----------|
| **identity-service** | 99.95% | <100ms | <0.05% |
| **config-feature-flag-service** | 99.99% | <5ms | <0.01% |
| **admin-gateway-service** | 99.9% | <200ms | <0.1% |
| **audit-trail-service** | 99.9% | <500ms | <0.1% |
| **cdc-consumer-service** | 99.5% | N/A (async) | <0.1% end-to-end |
| **outbox-relay-service** | 99.9% | <100ms | <0.01% |
| **stream-processor-service** | 99% | N/A (batch) | <1% |
| **reconciliation-engine** | 99% | N/A (batch, daily) | Zero mismatch (audit) |

### Read Path (4 services)

| Service | Availability | Latency p99 | Error Rate | Freshness |
|---------|--------------|-------------|-----------|-----------|
| **search-service** | 99% | <200ms | <0.5% | <5 min |
| **catalog-service** | 99.5% | <300ms | <0.1% | <30 sec |
| **pricing-service** | 99% | <100ms | <0.1% | Real-time |
| **mobile-bff-service** | 99% | <300ms | <0.1% | N/A (aggregator) |

### Fulfillment Logistics (4 services)

| Service | Availability | Latency p99 | Other |
|---------|--------------|-------------|-------|
| **warehouse-service** | 99.5% | <500ms | Capacity accuracy: 99.9% |
| **rider-fleet-service** | 99% | <1s | Utilization: >70% |
| **routing-eta-service** | 99% | <100ms | Accuracy: ±15 min (99%) |
| **dispatch-optimizer-service** | 99% | <500ms | Accepted rate: >95% |

### AI/Engagement (4 services)

| Service | Availability | Latency p50/p99 | Other |
|---------|--------------|-----------------|-------|
| **ai-inference-service** | 95% | <5s / <15s | Batch latency OK |
| **ai-orchestrator-service** | 95% | <100ms / <1s | Orchestration SLO |
| **fraud-detection-service** | 99% | <500ms / <2s | FPR: <1% |
| **notification-service** | 99% | <1s delivery | Dedup: 99.9% |

### Data/Other (2 services)

| Service | Availability | Goal |
|---------|--------------|------|
| **location-ingestion-service** | 99% | Ingestion lag: <30sec |
| **wallet-loyalty-service** | 99.5% | Balance consistency: 99.99% |

---

## Error Budget Policy

### Monthly Reset (First Day UTC)

```
Error Budget = (1 - SLO_TARGET) * Total_Seconds_in_Month

Example (payment-service, 99.95% SLO):
- Budget = (1 - 0.9995) * 2,592,000 sec = 1,296 seconds (~21.6 minutes)
- Burn rate: 1% error = 25.92 sec/min budget consumed
- Alert: If 5+ minutes at 1% error, investigating begins
```

### Fast Burn Rules (5-minute windows)

- If any 5-min window: error > (SLO_ERROR% × 288), page on-call
  - Example: payment (0.05% target) → 5-min threshold = 0.144%
  - If any 5-min window > 0.144% errors, SEV-1 page

### Budget Trading (Optional)

- Service owners can trade unused budget for investment
- Example: "We have 10 min budget left this month, using 8 min for DB migration"
- Requires: VP approval + SLO commitment next month

---

## Deployment Timeline

| Day | Task | Owner |
|-----|------|-------|
| Mon | Deploy Prometheus + Grafana | Platform |
| Tue | Create 28 service dashboards | Platform |
| Tue | Load alert rules | Platform |
| Wed | Integration testing (sandbox) | QA |
| Wed | Team training (30 min) | Platform |
| Thu | Production deployment | Platform |
| Fri | Validation + runbook updates | All |

---

## Runbook Structure

**Example**: `docs/runbooks/payment-service-high-latency.md`

```markdown
## Payment Service High Latency (p99 > 300ms)

### Symptoms
- Grafana alert triggered
- Checkout conversion drop

### Investigation (5 min)
1. Check PagerDuty + Slack #incidents-severity2
2. Dashboard: Latency breakdown (DB vs network vs business logic)
3. Query logs: `service: payment-service AND duration_ms > 300`

### Common Causes
1. **Database slow** → check pg_slow_query_log
2. **Rate limiter active** → check gateway metrics
3. **Third-party (Stripe) slow** → check vendor status page
4. **GC pause** → check Java heap metrics

### Resolution
- [Scaling runbook]
- [Failover runbook]
- [Rollback runbook]

### Escalation
If unresolved in 15 min → page VP Eng
```

---

## Success Criteria

| Metric | Target | Done? |
|--------|--------|-------|
| Dashboards deployed | 31 (1 exec + 28 service + 2 cluster) | 📋 |
| Alert rules | 50+ | 📋 |
| Notification channels | 4 (PD, Slack, email, webhook) | 📋 |
| Runbooks | 28 (1 per service) | 📋 |
| Team training | 100% of on-call | 📋 |
| SLO coverage | All 28 services | 📋 |

---

## Next Phase (Wave 41)

- Chaos engineering: Validate dashboards during incident simulations
- SLO evolution: Auto-adjust targets based on 3-month historical data
- Custom metrics: Business metrics (NPS, ARR) integrated into SLO dashboard
