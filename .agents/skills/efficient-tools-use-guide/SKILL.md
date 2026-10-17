---
name: efficient-tools-use-guide
description: Use when you want a low-noise, high-leverage workflow for exploring, debugging, and validating changes in InstaCommerce.
---

Use this skill to keep exploration and implementation efficient.

Recommended workflow:

1. Start with the smallest authoritative context:
   - `.github/copilot-instructions.md`
   - root and service READMEs
   - build files
   - `.github/workflows/ci.yml`
2. Prefer code intelligence and LSP over broad text search when possible.
3. Batch related searches and file reads instead of opening files serially without a plan.
4. Choose the narrowest validation loop that can prove the change:
   - `./gradlew :services:<service-name>:test`
   - `cd services/<go-service> && go test -race ./...`
   - `cd services/<python-service> && pytest ...`
   - `cd data-platform/dbt && dbt test --select ...`
5. Escalate to broader validation only when the blast radius crosses service or stack boundaries.
6. Use GitHub MCP context when the task depends on PR, issue, or workflow-run history.
7. Keep outputs high signal:
   - summarize what you learned
   - identify the next best action
   - avoid dumping raw logs or exhaustive file inventories unless they are necessary

Favor reversible repo-local or user-local setup over machine-wide changes unless the user explicitly wants machine-wide installation.
