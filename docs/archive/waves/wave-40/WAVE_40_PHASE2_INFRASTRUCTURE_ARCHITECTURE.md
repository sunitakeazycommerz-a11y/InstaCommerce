# Wave 40 Phase 2 - SF Canary Infrastructure Architecture
## San Francisco Dark-Store Fulfillment Deployment
## Deployment Date: March 24-28, 2026

---

## ARCHITECTURE OVERVIEW

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                           GCP PROJECT (us-west1)                            │
├─────────────────────────────────────────────────────────────────────────────┤
│                                                                             │
│  ┌─────────────────────────────────────────────────────────────────────┐   │
│  │         instacommerce-vpc (VPC Network)                            │   │
│  │         CIDR: 10.0.0.0/8                                          │   │
│  │                                                                   │   │
│  │  ┌─────────────────────────────────────────────────────────────┐ │   │
│  │  │     sf-canary-subnet (Subnetwork)                          │ │   │
│  │  │     CIDR: 10.1.0.0/24                                      │ │   │
│  │  │     Pod Range: 10.244.0.0/16 (pods-range)                │ │   │
│  │  │     Svc Range: 10.96.0.0/12 (services-range)            │ │   │
│  │  │                                                           │ │   │
│  │  │  ┌────────────────────────────────────────────────────┐  │ │   │
│  │  │  │   sf-canary-cluster (GKE Cluster)                 │  │ │   │
│  │  │  │   K8s Version: 1.28+                              │  │ │   │
│  │  │  │   Workload Identity: ENABLED                      │  │ │   │
│  │  │  │                                                  │  │ │   │
│  │  │  │  ┌──────────────────────────────────────────┐   │  │ │   │
│  │  │  │  │   dark-store-pool (3 nodes)            │   │  │ │   │
│  │  │  │  │   Machine: n1-standard-4 (4vCPU, 15GB) │   │  │ │   │
│  │  │  │  │   Disk: 100Gi SSD                      │   │  │ │   │
│  │  │  │  │   Taints: workload=dark-store:NoSched  │   │  │ │   │
│  │  │  │  │                                        │   │  │ │   │
│  │  │  │  │   ┌─────────────────────────────────┐  │   │  │ │   │
│  │  │  │  │   │ Pod Instances (5/15 max)       │  │   │  │ │   │
│  │  │  │  │   │                                │  │   │  │ │   │
│  │  │  │  │   │ • dark-store-XXXXX-1          │  │   │  │ │   │
│  │  │  │  │   │   CPU: 1000m | Mem: 2Gi       │  │   │  │ │   │
│  │  │  │  │   │   Probes: ✓✓✓ (up/ready/live)│  │   │  │ │   │
│  │  │  │  │   │                                │  │   │  │ │   │
│  │  │  │  │   │ • dark-store-XXXXX-2 ... -5   │  │   │  │ │   │
│  │  │  │  │   │   [same config]                │  │   │  │ │   │
│  │  │  │  │   │                                │  │   │  │ │   │
│  │  │  │  │   │   HPA: Scales 5-15 pods      │  │   │  │ │   │
│  │  │  │  │   │   Trigger: CPU 70% / Mem 80% │  │   │  │ │   │
│  │  │  │  │   └─────────────────────────────┘  │   │  │ │   │
│  │  │  │  │                                    │   │  │ │   │
│  │  │  │  │   Ports:                           │   │  │ │   │
│  │  │  │  │   • 8080/TCP (HTTP)               │   │  │ │   │
│  │  │  │  │   • 9090/TCP (gRPC)               │   │  │ │   │
│  │  │  │  │   • 9091/TCP (Metrics)            │   │  │ │   │
│  │  │  │  └──────────────────────────────────┘   │  │ │   │
│  │  │  │                                         │  │ │   │
│  │  │  │  ┌──────────────────────────────────┐   │  │ │   │
│  │  │  │  │   system-pool (2 nodes)          │   │  │ │   │
│  │  │  │  │   Machine: n1-standard-2 (2vCPU)│   │  │ │   │
│  │  │  │  │   Disk: 50Gi standard            │   │  │ │   │
│  │  │  │  │   Taints: dedicated=system       │   │  │ │   │
│  │  │  │  │                                  │   │  │ │   │
│  │  │  │  │   System Services:               │   │  │ │   │
│  │  │  │  │   • coredns (DNS)               │   │  │ │   │
│  │  │  │  │   • kube-proxy                   │   │  │ │   │
│  │  │  │  │   • metrics-server               │   │  │ │   │
│  │  │  │  │   • prometheus                   │   │  │ │   │
│  │  │  │  │   • istio-ingressgateway         │   │  │ │   │
│  │  │  │  └──────────────────────────────┘   │  │ │   │
│  │  │  │                                     │  │ │   │
│  │  │  │  Namespaces:                        │  │ │   │
│  │  │  │  • kube-system                      │  │ │   │
│  │  │  │  • sf-canary (dark-store, 50 pod) │  │ │   │
│  │  │  │  • prometheus (monitoring)          │  │ │   │
│  │  │  │  • istio-system (service mesh)      │  │ │   │
│  │  │  │  • argocd (GitOps)                 │  │ │   │
│  │  │  └────────────────────────────────────┘  │ │   │
│  │  │                                           │ │   │
│  │  │  Service Mesh (Istio):                    │ │   │
│  │  │  • Gateway: dark-store-gateway           │ │   │
│  │  │  • VirtualService: dark-store-vs (100%)  │ │   │
│  │  │  • DestinationRule: dark-store-dr        │ │   │
│  │  │  • mTLS: STRICT                          │ │   │
│  │  │  • Auth: JWT + AuthzPolicy               │ │   │
│  │  │                                           │ │   │
│  │  │  Storage (275Gi SSD):                     │ │   │
│  │  │  • dark-store-orders-pvc: 100Gi         │ │   │
│  │  │  • dark-store-fulfillment-pvc: 100Gi   │ │   │
│  │  │  • dark-store-cache-pvc: 50Gi           │ │   │
│  │  │  • dark-store-logs-pvc: 25Gi            │ │   │
│  │  │                                           │ │   │
│  │  └─────────────────────────────────────────┘ │   │
│  │                                              │   │
│  └──────────────────────────────────────────────┘   │
│                                                     │
│  Firewall Rules (13):                              │
│  • Allow internal (10.0.0.0/8)                    │
│  • Allow SSH (admin CIDR)                         │
│  • Allow HTTP/HTTPS (K8s, 8080/8443)             │
│  • Allow Prometheus (9090-9092)                   │
│  • Allow GKE API (443/10250)                      │
│  • Allow Cloud SQL (5432)                         │
│  • Allow Redis (6379)                             │
│  • Allow Kafka (9092-9094)                        │
│  • Allow health checks (35.191/130.211)          │
│  • Allow Google APIs egress (443)                 │
│  • Allow DNS egress (UDP 53)                      │
│  • Deny all default (ingress)                     │
│  • Deny all default (egress)                      │
│                                                     │
│  Monitoring Resources:                             │
│  • Dashboard: Real-time metrics (8 panels)        │
│  • Alert Policies (6):                            │
│    - High Error Rate (>0.1%)                      │
│    - High Latency (>2000ms p99)                   │
│    - Pod Restarts (>5)                            │
│    - Memory Pressure (>80%)                       │
│    - CPU Throttling (>100 periods)                │
│    - Network I/O (monitored)                      │
│  • Uptime Check: Synthetic monitoring             │
│  • SLO: 99.5% availability (30-day rolling)      │
│  • Backup: Velero (daily 2 AM UTC, 30-day TTL)  │
│                                                     │
└─────────────────────────────────────────────────────────────────────────────┘
```

---

## TRAFFIC FLOW DIAGRAM

```
External Request (100% SF canary)
         │
         ├─ dark-store.instacommerce.internal
         ├─ dark-store-sf.instacommerce.internal
         └─ fulfillment-sf.instacommerce.internal
         │
         ▼
┌─────────────────────────────────┐
│   Istio Ingress Gateway         │
│   dark-store-gateway            │
│   (ns: istio-system)            │
│   Ports: 80 (HTTP), 9090 (gRPC)│
└────────────┬────────────────────┘
             │
             ▼
┌─────────────────────────────────────────┐
│   Istio VirtualService                  │
│   dark-store-vs (sf-canary namespace)   │
│   HTTP Routes:                          │
│   • Match: prefix: /                   │
│   • Destination: dark-store-service    │
│   • Weight: 100%                       │
│   Timeouts: 30s                        │
│   Retries: 3 attempts                  │
└────────────┬────────────────────────────┘
             │
             ▼
┌────────────────────────────────────┐
│   Istio DestinationRule            │
│   dark-store-dr                    │
│   LoadBalancer: LEAST_REQUEST      │
│   ConnectionPool:                  │
│   • HTTP: 500 pending requests     │
│   • HTTP2: 2000 max requests       │
│   OutlierDetection: Active         │
└────────────┬─────────────────────┘
             │
             ▼
┌──────────────────────────────────┐
│   Kubernetes Service              │
│   dark-store-service (ClusterIP) │
│   Port: 8080/TCP                 │
│   SessionAffinity: ClientIP       │
│   Endpoints: 5/5 (all pods)      │
└────────────┬─────────────────────┘
             │
             ├─────────┬─────────┬─────────┬─────────┐
             │         │         │         │         │
             ▼         ▼         ▼         ▼         ▼
        ┌────────┐┌────────┐┌────────┐┌────────┐┌────────┐
        │Pod #1  ││Pod #2  ││Pod #3  ││Pod #4  ││Pod #5  │
        │8080    ││8080    ││8080    ││8080    ││8080    │
        └────────┘└────────┘└────────┘└────────┘└────────┘
```

---

## DATA PERSISTENCE LAYER

```
┌────────────────────────────────────────────────────────┐
│        Google Cloud Storage (GCS)                      │
│        Regional SSD Volumes (us-west1)                │
├────────────────────────────────────────────────────────┤
│                                                        │
│  ┌──────────────────────────────────────────────────┐ │
│  │  StorageClass: dark-store-ssd                   │ │
│  │  Provisioner: pd.csi.storage.gke.io            │ │
│  │  Type: pd-ssd (high-performance)               │ │
│  │  Replication: regional-pd (HA)                 │ │
│  │                                                  │ │
│  │  ┌────────────────────────────────────────────┐ │ │
│  │  │ dark-store-orders-pvc (100Gi)             │ │ │
│  │  │ • Order state & transaction logs           │ │ │
│  │  │ • AccessMode: ReadWriteOnce                │ │ │
│  │  │ • Backup: Daily snapshots (7-day TTL)     │ │ │
│  │  │ • Recovery: <30 minutes (restore snapshot) │ │ │
│  │  └────────────────────────────────────────────┘ │ │
│  │                                                  │ │
│  │  ┌────────────────────────────────────────────┐ │ │
│  │  │ dark-store-fulfillment-pvc (100Gi)       │ │ │
│  │  │ • Fulfillment tracking & inventory state  │ │ │
│  │  │ • AccessMode: ReadWriteOnce               │ │ │
│  │  │ • Backup: Daily snapshots (7-day TTL)    │ │ │
│  │  │ • Recovery: <30 minutes (restore)         │ │ │
│  │  └────────────────────────────────────────────┘ │ │
│  │                                                  │ │
│  │  ┌────────────────────────────────────────────┐ │ │
│  │  │ dark-store-cache-pvc (50Gi)               │ │ │
│  │  │ • Local cache for fast access patterns     │ │ │
│  │  │ • AccessMode: ReadWriteOnce               │ │ │
│  │  │ • No backup (ephemeral cache)             │ │ │
│  │  └────────────────────────────────────────────┘ │ │
│  │                                                  │ │
│  │  ┌────────────────────────────────────────────┐ │ │
│  │  │ dark-store-logs-pvc (25Gi)                │ │ │
│  │  │ • Application logs storage                 │ │ │
│  │  │ • AccessMode: ReadWriteOnce               │ │ │
│  │  │ • Retention: 3 days (auto-cleanup)        │ │ │
│  │  └────────────────────────────────────────────┘ │ │
│  │                                                  │ │
│  └──────────────────────────────────────────────────┘ │
│                                                        │
│  Backup Strategy (Velero):                            │
│  • Frequency: Daily at 2:00 AM UTC                   │
│  • Retention: 30 days                                │
│  • Target: Google Cloud Storage (GCS)                │
│  • Objects: PVCs, PVs, ConfigMaps, Secrets          │
│  • Recovery Time: <30 minutes                        │
│                                                        │
└────────────────────────────────────────────────────────┘
```

---

## OBSERVABILITY STACK

```
┌────────────────────────────────────────────────────────────┐
│              Monitoring & Observability                    │
├────────────────────────────────────────────────────────────┤
│                                                            │
│  METRICS COLLECTION:                                      │
│  ┌──────────────────────────────────────────────────────┐ │
│  │  Prometheus (Kubernetes native)                     │ │
│  │  • PodMonitor: dark-store-metrics                   │ │
│  │  • Scrape interval: 30 seconds                      │ │
│  │  • Metrics endpoint: /actuator/prometheus:9091      │ │
│  │  • Retention: 15 days                               │ │
│  │  • Storage: ~50GB (estimated)                       │ │
│  │  • Labels:                                          │ │
│  │    - namespace: sf-canary                           │ │
│  │    - pod: dark-store-*                              │ │
│  │    - region: us-west1                               │ │
│  └──────────────────────────────────────────────────────┘ │
│                                                            │
│  ALERT POLICIES (6):                                      │
│  ┌──────────────────────────────────────────────────────┐ │
│  │ 1. Error Rate Alert                                 │ │
│  │    • Metric: http_server_requests (5xx)            │ │
│  │    • Threshold: >0.1% (5-min window)               │ │
│  │    • Severity: WARNING                             │ │
│  │    • Action: Slack #canary-sf-alerts              │ │
│  │                                                     │ │
│  │ 2. Latency Alert (p99)                              │ │
│  │    • Metric: http_request_duration_seconds          │ │
│  │    • Threshold: >2000ms p99                        │ │
│  │    • Severity: CRITICAL                            │ │
│  │    • Action: PagerDuty page on-call               │ │
│  │                                                     │ │
│  │ 3. Pod Restart Alert                                │ │
│  │    • Metric: kubernetes_pod_restart_count           │ │
│  │    • Threshold: >5 restarts (10-min window)        │ │
│  │    • Severity: WARNING                             │ │
│  │    • Action: Slack + Investigation                │ │
│  │                                                     │ │
│  │ 4. Memory Pressure Alert                            │ │
│  │    • Metric: kubernetes_memory_used_bytes           │ │
│  │    • Threshold: >80% (3.3GB of 4GB)               │ │
│  │    • Severity: INFO                                │ │
│  │    • Action: Trigger HPA scale-up                 │ │
│  │                                                     │ │
│  │ 5. CPU Throttling Alert                             │ │
│  │    • Metric: kubernetes_cpu_throttled_periods       │ │
│  │    • Threshold: >100 periods                       │ │
│  │    • Severity: INFO                                │ │
│  │    • Action: Monitor for performance impact       │ │
│  │                                                     │ │
│  │ 6. Network I/O Monitor                              │ │
│  │    • Metrics: network_transmitted/received_bytes   │ │
│  │    • Threshold: Baseline for anomaly detection    │ │
│  │    • Severity: INFORMATIONAL                       │ │
│  │    • Action: Track for saturation events          │ │
│  │                                                     │ │
│  └──────────────────────────────────────────────────────┘ │
│                                                            │
│  MONITORING DASHBOARD (8 Panels):                        │
│  ┌──────────────────────────────────────────────────────┐ │
│  │ Panel 1: Request Rate              (per second)    │ │
│  │ Panel 2: Error Rate                (%)             │ │
│  │ Panel 3: Latency p50/p95/p99      (ms)            │ │
│  │ Panel 4: Memory Usage              (Gi)            │ │
│  │ Panel 5: CPU Usage                 (%)             │ │
│  │ Panel 6: Pod Restart Count         (#)             │ │
│  │ Panel 7: Network I/O               (bytes/sec)     │ │
│  │ Panel 8: Pod Health Status         (ready/total)   │ │
│  └──────────────────────────────────────────────────────┘ │
│                                                            │
│  DISTRIBUTED TRACING:                                    │
│  ┌──────────────────────────────────────────────────────┐ │
│  │  Jaeger Integration (via Istio)                     │ │
│  │  • Sampling Rate: 100% (all requests traced)        │ │
│  │  • Trace Span: <500ms p99 overhead                  │ │
│  │  • Retention: 72 hours                              │ │
│  │  • Storage: In-memory + persistent backend         │ │
│  └──────────────────────────────────────────────────────┘ │
│                                                            │
│  LOGGING:                                                 │
│  ┌──────────────────────────────────────────────────────┐ │
│  │  Google Cloud Logging (Stack Driver)               │ │
│  │  • Container logs: Application output               │ │
│  │  • System logs: Kubernetes events                   │ │
│  │  • Log level: INFO (default)                        │ │
│  │  • Retention: 7 days                                │ │
│  │  • Filter: labels.region=us-west1, app=dark-store  │ │
│  │  • Alert: ERROR or FATAL detected                  │ │
│  └──────────────────────────────────────────────────────┘ │
│                                                            │
│  SLO TRACKING:                                            │
│  ┌──────────────────────────────────────────────────────┐ │
│  │  Service Level Objective: 99.5% Availability       │ │
│  │  • Measurement: 2xx responses / total requests      │ │
│  │  • Window: 30-day rolling                           │ │
│  │  • Error Budget: 3.6 hours per month                │ │
│  │  • Status: 100% current (new deployment)            │ │
│  │  • Runway: 30 days at current rate                  │ │
│  │  • Dashboard: Real-time budget tracking             │ │
│  └──────────────────────────────────────────────────────┘ │
│                                                            │
└────────────────────────────────────────────────────────────┘
```

---

## DEPLOYMENT DEPENDENCIES

```
Phase 1: Infrastructure Foundation (Terraform)
   ├─ GKE Cluster: sf-canary-cluster
   │  ├─ Node Pool: dark-store-pool (3 nodes, n1-std-4)
   │  └─ Node Pool: system-pool (2 nodes, n1-std-2)
   ├─ VPC Network: instacommerce-vpc
   ├─ Subnets & IP Ranges
   ├─ Service Accounts (IAM)
   └─ Firewall Rules (13 rules)
        │
        ▼
Phase 2: Kubernetes Resources
   ├─ Namespace: sf-canary
   │  ├─ ResourceQuota (50 pods, 20 CPU, 80Gi memory)
   │  ├─ NetworkPolicy (default DENY)
   │  ├─ ServiceAccount (dark-store-sa)
   │  ├─ RBAC Roles & Bindings
   │  └─ LimitRange (resource defaults)
   ├─ StorageClass: dark-store-ssd
   │  ├─ PVCs: orders, fulfillment, cache, logs
   │  └─ Backup schedule (Velero)
   ├─ Deployment: dark-store-service
   │  ├─ Replicas: 5 (initial)
   │  ├─ HPA: 5-15 (scale-out)
   │  ├─ RollingUpdate strategy
   │  ├─ Health probes (3 types)
   │  └─ Security context
   └─ Service: dark-store-service (ClusterIP)
        │
        ▼
Phase 3: Service Mesh (Istio)
   ├─ Gateway: dark-store-gateway
   ├─ VirtualService: dark-store-vs (100% SF)
   ├─ DestinationRule: dark-store-dr
   ├─ RequestAuthentication (JWT)
   ├─ AuthorizationPolicy (RBAC)
   ├─ PeerAuthentication (mTLS)
   └─ Telemetry (metrics + tracing)
        │
        ▼
Phase 4: Helm Release
   ├─ Chart: deploy/helm/
   ├─ Base values: values.yaml
   ├─ Canary values: values-canary-sf.yaml
   ├─ Services enabled: fulfillment, warehouse, rider-fleet
   └─ Canary parameters: traffic weight, SLOs, analysis
        │
        ▼
Phase 5: ArgoCD GitOps
   ├─ Application: canary-sf-fulfillment
   ├─ Repository: GitHub (canary/sf branch)
   ├─ Sync Policy: Automated (prune=true, selfHeal=true)
   ├─ AnalysisTemplates: success-rate, latency-p99
   ├─ Rollout strategy: 100% weight (Week 1)
   └─ Notification channels: Slack + PagerDuty
        │
        ▼
Phase 6: Monitoring & Observability
   ├─ Prometheus: Metrics collection
   ├─ Alerts: 6 policies (configured)
   ├─ Dashboard: 8-panel monitoring view
   ├─ Uptime Check: Synthetic monitoring
   ├─ SLO: 99.5% availability target
   └─ Backup: Daily Velero snapshots
```

---

## RESOURCE ALLOCATION SUMMARY

### Compute Resources (Initial)
```
Total vCPU:         16 (12 dark-store + 4 system)
Total Memory:       60Gi (45Gi dark-store + 15Gi system)
Total Disk:         150Gi (100Gi dark-store SSD + 50Gi system)

Per Pod (dark-store):
  Requests:  1 vCPU, 2Gi memory
  Limits:    2 vCPU, 4Gi memory

Per Node (dark-store pool):
  Requests:  3-4 vCPU, 8-10Gi memory
  Limits:    6-8 vCPU, 16-20Gi memory
```

### Storage Resources
```
Total PVCs:         275Gi SSD
  • Orders:         100Gi
  • Fulfillment:    100Gi
  • Cache:          50Gi
  • Logs:           25Gi

Backup Storage:     ~30 snapshots x 275Gi = 8.25TB (managed by GCS)
Snapshot Retention: 30 days
```

### Network Resources
```
VPC:                1 (instacommerce-vpc)
Subnets:            1 (sf-canary-subnet)
Pod CIDR:           10.244.0.0/16 (65,536 IPs)
Service CIDR:       10.96.0.0/12 (1,048,576 IPs)
Firewall Rules:     13 (ingress + egress)
Internal LB IPs:    1 (10.0.1.100)
```

### Cost Estimation (Monthly)
```
GKE Cluster:        $586 (16 vCPU + 60Gi memory)
Storage (PVC):      $47 (275Gi SSD x $0.17/GB)
Backups:            $22 (30 snapshots)
Networking:         $36 (data transfer + ILB)
Monitoring:         $60 (6 alert policies)
─────────────────────────────
Total:              $1,044/month (baseline)
Peak (HPA max):     $1,500/month (26 vCPU + 130Gi memory)
```

---

## DEPLOYMENT READINESS CHECKLIST

**Infrastructure:** ✅ GKE cluster (sf-canary-cluster) configured
**Networking:** ✅ VPC, subnets, firewall rules deployed
**Kubernetes:** ✅ Namespace, RBAC, NetworkPolicy configured
**Storage:** ✅ StorageClass + 4 PVCs provisioned
**Application:** ✅ 5 pod replicas deployed + HPA enabled
**Service Mesh:** ✅ Istio Gateway/VirtualService/mTLS configured
**Helm:** ✅ Release deployed (instacommerce-sf)
**ArgoCD:** ✅ Application synced (canary-sf-fulfillment)
**Monitoring:** ✅ Dashboard, alerts, SLO configured
**Security:** ✅ JWT auth, mTLS, RBAC enforced
**Backup:** ✅ Velero schedule active

---

## ROLLBACK DECISION TREE

```
Issue Detected
    │
    ├─ Single Pod Unhealthy
    │  └─ ACTION: kubectl delete pod (Level 1 rollback, 30s)
    │
    ├─ Deployment Broken (<5 pods ready)
    │  └─ ACTION: kubectl rollout undo (Level 2, 1-3 min)
    │
    ├─ Application Errors (>1% error rate)
    │  └─ ACTION: ArgoCD rollback (Level 3, 5-10 min)
    │
    ├─ Cluster Issues (node failures, network down)
    │  └─ ACTION: Terraform destroy + redeploy (Level 4, 30-40 min)
    │
    └─ Unknown/Emergency
       └─ ACTION: Escalate to VP Engineering (P0 incident)
```

---

**Architecture Version:** 1.0 (Phase 2)
**Last Updated:** 2026-03-22
**Next Phase:** Phase 2B (Austin + Seattle expansion - Week 2)

