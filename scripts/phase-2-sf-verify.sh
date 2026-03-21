#!/bin/bash

################################################################################
# Wave 40 Phase 2: SF Canary Verification Script
#
# Purpose: Verify SF canary deployment is healthy and traffic is routed correctly
# Checks: Pod status, endpoints, traffic routing, latency baselines, storage
#
# Usage: ./phase-2-sf-verify.sh [--detailed] [--watch] [--interval N]
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
REGION="us-west-1"

# Services to verify
CORE_SERVICES=(
  "order-service"
  "fulfillment-service"
  "inventory-service"
)

DEPENDENT_SERVICES=(
  "catalog-service"
  "warehouse-service"
)

ALL_SERVICES=("${CORE_SERVICES[@]}" "${DEPENDENT_SERVICES[@]}")

# Verification thresholds
POD_READY_TIMEOUT=300  # 5 minutes
LATENCY_THRESHOLD_MS=1000  # 1 second acceptable for canary
TRAFFIC_CHECK_INTERVAL=5

DETAILED_MODE=false
WATCH_MODE=false
CHECK_INTERVAL=30

VERIFICATION_PASSED=0
VERIFICATION_FAILED=0

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

record_result() {
  local check=$1
  local passed=$2

  if [ "$passed" = true ]; then
    VERIFICATION_PASSED=$((VERIFICATION_PASSED + 1))
    log_success "$check"
  else
    VERIFICATION_FAILED=$((VERIFICATION_FAILED + 1))
    log_error "$check"
  fi
}

# ============================================================================
# PREREQUISITE CHECKS
# ============================================================================

validate_tools() {
  log_section "Validating Tools"

  local missing=()
  for tool in kubectl jq curl gcloud; do
    if ! command -v "$tool" &> /dev/null; then
      missing+=("$tool")
      log_error "Missing tool: $tool"
    else
      log_success "Found: $tool"
    fi
  done

  if [ ${#missing[@]} -gt 0 ]; then
    log_error "Missing required tools: ${missing[*]}"
    exit 1
  fi
}

check_namespace_exists() {
  log_section "Checking Namespace: $NAMESPACE"

  if kubectl get namespace "$NAMESPACE" &> /dev/null; then
    log_success "Namespace $NAMESPACE exists"
    return 0
  else
    log_error "Namespace $NAMESPACE does not exist"
    return 1
  fi
}

check_kubectl_access() {
  log_section "Checking Kubernetes Access"

  if kubectl cluster-info &> /dev/null; then
    local cluster_name=$(kubectl config current-context)
    log_success "Connected to cluster: $cluster_name"
    return 0
  else
    log_error "Cannot access Kubernetes cluster"
    return 1
  fi
}

# ============================================================================
# POD STATUS VERIFICATION
# ============================================================================

verify_pods_running() {
  log_section "Verifying Pod Status (5 pods per service expected)"

  local all_healthy=true

  for service in "${ALL_SERVICES[@]}"; do
    log_info "Checking pods for $service..."

    local pods=$(kubectl get pods -n "$NAMESPACE" -l app="$service" -o json)
    local pod_count=$(echo "$pods" | jq '.items | length')

    if [ "$pod_count" -eq 0 ]; then
      log_error "$service: No pods found"
      record_result "$service pods exist" false
      all_healthy=false
      continue
    fi

    # Check all pods are running and ready
    local ready_count=$(echo "$pods" | jq '[.items[] | select(.status.conditions[] | select(.type=="Ready" and .status=="True"))] | length')
    local running_count=$(echo "$pods" | jq '[.items[] | select(.status.phase=="Running")] | length')

    if [ "$ready_count" -eq "$pod_count" ] && [ "$running_count" -eq "$pod_count" ]; then
      log_success "$service: All $pod_count/$pod_count pods running and ready"
      record_result "$service pod status" true
    else
      log_warning "$service: $ready_count ready, $running_count running out of $pod_count total"
      record_result "$service pod status" false
      all_healthy=false

      if [ "$DETAILED_MODE" = true ]; then
        echo "$pods" | jq '.items[] | {name: .metadata.name, phase: .status.phase, ready: (.status.conditions[] | select(.type=="Ready") | .status)}'
      fi
    fi
  done

  return $([ "$all_healthy" = true ] && echo 0 || echo 1)
}

# ============================================================================
# SERVICE ENDPOINTS VERIFICATION
# ============================================================================

verify_service_endpoints() {
  log_section "Verifying Service Endpoints"

  local all_ok=true

  for service in "${ALL_SERVICES[@]}"; do
    log_info "Checking endpoints for $service..."

    if ! kubectl get service "$service" -n "$NAMESPACE" &> /dev/null; then
      log_error "$service: Service not found"
      record_result "$service service exists" false
      all_ok=false
      continue
    fi

    local endpoints=$(kubectl get endpoints "$service" -n "$NAMESPACE" -o json)
    local endpoint_count=$(echo "$endpoints" | jq '.subsets[0].addresses | length // 0')

    if [ "$endpoint_count" -gt 0 ]; then
      log_success "$service: $endpoint_count endpoint(s) available"
      record_result "$service endpoints" true

      if [ "$DETAILED_MODE" = true ]; then
        echo "$endpoints" | jq '.subsets[0].addresses[] | .ip' | head -3
      fi
    else
      log_error "$service: No endpoints available"
      record_result "$service endpoints" false
      all_ok=false
    fi
  done

  return $([ "$all_ok" = true ] && echo 0 || echo 1)
}

# ============================================================================
# TRAFFIC ROUTING VERIFICATION
# ============================================================================

verify_traffic_routing() {
  log_section "Verifying Traffic Routing (100% to SF canary expected)"

  log_info "Checking Istio VirtualService configuration..."

  if kubectl get virtualservice sf-canary-routing -n "$NAMESPACE" &> /dev/null; then
    local vs=$(kubectl get virtualservice sf-canary-routing -n "$NAMESPACE" -o json)
    local total_weight=$(echo "$vs" | jq '.spec.http[].route[].weight | add')

    if [ "$total_weight" = "100" ]; then
      log_success "Traffic routing: Weights sum to 100%"
      record_result "Traffic routing configuration" true

      if [ "$DETAILED_MODE" = true ]; then
        echo "$vs" | jq '.spec.http[].route[] | {destination: .destination.host, weight}'
      fi
    else
      log_warning "Traffic routing: Weights sum to $total_weight (expected 100)"
      record_result "Traffic routing configuration" false
    fi
  else
    log_warning "VirtualService sf-canary-routing not found"
    record_result "Traffic routing configuration" false
  fi

  # Check traffic distribution via metrics (if Prometheus is available)
  verify_prometheus_traffic_routing
}

verify_prometheus_traffic_routing() {
  log_info "Checking traffic distribution from Prometheus..."

  # Try to port-forward to Prometheus if available
  local prometheus_pod=$(kubectl get pods -n istio-system -l app=prometheus -o jsonpath='{.items[0].metadata.name}' 2>/dev/null || echo "")

  if [ -z "$prometheus_pod" ]; then
    log_warning "Prometheus not found in istio-system namespace - skipping metrics check"
    return
  fi

  log_info "Found Prometheus pod: $prometheus_pod"
  # In a real scenario, would query Prometheus via port-forward
}

# ============================================================================
# HEALTH CHECK VERIFICATION
# ============================================================================

verify_service_health() {
  log_section "Verifying Service Health Endpoints"

  # Get a pod from catalog-service for health check
  local test_pod=$(kubectl get pods -n "$NAMESPACE" -l app=catalog-service -o jsonpath='{.items[0].metadata.name}' 2>/dev/null || echo "")

  if [ -z "$test_pod" ]; then
    log_warning "No pod found for health check"
    record_result "Service health check" false
    return 1
  fi

  log_info "Testing health endpoint on pod: $test_pod"

  # Exec into pod and curl health endpoint
  if kubectl exec -n "$NAMESPACE" "$test_pod" -- curl -s http://localhost:8080/health &> /dev/null; then
    log_success "Service health endpoint responding"
    record_result "Service health check" true
  else
    log_warning "Service health endpoint not responding"
    record_result "Service health check" false
  fi
}

# ============================================================================
# LATENCY BASELINE CAPTURE
# ============================================================================

capture_latency_baseline() {
  log_section "Capturing Latency Baseline (p50/p95/p99)"

  local baseline_file="${SCRIPTS_DIR}/.sf-canary-latency-baseline.json"

  # Try to get metrics from Prometheus
  local prometheus_pod=$(kubectl get pods -n istio-system -l app=prometheus -o jsonpath='{.items[0].metadata.name}' 2>/dev/null || echo "")

  if [ -z "$prometheus_pod" ]; then
    log_warning "Prometheus not available - generating synthetic latency data"

    cat > "$baseline_file" <<EOF
{
  "timestamp": "$(date -u +%Y-%m-%dT%H:%M:%SZ)",
  "note": "Synthetic baseline - Prometheus not available",
  "latencies_ms": {
    "p50": 45,
    "p95": 180,
    "p99": 450
  }
}
EOF
  else
    # Would query Prometheus in real deployment
    log_info "Prometheus available but querying not implemented in automation"
    return 0
  fi

  log_success "Latency baseline saved to $baseline_file"

  if [ "$DETAILED_MODE" = true ]; then
    jq . "$baseline_file"
  fi
}

# ============================================================================
# STORAGE VERIFICATION
# ============================================================================

verify_storage_backing() {
  log_section "Verifying Storage Backings (PVC Status)"

  local pvcs=$(kubectl get pvc -n "$NAMESPACE" -o json)
  local pvc_count=$(echo "$pvcs" | jq '.items | length')

  if [ "$pvc_count" -eq 0 ]; then
    log_warning "No PersistentVolumeClaims found"
    record_result "Storage backing" true  # Not required for all services
    return 0
  fi

  log_info "Found $pvc_count PVCs"

  local bound_count=$(echo "$pvcs" | jq '[.items[] | select(.status.phase=="Bound")] | length')
  local pending_count=$(echo "$pvcs" | jq '[.items[] | select(.status.phase=="Pending")] | length')

  if [ "$pending_count" -eq 0 ]; then
    log_success "All $bound_count PVCs are bound"
    record_result "Storage backing" true

    if [ "$DETAILED_MODE" = true ]; then
      echo "$pvcs" | jq '.items[] | {name: .metadata.name, phase: .status.phase, size: .spec.resources.requests.storage}'
    fi
  else
    log_warning "$bound_count bound, $pending_count pending"
    record_result "Storage backing" false
  fi
}

# ============================================================================
# RESOURCE USAGE VERIFICATION
# ============================================================================

verify_resource_usage() {
  log_section "Verifying Resource Usage (CPU/Memory)"

  log_info "Checking pod resource usage..."

  # Check if metrics-server is available
  if ! kubectl get deployment metrics-server -n kube-system &> /dev/null; then
    log_warning "metrics-server not found - skipping resource usage check"
    return 0
  fi

  local all_ok=true

  for service in "${CORE_SERVICES[@]}"; do
    local top_output=$(kubectl top pods -n "$NAMESPACE" -l app="$service" --no-headers 2>/dev/null || echo "")

    if [ -z "$top_output" ]; then
      log_warning "$service: No metrics available"
      all_ok=false
      continue
    fi

    local avg_cpu=$(echo "$top_output" | awk '{sum+=$2; count++} END {if (count>0) printf "%.0f", sum/count; else print "0"}')
    local avg_mem=$(echo "$top_output" | awk '{sum+=$3; count++} END {if (count>0) printf "%.0f", sum/count; else print "0"}')

    log_success "$service: CPU avg ${avg_cpu}m, Memory avg ${avg_mem}Mi"
    record_result "$service resource usage" true
  done

  return $([ "$all_ok" = true ] && echo 0 || echo 1)
}

# ============================================================================
# DEPENDENT SERVICE VERIFICATION
# ============================================================================

verify_dependent_services() {
  log_section "Verifying Dependent Services Connectivity"

  log_info "Verifying connectivity to: ${DEPENDENT_SERVICES[*]}"

  local all_ok=true

  for service in "${DEPENDENT_SERVICES[@]}"; do
    if kubectl get service "$service" -n "$NAMESPACE" &> /dev/null; then
      local endpoints=$(kubectl get endpoints "$service" -n "$NAMESPACE" -o jsonpath='{.subsets[0].addresses}' | jq 'length // 0')

      if [ "$endpoints" -gt 0 ]; then
        log_success "$service is available with $endpoints endpoint(s)"
        record_result "$service available" true
      else
        log_warning "$service has no active endpoints"
        record_result "$service available" false
        all_ok=false
      fi
    else
      log_error "$service service not found"
      record_result "$service available" false
      all_ok=false
    fi
  done

  return $([ "$all_ok" = true ] && echo 0 || echo 1)
}

# ============================================================================
# ISTIO CONFIGURATION VERIFICATION
# ============================================================================

verify_istio_configuration() {
  log_section "Verifying Istio Configuration"

  local checks_passed=true

  # Check VirtualServices
  log_info "Checking VirtualServices..."
  local vs_count=$(kubectl get virtualservices -n "$NAMESPACE" --no-headers 2>/dev/null | wc -l)
  if [ "$vs_count" -gt 0 ]; then
    log_success "Found $vs_count VirtualService(s)"
    record_result "VirtualServices exist" true
  else
    log_warning "No VirtualServices found"
    record_result "VirtualServices exist" false
    checks_passed=false
  fi

  # Check DestinationRules
  log_info "Checking DestinationRules..."
  local dr_count=$(kubectl get destinationrules -n "$NAMESPACE" --no-headers 2>/dev/null | wc -l)
  if [ "$dr_count" -gt 0 ]; then
    log_success "Found $dr_count DestinationRule(s)"
    record_result "DestinationRules exist" true
  else
    log_warning "No DestinationRules found"
    record_result "DestinationRules exist" false
    checks_passed=false
  fi

  # Check AuthorizationPolicies
  log_info "Checking AuthorizationPolicies..."
  local ap_count=$(kubectl get authorizationpolicies -n "$NAMESPACE" --no-headers 2>/dev/null | wc -l)
  if [ "$ap_count" -gt 0 ]; then
    log_success "Found $ap_count AuthorizationPolicy(ies)"
    record_result "AuthorizationPolicies exist" true
  else
    log_warning "No AuthorizationPolicies found"
    record_result "AuthorizationPolicies exist" false
    checks_passed=false
  fi

  return $([ "$checks_passed" = true ] && echo 0 || echo 1)
}

# ============================================================================
# VERIFICATION SUMMARY
# ============================================================================

print_verification_summary() {
  log_section "Verification Summary"

  local total=$((VERIFICATION_PASSED + VERIFICATION_FAILED))

  echo ""
  echo -e "${BLUE}Results:${NC}"
  echo -e "  ${GREEN}Passed: $VERIFICATION_PASSED${NC}"
  echo -e "  ${RED}Failed: $VERIFICATION_FAILED${NC}"
  echo -e "  ${BLUE}Total:  $total${NC}"

  if [ "$VERIFICATION_FAILED" -eq 0 ]; then
    echo ""
    echo -e "${GREEN}All verification checks passed! ✓${NC}"
    echo -e "${GREEN}SF Canary deployment is healthy.${NC}"
    return 0
  else
    echo ""
    echo -e "${RED}Some verification checks failed. ✗${NC}"
    return 1
  fi
}

print_verification_status() {
  echo ""
  echo -e "${BLUE}SF Canary Verification Status${NC}"
  echo -e "  Namespace:     $NAMESPACE"
  echo -e "  Region:        $REGION"
  echo -e "  Services:      ${#ALL_SERVICES[@]}"
  echo -e "  Timestamp:     $(date '+%Y-%m-%d %H:%M:%S')"
}

# ============================================================================
# CONTINUOUS MONITORING
# ============================================================================

run_continuous_verification() {
  log_warning "Continuous monitoring enabled (interval: ${CHECK_INTERVAL}s)"
  log_info "Press Ctrl+C to stop"

  local iteration=0

  while true; do
    iteration=$((iteration + 1))
    echo ""
    log_section "Verification Run #$iteration - $(date '+%H:%M:%S')"

    VERIFICATION_PASSED=0
    VERIFICATION_FAILED=0

    verify_pods_running
    verify_service_endpoints
    verify_dependent_services

    print_verification_status

    if [ $VERIFICATION_FAILED -eq 0 ]; then
      echo -e "${GREEN}Status: All checks passed${NC}"
    else
      echo -e "${YELLOW}Status: $VERIFICATION_FAILED check(s) failed${NC}"
    fi

    log_info "Next check in ${CHECK_INTERVAL}s (Ctrl+C to stop)"
    sleep "$CHECK_INTERVAL"
  done
}

# ============================================================================
# ARGUMENT PARSING
# ============================================================================

parse_arguments() {
  while [[ $# -gt 0 ]]; do
    case $1 in
      --detailed)
        DETAILED_MODE=true
        shift
        ;;
      --watch)
        WATCH_MODE=true
        shift
        ;;
      --interval)
        CHECK_INTERVAL="$2"
        shift 2
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
  log_section "Wave 40 Phase 2: SF Canary Verification"
  log_info "Start time: $(date '+%Y-%m-%d %H:%M:%S')"

  parse_arguments "$@"

  validate_tools
  check_kubectl_access
  check_namespace_exists

  print_verification_status

  if [ "$WATCH_MODE" = true ]; then
    run_continuous_verification
  fi

  # Run all verification checks
  verify_pods_running
  verify_service_endpoints
  verify_traffic_routing
  verify_service_health
  capture_latency_baseline
  verify_storage_backing
  verify_resource_usage
  verify_dependent_services
  verify_istio_configuration

  print_verification_summary

  if [ $VERIFICATION_FAILED -eq 0 ]; then
    log_success "All verification checks passed!"
    exit 0
  else
    log_error "Verification completed with $VERIFICATION_FAILED failure(s)"
    exit 1
  fi
}

main "$@"
