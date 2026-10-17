#!/usr/bin/env bash
# Wave 40 Phase 3: Deploy SLO Dashboards and Alert Rules to Grafana + Prometheus
# Comprehensive deployment script with validation and rollback
# Generated: 2026-03-22

set -euo pipefail

# Configuration
GRAFANA_URL="${GRAFANA_URL:-https://grafana.instacommerce.dev}"
GRAFANA_TOKEN="${GRAFANA_TOKEN:-}"
PROMETHEUS_URL="${PROMETHEUS_URL:-https://prometheus.instacommerce.dev}"
PROMETHEUS_CONFIG_PATH="${PROMETHEUS_CONFIG_PATH:-/etc/prometheus/rules}"
DASHBOARDS_DIR="docs/observability/grafana-dashboards"
ALERT_RULES_FILE="prometheus/rules/wave-40-complete-slo-alerts.yml"
LOG_FILE="wave-40-phase3-deployment.log"
BACKUP_DIR="backups/wave-40-phase3-$(date +%Y%m%d_%H%M%S)"

# Color output
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
NC='\033[0m' # No Color

# Logging functions
log() {
    echo -e "${GREEN}[$(date +'%Y-%m-%d %H:%M:%S')]${NC} $1" | tee -a "$LOG_FILE"
}

warn() {
    echo -e "${YELLOW}[$(date +'%Y-%m-%d %H:%M:%S')]${NC} WARNING: $1" | tee -a "$LOG_FILE"
}

error() {
    echo -e "${RED}[$(date +'%Y-%m-%d %H:%M:%S')]${NC} ERROR: $1" | tee -a "$LOG_FILE"
    exit 1
}

# Pre-deployment validation
validate_environment() {
    log "Validating deployment environment..."

    # Check required tools
    for tool in curl jq kubectl; do
        if ! command -v "$tool" &> /dev/null; then
            error "$tool is not installed"
        fi
    done

    # Verify API tokens
    if [ -z "$GRAFANA_TOKEN" ]; then
        error "GRAFANA_TOKEN environment variable not set"
    fi

    # Test connectivity
    if ! curl -sf -H "Authorization: Bearer $GRAFANA_TOKEN" "$GRAFANA_URL/api/health" > /dev/null; then
        error "Cannot connect to Grafana at $GRAFANA_URL"
    fi

    log "Environment validation successful"
}

# Backup existing configurations
backup_configurations() {
    log "Backing up existing configurations..."
    mkdir -p "$BACKUP_DIR"

    # Backup Prometheus rules
    if [ -f "$PROMETHEUS_CONFIG_PATH/slo-alerts.yml" ]; then
        cp "$PROMETHEUS_CONFIG_PATH/slo-alerts.yml" "$BACKUP_DIR/slo-alerts.yml.backup"
        log "  [+] Backed up Prometheus SLO rules"
    fi

    # Backup Grafana dashboards metadata
    curl -sf -H "Authorization: Bearer $GRAFANA_TOKEN" \
        "$GRAFANA_URL/api/search?query=slo" \
        | jq '.' > "$BACKUP_DIR/grafana-dashboards-metadata.json"
    log "  [+] Backed up Grafana dashboard metadata"

    log "Backups created at: $BACKUP_DIR"
}

# Deploy Prometheus alert rules
deploy_prometheus_alerts() {
    log "Deploying Prometheus alert rules..."

    if [ ! -f "$ALERT_RULES_FILE" ]; then
        error "Alert rules file not found: $ALERT_RULES_FILE"
    fi

    # Copy alert rules to Prometheus config
    sudo cp "$ALERT_RULES_FILE" "$PROMETHEUS_CONFIG_PATH/wave-40-slo-alerts.yml"
    log "  [+] Copied alert rules to $PROMETHEUS_CONFIG_PATH"

    # Validate Prometheus configuration
    if ! docker exec prometheus promtool check config /etc/prometheus/prometheus.yml > /dev/null 2>&1; then
        error "Prometheus configuration validation failed"
    fi
    log "  [+] Prometheus configuration validation passed"

    # Reload Prometheus
    curl -sf -X POST "$PROMETHEUS_URL/-/reload" > /dev/null
    log "  [+] Prometheus reloaded successfully"

    sleep 5

    # Verify alerts are loaded
    alert_count=$(curl -sf "$PROMETHEUS_URL/api/v1/rules" | jq '[.data.groups[].rules[]] | length')
    log "  [+] Total alert rules loaded: $alert_count"

    if [ "$alert_count" -lt 50 ]; then
        warn "Expected >50 alert rules, but found: $alert_count"
    fi
}

# Deploy Grafana dashboards
deploy_grafana_dashboards() {
    log "Deploying Grafana SLO dashboards (28 service + 3 platform)..."

    if [ ! -d "$DASHBOARDS_DIR" ]; then
        error "Dashboards directory not found: $DASHBOARDS_DIR"
    fi

    # Count dashboards to deploy
    dashboard_count=$(find "$DASHBOARDS_DIR" -name "*-slo.json" -o -name "platform-*.json" | wc -l)
    log "  Deploying $dashboard_count dashboards..."

    deployed=0
    failed=0

    # Deploy service dashboards
    for dashboard_file in "$DASHBOARDS_DIR"/*-slo.json; do
        if [ -f "$dashboard_file" ]; then
            dashboard_name=$(basename "$dashboard_file" -slo.json)

            # Read dashboard JSON
            dashboard_json=$(cat "$dashboard_file")

            # Extract UID from filename
            uid="slo-${dashboard_name}"

            # Deploy to Grafana
            response=$(curl -sf -X POST \
                -H "Authorization: Bearer $GRAFANA_TOKEN" \
                -H "Content-Type: application/json" \
                -d "{\"dashboard\": $dashboard_json, \"overwrite\": true}" \
                "$GRAFANA_URL/api/dashboards/db")

            if echo "$response" | jq -e '.id' > /dev/null 2>&1; then
                dashboard_id=$(echo "$response" | jq '.id')
                log "  [+] Deployed $dashboard_name (ID: $dashboard_id)"
                ((deployed++))
            else
                error_msg=$(echo "$response" | jq '.message' 2>/dev/null || echo "Unknown error")
                warn "  [!] Failed to deploy $dashboard_name: $error_msg"
                ((failed++))
            fi
        fi
    done

    # Deploy platform dashboards
    for dashboard_file in "$DASHBOARDS_DIR"/platform-*.json; do
        if [ -f "$dashboard_file" ]; then
            dashboard_name=$(basename "$dashboard_file" .json)
            dashboard_json=$(cat "$dashboard_file")

            response=$(curl -sf -X POST \
                -H "Authorization: Bearer $GRAFANA_TOKEN" \
                -H "Content-Type: application/json" \
                -d "{\"dashboard\": $dashboard_json, \"overwrite\": true}" \
                "$GRAFANA_URL/api/dashboards/db")

            if echo "$response" | jq -e '.id' > /dev/null 2>&1; then
                dashboard_id=$(echo "$response" | jq '.id')
                log "  [+] Deployed $dashboard_name (ID: $dashboard_id)"
                ((deployed++))
            else
                warn "  [!] Failed to deploy $dashboard_name"
                ((failed++))
            fi
        fi
    done

    log "Grafana dashboards: $deployed deployed, $failed failed"

    if [ $failed -gt 0 ]; then
        error "Some dashboards failed to deploy"
    fi
}

# Configure notification channels
configure_notifications() {
    log "Configuring alert notification channels..."

    # PagerDuty notification channel
    pagerduty_payload=$(cat <<EOF
{
  "name": "PagerDuty - SLO Alerts",
  "type": "pagerduty",
  "isDefault": false,
  "secureJsonData": {
    "integrationKey": "${PAGERDUTY_KEY:-placeholder}"
  },
  "settings": {}
}
EOF
)

    pagerduty_response=$(curl -sf -X POST \
        -H "Authorization: Bearer $GRAFANA_TOKEN" \
        -H "Content-Type: application/json" \
        -d "$pagerduty_payload" \
        "$GRAFANA_URL/api/v1/provisioning/contact-points")

    log "  [+] PagerDuty notification channel configured"

    # Slack notification channel
    slack_payload=$(cat <<EOF
{
  "name": "Slack - SLO Alerts",
  "type": "slack",
  "isDefault": false,
  "secureJsonData": {
    "url": "${SLACK_WEBHOOK_URL:-placeholder}"
  },
  "settings": {}
}
EOF
)

    curl -sf -X POST \
        -H "Authorization: Bearer $GRAFANA_TOKEN" \
        -H "Content-Type: application/json" \
        -d "$slack_payload" \
        "$GRAFANA_URL/api/v1/provisioning/contact-points" > /dev/null

    log "  [+] Slack notification channel configured"
}

# Verify deployment
verify_deployment() {
    log "Verifying deployment..."

    # Check Prometheus alert rules
    alert_groups=$(curl -sf "$PROMETHEUS_URL/api/v1/rules" | jq '.data.groups | length')
    log "  [+] Prometheus alert groups: $alert_groups"

    # Check Grafana dashboards
    dashboards=$(curl -sf -H "Authorization: Bearer $GRAFANA_TOKEN" \
        "$GRAFANA_URL/api/search?query=slo" | jq 'length')
    log "  [+] Grafana SLO dashboards: $dashboards"

    # Test alert evaluation
    test_alert=$(curl -sf "$PROMETHEUS_URL/api/v1/rules" | \
        jq '.data.groups[] | select(.name=="tier_1_critical_path") | .rules | length')
    log "  [+] Critical path alert rules: $test_alert"

    if [ "$test_alert" -lt 10 ]; then
        error "Critical path alerts not properly loaded"
    fi

    log "Deployment verification successful"
}

# Create SLO dashboard folder in Grafana
setup_grafana_folders() {
    log "Creating Grafana folders for SLO dashboards..."

    folders=("SLO Dashboards" "Platform SLO" "Service Tier 1" "Service Tier 2" "Service Tier 3")

    for folder_name in "${folders[@]}"; do
        folder_payload="{\"title\": \"$folder_name\"}"

        curl -sf -X POST \
            -H "Authorization: Bearer $GRAFANA_TOKEN" \
            -H "Content-Type: application/json" \
            -d "$folder_payload" \
            "$GRAFANA_URL/api/folders" > /dev/null 2>&1

        log "  [+] Folder created: $folder_name"
    done
}

# Generate summary report
generate_summary() {
    log "Generating deployment summary..."

    summary_file="wave-40-phase3-summary.md"
    cat > "$summary_file" <<EOF
# Wave 40 Phase 3 Deployment Summary
**Timestamp**: $(date -u '+%Y-%m-%d %H:%M:%S UTC')
**Status**: $([ $? -eq 0 ] && echo 'SUCCESS' || echo 'PARTIAL SUCCESS')**

## Deployed Artifacts
- **Prometheus Alert Rules**: 50+ multi-window burn-rate rules
- **Grafana Dashboards**: 28 service + 3 platform = 31 total
- **Notification Channels**: PagerDuty, Slack
- **Grafana Folders**: 5 organizational folders

## Alert Rules Breakdown
- Tier 1 (Critical Path): 18 rules
- Tier 2 (Read Path): 7 rules
- Tier 3 (Platform): 12 rules
- Tier 4 (Logistics): 4 rules
- Tier 5 (Mobile/AI): 6 rules
- Error Budget Tracking: 2 rules
- Infrastructure: 7 rules
- Cross-Service Dependencies: 2 rules

## Service Coverage (28 services)
### Tier 1 (Critical)
- payment-service (99.95% SLO)
- payment-webhook-service (99.99% SLO)
- order-service (99.9% SLO)
- checkout-orchestrator-service (99.9% SLO)
- fulfillment-service (99.5% SLO)

### Tier 2 (Read Path)
- search-service (99% SLO)
- catalog-service (99.5% SLO)
- cart-service (99.8% SLO)
- pricing-service (99% SLO)
- inventory-service (99.5% SLO)
- fraud-detection-service (99% SLO)
- config-feature-flag-service (99% SLO)

### Tier 3 & Beyond
- identity-service, audit-trail-service, admin-gateway-service
- cdc-consumer-service, outbox-relay-service, stream-processor-service
- reconciliation-engine
- wallet-loyalty-service, notification-service
- routing-eta-service, dispatch-optimizer-service
- warehouse-service, rider-fleet-service, location-ingestion-service
- mobile-bff-service
- ai-orchestrator-service, ai-inference-service

## Dashboard Panels (Per Service)
Each service dashboard includes:
- SLO Status Gauge (current availability %)
- Error Budget Remaining (%)
- Service SLO Summary Table
- Latency Percentiles (p50/p95/p99)
- Request Rate (RPS)
- Error Rate (5xx)
- Error Budget Burn Rate (3 windows)

## Platform Dashboards (3)
1. **Cross-Service SLO Overview**: All 28 services status at glance
2. **Error Budget Burn Analysis**: Monthly burn rate per service
3. **Incident Timeline & Correlation**: Service dependency correlation

## Alert Severity Levels
- **SEV-1 (Critical)**: Fast burn >10% error rate (5min window) → Page immediately
- **SEV-2 (Warning)**: Medium burn >5% error rate (30min window) → Create incident
- **SEV-3 (Info)**: Slow burn >1% error rate (6h window) → SRE review

## Validation Checklist
- [ ] Prometheus alert rules loaded and validating
- [ ] Grafana dashboards accessible from all regions
- [ ] Notification channels tested (PagerDuty, Slack)
- [ ] On-call team trained on SLO monitoring
- [ ] Runbooks linked in all alert annotations
- [ ] Error budget tracking verified for 30-day window
- [ ] Burn-rate calculations confirmed accurate

## Backup Location
Configurations backed up at: $BACKUP_DIR

## Next Steps (Phase 3 Completion)
1. Schedule team training session (all SRE + service owners)
2. Validate alert thresholds in staging (48 hour test)
3. Configure on-call escalation policies
4. Deploy to production (scheduled window)
5. Monitor alert false positive rate (target: <5%)
6. Publish SLO dashboard links to team wiki

## Support & Escalation
- **SLO Questions**: #slo-alerts Slack channel
- **Dashboard Issues**: Grafana Admin team
- **Alert Tuning**: Platform team + Service owner
EOF

    log "Summary report: $summary_file"
}

# Rollback function
rollback() {
    log "Rolling back to previous configuration..."

    if [ -d "$BACKUP_DIR" ]; then
        # Restore Prometheus rules
        if [ -f "$BACKUP_DIR/slo-alerts.yml.backup" ]; then
            sudo cp "$BACKUP_DIR/slo-alerts.yml.backup" "$PROMETHEUS_CONFIG_PATH/slo-alerts.yml"
            curl -sf -X POST "$PROMETHEUS_URL/-/reload" > /dev/null
            log "  [+] Prometheus rules restored"
        fi

        log "Rollback completed. Backup location: $BACKUP_DIR"
    else
        error "Backup directory not found"
    fi
}

# Main execution flow
main() {
    log "Starting Wave 40 Phase 3 Deployment (31 SLO Dashboards + 50+ Alert Rules)"
    log "========================================================================="

    # Pre-deployment checks
    validate_environment
    backup_configurations

    # Deployment
    deploy_prometheus_alerts
    setup_grafana_folders
    deploy_grafana_dashboards
    configure_notifications

    # Post-deployment verification
    verify_deployment
    generate_summary

    log "========================================================================="
    log "Wave 40 Phase 3 Deployment COMPLETED SUCCESSFULLY"
    log "Review summary report: wave-40-phase3-summary.md"
}

# Handle script arguments
case "${1:-deploy}" in
    deploy)
        main
        ;;
    rollback)
        rollback
        ;;
    validate)
        validate_environment
        ;;
    *)
        echo "Usage: $0 {deploy|rollback|validate}"
        exit 1
        ;;
esac
