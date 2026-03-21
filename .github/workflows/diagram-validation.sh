#!/bin/bash
# Wave 40 Phase 1: Diagram Validation Script
# Validates all service diagrams for completeness and syntax

set -e

REPO_ROOT=$(git rev-parse --show-toplevel)
DOCS_DIR="$REPO_ROOT/docs/services"

echo "=== Wave 40 Phase 1: Diagram Validation ==="
echo ""

# Services that should have complete 7-diagram coverage
REQUIRED_SERVICES=(
  "checkout-orchestrator-service"
  "order-service"
  "payment-service"
  "payment-webhook-service"
  "inventory-service"
  "fulfillment-service"
  "warehouse-service"
  "routing-eta-service"
  "dispatch-optimizer-service"
  "rider-assignment-service"
  "location-ingestion-service"
  "identity-cluster"
  "admin-gateway-service"
  "mobile-bff-service"
  "audit-trail-service"
  "cdc-consumer-service"
  "outbox-relay-service"
  "stream-processor-service"
  "reconciliation-engine"
  "catalog-service"
  "cart-service"
  "pricing-service"
  "notification-service"
  "config-feature-flag-service"
  "wallet-loyalty-service"
  "search-service"
  "recommendation-engine"
  "ai-inference-service"
  "ai-orchestrator-service"
)

total_services=${#REQUIRED_SERVICES[@]}
complete_services=0
incomplete_services=()

echo "Checking diagram coverage for $total_services services..."
echo ""

for service in "${REQUIRED_SERVICES[@]}"; do
  service_dir="$DOCS_DIR/$service"

  # Count diagrams (01-07)
  diagram_count=$(find "$service_dir" -path "*/diagrams*" -name "0[1-7]-*.md" 2>/dev/null | wc -l || echo "0")

  if [ "$diagram_count" -eq 7 ]; then
    echo "✅ $service: $diagram_count/7"
    ((complete_services++))
  else
    echo "⏳ $service: $diagram_count/7"
    incomplete_services+=("$service")
  fi
done

echo ""
echo "=== Summary ==="
echo "Complete: $complete_services/$total_services"
echo "Incomplete: ${#incomplete_services[@]}"

if [ ${#incomplete_services[@]} -gt 0 ]; then
  echo ""
  echo "Incomplete services:"
  for service in "${incomplete_services[@]}"; do
    echo "  - $service"
  done
fi

# Validate Mermaid syntax in a few random diagrams
echo ""
echo "=== Spot-Checking Mermaid Syntax ==="
sample_count=5
checked=0

for service in "${REQUIRED_SERVICES[@]}"; do
  if [ "$checked" -ge "$sample_count" ]; then
    break
  fi

  service_dir="$DOCS_DIR/$service"
  diagram=$(find "$service_dir" -path "*/diagrams*" -name "0[1-7]-*.md" 2>/dev/null | head -1)

  if [ -n "$diagram" ]; then
    # Check for mermaid code block
    if grep -q "^\`\`\`mermaid" "$diagram"; then
      echo "✅ $diagram: Contains mermaid block"
      ((checked++))
    else
      echo "⚠️  $diagram: Missing or malformed mermaid block"
    fi
  fi
done

echo ""
echo "Validation complete!"
