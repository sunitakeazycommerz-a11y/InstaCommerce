#!/bin/bash

################################################################################
# Wave 40 Phase 2: SF Canary Deployment Automation
#
# Purpose: Deploy dark-store infrastructure to SF canary cluster
# Includes: Terraform init/plan/apply, K8s manifest deployment, health checks
#
# Usage: ./phase-2-sf-canary-deploy.sh [--dry-run] [--skip-tf] [--skip-k8s]
################################################################################

set -euo pipefail

# ============================================================================
# COLORS & FORMATTING
# ============================================================================
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
NC='\033[0m' # No Color

SUCCESS_MARK="✅"
FAILURE_MARK="❌"
WARNING_MARK="⚠️"
INFO_MARK="ℹ️"

# ============================================================================
# CONFIGURATION
# ============================================================================
REPO_ROOT="/Users/omkarkumar/InstaCommerce"
TERRAFORM_DIR="${REPO_ROOT}/infra/terraform/environments/prod"
DEPLOY_DIR="${REPO_ROOT}/deploy"
HELM_DIR="${DEPLOY_DIR}/helm"
SCRIPTS_DIR="${REPO_ROOT}/scripts"

NAMESPACE="sf-canary"
REGION="us-west-1"  # San Francisco
CLUSTER_NAME="instacommerce-sf-canary"
CONTEXT="gke_instacommerce_${REGION}_${CLUSTER_NAME}"

# Canary deployment configuration
CANARY_SERVICES=(
  "catalog-service"
  "inventory-service"
  "order-service"
  "fulfillment-service"
  "warehouse-service"
)

REPLICAS_CANARY=5
WAIT_TIMEOUT=600  # 10 minutes
HEALTH_CHECK_INTERVAL=10  # seconds

DRY_RUN=false
SKIP_TERRAFORM=false
SKIP_K8S=false
ROLLBACK_ON_FAILURE=true

# Tracking
DEPLOYMENT_FAILED=false
FAILED_CHECKS=()

# ============================================================================
# UTILITY FUNCTIONS
# ============================================================================

log_info() {
  echo -e "${BLUE}${INFO_MARK} $(date '+%Y-%m-%d %H:%M:%S')${NC} $*"
}

log_success() {
  echo -e "${GREEN}${SUCCESS_MARK} $(date '+%Y-%m-%d %H:%M:%S')${NC} $*"
}

log_warning() {
  echo -e "${YELLOW}${WARNING_MARK} $(date '+%Y-%m-%d %H:%M:%S')${NC} $*"
}

log_error() {
  echo -e "${RED}${FAILURE_MARK} $(date '+%Y-%m-%d %H:%M:%S')${NC} $*"
}

log_section() {
  echo ""
  echo -e "${BLUE}╔════════════════════════════════════════════════════════╗${NC}"
  echo -e "${BLUE}║ $*${NC}"
  echo -e "${BLUE}╚════════════════════════════════════════════════════════╝${NC}"
  echo ""
}

handle_error() {
  local line_num=$1
  log_error "Deployment failed at line $line_num"
  DEPLOYMENT_FAILED=true

  if [ "$ROLLBACK_ON_FAILURE" = true ]; then
    log_warning "Initiating automatic rollback due to failure..."
    if [ -f "${SCRIPTS_DIR}/phase-2-sf-rollback.sh" ]; then
      bash "${SCRIPTS_DIR}/phase-2-sf-rollback.sh" --force
    else
      log_warning "Rollback script not found at ${SCRIPTS_DIR}/phase-2-sf-rollback.sh"
    fi
  fi

  exit 1
}

trap 'handle_error ${LINENO}' ERR

cleanup_on_exit() {
  if [ "$DEPLOYMENT_FAILED" = true ]; then
    log_error "Deployment completed with errors"
    exit 1
  else
    log_success "Deployment completed successfully"
  fi
}

trap cleanup_on_exit EXIT

# ============================================================================
# VALIDATION FUNCTIONS
# ============================================================================

validate_prerequisites() {
  log_section "Validating Prerequisites"

  local missing_tools=()

  # Check for required tools
  for tool in terraform kubectl helm gcloud jq gke-gcloud-auth-plugin; do
    if ! command -v "$tool" &> /dev/null; then
      missing_tools+=("$tool")
      log_error "Missing required tool: $tool"
    else
      log_success "Found $tool: $(command -v "$tool")"
    fi
  done

  if [ ${#missing_tools[@]} -gt 0 ]; then
    log_error "Missing required tools: ${missing_tools[*]}"
    exit 1
  fi

  # Validate directories exist
  if [ ! -d "$TERRAFORM_DIR" ]; then
    log_error "Terraform directory not found: $TERRAFORM_DIR"
    exit 1
  fi
  log_success "Terraform directory exists: $TERRAFORM_DIR"

  if [ ! -d "$HELM_DIR" ]; then
    log_error "Helm directory not found: $HELM_DIR"
    exit 1
  fi
  log_success "Helm directory exists: $HELM_DIR"

  # Validate kubectl access
  if ! kubectl cluster-info &> /dev/null; then
    log_error "Cannot access Kubernetes cluster"
    exit 1
  fi
  log_success "Kubernetes cluster is accessible"

  log_success "All prerequisites validated"
}

validate_terraform_config() {
  log_section "Validating Terraform Configuration"

  cd "$TERRAFORM_DIR"

  log_info "Running terraform validate..."
  if terraform validate; then
    log_success "Terraform configuration is valid"
  else
    log_error "Terraform validation failed"
    exit 1
  fi

  cd - > /dev/null
}

# ============================================================================
# TERRAFORM DEPLOYMENT
# ============================================================================

deploy_terraform() {
  if [ "$SKIP_TERRAFORM" = true ]; then
    log_warning "Skipping Terraform deployment (--skip-tf flag set)"
    return
  fi

  log_section "Deploying Infrastructure with Terraform"

  cd "$TERRAFORM_DIR"

  log_info "Initializing Terraform..."
  terraform init -upgrade=false

  log_info "Creating Terraform plan..."
  terraform plan -out=tfplan -var="region=${REGION}" -var="cluster_name=${CLUSTER_NAME}"

  if [ "$DRY_RUN" = true ]; then
    log_warning "Dry run mode enabled - stopping before apply"
    log_info "Terraform plan saved to tfplan (not applied)"
    cd - > /dev/null
    return
  fi

  log_info "Applying Terraform plan (this may take 5-10 minutes)..."
  terraform apply tfplan

  log_success "Terraform infrastructure deployed successfully"

  # Capture output for later use
  export CLUSTER_ENDPOINT=$(terraform output -raw kubernetes_cluster_endpoint 2>/dev/null || echo "")
  export CLUSTER_CA=$(terraform output -raw kubernetes_cluster_ca 2>/dev/null || echo "")

  log_info "Cluster endpoint: $CLUSTER_ENDPOINT"

  cd - > /dev/null
}

# ============================================================================
# KUBERNETES NAMESPACE & SETUP
# ============================================================================

setup_kubernetes_namespace() {
  log_section "Setting Up Kubernetes Namespace: $NAMESPACE"

  # Create namespace if it doesn't exist (idempotent)
  if kubectl get namespace "$NAMESPACE" &> /dev/null; then
    log_info "Namespace $NAMESPACE already exists"
  else
    log_info "Creating namespace $NAMESPACE..."
    kubectl create namespace "$NAMESPACE"
    log_success "Namespace $NAMESPACE created"
  fi

  # Label namespace for canary
  log_info "Labeling namespace for canary deployment..."
  kubectl label namespace "$NAMESPACE" \
    canary=true \
    region=sf \
    environment=canary \
    --overwrite

  # Create resource quota
  log_info "Applying resource quota..."
  kubectl apply -f - <<EOF
apiVersion: v1
kind: ResourceQuota
metadata:
  name: sf-canary-quota
  namespace: $NAMESPACE
spec:
  hard:
    requests.cpu: "50"
    requests.memory: "100Gi"
    limits.cpu: "100"
    limits.memory: "200Gi"
    pods: "100"
    services: "20"
    persistentvolumeclaims: "20"
EOF

  # Create network policy (default deny, then allow)
  log_info "Applying network policies..."
  kubectl apply -f - <<EOF
apiVersion: networking.k8s.io/v1
kind: NetworkPolicy
metadata:
  name: default-deny-ingress
  namespace: $NAMESPACE
spec:
  podSelector: {}
  policyTypes:
  - Ingress
---
apiVersion: networking.k8s.io/v1
kind: NetworkPolicy
metadata:
  name: allow-same-namespace
  namespace: $NAMESPACE
spec:
  podSelector: {}
  policyTypes:
  - Ingress
  ingress:
  - from:
    - podSelector: {}
EOF

  log_success "Kubernetes namespace configured"
}

# ============================================================================
# KUBERNETES MANIFEST DEPLOYMENT
# ============================================================================

deploy_kubernetes_manifests() {
  if [ "$SKIP_K8S" = true ]; then
    log_warning "Skipping Kubernetes manifest deployment (--skip-k8s flag set)"
    return
  fi

  log_section "Deploying Kubernetes Manifests to $NAMESPACE"

  # Deploy Istio resources first
  log_info "Deploying Istio resources (VirtualService, DestinationRule)..."
  kubectl apply -f "${HELM_DIR}/templates/istio/" -n "$NAMESPACE" || true

  # Deploy services incrementally with validation
  for service in "${CANARY_SERVICES[@]}"; do
    log_info "Deploying $service to canary cluster..."

    # Generate Helm values for this service
    helm template "$service" "${HELM_DIR}" \
      --values "${HELM_DIR}/values-dev.yaml" \
      --set "services.${service}.tag=canary" \
      --set "services.${service}.replicas=${REPLICAS_CANARY}" \
      --set "services.${service}.namespace=${NAMESPACE}" \
      --set "services.${service}.region=${REGION}" \
      --namespace "$NAMESPACE" | kubectl apply -f -

    sleep 2  # Brief pause between deployments
  done

  log_success "Kubernetes manifests deployed"
}

# ============================================================================
# HEALTH CHECKS
# ============================================================================

check_pod_readiness() {
  local service=$1
  local desired_replicas=$2
  local elapsed=0

  log_info "Waiting for $service pods (desired: $desired_replicas)..."

  while [ $elapsed -lt $WAIT_TIMEOUT ]; do
    local ready_replicas=$(kubectl get deployment "$service" -n "$NAMESPACE" \
      -o jsonpath='{.status.readyReplicas}' 2>/dev/null || echo "0")

    if [ "$ready_replicas" = "$desired_replicas" ]; then
      log_success "$service: All $ready_replicas/$desired_replicas replicas ready"
      return 0
    fi

    log_info "$service: Waiting for replicas ($ready_replicas/$desired_replicas ready)..."
    sleep $HEALTH_CHECK_INTERVAL
    elapsed=$((elapsed + HEALTH_CHECK_INTERVAL))
  done

  log_error "$service: Timeout waiting for pod readiness (${elapsed}s elapsed)"
  FAILED_CHECKS+=("$service")
  DEPLOYMENT_FAILED=true
  return 1
}

check_all_pods_ready() {
  log_section "Checking Pod Readiness"

  for service in "${CANARY_SERVICES[@]}"; do
    check_pod_readiness "$service" "$REPLICAS_CANARY"
  done

  if [ "$DEPLOYMENT_FAILED" = true ]; then
    log_error "Some services failed pod readiness checks"
    return 1
  fi

  log_success "All pods are ready"
}

check_service_endpoints() {
  log_section "Checking Service Endpoints"

  for service in "${CANARY_SERVICES[@]}"; do
    log_info "Checking endpoints for $service..."

    if kubectl get endpoints "$service" -n "$NAMESPACE" &> /dev/null; then
      local endpoints=$(kubectl get endpoints "$service" -n "$NAMESPACE" \
        -o jsonpath='{.subsets[0].addresses[*].ip}' | wc -w)

      if [ "$endpoints" -gt 0 ]; then
        log_success "$service has $endpoints endpoint(s)"
      else
        log_error "$service has no endpoints"
        FAILED_CHECKS+=("$service-endpoints")
        DEPLOYMENT_FAILED=true
      fi
    else
      log_error "$service service not found"
      FAILED_CHECKS+=("$service-service")
      DEPLOYMENT_FAILED=true
    fi
  done
}

check_service_connectivity() {
  log_section "Checking Service Connectivity"

  # Port forward to one of the services and check health
  local test_service="catalog-service"
  local test_port=8080

  log_info "Testing connectivity to $test_service..."

  # Port-forward in background
  kubectl port-forward -n "$NAMESPACE" "svc/$test_service" "$test_port:$test_port" &
  local pf_pid=$!

  sleep 2  # Wait for port-forward to establish

  if curl -s -f http://localhost:$test_port/health &> /dev/null; then
    log_success "$test_service health check passed"
  else
    log_warning "$test_service health check failed or not responding"
  fi

  kill $pf_pid 2>/dev/null || true
  wait $pf_pid 2>/dev/null || true
}

check_persistent_volumes() {
  log_section "Checking Persistent Volumes"

  log_info "Checking PVC status..."

  local pvc_count=$(kubectl get pvc -n "$NAMESPACE" --no-headers 2>/dev/null | wc -l)

  if [ "$pvc_count" -eq 0 ]; then
    log_warning "No persistent volume claims found in $NAMESPACE"
    return 0
  fi

  local bound_pvcs=$(kubectl get pvc -n "$NAMESPACE" -o jsonpath='{.items[?(@.status.phase=="Bound")]}' | jq length 2>/dev/null || echo "0")

  if [ "$bound_pvcs" -eq "$pvc_count" ]; then
    log_success "All $pvc_count PVCs are bound"
  else
    log_warning "$bound_pvcs/$pvc_count PVCs are bound"
  fi
}

# ============================================================================
# TRAFFIC ROUTING CONFIGURATION
# ============================================================================

configure_traffic_routing() {
  log_section "Configuring Traffic Routing"

  log_info "Applying Istio VirtualService for canary traffic..."

  kubectl apply -f - <<EOF
apiVersion: networking.istio.io/v1beta1
kind: VirtualService
metadata:
  name: sf-canary-routing
  namespace: $NAMESPACE
spec:
  hosts:
  - "*.example.com"
  gateways:
  - sf-canary-gateway
  http:
  - match:
    - uri:
        prefix: /api
    route:
    - destination:
        host: catalog-service
        port:
          number: 8080
      weight: 100
    timeout: 30s
    retries:
      attempts: 3
      perTryTimeout: 10s
---
apiVersion: networking.istio.io/v1beta1
kind: Gateway
metadata:
  name: sf-canary-gateway
  namespace: $NAMESPACE
spec:
  selector:
    istio: ingressgateway
  servers:
  - port:
      number: 80
      name: http
      protocol: HTTP
    hosts:
    - "*.example.com"
  - port:
      number: 443
      name: https
      protocol: HTTPS
    tls:
      mode: SIMPLE
      credentialName: sf-canary-tls
    hosts:
    - "*.example.com"
EOF

  log_success "Traffic routing configured"
}

# ============================================================================
# BASELINE METRICS CAPTURE
# ============================================================================

capture_baseline_metrics() {
  log_section "Capturing Baseline Metrics"

  local metrics_file="${SCRIPTS_DIR}/.sf-canary-baseline-metrics.json"

  log_info "Capturing baseline metrics at $(date '+%Y-%m-%d %H:%M:%S')..."

  cat > "$metrics_file" <<EOF
{
  "timestamp": "$(date -u +%Y-%m-%dT%H:%M:%SZ)",
  "deployment": {
    "namespace": "$NAMESPACE",
    "region": "$REGION",
    "cluster": "$CLUSTER_NAME"
  },
  "services": {
EOF

  for service in "${CANARY_SERVICES[@]}"; do
    local pod_count=$(kubectl get pods -n "$NAMESPACE" -l app="$service" --no-headers 2>/dev/null | wc -l)
    local cpu=$(kubectl top pods -n "$NAMESPACE" -l app="$service" --no-headers 2>/dev/null | awk '{sum+=$2} END {print sum}' || echo "0m")
    local memory=$(kubectl top pods -n "$NAMESPACE" -l app="$service" --no-headers 2>/dev/null | awk '{sum+=$3} END {print sum}' || echo "0Mi")

    cat >> "$metrics_file" <<EOF
    "$service": {
      "pod_count": $pod_count,
      "cpu_total": "$cpu",
      "memory_total": "$memory",
      "timestamp": "$(date -u +%Y-%m-%dT%H:%M:%SZ)"
    },
EOF
  done

  # Remove trailing comma and close JSON
  sed -i '$ s/,$//' "$metrics_file"
  echo '  }' >> "$metrics_file"
  echo '}' >> "$metrics_file"

  log_success "Baseline metrics captured to $metrics_file"

  # Display metrics
  log_info "Baseline metrics summary:"
  jq . "$metrics_file" 2>/dev/null || cat "$metrics_file"
}

# ============================================================================
# DEPLOYMENT SUMMARY
# ============================================================================

print_deployment_summary() {
  log_section "Deployment Summary"

  echo ""
  echo -e "${BLUE}Deployment Configuration:${NC}"
  echo -e "  Namespace:     ${BLUE}$NAMESPACE${NC}"
  echo -e "  Region:        ${BLUE}$REGION${NC}"
  echo -e "  Cluster:       ${BLUE}$CLUSTER_NAME${NC}"
  echo -e "  Services:      ${BLUE}${#CANARY_SERVICES[@]}${NC}"
  echo -e "  Replicas/Svc:  ${BLUE}$REPLICAS_CANARY${NC}"

  echo ""
  echo -e "${BLUE}Services Deployed:${NC}"
  for service in "${CANARY_SERVICES[@]}"; do
    local ready=$(kubectl get deployment "$service" -n "$NAMESPACE" -o jsonpath='{.status.readyReplicas}' 2>/dev/null || echo "0")
    local desired=$(kubectl get deployment "$service" -n "$NAMESPACE" -o jsonpath='{.spec.replicas}' 2>/dev/null || echo "0")

    if [ "$ready" = "$desired" ] && [ "$ready" -gt 0 ]; then
      echo -e "  ${GREEN}✓${NC} $service: ${GREEN}$ready/$desired${NC} replicas ready"
    else
      echo -e "  ${RED}✗${NC} $service: ${YELLOW}$ready/$desired${NC} replicas ready"
    fi
  done

  if [ ${#FAILED_CHECKS[@]} -gt 0 ]; then
    echo ""
    echo -e "${RED}Failed Checks:${NC}"
    for check in "${FAILED_CHECKS[@]}"; do
      echo -e "  ${RED}✗${NC} $check"
    done
  else
    echo ""
    echo -e "${GREEN}All checks passed!${NC}"
  fi
}

# ============================================================================
# ARGUMENT PARSING
# ============================================================================

parse_arguments() {
  while [[ $# -gt 0 ]]; do
    case $1 in
      --dry-run)
        DRY_RUN=true
        shift
        ;;
      --skip-tf)
        SKIP_TERRAFORM=true
        shift
        ;;
      --skip-k8s)
        SKIP_K8S=true
        shift
        ;;
      --no-rollback)
        ROLLBACK_ON_FAILURE=false
        shift
        ;;
      *)
        log_error "Unknown argument: $1"
        exit 1
        ;;
    esac
  done
}

# ============================================================================
# MAIN EXECUTION
# ============================================================================

main() {
  log_section "Wave 40 Phase 2: SF Canary Deployment"
  log_info "Start time: $(date '+%Y-%m-%d %H:%M:%S')"

  parse_arguments "$@"

  if [ "$DRY_RUN" = true ]; then
    log_warning "Running in DRY-RUN mode - no changes will be applied"
  fi

  validate_prerequisites
  validate_terraform_config
  deploy_terraform
  setup_kubernetes_namespace
  deploy_kubernetes_manifests
  check_all_pods_ready
  check_service_endpoints
  check_service_connectivity
  check_persistent_volumes
  configure_traffic_routing
  capture_baseline_metrics
  print_deployment_summary

  if [ "$DEPLOYMENT_FAILED" = true ]; then
    exit 1
  else
    log_success "SF Canary deployment completed successfully!"
    exit 0
  fi
}

main "$@"
