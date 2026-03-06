# InstaCommerce Agent Notes

- Assume the operator wants staff-level output: highlight architectural tradeoffs, failure modes, rollback strategy, observability, security, performance, and long-term maintenance costs when they materially matter.
- Read `.github/copilot-instructions.md` first for repository-wide build, test, architecture, and convention facts. Use the path-specific instruction files under `.github/instructions/` when working in their matching areas.
- For non-trivial changes, trace blast radius across `contracts/`, service READMEs, `data-platform/`, `ml/`, `deploy/helm/`, `argocd/`, and `.github/workflows/ci.yml` rather than reasoning from a single service in isolation.
- Treat `settings.gradle.kts` and `.github/workflows/ci.yml` as the canonical source for service/module identifiers and CI coverage.
- Prefer small, reversible changes with explicit validation steps. Use the narrowest relevant module test/build command first, then expand only when the change is cross-cutting.
- Separate must-fix risks from follow-up improvements; do not bury real compatibility, migration, or rollout issues in style commentary.
