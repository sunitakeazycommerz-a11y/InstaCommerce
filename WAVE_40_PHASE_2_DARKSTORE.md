# Wave 40 Phase 2: Dark-Store Pilot Deployment Plan

## Overview
Canary deployment of dark-store fulfillment to 3 US cities (SF, Seattle, Austin) with SLO validation before full rollout.

---

## Phase 2a: Configuration & Secrets (1 Day)

### 2a.1 Service Configuration
Update `services/fulfillment-service/src/main/resources/application-darkstore.yml`:
```yaml
darkstore:
  enabled: true
  pilot-regions:
    - id: US-CA-SF
      name: "San Francisco"
      center-lat: 37.7749
      center-lon: -122.4194
      coverage-radius-km: 25
      slo-target: "99.5%"
    - id: US-WA-SEA
      name: "Seattle"
      center-lat: 47.6062
      center-lon: -122.3321
      coverage-radius-km: 25
      slo-target: "99.5%"
    - id: US-TX-AUS
      name: "Austin"
      center-lat: 30.2672
      center-lon: -97.7431
      coverage-radius-km: 25
      slo-target: "99.5%"

fulfillment:
  warehouse-assignment-strategy: "nearest-darkstore"
  darkstore-priority: true
  fallback-to-traditional: true
```

### 2a.2 Kubernetes Secrets
Store in `.azure/secrets/darkstore-keys.env`:
```
DARKSTORE_SF_API_KEY=<gcp-secret>
DARKSTORE_SEA_API_KEY=<gcp-secret>
DARKSTORE_AUS_API_KEY=<gcp-secret>
```

### 2a.3 Service Configuration
Update Helm values: `helm/values-darkstore.yaml`
- Replicas: 5 per region (for resilience)
- CPU: 500m, Memory: 1Gi
- Storage: 50Gi (events cache)

---

## Phase 2b: Feature Rollout (2 Days)

### 2b.1 Fulfillment Service Updates
File: `services/fulfillment-service/src/main/java/com/instacommerce/fulfillment/DarkstoreFulfillmentOrchestrator.java`

**Key Methods**:
1. `selectFulfillmentPath()` - Route to darkstore vs traditional warehouse
2. `calculateDeliveryTime()` - Darkstore SLA (2-4 hours vs 24-48h)
3. `assignPickAndPackTasks()` - Micro-fulfillment workflow
4. `publishFulfillmentEvents()` - Kafka events for Order service

### 2b.2 Warehouse Service Updates
File: `services/warehouse-service/src/main/java/com/instacommerce/warehouse/DarkstoreInventoryManager.java`

**Capabilities**:
- Real-time inventory sync from darkstore POS systems
- 500-2000 item SKU subset (high-velocity products)
- Automated replenishment triggers (inventory < threshold)

### 2b.3 Rider Fleet Updates
File: `services/rider-fleet-service/src/main/java/com/instacommerce/rider/DarkstoreDispatchManager.java`

**Features**:
- Micro-fulfillment optimized routing
- Geo-fenced delivery radius (<25km)
- ETA calculation: <30min to 120min (vs 60-180min traditional)

---

## Phase 2c: Deployment Strategy (3 Days)

### 2c.1 Staging Validation (Day 1)
1. Deploy to dev Kubernetes cluster
2. Run integration tests with mocked darkstore APIs
3. Load test: 1000 orders/hour per region
4. Validate SLO targets:
   - ✅ Order fulfillment latency: <4 hours p99
   - ✅ Error rate: <0.1%
   - ✅ ETA accuracy: ±15 min

### 2c.2 Canary Rollout (Days 2-3)
**Week 2 Timeline**:
- Day 1: Deploy to SF (traffic: 5%)
- Day 2: Scale to SF (20%), Seattle (5%)
- Day 3: Full SF (100%), Seattle (20%), Austin (5%)

**Success Criteria per Region**:
- ✅ P99 latency <4 hours
- ✅ Error rate <0.1%
- ✅ SLO compliance >99.5%
- ✅ Zero data loss events
- ✅ ETA accuracy within ±15 min

### 2c.3 Production Metrics
**Prometheus metrics to monitor**:
- `darkstore_fulfillment_latency_seconds` (histogram, p99)
- `darkstore_order_count_total` (counter)
- `darkstore_error_rate` (gauge)
- `darkstore_inventory_sync_lag_seconds` (gauge)
- `darkstore_eta_accuracy_percent` (gauge)

---

## Phase 2d: Rollback Plan

If SLO misses or critical issues:
1. Disable darkstore routing: `darkstore.enabled = false`
2. All orders → traditional fulfillment
3. Data preserved in PostgreSQL (audit trail)
4. RCA meeting: Within 2 hours

---

## Success Metrics (EOW Week 2)

| Metric | Target | Validation |
|--------|--------|-----------|
| Fulfillment latency (p99) | <4 hours | Prometheus dashboard |
| Error rate | <0.1% | CloudWatch logs |
| ETA accuracy | ±15 min | Order history |
| SLO compliance | >99.5% | Grafana burn rate |
| Order volume | 10K+ orders/city | Admin dashboard |

**Gate for Week 3 Expansion**: All 3 cities >99.5% SLO for 24 hours continuous

---

## Files to Update

1. ✅ `services/fulfillment-service/src/main/resources/application-darkstore.yml` (NEW)
2. ✅ `services/fulfillment-service/src/main/java/com/instacommerce/fulfillment/DarkstoreFulfillmentOrchestrator.java` (NEW)
3. ✅ `services/warehouse-service/src/main/java/com/instacommerce/warehouse/DarkstoreInventoryManager.java` (NEW)
4. ✅ `services/rider-fleet-service/src/main/java/com/instacommerce/rider/DarkstoreDispatchManager.java` (NEW)
5. ✅ `helm/values-darkstore.yaml` (NEW)
6. ✅ `.github/workflows/ci.yml` (approval for darkstore feature flag)

---

## Ownership & Schedule

| Task | Owner | Start | End | Status |
|------|-------|-------|-----|--------|
| Config + Secrets | Platform | Mon | Mon | 📋 TODO |
| Service implementation | Fulfillment | Mon | Tue | 📋 TODO |
| Staging validation | QA | Tue | Tue | 📋 TODO |
| SF canary (Week 2) | Fulfillment + Ops | Wed | Wed | 📋 TODO |
| Seattle + Austin | Fulfillment + Ops | Thu-Fri | Fri | 📋 TODO |

---

## Next: Phase 3 (Week 3)
Observability: Grafana SLO dashboards + 50+ alert rules for all 28 services
