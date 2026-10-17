# Monorepo Consistency Audit & Remediation Prompt Template

Use this prompt to run an autonomous end-to-end drift audit and remediation across the InstaCommerce monorepo.

---

## Prompt Template

```markdown
Please act as the Monorepo Consistency Guardian and perform a comprehensive consistency and drift audit of the InstaCommerce repository.

Follow these strict execution steps:

1. **Run the Automated Drift Scanner**:
   - Execute `./scripts/check-monorepo-consistency.sh`.
   - Parse all passed checks, warnings, and failures.

2. **Categorize and Triage Detected Drift**:
   - **Critical / Blocking Failures**:
     - Database migration collisions or invalid Flyway numbering
     - Microservices missing in `.github/workflows/ci.yml` path filters or matrix lists
     - Services missing Helm deployment keys in `deploy/helm/values.yaml`
     - Broken Protobuf contracts or JSON event envelopes
     - Services missing Dockerfiles or build scripts
   - **Architectural & Configuration Warnings**:
     - Toolchain version drift (Go 1.24 vs 1.25, Gradle plugins)
     - Missing service README documentation
     - Port conflicts between `application.yml`, Dockerfiles, and Helm manifests
     - Inconsistent health check endpoints (`/actuator/health` vs `/health/ready` vs `/health`)

3. **Execute Safe & Reversible Remediation**:
   - Remediate all critical failures with minimal surgical edits.
   - Align configuration files, path filters, or migration filenames.
   - Re-run `./scripts/check-monorepo-consistency.sh` to confirm 100% clean check.

4. **Deliver Staff-Level Audit Report**:
   - Provide a concise summary table:
     | Service / Component | Drift Detected | Root Cause | Remediated Action | Verification Status |
   - Highlight any residual follow-up items or long-term architectural recommendations.
```

