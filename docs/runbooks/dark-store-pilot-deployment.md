# Dark-Store Pilot Deployment Runbook

## Overview

This runbook covers the deployment procedure for the InstaCommerce dark-store pilot across three cities:
- **SF (San Francisco)**: Week 1 - 100% traffic (canary)
- **Seattle**: Week 2 - 10% → 100% traffic (gated rollout)
- **Austin**: Week 3 - 5% → 100% traffic (conservative gated rollout)

**Services**: fulfillment-service, warehouse-service, rider-fleet-service

## Pre-Deployment Checklist

### General Prerequisites

- [ ] All staging tests passed
- [ ] SLO baseline metrics collected (past 7 days)
- [ ] On-call team notified
- [ ] Rollback procedure reviewed
- [ ] Slack channel created: #canary-{city}-alerts
- [ ] PagerDuty service configured

### Per-City Prerequisites

| City | Helm Values | ArgoCD Apps | Grafana Dashboard |
|------|-------------|-------------|-------------------|
| SF | values-canary-sf.yaml | canary-sf-*.yaml | canary-sf-overview |
| Seattle | values-canary-seattle.yaml | canary-seattle-*.yaml | canary-seattle-overview |
| Austin | values-canary-austin.yaml | canary-austin-*.yaml | canary-austin-overview |

---

## SF Canary Deployment (Week 1)

### Step 1: Apply ArgoCD Applications

```bash
# Apply all SF canary applications
kubectl apply -f argocd/applications/canary-sf-fulfillment.yaml
kubectl apply -f argocd/applications/canary-sf-warehouse.yaml
kubectl apply -f argocd/applications/canary-sf-rider-fleet.yaml

# Verify applications created
argocd app list | grep canary-sf
```

### Step 2: Sync and Monitor

```bash
# Sync all applications
argocd app sync canary-sf-fulfillment
argocd app sync canary-sf-warehouse
argocd app sync canary-sf-rider-fleet

# Watch rollout status
kubectl rollout status deployment/fulfillment-service-canary -n canary-sf
kubectl rollout status deployment/warehouse-service-canary -n canary-sf
kubectl rollout status deployment/rider-fleet-service-canary -n canary-sf
```

### Step 3: Verify Pod Health

```bash
# Check pod status
kubectl get pods -n canary-sf -l app.kubernetes.io/part-of=dark-store-pilot

# Check pod logs for errors
kubectl logs -n canary-sf -l app=fulfillment-service --tail=100 | grep -i error

# Verify readiness
kubectl get endpoints -n canary-sf
```

### Step 4: Validate Traffic Routing

```bash
# Test fulfillment endpoint
curl -H "x-canary-region: sf" https://api.instacommerce.dev/fulfillment/health

# Check Istio VirtualService
kubectl get virtualservice -n canary-sf -o yaml | grep weight
```

### Step 5: Monitor SLOs

**Grafana Dashboard**: https://grafana.instacommerce.dev/d/canary-sf-overview

**Key Metrics to Watch**:
- Error rate < 1%
- P99 latency < 2s
- Order packing time < 5 min
- Rider assignment < 30s

**Prometheus Queries**:
```promql
# Error rate
sum(rate(http_requests_total{status=~"5..",region="us-west-1"}[5m])) 
/ sum(rate(http_requests_total{region="us-west-1"}[5m]))

# P99 latency
histogram_quantile(0.99, sum(rate(http_request_duration_seconds_bucket{region="us-west-1"}[5m])) by (le, service))
```

---

## Seattle Gated Deployment (Week 2)

### Traffic Ramp Schedule

| Stage | Traffic | Pause Duration | Success Criteria |
|-------|---------|----------------|------------------|
| 1 | 10% | 1 hour | Error rate < 1%, P99 < 2s |
| 2 | 25% | 2 hours | Error rate < 1%, P99 < 2s |
| 3 | 50% | 4 hours | Error rate < 0.5%, P99 < 1.5s |
| 4 | 100% | - | Full rollout |

### Step 1: Initial 10% Deployment

```bash
# Apply Seattle canary applications
kubectl apply -f argocd/applications/canary-seattle-fulfillment.yaml
kubectl apply -f argocd/applications/canary-seattle-warehouse.yaml
kubectl apply -f argocd/applications/canary-seattle-rider-fleet.yaml

# Verify 10% weight
kubectl get rollout fulfillment-service -n canary-seattle -o jsonpath='{.status.canary.weight}'
```

### Step 2: Promote to 25%

After 1 hour with healthy metrics:

```bash
# Promote canary (advances to next step)
kubectl argo rollouts promote fulfillment-service -n canary-seattle
kubectl argo rollouts promote warehouse-service -n canary-seattle
kubectl argo rollouts promote rider-fleet-service -n canary-seattle

# Verify new weight
kubectl argo rollouts status fulfillment-service -n canary-seattle
```

### Step 3: Continue Promotion

Repeat promotion at each stage after pause duration and metrics validation.

```bash
# View rollout history
kubectl argo rollouts get rollout fulfillment-service -n canary-seattle

# Check analysis results
kubectl get analysisrun -n canary-seattle
```

---

## Austin Gated Deployment (Week 3)

### Traffic Ramp Schedule (Conservative)

| Stage | Traffic | Pause Duration | Success Criteria |
|-------|---------|----------------|------------------|
| 1 | 5% | 2 hours | Error rate < 0.5%, P99 < 1.5s |
| 2 | 10% | 4 hours | Error rate < 0.5%, P99 < 1.5s |
| 3 | 25% | 8 hours | Error rate < 0.5%, P99 < 1.5s |
| 4 | 50% | 12 hours | Error rate < 0.3%, P99 < 1s |
| 5 | 100% | - | Full rollout |

### Deployment Steps

Follow same pattern as Seattle with longer pause durations and stricter thresholds.

---

## Rollback Procedures

### Immediate Rollback (P0 Incident)

```bash
# Set traffic to 0% immediately
kubectl patch rollout fulfillment-service -n canary-{city} --type merge -p '{"spec":{"strategy":{"canary":{"steps":[{"setWeight":0}]}}}}'

# Or abort the rollout
kubectl argo rollouts abort fulfillment-service -n canary-{city}
kubectl argo rollouts abort warehouse-service -n canary-{city}
kubectl argo rollouts abort rider-fleet-service -n canary-{city}

# Delete canary pods
kubectl delete pods -n canary-{city} -l rollout-type=canary
```

### Graceful Rollback

```bash
# Undo last rollout
kubectl argo rollouts undo fulfillment-service -n canary-{city}

# Verify stable version restored
kubectl argo rollouts status fulfillment-service -n canary-{city}
```

### Full Rollback

```bash
# Delete all canary resources
argocd app delete canary-{city}-fulfillment
argocd app delete canary-{city}-warehouse
argocd app delete canary-{city}-rider-fleet

# Verify cleanup
kubectl get all -n canary-{city}
```

---

## Escalation

### P0: Complete Outage
1. Page @fulfillment-oncall immediately
2. Execute immediate rollback
3. Open incident channel: #incident-canary-{city}
4. Notify engineering leadership

### P1: Degraded Performance
1. Notify #canary-{city}-alerts
2. Pause rollout: `kubectl argo rollouts pause fulfillment-service -n canary-{city}`
3. Investigate metrics
4. Resume or rollback based on findings

### P2: Minor Issues
1. Document in #canary-{city}-alerts
2. Continue monitoring
3. Address in next sprint if non-blocking

---

## Success Criteria

### Per-City Go-Live Criteria

| Metric | Target | Measurement |
|--------|--------|-------------|
| Availability | ≥99.5% | 24h rolling average |
| P99 Latency | <2s | All endpoints |
| Error Rate | <1% | 5xx responses |
| Order Packing | <5min P99 | Per warehouse |
| Rider Assignment | <30s P99 | Per region |
| ETA Accuracy | ±15min | Delivered orders |

### Pilot Completion Criteria

- [ ] All 3 cities at 100% traffic
- [ ] 7 consecutive days meeting SLOs
- [ ] No P0/P1 incidents in past 3 days
- [ ] Post-pilot review completed
- [ ] Recommendations documented for wider rollout
