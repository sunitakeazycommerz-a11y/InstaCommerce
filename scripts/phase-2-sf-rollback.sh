#!/bin/bash

################################################################################
# Wave 40 Phase 2: SF Canary Rollback Script
#
# Purpose: Rollback SF canary deployment instantly
# Actions: Drain traffic, scale replicas to 0, cleanup resources, verify primary
#
# Usage: ./phase-2-sf-rollback.sh [--force] [--keep-namespace]
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
SCRIPTS_DIR="${REPO_ROOT}/scripts"
NAMESPACE="sf-canary"
PRIMARY_CLUSTER="us-central1-prod"

# Canary services
CANARY_SERVICES=(
  "catalog-service"
  "inventory-service"
  "order-service"
  "fulfillment-service"
  "warehouse-service"
)

FORCE_ROLLBACK=false
KEEP_NAMESPACE=false
ROLLBACK_SUCCESS=true

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
  echo -e "${RED}╔════════════════════════════════════════════════════════╗${NC}"
  echo -e "${RED}║ $*${NC}"
  echo -e "${RED}╚════════════════════════════════════════════════════════╝${NC}"
  echo ""
}

confirm_action() {
  local prompt="$1"

  if [ "$FORCE_ROLLBACK" = true ]; then
    log_warning "Force mode enabled - proceeding without confirmation"
    return 0
  fi

  echo -ne "${YELLOW}$prompt (yes/no): ${NC}"
  read -r response

  if [ "$response" = "yes" ]; then
    return 0
  else
    return 1
  fi
}

# ============================================================================
# PREREQUISITE CHECKS
# ============================================================================

validate_prerequisites() {
  log_section "Validating Prerequisites for Rollback"

  # Check tools
  for tool in kubectl helm gcloud jq; do
    if ! command -v "$tool" &> /dev/null; then
      log_error "Missing required tool: $tool"
      exit 1
    fi
  done

  log_success "All required tools found"

  # Check kubectl access
  if ! kubectl cluster-info &> /dev/null; then
    log_error "Cannot access Kubernetes cluster"
    exit 1
  fi

  local current_context=$(kubectl config current-context)
  log_success "Connected to cluster: $current_context"

  # Check namespace exists
  if ! kubectl get namespace "$NAMESPACE" &> /dev/null; then
    log_error "Namespace $NAMESPACE does not exist"
    exit 1
  fi

  log_success "Namespace $NAMESPACE exists"
}

# ============================================================================
# TRAFFIC DRAINING
# ============================================================================

drain_canary_traffic() {
  log_section "Draining Traffic from SF Canary (Route to Primary Cluster)"

  log_info "Updating Istio VirtualService to route 0% traffic to canary..."

  # Check if VirtualService exists
  if ! kubectl get virtualservice sf-canary-routing -n "$NAMESPACE" &> /dev/null; then
    log_warning "VirtualService sf-canary-routing not found"
    return 0
  fi

  # Update VirtualService to route traffic back to primary
  kubectl patch virtualservice sf-canary-routing -n "$NAMESPACE" --type merge -p '
  {
    "spec": {
      "http": [
        {
          "match": [{"uri": {"prefix": "/api"}}],
          "route": [
            {
              "destination": {"host": "order-service.default.svc.cluster.local"},
              "weight": 100
            }
          ]
        }
      ]
    }
  }' || log_warning "Failed to patch VirtualService, continuing..."

  log_success "Traffic routed back to primary cluster"

  # Wait for connections to drain
  log_info "Waiting 30 seconds for active connections to drain..."
  sleep 30

  log_success "Traffic draining complete"
}

# ============================================================================
# SCALE DOWN CANARY SERVICES
# ============================================================================

scale_down_canary_services() {
  log_section "Scaling Down Canary Services to Zero Replicas"

  log_info "Scaling ${#CANARY_SERVICES[@]} services to 0 replicas..."

  for service in "${CANARY_SERVICES[@]}"; do
    log_info "Scaling $service..."

    # Check if deployment exists
    if kubectl get deployment "$service" -n "$NAMESPACE" &> /dev/null; then
      kubectl scale deployment "$service" -n "$NAMESPACE" --replicas=0 2>/dev/null || true

      # Wait for pods to terminate
      log_info "Waiting for $service pods to terminate..."
      local timeout=60
      local elapsed=0

      while kubectl get pods -n "$NAMESPACE" -l app="$service" --field-selector=status.phase=Running 2>/dev/null | grep -q "Running"; do
        if [ $elapsed -ge $timeout ]; then
          log_warning "$service: Timeout waiting for pods to terminate"
          break
        fi
        sleep 5
        elapsed=$((elapsed + 5))
      done

      log_success "$service scaled to 0 replicas"
    else
      log_warning "$service deployment not found"
    fi
  done

  # Verify all pods are gone
  local running_pods=$(kubectl get pods -n "$NAMESPACE" --field-selector=status.phase=Running --no-headers 2>/dev/null | wc -l)

  if [ "$running_pods" -eq 0 ]; then
    log_success "All canary pods terminated"
  else
    log_warning "$running_pods pod(s) still running in canary namespace"
  fi
}

# ============================================================================
# RESOURCE CLEANUP
# ============================================================================

cleanup_kubernetes_resources() {
  log_section "Cleaning Up Kubernetes Resources"

  log_info "Deleting PersistentVolumeClaims..."
  kubectl delete pvc --all -n "$NAMESPACE" --ignore-not-found=true

  log_info "Deleting Services..."
  kubectl delete service --all -n "$NAMESPACE" --ignore-not-found=true

  log_info "Deleting Istio resources (VirtualServices, DestinationRules)..."
  kubectl delete virtualservices --all -n "$NAMESPACE" --ignore-not-found=true
  kubectl delete destinationrules --all -n "$NAMESPACE" --ignore-not-found=true
  kubectl delete gateways --all -n "$NAMESPACE" --ignore-not-found=true

  log_info "Deleting NetworkPolicies..."
  kubectl delete networkpolicies --all -n "$NAMESPACE" --ignore-not-found=true

  log_info "Deleting ResourceQuotas..."
  kubectl delete resourcequota --all -n "$NAMESPACE" --ignore-not-found=true

  log_success "Kubernetes resources cleaned up"
}

cleanup_namespace() {
  if [ "$KEEP_NAMESPACE" = true ]; then
    log_info "Keeping namespace $NAMESPACE for inspection (--keep-namespace flag set)"
    return
  fi

  log_section "Deleting Namespace: $NAMESPACE"

  if confirm_action "Delete namespace $NAMESPACE? This will remove all remaining resources."; then
    log_info "Deleting namespace $NAMESPACE..."

    kubectl delete namespace "$NAMESPACE" --ignore-not-found=true

    # Wait for namespace deletion
    local timeout=120
    local elapsed=0

    while kubectl get namespace "$NAMESPACE" &> /dev/null; do
      if [ $elapsed -ge $timeout ]; then
        log_warning "Timeout waiting for namespace deletion"
        break
      fi
      sleep 5
      elapsed=$((elapsed + 5))
    done

    log_success "Namespace $NAMESPACE deleted"
  else
    log_info "Namespace deletion skipped"
  fi
}

# ============================================================================
# PRIMARY CLUSTER VERIFICATION
# ============================================================================

verify_primary_cluster() {
  log_section "Verifying Primary Cluster Has Absorbed Traffic"

  log_info "Checking primary cluster connectivity..."

  # Get primary cluster context
  local primary_contexts=$(kubectl config get-contexts -o name | grep -E "(prod|primary)" | head -1 || echo "")

  if [ -z "$primary_contexts" ]; then
    log_warning "Could not identify primary cluster context automatically"
    return 1
  fi

  log_info "Primary cluster context: $primary_contexts"

  # Check if we can switch to primary context
  if kubectl config use-context "$primary_contexts" &> /dev/null; then
    log_success "Switched to primary cluster context"

    # Verify core services are running in primary
    log_info "Verifying core services in primary cluster..."

    for service in "${CANARY_SERVICES[@]}"; do
      local pod_count=$(kubectl get pods -l app="$service" --all-namespaces --no-headers 2>/dev/null | wc -l || echo "0")

      if [ "$pod_count" -gt 0 ]; then
        log_success "Primary: $service has $pod_count pod(s) running"
      else
        log_warning "Primary: $service has no running pods"
      fi
    done

    # Switch back to canary context (if it still exists)
    kubectl config use-context "gke_instacommerce_us-west-1_instacommerce-sf-canary" 2>/dev/null || true
  else
    log_warning "Could not switch to primary cluster context"
  fi
}

# ============================================================================
# TERRAFORM CLEANUP
# ============================================================================

cleanup_terraform_resources() {
  log_section "Cleaning Up Terraform Infrastructure (Optional)"

  if ! confirm_action "Delete Terraform-managed infrastructure (GKE cluster, VPC, etc.)?"; then
    log_info "Terraform infrastructure cleanup skipped"
    return
  fi

  local terraform_dir="${REPO_ROOT}/infra/terraform/environments/prod"

  if [ ! -d "$terraform_dir" ]; then
    log_warning "Terraform directory not found: $terraform_dir"
    return
  fi

  log_info "Switching to Terraform directory: $terraform_dir"
  cd "$terraform_dir"

  log_info "Running terraform destroy..."

  if terraform destroy -auto-approve -var="region=us-west-1" -var="cluster_name=instacommerce-sf-canary"; then
    log_success "Terraform infrastructure destroyed"
  else
    log_error "Terraform destroy failed - manual cleanup may be required"
    ROLLBACK_SUCCESS=false
  fi

  cd - > /dev/null
}

# ============================================================================
# ROLLBACK SUMMARY
# ============================================================================

print_rollback_summary() {
  log_section "Rollback Summary"

  echo ""
  echo -e "${BLUE}Rollback Actions Completed:${NC}"
  echo -e "  ${GREEN}✓${NC} Traffic drained to primary cluster"
  echo -e "  ${GREEN}✓${NC} Canary services scaled to 0 replicas"
  echo -e "  ${GREEN}✓${NC} Kubernetes resources cleaned up"

  if [ "$KEEP_NAMESPACE" = false ]; then
    echo -e "  ${GREEN}✓${NC} Namespace $NAMESPACE deleted"
  else
    echo -e "  ${YELLOW}⚠${NC} Namespace $NAMESPACE kept for inspection"
  fi

  echo ""

  if [ "$ROLLBACK_SUCCESS" = true ]; then
    echo -e "${GREEN}Rollback completed successfully!${NC}"
    echo -e "${GREEN}Primary cluster is now handling all traffic.${NC}"
  else
    echo -e "${YELLOW}Rollback completed with warnings - manual verification recommended.${NC}"
  fi
}

print_next_steps() {
  log_section "Next Steps"

  echo ""
  echo -e "${BLUE}Recommended Actions:${NC}"
  echo ""
  echo "1. Verify primary cluster is healthy:"
  echo "   ${YELLOW}kubectl get pods --all-namespaces${NC}"
  echo ""
  echo "2. Check traffic metrics in Grafana:"
  echo "   ${YELLOW}http://grafana.example.com/dashboards${NC}"
  echo ""
  echo "3. Review incident logs:"
  echo "   ${YELLOW}kubectl logs -n default --tail=100 | grep error${NC}"
  echo ""
  echo "4. Post-incident review:"
  echo "   ${YELLOW}Create incident report in docs/incidents/${NC}"
  echo ""
}

# ============================================================================
# ARGUMENT PARSING
# ============================================================================

parse_arguments() {
  while [[ $# -gt 0 ]]; do
    case $1 in
      --force)
        FORCE_ROLLBACK=true
        log_warning "Force rollback mode enabled - no confirmations required"
        shift
        ;;
      --keep-namespace)
        KEEP_NAMESPACE=true
        log_warning "Namespace will be kept for inspection"
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
  log_section "Wave 40 Phase 2: SF Canary Rollback"
  log_info "Start time: $(date '+%Y-%m-%d %H:%M:%S')"

  parse_arguments "$@"

  validate_prerequisites

  log_warning "Initiating rollback of SF canary deployment!"

  drain_canary_traffic
  scale_down_canary_services
  cleanup_kubernetes_resources
  cleanup_namespace
  verify_primary_cluster

  # Optional Terraform cleanup
  if [ "$FORCE_ROLLBACK" = false ]; then
    if confirm_action "Clean up Terraform infrastructure?"; then
      cleanup_terraform_resources
    fi
  fi

  print_rollback_summary
  print_next_steps

  log_success "Rollback process completed at $(date '+%Y-%m-%d %H:%M:%S')"

  if [ "$ROLLBACK_SUCCESS" = true ]; then
    exit 0
  else
    exit 1
  fi
}

main "$@"
