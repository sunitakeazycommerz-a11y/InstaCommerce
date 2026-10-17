---
name: pr
description: Pull request authoring and structure: concise visual summaries, before/after evidence, merge danger evaluation, and monorepo blast radius analysis.
---

# Pull Request (PR) Skill: High-Signal Monorepo PRs

Use this skill when preparing, structuring, drafting, or finalizing pull requests across the InstaCommerce monorepo to ensure frictionless, staff-level peer review.

---

## 1. Core Principles (Matt Pocock Methodology)

Every PR must respect reviewers' attention and time:
1. **Self-Contained & Context-Rich**: A reviewer should understand the motivation, changes, and verification steps without hunting through Slack or tickets.
2. **Rigorous Risk Evaluation**: Distinguish between reversible changes (two-way doors) and irreversible mutations (one-way doors).
3. **Hard Evidence**: Include proof of correctness (test results, diffs, metrics) rather than asserting "tested locally".

---

## 2. Standard PR Structure Template

Every PR description in InstaCommerce should follow this structure:

```markdown
## Summary
Brief 2-3 sentence overview of the problem, the chosen solution, and key tradeoffs.

## Type of Change
- [ ] 🚀 New Feature
- [ ] 🐛 Bugfix
- [ ] 🔄 Refactoring (no behavior change)
- [ ] 🗄️ Database Migration (Flyway)
- [ ] 📜 Contract / Schema Update (Protobuf / JSON)
- [ ] ⚙️ Infrastructure / Helm / CI

## Blast Radius & Monorepo Impact
- **Services Modified**: `services/order-service`, `services/inventory-service`
- **Contracts Affected**: `contracts/schemas/events/...` (breaking vs backward-compatible)
- **Database Migrations**: `V12__add_index.sql` (locks table? backfill needed?)
- **Helm / GitOps Overlays**: `deploy/helm/values.yaml` (new env vars or secrets?)
- **CI Pipelines**: `.github/workflows/ci.yml` path filter matches

## Merge Danger Assessment
- **Door Type**: [Two-Way Door (Low Risk) | One-Way Door (High Risk)]
- **Data Loss / Corruption Risk**: [None | High (requires backup verification)]
- **Downtime / Schema Lock**: [Zero-downtime | Requires maintenance window]
- **Rollback Strategy**:
  1. Revert Git commit.
  2. Flyway rollback / backward-compatible schema plan.
  3. Feature flag toggle: `feature.order-v2.enabled=false`.

## Verification & Evidence
### Automated Tests
```bash
./gradlew :services:order-service:test --tests "OrderPlacementTest"
# Result: 12 tests passed, 0 failures (1.4s)
```

### Verification Logs / Before-After
- **Before**: Order saga hung on payment timeout without compensation.
- **After**: Temporal compensating activity `ReleaseInventory` triggered within 500ms.
```

---

## 3. Pre-PR Checklist

Before publishing or requesting review:
- [ ] Run `./scripts/check-monorepo-consistency.sh` and ensure 0 failures.
- [ ] Ensure all Flyway migration versions are sequential and unique.
- [ ] Verify no secrets, `.env` files, or temporary scratch files are committed.
- [ ] Update `GLOSSARY.md` if new domain terms or service mappings were introduced.
- [ ] Targeted tests executed and passed with race detection (Go) or Spring context (Java).

