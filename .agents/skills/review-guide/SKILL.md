---
name: review-guide
description: Use when reviewing code, pull requests, or designs in InstaCommerce and you need a high-signal correctness and rollout checklist.
---

Use this skill for pre-merge review, design review, and risk triage.

Review checklist:

1. Correctness and reliability
   - Are state transitions, retries, idempotency, and concurrency rules preserved?
   - Does the change alter Temporal workflow behavior, compensation order, webhook deduplication, or CDC/outbox assumptions?

2. Contracts and compatibility
   - Does the change affect `contracts/`, event payloads, REST responses, or downstream data consumers?
   - If yes, is the change additive or does it require a new versioned schema?

3. Persistence and rollout safety
   - Are Flyway migrations included when schema changes are required?
   - Does the rollout order matter for producers/consumers, readers/writers, or Helm manifests?
   - Are CI path filters, service-name mappings, and deploy manifests still correct?

4. Operability
   - Are health, metrics, traces, and logs still sufficient to debug failures?
   - Would on-call engineers have enough visibility if the new behavior fails in production?

5. Validation
   - Is there a targeted command that proves the changed module still works?
   - Is a broader validation loop needed because the change is cross-service or cross-stack?

Output format:
- Must-fix findings first
- Then follow-up improvements
- Then the highest-risk missing validation step, if any

Avoid style-only comments unless they hide a real maintenance or correctness risk.
