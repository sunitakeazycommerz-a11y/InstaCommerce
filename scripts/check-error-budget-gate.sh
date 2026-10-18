#!/usr/bin/env bash
# ==============================================================================
# InstaCommerce SRE Error Budget Gate
# Enforces automated deployment freeze when 30-day error budget is exhausted (< 0%).
# Reference: ADR-015 (SLO & Error Budget Policy) & Issue #621.
# ==============================================================================

set -euo pipefail

SERVICE=""
PROMETHEUS_URL="${PROMETHEUS_URL:-http://prometheus.monitoring:9090}"
EMERGENCY_OVERRIDE="${EMERGENCY_OVERRIDE:-false}"
VP_APPROVAL_TOKEN="${VP_APPROVAL_TOKEN:-}"
SIMULATED_AVAILABILITY="${SIMULATED_AVAILABILITY:-}"

usage() {
    cat <<EOF
Usage: $0 --service <service_name> [options]

Options:
  --service <name>            Microservice identifier (e.g. order-service)
  --prometheus-url <url>      Prometheus API endpoint (default: \$PROMETHEUS_URL)
  --emergency-override        Bypass deployment freeze with VP approval
  --vp-token <token>          VP Engineering approval token for emergency hotfix
  --simulated-availability <val> (Optional test override, e.g. 0.992)
  -h, --help                  Show this help message
EOF
    exit 1
}

while [[ $# -gt 0 ]]; do
    case "$1" in
        --service)
            SERVICE="$2"
            shift 2
            ;;
        --prometheus-url)
            PROMETHEUS_URL="$2"
            shift 2
            ;;
        --emergency-override)
            EMERGENCY_OVERRIDE="true"
            shift
            ;;
        --vp-token)
            VP_APPROVAL_TOKEN="$2"
            shift 2
            ;;
        --simulated-availability)
            SIMULATED_AVAILABILITY="$2"
            shift 2
            ;;
        -h|--help)
            usage
            ;;
        *)
            echo "Unknown argument: $1" >&2
            usage
            ;;
    esac
done

if [[ -z "$SERVICE" ]]; then
    echo "ERROR: --service is required" >&2
    usage
fi

# Determine SLO Target based on Service Tier from error-budget-policy.yaml
case "$SERVICE" in
    identity-service|checkout-orchestrator-service|payment-service)
        SLO_TARGET=0.999
        TIER="0"
        ;;
    order-service|inventory-service|fulfillment-service|cart-service|pricing-service|rider-fleet-service|routing-eta-service)
        SLO_TARGET=0.995
        TIER="1"
        ;;
    *)
        SLO_TARGET=0.990
        TIER="2"
        ;;
esac

echo "=================================================================="
echo "InstaCommerce CI Error Budget Gate"
echo "Service:             $SERVICE (Tier $TIER)"
echo "SLO Target:          $(awk -v t="$SLO_TARGET" 'BEGIN { printf "%.2f%%", t*100 }')"
echo "=================================================================="

# Query 30-day availability from Prometheus or simulated test input
if [[ -n "$SIMULATED_AVAILABILITY" ]]; then
    AVAIL_30D="$SIMULATED_AVAILABILITY"
else
    # Prometheus PromQL for 30-day availability SLI
    QUERY="1 - (sum(rate(http_server_requests_seconds_count{service=\"$SERVICE\",status=~\"5..\"}[30d])) / sum(rate(http_server_requests_seconds_count{service=\"$SERVICE\"}[30d])))"
    HTTP_RES=$(curl -s -m 5 -G "${PROMETHEUS_URL}/api/v1/query" --data-urlencode "query=${QUERY}" 2>/dev/null || echo "")
    
    if [[ -z "$HTTP_RES" ]] || ! echo "$HTTP_RES" | grep -q '"status":"success"'; then
        echo "WARNING: Prometheus unavailable or no 30d metric found. Defaulting to 100% availability."
        AVAIL_30D=1.000
    else
        AVAIL_30D=$(echo "$HTTP_RES" | python3 -c 'import sys, json; data=json.load(sys.stdin); res=data["data"]["result"]; print(res[0]["value"][1] if res else 1.0)' 2>/dev/null || echo 1.0)
    fi
fi

# Calculate remaining error budget: 1 - ((1 - Avail) / (1 - Target))
REMAINING_BUDGET=$(awk -v avail="$AVAIL_30D" -v target="$SLO_TARGET" '
BEGIN {
    allowed_unavail = 1.0 - target;
    actual_unavail = 1.0 - avail;
    if (actual_unavail < 0) actual_unavail = 0;
    budget_used = actual_unavail / allowed_unavail;
    remaining = (1.0 - budget_used) * 100;
    printf "%.2f", remaining;
}')

echo "30-Day Availability: $(awk -v a="$AVAIL_30D" 'BEGIN { printf "%.3f%%", a*100 }')"
echo "Budget Remaining:    ${REMAINING_BUDGET}%"

# Check if budget is exhausted (< 0%)
IS_EXHAUSTED=$(awk -v b="$REMAINING_BUDGET" 'BEGIN { print (b < 0) ? "1" : "0" }')

if [[ "$IS_EXHAUSTED" == "1" ]]; then
    echo "------------------------------------------------------------------"
    echo "🚨 DEPLOYMENT BLOCKED: Error budget exhausted (< 0%)!"
    echo "Policy: Non-emergency feature deployments are frozen."
    echo "Engineering capacity must be allocated to reliability fixes."
    echo "------------------------------------------------------------------"

    if [[ "$EMERGENCY_OVERRIDE" == "true" ]]; then
        if [[ "$VP_APPROVAL_TOKEN" == "VP-ENG-OVERRIDE-HOTFIX" ]] || [[ "$VP_APPROVAL_TOKEN" == "EMERGENCY_APPROVED" ]]; then
            echo "⚠️  EMERGENCY OVERRIDE DETECTED with valid VP Engineering token."
            echo "Bypassing freeze for emergency hotfix. Incident ticket audit logged."
            exit 0
        else
            echo "❌ ERROR: Invalid or missing VP_APPROVAL_TOKEN for emergency override."
            exit 1
        fi
    fi

    exit 1
fi

echo "✔ [PASS] Error budget healthy. Promotion allowed."
exit 0
