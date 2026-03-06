---
applyTo: ".github/workflows/**/*.yml,.github/workflows/**/*.yaml,deploy/**/*.yaml,deploy/**/*.yml,argocd/**/*.yaml,argocd/**/*.yml,infra/**/*.tf,docker-compose.yml"
---

- `.github/workflows/ci.yml` is the source of truth for service path filters, Java and Go validation matrices, and dev deploy tag updates.
- If you add, rename, or split a service, update the CI filter list, matrix entries, full-validation list, and any Go-to-Helm deploy-name mapping together.
- Helm `values-dev.yaml` keys drive GitOps deployments; some Go module names intentionally differ from their deploy keys.
- Keep the separation of concerns between Terraform (`infra/`), Helm (`deploy/helm/`), and ArgoCD (`argocd/`) intact rather than duplicating configuration across layers.
- Keep local infrastructure assumptions aligned with `docker-compose.yml` and `scripts/init-dbs.sql`.
