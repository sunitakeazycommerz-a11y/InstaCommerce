---
name: wizard
description: Author interactive CLI wizards in bash to safely guide operators through complex multi-step migrations, cutovers, and secret setups.
---

# Wizard Skill: Interactive Operator Runbooks

Use this skill when designing operational scripts that require human-in-the-loop validation, credentials input, cloud provider console confirmation, or staged database cutovers.

---

## 1. When to Use a Wizard

Do not write raw multi-page markdown instructions when an executable wizard can walk the operator step-by-step:
- Multi-step database cutovers and data backfills.
- Secret rotations and GCP IAM service account provisioning.
- Regional dark store activations and canary traffic promotions.
- Disaster recovery failover procedures.

---

## 2. Standard Wizard Script Structure

Every wizard must be a self-contained, colorized, idempotent bash script with explicit human gates:

```bash
#!/usr/bin/env bash
# ==============================================================================
# InstaCommerce Operational Cutover Wizard
# ==============================================================================
set -euo pipefail

# Visual helpers
BOLD='\033[1m'
CYAN='\033[0;36m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
RED='\033[0;31m'
NC='\033[0m'

TOTAL_STEPS=4
CURRENT_STEP=0

step() {
    CURRENT_STEP=$((CURRENT_STEP + 1))
    echo -e "\n${BOLD}${CYAN}[Step ${CURRENT_STEP}/${TOTAL_STEPS}]${NC} ${BOLD}$1${NC}"
    echo -e "${YELLOW}------------------------------------------------------------${NC}"
}

prompt_confirm() {
    echo -e "\n${YELLOW}Action required:${NC} $1"
    read -rp "Have you completed this step and wish to continue? [y/N]: " answer
    case "$answer" in
        [yY][eE][sS]|[yY]) echo -e "${GREEN}✔ Confirmed.${NC}" ;;
        *) echo -e "${RED}✖ Aborting wizard.${NC}"; exit 1 ;;
    esac
}

# Step 1: Pre-flight Verification
step "Verifying GCP credentials and cluster access"
gcloud auth list --filter=status:ACTIVE --format="value(account)"
kubectl cluster-info

# Step 2: Database Migration Check
step "Checking Flyway database migration status"
./scripts/check-monorepo-consistency.sh
prompt_confirm "Confirm all database migrations and consistency checks passed."

# Step 3: Canary Promotion
step "Executing canary traffic shift"
./scripts/phase-2-sf-canary-deploy.sh
prompt_confirm "Check Grafana SLO dashboard. Confirm error rate < 0.1%."

# Step 4: Verification & Sign-off
step "Running automated verification suite"
./scripts/phase-2-sf-verify.sh
echo -e "\n${BOLD}${GREEN}✔ Wizard completed successfully!${NC}\n"
```

