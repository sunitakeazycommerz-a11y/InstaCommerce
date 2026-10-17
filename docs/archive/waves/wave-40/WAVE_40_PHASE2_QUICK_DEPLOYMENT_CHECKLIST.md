# Wave 40 Phase 2 - SF Canary Deployment Checklist
## Quick Reference for Execution Team (Mar 24-28, 2026)

---

## PRE-DEPLOYMENT SETUP (Day 0: Mar 23)

### Environment Preparation
- [ ] Verify GCP project access: `gcloud config get-value project`
- [ ] Verify kubectl context: `kubectl config current-context`
- [ ] Verify Helm version: `helm version` (should be 3.12+)
- [ ] Verify ArgoCD CLI: `argocd version`
- [ ] Clone repository: `git clone https://github.com/your-org/instacommerce`
- [ ] Checkout correct branch: `git checkout feat/wave40-service-documentation`

### Pre-Deployment Approvals
- [ ] Platform Lead approval (signature)
- [ ] SRE Lead approval (signature)
- [ ] Fulfillment Ops Lead approval (signature)
- [ ] Security Lead approval (signature)

### Notifications
- [ ] Post to #wave-40-phase2-execution: "Deployment scheduled for Mar 24"
- [ ] Create PagerDuty incident: "Wave 40 Phase 2 SF Canary Deployment"
- [ ] Notify on-call team: deployment timeline + escalation info

---

## PHASE A: TERRAFORM INFRASTRUCTURE (Est. 1-2 hours)
### Execution Date: Mar 24, 2026 | 09:00 UTC

**Pre-flight Checks:**
```bash
# Terminal Session 1
cd /Users/omkarkumar/InstaCommerce/infra/terraform/phases/phase-2/
terraform version           # Should be 1.5+
terraform init              # Initialize working directory
terraform validate          # Check syntax
```
- [ ] `terraform init` completed successfully
- [ ] `terraform validate` returned no errors

**Dry-Run Plan:**
```bash
terraform plan \
  -var="project_id=instacommerce-prod" \
  -var="admin_cidrs=[\"203.0.113.0/24\"]" \
  -out=tfplan
```
- [ ] Plan output shows ~40 resources to create
- [ ] No unexpected changes
- [ ] Output file: tfplan created

**Review Checklist:**
- [ ] GKE cluster resource correct (us-west1, 1.28+)
- [ ] Node pool config correct (3 dark-store, 2 system)
- [ ] Machine types correct (n1-standard-4, n1-standard-2)
- [ ] Firewall rules complete (13 rules)
- [ ] Monitoring resources included (alerts, dashboard, SLO)
- [ ] No resource deletions in plan

**Apply Infrastructure:**
```bash
terraform apply tfplan
# Expected duration: 15-20 minutes
```
- [ ] Terraform apply completed successfully
- [ ] Cluster status: Creating
- [ ] Monitor progress: `gcloud container clusters list --region us-west1`
- [ ] Wait for cluster status: RUNNING (typically 18-22 min)

**Verify Cluster Created:**
```bash
gcloud container clusters describe sf-canary-cluster \
  --region us-west1 --project instacommerce-prod

# Should show:
#   status: RUNNING
#   nodePool count: 2 (dark-store-pool, system-pool)
```
- [ ] Cluster endpoint accessible
- [ ] API server responding
- [ ] Master version: 1.28+

**Store Terraform Outputs:**
```bash
terraform output -json > tf-outputs.json
cat tf-outputs.json | jq '.'
```
- [ ] Save outputs: cluster_name, cluster_endpoint, node_pools
- [ ] Verify outputs file backed up to: `/tmp/wave40-phase2-tf-outputs.json`

**Checkpoint A1: Cluster Ready**
- [ ] GKE cluster: RUNNING ✓
- [ ] Nodes: 5 total (3 + 2) in Ready state ✓
- [ ] Network: VPC + Subnet + Firewall rules configured ✓
- [ ] Monitoring: Alert policies + Dashboard created ✓
- [ ] **Status:** ✅ COMPLETE (duration: ___ min)

---

## PHASE B: KUBERNETES DEPLOYMENT (Est. 2-3 hours)
### Execution Date: Mar 24-25, 2026 | 11:00 UTC

**Pre-flight Checks:**
```bash
# Terminal Session 2
gcloud container clusters get-credentials sf-canary-cluster \
  --region us-west1 --project instacommerce-prod

kubectl cluster-info              # Verify API access
kubectl get nodes                 # Should show 5 nodes ready
kubectl get pods -A               # Check existing workloads
```
- [ ] kubectl context set correctly to sf-canary-cluster
- [ ] All 5 nodes in Ready state
- [ ] No pods currently in sf-canary namespace

**Step B1: Create Namespace & RBAC**
```bash
kubectl apply -f infra/kubernetes/phases/phase-2/01-namespace-sf-canary.yaml

# Verify:
kubectl get namespace sf-canary
kubectl get resourcequota -n sf-canary
kubectl get networkpolicy -n sf-canary
kubectl get serviceaccount -n sf-canary
```
- [ ] Namespace: sf-canary created (Active)
- [ ] ResourceQuota: sf-canary-quota active
- [ ] NetworkPolicy: sf-canary-default-deny + sf-canary-allow-dns
- [ ] ServiceAccount: dark-store-sa created
- [ ] RBAC: dark-store-rolebinding active

**Checkpoint B1: Namespace Ready**
- [ ] Namespace: ✓
- [ ] RBAC: ✓
- [ ] Network policies: ✓
- [ ] **Status:** ✅ COMPLETE (duration: ___ min)

**Step B2: Deploy Dark-Store Workload**
```bash
kubectl apply -f infra/kubernetes/phases/phase-2/02-dark-store-deployment-sf.yaml

# Monitor rollout:
kubectl rollout status deployment/dark-store-service \
  -n sf-canary --timeout=10m

# Watch pod startup:
watch kubectl get pods -n sf-canary -l app=dark-store
```
- [ ] Deployment created: dark-store-service
- [ ] Pod creation started: observe 5 pods in Pending -> Running
- [ ] Pod startup time: <45 seconds per pod
- [ ] All probes passing: Startup -> Readiness -> Liveness
- [ ] Service: dark-store-service (ClusterIP assigned)

**Verify Pod Health:**
```bash
kubectl get pods -n sf-canary -l app=dark-store -o wide
kubectl describe pod <pod-name> -n sf-canary | grep -A5 Conditions
```
- [ ] All 5 pods: Running status
- [ ] All 5 pods: Ready=1/1
- [ ] No restart count (or very low)
- [ ] Events: no Error or Warning

**Checkpoint B2: Deployment Ready**
- [ ] Replicas: 5/5 ✓
- [ ] Pods: All Running ✓
- [ ] Readiness probes: 100% passing ✓
- [ ] Service endpoints: 5/5 ✓
- [ ] **Status:** ✅ COMPLETE (duration: ___ min)

**Step B3: Deploy Istio Traffic Routing**
```bash
kubectl apply -f infra/kubernetes/phases/phase-2/03-traffic-routing-sf-100pct.yaml

# Verify:
kubectl get gateway -n sf-canary
kubectl get virtualservice -n sf-canary
kubectl get destinationrule -n sf-canary
kubectl get requestauthentication -n sf-canary
kubectl get authorizationpolicy -n sf-canary
kubectl get peerauthentication -n sf-canary
```
- [ ] Gateway: dark-store-gateway created
- [ ] VirtualService: dark-store-vs (100% SF canary weight)
- [ ] DestinationRule: dark-store-dr (LEAST_REQUEST LB)
- [ ] RequestAuthentication: JWT validation enabled
- [ ] AuthorizationPolicy: Access control rules active
- [ ] PeerAuthentication: mTLS STRICT

**Verify Traffic Routing:**
```bash
kubectl get vs dark-store-vs -n sf-canary -o yaml | grep -A10 http:
```
- [ ] HTTP route: 100% weight to dark-store-service ✓
- [ ] TCP route (gRPC): 100% weight to port 9090 ✓

**Checkpoint B3: Traffic Routing Ready**
- [ ] Gateway: ✓
- [ ] VirtualService: 100% SF canary ✓
- [ ] Security policies: ✓
- [ ] **Status:** ✅ COMPLETE (duration: ___ min)

**Step B4: Deploy Storage**
```bash
kubectl apply -f infra/kubernetes/phases/phase-2/05-storage-pvc-sf.yaml

# Verify:
kubectl get storageclass dark-store-ssd
kubectl get pvc -n sf-canary
kubectl get pv | grep dark-store
```
- [ ] StorageClass: dark-store-ssd created
- [ ] PVCs: 4 in Bound state (orders, fulfillment, cache, logs)
- [ ] Total storage: 275Gi provisioned
- [ ] All PVCs in Bound status (not Pending)

**Checkpoint B4: Storage Ready**
- [ ] StorageClass: ✓
- [ ] PVCs: 4/4 Bound ✓
- [ ] Storage capacity: 275Gi ✓
- [ ] **Status:** ✅ COMPLETE (duration: ___ min)

**Final K8s Verification:**
```bash
kubectl get all -n sf-canary
kubectl top pods -n sf-canary -l app=dark-store
kubectl top nodes
```
- [ ] CPU utilization: <30% per pod
- [ ] Memory utilization: <50% per pod
- [ ] Node utilization: 40-50% total
- [ ] No pod restarts (restart count = 0)

**Checkpoint B5: Kubernetes Complete**
- [ ] Namespace: ✓
- [ ] Deployment: 5/5 ready ✓
- [ ] Traffic routing: 100% SF ✓
- [ ] Storage: 275Gi ✓
- [ ] **Status:** ✅ COMPLETE (total duration: ___ min)

---

## PHASE C: HELM RELEASE DEPLOYMENT (Est. 45-60 min)
### Execution Date: Mar 25, 2026 | 14:00 UTC

**Pre-flight Checks:**
```bash
helm version                    # Should be 3.12+
helm repo update
helm lint deploy/helm/
```
- [ ] Helm binary available
- [ ] Chart lint passed (no errors or critical warnings)

**Deploy Helm Release:**
```bash
helm install instacommerce-sf deploy/helm/ \
  -f deploy/helm/values.yaml \
  -f deploy/helm/values-canary-sf.yaml \
  --namespace sf-canary \
  --wait \
  --timeout 10m

# Expected output:
#   NAME: instacommerce-sf
#   NAMESPACE: sf-canary
#   STATUS: deployed
#   REVISION: 1
```
- [ ] Release created: instacommerce-sf
- [ ] Status: deployed
- [ ] Revision: 1
- [ ] Wait completed (all resources ready)

**Verify Helm Release:**
```bash
helm list -n sf-canary
helm status instacommerce-sf -n sf-canary
helm get values instacommerce-sf -n sf-canary
```
- [ ] Release: instacommerce-sf (deployed)
- [ ] Namespace: sf-canary
- [ ] Canary weight: 100
- [ ] All services enabled

**Checkpoint C1: Helm Ready**
- [ ] Release deployed: ✓
- [ ] Status: deployed ✓
- [ ] All values correct: ✓
- [ ] **Status:** ✅ COMPLETE (duration: ___ min)

---

## PHASE D: ARGOCD GITOPS (Est. 15-20 min)
### Execution Date: Mar 25, 2026 | 15:00 UTC

**Pre-flight Checks:**
```bash
argocd version                  # Should be 2.0+
argocd login argocd.your-org.dev --username admin
argocd account list
```
- [ ] ArgoCD CLI available
- [ ] Logged in successfully
- [ ] Can access ArgoCD server

**Create ArgoCD Application:**
```bash
kubectl apply -f argocd/applications/canary-sf-fulfillment.yaml

# Verify:
kubectl get application -n argocd
argocd app list | grep canary-sf
argocd app get canary-sf-fulfillment
```
- [ ] Application created: canary-sf-fulfillment
- [ ] Namespace: argocd
- [ ] Project: instacommerce-canary

**Wait for Sync:**
```bash
argocd app get canary-sf-fulfillment
# Wait until: Sync Status = Synced
```
- [ ] Sync Status: Synced
- [ ] Health Status: Healthy
- [ ] Last Sync: recent (within 5 min)

**Manual Sync (if needed):**
```bash
argocd app sync canary-sf-fulfillment
# Expected duration: 2-3 minutes
```
- [ ] Sync operation completed
- [ ] All resources synced
- [ ] No failed resources

**Verify Application:**
```bash
argocd app get canary-sf-fulfillment -o yaml | grep -E "syncStatus|healthStatus"
kubectl describe app canary-sf-fulfillment -n argocd
```
- [ ] syncStatus.status: Synced
- [ ] healthStatus.status: Healthy
- [ ] No conditions with error

**Checkpoint D1: ArgoCD Ready**
- [ ] Application created: ✓
- [ ] Sync Status: Synced ✓
- [ ] Health Status: Healthy ✓
- [ ] **Status:** ✅ COMPLETE (duration: ___ min)

---

## VALIDATION & HEALTH CHECKS (Est. 1-2 hours)
### Execution Date: Mar 25-26, 2026

### V1: Infrastructure Health
```bash
# Check cluster
gcloud container clusters describe sf-canary-cluster \
  --region us-west1 --project instacommerce-prod

# Check nodes
kubectl get nodes -o wide
kubectl describe nodes | grep -E "Allocatable|Allocated"

# Check firewall rules
gcloud compute firewall-rules list --filter="name~'sf-canary'"
```
- [ ] Cluster: RUNNING status
- [ ] Nodes: 5 ready (3 dark-store + 2 system)
- [ ] Node disk: >80Gi available
- [ ] Node memory: >70% available
- [ ] Firewall rules: 13 active

**V1 Sign-off:** _____ (initials) Date: _____ Status: ✅

### V2: Kubernetes Deployment
```bash
# Pod status
kubectl get pods -n sf-canary -l app=dark-store -o wide

# Probe checks
kubectl get pods -n sf-canary -l app=dark-store \
  -o jsonpath='{.items[*].status.containerStatuses[0].ready}'

# Service endpoints
kubectl get endpoints -n sf-canary dark-store-service
```
- [ ] All 5 pods: Running
- [ ] All 5 pods: Ready=true
- [ ] All readiness probes: passing
- [ ] Service: 5 endpoints active
- [ ] No restart count >0

**V2 Sign-off:** _____ (initials) Date: _____ Status: ✅

### V3: Networking & Istio
```bash
# DNS resolution
nslookup dark-store-service.sf-canary.svc.cluster.local

# mTLS check
kubectl get peerauthentication -n sf-canary

# Authorization policies
kubectl get authorizationpolicy -n sf-canary
```
- [ ] DNS: resolves to service IP
- [ ] mTLS: STRICT mode enabled
- [ ] AuthzPolicy: 4+ rules active
- [ ] RequestAuth: JWT validation enabled

**V3 Sign-off:** _____ (initials) Date: _____ Status: ✅

### V4: Application Health
```bash
# Port-forward to pod
kubectl port-forward -n sf-canary pod/<pod-name> 8080:8080 &

# Health checks (from local)
curl http://localhost:8080/actuator/health
curl http://localhost:8080/actuator/health/readiness
curl http://localhost:8080/actuator/health/liveness
curl http://localhost:8080/actuator/prometheus
```
- [ ] /actuator/health: 200 OK
- [ ] /actuator/health/readiness: 200 OK
- [ ] /actuator/health/liveness: 200 OK
- [ ] /actuator/prometheus: 200 OK (metrics available)

**V4 Sign-off:** _____ (initials) Date: _____ Status: ✅

### V5: Resource Utilization
```bash
# Metrics
kubectl top pods -n sf-canary -l app=dark-store
kubectl top nodes

# HPA status
kubectl get hpa -n sf-canary
kubectl describe hpa dark-store-hpa -n sf-canary
```
- [ ] CPU per pod: <500m (under 1000m request)
- [ ] Memory per pod: <1Gi (under 2Gi request)
- [ ] Node CPU: 40-50% utilized
- [ ] Node memory: 50-60% utilized
- [ ] HPA: active, no scaling needed

**V5 Sign-off:** _____ (initials) Date: _____ Status: ✅

### V6: Monitoring & Alerts
```bash
# Prometheus metrics
kubectl port-forward -n prometheus svc/prometheus 9090:9090 &
# Visit: http://localhost:9090/targets

# Alert status
kubectl get alertmanagerconfig -n monitoring

# Dashboard verification
gcloud monitoring dashboards list | grep dark-store
```
- [ ] Prometheus: all targets UP
- [ ] PodMonitor: dark-store-metrics active
- [ ] Alert policies: 6 configured (all Active)
- [ ] Dashboard: accessible and showing data
- [ ] SLO: 99.5% target set

**V6 Sign-off:** _____ (initials) Date: _____ Status: ✅

### V7: Security Verification
```bash
# Pod security context
kubectl get pods -n sf-canary -l app=dark-store \
  -o jsonpath='{.items[0].spec.securityContext}'

# Network policies
kubectl describe networkpolicy sf-canary-default-deny -n sf-canary

# Firewall rules
gcloud compute firewall-rules describe sf-canary-allow-internal
```
- [ ] runAsNonRoot: true
- [ ] runAsUser: 1000
- [ ] Capabilities: dropped ALL
- [ ] NetworkPolicy: DENY all by default
- [ ] Firewall: ingress restricted to 10.0.0.0/8

**V7 Sign-off:** _____ (initials) Date: _____ Status: ✅

### V8: Backup & Disaster Recovery
```bash
# Check Velero schedule
kubectl get schedule -n velero

# PVC snapshots
kubectl get volumesnapshot -n sf-canary
```
- [ ] Velero schedule: daily at 2 AM UTC
- [ ] Backup retention: 30 days
- [ ] Volume snapshots: created for orders + fulfillment
- [ ] Restore plan: documented

**V8 Sign-off:** _____ (initials) Date: _____ Status: ✅

---

## DEPLOYMENT METRICS BASELINE
### Captured at: _________________ (datetime)

```
Availability:            100% (target: 99.95%)
Error Rate:              0.0% (target: <0.5%)
Latency p50:             ~150ms (target: <500ms)
Latency p95:             ~350ms (target: <1500ms)
Latency p99:             ~450ms (target: <2000ms)
CPU Utilization:         ~25% (target: 30-40%)
Memory Utilization:      ~45% (target: 50-60%)
Pod Restarts:            0 (target: <1/day)
Delivery SLA:            N/A (pilot phase)
Inventory Conflicts:     0 (target: 0)
```

**Metrics Captured By:** _____________________ Date: _____

---

## POST-DEPLOYMENT HANDOFF (Mar 28)

### Go-Live Readiness Review
- [ ] All 8 validation checkpoints passed
- [ ] No P1/P2 issues outstanding
- [ ] On-call team briefed and ready
- [ ] Rollback plan tested and documented
- [ ] Run books prepared
- [ ] Monitoring dashboards verified
- [ ] Alert routing validated

### Sign-Off for Go-Live
**Infrastructure:** _________________________ Date: _____
**Application:** ______________________________ Date: _____
**Operations:** _______________________________ Date: _____
**Final Approval:** ____________________________ Date: _____

### 24-Hour Post-Deployment Monitoring
- [ ] Hour 1: Check error rate + latency (should be stable)
- [ ] Hour 4: Check resource utilization (no unexpected growth)
- [ ] Hour 8: Check pod restart count (should be 0)
- [ ] Hour 24: Capture detailed metrics for post-mortem

### Go-Live Cutover (If Approved)
```bash
# Traffic routing commands (when ready):
# TO CUT OVER 100% TRAFFIC TO SF:
kubectl patch vs dark-store-vs -n sf-canary \
  --type merge -p '{"spec":{"http":[{"route":[{"destination":{"host":"dark-store-service","subset":"sf-canary"},"weight":100}]}]}}'
```
- [ ] Traffic cutover command prepared
- [ ] Ready command documented
- [ ] Rollback command ready: (see Phase C Rollback)

---

## TROUBLESHOOTING QUICK REFERENCE

**Issue: Pods stuck in Pending**
```bash
kubectl describe pod <pod-name> -n sf-canary
# Check: ResourceQuota, PVC availability, node resources
```

**Issue: High Error Rate**
```bash
kubectl logs -n sf-canary -l app=dark-store --tail=50
kubectl get events -n sf-canary
# Check: logs for exceptions, events for warnings
```

**Issue: Pod Restarts**
```bash
kubectl get pods -n sf-canary -l app=dark-store \
  -o jsonpath='{.items[*].status.containerStatuses[0].restartCount}'
kubectl logs -n sf-canary -l app=dark-store --previous
# Analyze: previous logs for crash reason
```

**Issue: Network Connectivity**
```bash
kubectl exec -n sf-canary <pod-name> -- \
  curl http://dark-store-service:8080/actuator/health
# Test: internal service connectivity
```

**Issue: Prometheus Metrics Not Appearing**
```bash
kubectl get podmonitor -n sf-canary
kubectl describe podmonitor dark-store-metrics -n sf-canary
kubectl port-forward -n prometheus svc/prometheus 9090:9090
# Verify: PodMonitor configuration and Prometheus targets
```

---

## EMERGENCY ROLLBACK PROCEDURES

**ROLLBACK LEVEL 1: Pod Restart (If single pod fails)**
```bash
kubectl delete pod <pod-name> -n sf-canary
# New pod auto-scheduled in ~30 seconds
```

**ROLLBACK LEVEL 2: Deployment Rollback (If deployment broken)**
```bash
kubectl rollout undo deployment/dark-store-service -n sf-canary
# Previous replicas: active within 1-2 minutes
```

**ROLLBACK LEVEL 3: ArgoCD Rollback (If entire app broken)**
```bash
argocd app rollback canary-sf-fulfillment <revision>
# Previous state: restored within 5-10 minutes
```

**ROLLBACK LEVEL 4: Full Infrastructure Rollback**
```bash
cd infra/terraform/phases/phase-2/
terraform destroy -auto-approve
# Cluster: destroyed within 15-20 minutes
# Re-deploy: terraform apply (takes 20 min)
```

---

**Deployment Window:** Mar 24-28, 2026
**On-Call Lead:** _________________________ Phone: _____
**Deployment Lead:** _______________________ Email: _____

**FINAL STATUS:** ⬜ NOT STARTED | 🔄 IN PROGRESS | ✅ COMPLETE | ⛔ FAILED

---

Report generated: 2026-03-22
For questions, contact: platform-engineering@instacommerce.dev
