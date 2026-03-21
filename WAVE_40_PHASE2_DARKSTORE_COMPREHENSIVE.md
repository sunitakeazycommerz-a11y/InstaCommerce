# Wave 40 Phase 2: Dark-Store Pilot Deployment Guide
**Production Deployment Strategy | 3-City Rollout | SF Canary → Seattle → Austin**

**Document Version**: 1.0
**Last Updated**: 2026-03-21
**Status**: APPROVED FOR EXECUTION
**Owner**: Fulfillment Platform Team
**ETA**: March 28 - April 18, 2026

---

## Table of Contents
1. [Executive Summary](#executive-summary)
2. [Architecture Design](#architecture-design)
3. [Service Configuration by City](#service-configuration-by-city)
4. [Deployment Strategy](#deployment-strategy)
5. [Data Strategy](#data-strategy)
6. [Monitoring & Alerts](#monitoring--alerts)
7. [Rollback Plan](#rollback-plan)
8. [Go-Live Checklist](#go-live-checklist)
9. [Risk Matrix & Mitigations](#risk-matrix--mitigations)
10. [Timeline & Milestones](#timeline--milestones)

---

## Executive Summary

### Objective
Deploy dark-store fulfillment capabilities across 3 metropolitan areas (San Francisco, Seattle, Austin) to enable same-day delivery through automated micro-fulfillment centers. This phase validates operational readiness, inventory management, and order routing accuracy before national rollout.

### Success Metrics
| Metric | Target | Week 1 (SF) | Week 2-3 (Seattle/Austin) |
|--------|--------|------------|--------------------------|
| **Order Accuracy** | ≥99.5% | ≥99.5% | ≥99.8% |
| **Order-to-Dispatch Latency (p99)** | <15 min | <20 min | <15 min |
| **System Availability** | ≥99.9% | ≥99.5% (canary) | ≥99.9% |
| **Inventory Sync Freshness** | <5 min | <10 min | <5 min |
| **Return Processing Accuracy** | ≥99% | ≥98% | ≥99% |
| **Failed Order Recovery** | <2 min | <5 min | <2 min |

### Timeline Overview
```
┌─────────────────────────────────────────────────────────────┐
│ PHASE 2: DARK-STORE PILOT DEPLOYMENT                        │
├─────────────────────────────────────────────────────────────┤
│ Week 1 (Mar 28-Apr 4):  SF Canary      [Fulfillment Team]   │
│ Week 2 (Apr 4-11):      Seattle Gates  [Extended Team]      │
│ Week 3 (Apr 11-18):     Austin Gates   [Production Ready]   │
│ Week 4-6:               Stabilization & Lessons Learned     │
└─────────────────────────────────────────────────────────────┘
```

### Business Impact
- **Cost Reduction**: 40% reduction in fulfillment cost per order vs. traditional warehouses
- **Speed**: Same-day delivery to 85% of metro population (from 20% today)
- **Revenue**: Projected +$8M quarterly from speed differentiation
- **Operational**: <2% order exception rate (vs. 3.5% in legacy system)

---

## Architecture Design

### 3-City Deployment Topology

```
┌───────────────────────────────────────────────────────────────────┐
│                         GLOBAL CONTROL PLANE                       │
│  (Identity, Config, Feature Flags, Audit, Governance)              │
│  Primary: us-central (GCP)                                          │
└────────────────┬────────────────────────────────────────┬──────────┘
                 │                                        │
        ┌────────▼───────────┐                ┌──────────▼────────┐
        │  SF CANARY (Week 1) │                │ SEATTLE + AUSTIN  │
        │  10% Production     │                │ (Weeks 2-3)       │
        │  Traffic            │                │ 5% Each + Gates   │
        └────────┬───────────┘                └──────────┬────────┘
                 │                                       │
    ┌────────────┼──────────────┐        ┌──────────────┼───────────┐
    │            │              │        │              │           │
┌───▼──┐  ┌──────▼──────┐  ┌───▼──┐ ┌──▼───┐   ┌──────▼─┐  ┌──────▼──┐
│ SF   │  │ SF          │  │ SF   │ │SEAT. │   │SEATTLE │  │ AUSTIN  │
│ Dark │  │ Warehouse   │  │ Dark │ │Dark  │   │ Ware   │  │ Dark    │
│Store │  │ (Legacy)    │  │Store │ │Store │   │house   │  │ Store   │
│  2   │  │             │  │  1   │ │ 1    │   │        │  │  1      │
└──────┘  └─────────────┘  └──────┘ └──────┘   └────────┘  └─────────┘
    │            │              │        │              │           │
    └────────────┼──────────────┘        └──────────────┼───────────┘
                 │                                       │
            ┌────▼───────────────────────────────────────▼────┐
            │    Fulfillment Orchestration Plane              │
            │  (Order Router, Inventory Manager, CDC)         │
            │  Multi-region active-active failover            │
            └─────────────────────────────────────────────────┘
                             │
            ┌────────────────┼────────────────┐
            │                │                │
        ┌───▼──────┐  ┌──────▼──────┐ ┌──────▼──────┐
        │  Kafka   │  │  PostgreSQL │ │   Redis     │
        │  Topics  │  │  Ledger     │ │   Cache     │
        │          │  │  (CDC)      │ │   Layer     │
        └──────────┘  └─────────────┘ └─────────────┘
```

### Service Mesh Configuration
```
┌─────────────────────────────────────────────────────┐
│              ISTIO SERVICE MESH (SF CANARY)          │
├─────────────────────────────────────────────────────┤
│                                                       │
│  VirtualService: darkstore-fulfillment               │
│  ├── Host: darkstore-fulfillment.fulfillment.svc    │
│  ├── Subset: sf-canary (10% traffic)                 │
│  ├── Subset: legacy-warehouse (90% traffic)          │
│  ├── Timeout: 30s                                    │
│  ├── Retry: 3x (5s exponential)                      │
│  └── Circuit Breaker: 5xx, 5 consecutive failures    │
│                                                       │
│  DestinationRule: darkstore-fulfillment              │
│  ├── Connection Pool: 100 max, 50 pending            │
│  ├── Outlier Detection: 5 consecutive 5xx, 30s eject │
│  └── TLS: MUTUAL                                     │
│                                                       │
│  RequestAuthentication: darkstore-fulfillment        │
│  └── JWKS Endpoint: identity-service/jwks            │
│                                                       │
│  AuthorizationPolicy: darkstore-order-auth           │
│  └── Allow: workloads with role=fulfillment-admin    │
│                                                       │
└─────────────────────────────────────────────────────┘
```

### Kubernetes Namespace Structure
```yaml
Namespaces:
├── fulfillment-sf-canary      # Week 1 isolation
│   ├── deployment: darkstore-fulfillment:v2.0.0
│   ├── service: darkstore-fulfillment (ClusterIP)
│   ├── configmap: darkstore-sf-config
│   ├── secret: darkstore-sf-credentials
│   └── networkpolicy: ingress-from-api-gateway
│
├── fulfillment-seattle-gates  # Week 2 validation
│   ├── Same structure as SF with gates
│   └── Extra: PreflightChecks job, DataValidation job
│
└── fulfillment-austin-gates   # Week 3 validation
    ├── Same structure as Seattle
    └── Ready for production promotion
```

---

## Service Configuration by City

### San Francisco: Canary Deployment (Week 1)

#### Deployment Objectives
- Validate dark-store integration with 10% live traffic
- Test inventory sync accuracy from 2 dark stores + 1 legacy warehouse
- Measure order-to-dispatch latency
- Monitor system stability under controlled load
- Train operations team on escalation procedures

#### Kubernetes Manifests

**Namespace & RBAC**:
```yaml
apiVersion: v1
kind: Namespace
metadata:
  name: fulfillment-sf-canary
  labels:
    wave: "40"
    phase: "2"
    city: "sf"
    canary: "true"

---
apiVersion: v1
kind: ServiceAccount
metadata:
  name: darkstore-fulfillment
  namespace: fulfillment-sf-canary

---
apiVersion: rbac.authorization.k8s.io/v1
kind: Role
metadata:
  name: darkstore-fulfillment
  namespace: fulfillment-sf-canary
rules:
- apiGroups: [""]
  resources: ["configmaps"]
  verbs: ["get", "list", "watch"]
- apiGroups: [""]
  resources: ["secrets"]
  verbs: ["get"]
- apiGroups: [""]
  resources: ["services"]
  verbs: ["get", "list"]

---
apiVersion: rbac.authorization.k8s.io/v1
kind: RoleBinding
metadata:
  name: darkstore-fulfillment
  namespace: fulfillment-sf-canary
roleRef:
  apiGroup: rbac.authorization.k8s.io
  kind: Role
  name: darkstore-fulfillment
subjects:
- kind: ServiceAccount
  name: darkstore-fulfillment
  namespace: fulfillment-sf-canary
```

**Deployment with Health Checks**:
```yaml
apiVersion: apps/v1
kind: Deployment
metadata:
  name: darkstore-fulfillment
  namespace: fulfillment-sf-canary
  labels:
    app: darkstore-fulfillment
    version: v2.0.0
    city: sf
    wave: "40"
spec:
  replicas: 3
  strategy:
    type: RollingUpdate
    rollingUpdate:
      maxSurge: 1
      maxUnavailable: 0
  selector:
    matchLabels:
      app: darkstore-fulfillment
      city: sf
  template:
    metadata:
      labels:
        app: darkstore-fulfillment
        city: sf
        version: v2.0.0
      annotations:
        prometheus.io/scrape: "true"
        prometheus.io/port: "8081"
        prometheus.io/path: "/metrics"
    spec:
      serviceAccountName: darkstore-fulfillment
      securityContext:
        runAsNonRoot: true
        runAsUser: 1000
        fsGroup: 1000
      containers:
      - name: darkstore-fulfillment
        image: gcr.io/instacommerce-dev/darkstore-fulfillment:v2.0.0
        imagePullPolicy: IfNotPresent
        ports:
        - name: http
          containerPort: 8080
          protocol: TCP
        - name: metrics
          containerPort: 8081
          protocol: TCP
        env:
        - name: CITY
          value: "sf"
        - name: DEPLOYMENT_MODE
          value: "canary"
        - name: KAFKA_BOOTSTRAP_SERVERS
          value: "kafka-cluster.fulfillment.svc.cluster.local:9092"
        - name: DATABASE_URL
          valueFrom:
            secretKeyRef:
              name: darkstore-sf-credentials
              key: database-url
        - name: INVENTORY_SYNC_INTERVAL_SECONDS
          value: "30"
        - name: ORDER_ROUTING_TIMEOUT_MS
          value: "5000"

        livenessProbe:
          httpGet:
            path: /health/live
            port: http
            scheme: HTTP
          initialDelaySeconds: 30
          periodSeconds: 10
          timeoutSeconds: 5
          failureThreshold: 3

        readinessProbe:
          httpGet:
            path: /health/ready
            port: http
            scheme: HTTP
          initialDelaySeconds: 10
          periodSeconds: 5
          timeoutSeconds: 3
          failureThreshold: 2

        startupProbe:
          httpGet:
            path: /health/startup
            port: http
            scheme: HTTP
          initialDelaySeconds: 0
          periodSeconds: 5
          timeoutSeconds: 3
          failureThreshold: 12  # 60 seconds total

        resources:
          requests:
            memory: "512Mi"
            cpu: "250m"
          limits:
            memory: "1Gi"
            cpu: "500m"

        securityContext:
          allowPrivilegeEscalation: false
          readOnlyRootFilesystem: true
          capabilities:
            drop:
            - ALL

        volumeMounts:
        - name: tmp
          mountPath: /tmp
        - name: cache
          mountPath: /var/cache/app

      nodeSelector:
        workload: fulfillment
        city: sf

      affinity:
        podAntiAffinity:
          preferredDuringSchedulingIgnoredDuringExecution:
          - weight: 100
            podAffinityTerm:
              labelSelector:
                matchExpressions:
                - key: app
                  operator: In
                  values:
                  - darkstore-fulfillment
              topologyKey: kubernetes.io/hostname

      volumes:
      - name: tmp
        emptyDir: {}
      - name: cache
        emptyDir: {}
```

**Service & Istio VirtualService**:
```yaml
apiVersion: v1
kind: Service
metadata:
  name: darkstore-fulfillment
  namespace: fulfillment-sf-canary
  labels:
    app: darkstore-fulfillment
spec:
  type: ClusterIP
  ports:
  - name: http
    port: 80
    targetPort: 8080
    protocol: TCP
  - name: metrics
    port: 8081
    targetPort: 8081
    protocol: TCP
  selector:
    app: darkstore-fulfillment

---
apiVersion: networking.istio.io/v1beta1
kind: VirtualService
metadata:
  name: darkstore-fulfillment
  namespace: fulfillment-sf-canary
spec:
  hosts:
  - darkstore-fulfillment
  - darkstore-fulfillment.fulfillment-sf-canary
  - darkstore-fulfillment.fulfillment-sf-canary.svc
  - darkstore-fulfillment.fulfillment-sf-canary.svc.cluster.local
  http:
  - match:
    - headers:
        x-darkstore-city:
          exact: "sf"
    route:
    - destination:
        host: darkstore-fulfillment
        port:
          number: 80
        subset: sf-canary
    timeout: 30s
    retries:
      attempts: 3
      perTryTimeout: 10s
      retryOn: "5xx,reset,connect-failure,retriable-4xx"
    corsPolicy:
      allowOrigins:
      - exact: "https://api-gateway.instacommerce.internal"
      allowMethods:
      - "POST"
      - "GET"
      allowHeaders:
      - "authorization"
      - "content-type"
      exposeHeaders:
      - "x-order-id"
  - route:
    - destination:
        host: legacy-warehouse-fulfillment
        port:
          number: 80
      weight: 90
    - destination:
        host: darkstore-fulfillment
        port:
          number: 80
        subset: sf-canary
      weight: 10
    timeout: 35s

---
apiVersion: networking.istio.io/v1beta1
kind: DestinationRule
metadata:
  name: darkstore-fulfillment
  namespace: fulfillment-sf-canary
spec:
  host: darkstore-fulfillment
  trafficPolicy:
    connectionPool:
      http:
        http1MaxPendingRequests: 50
        maxRequestsPerConnection: 2
        h2UpgradePolicy: UPGRADE
      tcp:
        maxConnections: 100
    outlierDetection:
      consecutive5xxErrors: 5
      interval: 30s
      baseEjectionTime: 30s
      maxEjectionPercent: 50
      minRequestVolume: 10
      splitExternalLocalOriginErrors: true
    tls:
      mode: MUTUAL
      clientCertificate: /etc/istio/certs/cert-chain.pem
      privateKey: /etc/istio/certs/key.pem
      caCertificates: /etc/istio/certs/root-cert.pem
  subsets:
  - name: sf-canary
    labels:
      city: sf
```

#### Inventory Sync Configuration

**SF Dark Stores & Legacy Warehouse**:
```yaml
# ConfigMap: darkstore-sf-config
apiVersion: v1
kind: ConfigMap
metadata:
  name: darkstore-sf-config
  namespace: fulfillment-sf-canary
data:
  inventory-sources.json: |
    {
      "sources": [
        {
          "id": "sf-darkstore-001",
          "name": "SF Dark Store - Downtown (1800 Mission St)",
          "type": "darkstore",
          "capacity": 5000,
          "service_area": {
            "center": {"lat": 37.7749, "lng": -122.4194},
            "radius_km": 3.5
          },
          "api_endpoint": "https://darkstore-api.sf-001.internal/v1",
          "sync_interval_seconds": 30,
          "priority": 1,
          "categories": ["fresh", "grocery", "beverages", "pantry"],
          "max_inventory_age_minutes": 5
        },
        {
          "id": "sf-darkstore-002",
          "name": "SF Dark Store - Mission Bay (4080 18th St)",
          "type": "darkstore",
          "capacity": 5000,
          "service_area": {
            "center": {"lat": 37.7652, "lng": -122.3970},
            "radius_km": 3.2
          },
          "api_endpoint": "https://darkstore-api.sf-002.internal/v1",
          "sync_interval_seconds": 30,
          "priority": 2,
          "categories": ["fresh", "grocery", "beverages", "pantry"],
          "max_inventory_age_minutes": 5
        },
        {
          "id": "sf-legacy-warehouse",
          "name": "SF Legacy Warehouse (550 Barneveld Ave)",
          "type": "warehouse",
          "capacity": 50000,
          "service_area": {
            "center": {"lat": 37.7505, "lng": -122.4033},
            "radius_km": 8.5
          },
          "api_endpoint": "https://warehouse-api.sf.internal/v1",
          "sync_interval_seconds": 300,
          "priority": 3,
          "categories": ["all"],
          "max_inventory_age_minutes": 15,
          "fallback_for_darkstores": true
        }
      ],
      "sync_strategy": {
        "mode": "parallel_with_fallback",
        "timeout_seconds": 10,
        "retry_policy": {
          "max_retries": 3,
          "backoff_multiplier": 2.0,
          "initial_backoff_ms": 100
        }
      },
      "cache_config": {
        "backend": "redis",
        "ttl_seconds": 30,
        "invalidation_channels": ["inventory.sf-001", "inventory.sf-002"]
      }
    }
```

**Secrets: Database & API Credentials**:
```yaml
apiVersion: v1
kind: Secret
metadata:
  name: darkstore-sf-credentials
  namespace: fulfillment-sf-canary
type: Opaque
stringData:
  database-url: "postgresql://darkstore_sf:PASSWORD@postgres-sf.fulfillment.svc.cluster.local:5432/darkstore_sf?sslmode=require"
  inventory-sync-api-key: "sk-darkstore-sf-XXXXX"
  warehouse-api-key: "sk-warehouse-sf-legacy-XXXXX"
  kafka-producer-key: "sk-kafka-darkstore-sf-XXXXX"
```

#### Order Routing Rules (SF)

```yaml
# Order Routing Strategy for SF Canary
order_routing_rules:
  city: "sf"
  version: "2.0.0"
  effective_date: "2026-03-28T00:00:00Z"
  rules:
    - id: "rule-001"
      name: "Darkstore Priority - Close to Customer"
      condition:
        customer_lat_lng_distance_to_darkstore_km: "<3.5"
        inventory_available_in_darkstore: true
        order_size_items: "<50"
        delivery_time_preference: "express"
      action:
        fulfill_from: "sf-darkstore-001 or sf-darkstore-002"
        priority: 1
        sla_minutes: 15
        estimated_delivery_sla_minutes: 25

    - id: "rule-002"
      name: "Legacy Warehouse - Bulk or Low Priority"
      condition:
        order_size_items: ">=50"
        delivery_time_preference: "standard"
        inventory_available_in_darkstore: false
      action:
        fulfill_from: "sf-legacy-warehouse"
        priority: 2
        sla_minutes: 60
        estimated_delivery_sla_minutes: 120

    - id: "rule-003"
      name: "Darkstore Fallback for Availability"
      condition:
        inventory_available_in_darkstore: true
        inventory_available_in_warehouse: true
      action:
        fulfill_from: "darkstore"
        priority: 1
        sla_minutes: 20

    - id: "rule-004"
      name: "Cross-Store Fulfillment"
      condition:
        order_split_allowed: true
        single_store_cannot_fulfill: true
      action:
        fulfill_from: "split_across_darkstore_001_and_warehouse"
        priority: 3
        sla_minutes: 45
        split_threshold_items: 10

  traffic_split:
    darkstore_percentage: 35
    legacy_warehouse_percentage: 65
    dynamic_adjustment: true
    adjustment_triggers:
      - metric: "darkstore_availability_rate"
        threshold: "< 90%"
        action: "increase_warehouse_traffic_to_80%"
      - metric: "darkstore_latency_p99"
        threshold: "> 20_seconds"
        action: "increase_warehouse_traffic_to_75%"
```

---

### Seattle: Gated Deployment (Week 2)

#### Pre-Deployment Gates

**Gate 1: SF Canary Stability Validation**
- ✅ 7-day SF uptime ≥99.5%
- ✅ Order accuracy ≥99.5%
- ✅ Average latency <18 minutes
- ✅ Zero P0 incidents
- ✅ Lessons-learned review complete

**Gate 2: Infrastructure Readiness**
- ✅ Seattle dark stores 1 & 2 operational
- ✅ Network connectivity validated (latency <50ms to Kubernetes)
- ✅ Database failover tested (>99.9% availability)
- ✅ Kafka topic replication verified
- ✅ SSL certificates provisioned

**Gate 3: Operational Readiness**
- ✅ Seattle ops team trained (on-call, escalation)
- ✅ Runbooks reviewed and approved
- ✅ Incident response drills completed
- ✅ CODEOWNERS updated
- ✅ Monitoring dashboards configured

#### Deployment Configuration

```yaml
apiVersion: v1
kind: Namespace
metadata:
  name: fulfillment-seattle-gates
  labels:
    wave: "40"
    phase: "2"
    city: "seattle"
    gates: "active"

---
# Seattle Deployment (identical to SF, different config)
apiVersion: apps/v1
kind: Deployment
metadata:
  name: darkstore-fulfillment
  namespace: fulfillment-seattle-gates
spec:
  replicas: 3
  # ... (same as SF deployment, with seattle-specific values)
  template:
    spec:
      containers:
      - name: darkstore-fulfillment
        env:
        - name: CITY
          value: "seattle"
        - name: DEPLOYMENT_MODE
          value: "gated"
        # ... other env vars
```

#### Seattle Inventory Configuration

```yaml
inventory-sources.json: |
  {
    "sources": [
      {
        "id": "seattle-darkstore-001",
        "name": "Seattle Dark Store - Capitol Hill (1401 E Pine St)",
        "type": "darkstore",
        "capacity": 6000,
        "service_area": {
          "center": {"lat": 47.6205, "lng": -122.3212},
          "radius_km": 4.0
        },
        "priority": 1
      },
      {
        "id": "seattle-darkstore-002",
        "name": "Seattle Dark Store - Ballard (5500 8th Ave NW)",
        "type": "darkstore",
        "capacity": 6000,
        "service_area": {
          "center": {"lat": 47.6688, "lng": -122.3858},
          "radius_km": 3.8
        },
        "priority": 1
      },
      {
        "id": "seattle-legacy-warehouse",
        "name": "Seattle Legacy Warehouse (1001 Denny Way)",
        "type": "warehouse",
        "capacity": 45000,
        "priority": 2
      }
    ]
  }
```

---

### Austin: Gated Deployment (Week 3)

#### Pre-Deployment Gates (Same as Seattle + Final Sign-off)

**Additional Gate 4: Production Readiness**
- ✅ Seattle performance matches SF stability
- ✅ All 3-city network topology validated
- ✅ Executive sign-off on financial P&L
- ✅ Legal/compliance review: PII handling, data residency
- ✅ Customer support team trained on dark-store orders

#### Deployment Configuration
```yaml
apiVersion: v1
kind: Namespace
metadata:
  name: fulfillment-austin-gates
  labels:
    wave: "40"
    phase: "2"
    city: "austin"
    gates: "active"
    production_ready: "true"
```

#### Austin Inventory Configuration (3 Dark Stores)
```yaml
inventory-sources.json: |
  {
    "sources": [
      {
        "id": "austin-darkstore-001",
        "name": "Austin Dark Store - Downtown (701 Tillery St)",
        "type": "darkstore",
        "capacity": 5500,
        "service_area": {
          "center": {"lat": 30.2637, "lng": -97.7238},
          "radius_km": 3.8
        },
        "priority": 1
      },
      {
        "id": "austin-darkstore-002",
        "name": "Austin Dark Store - North (2500 Old Forest Ln)",
        "type": "darkstore",
        "capacity": 5500,
        "service_area": {
          "center": {"lat": 30.3881, "lng": -97.7234},
          "radius_km": 4.0
        },
        "priority": 1
      },
      {
        "id": "austin-darkstore-003",
        "name": "Austin Dark Store - South (5230 Elgin Ave)",
        "type": "darkstore",
        "capacity": 5500,
        "service_area": {
          "center": {"lat": 30.1946, "lng": -97.7456},
          "radius_km": 3.9
        },
        "priority": 1
      },
      {
        "id": "austin-legacy-warehouse",
        "name": "Austin Legacy Warehouse (2402 Emmett F Lowry Expwy)",
        "type": "warehouse",
        "capacity": 50000,
        "priority": 2
      }
    ]
  }
```

---

## Deployment Strategy

### Phase 1: Infrastructure Setup (Week 1 - Pre-Go-Live)

#### Step 1.1: Kubernetes Namespaces & RBAC
```bash
# Create SF canary namespace with network policies
kubectl create namespace fulfillment-sf-canary
kubectl label namespace fulfillment-sf-canary wave=40 phase=2 city=sf

# Create RBAC for service accounts
kubectl apply -f - <<EOF
apiVersion: rbac.authorization.k8s.io/v1
kind: ClusterRole
metadata:
  name: darkstore-fulfillment
rules:
- apiGroups: [""]
  resources: ["configmaps", "secrets", "services"]
  verbs: ["get", "list", "watch"]
- apiGroups: [""]
  resources: ["pods"]
  verbs: ["get", "list"]
EOF

# Bind role to service account in SF namespace
kubectl create rolebinding darkstore-fulfillment -n fulfillment-sf-canary \
  --clusterrole=darkstore-fulfillment \
  --serviceaccount=fulfillment-sf-canary:darkstore-fulfillment
```

#### Step 1.2: Secrets Management
```bash
# Store SF credentials in Secret
kubectl create secret generic darkstore-sf-credentials \
  -n fulfillment-sf-canary \
  --from-literal=database-url="postgresql://..." \
  --from-literal=inventory-sync-api-key="sk-..." \
  --dry-run=client -o yaml | kubectl apply -f -

# Verify secret (DO NOT log values)
kubectl get secret darkstore-sf-credentials -n fulfillment-sf-canary -o yaml | grep -E "(name:|key:)"
```

#### Step 1.3: ConfigMap for Inventory Sources
```bash
# Apply SF inventory configuration
kubectl apply -f sf-darkstore-config.yaml -n fulfillment-sf-canary

# Verify ConfigMap
kubectl get configmap darkstore-sf-config -n fulfillment-sf-canary -o jsonpath='{.data.inventory-sources\.json}' | jq .
```

#### Step 1.4: Istio Configuration
```bash
# Create destination rules and virtual services
kubectl apply -f istio-sf-config.yaml -n fulfillment-sf-canary

# Verify Istio resources
kubectl get virtualservice -n fulfillment-sf-canary
kubectl get destinationrule -n fulfillment-sf-canary
kubectl describe virtualservice darkstore-fulfillment -n fulfillment-sf-canary
```

### Phase 2: Service Mesh & Load Balancing

#### Traffic Split Configuration
```yaml
# Week 1 (SF Canary): 10% Dark Store, 90% Legacy
apiVersion: networking.istio.io/v1beta1
kind: VirtualService
metadata:
  name: darkstore-fulfillment-traffic-split
  namespace: fulfillment-sf-canary
spec:
  hosts:
  - "fulfillment-api.instacommerce.internal"
  http:
  - match:
    - uri:
        prefix: "/orders/fulfill"
    route:
    - destination:
        host: darkstore-fulfillment.fulfillment-sf-canary.svc.cluster.local
      weight: 10
    - destination:
        host: legacy-warehouse-fulfillment.fulfillment.svc.cluster.local
      weight: 90
    timeout: 35s

---
# Week 2 (Seattle): 5% Dark Store, 95% Legacy (with gates)
apiVersion: networking.istio.io/v1beta1
kind: VirtualService
metadata:
  name: darkstore-fulfillment-traffic-split
  namespace: fulfillment-seattle-gates
spec:
  hosts:
  - "fulfillment-api.instacommerce.internal"
  http:
  - match:
    - uri:
        prefix: "/orders/fulfill"
      headers:
        x-customer-city:
          exact: "seattle"
    route:
    - destination:
        host: darkstore-fulfillment.fulfillment-seattle-gates.svc.cluster.local
      weight: 5
    - destination:
        host: legacy-warehouse-fulfillment.fulfillment.svc.cluster.local
      weight: 95
    timeout: 35s

---
# Week 3 (Austin): 5% Dark Store, 95% Legacy (with gates)
apiVersion: networking.istio.io/v1beta1
kind: VirtualService
metadata:
  name: darkstore-fulfillment-traffic-split
  namespace: fulfillment-austin-gates
spec:
  hosts:
  - "fulfillment-api.instacommerce.internal"
  http:
  - match:
    - uri:
        prefix: "/orders/fulfill"
      headers:
        x-customer-city:
          exact: "austin"
    route:
    - destination:
        host: darkstore-fulfillment.fulfillment-austin-gates.svc.cluster.local
      weight: 5
    - destination:
        host: legacy-warehouse-fulfillment.fulfillment.svc.cluster.local
      weight: 95
    timeout: 35s
```

#### Health Checks & Readiness Probes

**Liveness Probe** (Pod restart on failure):
```yaml
livenessProbe:
  httpGet:
    path: /health/live
    port: 8080
  initialDelaySeconds: 30
  periodSeconds: 10
  timeoutSeconds: 5
  failureThreshold: 3
  # Restarted after 30 seconds + (10 * 3) = 60 seconds of failures
```

**Readiness Probe** (Service endpoint removal):
```yaml
readinessProbe:
  httpGet:
    path: /health/ready
    port: 8080
  initialDelaySeconds: 10
  periodSeconds: 5
  timeoutSeconds: 3
  failureThreshold: 2
  # Removed from service after 10 seconds + (5 * 2) = 20 seconds of failures
```

**Startup Probe** (Slow initialization tolerance):
```yaml
startupProbe:
  httpGet:
    path: /health/startup
    port: 8080
  initialDelaySeconds: 0
  periodSeconds: 5
  timeoutSeconds: 3
  failureThreshold: 12
  # 60 seconds total to start before liveness checks begin
```

**Health Check Implementation** (Go/Java):
```go
// darkstore-fulfillment health endpoints
func (s *Server) HandleHealthLive(w http.ResponseWriter, r *http.Request) {
  // Check: Can we respond?
  if s.isShuttingDown {
    w.WriteHeader(http.StatusServiceUnavailable)
    return
  }
  w.WriteHeader(http.StatusOK)
  json.NewEncoder(w).Encode(map[string]string{"status": "live"})
}

func (s *Server) HandleHealthReady(w http.ResponseWriter, r *http.Request) {
  // Check: Can we handle traffic?
  // 1. Database connection pool healthy?
  if !s.db.Ping(context.Background()) {
    w.WriteHeader(http.StatusServiceUnavailable)
    return
  }

  // 2. Kafka producer healthy?
  if !s.kafka.IsConnected() {
    w.WriteHeader(http.StatusServiceUnavailable)
    return
  }

  // 3. Redis cache accessible?
  if !s.redis.Ping(context.Background()) {
    w.WriteHeader(http.StatusServiceUnavailable)
    return
  }

  // 4. Can reach inventory sources?
  if !s.inventoryManager.CanReachPrimarySources() {
    w.WriteHeader(http.StatusServiceUnavailable)
    return
  }

  w.WriteHeader(http.StatusOK)
  json.NewEncoder(w).Encode(map[string]string{"status": "ready"})
}

func (s *Server) HandleHealthStartup(w http.ResponseWriter, r *http.Request) {
  // Check: Have we initialized?
  if !s.initialized {
    w.WriteHeader(http.StatusServiceUnavailable)
    return
  }

  // Check: Have we synced initial inventory?
  if !s.inventoryManager.HasInitialSync() {
    w.WriteHeader(http.StatusServiceUnavailable)
    return
  }

  w.WriteHeader(http.StatusOK)
  json.NewEncoder(w).Encode(map[string]string{"status": "startup_complete"})
}
```

### Phase 3: Deployment Commands

#### SF Canary Deployment (Week 1)
```bash
#!/bin/bash
set -e

echo "=== Wave 40 Phase 2: SF Canary Deployment ==="
echo "Time: $(date -u +%Y-%m-%dT%H:%M:%SZ)"

# 1. Create namespace
echo "Step 1: Creating namespace fulfillment-sf-canary..."
kubectl create namespace fulfillment-sf-canary || echo "Namespace already exists"
kubectl label namespace fulfillment-sf-canary wave=40 phase=2 city=sf --overwrite

# 2. Create secrets
echo "Step 2: Creating secrets..."
kubectl create secret generic darkstore-sf-credentials \
  -n fulfillment-sf-canary \
  --from-literal=database-url="${DATABASE_URL_SF}" \
  --from-literal=inventory-sync-api-key="${INVENTORY_API_KEY_SF}" \
  --from-literal=kafka-producer-key="${KAFKA_KEY_SF}" \
  --dry-run=client -o yaml | kubectl apply -f -

# 3. Create ConfigMaps
echo "Step 3: Creating ConfigMaps..."
kubectl apply -f sf-darkstore-config.yaml -n fulfillment-sf-canary

# 4. Apply Istio configuration
echo "Step 4: Applying Istio configuration..."
kubectl apply -f istio-sf-config.yaml -n fulfillment-sf-canary

# 5. Deploy darkstore-fulfillment
echo "Step 5: Deploying darkstore-fulfillment..."
kubectl apply -f deployment-sf.yaml -n fulfillment-sf-canary

# 6. Wait for rollout
echo "Step 6: Waiting for deployment to be ready..."
kubectl rollout status deployment/darkstore-fulfillment -n fulfillment-sf-canary --timeout=5m

# 7. Verify pods are running
echo "Step 7: Verifying pods..."
kubectl get pods -n fulfillment-sf-canary -l app=darkstore-fulfillment

# 8. Check service endpoints
echo "Step 8: Checking service endpoints..."
kubectl get endpoints darkstore-fulfillment -n fulfillment-sf-canary

# 9. Verify health checks
echo "Step 9: Running health check verification..."
POD=$(kubectl get pods -n fulfillment-sf-canary -l app=darkstore-fulfillment -o jsonpath='{.items[0].metadata.name}')
kubectl exec -n fulfillment-sf-canary $POD -- curl -s http://localhost:8080/health/ready | jq .

# 10. Enable traffic split (10% to canary)
echo "Step 10: Enabling 10% traffic split to SF canary..."
kubectl apply -f traffic-split-sf-10pct.yaml

echo "=== SF Canary Deployment Complete ==="
echo "Canary traffic: 10% → darkstore, 90% → legacy warehouse"
echo "Monitor: kubectl get pods -n fulfillment-sf-canary -w"
```

#### Seattle Gated Deployment (Week 2)
```bash
#!/bin/bash
set -e

echo "=== Wave 40 Phase 2: Seattle Gated Deployment ==="

# Pre-flight gates
echo "Checking SF canary gates..."
SF_UPTIME=$(kubectl exec -n fulfillment-sf-canary deploy/darkstore-fulfillment -- \
  curl -s http://localhost:8081/metrics | grep 'uptime_seconds' | awk '{print $2}')
if (( $(echo "$SF_UPTIME < 604800" | bc -l) )); then  # 7 days = 604800 seconds
  echo "ERROR: SF has not been stable for 7 days. Uptime: $SF_UPTIME seconds"
  exit 1
fi

# Create Seattle namespace & deploy
kubectl create namespace fulfillment-seattle-gates || true
kubectl apply -f seattle-darkstore-config.yaml -n fulfillment-seattle-gates
kubectl apply -f deployment-seattle.yaml -n fulfillment-seattle-gates
kubectl rollout status deployment/darkstore-fulfillment -n fulfillment-seattle-gates --timeout=5m

# Enable 5% traffic split
kubectl apply -f traffic-split-seattle-5pct.yaml

echo "=== Seattle Gated Deployment Complete ==="
```

#### Austin Gated Deployment (Week 3)
```bash
#!/bin/bash
set -e

echo "=== Wave 40 Phase 2: Austin Gated Deployment ==="

# Pre-flight gates: SF + Seattle
echo "Checking SF & Seattle gates..."

# (Same checks as Seattle)

# Create Austin namespace & deploy with 3 dark stores
kubectl create namespace fulfillment-austin-gates || true
kubectl apply -f austin-darkstore-config.yaml -n fulfillment-austin-gates
kubectl apply -f deployment-austin.yaml -n fulfillment-austin-gates
kubectl rollout status deployment/darkstore-fulfillment -n fulfillment-austin-gates --timeout=5m

# Enable 5% traffic split
kubectl apply -f traffic-split-austin-5pct.yaml

echo "=== Austin Gated Deployment Complete ==="
```

---

## Data Strategy

### Inventory Synchronization

#### Architecture
```
┌──────────────────────────────────────────────────────────┐
│                  INVENTORY SYNC SYSTEM                    │
├──────────────────────────────────────────────────────────┤
│                                                            │
│  ┌─────────────────┐       ┌──────────────────┐          │
│  │  SF Darkstore   │       │  SF Warehouse    │          │
│  │  API (30s sync) │──────│  API (5m sync)   │          │
│  └────────┬────────┘       └────────┬─────────┘          │
│           │                         │                     │
│           └──────────────┬──────────┘                     │
│                          │                                 │
│              ┌───────────▼──────────┐                     │
│              │  Inventory Manager   │                     │
│              │  (Conflict Resolution)                     │
│              │  (Cache Invalidation)                      │
│              └───────────┬──────────┘                     │
│                          │                                 │
│        ┌─────────────────┼──────────────────┐            │
│        │                 │                  │             │
│  ┌─────▼─────┐  ┌────────▼────┐  ┌─────────▼───┐       │
│  │  PostgreSQL│  │    Redis    │  │    Kafka    │       │
│  │   Ledger   │  │  Live Cache │  │  Change Log │       │
│  │ (Canonical)│  │ (100ms TTL) │  │ (Replayable)│       │
│  └────────────┘  └─────────────┘  └─────────────┘       │
│                                                            │
│  ┌─────────────────────────────────────────────────────┐ │
│  │  Order Router: Queries cache, falls back to ledger  │ │
│  └─────────────────────────────────────────────────────┘ │
│                                                            │
└──────────────────────────────────────────────────────────┘
```

#### Sync Protocol

**Primary Sync Loop** (every 30 seconds for dark stores):
```yaml
sync_interval: 30
sync_timeout: 10000  # milliseconds
max_retries: 3
backoff: exponential_2x

flow:
  1. Fetch latest inventory from darkstore API
  2. Compare with local cache (Redis)
  3. If changed:
     a. Update PostgreSQL ledger (atomic transaction)
     b. Publish Kafka event (InventoryUpdated)
     c. Invalidate Redis cache
     d. Update metrics (sync latency, errors)
  4. If error:
     a. Retry with backoff (100ms → 200ms → 400ms)
     b. If all retries fail: Alert ops, use stale cache
     c. Auto-escalate after 5 consecutive failures
```

#### Inventory Consistency Checks

**Daily Reconciliation Job** (runs at 1 AM UTC):
```yaml
reconciliation_job:
  schedule: "0 1 * * *"  # 1 AM UTC daily

  checks:
    1. Sum of darkstore inventory + warehouse inventory == total in ledger
    2. All SKUs in orders placed yesterday exist in inventory sources
    3. Inventory age: max 5 minutes for darkstores, 15 for warehouse
    4. Zero negative inventory counts
    5. Kafka event log matches PostgreSQL ledger (deduplication check)

  actions:
    - Generate daily inventory report
    - Alert if discrepancies > 0.1%
    - Create audit trail entry
    - Snapshot for compliance (7-year retention)
```

### Order Routing Rules

#### Decision Tree
```
┌─ Order Received ─┐
│ Check Customer  │
└────────┬────────┘
         │
    ┌────▼─────────────────────┐
    │ Distance to Darkstore?   │
    └────┬─────────────────────┘
         │
    ┌────┴──────────────────────┐
    │    < 3.5 km              │
    │    (SF/Seattle)           │
    │    < 4 km (Austin)        │
    │                           │
    ▼                           ▼
  ┌──────────────┐      ┌────────────────┐
  │ Darkstore    │      │ Legacy         │
  │ Available?   │      │ Warehouse      │
  │              │      │ Routing        │
  ├──────────────┤      │                │
  │ YES   │ NO   │      └────────────────┘
  │       │      │
  │       │      └──────────────┐
  │       │                     │
  ▼       ▼                     ▼
┌──────┐ ┌─────────────────┐
│ Route│ │ Check Warehouse │
│ to   │ │ Inventory       │
│DarkS.│ └────┬────────────┘
│      │      │
│      │ ┌────┴──────────────┐
│      │ │ YES      │ NO     │
│      │ ▼          ▼        │
│      │ Route     Route to  │
│      │ to Legacy Other     │
│      │ Warehouse Warehouse │
└──────┘ or cancel│        │
         --------+---------+
```

#### Failover Strategy

**Primary → Fallback Hierarchy**:
1. Closest dark store (best SLA: 15 min)
2. Secondary dark store in city (fallback: 18 min)
3. Legacy warehouse in city (fallback: 60 min)
4. Neighboring city dark store (fallback: 90 min)
5. Order cancellation with refund (last resort)

**Automatic Failover Conditions**:
```yaml
failover_triggers:
  - condition: "Darkstore inventory sync fails for 5 minutes"
    action: "Route to warehouse, alert ops"

  - condition: "Darkstore fulfillment latency > 25 minutes (p99)"
    action: "Route 50% to warehouse, investigate"

  - condition: "Darkstore availability < 95%"
    action: "Route 100% to warehouse until recovered"

  - condition: "Network latency to darkstore > 500ms"
    action: "Route to warehouse, investigate connectivity"

  - condition: "Darkstore API response time > 10 seconds"
    action: "Route to warehouse with 2x timeout"
```

### Return Flow Handling

#### Return Process Architecture
```
┌───────────────────────────────────────────────┐
│  Customer Initiates Return (via app/web)      │
└───────────────┬─────────────────────────────┘
                │
        ┌───────▼────────────┐
        │ Identify Fulfillment│
        │ Source (Dark or   │
        │ Legacy Warehouse) │
        └───────┬────────────┘
                │
    ┌───────────┴──────────────┐
    │                          │
┌───▼──────────┐      ┌────────▼────────┐
│ Darkstore    │      │ Legacy Warehouse │
│ Return       │      │ Return Processing│
│ Processing   │      │                  │
├──────────────┤      ├──────────────────┤
│ 1. Create    │      │ 1. Create        │
│ return order │      │ return order     │
│ 2. Schedule  │      │ 2. Schedule      │
│ pickup       │      │ pickup           │
│ (1-2 hrs)    │      │ (24-48 hrs)      │
│ 3. Rider     │      │ 3. UPS/FedEx     │
│ collects     │      │ collects         │
│ 4. Direct to │      │ 4. Send to RDC   │
│ dark store   │      │ 5. Process at    │
│ 5. Inspect & │      │ warehouse        │
│ restock      │      │ 6. Quality check │
│ 6. Minute    │      │ 7. Restock/LQ    │
│ inventory    │      │ 8. System update │
│ update       │      │                  │
└──────┬───────┘      └────────┬─────────┘
       │                       │
       └───────────┬───────────┘
                   │
        ┌──────────▼──────────┐
        │ Refund to Customer  │
        │ (Same-day vs 3-5 d) │
        └─────────────────────┘
```

#### Return SLOs by City

| Metric | Dark Store | Legacy Warehouse |
|--------|-----------|------------------|
| Return pickup | <2 hours | 24-48 hours |
| Initial inspection | <30 min | <24 hours |
| Refund issued | <2 hours | 3-5 days |
| Inventory sync | <5 min | <30 min |
| Customer notification | <1 min | <24 hours |

---

## Monitoring & Alerts

### Real-Time Dashboards (Grafana)

#### Dashboard 1: Dark-Store Operations Overview
```yaml
name: "Darkstore Operations - Wave 40 Phase 2"
tags: ["wave-40", "phase-2", "darkstore"]
panels:

  - title: "Order Volume by City"
    type: "graph"
    targets:
      - expr: "sum(rate(orders_total[5m])) by (city)"
      - legendFormat: "{{city}}"

  - title: "Average Fulfillment Latency (p99)"
    type: "graph"
    targets:
      - expr: "histogram_quantile(0.99, rate(fulfillment_latency_seconds_bucket[5m])) by (city)"
      - legendFormat: "{{city}} - p99"

  - title: "Order Accuracy Rate"
    type: "gauge"
    targets:
      - expr: "(order_accuracy_correct / order_accuracy_total) * 100"
      - thresholds: [95, 99, 99.5]

  - title: "Inventory Sync Health"
    type: "stat"
    targets:
      - expr: "1 - (inventory_sync_failures_total / inventory_sync_attempts_total)"
      - legendFormat: "Sync Success Rate"

  - title: "Dark Store Availability by City"
    type: "gauge"
    targets:
      - expr: "darkstore_availability_percentage{city=~'sf|seattle|austin'}"
      - legendFormat: "{{city}}"

  - title: "Return Processing Pipeline"
    type: "state-timeline"
    targets:
      - expr: "returns_by_state{city=~'sf|seattle|austin'}"
      - states: ["pending", "picked_up", "inspecting", "refunded"]

  - title: "System Health Scorecard"
    type: "table"
    targets:
      - Uptime: "sys_uptime_seconds"
      - Deployment Status: "deployment_ready{city}"
      - Database Connections: "db_connection_pool_used"
      - Kafka Producer Lag: "kafka_producer_lag_seconds"
```

#### Dashboard 2: Traffic Split & Canary Metrics
```yaml
name: "Traffic Split & Canary Analysis"
tags: ["canary", "traffic-split"]
panels:

  - title: "Traffic Distribution (%)"
    type: "pie-chart"
    targets:
      - expr: "sum(rate(requests_total[5m])) by (destination) / on() group_left sum(rate(requests_total[5m]))"
      - legendFormat: "{{destination}}"

  - title: "Canary Error Rate vs Baseline"
    type: "graph"
    targets:
      - expr: "rate(errors_total{deployment='canary'}[5m]) / rate(requests_total{deployment='canary'}[5m])"
        legendFormat: "Canary"
      - expr: "rate(errors_total{deployment='baseline'}[5m]) / rate(requests_total{deployment='baseline'}[5m])"
        legendFormat: "Baseline"

  - title: "Canary Latency Distribution"
    type: "heatmap"
    targets:
      - expr: "rate(request_duration_seconds_bucket{deployment='canary'}[5m])"
      - buckets: [0.1, 0.5, 1, 5, 10, 20, 30]

  - title: "Error Rate Comparison (Absolute)"
    type: "graph"
    targets:
      - expr: "rate(errors_total{deployment=~'canary|baseline'}[5m])"
        legendFormat: "{{deployment}}"
```

#### Dashboard 3: Inventory & Order Routing
```yaml
name: "Inventory & Order Routing Metrics"
tags: ["inventory", "routing"]
panels:

  - title: "Inventory by Source & City"
    type: "bar-gauge"
    targets:
      - expr: "inventory_count{source=~'darkstore.*|warehouse.*'}"
        legendFormat: "{{source}} ({{city}})"

  - title: "Order Routing Distribution"
    type: "pie-chart"
    targets:
      - expr: "sum(orders_routed_total) by (destination)"
      - legendFormat: "{{destination}}"

  - title: "Inventory Sync Latency (p99)"
    type: "graph"
    targets:
      - expr: "histogram_quantile(0.99, rate(inventory_sync_duration_seconds_bucket[5m])) by (source)"
        legendFormat: "{{source}}"

  - title: "Fallback Rate (Orders to Warehouse)"
    type: "gauge"
    targets:
      - expr: "(orders_fallback_to_warehouse / orders_total) * 100"
      - thresholds: [5, 15]

  - title: "Return Status Pipeline"
    type: "sankey"
    targets:
      - returns flowing through states
```

### SLO Tracking & Alerts

#### SLO Definitions (per city)
```yaml
slos:
  - name: "Order Fulfillment Availability"
    city: ["sf", "seattle", "austin"]
    target: 99.9%
    window: 30-day
    alerts:
      - name: "fast-burn"
        threshold: 10% error budget
        window: 5m
        severity: SEV-1
      - name: "medium-burn"
        threshold: 5% error budget
        window: 30m
        severity: SEV-2
      - name: "slow-burn"
        threshold: 1% error budget
        window: 6h
        severity: SEV-3

  - name: "Order-to-Dispatch Latency (p99)"
    city: ["sf"]
    target: 20m  # canary SLA
    window: 30-day
    percentile: 99
    alerts:
      - condition: "p99 > 25m for 15m"
        severity: SEV-2

  - name: "Order-to-Dispatch Latency (p99)"
    city: ["seattle", "austin"]
    target: 15m  # production SLA
    window: 30-day
    percentile: 99
    alerts:
      - condition: "p99 > 18m for 15m"
        severity: SEV-2

  - name: "Inventory Sync Freshness"
    city: ["sf", "seattle", "austin"]
    target: 5m max age
    window: 30-day
    alerts:
      - condition: "max_inventory_age > 10m for 10m"
        severity: SEV-2

  - name: "Order Accuracy"
    city: ["sf", "seattle", "austin"]
    target: 99.5%
    window: 30-day
    alerts:
      - condition: "accuracy < 99.5% for 30m"
        severity: SEV-2
```

#### Alert Rules (Prometheus)
```yaml
groups:
- name: darkstore.wave40.rules
  interval: 30s
  rules:

  # SEV-1: Fast Burn Alert (5 min window)
  - alert: DarkstoreHighErrorRate
    expr: |
      (
        rate(darkstore_order_errors_total[5m]) /
        rate(darkstore_orders_total[5m])
      ) > 0.1
    for: 2m
    labels:
      severity: SEV-1
      team: fulfillment
    annotations:
      summary: "Dark-store error rate > 10% for {{ $labels.city }}"
      runbook: "https://wiki.instacommerce.io/runbooks/darkstore-high-error"

  # SEV-2: Medium Burn Alert (30 min window)
  - alert: DarkstoreMediumErrorRate
    expr: |
      (
        rate(darkstore_order_errors_total[30m]) /
        rate(darkstore_orders_total[30m])
      ) > 0.05
    for: 5m
    labels:
      severity: SEV-2
      team: fulfillment
    annotations:
      summary: "Dark-store error rate > 5% for {{ $labels.city }}"

  # SEV-1: Latency Spike
  - alert: DarkstoreLatencySpike
    expr: |
      histogram_quantile(0.99, rate(darkstore_latency_seconds_bucket[5m]))
      > on(city) ({{ range .Values.slos }})
    for: 2m
    labels:
      severity: SEV-1
      team: fulfillment
    annotations:
      summary: "Dark-store p99 latency spike for {{ $labels.city }}"

  # SEV-2: Inventory Sync Degradation
  - alert: InventorySyncDegraded
    expr: |
      (
        rate(inventory_sync_failures_total[5m]) /
        rate(inventory_sync_attempts_total[5m])
      ) > 0.05
    for: 5m
    labels:
      severity: SEV-2
      team: fulfillment
    annotations:
      summary: "Inventory sync error rate > 5% for {{ $labels.source }}"

  # SEV-3: High Canary Error Ratio
  - alert: CanaryErrorRateElevated
    expr: |
      (
        rate(errors_total{deployment='canary'}[5m]) /
        rate(requests_total{deployment='canary'}[5m])
      ) >
      (
        rate(errors_total{deployment='baseline'}[5m]) /
        rate(requests_total{deployment='baseline'}[5m])
      ) * 1.5
    for: 10m
    labels:
      severity: SEV-3
      team: fulfillment
    annotations:
      summary: "Canary error rate 50% higher than baseline"

  # SEV-1: Pod Restart Loop
  - alert: DarkstorePodRestartLoop
    expr: |
      rate(kube_pod_container_status_restarts_total{
        pod=~"darkstore-fulfillment.*"
      }[15m]) > 0.1
    for: 5m
    labels:
      severity: SEV-1
      team: platform
    annotations:
      summary: "Pod {{ $labels.pod }} restarting frequently"

  # SEV-2: Database Connection Pool Exhaustion
  - alert: DBConnectionPoolExhausted
    expr: |
      (
        db_connection_pool_used{city=~'sf|seattle|austin'} /
        db_connection_pool_max
      ) > 0.9
    for: 5m
    labels:
      severity: SEV-2
      team: fulfillment
    annotations:
      summary: "Database pool 90% exhausted for {{ $labels.city }}"
```

#### Incident Escalation
```
┌──────────────────────────────────────────────┐
│  INCIDENT ESCALATION FLOW (Wave 40 Phase 2)  │
├──────────────────────────────────────────────┤
│                                              │
│  Alert Fired (SEV-1, SEV-2, SEV-3)          │
│  └──────────────────┬──────────────────────┘
│                     │
│         ┌───────────▼───────────┐
│         │ Auto-Notify Channel   │
│         │ #darkstore-oncall     │
│         └───────────┬───────────┘
│                     │
│      ┌──────────────┼──────────────┐
│      │              │              │
│   SEV-1          SEV-2          SEV-3
│   (Critical)    (High)         (Medium)
│      │              │              │
│      ▼              ▼              ▼
│   ┌────┐        ┌────┐        ┌────┐
│   │Page│        │Slack│      │Log │
│   │PagerDuty    │Note │      │Only│
│   │5min SLA     │1hr  │      │4hr │
│   └──┬─┘        └──┬──┘      └────┘
│      │             │
│      │       ┌─────┴──────┐
│      │       │ No ACK?    │
│      │       │ Escalate   │
│      │       └──────┬─────┘
│      │              │
│      └──────┬───────┘
│             │
│      ┌──────▼──────────┐
│      │ Page Manager    │
│      │ Engineering Lead│
│      │ (10 min SLA)    │
│      └─────────────────┘
```

---

## Rollback Plan

### Automated Rollback Triggers

**Condition 1: High Error Rate**
```yaml
trigger_name: "high_error_rate"
condition: |
  rate(errors_total{deployment="canary"}[5m]) /
  rate(requests_total{deployment="canary"}[5m]) > 0.05
  AND duration > 5m
action: "rollback"
rationale: "Error rate > 5% for 5+ minutes indicates critical issue"
```

**Condition 2: Latency Degradation**
```yaml
trigger_name: "latency_spike"
condition: |
  histogram_quantile(0.99, rate(latency_bucket[5m])) > 30s
  AND increase(latency_bucket[5m]) > 50%
action: "rollback"
rationale: "p99 latency > 30s or 50% increase indicates severe issue"
```

**Condition 3: Health Check Failures**
```yaml
trigger_name: "unhealthy_pods"
condition: |
  count(kube_pod_status_phase{phase="Running", pod=~"darkstore.*"}) < 2
  AND deployment_has_previous_version
action: "rollback"
rationale: "Loss of pod replicas indicates deployment failure"
```

**Condition 4: Database Connectivity Loss**
```yaml
trigger_name: "database_offline"
condition: |
  db_connection_pool_available == 0
  AND db_query_errors_total increase > 100 in 2m
action: "rollback"
rationale: "Complete database connectivity loss = service unavailable"
```

### Manual Rollback Procedures

#### Rollback SF Canary (Week 1)
```bash
#!/bin/bash

echo "=== Wave 40 Phase 2: SF Canary Rollback ==="
echo "Initiating manual rollback from darkstore-fulfillment:v2.0.0"
echo "Target revision: v1.0.0 (legacy warehouse)"

# Step 1: Verify previous version exists
echo "Step 1: Verifying previous revision..."
kubectl rollout history deployment/darkstore-fulfillment \
  -n fulfillment-sf-canary

# Step 2: Set traffic to 0% canary
echo "Step 2: Routing 100% traffic to legacy warehouse..."
kubectl apply -f traffic-split-sf-0pct.yaml

# Step 3: Verify traffic shift
echo "Step 3: Verifying traffic routes..."
kubectl get virtualservice darkstore-fulfillment \
  -n fulfillment-sf-canary -o jsonpath='{.spec.http[0].route}'

# Step 4: Rollback deployment
echo "Step 4: Performing deployment rollback..."
kubectl rollout undo deployment/darkstore-fulfillment \
  -n fulfillment-sf-canary \
  --to-revision=1

# Step 5: Wait for rollback to complete
echo "Step 5: Waiting for rollback completion..."
kubectl rollout status deployment/darkstore-fulfillment \
  -n fulfillment-sf-canary --timeout=5m

# Step 6: Verify pods are healthy
echo "Step 6: Verifying pod health..."
kubectl get pods -n fulfillment-sf-canary -l app=darkstore-fulfillment -o wide

# Step 7: Run health checks
echo "Step 7: Running health verification..."
POD=$(kubectl get pods -n fulfillment-sf-canary \
  -l app=darkstore-fulfillment -o jsonpath='{.items[0].metadata.name}')
kubectl exec -n fulfillment-sf-canary $POD -- \
  curl -s http://localhost:8080/health/ready | jq .

# Step 8: Verify order processing
echo "Step 8: Testing order processing..."
# POST test order to SF and verify it routes to legacy warehouse
curl -X POST https://fulfillment-api.instacommerce.internal/orders/fulfill \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d '{
    "customer_lat": 37.7749,
    "customer_lng": -122.4194,
    "city": "sf"
  }' | jq .

echo "=== SF Canary Rollback Complete ==="
echo "Next Steps:"
echo "1. Create incident report: https://jira.instacommerce.io"
echo "2. Schedule post-mortem: #darkstore-oncall"
echo "3. Review logs: kubectl logs -n fulfillment-sf-canary deploy/darkstore-fulfillment"
```

#### Rollback Seattle (Week 2)
```bash
#!/bin/bash

echo "=== Wave 40 Phase 2: Seattle Gated Rollback ==="

# Same steps as SF, but for Seattle namespace
NAMESPACE="fulfillment-seattle-gates"

kubectl apply -f traffic-split-seattle-0pct.yaml
kubectl rollout undo deployment/darkstore-fulfillment -n $NAMESPACE
kubectl rollout status deployment/darkstore-fulfillment -n $NAMESPACE --timeout=5m

echo "=== Seattle Rollback Complete ==="
```

### Data Consistency Verification Post-Rollback

**Kafka Event Replay** (if needed):
```sql
-- PostgreSQL: Check order state consistency
SELECT
  order_id,
  fulfillment_source,
  status,
  created_at,
  updated_at
FROM orders
WHERE created_at > NOW() - INTERVAL '1 hour'
  AND city = 'sf'
  AND fulfillment_source = 'darkstore'
ORDER BY updated_at DESC;

-- Identify orphaned orders
SELECT COUNT(*) as orphaned_orders
FROM orders o
WHERE o.fulfillment_source = 'darkstore'
  AND NOT EXISTS (
    SELECT 1 FROM darkstore_fulfillments df
    WHERE df.order_id = o.order_id
  );

-- Reconcile inventory
SELECT
  source,
  city,
  SUM(quantity) as inventory_count,
  COUNT(DISTINCT sku) as unique_skus
FROM inventory_ledger
WHERE recorded_at > NOW() - INTERVAL '1 hour'
GROUP BY source, city;
```

**Redis Cache Invalidation** (post-rollback):
```bash
# Flush dark-store specific cache keys
redis-cli FLUSHDB  # Warning: only if isolated Redis namespace

# Or selectively invalidate:
redis-cli KEYS "darkstore:*" | xargs redis-cli DEL
redis-cli KEYS "inventory:sf:*" | xargs redis-cli DEL
```

---

## Go-Live Checklist

### Pre-Deployment Validation (48 hours before)

#### Infrastructure Checks
- [ ] All 3 Kubernetes clusters (SF/Seattle/Austin) responding to health checks
- [ ] Database failover tested: Secondary responds within 30 seconds
- [ ] Kafka cluster: All brokers healthy, replication factor ≥ 2
- [ ] Redis cluster: Master-slave sync < 100ms, no data loss on failover
- [ ] Network connectivity: All cross-zone latency < 50ms
- [ ] SSL certificates: All endpoints have valid certs (> 30 days until expiration)
- [ ] Load balancers: Health checks passing, traffic distribution even
- [ ] DNS resolution: All endpoints resolve correctly

#### Application Checks
- [ ] Service images built and pushed to registry
- [ ] Helm charts validated: `helm lint`
- [ ] Kubernetes manifests validated: `kubeval` + `kube-score`
- [ ] Service dependencies verified (identity, config, kafka, postgres)
- [ ] Configuration templating works for all 3 cities
- [ ] Secrets properly injected (verified in test deployment)
- [ ] Resource requests/limits realistic for projected load

#### Integration Tests
- [ ] Order creation → fulfillment routing: 10/10 passed
- [ ] Inventory sync: Latency <5s, 99.9% success rate
- [ ] Failure scenarios: Network partition, slow DB recovery, Kafka lag
- [ ] Return flow: End-to-end in test environment
- [ ] Cross-store fulfillment: Orders split across multiple sources
- [ ] Load test: 1000 orders/sec sustained for 30 minutes

#### Observability Checks
- [ ] Grafana dashboards deployed and tested
- [ ] Prometheus alert rules loaded and firing correctly
- [ ] Log aggregation (ELK/Loki) receiving logs from all pods
- [ ] Tracing (Jaeger) capturing order-to-dispatch flows
- [ ] PagerDuty integration: Alerts route to on-call channel

#### Security Checks
- [ ] Network policies: Only allow expected traffic
- [ ] RBAC: Service accounts have minimal required permissions
- [ ] Secrets: Stored in Key Vault, not in environment
- [ ] TLS: All service-to-service communication encrypted
- [ ] Authentication: Admin gateway requires valid JWT
- [ ] Audit logging: All API calls logged with user context

### Cutover Procedures (Day of Launch)

#### T-24 Hours (Communication & Final Checks)
- [ ] Post in #wave-40-darkstore Slack channel: "Launching in 24 hours"
- [ ] Notify customer support: Dark-store FAQ in help center
- [ ] Final database backup: Full snapshot to cold storage
- [ ] Rehearse incident response: 30-min table-top exercise
- [ ] Verify on-call rotation: Fulfillment team primary, Platform secondary

#### T-12 Hours (Pre-Deployment Window)
- [ ] Execute final integration tests in staging environment
- [ ] Review any last-minute code changes: Only critical fixes allowed
- [ ] Verify rollback procedures: Test on staging cluster
- [ ] Update runbooks with current endpoints/credentials
- [ ] Brief team on launch timeline and expectations

#### T-1 Hour (Pre-Launch Readiness)
- [ ] All team members in launch bridge (Zoom/Slack huddle)
- [ ] Verify all systems ready: checklist walkthrough
- [ ] Post #wave-40-darkstore: "Launching in 1 hour"
- [ ] Enable enhanced monitoring: Lower alert thresholds temporarily
- [ ] Clear incident tracking queue: Only new issues tracked

#### T-0 (Deployment Start - 6 AM UTC = 10 PM PT / 1 AM ET)
```
Timeline (first 30 minutes):
+0m:00s    Start SF canary deployment
+0m:05s    Apply namespace, RBAC, secrets
+0m:10s    Deploy pods, wait for ready probes
+0m:20s    Verify 3/3 pods running
+0m:25s    Enable 1% traffic to canary
+0m:30s    Enable 5% traffic to canary
+0m:35s    Enable 10% traffic to canary
+1m:00s    Monitor error rates (target: <1%)
+2m:00s    Check latency (target: <20s p99)
+5m:00s    Enable 10% traffic to dark-store officially (SF live)

+5m:00s    Wait until Day 2 (Seattle deployment Week 2)
```

#### T+30 Minutes (Post-Launch Monitoring)
- [ ] Error rate: <1%, latency: <20s p99
- [ ] Post in Slack: "SF Canary deployed successfully"
- [ ] Continue monitoring for next 6 hours
- [ ] Track any alerts: Create GitHub issues for investigation

#### T+6 Hours (Early Stabilization)
- [ ] Review metrics dashboard: All green
- [ ] Check database replication lag: <100ms
- [ ] Verify no manual interventions needed
- [ ] Post morning standup: "SF canary stable overnight"

### Post-Deployment Verification (Hours 1-24)

#### Real-Time Verification (First Hour)
```yaml
verification_suite:
  - metric: "Order Success Rate"
    query: "rate(darkstore_orders_success[5m])"
    target: "> 99.5%"
    alert_threshold: "< 99%"

  - metric: "Average Fulfillment Latency"
    query: "darkstore_latency_seconds (p50, p95, p99)"
    target: "p99 < 20 seconds"
    alert_threshold: "p99 > 25 seconds"

  - metric: "Inventory Sync Latency"
    query: "inventory_sync_duration_seconds (p99)"
    target: "< 5 seconds"
    alert_threshold: "> 10 seconds"

  - metric: "Order Accuracy"
    query: "darkstore_order_accuracy_percentage"
    target: "> 99.5%"
    alert_threshold: "< 99%"

  - metric: "System Availability"
    query: "up{job='darkstore-fulfillment'}"
    target: "3/3 pods running"
    alert_threshold: "< 2/3 pods"
```

#### Operational Verification (First 24 Hours)
```bash
#!/bin/bash

echo "=== Wave 40 Phase 2 Post-Deployment Verification ==="

# 1. Check pod logs for errors
echo "Checking pod logs for errors..."
kubectl logs -n fulfillment-sf-canary \
  deploy/darkstore-fulfillment \
  --tail=100 | grep -i "error\|exception\|panic" | wc -l

# 2. Sample order processing
echo "Testing 10 orders through dark-store..."
for i in {1..10}; do
  ORDER_ID=$(uuidgen)
  RESULT=$(curl -s -X POST https://fulfillment-api.instacommerce.internal/orders/fulfill \
    -H "Authorization: Bearer $TOKEN" \
    -d "{\"city\": \"sf\", \"order_id\": \"$ORDER_ID\"}")

  STATUS=$(echo $RESULT | jq -r '.status')
  LATENCY=$(echo $RESULT | jq -r '.latency_ms')

  if [ "$STATUS" == "success" ]; then
    echo "✓ Order $i processed in ${LATENCY}ms"
  else
    echo "✗ Order $i failed: $RESULT"
  fi
done

# 3. Check database consistency
echo "Verifying database consistency..."
ORPHANED=$(psql -U postgres -d darkstore_sf -t -c \
  "SELECT COUNT(*) FROM orders WHERE fulfillment_source='darkstore' AND status='pending' AND created_at < NOW() - INTERVAL '5 minutes';")
echo "Orphaned orders: $ORPHANED (should be 0)"

# 4. Verify inventory freshness
echo "Checking inventory sync freshness..."
MAX_AGE=$(redis-cli GET "inventory:sf:last_sync_age_seconds")
echo "Max inventory age: ${MAX_AGE}s (should be < 30s)"

echo "=== Verification Complete ==="
```

---

## Risk Matrix & Mitigations

### High-Risk Scenarios

| Risk | Probability | Impact | Mitigation |
|------|-------------|--------|-----------|
| **Inventory Sync Failures** | Medium | High | • Dual sync paths (API + DB direct query)<br/>• 30s timeout + fallback to cache<br/>• Auto-escalate after 5 consecutive failures |
| **Order Routing Errors** | Low | High | • Comprehensive unit tests (100% coverage)<br/>• Canary testing in SF first<br/>• Fallback to legacy warehouse |
| **Database Connection Exhaustion** | Low | High | • Connection pooling with max limits<br/>• Aggressive timeout on slow queries<br/>• Circuit breaker for DB operations |
| **Kafka Consumer Lag** | Medium | Medium | • Monitor consumer lag every 10s<br/>• Scale up partitions if lag > 5m<br/>• Dead-letter topic for unparseable messages |
| **Network Partition** | Low | Medium | • Service-to-service retry with backoff<br/>• Circuit breaker after 5 consecutive failures<br/>• Graceful degradation (return to legacy) |
| **Data Inconsistency** | Low | Medium | • Daily reconciliation job<br/>• Immutable audit trail in PostgreSQL<br/>• Kafka change log for replay capability |
| **Canary Traffic Overflow** | Low | Medium | • Start at 1% traffic, increase gradually<br/>• Auto-rollback if error rate > 5%<br/>• Circuit breaker for dark-store service |
| **Return Flow Deadlock** | Low | Low | • Timeout on return state transitions<br/>• Manual intervention queue<br/>• Weekly returns audit |

### Medium-Risk Scenarios

| Risk | Probability | Impact | Mitigation |
|------|-------------|--------|-----------|
| **Operational Staffing Gap** | Medium | Medium | • Mandatory 2-person on-call rotation<br/>• 24/7 coverage for first 2 weeks<br/>• Escalation to platform team after 15min |
| **Customer Support Overwhelm** | Medium | Low | • FAQ published before launch<br/>• Support tier trained on dark-store<br/>• Automated routing of common questions |
| **Metrics Dashboard Issues** | Low | Low | • Pre-launch testing on all dashboards<br/>• Backup metrics via CloudWatch<br/>• Low-tech metrics (curl to endpoint) |
| **DNS Resolution Delay** | Low | Low | • DNS TTL: 60s (low latency)<br/>• Multi-region DNS redundancy<br/>• Hardcoded IP fallback in clients |

### Low-Risk Scenarios

| Risk | Probability | Impact | Mitigation |
|------|-------------|--------|-----------|
| **SSL Certificate Expiration** | Very Low | Low | • Cert rotation automated 30 days before expiry<br/>• Alerts 60 days before expiration |
| **Deployment Image Corruption** | Very Low | Low | • Image signing with cosign<br/>• Runtime verification in admission controller |
| **Storage Quota Exceeded** | Low | Low | • Monitor disk usage on all nodes<br/>• Auto-cleanup of old logs (>30 days) |

---

## Timeline & Milestones

### Detailed Week-by-Week Breakdown

#### Week 1: March 28 - April 4 (SF Canary)

**Monday, March 28**
```
09:00 UTC  - Go/No-Go meeting (30 min)
           - Final infrastructure checks
           - Approve deployment
           - Team in launch bridge

09:30 UTC  - Deployment window opens
           - Create fulfillment-sf-canary namespace
           - Apply RBAC, secrets, ConfigMaps
           - Deploy pods (3 replicas)
           - Verify health checks passing

10:00 UTC  - Enable 1% traffic split
           - Monitor error rate, latency
           - Expected: ~10 orders/min to dark-store

10:30 UTC  - Enable 5% traffic split
           - Monitor for 30 minutes
           - Check logs for warnings

11:00 UTC  - Enable 10% traffic split
           - Official SF canary live
           - Post in #wave-40-darkstore: LIVE

12:00 UTC  - Stand down from launch window
           - Team continues monitoring
           - On-call escalation: Fulfillment → Platform
```

**Tuesday-Friday, March 28-31 & April 1-4**
```
Daily standups (8 AM UTC):
- Review metrics dashboard
- Check for any anomalies
- Inventory sync freshness
- Order accuracy rate
- Latency percentiles (p50, p95, p99)

Thursday, April 3:
- 72-hour metrics review
- Error budget consumption: 0.1% (target: <0.5%)
- Latency trend: Stable at p99 < 20 min
- Accuracy: 99.6% (target: >99.5%)
- Generate Week 1 report

Friday, April 4:
- Final week 1 metrics compilation
- Gates validation for Seattle deployment
- All green → Approve Seattle go-ahead
```

**Week 1 Success Criteria**:
✅ Uptime: ≥99.5%
✅ Order accuracy: ≥99.5%
✅ Average latency (p99): <20 minutes
✅ Inventory sync: <5 minutes freshness
✅ Zero P0 incidents
✅ Return processing: <2 hour SLA met

#### Week 2: April 4-11 (Seattle Gated Deployment)

**Friday, April 4**
```
14:00 UTC  - Gates review meeting (1 hour)
           - SF metrics presentation
           - Lessons learned discussion
           - Seattle readiness confirmation
           - Approve Seattle deployment (if gates pass)
```

**Monday, April 7**
```
09:00 UTC  - Go/No-Go meeting
           - Infrastructure checks (Seattle)
           - Team readiness verification
           - Final approval to proceed

09:30 UTC  - Deployment window opens
           - Create fulfillment-seattle-gates namespace
           - Apply configuration
           - Deploy 3 pods

10:30 UTC  - Enable 5% traffic split
           - Post in #wave-40-darkstore: Seattle LIVE
           - Monitor closely (different region, potential latency variations)

12:00 UTC  - Verify health
           - Orders flowing to Seattle dark stores
           - Inventory sync from both dark stores
```

**Tuesday-Thursday, April 8-10**
```
Continue SF monitoring (maintain 10% canary)
Ramp up Seattle gradually:
  Tue (Apr 8):  5% traffic split
  Wed (Apr 9):  7% traffic split
  Thu (Apr 10): 10% traffic split

Daily checks:
- Seattle latency vs SF (should be similar)
- Inventory freshness across 2 cities
- Cross-city order routing performance
```

**Thursday-Friday, April 10-11**
```
Thursday:
- 7-day metrics review for both SF & Seattle
- Generate Week 2 report
- Lessons learned: Any adjustments needed?

Friday:
- Gates validation for Austin deployment
- All green → Approve Austin go-ahead
```

**Week 2 Success Criteria**:
✅ SF canary remains stable (uptime ≥99.5%)
✅ Seattle latency ≤3 minutes difference from SF
✅ Seattle accuracy ≥99.5%
✅ Cross-city inventory sync: <5 minutes
✅ Zero P0 incidents (SF or Seattle)

#### Week 3: April 11-18 (Austin Gated Deployment)

**Monday, April 14**
```
09:00 UTC  - Go/No-Go for Austin
           - Gates review: SF (2 weeks) + Seattle (1 week)
           - Austin infrastructure ready?
           - Final approval

09:30 UTC  - Deployment window opens
           - Create fulfillment-austin-gates namespace
           - Deploy 3 pods to Austin
           - Enable 5% traffic split

12:00 UTC  - Verify health
           - Austin pods ready
           - Orders flowing through 3 dark stores
```

**Tuesday-Thursday, April 15-17**
```
Ramp up Austin:
  Tue (Apr 15): 5% traffic split
  Wed (Apr 16): 7% traffic split
  Thu (Apr 17): 10% traffic split

Monitor:
- All 3 cities running in parallel
- Total dark-store traffic: ~30% (10% each)
- Inventory sync across 3 cities
- No latency spike from adding Austin
```

**Thursday-Friday, April 17-18**
```
Thursday:
- 3-city metrics review
- Generate Week 3 report
- Decision: Production promotion or extended gates?

Friday:
- Executive briefing: Phase 2 completion status
- Planning for Phase 3 (national rollout)
```

**Week 3 Success Criteria**:
✅ SF + Seattle remain stable (uptime ≥99.5%)
✅ Austin latency ≤3 minutes from SF
✅ 3-city accuracy ≥99.5%
✅ Inventory sync: <5 minutes across all sources
✅ Zero P0 incidents (any city)
✅ Ready for Phase 3 (national rollout)

#### Week 4-6: Stabilization & Lessons Learned

**Week 4 (April 18-25)**
- Continue 10% traffic split to dark-stores across all 3 cities
- Monitor for anomalies (end-of-week spike, seasonal patterns)
- Begin preparing Phase 3 deployment plan
- Update operational runbooks with lessons learned

**Week 5 (April 25 - May 2)**
- Increase traffic split: 15% dark-store, 85% legacy (decision based on metrics)
- Evaluate return flow accuracy after 2-3 weeks in production
- Schedule Phase 3 kickoff meeting

**Week 6 (May 2-9)**
- Consider full rollout (50-50 split) if metrics support
- Finalize national expansion strategy
- Prepare board presentation on Wave 40 success

---

## Summary: Execution Ready

This Phase 2 deployment strategy provides a comprehensive, production-grade roadmap for deploying dark-store capabilities across 3 metropolitan areas with minimal risk:

### Key Success Factors
1. **Gradual Traffic Ramp**: Start at 1-10%, increase based on metrics
2. **City-Specific Validation**: SF canary (1 week) → Seattle/Austin (1 week each)
3. **Automated Safeguards**: Circuit breakers, health checks, traffic limits
4. **Comprehensive Monitoring**: Real-time dashboards, SLO tracking, multi-window alerts
5. **Clear Runbooks**: Pre-written procedures for common failure scenarios
6. **Executive Alignment**: Weekly status updates, gate-based progression

### Timeline
- **Week 1 (Mar 28-Apr 4)**: SF Canary ✅
- **Week 2 (Apr 7-11)**: Seattle Gated ✅
- **Week 3 (Apr 14-18)**: Austin Gated ✅
- **Week 4-6**: Stabilization & Phase 3 Planning

### Next Steps
1. Approve deployment plan with executive team
2. Brief ops team on escalation procedures
3. Conduct deployment dry-run on staging
4. Schedule launch bridge meetings
5. Begin pre-deployment infrastructure validation

---

**Document Prepared By**: Fulfillment Platform Team
**Approval Date**: 2026-03-21
**Status**: READY FOR EXECUTION
