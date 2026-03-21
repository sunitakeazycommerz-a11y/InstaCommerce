# Wave 39 Production Deployment Runbook

**Date**: March 2026
**Status**: Ready for Production Deployment
**SLA**: Zero-downtime deployment with staged rollout
**Rollback Window**: 30 minutes (automatic if SLOs breached)

---

## Table of Contents

1. [Pre-Deployment Checklist](#pre-deployment-checklist)
2. [Deployment Steps](#deployment-steps)
3. [Rollback Plan](#rollback-plan)
4. [Post-Deployment Validation](#post-deployment-validation)
5. [Support & Escalation](#support--escalation)
6. [Command Reference](#command-reference)

---

## Pre-Deployment Checklist

Before proceeding with Wave 39 deployment, **ALL** items must be checked off:

- [ ] **PR #205 merged to master** - Architectural diagrams + build system fixes committed
  - Verify: `git log --oneline | grep "Wave 39"` returns 2+ commits
  - Commit hash format: `d1044ac feat(wave-39): Complete architectural diagrams...`

- [ ] **CI Pipeline GREEN** - All GitHub Actions jobs passing
  - Navigate to: `https://github.com/InstaCommerce/InstaCommerce/actions`
  - Verify: ✅ All Java services compile (JDK 17)
  - Verify: ✅ All Go services build (1.21+)
  - Verify: ✅ Docker images built and available
  - Verify: ✅ All Python services pass linting

- [ ] **Build Artifacts Ready**
  - Java: 13 services (Spring Boot 3.1+) → Docker images pushed to ACR
  - Go: 12 services → Docker images pushed to ACR
  - Python: 3 services → Docker images pushed to ACR
  - Command: `docker images | grep instacommerce | wc -l` should return 28

- [ ] **Backup of Previous Deploy Documented**
  - Previous version: **Wave 38** (commit `55dc25e`)
  - Backup location: `gs://instacommerce-backups/wave38-backup-$(date +%Y%m%d-%H%M%S).tar.gz`
  - Command: See [Backup Procedure](#backup-procedure) section

- [ ] **Stakeholder Notification Sent**
  - [ ] Platform Engineering team notified (Slack: #platform-eng)
  - [ ] Finance team notified (reconciliation engine running)
  - [ ] DevOps team on standby
  - [ ] Message template:
    ```
    🚀 Wave 39 Deployment Scheduled
    ⏰ Start: [TIME] UTC
    📊 Services: 28 (all healthy)
    🎯 Rollout: Canary 5% → 25% → 50% → 100% (15 min per stage)
    ⏱️  Expected duration: 1 hour
    🆘 Escalation: See #wave39-deployment-status
    ```

- [ ] **Rollback Plan Reviewed**
  - [ ] Rollback command tested in staging
  - [ ] Estimated rollback time: **5 minutes**
  - [ ] Rollback triggers documented (see [Rollback Triggers](#rollback-triggers))

---

## Deployment Steps

### Zero-Downtime Strategy

This deployment uses **Istio VirtualService traffic management** with progressive rollout:
- Stage 1: Canary (5% traffic) → 15 min monitoring
- Stage 2: Growth (25% traffic) → 15 min monitoring
- Stage 3: Majority (50% traffic) → 15 min monitoring
- Stage 4: Full (100% traffic) → 15 min final validation

**Total deployment time**: ~1 hour

---

### Step 1: Verify Master Branch

**Objective**: Confirm we're deploying Wave 39 code.

```bash
# Check current branch
git branch -v
# Expected output: * master d1044ac fix(build): Resolve P0 compilation errors...

# Verify latest commits
git log --oneline -5
# Expected output:
# 9c7cad1 fix(build): Resolve P0 compilation errors and Jackson classpath conflict
# d1044ac feat(wave-39): Complete architectural diagrams for 28+ services + build system fixes
# fed80ff docs(diagrams): Complete comprehensive Mermaid diagrams for 28+ services
# 0576ffa Merge pull request #204 from sunitakeazycommerz-a11y/feat/wave34-38-governance-hardening

# Get current commit hash for rollback reference
CURRENT_COMMIT=$(git rev-parse HEAD)
echo "Wave 39 Commit Hash: $CURRENT_COMMIT"
# Save this for rollback procedures

# Verify no uncommitted changes
git status
# Expected: "On branch master" and "nothing to commit, working tree clean"
```

**Success Criteria**:
- ✅ On master branch
- ✅ Latest commit is Wave 39 (build fixes)
- ✅ No uncommitted changes
- ✅ Commit hash documented

---

### Step 2: Pull Master to Staging Environment

**Objective**: Deploy Wave 39 code to staging cluster for pre-production testing.

```bash
# SSH into staging deployment node
ssh deploy@staging-deployer-01.instacommerce.internal

# Pull latest Wave 39 code
cd /opt/instacommerce/deployment
git fetch origin master
git checkout master
git pull origin master

# Verify deployment manifests are present
ls -la k8s/services/ | wc -l
# Expected: 28 services

# Verify Istio VirtualService for canary routing exists
kubectl get virtualservice -n instacommerce-staging | wc -l
# Expected: 28+ (one per service)
```

**Verification**:
```bash
# Ensure all ConfigMaps updated with Wave 39 feature flags
kubectl get configmap -n instacommerce-staging | grep feature-flag | head -5

# Check image digests match CI build
kubectl get deployments -n instacommerce-staging -o wide | head -10
```

**Success Criteria**:
- ✅ Master code pulled to staging
- ✅ All 28 service manifests present
- ✅ Istio VirtualServices configured for canary
- ✅ Image digests match Wave 39 build

---

### Step 3: Run Smoke Tests

**Objective**: Verify staging deployment is functional before canary rollout.

```bash
# SSH into staging cluster
kubectl config use-context staging-cluster

# Run unit + smoke tests (no integration tests yet)
cd /opt/instacommerce/src
./gradlew test -x integrationTest --info 2>&1 | tee /tmp/smoke-tests.log

# Expected output sample:
# BUILD SUCCESSFUL
# > Task :admin-gateway-service:test
# > Task :catalog-service:test
# ... (13 Java services)
# > Task :reconciliation-engine:test (Wave 36 Reconciliation logic)
```

**Verify Key Services**:
```bash
# Health check: Payment service (most critical)
curl -s http://payment-service.instacommerce-staging:8080/health | jq '.status'
# Expected: "UP"

# Health check: Identity service (auth critical)
curl -s http://identity-service.instacommerce-staging:8080/health | jq '.status'
# Expected: "UP"

# Health check: Reconciliation engine (Wave 36)
curl -s http://reconciliation-engine.instacommerce-staging:8080/health | jq '.status'
# Expected: "UP"

# Check SLO metrics available
curl -s http://prometheus.instacommerce-staging:9090/api/v1/query?query=up | jq '.data.result | length'
# Expected: 28+ (one metric per service)
```

**Smoke Test Coverage**:
```bash
# Verify Wave 39 specific tests pass
grep -r "Wave39\|wave39" src/*/src/test --include="*.java" --include="*.go" --include="*.py" | wc -l
# Expected: 5+ new tests for architectural changes

# Check test report
cat /tmp/smoke-tests.log | grep -E "PASSED|FAILED"
# Expected: 0 FAILED
```

**Success Criteria**:
- ✅ All unit tests pass
- ✅ All 28 services health checks GREEN
- ✅ SLO metrics being collected
- ✅ 0 test failures

---

### Step 4: Deploy to Canary (5% Traffic)

**Objective**: Route 5% of production traffic to Wave 39 code, while 95% stays on Wave 38.

```bash
# Switch to production cluster
kubectl config use-context production-cluster

# Verify current production state
kubectl get deployments -n instacommerce | head -10
# Expected: All services running with Wave 38 images

# Apply Wave 39 canary VirtualService (5% traffic split)
kubectl apply -f k8s/canary/virtualservice-wave39-5pct.yaml -n instacommerce

# Deployment script example:
cat > /tmp/deploy-canary-5pct.sh << 'EOF'
#!/bin/bash
set -e

NAMESPACE="instacommerce"
WAVE39_COMMIT="9c7cad1"

# Update all service deployments to Wave 39 images
for service in $(ls k8s/services/); do
  kubectl set image deployment/$service \
    $service=instacommerce-acr.azurecr.io/$service:$WAVE39_COMMIT \
    -n $NAMESPACE --record
done

# Wait for 5 replicas (canary - minimal traffic)
kubectl scale deployment/payment-service -n $NAMESPACE --replicas=5
kubectl scale deployment/order-service -n $NAMESPACE --replicas=5

# Apply Istio VirtualService with 5% traffic weight
kubectl apply -f - <<MANIFEST
apiVersion: networking.istio.io/v1beta1
kind: VirtualService
metadata:
  name: payment-service
  namespace: $NAMESPACE
spec:
  hosts:
  - payment-service
  http:
  - match:
    - headers:
        user-segment:
          exact: wave39-canary
    route:
    - destination:
        host: payment-service
        port:
          number: 8080
  - route:
    - destination:
        host: payment-service
        port:
          number: 8080
        host: payment-service-wave38  # Stable version
      weight: 95
    - destination:
        host: payment-service
        port:
          number: 8080
        host: payment-service-wave39  # Canary version
      weight: 5
MANIFEST

echo "✅ Canary deployment (5%) applied to all 28 services"
EOF

chmod +x /tmp/deploy-canary-5pct.sh
/tmp/deploy-canary-5pct.sh
```

**Canary Verification**:
```bash
# Check rollout status
kubectl rollout status deployment/payment-service -n instacommerce

# Verify traffic split in Istio
kubectl get virtualservice payment-service -n instacommerce -o yaml | grep weight

# Monitor canary pod logs
kubectl logs -l app=payment-service,wave=wave39 -n instacommerce --tail=50 -f &

# Expected log entries:
# INFO  Starting payment-service Wave 39
# INFO  Wave39 Feature Flags Cache Initialized
# INFO  Admin-Gateway JWT Auth Enabled
```

**Success Criteria**:
- ✅ 5% traffic routed to Wave 39 services
- ✅ 95% traffic still on Wave 38 (fallback available)
- ✅ Canary pods are READY
- ✅ No error spikes in production metrics

---

### Step 5: Monitor SLOs for 15 Minutes

**Objective**: Verify Wave 39 meets SLO targets before increasing traffic.

```bash
# Monitor critical SLOs in real-time
watch -n 5 'kubectl get pod -n instacommerce | grep wave39'

# Dashboard command: Open Grafana SLO dashboard
# URL: https://grafana.instacommerce.io/d/wave39-slos

# Monitor via CLI (if Prometheus available):
PROMETHEUS_URL="http://prometheus.instacommerce.io:9090"

# Check Payment Service SLO (99.95% availability)
curl -s "${PROMETHEUS_URL}/api/v1/query?query=up%7Bjob%3D%22payment-service%22%7D" | jq '.data.result'

# Check Error Rate (should be <0.05%)
curl -s "${PROMETHEUS_URL}/api/v1/query?query=rate(http_requests_total%7Bstatus%3D~%225..%22%7D%5B5m%5D)" | jq '.data.result'

# Check Latency p99 (should be <300ms for Payment)
curl -s "${PROMETHEUS_URL}/api/v1/query?query=histogram_quantile%280.99%2C%20rate%28http_request_duration_seconds_bucket%5B5m%5D%29%29" | jq '.data.result'
```

**SLO Targets for Wave 39 (All Services)**:

| Service | Availability | P99 Latency | Error Rate |
|---------|-------------|------------|-----------|
| Payment | 99.95% | <300ms | <0.05% |
| Order | 99.9% | <500ms | <0.1% |
| Fulfillment | 99.5% | <2s | <0.5% |
| Search | 99% | <200ms | <1% |
| Catalog | 99% | <300ms | <0.5% |
| Inventory | 99.9% | <400ms | <0.1% |
| Cart | 99.9% | <500ms | <0.1% |
| Reconciliation Engine | 99.9% | <1s | <0.1% |

**15-Minute Canary Monitoring Checklist**:
```bash
# T+0: Start canary
echo "Canary Start: $(date)" > /tmp/wave39-canary.log

# T+5 min: Check no error spikes
for i in {1..3}; do
  sleep 300
  echo "T+$((i*5)) min check: $(date)" >> /tmp/wave39-canary.log
  kubectl logs -l wave=wave39 -n instacommerce --tail=20 | grep -i error >> /tmp/wave39-canary.log || true
done

# T+15 min: Final validation
echo "✅ 15-minute monitoring complete at $(date)" >> /tmp/wave39-canary.log
cat /tmp/wave39-canary.log
```

**Abort Criteria** (Immediate rollback if triggered):
- ❌ Payment service error rate > 1% (SLO breach)
- ❌ Payment service p99 latency > 1000ms (slowdown)
- ❌ Order service error rate > 2% (cascading failures)
- ❌ Any service pod CrashLoopBackOff

**Success Criteria**:
- ✅ All services within SLO targets
- ✅ No error spikes
- ✅ Pod logs show healthy operation
- ✅ 15 minutes monitoring completed

---

### Step 6: Promote Traffic Stages (25% → 50% → 100%)

**Objective**: Progressively increase Wave 39 traffic if canary monitoring successful.

#### Stage 1: Promote to 25% Traffic

```bash
# Update VirtualService to route 25% traffic to Wave 39
kubectl patch virtualservice payment-service -n instacommerce --type merge -p '{
  "spec": {
    "http": [{
      "route": [
        {"destination": {"host": "payment-service-wave38"}, "weight": 75},
        {"destination": {"host": "payment-service-wave39"}, "weight": 25}
      ]
    }]
  }
}'

# Scale Wave 39 replicas to handle 25% load
kubectl scale deployment/payment-service -n instacommerce --replicas=20

# Monitor for 15 minutes
echo "25% Traffic Stage - Start: $(date)"
sleep 900  # 15 minutes
echo "25% Traffic Stage - Complete: $(date)"

# Verify SLOs still met
kubectl exec -n instacommerce prometheus-pod -- \
  promtool query instant 'up{job="payment-service"}'
```

#### Stage 2: Promote to 50% Traffic

```bash
# Update VirtualService to route 50% traffic to Wave 39
kubectl patch virtualservice payment-service -n instacommerce --type merge -p '{
  "spec": {
    "http": [{
      "route": [
        {"destination": {"host": "payment-service-wave38"}, "weight": 50},
        {"destination": {"host": "payment-service-wave39"}, "weight": 50}
      ]
    }]
  }
}'

# Scale Wave 39 replicas to handle 50% load
kubectl scale deployment/payment-service -n instacommerce --replicas=40

# Monitor for 15 minutes
echo "50% Traffic Stage - Start: $(date)"
sleep 900
echo "50% Traffic Stage - Complete: $(date)"

# Verify no degradation
curl -s http://prometheus.instacommerce.io:9090/api/v1/query?query='rate(errors_total[5m])' | jq '.data.result'
```

#### Stage 3: Promote to 100% Traffic (Full Rollout)

```bash
# Update VirtualService to route 100% traffic to Wave 39
kubectl patch virtualservice payment-service -n instacommerce --type merge -p '{
  "spec": {
    "http": [{
      "route": [
        {"destination": {"host": "payment-service-wave39"}, "weight": 100}
      ]
    }]
  }
}'

# Scale Wave 39 replicas to full production load (80 replicas for critical services)
kubectl scale deployment/payment-service -n instacommerce --replicas=80

# Remove Wave 38 deployment (after 100% rollout confirmed)
kubectl delete deployment payment-service-wave38 -n instacommerce

echo "✅ Full Wave 39 Rollout Complete: $(date)"
```

**Rollout Status Tracking**:
```bash
# Check all services transitioned to Wave 39
for svc in payment order fulfillment inventory search catalog cart; do
  echo "=== $svc ==="
  kubectl get deployment $svc-service -n instacommerce -o wide
done

# Confirm no Wave 38 pods remaining
kubectl get pods -n instacommerce | grep wave38 | wc -l
# Expected: 0
```

**Success Criteria**:
- ✅ All traffic flowing to Wave 39 (100%)
- ✅ All 28 services running on Wave 39 code
- ✅ SLOs maintained throughout all stages
- ✅ Wave 38 deployments removed

---

### Step 7: Verify All 28 Services Healthy

**Objective**: Comprehensive health check across entire platform.

```bash
# Full Health Check Script
cat > /tmp/full-health-check.sh << 'EOF'
#!/bin/bash

NAMESPACE="instacommerce"
SERVICES=(
  "admin-gateway-service"
  "catalog-service"
  "config-feature-flag-service"
  "notification-service"
  "mobile-bff-service"
  "routing-eta-service"
  "warehouse-service"
  "cdc-consumer-service"
  "dispatch-optimizer-service"
  "location-ingestion-service"
  "stream-processor-service"
  "reconciliation-engine"
  "payment-service"
  "order-service"
  "fulfillment-service"
  "inventory-service"
  "cart-service"
  "search-service"
  "pricing-service"
  "wallet-service"
  "fraud-detection-service"
  "identity-service"
  "audit-service"
  "relay-service"
  "customer-journey-service"
  "ml-recommendation-service"
  "ml-demand-forecast-service"
  "ml-anomaly-detection-service"
)

HEALTHY=0
UNHEALTHY=0

echo "Wave 39 Production Health Check - $(date)"
echo "================================================"

for svc in "${SERVICES[@]}"; do
  # Check if service deployment exists
  if kubectl get deployment $svc -n $NAMESPACE &>/dev/null; then
    # Get current replica status
    READY=$(kubectl get deployment $svc -n $NAMESPACE -o jsonpath='{.status.readyReplicas}')
    DESIRED=$(kubectl get deployment $svc -n $NAMESPACE -o jsonpath='{.status.replicas}')

    # Check health endpoint
    HEALTH_STATUS=$(kubectl exec -n $NAMESPACE \
      $(kubectl get pod -n $NAMESPACE -l app=$svc --field-selector=status.phase=Running -o name | head -1) \
      -- curl -s http://localhost:8080/health 2>/dev/null | jq -r '.status // "UNKNOWN"' 2>/dev/null || echo "UNKNOWN")

    if [ "$READY" == "$DESIRED" ] && [ "$HEALTH_STATUS" == "UP" ]; then
      echo "✅ $svc: $READY/$DESIRED replicas READY"
      ((HEALTHY++))
    else
      echo "❌ $svc: $READY/$DESIRED replicas (Health: $HEALTH_STATUS)"
      ((UNHEALTHY++))
    fi
  else
    echo "⚠️  $svc: Deployment not found"
    ((UNHEALTHY++))
  fi
done

echo "================================================"
echo "Summary: $HEALTHY healthy, $UNHEALTHY unhealthy"
[ $UNHEALTHY -eq 0 ] && echo "✅ All services healthy!" || echo "❌ Some services need attention"
EOF

chmod +x /tmp/full-health-check.sh
/tmp/full-health-check.sh
```

**Detailed Service Verification**:

```bash
# 1. Check all pods running (no CrashLoopBackOff)
kubectl get pods -n instacommerce --field-selector=status.phase!=Running | wc -l
# Expected: 0

# 2. Verify deployment image tags match Wave 39 commit hash
kubectl get deployment -n instacommerce -o jsonpath='{range .items[*]}{.metadata.name}{"\t"}{.spec.template.spec.containers[0].image}{"\n"}{end}' | grep -v "9c7cad1" | wc -l
# Expected: 0 (all should have Wave 39 commit hash)

# 3. Check critical service metrics
for svc in payment order fulfillment inventory; do
  echo "=== $svc-service ==="
  kubectl exec -n instacommerce prometheus-pod -- \
    promtool query instant "up{job=\"$svc-service\"}" 2>/dev/null | head -5
done

# 4. Verify database connections healthy
kubectl logs -n instacommerce -l app=payment-service --tail=20 | grep -E "Connection|Pool|Database"

# 5. Check for any pending restarts
kubectl rollout status deployment --all -n instacommerce

# 6. Verify Wave 39 specific features operational
echo "=== Wave 39 Feature Verification ==="
# Admin-Gateway JWT Auth enabled
kubectl logs -n instacommerce -l app=admin-gateway-service --tail=20 | grep "JWT\|Security"

# Feature Flag Cache invalidation working
kubectl logs -n instacommerce -l app=config-feature-flag-service --tail=20 | grep "Cache\|Redis\|Invalidat"

# Reconciliation Engine running
kubectl logs -n instacommerce -l app=reconciliation-engine --tail=20 | grep "Reconciliation\|Ledger\|Schedule"
```

**Success Criteria**:
- ✅ All 28 services READY
- ✅ 0 CrashLoopBackOff pods
- ✅ All image tags show Wave 39 commit (9c7cad1)
- ✅ Database connections healthy
- ✅ Feature-specific operations confirmed

---

### Step 8: Mark Wave 39 COMPLETE in GitHub Releases

**Objective**: Create official GitHub release marking Wave 39 deployment to production.

```bash
# Get Wave 39 commit hash and info
WAVE39_COMMIT=$(git rev-parse HEAD)
WAVE39_DATE=$(date -u +'%Y-%m-%d %H:%M:%S UTC')
PREVIOUS_WAVE_COMMIT="55dc25e"  # Wave 38

# Generate release notes from commits
git log ${PREVIOUS_WAVE_COMMIT}..${WAVE39_COMMIT} --pretty=format:"- %h %s" > /tmp/wave39-release-notes.txt

# Create GitHub release via CLI
gh release create "wave-39-production-deployment" \
  --title "Wave 39 - Production Deployment Complete" \
  --notes "$(cat /tmp/wave39-release-notes.txt)" \
  --target master \
  --draft=false

# Alternative: If gh CLI not available, use curl
cat > /tmp/create-release.json << RELEASE_JSON
{
  "tag_name": "v39.0.0-prod",
  "target_commitish": "master",
  "name": "Wave 39 - Production Deployment",
  "body": "## Wave 39 Production Deployment Complete\n\n**Deployment Date**: $WAVE39_DATE\n**Commit**: $WAVE39_COMMIT\n\n### Key Features\n- Architectural diagrams for 28+ services\n- Build system optimizations\n- P0 compilation errors resolved\n- Jackson classpath conflict fixed\n\n### Services Deployed\n- All 28 microservices (Java 13, Go 12, Python 3)\n- Zero-downtime rollout: Canary 5% → 25% → 50% → 100%\n- SLO targets maintained throughout\n\n### Verification\n✅ All health checks passing\n✅ All SLOs within target\n✅ No rollback required",
  "draft": false,
  "prerelease": false
}
RELEASE_JSON

curl -X POST https://api.github.com/repos/InstaCommerce/InstaCommerce/releases \
  -H "Authorization: token $GITHUB_TOKEN" \
  -H "Content-Type: application/json" \
  -d @/tmp/create-release.json
```

**Post-Release Communications**:

```bash
# Update GitHub milestone
gh issue create \
  --title "🎉 Wave 39 Deployment Complete" \
  --body "✅ Wave 39 has been successfully deployed to production.
  - Commit: $(git rev-parse HEAD)
  - Deployment Time: $(date -u)
  - Status: All services healthy
  - SLOs: All targets met
  - Next: Wave 40 planning kickoff" \
  --label "wave-39,deployment-complete"

# Send Slack notification
curl -X POST $SLACK_WEBHOOK_URL \
  -H 'Content-Type: application/json' \
  -d '{
    "text": "🚀 Wave 39 Deployed to Production",
    "blocks": [
      {
        "type": "section",
        "text": {
          "type": "mrkdwn",
          "text": "*Wave 39 Production Deployment Complete*\n✅ All 28 services healthy\n✅ SLOs maintained\n✅ Zero downtime achieved\nCommit: '$WAVE39_COMMIT'"
        }
      }
    ]
  }'
```

**Success Criteria**:
- ✅ GitHub release created with commit hash
- ✅ Release notes include architectural diagram info
- ✅ Milestone updated to COMPLETE
- ✅ Slack notification sent to platform-eng team

---

## Rollback Plan

### Overview

**Rollback Trigger**: Automatic if any SLO breached for >5 minutes or manual if critical issues detected.

**Estimated Rollback Time**: 5-10 minutes

**Automatic Triggers**:
- Payment service error rate > 1% for 5 minutes
- Payment service p99 latency > 1000ms for 5 minutes
- Order service error rate > 2% for 5 minutes
- Any service CrashLoopBackOff for >2 minutes

---

### Rollback Procedures

#### Rollback Triggers

```bash
# Define rollback threshold monitoring
cat > /tmp/monitor-rollback-triggers.sh << 'EOF'
#!/bin/bash

PROMETHEUS_URL="http://prometheus.instacommerce.io:9090"
ALERT_THRESHOLD=5  # minutes

# Function to check metric and trigger rollback
check_and_rollback() {
  local metric=$1
  local threshold=$2
  local service=$3

  result=$(curl -s "${PROMETHEUS_URL}/api/v1/query?query=${metric}" | \
    jq '.data.result[0].value[1]' 2>/dev/null || echo "0")

  if (( $(echo "$result > $threshold" | bc -l) )); then
    echo "⚠️  ALERT: $service metric exceeded threshold: $result > $threshold"
    echo "🔄 Initiating automatic rollback..."
    rollback_to_wave38
    exit 1
  fi
}

# Monitor every 30 seconds
while true; do
  check_and_rollback 'rate(errors_total{job="payment-service"}[1m])' '0.01' 'Payment'
  check_and_rollback 'histogram_quantile(0.99, rate(http_request_duration_seconds_bucket{job="payment-service"}[1m]))' '1' 'Payment Latency'
  check_and_rollback 'rate(errors_total{job="order-service"}[1m])' '0.02' 'Order'

  sleep 30
done
EOF

chmod +x /tmp/monitor-rollback-triggers.sh
```

#### Immediate Rollback (Manual Trigger)

```bash
# STEP 1: Stop new traffic to Wave 39
kubectl patch virtualservice payment-service -n instacommerce --type merge -p '{
  "spec": {
    "http": [{
      "route": [
        {"destination": {"host": "payment-service-wave38"}, "weight": 100}
      ]
    }]
  }
}'

# STEP 2: Scale Wave 39 to zero
kubectl scale deployment/payment-service -n instacommerce --replicas=0

# STEP 3: Scale Wave 38 back to full (80 replicas for critical services)
kubectl scale deployment/payment-service-wave38 -n instacommerce --replicas=80

# STEP 4: Revert code on deployment nodes
cd /opt/instacommerce/deployment
git revert -n 9c7cad1  # Wave 39 commit
git commit -m "🔄 Revert Wave 39: Manual rollback initiated"

# STEP 5: Redeploy Wave 38
kubectl rollout undo deployment/payment-service -n instacommerce
kubectl rollout status deployment/payment-service -n instacommerce
```

#### Automated Rollback Function

```bash
# Define rollback function in deployment script
rollback_to_wave38() {
  echo "🔄 Rolling back to Wave 38 ($(date))"

  # 1. Immediate: Stop Wave 39 traffic
  for svc in payment order fulfillment; do
    kubectl patch virtualservice $svc-service -n instacommerce --type merge -p '{
      "spec": {
        "http": [{
          "route": [
            {"destination": {"host": "$svc-service-wave38"}, "weight": 100}
          ]
        }]
      }
    }' 2>/dev/null || true
  done

  # 2. Scale Wave 39 to zero, Wave 38 to 100
  kubectl scale deployment --all -n instacommerce --replicas=0 -l wave=wave39
  kubectl scale deployment --all -n instacommerce --replicas=80 -l wave=wave38

  # 3. Wait for Wave 38 pods ready
  kubectl rollout status deployment --all -n instacommerce -l wave=wave38 --timeout=5m

  # 4. Revert Git commit
  cd /opt/instacommerce/deployment
  git revert -n --no-edit 9c7cad1
  git commit -m "🔄 Automatic rollback: Wave 39 → Wave 38 (SLO breach triggered)"
  git push origin master

  # 5. Notify team
  echo "✅ Rollback to Wave 38 complete at $(date)"
  echo "📊 Check SLO metrics in Grafana: https://grafana.instacommerce.io/d/wave38-slos"

  # Send alert
  curl -X POST $SLACK_WEBHOOK_URL \
    -H 'Content-Type: application/json' \
    -d '{"text": "🔄 Wave 39 Rolled Back to Wave 38 - SLO Breach Detected"}'
}
```

#### Rollback for Wave 39 Diagrams & Documentation

**Note**: Diagrams and documentation rollback is non-breaking (no production code impact).

```bash
# If only docs/diagrams rollback needed:
git revert --no-edit d1044ac  # Wave 39 diagrams commit
git push origin master

# Remove Wave 39 documentation
rm -rf docs/architecture/wave39-diagrams.md
rm -rf docs/WAVE39_DELIVERY.md
git add -A
git commit -m "docs: Revert Wave 39 documentation rollback"
git push origin master
```

#### Rollback Documentation Audit

```bash
# Verify rollback was successful
git log --oneline | head -5
# Expected: Revert commit should appear at top

# Confirm services back on Wave 38
kubectl get deployment -n instacommerce -o jsonpath='{.items[*].spec.template.spec.containers[0].image}' | tr ' ' '\n' | grep wave38 | wc -l
# Expected: 28

# Verify SLOs returning to normal
curl -s "http://prometheus.instacommerce.io:9090/api/v1/query?query=up" | jq '.data.result | length'
# Expected: 28+ services up
```

---

## Post-Deployment Validation

### 4.1: Verify All 28 Services Health

```bash
# Quick health check
kubectl get deployments -n instacommerce -o wide | head -30

# Full audit of service health
kubectl exec -n instacommerce prometheus-pod -- \
  promtool query instant 'up' | sort

# Expected: All 28 services showing "1" (up)
```

### 4.2: Verify SLOs Met

```bash
# Payment Service SLO (99.95%, <300ms p99, <0.05% error)
echo "=== Payment Service SLO ==="
curl -s "http://prometheus.instacommerce.io:9090/api/v1/query?query=availability%7Bjob%3D%22payment-service%22%7D" | jq '.data.result[0].value[1]'
# Expected: >= 0.9995

curl -s "http://prometheus.instacommerce.io:9090/api/v1/query?query=p99_latency%7Bjob%3D%22payment-service%22%7D" | jq '.data.result[0].value[1]'
# Expected: <= 0.3 (seconds)

curl -s "http://prometheus.instacommerce.io:9090/api/v1/query?query=error_rate%7Bjob%3D%22payment-service%22%7D" | jq '.data.result[0].value[1]'
# Expected: <= 0.0005

# Order Service SLO (99.9%, <500ms p99, <0.1% error)
echo "=== Order Service SLO ==="
curl -s "http://prometheus.instacommerce.io:9090/api/v1/query?query=availability%7Bjob%3D%22order-service%22%7D" | jq '.data.result[0].value[1]'
# Expected: >= 0.999

# Reconciliation Engine SLO (99.9%, <1s p99, <0.1% error)
echo "=== Reconciliation Engine SLO ==="
curl -s "http://prometheus.instacommerce.io:9090/api/v1/query?query=reconciliation_daily_settlement_time%7B%7D" | jq '.data.result[0].value[1]'
# Expected: < 14400 (4 hours in seconds)
```

### 4.3: Confirm SLO Dashboards Live

```bash
# Navigate to Grafana and verify dashboards are populated
# Dashboard URLs:
# - Payment SLOs: https://grafana.instacommerce.io/d/payment-slos
# - Order SLOs: https://grafana.instacommerce.io/d/order-slos
# - All Services: https://grafana.instacommerce.io/d/wave39-all-services
# - Reconciliation: https://grafana.instacommerce.io/d/reconciliation-engine

# Verify dashboard data freshness
curl -s https://grafana.instacommerce.io/api/datasources/proxy/1/api/v1/targets/metadata?match=%7B%7D | jq '.data | length'
# Expected: >1000 metrics being collected
```

### 4.4: Start Wave 38 Governance Forums

```bash
# Create calendar invitations for Wave 39 governance forums

# 1. Weekly Service Ownership Review (Every Monday, 9 AM UTC)
cat > /tmp/service-ownership-review.ics << 'EOF'
BEGIN:VCALENDAR
VERSION:2.0
PRODID:-//Wave 39 Deployment//NONSGML Event Calendar//EN
BEGIN:VEVENT
DTSTART:20260331T090000Z
DTEND:20260331T093000Z
RRULE:FREQ=WEEKLY;BYDAY=MO
SUMMARY:Wave 39 - Weekly Service Ownership Review
DESCRIPTION:Review service health, incidents, and ownership changes
LOCATION:https://meet.google.com/wave39-ownership
END:VEVENT
END:VCALENDAR
EOF

# 2. Biweekly Contract Review (Every Wednesday, 2 PM UTC)
cat > /tmp/contract-review.ics << 'EOF'
BEGIN:VCALENDAR
VERSION:2.0
PRODID:-//Wave 39 Deployment//NONSGML Event Calendar//EN
BEGIN:VEVENT
DTSTART:20260326T140000Z
DTEND:20260326T142700Z
RRULE:FREQ=WEEKLY;BYDAY=WE;INTERVAL=2
SUMMARY:Wave 39 - Biweekly Contract Review
DESCRIPTION:Review service contracts, API changes, and breaking changes
LOCATION:https://meet.google.com/wave39-contracts
END:VEVENT
END:VCALENDAR
EOF

# 3. Monthly Reliability Review (First Friday of month, 10 AM UTC)
cat > /tmp/reliability-review.ics << 'EOF'
BEGIN:VCALENDAR
VERSION:2.0
PRODID:-//Wave 39 Deployment//NONSGML Event Calendar//EN
BEGIN:VEVENT
DTSTART:20260403T100000Z
DTEND:20260403T110000Z
RRULE:FREQ=MONTHLY;BYDAY=1FR
SUMMARY:Wave 39 - Monthly Reliability Review
DESCRIPTION:Review SLO attainment, incidents, and error budget status
LOCATION:https://meet.google.com/wave39-reliability
END:VEVENT
END:VCALENDAR
EOF

# 4. Quarterly Steering (First week of Q+1)
cat > /tmp/steering.ics << 'EOF'
BEGIN:VCALENDAR
VERSION:2.0
PRODID:-//Wave 39 Deployment//NONSGML Event Calendar//EN
BEGIN:VEVENT
DTSTART:20260407T090000Z
DTEND:20260407T101500Z
RRULE:FREQ=QUARTERLY;BYMONTH=4,7,10,1
SUMMARY:Wave 39 - Quarterly Steering
DESCRIPTION:Q+1 planning, roadmap review, and strategic decisions
LOCATION:https://meet.google.com/wave39-steering
END:VEVENT
END:VCALENDAR
EOF

echo "✅ Governance forums scheduled"
```

---

## Support & Escalation

### Support Matrix

| Issue | Severity | Response Time | Escalation |
|-------|----------|---------------|-----------|
| Service down (payment/order) | P0 | 5 min | VP Engineering |
| SLO breach (>5% error) | P1 | 15 min | Engineering Manager |
| Performance degradation | P2 | 1 hour | Service Owner |
| Documentation issue | P3 | 24 hours | Tech Lead |

### Escalation Contacts

**On-Call Engineer** (Immediate): `#wave39-on-call` Slack channel

**Platform Engineering Manager**: `@platform-eng-manager`

**VP Engineering**: `@vp-engineering`

### Incident Response

```bash
# If deployment fails or SLOs breached:

# 1. Create incident Slack thread
# 2. Run automatic health checks
kubectl get pods -n instacommerce | grep -v "Running"

# 3. Check logs for errors
kubectl logs -n instacommerce --all-containers=true --tail=100 | grep -i error | head -20

# 4. Review metrics
curl -s "http://prometheus.instacommerce.io:9090/api/v1/query?query=rate(errors_total[5m])" | jq '.data.result'

# 5. Initiate rollback if SLO breached
./rollback_to_wave38.sh

# 6. Document incident
cat > /tmp/incident-report.md << 'EOF'
# Incident Report - Wave 39

**Time**: $(date -u)
**Service Affected**: [SERVICE_NAME]
**SLO Breached**: Yes/No
**Resolution Time**: [DURATION]
**Rollback Executed**: Yes/No
**Root Cause**: [TO_BE_DETERMINED]

## Timeline
- T+0: Issue detected
- T+X: Action taken
- T+Y: Resolved

## Postmortem Required
[ ] Yes
[ ] No
EOF
```

### 24-Hour Support Window

**Wave 39 Deployment Support**: 24 hours from deployment start time
- **Monitoring**: Active 24/7 with automated rollback
- **On-Call**: Available via `#wave39-on-call`
- **Escalation**: See support matrix above

---

## Command Reference

### Deployment Commands

```bash
# Pull latest Wave 39 code
git fetch origin master && git checkout master && git pull origin master

# Verify Wave 39 commit
git log --oneline | head -3

# Check CI status
gh workflow view

# Scale to canary (5%)
kubectl scale deployment/payment-service -n instacommerce --replicas=5

# Apply canary VirtualService
kubectl apply -f k8s/canary/virtualservice-wave39-5pct.yaml -n instacommerce

# Monitor deployment
watch -n 5 'kubectl get pods -n instacommerce | grep wave39'

# Full rollout to 100%
kubectl patch virtualservice payment-service -n instacommerce --type merge -p '{"spec":{"http":[{"route":[{"destination":{"host":"payment-service-wave39"},"weight":100}]}]}}'
```

### Monitoring Commands

```bash
# Health checks
kubectl get pods -n instacommerce --field-selector=status.phase!=Running

# SLO metrics
curl -s "http://prometheus.instacommerce.io:9090/api/v1/query?query=up" | jq '.data.result | length'

# Error rates
curl -s "http://prometheus.instacommerce.io:9090/api/v1/query?query=rate(errors_total[5m])" | jq '.data.result'

# Latency
curl -s "http://prometheus.instacommerce.io:9090/api/v1/query?query=histogram_quantile(0.99,rate(http_request_duration_seconds_bucket[5m]))" | jq '.data.result'
```

### Rollback Commands

```bash
# Immediate rollback to Wave 38
git revert -n 9c7cad1
git commit -m "🔄 Revert Wave 39"

# Stop Wave 39 traffic
kubectl patch virtualservice payment-service -n instacommerce --type merge -p '{"spec":{"http":[{"route":[{"destination":{"host":"payment-service-wave38"},"weight":100}]}]}}'

# Scale Wave 38 back up
kubectl scale deployment/payment-service-wave38 -n instacommerce --replicas=80
```

### Logging Commands

```bash
# View Wave 39 pod logs
kubectl logs -l wave=wave39 -n instacommerce --tail=50 -f

# Search for errors
kubectl logs -l wave=wave39 -n instacommerce --all-containers=true | grep -i error

# Specific service logs
kubectl logs -n instacommerce -l app=payment-service --tail=20

# Stream logs
kubectl logs -n instacommerce -l wave=wave39 -f --timestamps=true
```

---

## Appendix: Backup Procedure

### Pre-Deployment Backup

```bash
# Create backup of Wave 38 deployment state
BACKUP_DIR="/data/backups/wave38-$(date +%Y%m%d-%H%M%S)"
mkdir -p $BACKUP_DIR

# Export all Kubernetes manifests
kubectl get all -n instacommerce -o yaml > $BACKUP_DIR/k8s-all.yaml

# Export database schema (PostgreSQL)
pg_dump -h postgres.instacommerce.io -U admin -d instacommerce > $BACKUP_DIR/db-schema.sql

# Export ConfigMaps and Secrets
kubectl get configmap -n instacommerce -o yaml > $BACKUP_DIR/configmaps.yaml
kubectl get secret -n instacommerce -o yaml > $BACKUP_DIR/secrets-encrypted.yaml

# Tar and compress
tar -czf ${BACKUP_DIR}.tar.gz $BACKUP_DIR

# Upload to GCS
gsutil -m cp ${BACKUP_DIR}.tar.gz gs://instacommerce-backups/

echo "✅ Backup complete: ${BACKUP_DIR}.tar.gz"
```

### Post-Deployment Backup Verification

```bash
# Verify backup can be restored
tar -tzf ${BACKUP_DIR}.tar.gz | head -20

# Confirm backup in GCS
gsutil ls gs://instacommerce-backups/ | tail -5
```

---

## Final Approval Checklist

**Before marking Wave 39 deployment COMPLETE**, all items must be verified:

- [ ] Pre-deployment checklist completed
- [ ] Deployment through all stages (5% → 25% → 50% → 100%) successful
- [ ] All 28 services healthy and running Wave 39
- [ ] SLO targets met for all critical services
- [ ] Post-deployment validation complete
- [ ] GitHub release created with commit hash
- [ ] Governance forums scheduled for Wave 39
- [ ] Rollback verified and documented
- [ ] Team notification sent (Slack, email)
- [ ] Wave 40 kickoff meeting scheduled

---

**Deployment Owner**: [NAME] | **Date**: [DATE] | **Status**: READY FOR PRODUCTION

**Sign-off**: `_________________` (Platform Engineering Lead)

---

*Last Updated*: March 21, 2026
*Next Review*: Post-Wave 39 deployment (April 2026)
