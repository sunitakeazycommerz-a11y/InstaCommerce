#!/usr/bin/env bash
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$REPO_ROOT"

STRICT_DOCS=false
for arg in "$@"; do
    case "$arg" in
        --strict-docs) STRICT_DOCS=true ;;
        -h|--help)
            echo "Usage: $0 [--strict-docs]"
            exit 0
            ;;
        *)
            echo "Unknown argument: $arg" >&2
            exit 2
            ;;
    esac
done

RED='\033[0;31m'; GREEN='\033[0;32m'; YELLOW='\033[1;33m'; PURPLE='\033[0;35m'
CYAN='\033[0;36m'; BOLD='\033[1m'; NC='\033[0m'
ERRORS=0; WARNINGS=0; CHECKS=0

log_header() { echo -e "\n${BOLD}${CYAN}=== $1 ===${NC}"; }
log_pass() { CHECKS=$((CHECKS + 1)); echo -e "  ${GREEN}✔ [PASS]${NC} $1"; }
log_warn() { CHECKS=$((CHECKS + 1)); WARNINGS=$((WARNINGS + 1)); echo -e "  ${YELLOW}⚠ [WARN]${NC} $1"; }
log_fail() { CHECKS=$((CHECKS + 1)); ERRORS=$((ERRORS + 1)); echo -e "  ${RED}✖ [FAIL]${NC} $1"; }

assert_check() {
    local pass_msg="$1" fail_msg="$2"; shift 2
    if "$@"; then log_pass "$pass_msg"; else log_fail "$fail_msg"; fi
}

has_pyyaml() {
    command -v python3 >/dev/null 2>&1 && python3 -c "import yaml" 2>/dev/null
}

run_python_check() {
    local subcmd="$1" pass_msg="$2" fail_prefix="$3" require_yaml="${4:-true}" output
    if [ "$require_yaml" = "true" ] && ! has_pyyaml; then return 0; fi
    command -v python3 >/dev/null 2>&1 || return 0
    if output=$(python3 scripts/consistency_checks.py "$subcmd" 2>&1); then
        log_pass "$pass_msg"
    else
        log_fail "${fail_prefix}: ${output}"
    fi
}

list_service_dirs() {
    local svc_dir svc
    for svc_dir in services/*; do
        [ -d "$svc_dir" ] || continue
        svc=$(basename "$svc_dir")
        [ "$svc" = "build" ] || echo "$svc"
    done
}

check_ci_workflow() {
    log_header "1. CI Workflow & Path Filter Synchronization"
    local ci_file=".github/workflows/ci.yml" svc
    [ -f "$ci_file" ] || { log_fail "CI workflow $ci_file missing!"; return; }

    for svc in $(grep 'include("services:' settings.gradle.kts | sed -E 's/.*services:([^"]+)".*/\1/' | sort); do
        assert_check "Java service '$svc' registered in CI path filters" \
            "Java service '$svc' MISSING from CI path filters in $ci_file" \
            grep -q "${svc}: 'services/${svc}/\*\*'" "$ci_file"
    done
    for svc in $(find services -maxdepth 2 -name "go.mod" -exec dirname {} \; | sed 's|services/||' | sort); do
        local label="Go service '$svc'"
        [ "$svc" = "go-shared" ] && label="Go shared library 'go-shared'"
        assert_check "${label} registered in CI path filters" \
            "${label} MISSING from CI path filters in $ci_file" \
            grep -q "${svc}: 'services/${svc}/\*\*'" "$ci_file"
    done
    for svc in "ai-orchestrator-service" "ai-inference-service"; do
        assert_check "Python service '$svc' registered in CI path filters" \
            "Python service '$svc' MISSING from CI path filters in $ci_file" \
            grep -q "${svc}:" "$ci_file"
    done
    for svc in $(list_service_dirs); do
        assert_check "Service directory '$svc' verified tracked in CI path filters" \
            "Service directory '$svc' exists on disk but is MISSING from CI path filters in $ci_file" \
            grep -q "${svc}:" "$ci_file"
    done
}

check_helm_and_istio() {
    log_header "2. Helm Chart & Values Key Mapping"
    local helm_values="deploy/helm/values.yaml" pair svc helm_key has_helm
    [ -f "$helm_values" ] || { log_fail "Helm values file $helm_values missing!"; return; }

    local helm_pairs=(
        "identity-service:identity-service" "catalog-service:catalog-service" "inventory-service:inventory-service"
        "order-service:order-service" "payment-service:payment-service" "fulfillment-service:fulfillment-service"
        "notification-service:notification-service" "search-service:search-service" "pricing-service:pricing-service"
        "cart-service:cart-service" "checkout-orchestrator-service:checkout-orchestrator-service" "warehouse-service:warehouse-service"
        "rider-fleet-service:rider-fleet-service" "routing-eta-service:routing-eta-service" "wallet-loyalty-service:wallet-loyalty-service"
        "audit-trail-service:audit-trail-service" "fraud-detection-service:fraud-detection-service" "config-feature-flag-service:config-feature-flag-service"
        "mobile-bff-service:mobile-bff-service" "admin-gateway-service:admin-gateway-service" "cdc-consumer-service:cdc-consumer"
        "dispatch-optimizer-service:dispatch-optimizer-service" "location-ingestion-service:location-ingestion" "outbox-relay-service:outbox-relay"
        "payment-webhook-service:payment-webhook" "reconciliation-engine:reconciliation-engine" "reverse-etl-orchestrator:reverse-etl-orchestrator"
        "stream-processor-service:stream-processor" "ai-orchestrator-service:ai-orchestrator-service" "ai-inference-service:ai-inference-service"
    )

    for pair in "${helm_pairs[@]}"; do
        svc="${pair%%:*}"; helm_key="${pair##*:}"
        assert_check "Helm definition exists for '$svc' (key: '$helm_key')" \
            "Helm definition MISSING for '$svc' (expected key: '$helm_key') in $helm_values" \
            grep -qE "^  ${helm_key}:" "$helm_values"
    done
    for svc in $(list_service_dirs); do
        [ "$svc" = "go-shared" ] && continue
        has_helm=false
        for pair in "${helm_pairs[@]}"; do [ "${pair%%:*}" = "$svc" ] && has_helm=true && break; done
        assert_check "Microservice '$svc' mapped to Helm deployment configuration" \
            "Microservice '$svc' MISSING from Helm deployment mapping in $helm_values" \
            test "$has_helm" = "true"
    done
    run_python_check "istio-policies" \
        "Helm istio.servicePolicies targets and allowFrom sources align with .Values.services" \
        "Helm istio.servicePolicies drift detected"
}

check_toolchains_and_dependabot() {
    log_header "3. Toolchain & Runtime Versions"
    local go_versions go_count
    go_versions=$(grep -h "^go " services/*/go.mod | sort -u || true)
    go_count=$(echo "$go_versions" | wc -l | tr -d ' ')
    if [ "$go_count" -gt 1 ]; then
        log_warn "Go toolchain version drift detected across modules: $(echo "$go_versions" | tr '\n' ' ')"
    else
        log_pass "Go toolchain version consistent across modules (${go_versions})"
    fi

    assert_check "Spring Boot 4.0.0 BOM enforced in root build.gradle.kts" \
        "Spring Boot 4.0.0 BOM missing or drifted in root build.gradle.kts" \
        grep -q 'mavenBom("org.springframework.boot:spring-boot-dependencies:4.0.0")' build.gradle.kts

    if grep -q 'include("libs:platform-common")' settings.gradle.kts && [ -f "libs/platform-common/build.gradle.kts" ]; then
        log_pass ":libs:platform-common Gradle module included and configured"
    else
        log_fail ":libs:platform-common missing from settings.gradle.kts or build script"
    fi

    [ -f ".github/dependabot.yml" ] && run_python_check "dependabot" \
        ".github/dependabot.yml enforces cooldowns and grouped Docker coverage across all 30 services" \
        ".github/dependabot.yml governance drift"
}

check_migrations() {
    log_header "4. Database Migrations & Outbox Pattern"
    local svc mig_dir svc_name duplicates clean_on_val
    local tx_services=(
        order-service payment-service inventory-service fulfillment-service catalog-service
        rider-fleet-service cart-service wallet-loyalty-service warehouse-service pricing-service
    )

    for svc in "${tx_services[@]}"; do
        mig_dir="services/$svc/src/main/resources/db/migration"
        if [ ! -d "$mig_dir" ]; then
            log_fail "Migration directory missing for '$svc' ($mig_dir)"
        else
            assert_check "Transactional service '$svc' has outbox migration" \
                "Transactional service '$svc' MISSING outbox table migration in $mig_dir" \
                grep -rq "outbox" "$mig_dir"
        fi
    done

    for mig_dir in services/*/src/main/resources/db/migration; do
        [ -d "$mig_dir" ] || continue
        svc_name=$(echo "$mig_dir" | cut -d'/' -f2)
        duplicates=$(ls "$mig_dir" | grep -E '^V[0-9]+__' | sed -E 's/^(V[0-9]+)__.*/\1/' | sort | uniq -d || true)
        assert_check "Flyway migration versions sequential and unique in $svc_name" \
            "Duplicate Flyway migration version(s) in $svc_name: $duplicates" \
            test -z "$duplicates"
    done

    clean_on_val=$(grep -rnE 'clean-on-validation-error:\s*true' services/*/src/main/resources/application*.yml || true)
    assert_check "Flyway clean-on-validation-error is disabled/safe across all production configurations" \
        "Hazardous 'clean-on-validation-error: true' detected in production configuration: $clean_on_val" \
        test -z "$clean_on_val"
}

check_service_artifacts_and_governance() {
    log_header "5. Service Artifacts & Documentation Coverage"
    local svc entry reg_svc rest reg_port helm_key app_yml helm_port owner_path
    local helm_values="deploy/helm/values.yaml"

    for svc in $(list_service_dirs); do
        assert_check "README.md present for '$svc'" "README.md MISSING for '$svc'" test -f "services/$svc/README.md"
        if [ "$svc" != "go-shared" ]; then
            assert_check "Dockerfile present for '$svc'" "Dockerfile MISSING for '$svc'" test -f "services/$svc/Dockerfile"
        fi
    done

    for entry in $(grep -E '^\| `[a-z0-9-]+`' GLOSSARY.md | sed -E 's/^\| `([^`]+)` \| [^\|]+ \| ([0-9]+) \|[^|]+\|[^|]+\| `([^`]+)`.*/\1:\2:\3/'); do
        reg_svc="${entry%%:*}"; rest="${entry#*:}"; reg_port="${rest%%:*}"; helm_key="${rest##*:}"
        assert_check "Service '$reg_svc' README documents port $reg_port (matches GLOSSARY.md)" \
            "Service '$reg_svc' README MISSING documentation for port $reg_port registered in GLOSSARY.md" \
            grep -q "$reg_port" "services/$reg_svc/README.md"

        app_yml="services/$reg_svc/src/main/resources/application.yml"
        if [ -f "$app_yml" ]; then
            assert_check "Service '$reg_svc' application.yml port matches $reg_port" \
                "Service '$reg_svc' application.yml port does NOT match $reg_port from GLOSSARY.md" \
                grep -q "port: \${SERVER_PORT:${reg_port}}" "$app_yml"
        fi

        helm_port=$(awk -v key="  ${helm_key}:" '
            $0 == key { in_svc = 1; next }
            in_svc && /^  [a-z0-9-]+:/ { in_svc = 0 }
            in_svc && /^    port: / { print $2; exit }
        ' "$helm_values")
        assert_check "Service '$reg_svc' Helm port ($helm_port) matches GLOSSARY.md ($reg_port)" \
            "Service '$reg_svc' Helm port ($helm_port) does NOT match GLOSSARY.md ($reg_port)" \
            test "$helm_port" = "$reg_port"

        if [ -f "deploy/helm/README.md" ]; then
            assert_check "Service '$helm_key' deploy/helm/README.md port ($reg_port) matches GLOSSARY.md" \
                "Service '$helm_key' deploy/helm/README.md port table out of sync with GLOSSARY.md ($reg_port)" \
                grep -qE "^\| \`${helm_key}\` \| ${reg_port} \| ${reg_port} \|" deploy/helm/README.md
        fi
    done

    [ -f ".github/CODEOWNERS" ] || { log_fail ".github/CODEOWNERS missing!"; return; }
    log_pass ".github/CODEOWNERS present"
    for svc in $(list_service_dirs); do
        assert_check "CODEOWNERS rule covers '/services/${svc}/'" \
            "CODEOWNERS MISSING explicit rule for '/services/${svc}/'" \
            grep -qE "^/services/${svc}/[[:space:]]+@" .github/CODEOWNERS
    done
    for owner_path in "/libs/" "/contracts/" "/deploy/" "/infra/" "/argocd/" "/monitoring/" "/scripts/" "/tests/e2e/" "/data-platform/" "/data-platform-jobs/" "/ml/" "/prometheus/" "/k8s/" "/GLOSSARY.md" "/AGENTS.md"; do
        assert_check "CODEOWNERS rule covers '${owner_path}'" \
            "CODEOWNERS MISSING explicit rule for '${owner_path}'" \
            grep -qE "^${owner_path}[[:space:]]+@" .github/CODEOWNERS
    done
}

check_contracts_and_argocd() {
    log_header "6. Shared Contracts & Protobuf Compilation"
    local tracked_bins protoc_ver protobuf_java_ver argocd_your_org stale_hikari

    [ -f "contracts/build.gradle.kts" ] && log_pass "contracts build script present"
    assert_check "Canonical EventEnvelope.v1.json schema present" "Canonical EventEnvelope.v1.json schema missing!" \
        test -f "contracts/src/main/resources/schemas/common/EventEnvelope.v1.json"
    assert_check "Canonical TopicNames.java topic constants present" "Canonical TopicNames.java topic constants missing!" \
        test -f "contracts/src/main/java/com/instacommerce/contracts/topics/TopicNames.java"
    assert_check "Kafka reconciliation topic partitions standardized to 12" \
        "Kafka reconciliation topic partitions not standardized to 12 in Terraform" \
        grep -q 'partitions[[:space:]]*=[[:space:]]*12' infra/terraform/debezium-connector-reconciliation.tf

    tracked_bins=$(git ls-files services/ | xargs file 2>/dev/null | grep -E "Mach-O|ELF" || true)
    assert_check "No tracked Mach-O/ELF binaries in services/" \
        "Tracked Mach-O/ELF binaries found in services/: $tracked_bins" test -z "$tracked_bins"

    protoc_ver=$(grep -E 'com\.google\.protobuf:protoc:[0-9.]+' contracts/build.gradle.kts | sed -E 's/.*com\.google\.protobuf:protoc:([0-9.]+).*/\1/' | head -1)
    protobuf_java_ver=$(grep -E 'com\.google\.protobuf:protobuf-java:[0-9.]+' build.gradle.kts | sed -E 's/.*com\.google\.protobuf:protobuf-java:([0-9.]+).*/\1/' | head -1)
    if [ -n "$protoc_ver" ] && [ "$protoc_ver" = "$protobuf_java_ver" ]; then
        log_pass "contracts/build.gradle.kts protoc version ($protoc_ver) matches root protobuf-java version ($protobuf_java_ver)"
    else
        log_fail "contracts/build.gradle.kts protoc version ($protoc_ver) does NOT match root protobuf-java version ($protobuf_java_ver)"
    fi

    argocd_your_org=$(git ls-files -- 'argocd/*.yaml' 'argocd/**/*.yaml' 'argocd/*.yml' 'argocd/**/*.yml' | xargs grep -n "your-org" 2>/dev/null || true)
    assert_check "No 'your-org' placeholder found in argocd/" \
        "Placeholder 'your-org' found in argocd/: $argocd_your_org" test -z "$argocd_your_org"

    run_python_check "argocd-apps" "No duplicate ArgoCD Application metadata.name in argocd/" "Duplicate ArgoCD Application metadata.name found"
    run_python_check "argocd-ports" "ArgoCD Rollout containerPorts match canonical GLOSSARY.md ports" "ArgoCD Rollout containerPort drift detected"
    run_python_check "argocd-tags" "ArgoCD manifests pin non-latest container image tags" "Unpinned or :latest image tag found in argocd/"
    run_python_check "dockerfile-ports" "Service Dockerfiles match canonical GLOSSARY.md ports" "Dockerfile port drift detected"
    run_python_check "dockerfile-base-images" "Service Dockerfiles use standardized base images and non-root USER app" "Dockerfile base-image/user drift detected"
    run_python_check "k8s-compose-ports" "k8s manifests and docker-compose.yml ports and image tags align with GLOSSARY.md" "k8s/docker-compose drift detected"

    stale_hikari=$(grep -rnE 'hikaricp_(active_connections|max_connections|pending_threads|connection_timeout_total)' monitoring/ prometheus/ || true)
    assert_check "Monitoring rules and dashboards use canonical Micrometer hikaricp_connections_* metrics" \
        "Stale HikariCP metric names found in monitoring/: $stale_hikari" test -z "$stale_hikari"

    run_python_check "topic-dlt" "TopicNames.java defines .DLT topics for all .events domain topics" "TopicNames.java missing .DLT constants for" "false"
}

check_agent_customizations() {
    log_header "7. Agent Customizations, Skills & Prompts Alignment"
    local skill agent
    local skills=(
        code-walkthrough architecture-guide review-guide coding-best-practices-guide
        efficient-tools-use-guide code-review architecture-review tool-use doc-search
        testing-unit testing-integration pipeline-skills cd-skills infra-skills
        automation-skills monorepo-conventions monorepo-consistency diagnosing-bugs
        wizard domain-modeling codebase-design pr
    )
    local agents=(
        code-walkthrough architecture-guide review-guide
        coding-best-practices-guide efficient-tools-use-guide monorepo-consistency
    )

    for skill in "${skills[@]}"; do
        if [ -f ".agents/skills/$skill/SKILL.md" ] && [ -f ".github/skills/$skill/SKILL.md" ]; then
            log_pass "Skill '$skill' aligned across .agents and .github"
        else
            log_fail "Skill '$skill' not mirrored in both .agents/skills and .github/skills"
        fi
    done
    for agent in "${agents[@]}"; do
        if [ -f ".agents/agents/$agent.agent.md" ] && [ -f ".github/agents/$agent.agent.md" ]; then
            log_pass "Agent '$agent' aligned across .agents and .github"
        else
            log_fail "Agent '$agent' not mirrored in both .agents/agents and .github/agents"
        fi
    done
    assert_check "GLOSSARY.md present at repository root" "GLOSSARY.md missing from repository root" test -f "GLOSSARY.md"
    assert_check "AGENTS.md present at repository root" "AGENTS.md missing from repository root" test -f "AGENTS.md"
}

check_data_platform() {
    log_header "8. Data Platform & dbt Models"
    run_python_check "dbt-refs" "dbt models reference valid sources/models and incremental models use is_incremental()" "dbt model reference or incremental guard drift detected"
}

check_live_doc_drift() {
    log_header "9. Live Documentation Drift"
    if ! has_pyyaml; then return 0; fi
    local output
    if output=$(python3 scripts/consistency_checks.py doc-drift 2>&1); then
        log_pass "Live root/architecture documentation aligns with codebase truth"
    elif [ "$STRICT_DOCS" = "true" ]; then
        log_fail "Live documentation drift detected: ${output}"
    else
        log_warn "Live documentation drift detected (re-run with --strict-docs after doc sync): ${output}"
    fi
}

echo -e "${BOLD}${PURPLE}================================================================${NC}"
echo -e "${BOLD}${PURPLE}       InstaCommerce Monorepo Consistency & Drift Scanner       ${NC}"
echo -e "${BOLD}${PURPLE}================================================================${NC}"
echo -e "Repository root: ${REPO_ROOT}"
echo -e "Timestamp: $(date -u +"%Y-%m-%dT%H:%M:%SZ")\n"

check_ci_workflow
check_helm_and_istio
check_toolchains_and_dependabot
check_migrations
check_service_artifacts_and_governance
check_contracts_and_argocd
check_agent_customizations
check_data_platform
check_live_doc_drift

echo -e "\n${BOLD}${PURPLE}================================================================${NC}"
echo -e "${BOLD}Scan Summary:${NC}"
echo -e "  Total Checks: ${BOLD}${CHECKS}${NC}"
echo -e "  Passed:       ${GREEN}${BOLD}$((CHECKS - ERRORS - WARNINGS))${NC}"
echo -e "  Warnings:     ${YELLOW}${BOLD}${WARNINGS}${NC}"
echo -e "  Failures:     ${RED}${BOLD}${ERRORS}${NC}"
echo -e "${BOLD}${PURPLE}================================================================${NC}"

[ "$ERRORS" -eq 0 ] || { echo -e "\n${RED}Drift detected! Run with the monorepo-consistency agent to remediate failures.${NC}\n"; exit 1; }
echo -e "\n${GREEN}Monorepo is consistent and aligned with standards!${NC}\n"
