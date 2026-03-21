# SF Canary Architecture Documentation
## Wave 40 Phase 2 - Dark-Store Canary Deployment Infrastructure

**Document Version**: 1.0
**Created**: March 21, 2026
**Environment**: Production Canary (us-west1)
**Status**: Architecture Approved
**Revision**: Phase 2 Week 1

---

## Table of Contents

1. [Executive Summary](#executive-summary)
2. [High-Level Architecture](#high-level-architecture)
3. [Network Topology](#network-topology)
4. [Pod Placement Strategy](#pod-placement-strategy)
5. [Storage Architecture](#storage-architecture)
6. [Monitoring & Observability](#monitoring--observability)
7. [Security Architecture](#security-architecture)
8. [Disaster Recovery](#disaster-recovery)

---

## Executive Summary

The SF Canary deployment is a **100% traffic canary** of the dark-store service in the San Francisco region (us-west1). This deployment validates the dark-store architecture before expanding to Seattle and Austin.

### Key Metrics

- **Replicas**: 5 pods for high availability
- **Node Count**: 3 dark-store nodes + 2 system nodes (5 total)
- **Machine Type**: n1-standard-4 (4 vCPU, 15GB RAM, SSD)
- **Traffic**: 100% of SF dark-store requests
- **SLO Target**: 99.5% availability, <2s p99 latency, <0.1% error rate
- **Isolation**: Dedicated namespace (sf-canary) with network policies
- **Storage**: 225GB total (100GB orders + 100GB fulfillment + 25GB cache)
- **Cost Estimate**: $1,200/month (cluster + storage + monitoring)

---

## High-Level Architecture

### System Diagram

```
┌─────────────────────────────────────────────────────────────────────────┐
│                         GCP Project (us-west1)                          │
│                      instacommerce-prod                                 │
└─────────────────────────────────────────────────────────────────────────┘
                                  │
                    ┌─────────────┴─────────────┐
                    │                           │
         ┌──────────▼──────────┐    ┌──────────▼──────────┐
         │   Kubernetes API    │    │   Cloud IAM/SA      │
         │   (GKE Cluster)     │    │   (Workload ID)     │
         │   us-west1          │    │                     │
         └──────────┬──────────┘    └─────────────────────┘
                    │
        ┌───────────┼───────────┐
        │           │           │
   ┌────▼────┐ ┌───▼────┐ ┌───▼────┐
   │ System  │ │ Dark   │ │ Dark   │
   │ Pool    │ │ Store  │ │ Store  │
   │ (2x)    │ │ Pool   │ │ Pool   │
   │         │ │ (3x)   │ │ (Ready)│
   └─────────┘ └────┬───┘ └───────┘
                    │
            ┌───────┴───────┐
            │               │
      ┌─────▼─────┐    ┌───▼────┐
      │ Internal  │    │ Storage │
      │ LB (ILB)  │    │ (PVCs)  │
      │10.0.1.100 │    │ (SSD)   │
      └─────┬─────┘    └────┬────┘
            │               │
    ┌───────┴────────┐  ┌───▼────────────┐
    │                │  │                │
┌───▼───┐        ┌───▼──▼────┐    ┌─────▼───────┐
│Orders │        │Fulfillment │    │Cache/Logs   │
│(100GB)│        │(100GB)     │    │(75GB total) │
└───────┘        └────────────┘    └─────────────┘
                        │
        ┌───────────────┼───────────────┐
        │               │               │
   ┌────▼────┐    ┌────▼────┐    ┌────▼────┐
   │ Cloud   │    │  Redis  │    │  Cloud  │
   │  SQL    │    │ Memory  │    │ Logging │
   │(Private)│    │ store   │    │/Tracing │
   │         │    │(Private)│    │         │
   └─────────┘    └─────────┘    └─────────┘
```

### Component Interactions

```
┌──────────────────────────────────────────────────────────────┐
│                   Client Request Flow                         │
└──────────────────────────────────────────────────────────────┘
                            │
                            ▼
            ┌─────────────────────────────┐
            │   Istio Ingress Gateway     │
            │   (100% to SF Canary)       │
            └────────────┬────────────────┘
                         │
                         ▼
            ┌─────────────────────────────┐
            │   VirtualService (100%)     │
            │   dark-store-vs             │
            └────────────┬────────────────┘
                         │
                         ▼
            ┌─────────────────────────────┐
            │  DestinationRule + Subset   │
            │  (sf-canary subset)         │
            └────────────┬────────────────┘
                         │
                ┌────────┼────────┐
                ▼        ▼        ▼
           ┌──────────────────────────┐
           │  Dark-Store Pods (5x)    │
           │  ┌──────┬──────┬──────┐  │
           │  │ Pod1 │ Pod2 │ Pod3 │  │
           │  └──────┴──────┴──────┘  │
           │  ┌──────┬──────┐         │
           │  │ Pod4 │ Pod5 │         │
           │  └──────┴──────┘         │
           └──────────────────────────┘
                     │
        ┌────────────┼────────────┐
        ▼            ▼            ▼
   Cloud SQL    Redis Cache   Local Storage
   (orders)     (session)      (PVCs)
```

---

## Network Topology

### VPC Architecture

```
┌──────────────────────────────────────────────────────────────────┐
│               instacommerce-vpc (10.0.0.0/8)                     │
│                                                                   │
│  ┌────────────────────────────────────────────────────────────┐  │
│  │  sf-canary-subnet (10.0.0.0/20)                           │  │
│  │  Primary: 10.0.0.0/24                                     │  │
│  │                                                            │  │
│  │  Secondary Ranges:                                        │  │
│  │  - pods-range: 10.4.0.0/14       (pods)                  │  │
│  │  - services-range: 10.8.0.0/20   (services/ILB)          │  │
│  │                                                            │  │
│  │  ┌─────────────────────────────────────────────────────┐ │  │
│  │  │  GKE Cluster (sf-canary-cluster)                   │ │  │
│  │  │                                                     │ │  │
│  │  │  ┌─────────────┐      ┌─────────────┐             │ │  │
│  │  │  │   Node 1    │      │   Node 2    │             │ │  │
│  │  │  │ (Dark-Store)│      │ (Dark-Store)│             │ │  │
│  │  │  │ 10.0.0.5    │      │ 10.0.0.6    │             │ │  │
│  │  │  │ ┌─────────┐ │      │ ┌─────────┐ │             │ │  │
│  │  │  │ │Pod (1)  │ │      │ │Pod (2)  │ │             │ │  │
│  │  │  │ │10.4.0.2 │ │      │ │10.4.1.2 │ │             │ │  │
│  │  │  │ └─────────┘ │      │ └─────────┘ │             │ │  │
│  │  │  │ ┌─────────┐ │      │ ┌─────────┐ │             │ │  │
│  │  │  │ │Pod (3)  │ │      │ │Pod (4)  │ │             │ │  │
│  │  │  │ │10.4.0.3 │ │      │ │10.4.1.3 │ │             │ │  │
│  │  │  │ └─────────┘ │      │ └─────────┘ │             │ │  │
│  │  │  └─────────────┘      └─────────────┘             │ │  │
│  │  │                                                     │ │  │
│  │  │  ┌─────────────────────────────────────────────┐  │ │  │
│  │  │  │        Node 3 (Dark-Store)                 │  │ │  │
│  │  │  │        10.0.0.7                            │  │ │  │
│  │  │  │  ┌──────────────────────────────────────┐  │  │ │  │
│  │  │  │  │  Pod (5) 10.4.2.2                   │  │  │ │  │
│  │  │  │  │  Istio Sidecar injected             │  │  │ │  │
│  │  │  │  │  mTLS: STRICT                       │  │  │ │  │
│  │  │  │  └──────────────────────────────────────┘  │  │ │  │
│  │  │  └─────────────────────────────────────────────┘  │ │  │
│  │  │                                                     │ │  │
│  │  │  ┌──────────────────────────────────────────────┐  │ │  │
│  │  │  │   System Pool (2x nodes - kube-system)      │  │ │  │
│  │  │  │   Reserved for Kubernetes internals         │  │ │  │
│  │  │  └──────────────────────────────────────────────┘  │ │  │
│  │  │                                                     │ │  │
│  │  │  Control Plane: Google-managed                    │ │  │
│  │  │  Logging: Cloud Logging (StackDriver)            │ │  │
│  │  │  Monitoring: Cloud Monitoring (Stackdriver)      │ │  │
│  │  │  Service Mesh: Istio (mTLS enforced)            │ │  │
│  │  └─────────────────────────────────────────────────┘ │  │
│  │                                                        │  │
│  │  Internal Load Balancer (ILB): 10.0.1.100            │  │
│  │  - Protocol: TCP                                      │  │
│  │  - Port: 8080 (dark-store service)                   │  │
│  │  - Backend Service: dark-store-backend               │  │
│  │  - Health Check: /actuator/health (port 8080)        │  │
│  └────────────────────────────────────────────────────────┘  │
│                                                                   │
│  Firewall Rules (13 total):                                      │
│  - allow-internal (VPC traffic)                                  │
│  - allow-ssh-admin (22/tcp from admin CIDR)                     │
│  - allow-http-to-k8s (80, 8080, 8443)                          │
│  - allow-prometheus-scrape (9090, 9091, 9092)                  │
│  - allow-gke-api (10250/tcp, 443/tcp)                          │
│  - allow-cloudsql (5432/tcp)                                    │
│  - allow-redis (6379/tcp)                                       │
│  - allow-kafka (9092/tcp)                                       │
│  - allow-health-checks (8080, 8443)                            │
│  - allow-google-apis-egress (443/tcp outbound)                 │
│  - allow-dns-egress (53/udp outbound)                          │
│  - deny-all-default (65534)                                    │
│  - deny-all-egress-default (65534)                             │
└──────────────────────────────────────────────────────────────────┘
```

### Zone Distribution (us-west1 Zones)

```
┌──────────────────────────────────────────────────────────────┐
│                    us-west1 Region                            │
│                                                                │
│  ┌─────────────────┐  ┌─────────────────┐  ┌──────────────┐  │
│  │  us-west1-a     │  │  us-west1-b     │  │ us-west1-c   │  │
│  │                 │  │                 │  │              │  │
│  │  ┌───────────┐  │  │  ┌───────────┐  │  │              │  │
│  │  │  Node 1   │  │  │  │  Node 2   │  │  │ (optional)   │  │
│  │  │  Pod 1/3  │  │  │  │  Pod 2/4  │  │  │ (reserved)   │  │
│  │  │  Zone: a  │  │  │  │  Zone: b  │  │  │              │  │
│  │  └───────────┘  │  │  └───────────┘  │  └──────────────┘  │
│  │                 │  │                 │                     │
│  │  ┌───────────┐  │  │  ┌───────────┐  │                     │
│  │  │ System-1  │  │  │  │ System-2  │  │                     │
│  │  │ kube-sys  │  │  │  │ kube-sys  │  │                     │
│  │  └───────────┘  │  │  └───────────┘  │                     │
│  └─────────────────┘  └─────────────────┘  └──────────────────┘
│                                                                │
│  ┌──────────────────────────────────────────────────────────┐ │
│  │              Shared Services (Zone b)                    │ │
│  │  - Cloud SQL (dark-store-db, primary in us-west1-b)     │ │
│  │  - Redis Memorystore (us-west1-b)                       │ │
│  │  - Cloud Logging (multi-zone)                           │ │
│  │  - Cloud Monitoring (multi-zone)                        │ │
│  └──────────────────────────────────────────────────────────┘ │
│                                                                │
└──────────────────────────────────────────────────────────────┘
```

---

## Pod Placement Strategy

### Pod Affinity Rules

```
┌─────────────────────────────────────────────────────────────┐
│          Pod Placement: Anti-Affinity Distribution           │
│                                                              │
│  Preferred Rule: Spread across different nodes              │
│  ┌──────────┐  ┌──────────┐  ┌──────────┐                  │
│  │  Node 1  │  │  Node 2  │  │  Node 3  │                  │
│  │          │  │          │  │          │                  │
│  │  Pod 1 ✓ │  │  Pod 2 ✓ │  │  Pod 3 ✓ │                  │
│  │  Pod 4 ✓ │  │          │  │  Pod 5 ✓ │                  │
│  └──────────┘  └──────────┘  └──────────┘                  │
│                                                              │
│  Benefits:                                                   │
│  - Node failure affects max 2 pods (4/5 remain available)  │
│  - Zone failure: different nodes in same/diff zones        │
│  - Resource contention minimized (spread load)             │
│  - Better horizontal scaling                               │
│                                                              │
│  Topology Spread:                                           │
│  - maxSkew: 1 (difference between zones max 1 pod)         │
│  - topologyKey: kubernetes.io/hostname (per-node)          │
│  - whenUnsatisfiable: ScheduleAnyway (best effort)         │
└─────────────────────────────────────────────────────────────┘
```

### Node Pool Configuration

```
┌────────────────────────────────────────────────────────┐
│           Dark-Store Node Pool (3 nodes)               │
│                                                        │
│  Machine Type: n1-standard-4                          │
│  - CPUs: 4 vCPU                                       │
│  - Memory: 15 GB                                      │
│  - Disk: 100 GB SSD (pd-ssd)                          │
│  - Local SSD: 375 GB                                  │
│                                                        │
│  Resource Allocation (per node):                      │
│  ┌────────────────────────────────────────┐           │
│  │ Node Total: 4 CPU, 15 GB RAM           │           │
│  │                                        │           │
│  │ Reserved (System): 100m CPU, 256Mi RAM │           │
│  │ Available (Pods): 3900m CPU, 14.75GB   │           │
│  │                                        │           │
│  │ Per Pod Limit: 2 CPU, 4GB              │           │
│  │ Per Pod Request: 1 CPU, 2GB            │           │
│  │                                        │           │
│  │ Max Pods per Node: 2-3                 │           │
│  └────────────────────────────────────────┘           │
│                                                        │
│  Node Labels:                                         │
│  - node_pool: dark-store-pool                        │
│  - workload: dark-store                              │
│  - region: us-west1                                  │
│                                                        │
│  Node Taints:                                         │
│  - workload=dark-store:NoSchedule                    │
│    (Only dark-store pods can schedule)                │
│                                                        │
│  Auto-scaling:                                        │
│  - Min: 3 nodes (always running)                      │
│  - Max: 10 nodes (scale up if CPU > 70%)             │
│  - Scale Down: 300s cooldown                          │
└────────────────────────────────────────────────────────┘

┌────────────────────────────────────────────────────────┐
│          System Node Pool (2 nodes)                    │
│                                                        │
│  Machine Type: n1-standard-2                          │
│  - CPUs: 2 vCPU                                       │
│  - Memory: 7.5 GB                                     │
│  - Disk: 50 GB standard (pd-standard)                 │
│                                                        │
│  Purpose:                                              │
│  - Kubernetes system components (kube-system ns)     │
│  - Monitoring agents                                  │
│  - Logging sidecars                                   │
│  - Network policies enforcement                       │
│                                                        │
│  Node Taints:                                         │
│  - dedicated=system:NoSchedule                        │
│    (Only system pods can schedule)                     │
│                                                        │
│  Auto-scaling:                                        │
│  - Min: 2 nodes                                       │
│  - Max: 5 nodes                                       │
└────────────────────────────────────────────────────────┘
```

---

## Storage Architecture

### Volume Configuration

```
┌──────────────────────────────────────────────────────────┐
│         Dark-Store Persistent Storage (225GB total)      │
│                                                          │
│  ┌─────────────────────────────────────────────────────┐ │
│  │ Orders State (100 GB SSD)                           │ │
│  │ PVC: dark-store-orders-pvc                          │ │
│  │                                                     │ │
│  │ Mounted in Pod: /data/orders                        │ │
│  │ Access Mode: ReadWriteOnce (single pod at a time)  │ │
│  │ Storage Class: dark-store-ssd                      │ │
│  │ Backend: Google Cloud SSD (pd-ssd)                 │ │
│  │ Replication: Regional (replicated across zones)    │ │
│  │                                                     │ │
│  │ Data Contents:                                      │ │
│  │ - Order transactions                               │ │
│  │ - Order state (pending, confirmed, packed, etc)   │ │
│  │ - Timestamps and audit trails                      │ │
│  │ - Transaction logs (7-day rotation)                │ │
│  │                                                     │ │
│  │ Retention: Indefinite (backed up daily)            │ │
│  │ Backup: Velero (daily snapshots)                   │ │
│  │ RTO: < 1 hour                                      │ │
│  │ RPO: < 24 hours                                    │ │
│  └─────────────────────────────────────────────────────┘ │
│                                                          │
│  ┌─────────────────────────────────────────────────────┐ │
│  │ Fulfillment State (100 GB SSD)                      │ │
│  │ PVC: dark-store-fulfillment-pvc                    │ │
│  │                                                     │ │
│  │ Mounted in Pod: /data/fulfillment                  │ │
│  │ Access Mode: ReadWriteOnce                         │ │
│  │ Storage Class: dark-store-ssd                      │ │
│  │ Backend: Google Cloud SSD (pd-ssd)                 │ │
│  │ Replication: Regional                              │ │
│  │                                                     │ │
│  │ Data Contents:                                      │ │
│  │ - Inventory levels                                 │ │
│  │ - Item locations (shelf/bin mapping)              │ │
│  │ - Picking task state                              │ │
│  │ - Packing/shipping preparation                    │ │
│  │ - Route optimization cache                        │ │
│  │                                                     │ │
│  │ Retention: Indefinite (backed up daily)            │ │
│  │ Backup: Velero (daily snapshots)                   │ │
│  └─────────────────────────────────────────────────────┘ │
│                                                          │
│  ┌─────────────────────────────────────────────────────┐ │
│  │ Cache Storage (50 GB SSD)                           │ │
│  │ PVC: dark-store-cache-pvc                          │ │
│  │                                                     │ │
│  │ Mounted in Pod: /data/cache                        │ │
│  │ Access Mode: ReadWriteOnce                         │ │
│  │ Storage Class: dark-store-ssd                      │ │
│  │ Backend: Google Cloud SSD (pd-ssd)                 │ │
│  │                                                     │ │
│  │ Data Contents:                                      │ │
│  │ - Frequently accessed inventory                    │ │
│  │ - Routing calculations                            │ │
│  │ - Session data (distributed cache layer 2)       │ │
│  │ - Temporary computation results                   │ │
│  │                                                     │ │
│  │ Retention: Ephemeral (7-day cleanup)              │ │
│  │ Backup: Not backed up (non-critical)              │ │
│  │ Clearable: Yes (safe to delete on pod restart)    │ │
│  └─────────────────────────────────────────────────────┘ │
│                                                          │
│  ┌─────────────────────────────────────────────────────┐ │
│  │ Application Logs (25 GB SSD)                        │ │
│  │ PVC: dark-store-logs-pvc                           │ │
│  │                                                     │ │
│  │ Mounted in Pod: /app/logs                          │ │
│  │ Access Mode: ReadWriteOnce                         │ │
│  │ Storage Class: dark-store-ssd                      │ │
│  │ Backend: Google Cloud SSD (pd-ssd)                 │ │
│  │                                                     │ │
│  │ Data Contents:                                      │ │
│  │ - Application logs (JSON format)                  │ │
│  │ - Request/response logs                           │ │
│  │ - Error traces and stack traces                   │ │
│  │ - Debug output (if enabled)                       │ │
│  │                                                     │ │
│  │ Retention: 7 days (rotated daily)                  │ │
│  │ Backup: Cloud Logging (automatic)                │ │
│  │ Sync Interval: Real-time to Cloud Logging        │ │
│  └─────────────────────────────────────────────────────┘ │
│                                                          │
│  Total Storage Cost: ~$150/month                        │
│  (SSD at $0.30/GB/month, 225GB * 0.30 * (30/30))      │
└──────────────────────────────────────────────────────────┘
```

### Storage Snapshot & Backup Strategy

```
┌────────────────────────────────────────────────────────┐
│        Backup and Disaster Recovery Plan               │
│                                                        │
│  Backup Type: Snapshots (point-in-time recovery)      │
│  Frequency: Daily at 2:00 AM UTC                      │
│  Tool: Velero (Kubernetes backup framework)           │
│  Retention: 30 days (rolling window)                  │
│  Target: GCS buckets + Glacier archival               │
│                                                        │
│  Orders PVC Backup:                                    │
│  ┌──────────────────────────────────────────────────┐ │
│  │ Snapshot Lifecycle:                              │ │
│  │                                                  │ │
│  │ Day 1:  Created (snapshot-001)                 │ │
│  │ Day 2:  Incremental (snapshot-002)             │ │
│  │ ...                                             │ │
│  │ Day 30: Incremental (snapshot-030)             │ │
│  │ Day 31: Old snapshots deleted (FIFO)           │ │
│  │                                                  │ │
│  │ Total Backup Size: ~50 GB (compressed)         │ │
│  │ Backup Storage Cost: ~$15/month                │ │
│  └──────────────────────────────────────────────────┘ │
│                                                        │
│  Recovery Procedure:                                   │
│  1. Identify snapshot timestamp (dark-store-orders-   │
│     snapshot-<timestamp>)                             │ │
│  2. Create new PVC from snapshot                      │ │
│  3. Patch deployment to use new PVC                   │ │
│  4. Verify data integrity before committing          │ │
│  5. Rollback if needed (< 5 min RTO)                 │ │
│                                                        │
│  RTO: 5 minutes (Recovery Time Objective)             │ │
│  RPO: 24 hours (Recovery Point Objective)             │ │
└────────────────────────────────────────────────────────┘
```

---

## Monitoring & Observability

### Metrics Collection

```
┌────────────────────────────────────────────────────────┐
│      Prometheus Metrics Collection Pipeline            │
│                                                        │
│  Metrics Scraped (30-second interval):                 │
│                                                        │
│  Application Metrics:                                  │
│  - http_requests_total (counter)                      │
│  - http_request_duration_seconds (histogram)          │
│  - http_requests_in_flight (gauge)                    │
│  - app_errors_total (counter by type)                 │
│  - app_business_logic_duration (histogram)            │
│                                                        │
│  Infrastructure Metrics:                               │
│  - container_cpu_usage_seconds_total                  │
│  - container_memory_usage_bytes                       │
│  - container_fs_usage_bytes (disk)                    │
│  - container_network_receive_bytes_total              │
│  - container_network_transmit_bytes_total             │
│                                                        │
│  Kubernetes Metrics:                                   │
│  - kube_pod_status_ready                             │
│  - kube_pod_restart_count                            │
│  - kube_pod_container_status_state                   │
│  - kube_deployment_status_replicas                   │
│  - kube_deployment_status_replicas_ready             │
│                                                        │
│  Database Metrics:                                     │
│  - sql_connections (active/idle)                     │
│  - sql_query_duration_seconds                        │
│  - sql_errors_total                                  │
│                                                        │
│  Cache Metrics:                                        │
│  - redis_connected_clients                           │
│  - redis_used_memory_bytes                           │
│  - redis_evicted_keys_total                          │
│  - redis_keyspace_hits_total                         │
│  - redis_keyspace_misses_total                       │
└────────────────────────────────────────────────────────┘
```

### Observability Stack

```
┌────────────────────────────────────────────────────────┐
│       Multi-Layer Observability Architecture           │
│                                                        │
│  Layer 1: Application (in-pod)                        │
│  ┌──────────────────────────────────────────────────┐ │
│  │ - Spring Boot Actuator (/actuator/prometheus)   │ │
│  │ - Micrometer metrics                            │ │
│  │ - OpenTelemetry SDK (for traces)               │ │
│  │ - Structured logging (JSON format)             │ │
│  │                                                  │ │
│  │ Endpoints:                                      │ │
│  │ - /actuator/health (liveness/readiness)        │ │
│  │ - /actuator/prometheus (metrics)               │ │
│  │ - /actuator/metrics (detailed metrics)         │ │
│  └──────────────────────────────────────────────────┘ │
│                            │                           │
│  Layer 2: Sidecar Injection (Istio)                   │
│  ┌──────────────────────────────────────────────────┐ │
│  │ - Envoy proxy sidecar (istio-proxy)            │ │
│  │ - Automatic metrics collection (workload)      │ │
│  │ - Distributed trace sampling (1%)              │ │
│  │ - mTLS encryption + observability              │ │
│  │                                                  │ │
│  │ Metrics:                                        │ │
│  │ - request_duration_seconds_bucket               │ │
│  │ - requests_total (by response code)            │ │
│  │ - request_size_bytes                           │ │
│  └──────────────────────────────────────────────────┘ │
│                            │                           │
│  Layer 3: Node Exporter                              │
│  ┌──────────────────────────────────────────────────┐ │
│  │ - System-level metrics (CPU, memory, disk)     │ │
│  │ - Network interface statistics                 │ │
│  │ - Node temperature/power (if available)        │ │
│  │ - Kubelet metrics (container runtime)          │ │
│  └──────────────────────────────────────────────────┘ │
│                            │                           │
│  Layer 4: Scrape Config (Prometheus)                 │
│  ┌──────────────────────────────────────────────────┐ │
│  │ Targets:                                        │ │
│  │ - dark-store pods:9091/metrics (port 9091)    │ │
│  │ - Kubernetes API server:6443/metrics          │ │
│  │ - etcd:2379/metrics (control plane)           │ │
│  │ - Node exporter:9100/metrics                  │ │
│  │                                                  │ │
│  │ Scrape Interval: 30s                           │ │
│  │ Scrape Timeout: 10s                            │ │
│  │ Retention: 15 days (Prometheus storage)        │ │
│  └──────────────────────────────────────────────────┘ │
│                            │                           │
│  Layer 5: Time-Series DB (Google Cloud Monitoring)  │
│  ┌──────────────────────────────────────────────────┐ │
│  │ - Long-term metric storage (13 months)         │ │
│  │ - Automated dashboards                         │ │
│  │ - Alert policy evaluation                      │ │
│  │ - SLO calculation                              │ │
│  └──────────────────────────────────────────────────┘ │
│                            │                           │
│  Layer 6: Visualization (Grafana + GCP Console)      │
│  ┌──────────────────────────────────────────────────┐ │
│  │ - Custom dashboards (SF Canary Dashboard)      │ │
│  │ - Alert notifications                          │ │
│  │ - On-call paging (PagerDuty)                   │ │
│  └──────────────────────────────────────────────────┘ │
└────────────────────────────────────────────────────────┘
```

### Alert Policies

```
┌────────────────────────────────────────────────────────┐
│              Alert Policy Hierarchy                    │
│                                                        │
│  CRITICAL (SEV-0 - Page immediately)                 │
│  ├─ Error rate > 0.5% (5min window)                  │
│  ├─ Latency p99 > 3000ms (10min window)              │
│  ├─ Pod restart rate > 1 per hour                    │
│  └─ Service down (0 healthy endpoints)               │
│                                                        │
│  HIGH (SEV-1 - Page on-call within 30min)            │
│  ├─ Error rate > 0.1% (30min window)                 │
│  ├─ Latency p95 > 2000ms (30min window)              │
│  ├─ Memory > 80% of limit                            │
│  ├─ CPU > 75% of limit (sustained)                   │
│  └─ Persistent volume > 85% full                     │
│                                                        │
│  MEDIUM (SEV-2 - Review within 2 hours)             │
│  ├─ Latency p50 > 1000ms (1hour window)             │
│  ├─ Memory > 60% of limit                            │
│  ├─ Disk I/O wait > 30%                              │
│  └─ Health check failures (transient)                │
│                                                        │
│  LOW (SEV-3 - Review daily)                         │
│  ├─ Minor configuration warnings                     │
│  └─ Resource optimization suggestions               │
│                                                        │
│  Escalation Paths:                                    │
│  1. Alert fired → Slack channel                      │
│  2. 5 min no ack → Escalate to primary on-call      │
│  3. 30 min no response → Escalate to backup on-call │
│  4. 1 hour → Manager notification + incident        │
│  5. 4 hours → VP-level escalation                    │
└────────────────────────────────────────────────────────┘
```

---

## Security Architecture

### Network Security Model

```
┌──────────────────────────────────────────────────────────┐
│          Defense-in-Depth Security Layers              │
│                                                          │
│  Layer 1: VPC & GCP Network Boundary                   │
│  ├─ Private VPC (10.0.0.0/8)                          │
│  ├─ Subnetwork isolation (10.0.0.0/20)                │
│  ├─ No direct internet access (NAT for egress)        │
│  └─ GCP firewall rules (13 rules, default deny)       │
│                                                          │
│  Layer 2: Kubernetes Network Policies                  │
│  ├─ Ingress policy (only allowed sources)            │
│  │  ├─ Allow: Istio IngressGateway                   │
│  │  ├─ Allow: Other internal services                │
│  │  ├─ Allow: Prometheus scraping                    │
│  │  └─ Allow: Health checks only                     │
│  ├─ Egress policy (outbound restrictions)           │
│  │  ├─ Allow: DNS (53/UDP to kube-system)           │
│  │  ├─ Allow: Cloud SQL (5432/TCP)                  │
│  │  ├─ Allow: Redis (6379/TCP)                      │
│  │  ├─ Allow: Google APIs (443/TCP)                 │
│  │  └─ Deny: Everything else                         │
│  └─ Pod-to-pod isolation (deny by default)          │
│                                                          │
│  Layer 3: Istio Service Mesh Security                  │
│  ├─ mTLS (mutual TLS) - STRICT mode                  │
│  │  ├─ Encrypted pod-to-pod communication           │
│  │  ├─ Certificate rotation (30 days)               │
│  │  └─ Automatic sidecar injection                  │
│  ├─ AuthorizationPolicy (service-level RBAC)       │
│  │  ├─ JWT validation (from identity-service)       │
│  │  ├─ Service principal authentication              │
│  │  └─ Per-endpoint method/path restrictions        │
│  ├─ RequestAuthentication                             │
│  │  ├─ JWT signature verification                   │
│  │  ├─ Audience validation                          │
│  │  └─ Token expiration checks                      │
│  └─ PeerAuthentication                                │
│     └─ Enforces mTLS for all workload traffic       │
│                                                          │
│  Layer 4: Pod Security                                │
│  ├─ Pod Security Policy                              │
│  │  ├─ RunAsNonRoot (enforced)                       │
│  │  ├─ ReadOnlyRootFilesystem                        │
│  │  ├─ AllowPrivilegeEscalation: false               │
│  │  ├─ Capabilities dropped (all)                    │
│  │  └─ SELinux restricted                            │
│  ├─ RBAC (service account permissions)              │
│  │  ├─ dark-store-sa: minimal permissions           │
│  │  ├─ Only read (get, list, watch)                 │
│  │  └─ ConfigMaps/Secrets for specific names       │
│  └─ Workload Identity                                │
│     ├─ Pods authenticate as Google Service Account  │
│     ├─ Per-service token binding                     │
│     └─ Temporary token generation                    │
│                                                          │
│  Layer 5: Data Encryption                            │
│  ├─ In-transit (TLS 1.3)                            │
│  │  ├─ Pod-to-Pod (Istio mTLS)                      │
│  │  ├─ Pod-to-Database (Cloud SQL SSL)              │
│  │  ├─ Pod-to-Cache (Redis SSL)                     │
│  │  └─ Pod-to-APIs (HTTPS only)                     │
│  └─ At-rest (Application-level)                      │
│     ├─ Sensitive data hashed (bcrypt)              │
│     ├─ Payment tokens encrypted (AES-256)           │
│     └─ PII redacted in logs                          │
│                                                          │
│  Layer 6: Secret Management                          │
│  ├─ Credentials in Google Secret Manager             │
│  ├─ Pod mounts secrets as read-only                  │
│  ├─ No secrets in environment variables              │
│  ├─ Automatic rotation support                       │
│  └─ Audit logging for secret access                  │
│                                                          │
│  Audit & Monitoring:                                  │
│  ├─ GCP VPC Flow Logs (network traffic)             │
│  ├─ Cloud Audit Logs (API access)                   │
│  ├─ Application logs (request/response)             │
│  ├─ Istio access logs (mTLS events)                 │
│  └─ Security events to Cloud Logging                │
└──────────────────────────────────────────────────────────┘
```

---

## Disaster Recovery

### RTO/RPO Targets

```
┌────────────────────────────────────────────────────┐
│    Disaster Recovery Objectives by Scenario       │
│                                                    │
│  Scenario: Single Pod Failure                     │
│  RTO: < 30 seconds (automatic restart)           │
│  RPO: 0 (stateless pod)                          │
│  Impact: Reduced capacity (4/5 pods)             │
│  Recovery: Kubernetes automatically reschedules  │
│                                                    │
│  Scenario: Node Failure (1 of 3)                 │
│  RTO: < 2 minutes (pod rescheduling)             │
│  RPO: 0 (stateless pods)                         │
│  Impact: 33% capacity loss, auto-scale up        │
│  Recovery: Evicted pods restart on healthy nodes │
│                                                    │
│  Scenario: Zone Failure (us-west1-b)             │
│  RTO: < 5 minutes (cross-zone scheduling)        │
│  RPO: 0 (pods rescheduled to us-west1-a)        │
│  Impact: Nodes in zone-b offline (~1 pod each)  │
│  Recovery: Istio adjusts traffic routing         │
│                                                    │
│  Scenario: Entire Cluster Failure                │
│  RTO: < 30 minutes (rebuild from Terraform)      │
│  RPO: < 24 hours (daily backups)                 │
│  Impact: Complete dark-store outage             │
│  Recovery: Recreate cluster + restore PVCs      │
│                                                    │
│  Scenario: Data Corruption (PVC)                 │
│  RTO: < 15 minutes (restore from snapshot)      │
│  RPO: < 24 hours (latest snapshot)              │
│  Impact: Order/fulfillment data loss            │
│  Recovery: Create PVC from Velero backup        │
│                                                    │
│  Scenario: Database Corruption (Cloud SQL)       │
│  RTO: < 45 minutes (restore from backup)        │
│  RPO: < 1 hour (automated backups)              │
│  Impact: All data unavailable                    │
│  Recovery: GCP automatic recovery + restore      │
└────────────────────────────────────────────────────┘
```

### Backup Verification

```
Backup verification runs weekly:
- Test restore of snapshots to separate PVC
- Verify data integrity (MD5 checksums)
- Simulate RTO (measure restore time)
- Document any issues for improvement
```

---

## Capacity Planning

### Current Capacity (Phase 2)

```
┌──────────────────────────────────────────────────────┐
│           Phase 2 Capacity Snapshot                  │
│                                                      │
│  Compute:                                            │
│  - Total Nodes: 5 (3 dark-store + 2 system)        │
│  - Total CPU: 14 vCPU (3x4 + 2x2)                 │
│  - Total RAM: 75 GB (3x15 + 2x7.5)                │
│  - Total SSD: 300 GB (3x100 SSD node disk)        │
│                                                      │
│  Container Resources (per pod):                     │
│  - Request: 1 CPU, 2 GB RAM                        │
│  - Limit: 2 CPU, 4 GB RAM                          │
│  - Effective: 5 pods × 2 CPU max = 10 CPU used    │
│                                                      │
│  Storage:                                            │
│  - Persistent Volumes: 225 GB (SSD)                │
│  - Snapshot Storage: 50 GB (compressed)            │
│  - Container Image: 500 MB                         │
│                                                      │
│  Network:                                            │
│  - ILB IP: 10.0.1.100/32                          │
│  - Pod IPs: 10.4.0.0/14 (250K pods possible)      │
│  - Service IPs: 10.8.0.0/20 (4K services possible)│
│                                                      │
│  Estimated Monthly Cost:                            │
│  - GKE Cluster: $300                               │
│  - Node VMs: $600 (3x dark-store + 2x system)    │
│  - Storage (SSD): $150 (225 GB)                   │
│  - Snapshots: $15                                  │
│  - Load Balancer: $50                              │
│  - Monitoring: $20                                 │
│  - Logging: $5                                     │
│  TOTAL: ~$1,140/month                              │
└──────────────────────────────────────────────────────┘
```

### Scaling Strategy

```
Scaling Triggers and Actions:

CPU > 70% (5 min sustained)
├─ HPA: Add 1 pod (max 15 total)
├─ Node-level: Add node if pods pending
└─ Alert: Page on-call if > 80%

Memory > 80% (of limit)
├─ HPA: Scale up (memory-based)
├─ Action: Check for memory leaks
└─ Alert: WARNING - Memory pressure

Latency p99 > 2000ms
├─ Root cause: Database query performance?
├─ Action: Enable query caching
└─ Alert: Page on-call

Error rate > 0.1%
├─ Action: Immediately investigate logs
├─ Possible: Dependency failure (SQL, Redis)
└─ Alert: Page on-call
```

---

## Phase 2 to Phase 3 Transition

Once SF canary validates:
- ✅ 99.5% availability SLO maintained for 7+ days
- ✅ Zero data loss incidents
- ✅ Successful automated failover/recovery
- ✅ Team trained on operations

**Phase 3 (Seattle)** deployment begins with gates:
- New GKE cluster in us-central1
- Similar 5-pod canary configuration
- Cross-region monitoring
- Traffic split: SF 100%, Seattle 0% → Seattle 5% → Seattle 20% (gradual)

---

**Document prepared by**: Platform Engineering Team
**Reviewed by**: Fulfillment Platform Lead, SRE Team
**Last Updated**: March 21, 2026
