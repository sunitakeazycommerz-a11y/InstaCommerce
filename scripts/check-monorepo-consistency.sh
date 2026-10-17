#!/usr/bin/env bash
# ==============================================================================
# InstaCommerce Monorepo Consistency & Drift Scanner
# ==============================================================================
# Usage: ./scripts/check-monorepo-consistency.sh [--fix]
# Scans the polyglot monorepo for architectural, configuration, and structural
# drift across Java, Go, Python services, contracts, Helm, and CI workflows.
# ==============================================================================

set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$REPO_ROOT"

# Terminal Colors
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
PURPLE='\033[0;35m'
CYAN='\033[0;36m'
BOLD='\033[1m'
NC='\033[0m' # No Color

ERRORS=0
WARNINGS=0
CHECKS=0

log_header() {
    echo -e "\n${BOLD}${CYAN}=== $1 ===${NC}"
}

log_pass() {
    CHECKS=$((CHECKS + 1))
    echo -e "  ${GREEN}✔ [PASS]${NC} $1"
}

log_warn() {
    CHECKS=$((CHECKS + 1))
    WARNINGS=$((WARNINGS + 1))
    echo -e "  ${YELLOW}⚠ [WARN]${NC} $1"
}

log_fail() {
    CHECKS=$((CHECKS + 1))
    ERRORS=$((ERRORS + 1))
    echo -e "  ${RED}✖ [FAIL]${NC} $1"
}

echo -e "${BOLD}${PURPLE}================================================================${NC}"
echo -e "${BOLD}${PURPLE}       InstaCommerce Monorepo Consistency & Drift Scanner       ${NC}"
echo -e "${BOLD}${PURPLE}================================================================${NC}"
echo -e "Repository root: ${REPO_ROOT}"
echo -e "Timestamp: $(date -u +"%Y-%m-%dT%H:%M:%SZ")\n"

# ------------------------------------------------------------------------------
# 1. CI / GitHub Actions Synchronization Checks
# ------------------------------------------------------------------------------
log_header "1. CI Workflow & Path Filter Synchronization"

CI_FILE=".github/workflows/ci.yml"
SETTINGS_FILE="settings.gradle.kts"

if [ ! -f "$CI_FILE" ]; then
    log_fail "CI workflow $CI_FILE missing!"
else
    # Extract Java services from settings.gradle.kts
    JAVA_SERVICES=$(grep 'include("services:' "$SETTINGS_FILE" | sed -E 's/.*services:([^"]+)".*/\1/' | sort)

    for svc in $JAVA_SERVICES; do
        if grep -q "${svc}: 'services/${svc}/\*\*'" "$CI_FILE"; then
            log_pass "Java service '$svc' registered in CI path filters"
        else
            log_fail "Java service '$svc' MISSING from CI path filters in $CI_FILE"
        fi
    done

    # Check Go services in CI
    GO_MOD_SERVICES=$(find services -maxdepth 2 -name "go.mod" -exec dirname {} \; | sed 's|services/||' | sort)
    for svc in $GO_MOD_SERVICES; do
        if [ "$svc" == "go-shared" ]; then
            if grep -q "go-shared: 'services/go-shared/\*\*'" "$CI_FILE"; then
                log_pass "Go shared library 'go-shared' registered in CI path filters"
            else
                log_fail "Go shared library 'go-shared' MISSING from CI path filters"
            fi
        else
            if grep -q "${svc}: 'services/${svc}/\*\*'" "$CI_FILE"; then
                log_pass "Go service '$svc' registered in CI path filters"
            else
                log_fail "Go service '$svc' MISSING from CI path filters in $CI_FILE"
            fi
        fi
    done

    # Check Python services in CI
    for py_svc in "ai-orchestrator-service" "ai-inference-service"; do
        if grep -q "${py_svc}:" "$CI_FILE"; then
            log_pass "Python service '$py_svc' registered in CI path filters"
        else
            log_fail "Python service '$py_svc' MISSING from CI path filters in $CI_FILE"
        fi
    done
fi

# ------------------------------------------------------------------------------
# 2. Helm Values & Deployment Key Mapping
# ------------------------------------------------------------------------------
log_header "2. Helm Chart & Values Key Mapping"

HELM_VALUES="deploy/helm/values.yaml"
if [ ! -f "$HELM_VALUES" ]; then
    log_fail "Helm values file $HELM_VALUES missing!"
else
    # Pairs of "service_directory:helm_key"
    HELM_PAIRS=(
        "identity-service:identity-service"
        "catalog-service:catalog-service"
        "inventory-service:inventory-service"
        "order-service:order-service"
        "payment-service:payment-service"
        "fulfillment-service:fulfillment-service"
        "notification-service:notification-service"
        "search-service:search-service"
        "pricing-service:pricing-service"
        "cart-service:cart-service"
        "checkout-orchestrator-service:checkout-orchestrator-service"
        "warehouse-service:warehouse-service"
        "rider-fleet-service:rider-fleet-service"
        "routing-eta-service:routing-eta-service"
        "wallet-loyalty-service:wallet-loyalty-service"
        "audit-trail-service:audit-trail-service"
        "fraud-detection-service:fraud-detection-service"
        "config-feature-flag-service:config-feature-flag-service"
        "mobile-bff-service:mobile-bff-service"
        "admin-gateway-service:admin-gateway-service"
        "cdc-consumer-service:cdc-consumer"
        "dispatch-optimizer-service:dispatch-optimizer-service"
        "location-ingestion-service:location-ingestion"
        "outbox-relay-service:outbox-relay"
        "payment-webhook-service:payment-webhook"
        "reconciliation-engine:reconciliation-engine"
        "reverse-etl-orchestrator:reverse-etl-orchestrator"
        "stream-processor-service:stream-processor"
        "ai-orchestrator-service:ai-orchestrator-service"
        "ai-inference-service:ai-inference-service"
    )

    for pair in "${HELM_PAIRS[@]}"; do
        svc="${pair%%:*}"
        helm_key="${pair##*:}"
        if grep -E "^  ${helm_key}:" "$HELM_VALUES" > /dev/null; then
            log_pass "Helm definition exists for '$svc' (key: '$helm_key')"
        else
            log_fail "Helm definition MISSING for '$svc' (expected key: '$helm_key') in $HELM_VALUES"
        fi
    done
fi

# ------------------------------------------------------------------------------
# 3. Toolchain & Dependency Versions
# ------------------------------------------------------------------------------
log_header "3. Toolchain & Runtime Versions"

# Check Go toolchain versions
GO_VERSIONS=$(grep -h "^go " services/*/go.mod | sort -u || true)
GO_VERSION_COUNT=$(echo "$GO_VERSIONS" | wc -l | tr -d ' ')
if [ "$GO_VERSION_COUNT" -gt 1 ]; then
    log_warn "Go toolchain version drift detected across modules: $(echo "$GO_VERSIONS" | tr '\n' ' ')"
else
    log_pass "Go toolchain version consistent across modules (${GO_VERSIONS})"
fi

# Check Java / Spring Boot root BOM
if grep -q 'mavenBom("org.springframework.boot:spring-boot-dependencies:4.0.0")' build.gradle.kts; then
    log_pass "Spring Boot 4.0.0 BOM enforced in root build.gradle.kts"
else
    log_fail "Spring Boot 4.0.0 BOM missing or drifted in root build.gradle.kts"
fi

# ------------------------------------------------------------------------------
# 4. Database Migrations & Outbox Tables
# ------------------------------------------------------------------------------
log_header "4. Database Migrations & Outbox Pattern"

# Core transactional services that MUST have an outbox table
TRANSACTIONAL_SERVICES=(
    "order-service"
    "payment-service"
    "inventory-service"
    "fulfillment-service"
    "catalog-service"
    "rider-fleet-service"
    "cart-service"
    "wallet-loyalty-service"
    "warehouse-service"
    "pricing-service"
)

for svc in "${TRANSACTIONAL_SERVICES[@]}"; do
    mig_dir="services/$svc/src/main/resources/db/migration"
    if [ -d "$mig_dir" ]; then
        if grep -rq "outbox" "$mig_dir"; then
            log_pass "Transactional service '$svc' has outbox migration"
        else
            log_fail "Transactional service '$svc' MISSING outbox table migration in $mig_dir"
        fi
    else
        log_fail "Migration directory missing for '$svc' ($mig_dir)"
    fi
done

# Check for duplicate Flyway migration version numbers
for mig_dir in services/*/src/main/resources/db/migration; do
    if [ -d "$mig_dir" ]; then
        svc_name=$(echo "$mig_dir" | cut -d'/' -f2)
        duplicates=$(ls "$mig_dir" | grep -E '^V[0-9]+__' | sed -E 's/^(V[0-9]+)__.*/\1/' | sort | uniq -d || true)
        if [ -n "$duplicates" ]; then
            log_fail "Duplicate Flyway migration version(s) in $svc_name: $duplicates"
        else
            log_pass "Flyway migration versions sequential and unique in $svc_name"
        fi
    fi
done

# ------------------------------------------------------------------------------
# 5. Service Documentation & Dockerfile Coverage
# ------------------------------------------------------------------------------
log_header "5. Service Artifacts & Documentation Coverage"

for svc_dir in services/*; do
    [ -d "$svc_dir" ] || continue
    svc=$(basename "$svc_dir")
    [ "$svc" == "build" ] && continue

    # Check README.md
    if [ -f "$svc_dir/README.md" ]; then
        log_pass "README.md present for '$svc'"
    else
        log_fail "README.md MISSING for '$svc'"
    fi

    # Check Dockerfile
    if [ "$svc" != "go-shared" ]; then
        if [ -f "$svc_dir/Dockerfile" ]; then
            log_pass "Dockerfile present for '$svc'"
        else
            log_fail "Dockerfile MISSING for '$svc'"
        fi
    fi
done

# ------------------------------------------------------------------------------
# 6. Contracts & Event Schemas
# ------------------------------------------------------------------------------
log_header "6. Shared Contracts & Protobuf Compilation"

if [ -f "contracts/build.gradle.kts" ]; then
    log_pass "contracts build script present"
fi

if [ -f "contracts/src/main/resources/schemas/common/EventEnvelope.v1.json" ]; then
    log_pass "Canonical EventEnvelope.v1.json schema present"
else
    log_fail "Canonical EventEnvelope.v1.json schema missing!"
fi

if [ -f "contracts/src/main/java/com/instacommerce/contracts/topics/TopicNames.java" ]; then
    log_pass "Canonical TopicNames.java topic constants present"
else
    log_fail "Canonical TopicNames.java topic constants missing!"
fi

# ------------------------------------------------------------------------------
# 7. Skills & Agents Directory Alignment
# ------------------------------------------------------------------------------
log_header "7. Agent Customizations, Skills & Prompts Alignment"

ALL_SKILLS=(
    "code-walkthrough"
    "architecture-guide"
    "review-guide"
    "coding-best-practices-guide"
    "efficient-tools-use-guide"
    "code-review"
    "architecture-review"
    "tool-use"
    "doc-search"
    "testing-unit"
    "testing-integration"
    "pipeline-skills"
    "cd-skills"
    "infra-skills"
    "automation-skills"
    "monorepo-conventions"
    "monorepo-consistency"
    "diagnosing-bugs"
    "wizard"
    "domain-modeling"
    "codebase-design"
    "pr"
)

for skill in "${ALL_SKILLS[@]}"; do
    if [ -f ".agents/skills/$skill/SKILL.md" ] && [ -f ".github/skills/$skill/SKILL.md" ]; then
        log_pass "Skill '$skill' aligned across .agents and .github"
    else
        log_fail "Skill '$skill' not mirrored in both .agents/skills and .github/skills"
    fi
done

ALL_AGENTS=(
    "code-walkthrough"
    "architecture-guide"
    "review-guide"
    "coding-best-practices-guide"
    "efficient-tools-use-guide"
    "monorepo-consistency"
)

for agent in "${ALL_AGENTS[@]}"; do
    if [ -f ".agents/agents/$agent.agent.md" ] && [ -f ".github/agents/$agent.agent.md" ]; then
        log_pass "Agent '$agent' aligned across .agents and .github"
    else
        log_fail "Agent '$agent' not mirrored in both .agents/agents and .github/agents"
    fi
done

# Check Ubiquitous Language & Master Instructions
if [ -f "GLOSSARY.md" ]; then
    log_pass "GLOSSARY.md present at repository root"
else
    log_fail "GLOSSARY.md missing from repository root"
fi

if [ -f "AGENTS.md" ]; then
    log_pass "AGENTS.md present at repository root"
else
    log_fail "AGENTS.md missing from repository root"
fi

# ------------------------------------------------------------------------------
# Summary & Exit
# ------------------------------------------------------------------------------
echo -e "\n${BOLD}${PURPLE}================================================================${NC}"
echo -e "${BOLD}Scan Summary:${NC}"
echo -e "  Total Checks: ${BOLD}${CHECKS}${NC}"
echo -e "  Passed:       ${GREEN}${BOLD}$((CHECKS - ERRORS - WARNINGS))${NC}"
echo -e "  Warnings:     ${YELLOW}${BOLD}${WARNINGS}${NC}"
echo -e "  Failures:     ${RED}${BOLD}${ERRORS}${NC}"
echo -e "${BOLD}${PURPLE}================================================================${NC}"

if [ "$ERRORS" -gt 0 ]; then
    echo -e "\n${RED}Drift detected! Run with the monorepo-consistency agent to remediate failures.${NC}\n"
    exit 1
else
    echo -e "\n${GREEN}Monorepo is consistent and aligned with standards!${NC}\n"
    exit 0
fi
