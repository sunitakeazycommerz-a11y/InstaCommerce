---
name: code-review
description: High-signal, two-axis code review (spec alignment and engineering standards) evaluating diffs with zero token waste.
---

# Code Review Skill

Use this skill when reviewing pull requests, inspecting git diffs, checking code changes before commit, or auditing existing code for smells, defects, and security issues.

---

## 1. Core Principles: Two-Axis Review

A staff-level code review evaluates changes on **two independent axes**:

```
                       Spec Alignment (High)
                                ▲
                                │
             Needs Refactoring  │  Ready to Merge
            (Fowler smells /    │  (Clean, verified,
             leaky boundaries)  │   minimal diff)
                                │
   ◄────────────────────────────┼────────────────────────────►
   Standards (Low)              │              Standards (High)
                                │
               Reject           │  Scope Creep / Gold-Plated
            (Broken logic,      │  (Elegant code that
             wrong behavior)    │   solves the wrong problem)
                                │
                                ▼
                       Spec Alignment (Low)
```

1. **Axis 1: Spec & Invariant Fidelity**:
   - Does this change faithfully implement the requested behavior without scope creep?
   - Are aggregate boundaries and business invariants preserved? (e.g. stock cannot be negative, cancelled orders cannot be dispatched).
   - Are edge cases handled? (nil/null values, network timeouts, duplicate event delivery).
2. **Axis 2: Engineering Standards & Fowler Smells**:
   - Does it adhere to InstaCommerce platform patterns? (Flyway migrations, Outbox pattern, ShedLock, Actuator).
   - Smells to flag:
     - **Primitive Obsession**: Raw strings instead of typed IDs (`OrderId`, `CustomerId`) or monetary values without `Money` record.
     - **Feature Envy / Leaky Abstractions**: Service classes mutating private entity state instead of invoking domain methods.
     - **Shotgun Surgery**: Small changes requiring edits in 10 unrelated files instead of using a clean seam.
     - **Zombie Code**: Commented-out code, unused imports, dead mock fixtures.

---

## 2. Token-Saving Diff Protocol

Do NOT ingest entire source files for code review. Follow this protocol:

1. **Get the Exact Diff First**:
   ```bash
   git diff --stat origin/main...HEAD
   git diff -U5 origin/main...HEAD path/to/changed_file.ext
   ```
2. **Review Only Modified Hunks**:
   - Inspect the modified lines and 5 lines of surrounding context.
   - If symbol context is needed, use `./scripts/lsp-walkthrough outline <file>` to inspect the surrounding method structure instead of viewing the entire 800-line file.
3. **Check Test Alignment**:
   - Every code change must have a corresponding test hunk in the same diff.
   - If business logic changed but no test file was modified, flag immediately.

---

## 3. Review Checklist for InstaCommerce

### Concurrency & Transactions
- [ ] Are database mutations and outbox entries executed in the **same ACID transaction**?
- [ ] Is optimistic (`@Version`) or pessimistic locking applied where concurrent inventory or wallet mutations can occur?
- [ ] Is idempotency guaranteed via `X-Idempotency-Key` or unique DB constraint?

### Messaging & Contracts
- [ ] Does the event payload match `EventEnvelope.v1.json`?
- [ ] Is `correlation_id` propagated across HTTP and Kafka headers?
- [ ] Is the Kafka topic drawn from `TopicNames.java`?
- [ ] Are schema changes backward-compatible? (Additive only in `v1`; breaking changes in new `v2` schema file).

### Persistence & Deployability
- [ ] Are Flyway migrations sequential without version collisions? (Check `V<N>__<name>.sql`).
- [ ] If persistence changed, is a rollback / forward-migration plan documented?
- [ ] Are CI path filters in `.github/workflows/ci.yml` updated if a new file path or service was touched?
- [ ] Is the Helm deploy key in `deploy/helm/values.yaml` matched to any changed Go/Java service?

---

## 4. Review Feedback Format

Format all review findings into three clear tiers:

```markdown
### 🚨 Must-Fix (Blocks Merge)
- **[File:Line]**: Detailed description of bug, race condition, data loss risk, or contract break.
  - *Risk*: What fails in production.
  - *Suggested Fix*: Drop-in code snippet.

### ⚠️ Recommendations (Non-Blocking)
- **[File:Line]**: Architectural cleanup, Fowler smell remediation, or test ergonomics improvement.

### 💡 Questions / Verifications
- **[Area]**: Verification steps for the author (e.g. "Did you verify load behavior when Redis is unavailable?").
```

