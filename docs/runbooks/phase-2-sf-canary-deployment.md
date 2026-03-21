# SF Canary Deployment Runbook
## Wave 40 Phase 2 - Dark-Store Canary Deployment (100% Traffic)

**Document Version**: 1.0
**Created**: March 21, 2026
**Last Updated**: March 21, 2026
**Status**: Production Ready
**Author**: Platform Engineering Team
**Owners**: Fulfillment Platform Team

---

## Table of Contents

1. [Pre-Deployment Checklist](#pre-deployment-checklist)
2. [Infrastructure Setup](#infrastructure-setup)
3. [Kubernetes Manifests Deployment](#kubernetes-manifests-deployment)
4. [Traffic Routing Verification](#traffic-routing-verification)
5. [Monitoring Setup](#monitoring-setup)
6. [Rollback Procedures](#rollback-procedures)
7. [Success Metrics & Validation](#success-metrics--validation)
8. [Troubleshooting Guide](#troubleshooting-guide)
9. [Post-Deployment Verification](#post-deployment-verification)

---

## Pre-Deployment Checklist

### Phase 0: Environment & Credentials Setup

```bash
# Set environment variables
export GCP_PROJECT_ID="instacommerce-prod"
export GKE_CLUSTER_NAME="sf-canary-cluster"
export GKE_REGION="us-west1"
export GKE_ZONE="us-west1-b"
export NAMESPACE="sf-canary"
export KUBE_CONTEXT="gke_${GCP_PROJECT_ID}_${GKE_ZONE}_${GKE_CLUSTER_NAME}"

# Authenticate with GCP
gcloud auth login
gcloud config set project ${GCP_PROJECT_ID}

# Verify authentication
gcloud auth list
# Expected output: Current: <your-email>

# Get GKE credentials (NOTE: Cluster may not exist yet - this is prepared for after Terraform apply)
# gcloud container clusters get-credentials ${GKE_CLUSTER_NAME} \
#   --zone ${GKE_ZONE} \
#   --project ${GCP_PROJECT_ID}

# Verify kubectl configuration
# kubectl cluster-info
# Expected: Kubernetes master is running at https://<endpoint>
```

### Phase 1: Resource Quota Verification

```bash
# Verify GCP quota availability
gcloud compute project-info describe --project=${GCP_PROJECT_ID} \
  --format='value(quotas[name="CPUS"].limit)'
# Expected: >= 100 CPUs available

gcloud compute project-info describe --project=${GCP_PROJECT_ID} \
  --format='value(quotas[name="IN_USE_ADDRESSES"].limit)'
# Expected: >= 10 static IP addresses available

# Check persistent storage quota
gcloud compute project-info describe --project=${GCP_PROJECT_ID} \
  --format='value(quotas[name="SSD_TOTAL_GB"].limit)'
# Expected: >= 500 GB SSD available

# Verify regional quota for us-west1
gcloud compute regions describe us-west1 \
  --project=${GCP_PROJECT_ID} \
  --format='value(quotas[].quota)'
```

### Phase 2: VPC & Network Prerequisites

```bash
# Verify VPC exists
gcloud compute networks describe instacommerce-vpc \
  --project=${GCP_PROJECT_ID}
# Expected output: Shows network details

# Verify subnetwork exists
gcloud compute networks subnets describe sf-canary-subnet \
  --region=${GKE_REGION} \
  --project=${GCP_PROJECT_ID}
# Expected output: Shows subnet CIDR and secondary ranges

# Verify secondary IP ranges
gcloud compute networks subnets describe sf-canary-subnet \
  --region=${GKE_REGION} \
  --project=${GCP_PROJECT_ID} \
  --format='value(secondaryIpRanges[].name)'
# Expected: pods-range, services-range

# Test VPC connectivity (if bastion exists)
gcloud compute ssh <bastion-instance> \
  --zone=${GKE_ZONE} \
  --project=${GCP_PROJECT_ID} \
  --command="curl -s https://www.google.com" -q
# Expected: Successful connection
```

### Phase 3: Dependencies Check

```bash
# Verify Cloud SQL is running
gcloud sql instances describe dark-store-db \
  --project=${GCP_PROJECT_ID}
# Expected: Instance status = RUNNABLE

# Verify Redis is running
gcloud redis instances describe dark-store-cache \
  --region=${GKE_REGION} \
  --project=${GCP_PROJECT_ID}
# Expected: Instance status = READY

# Verify Kafka is accessible (if applicable)
gcloud bigtable instances describe dark-store-kafka \
  --project=${GCP_PROJECT_ID}
# Expected: Instance state = READY (or verify through private IP)

# Check Cloud Logging and Monitoring are enabled
gcloud logging buckets list --project=${GCP_PROJECT_ID}
gcloud monitoring metrics-descriptors list --project=${GCP_PROJECT_ID} \
  --filter="metric.type:kubernetes.io*" \
  --limit=1
```

### Phase 4: Tool Versions Check

```bash
# Verify Terraform version
terraform version
# Expected: Terraform v1.5.0 or later

# Verify kubectl version
kubectl version --client --short
# Expected: v1.28.0 or compatible with GKE master

# Verify gcloud CLI
gcloud version
# Expected: Latest stable (run 'gcloud components update' if needed)

# Verify Helm version (for future deployments)
helm version --short
# Expected: v3.12.0 or later

# Verify Docker version (for custom image builds)
docker --version
# Expected: Docker version 24.0.0 or later
```

---

## Infrastructure Setup

### Step 1: Initialize Terraform

```bash
cd /Users/omkarkumar/InstaCommerce/infra/terraform/phases/phase-2

# Initialize Terraform (download providers)
terraform init

# Expected output:
# Terraform has been successfully initialized!
# The following providers are required to be configured: google
```

### Step 2: Create terraform.tfvars

```bash
cat > terraform.tfvars << 'EOF'
project_id = "instacommerce-prod"
region = "us-west1"
network_name = "instacommerce-vpc"
subnetwork_name = "sf-canary-subnet"
pods_range_name = "pods-range"
services_range_name = "services-range"

cluster_name = "sf-canary-cluster"
dark_store_node_count = 3
dark_store_node_max_count = 10
dark_store_machine_type = "n1-standard-4"
system_node_count = 2

ilb_ip_address = "10.0.1.100"

admin_cidrs = [
  "203.0.113.0/24",  # Replace with your admin network
  "198.51.100.0/24"  # Add your office/VPN CIDR
]

dark_store_endpoint = "dark-store-sf.instacommerce.internal"
notification_channels = []  # Add later after notification channels are created

common_labels = {
  environment = "canary"
  region = "us-west1"
  phase = "2"
  deployment = "dark-store"
  managed_by = "terraform"
}
EOF

# Verify tfvars file
cat terraform.tfvars
```

### Step 3: Plan Terraform

```bash
# Generate Terraform plan
terraform plan -var-file=terraform.tfvars -out=tf.plan

# Expected output:
# Plan: XX to add, 0 to change, 0 to destroy.
#
# To perform exactly these actions, run the command below to apply:
#     terraform apply "tf.plan"

# Review the plan for:
# - GKE cluster creation in us-west1
# - Node pools creation (dark-store-pool, system-pool)
# - Internal Load Balancer setup
# - Firewall rules (13 rules)
# - Monitoring dashboards and alert policies
```

### Step 4: Apply Terraform

```bash
# Apply the plan (this takes 15-20 minutes)
terraform apply tf.plan

# Expected output after ~20 minutes:
#
# Apply complete! Resources have been created:
#
# Outputs:
# cluster_name = "sf-canary-cluster"
# cluster_endpoint = "1.2.3.4"
# ilb_ip_address = "10.0.1.100"
# workload_identity_service_account = "dark-store-service@instacommerce-prod.iam.gserviceaccount.com"

# Save outputs for reference
terraform output > tf_outputs.json
```

### Step 5: Verify GKE Cluster

```bash
# Get cluster credentials
gcloud container clusters get-credentials ${GKE_CLUSTER_NAME} \
  --zone ${GKE_ZONE} \
  --project ${GCP_PROJECT_ID}

# Verify cluster connectivity
kubectl cluster-info
# Expected: Kubernetes master is running at https://<endpoint>

# Check cluster status
gcloud container clusters describe ${GKE_CLUSTER_NAME} \
  --zone ${GKE_ZONE} \
  --project ${GCP_PROJECT_ID} \
  --format='value(status)'
# Expected: RUNNING

# Verify node pools
kubectl get nodes -o wide
# Expected: 5 nodes total (3 dark-store + 2 system)

# Check node labels and taints
kubectl get nodes --show-labels
# Expected: dark-store nodes have label workload=dark-store and taint workload=dark-store:NoSchedule

# Verify system components
kubectl get pods -n kube-system
# Expected: coredns, kube-proxy, metrics-server pods running
```

---

## Kubernetes Manifests Deployment

### Step 1: Create Namespace and RBAC

```bash
cd /Users/omkarkumar/InstaCommerce/infra/kubernetes/phases/phase-2

# Apply namespace, RBAC, and resource quotas
kubectl apply -f 01-namespace-sf-canary.yaml

# Expected output:
# namespace/sf-canary created
# resourcequota/sf-canary-quota created
# networkpolicy.networking.k8s.io/sf-canary-default-deny created
# serviceaccount/dark-store-sa created
# role.rbac.authorization.k8s.io/dark-store-role created
# rolebinding.rbac.authorization.k8s.io/dark-store-rolebinding created
# priorityclass.scheduling.k8s.io/canary-medium-priority created
# limitrange/sf-canary-limits created
# networkpolicy.networking.k8s.io/sf-canary-allow-dns created
# configmap/sf-canary-config created

# Verify namespace
kubectl get ns sf-canary
# Expected: sf-canary   Active

# Verify resource quota
kubectl describe resourcequota sf-canary-quota -n sf-canary
# Expected: Shows quotas for CPU, memory, pods, storage

# Verify service account
kubectl get sa dark-store-sa -n sf-canary
# Expected: Displays service account details
```

### Step 2: Create Storage (PVCs)

```bash
# Apply storage class and PVCs
kubectl apply -f 05-storage-pvc-sf.yaml

# Expected output:
# storageclass.storage.k8s.io/dark-store-ssd created
# persistentvolumeclaim/dark-store-orders-pvc created
# persistentvolumeclaim/dark-store-fulfillment-pvc created
# persistentvolumeclaim/dark-store-cache-pvc created
# persistentvolumeclaim/dark-store-logs-pvc created

# Verify storage class
kubectl get storageclass dark-store-ssd
# Expected: dark-store-ssd   pd.csi.storage.gke.io   Delete   Immediate

# Monitor PVC provisioning (takes 1-2 minutes)
kubectl get pvc -n sf-canary -w
# Expected: All PVCs transition from Pending to Bound

# After PVCs are bound, verify
kubectl get pvc -n sf-canary
# Expected:
# NAME                              STATUS   VOLUME                 ...
# dark-store-orders-pvc             Bound    pvc-xxx                ...
# dark-store-fulfillment-pvc        Bound    pvc-xxx                ...
# dark-store-cache-pvc              Bound    pvc-xxx                ...
# dark-store-logs-pvc               Bound    pvc-xxx                ...
```

### Step 3: Apply Deployment

```bash
# Apply dark-store deployment with 5 replicas
kubectl apply -f 02-dark-store-deployment-sf.yaml

# Expected output:
# deployment.apps/dark-store-service created
# service/dark-store-service created
# backendconfig.cloud.google.com/dark-store-backend-config created
# horizontalpodautoscaler.autoscaling/dark-store-hpa created
# podmonitor.monitoring.coreos.com/dark-store-metrics created

# Verify deployment is created
kubectl get deployment -n sf-canary
# Expected: dark-store-service   5/0   0   0   1s

# Watch pod startup (takes 1-3 minutes)
kubectl rollout status deployment/dark-store-service -n sf-canary --timeout=5m

# Expected output after completion:
# deployment "dark-store-service" successfully rolled out

# Verify all pods are running
kubectl get pods -n sf-canary
# Expected:
# NAME                                  READY   STATUS    RESTARTS   ...
# dark-store-service-xxx-xxxxx          1/1     Running   0          ...
# dark-store-service-xxx-xxxxx          1/1     Running   0          ...
# dark-store-service-xxx-xxxxx          1/1     Running   0          ...
# dark-store-service-xxx-xxxxx          1/1     Running   0          ...
# dark-store-service-xxx-xxxxx          1/1     Running   0          ...

# Verify pod distribution across nodes
kubectl get pods -n sf-canary -o wide | awk '{print $1, $7}'
# Expected: Pods distributed across 3 different nodes
```

### Step 4: Configure Networking Policies

```bash
# Apply network policies for traffic isolation
kubectl apply -f 04-networking-policies-sf.yaml

# Expected output:
# networkpolicy.networking.k8s.io/dark-store-allow-ingress created
# networkpolicy.networking.k8s.io/dark-store-allow-egress created
# networkpolicy.networking.k8s.io/sf-canary-deny-all created
# networkpolicy.networking.k8s.io/sf-canary-allow-dns-to-coredns created
# networkpolicy.networking.k8s.io/sf-canary-allow-internal created
# networkpolicy.networking.k8s.io/dark-store-isolate-namespace created
# networkpolicy.networking.k8s.io/dark-store-external-egress created
# networkpolicy.networking.k8s.io/dark-store-cloudsql-only created
# networkpolicy.networking.k8s.io/dark-store-redis-only created
# networkpolicy.networking.k8s.io/dark-store-allow-metrics created
# networkpolicy.networking.k8s.io/dark-store-allow-logging created

# Verify network policies
kubectl get networkpolicy -n sf-canary
# Expected: 11 network policies listed
```

### Step 5: Configure Traffic Routing (Istio)

```bash
# Verify Istio is installed (prerequisite)
kubectl get namespace | grep istio-system
# Expected: istio-system   Active

# Verify Istio version
kubectl get deployment -n istio-system istio-ingressgateway -o jsonpath='{.spec.template.spec.containers[0].image}'

# Apply Istio configuration
kubectl apply -f 03-traffic-routing-sf-100pct.yaml

# Expected output:
# gateway.networking.istio.io/dark-store-gateway created
# virtualservice.networking.istio.io/dark-store-vs created
# destinationrule.networking.istio.io/dark-store-dr created
# requestauthentication.security.istio.io/dark-store-jwt-auth created
# authorizationpolicy.security.istio.io/dark-store-authz created
# peerauthentication.security.istio.io/dark-store-mtls created
# telemetry.telemetry.istio.io/dark-store-telemetry created
# serviceentry.networking.istio.io/dark-store-metrics created
# proxyconfig.networking.istio.io/dark-store-proxy-config created

# Verify Istio resources
kubectl get gateway -n sf-canary
kubectl get virtualservice -n sf-canary
kubectl get destinationrule -n sf-canary

# Verify mTLS is enforced
kubectl get peerauthentication -n sf-canary
```

---

## Traffic Routing Verification

### Step 1: Verify Service Endpoints

```bash
# Check if service has endpoints
kubectl get endpoints dark-store-service -n sf-canary
# Expected:
# NAME                    ENDPOINTS                                    ...
# dark-store-service      10.1.x.x:8080,10.1.x.x:8080,...              ...

# Verify all 5 pods are registered as endpoints
kubectl get endpoints dark-store-service -n sf-canary \
  -o jsonpath='{.subsets[0].addresses | length}'
# Expected: 5

# Test connectivity to service from a debug pod
kubectl run -it --rm debug --image=curlimages/curl --restart=Never -- \
  curl -v http://dark-store-service.sf-canary:8080/actuator/health
# Expected output:
# < HTTP/1.1 200 OK
# {"status":"UP",...}
```

### Step 2: Verify Istio VirtualService

```bash
# Check VirtualService configuration
kubectl get vs dark-store-vs -n sf-canary -o yaml | grep -A 10 "route:"
# Expected: Shows 100% traffic weight to sf-canary subset

# Verify traffic distribution
kubectl get vs dark-store-vs -n sf-canary -o jsonpath='{.spec.http[0].route[0].weight}'
# Expected: 100

# Test through Istio ingress gateway
kubectl get svc istio-ingressgateway -n istio-system -o jsonpath='{.status.loadBalancer.ingress[0].ip}'
# Expected: Shows external IP (or pending if internal)

# For internal load balancer, test with port-forward
kubectl port-forward svc/dark-store-service 8080:8080 -n sf-canary &
sleep 2
curl http://localhost:8080/actuator/health
# Expected: {"status":"UP",...}
kill %1
```

### Step 3: Verify Load Balancing

```bash
# Generate traffic to verify load distribution
for i in {1..10}; do
  kubectl exec -it $(kubectl get pod -n sf-canary --no-headers | head -1 | awk '{print $1}') \
    -n sf-canary -- \
    curl -s http://dark-store-service:8080/actuator/health | jq .
done

# Check pod logs for request distribution
kubectl logs -n sf-canary -l app=dark-store --timestamps=true --tail=50 | \
  grep "request" | head -20

# Verify session affinity is working
kubectl get svc dark-store-service -n sf-canary -o jsonpath='{.spec.sessionAffinity}'
# Expected: ClientIP
```

### Step 4: Test Canary Failover

```bash
# Delete one pod to test automatic restart
POD_NAME=$(kubectl get pods -n sf-canary -o name | head -1 | cut -d'/' -f2)
kubectl delete pod $POD_NAME -n sf-canary

# Verify pod is recreated immediately
kubectl get pods -n sf-canary -w
# Expected: New pod created with status "Pending" -> "Running"

# Verify replica count remains 5
kubectl get deployment dark-store-service -n sf-canary -o jsonpath='{.status.replicas}'
# Expected: 5
```

---

## Monitoring Setup

### Step 1: Verify Prometheus Scraping

```bash
# Check if Prometheus is scraping dark-store pods
kubectl port-forward -n prometheus svc/prometheus 9090:9090 &
sleep 2

# Query Prometheus for dark-store metrics
curl -s 'http://localhost:9090/api/v1/query?query=rate(http_requests_total{namespace="sf-canary"}[5m])' | jq '.'

# Expected output: Shows metrics with values

kill %1

# Verify PodMonitor is created
kubectl get podmonitor -n sf-canary
# Expected: dark-store-metrics
```

### Step 2: Verify Cloud Monitoring Dashboards

```bash
# List monitoring dashboards
gcloud monitoring dashboards list --project=${GCP_PROJECT_ID} \
  --filter="displayName:'SF Dark Store'" \
  --format='value(name)'

# Get dashboard URL
DASHBOARD_ID=$(terraform output -raw dashboard_id)
echo "https://console.cloud.google.com/monitoring/dashboards/custom/${DASHBOARD_ID}?project=${GCP_PROJECT_ID}"
```

### Step 3: View Real-time Metrics

```bash
# Using kubectl port-forward to Prometheus
kubectl port-forward -n prometheus svc/prometheus 9090:9090 &
PROM_PID=$!

# Access Prometheus in browser: http://localhost:9090
# Queries to try:
# - rate(http_requests_total[5m])
# - histogram_quantile(0.99, http_request_duration_seconds)
# - container_memory_usage_bytes{pod=~"dark-store-.*"}

sleep 300
kill $PROM_PID
```

### Step 4: Configure Alert Notifications

```bash
# Get alert policy IDs from Terraform output
terraform output alert_policies

# Update notification channels (if email notifications needed)
# NOTE: Notification channels should be configured in GCP Console or via gcloud

gcloud alpha monitoring notification-channels list --project=${GCP_PROJECT_ID}

# Test alert by simulating high error rate (optional)
# This would be done through synthetic load testing
```

---

## Rollback Procedures

### Scenario 1: Immediate Rollback (Pod Crash)

```bash
# The deployment automatically handles pod crashes:

# Check pod status
kubectl get pods -n sf-canary

# Verify liveness probe restarted unhealthy pods
kubectl describe pod <pod-name> -n sf-canary | grep -A 5 "Restart Count"

# View recent restart events
kubectl get events -n sf-canary --sort-by='.lastTimestamp' | tail -20
```

### Scenario 2: Deployment Rollback

```bash
# Check rollout history
kubectl rollout history deployment/dark-store-service -n sf-canary

# If new version has issues, rollback to previous version
kubectl rollout undo deployment/dark-store-service -n sf-canary

# Verify rollback
kubectl rollout status deployment/dark-store-service -n sf-canary

# Check current image version
kubectl get deployment dark-store-service -n sf-canary -o jsonpath='{.spec.template.spec.containers[0].image}'
```

### Scenario 3: Complete Infrastructure Rollback

```bash
# ONLY if the entire deployment needs to be torn down

# Delete Kubernetes manifests in reverse order
kubectl delete -f 03-traffic-routing-sf-100pct.yaml
kubectl delete -f 04-networking-policies-sf.yaml
kubectl delete -f 02-dark-store-deployment-sf.yaml
kubectl delete -f 05-storage-pvc-sf.yaml
kubectl delete -f 01-namespace-sf-canary.yaml

# Delete Terraform-managed infrastructure
cd /Users/omkarkumar/InstaCommerce/infra/terraform/phases/phase-2
terraform destroy -var-file=terraform.tfvars

# Verify cleanup
gcloud container clusters describe ${GKE_CLUSTER_NAME} --zone ${GKE_ZONE}
# Expected: Cluster deleted (404 error if queried again)

gcloud compute forwarding-rules list --region=${GKE_REGION} | grep dark-store
# Expected: No results (resources cleaned up)
```

---

## Success Metrics & Validation

### Metric 1: Pod Availability

```bash
# Command: Verify all 5 pods are Running and Ready
kubectl get pods -n sf-canary --no-headers | \
  awk '{print $3, $2}' | sort | uniq -c

# Expected Output:
# 5 1/1 Running

# Command: Verify no pods are in CrashLoopBackOff
kubectl get pods -n sf-canary -o jsonpath='{.items[*].status.containerStatuses[*].state.waiting.reason}' | \
  grep -c "CrashLoopBackOff"

# Expected Output: 0
```

### Metric 2: Service Health

```bash
# Command: Check service endpoints
kubectl get endpoints dark-store-service -n sf-canary -o jsonpath='{.subsets[0].addresses | length}'

# Expected Output: 5

# Command: Test health check endpoint
for pod in $(kubectl get pods -n sf-canary -o name); do
  echo "Testing $pod..."
  kubectl exec $pod -n sf-canary -- curl -s localhost:8080/actuator/health | jq '.status'
done

# Expected Output:
# Testing pod/dark-store-service-xxx...
# "UP"
# ... (repeated 5 times)
```

### Metric 3: Request Latency

```bash
# Command: Check p99 latency from metrics
kubectl port-forward -n prometheus svc/prometheus 9090:9090 &
sleep 2

curl -s 'http://localhost:9090/api/v1/query?query=histogram_quantile(0.99,http_request_duration_seconds{namespace="sf-canary"})' | \
  jq '.data.result[0].value'

# Expected Output: Value < 2000 (milliseconds)
# Example: ["1234567890", "1543"]
```

### Metric 4: Error Rate

```bash
# Command: Check error rate from metrics
curl -s 'http://localhost:9090/api/v1/query?query=rate(http_requests_total{namespace="sf-canary",status=~"5.."}[5m])' | \
  jq '.data.result[0].value'

# Expected Output: < 0.001 (< 0.1%)
# Example: ["1234567890", "0.0005"]

kill %1
```

### Metric 5: Resource Utilization

```bash
# Command: Check CPU utilization
kubectl top pods -n sf-canary

# Expected Output:
# NAME                                CPU(cores)   MEMORY(Mi)
# dark-store-service-xxx-xxxxx        450m         850Mi
# dark-store-service-xxx-xxxxx        480m         920Mi
# ... (all should be < 1000m CPU, < 2000Mi memory)

# Command: Check memory utilization
kubectl get pods -n sf-canary -o jsonpath='{range .items[*]}{.metadata.name}{"\t"}{.spec.containers[0].resources.requests.memory}{"\n"}{end}'

# Expected Output:
# dark-store-service-xxx-xxxxx    2Gi
# ... (requested vs actual usage should show healthy allocation)
```

---

## Troubleshooting Guide

### Issue 1: Pods Not Starting

**Symptoms**: Pods remain in `Pending` or `ImagePullBackOff` state

**Diagnosis**:
```bash
# Check pod events
kubectl describe pod <pod-name> -n sf-canary

# Check image availability
gcloud container images describe gcr.io/instacommerce-prod/dark-store-service:v1.0.0-wave40-phase2

# Verify image pull secrets
kubectl get secrets -n sf-canary | grep docker
```

**Solutions**:
```bash
# Ensure image exists
docker pull gcr.io/instacommerce-prod/dark-store-service:v1.0.0-wave40-phase2

# Verify authentication
gcloud auth configure-docker

# Re-create deployment
kubectl delete deployment dark-store-service -n sf-canary
kubectl apply -f 02-dark-store-deployment-sf.yaml
```

### Issue 2: Services Not Accessible

**Symptoms**: Connection refused or timeout when accessing service

**Diagnosis**:
```bash
# Check service exists
kubectl get svc dark-store-service -n sf-canary

# Check endpoints
kubectl get endpoints dark-store-service -n sf-canary

# Test connectivity from debug pod
kubectl run -it --rm debug --image=alpine --restart=Never -- sh
apk add curl
curl http://dark-store-service.sf-canary:8080/actuator/health
exit
```

**Solutions**:
```bash
# Verify firewall rules allow traffic
gcloud compute firewall-rules describe dark-store-ilb-allow \
  --project=${GCP_PROJECT_ID}

# Check network policies aren't blocking traffic
kubectl get networkpolicy -n sf-canary

# Re-apply network policies if needed
kubectl delete -f 04-networking-policies-sf.yaml
kubectl apply -f 04-networking-policies-sf.yaml
```

### Issue 3: High Memory Usage

**Symptoms**: Pods OOMKilled or memory usage > 80% of limit

**Solutions**:
```bash
# Check JVM settings
kubectl logs <pod-name> -n sf-canary | grep "JAVA_OPTS"

# Increase memory limit
kubectl set resources deployment dark-store-service \
  -n sf-canary --limits=memory=8Gi

# Monitor memory after restart
kubectl top pods -n sf-canary --watch
```

### Issue 4: Traffic Not Routing

**Symptoms**: Requests fail with "no healthy upstream hosts" error

**Diagnosis**:
```bash
# Verify Istio sidecar injection
kubectl get pods -n sf-canary -o jsonpath='{.items[0].spec.containers | length}'
# Expected: 2 (app container + istio-proxy)

# Check VirtualService configuration
kubectl get vs dark-store-vs -n sf-canary -o yaml

# Verify DestinationRule
kubectl get dr dark-store-dr -n sf-canary -o yaml
```

**Solutions**:
```bash
# Re-enable sidecar injection in namespace
kubectl label namespace sf-canary istio-injection=enabled --overwrite

# Restart deployment to re-inject sidecars
kubectl rollout restart deployment/dark-store-service -n sf-canary

# Verify routing rules
kubectl describe vs dark-store-vs -n sf-canary
```

---

## Post-Deployment Verification

### Checklist: Deployment Success

- [ ] All 5 dark-store pods are Running and Ready
- [ ] Service endpoints show 5 registered pods
- [ ] Istio VirtualService is routing 100% traffic to SF canary
- [ ] All health checks passing (liveness, readiness, startup)
- [ ] PVCs provisioned and bound for orders, fulfillment, cache, logs
- [ ] Network policies in place (ingress/egress properly configured)
- [ ] Prometheus scraping metrics from dark-store pods
- [ ] Monitoring dashboard showing request rate, latency, error rate
- [ ] Alert policies created and notification channels configured
- [ ] Canary region set to us-west1 (SF)
- [ ] Internal Load Balancer IP address: 10.0.1.100
- [ ] Traffic load balanced across all 5 pods
- [ ] Latency p99 < 2000ms
- [ ] Error rate < 0.1%
- [ ] CPU utilization < 70% per pod
- [ ] Memory utilization < 80% per pod
- [ ] No pod restarts in last 1 hour

### Next Steps

1. **Monitor for 24 hours**: Watch metrics dashboard for stability
2. **Load testing**: Run synthetic load tests to verify capacity
3. **Failover testing**: Test pod failures and auto-recovery
4. **Scale testing**: Verify HPA scales up/down appropriately
5. **Canary gates approval**: Once metrics confirm 99.5% SLO, proceed to gates
6. **Schedule Seattle/Austin deployments**: Next phases in 1 week
7. **Update runbooks**: Document any deviations from this runbook
8. **Team training**: Ensure ops team familiar with rollback procedures
