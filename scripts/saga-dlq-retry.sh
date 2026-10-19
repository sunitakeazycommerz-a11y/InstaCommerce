#!/usr/bin/env bash
# ==============================================================================
# InstaCommerce Saga Compensation Dead-Letter Queue (DLQ) Operational CLI
# ==============================================================================
# Usage:
#   ./scripts/saga-dlq-retry.sh --list
#   ./scripts/saga-dlq-retry.sh --retry <dlq-id>
#   ./scripts/saga-dlq-retry.sh --resolve <dlq-id> [--note "resolution reason"]
#   ./scripts/saga-dlq-retry.sh --help
# ==============================================================================

set -euo pipefail

BASE_URL="${CHECKOUT_SERVICE_URL:-http://localhost:8091}"
DRY_RUN=false
COMMAND=""
TARGET_ID=""
NOTE="Manually resolved via saga-dlq-retry CLI"

show_help() {
    cat <<EOF
InstaCommerce Saga Compensation DLQ CLI

Manage and remediate stuck distributed saga compensations in checkout-orchestrator-service.

USAGE:
    ./scripts/saga-dlq-retry.sh [OPTIONS]

COMMANDS:
    -l, --list                  List all failed/pending saga compensations in DLQ
    -r, --retry <ID>            Retry a specific failed compensation entry
    -s, --resolve <ID>          Mark a compensation entry as manually resolved
    -h, --help                  Show this help message

OPTIONS:
    --url <URL>                 Base URL of checkout-orchestrator-service (default: http://localhost:8091)
    -n, --note <TEXT>           Resolution note when resolving (default: "Manually resolved via saga-dlq-retry CLI")
    -d, --dry-run               Simulate action without making HTTP mutations

EXAMPLES:
    ./scripts/saga-dlq-retry.sh --list
    ./scripts/saga-dlq-retry.sh --retry 4b5f483b-3162-4217-bc2d-5221b2507851
    ./scripts/saga-dlq-retry.sh --resolve 4b5f483b-3162-4217-bc2d-5221b2507851 --note "Inventory released manually in dark store DB"
    ./scripts/saga-dlq-retry.sh --retry 4b5f483b-3162-4217-bc2d-5221b2507851 --dry-run
EOF
}

while [[ $# -gt 0 ]]; do
    case "$1" in
        -l|--list)
            COMMAND="list"
            shift
            ;;
        -r|--retry)
            COMMAND="retry"
            TARGET_ID="${2:-}"
            if [[ -z "$TARGET_ID" || "$TARGET_ID" == -* ]]; then
                echo "Error: --retry requires an ID argument" >&2
                exit 1
            fi
            shift 2
            ;;
        -s|--resolve)
            COMMAND="resolve"
            TARGET_ID="${2:-}"
            if [[ -z "$TARGET_ID" || "$TARGET_ID" == -* ]]; then
                echo "Error: --resolve requires an ID argument" >&2
                exit 1
            fi
            shift 2
            ;;
        -n|--note)
            NOTE="${2:-}"
            shift 2
            ;;
        --url)
            BASE_URL="${2:-}"
            shift 2
            ;;
        -d|--dry-run)
            DRY_RUN=true
            shift
            ;;
        -h|--help)
            show_help
            exit 0
            ;;
        *)
            echo "Unknown option: $1" >&2
            show_help
            exit 1
            ;;
    esac
done

if [[ -z "$COMMAND" ]]; then
    show_help
    exit 1
fi

case "$COMMAND" in
    list)
        echo "Fetching failed compensations from ${BASE_URL}/checkout/dlq..."
        curl -s -f -X GET "${BASE_URL}/checkout/dlq" | jq . || curl -s -X GET "${BASE_URL}/checkout/dlq"
        ;;
    retry)
        if [[ "$DRY_RUN" == "true" ]]; then
            echo "[DRY-RUN] Would send POST ${BASE_URL}/checkout/dlq/${TARGET_ID}/retry"
            exit 0
        fi
        echo "Retrying saga compensation for ID: ${TARGET_ID}..."
        curl -s -f -X POST "${BASE_URL}/checkout/dlq/${TARGET_ID}/retry" | jq . || curl -s -X POST "${BASE_URL}/checkout/dlq/${TARGET_ID}/retry"
        ;;
    resolve)
        if [[ "$DRY_RUN" == "true" ]]; then
            echo "[DRY-RUN] Would send POST ${BASE_URL}/checkout/dlq/${TARGET_ID}/resolve with note: ${NOTE}"
            exit 0
        fi
        echo "Resolving saga compensation for ID: ${TARGET_ID} with note: '${NOTE}'..."
        curl -s -f -X POST "${BASE_URL}/checkout/dlq/${TARGET_ID}/resolve" \
            -H "Content-Type: application/json" \
            -d "{\"note\":\"${NOTE}\"}" | jq . || curl -s -X POST "${BASE_URL}/checkout/dlq/${TARGET_ID}/resolve" -H "Content-Type: application/json" -d "{\"note\":\"${NOTE}\"}"
        ;;
esac
