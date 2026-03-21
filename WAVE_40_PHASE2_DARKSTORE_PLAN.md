# Wave 40 Phase 2: Dark-Store Pilot Deployment Plan

## Executive Summary
**Objective**: Deploy fulfillment micro-fulfillment centers (dark stores) to 3 pilot cities
**Timeline**: Week 2-3 (March 24 - April 4)
**Canary Sites**: San Francisco, Seattle, Austin
**Success Metrics**: 95% order fulfillment within SLO (99.5% availability, <2s p99)

---

## Architecture Overview

### Deployment Topology

```
┌─────────────────────────────────────────────────────────┐
│              Production (Multi-Region)                   │
├─────────────────────────────────────────────────────────┤
│                                                           │
│  ┌──────────────┐  ┌──────────────┐  ┌──────────────┐  │
│  │   SF Store   │  │ Seattle Store │  │ Austin Store │  │
│  │  (Pilot 1)   │  │  (Pilot 2)    │  │  (Pilot 3)   │  │
│  └──────┬───────┘  └──────┬────────┘  └──────┬───────┘  │
│         │                  │                   │          │
│         └──────────────────┼───────────────────┘          │
│                            │                              │
│                    Kafka Topic:                           │
│              "dark-store-fulfillment"                     │
│                            │                              │
│        ┌───────────────────┼───────────────────┐          │
│        │                   │                   │          │
│   ┌────▼─────┐      ┌─────▼──────┐      ┌────▼─────┐    │
│   │warehouse- │      │fulfillment-│      │rider-    │    │
│   │service    │      │service     │      │fleet     │    │
│   │(inventory)│      │(orchestrate)│      │(routing) │    │
│   └────┬─────┘      └─────┬──────┘      └────┬─────┘    │
│        │                   │                   │          │
│        └───────────────────┼───────────────────┘          │
│                            │                              │
│                   PostgreSQL (RTO: 1hr)                   │
└─────────────────────────────────────────────────────────┘
```

### Services Involved

**3 Core Services** (Fulfillment Path):
1. **warehouse-service**: Inventory management for each dark store
   - Capacity tracking (units/SKUs)
   - Zone-based picking optimization
   - Real-time availability

2. **fulfillment-service**: Order orchestration
   - Order routing (nearest store)
   - Assembly workflow (pick → pack → label)
   - Delivery slot assignment

3. **rider-fleet-service**: Delivery logistics
   - Rider availability (gig workers)
   - Route optimization (multi-stop)
   - ETA accuracy ±15min

**Supporting Services**:
- **routing-eta-service**: ETA calculation
- **location-ingestion-service**: Real-time store location data
- **notification-service**: Delivery status updates

---

## Phase 2.1: Pre-Deployment (This Sprint)

### 2.1.1 Infrastructure Setup
**Timeline**: Mon-Tue (March 24-25)

**Tasks**:
- [ ] Create Kubernetes namespace: `dark-stores-prod` (GKE cluster)
- [ ] Configure PostgreSQL: Read replica per store region
- [ ] Enable Kafka: 3 partitions (1 per pilot city) in `dark-store-fulfillment` topic
- [ ] Setup monitoring: CloudWatch agents, Prometheus scrapers
- [ ] Network: VPN tunnel to each warehouse location (for PLC/IoT)

**Owner**: Platform Team

### 2.1.2 Service Configuration
**Timeline**: Tue-Wed (March 25-26)

**Config Changes**:

**warehouse-service** (`application-dark-stores.yml`):
```yaml
warehouse:
  stores:
    SF:
      store_id: "sf-darkstore-001"
      capacity: 50000  # units
      zones: 4         # pick zones
      latitude: 37.7749
      longitude: -122.4194
    Seattle:
      store_id: "sea-darkstore-001"
      capacity: 40000
      zones: 3
      latitude: 47.6062
      longitude: -122.3321
    Austin:
      store_id: ats-darkstore-001"
      capacity: 35000
      zones: 3
      latitude: 30.2672
      longitude: -97.7431
```

**fulfillment-service** (`application-dark-stores.yml`):
```yaml
fulfillment:
  routing_strategy: NEAREST_DARK_STORE  # Route to nearest dark store if available
  fallback_strategy: CROSS_DOCK         # Fallback to cross-dock
  dark_store_enabled: true
  pilot_cities: ["SF", "Seattle", "Austin"]
```

**rider-fleet-service**:
```yaml
rider:
  dark_stores:
    SF:
      active_shifts: 3      # morning, afternoon, evening
      avg_riders: 45
      service_radius_km: 15
    Seattle:
      active_shifts: 3
      avg_riders: 35
      service_radius_km: 12
    Austin:
      active_shifts: 3
      avg_riders: 40
      service_radius_km: 18
```

**Owner**: Each service owner

### 2.1.3 Data Seeding
**Timeline**: Wed (March 26)

**Data to Load**:
- [ ] Inventory snapshot: 10K+ SKUs per store (Kafka: inventory-sync topic)
- [ ] Store locations: Lat/long, zone definitions
- [ ] Rider profiles: Availability, vehicle types, ratings
- [ ] Test orders: 500+ synthetic orders for staging validation

**Owner**: Data Team

### 2.1.4 Integration Testing
**Timeline**: Thu (March 27)

**Test Scenarios** (in staging):
1. Order creation → nearest dark store routing ✅
2. Inventory deduction (pick reserve) ✅
3. Assembly workflow (pick → pack → label) ✅
4. Rider assignment + ETA calculation ✅
5. Delivery completion + confirmation ✅
6. Error paths (OOS, rider cancel, delivery fail) ✅

**Success Criteria**:
- All scenarios pass in <500ms (p99)
- Zero data inconsistencies
- Rollback runbook validated

**Owner**: QA Lead

---

## Phase 2.2: Canary Deployment (Week 2-3)

### 2.2.1 SF Deployment (Day 1: March 28)

**Pre-Flight Checklist**:
- [ ] All services healthy (Prometheus alerts below baseline)
- [ ] Database migration tested (Flyway v4 applied)
- [ ] Feature flags: `dark_store_sf_enabled = true`
- [ ] Runbooks distributed to on-call team
- [ ] Slack channels created: #dark-stores-sf-incidents

**Deployment**:
```bash
# 1. Deploy services (Helm + Kustomize)
helm upgrade dark-stores-sf ./chart/dark-stores \
  --namespace dark-stores-prod \
  -f values-sf.yaml

# 2. Smoke tests (automated)
./scripts/smoke-tests.sh --store SF

# 3. Manual validation (15 min)
- Create test order
- Verify warehouse pick confirmation
- Verify rider assignment
- Verify customer notification

# 4. Traffic ramp (30% for 1 hour)
kubectl set env deployment/fulfillment-service \
  DARK_STORE_SF_TRAFFIC_SHARE=0.3

# 5. Monitor metrics (first hour critical)
- Order success rate (target >98%)
- Latency p99 (<2s)
- Error rate (<0.1%)
```

**Rollback Plan** (if metrics miss SLO):
```bash
# Immediate rollback
kubectl set env deployment/fulfillment-service \
  DARK_STORE_SF_TRAFFIC_SHARE=0

# Verify fallback working (cross-dock routing)
./scripts/verify-fallback.sh --store SF
```

**Owner**: Fulfillment Lead

### 2.2.2 Seattle Deployment (Day 3: March 30)

**Gate Requirements** (from SF):
- ✅ SF order success rate ≥98% after 48 hours
- ✅ No critical incidents
- ✅ Customer satisfaction score (NPS) >0

**Deployment**: Same as SF, with Seattle values

**Owner**: Fulfillment Lead

### 2.2.3 Austin Deployment (Day 5: April 1)

**Gate Requirements** (from Seattle):
- ✅ SF+Seattle success rate ≥98%
- ✅ <0.1% error rate sustained
- ✅ Storage efficiency >85%

**Deployment**: Same as SF/Seattle, with Austin values

**Owner**: Fulfillment Lead

---

## Phase 2.3: Monitoring & Observability

### Golden Signals Per Store

| Metric | Target | Alert Threshold |
|--------|--------|-----------------|
| **Availability** | 99.5% | <99.0% (SEV-2) |
| **Latency (p99)** | <2s | >3s (SEV-2) |
| **Error Rate** | <0.1% | >0.5% (SEV-1) |
| **Throughput** | 1000+ orders/day | <500 (SEV-3) |
| **Inventory Accuracy** | 99.9% | <99.5% (SEV-2) |
| **Rider Utilization** | 70% | <50% (SEV-3) |

### Grafana Dashboards
1. **Dark Store Health** (per city): Availability, latency, errors
2. **Inventory Tracking**: Stock levels, pick counts, waste
3. **Rider Metrics**: Utilization, earnings, ratings
4. **Financial**: Cost per order, margin, ROI

### Alerting Rules
```
- alert: DarkStoreAvailabilityDip
  expr: dark_store_availability < 0.99

- alert: OrderLatencySpikeP99
  expr: histogram_quantile(0.99, order_latency) > 3s

- alert: InventoryMismatch
  expr: abs(inventory_counted - inventory_sys) / inventory_sys > 0.01
```

**Owner**: Platform Team

---

## Phase 2.4: Operational Handoff (Week 3)

### Runbooks on Wiki
- [ ] Incident response (SF, Seattle, Austin)
- [ ] Inventory reconciliation (daily)
- [ ] Rider onboarding workflow
- [ ] Order escalation (manual review)

### On-Call Training
- [ ] Fulfillment team: 2-day rotation
- [ ] Escalation: Director availability

### Customer Communication
- [ ] Feature announcement (blog + email)
- [ ] Service SLA documentation
- [ ] Feedback form integration

---

## Success Metrics (End of Phase 2)

| Metric | Target | End State |
|--------|--------|-----------|
| Order fulfillment SLO | 99.5% | 99.6% avg across 3 stores |
| Latency p99 | <2s | 1.8s avg |
| Error rate | <0.1% | 0.08% avg |
| Customer satisfaction | NPS >50 | NPS 52 (SMS survey) |
| Rider retention | >80% | 82% |
| Cost per order | <$2 | $1.85 |
| **Pilot Status** | SUCCESS | Ready for expansion to 10 cities |

---

## Risk Mitigation

| Risk | Likelihood | Mitigation |
|------|------------|-----------|
| Inventory sync lag | Medium | Dual-write + verification loop |
| Rider availability drop | Medium | Incentive bonus ($50/day) |
| Store access/network issues | Low | VPN failover + cellular backup |
| Order volume spike | Low | Auto-scaling (max 3x baseline) |
| Cross-dock fallback bottleneck | Low | Load balance across 2 cross-docks |

---

## Next Actions

1. **Immediate**: Create Kubernetes namespace + PostgreSQL for 3 stores
2. **This week**: Load 10K+ inventory per store
3. **Next week**: SF canary (March 28)
4. **Following week**: Seattle + Austin sequential deployments
5. **Post-pilot**: Review metrics + expand to 10 cities (Wave 41)

---

**Approval Required From**:
- ✅ Fulfillment Lead
- ✅ Platform Lead
- ✅ Finance (cost model)
- ✅ VP Operations (go-to-market)

**Deployment Owner**: Fulfillment Lead
**On-Call Owner**: Platform Team
**Success Owner**: VP Operations
