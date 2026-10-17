---
name: monorepo-consistency
description: Automated detection, triage, and remediation of architectural and configuration drift across the 30 InstaCommerce microservices.
---

# Monorepo Consistency Skill

Use this skill when auditing the repository for configuration drift, onboarding new microservices, reconciling discrepancies between CI and Helm manifests, or fixing out-of-sync database migrations.

---

## 1. Automated Drift Scanner (`scripts/check-monorepo-consistency.sh`)

Run the automated scanner to inspect all 30 microservices, CI path filters, Helm values, and migration sequences:

```bash
./scripts/check-monorepo-consistency.sh
```

The script verifies:
1. **CI Synchronization**: Every Java, Go, and Python service is registered in `.github/workflows/ci.yml` path filters and matrix variables.
2. **Helm Mapping**: Every service has a matching deployment definition in `deploy/helm/values.yaml`.
3. **Database Migration Health**: No duplicate Flyway version numbers (e.g. two `V10__` files) and outbox tables exist for all transactional services.
4. **Artifact Integrity**: Every service has a valid `Dockerfile` and `README.md`.
5. **Contract Compilation**: Protobuf schemas and event envelopes compile cleanly with `./gradlew :contracts:build`.
6. **Agent & Skill Alignment**: Skills in `.agents/skills` mirror `.github/skills`.

---

## 2. Drift Remediation Playbooks

### Playbook A: Duplicate Flyway Migration Detected
- **Cause**: Two branches authored a migration with the same version number (e.g., `V10__add_outbox.sql` and `V10__add_column.sql`).
- **Remediation**:
  1. Inspect the latest migration version in the directory: `ls services/<service>/src/main/resources/db/migration/`.
  2. Increment the colliding migration to `V<N+1>__<name>.sql`.
  3. Re-run `./scripts/check-monorepo-consistency.sh` to confirm resolution.

### Playbook B: Service Missing in CI Matrix
- **Cause**: A new service was added to `services/` or `settings.gradle.kts` but omitted from `.github/workflows/ci.yml`.
- **Remediation**:
  1. Add path filter entry in `detect-changes` job under `dorny/paths-filter`.
  2. Add the service name to `all_services` (Java) or `all_go_services` (Go) array.
  3. If Go module name differs from Helm deploy key, add mapping in deploy step.

### Playbook C: Helm Key Mismatch
- **Cause**: Service deployed under a different key than its folder name (e.g. `location-ingestion-service` deployed as `location-ingestion`).
- **Remediation**:
  1. Check `deploy/helm/values.yaml` for the matching service section.
  2. Align the key in `HELM_PAIRS` in `scripts/check-monorepo-consistency.sh` and ensure Helm templates render correctly:
     ```bash
     helm template instacommerce deploy/helm -f deploy/helm/values.yaml -s templates/deployment.yaml
     ```

### Playbook D: Missing Dockerfile
- **Cause**: A service was created without a multi-stage container build file.
- **Remediation**:
  - For Go: Use the non-root alpine multi-stage pattern (`golang:1.26-alpine` build stage $\rightarrow$ `alpine:3.23` runtime).
  - For Java: Use the Eclipse Temurin 21 distroless / non-root pattern with Spring Boot jar extraction.
  - For Python: Use `python:3.12-slim` with virtualenv and unprivileged `appuser`.

