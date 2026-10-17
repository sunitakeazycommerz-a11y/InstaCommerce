---
name: automation-skills
description: Production-grade workflow scripting, idempotent shell automation, CI/CD pipelines, and interactive operational wizards.
---

# Automation Skills: Idempotent Scripting & Tooling

Use this skill when writing automation scripts under `scripts/`, modifying GitHub Actions workflows in `.github/workflows/`, or creating operational cutover scripts.

---

## 1. Shell Scripting Golden Rules

All shell scripts in InstaCommerce must follow these strict safety standards:

1. **Strict Error Mode**:
   ```bash
   #!/usr/bin/env bash
   set -euo pipefail
   ```
2. **Directory Independence**:
   Never assume the user runs the script from the repository root. Always anchor to script directory:
   ```bash
   REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
   cd "$REPO_ROOT"
   ```
3. **Trap Handlers for Cleanup**:
   Ensure temporary files or dangling processes are cleaned up on `EXIT`, `SIGINT`, or `SIGTERM`:
   ```bash
   cleanup() {
       rm -rf "${TMP_DIR:-}"
   }
   trap cleanup EXIT
   ```
4. **Dry-Run Support**:
   For any destructive or state-mutating operation, support a `--dry-run` flag.

---

## 2. Interactive Operational Wizards (Matt Pocock Pattern)

When automating complex tasks that require human authorization (such as production database schema cutovers, cloud secret rotations, or canary rollouts), construct an **interactive bash wizard**:

```bash
#!/usr/bin/env bash
set -euo pipefail

step() {
    echo -e "\n\033[1;36mStep $1: $2\033[0m"
}

confirm() {
    read -rp "Proceed? [y/N]: " response
    case "$response" in
        [yY][eE][sS]|[yY]) return 0 ;;
        *) echo "Aborting."; exit 1 ;;
    esac
}

step 1 "Verifying cluster connectivity"
kubectl get nodes

step 2 "Check replication lag before cutover"
./scripts/check-replication-lag.sh
confirm

step 3 "Triggering traffic shift to canary"
kubectl apply -f argocd/applications/canary-austin.yaml
```

---

## 3. GitHub Actions CI Optimization

When modifying `.github/workflows/ci.yml`:
1. **Path Filtering**: Always gate expensive matrix jobs behind `dorny/paths-filter`.
2. **Deterministic Tooling**: Pin action versions to full commit SHA or validated major versions (e.g. `actions/checkout@v6`).
3. **Fail Fast with Concurrency**: Cancel in-progress runs when a new push arrives on the same branch:
   ```yaml
   concurrency:
     group: ci-${{ github.workflow }}-${{ github.event.pull_request.number || github.ref }}
     cancel-in-progress: true
   ```

