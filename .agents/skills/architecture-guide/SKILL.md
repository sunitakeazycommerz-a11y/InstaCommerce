---
name: architecture-guide
description: Use when a task needs big-picture architecture analysis, service-boundary mapping, or change-impact planning for InstaCommerce.
---

Use this skill to produce staff-level architecture guidance without getting lost in file-by-file trivia.

Recommended workflow:

1. Start with the authoritative overview sources:
   - `.github/copilot-instructions.md`
   - `README.md`
   - `docs/README.md`
   - `contracts/README.md`
   - `.github/workflows/ci.yml`
2. Read the closest service README files and any matching `docs/reviews/*` documents for the area in question.
3. Map the change or question across four dimensions:
   - entrypoints and callers
   - state ownership and persistence
   - async/event flows
   - deploy and rollout path
4. Call out cross-cutting implications explicitly:
   - contracts/schema versioning
   - Flyway or data migration ordering
   - outbox/Kafka consumers
   - Temporal workflow sequencing and compensation
   - Helm, ArgoCD, and CI path-filter impact
5. Present the answer in a compact format:
   - current architecture
   - change impact / blast radius
   - safer migration or rollout sequence
   - validation plan

Do not stop at "which files are involved." Explain why the boundaries exist and where hidden coupling or rollback risk lives.
