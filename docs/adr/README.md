# Architecture Decision Records (ADRs)

This directory contains Architecture Decision Records for InstaCommerce.

## What is an ADR?

An Architecture Decision Record captures an important architectural decision made along with its context and consequences. ADRs help the team understand *why* a decision was made, not just *what* was decided.

## Template

Each ADR follows this format:

```markdown
# ADR-NNN: Title

- **Status**: Proposed | Accepted | Deprecated | Superseded by ADR-XXX
- **Date**: YYYY-MM-DD
- **Authors**: @github-handle
- **Reviewers**: @team-handle

## Context

What is the issue or force motivating this decision?

## Decision

What is the change being proposed or decided?

## Consequences

What becomes easier or harder as a result of this decision?

### Positive
- ...

### Negative
- ...

### Risks
- ...
```

## Numbering Convention

- ADRs are numbered sequentially: `ADR-001`, `ADR-002`, etc.
- Use zero-padded three-digit numbers for sorting.
- Filename format: `NNN-short-title.md` (e.g., `001-checkout-authority.md`).

## Index

### Event-Driven Architecture (Waves 31-37)
| ADR | Title | Status | Date |
|-----|-------|--------|------|
| [ADR-001](001-checkout-authority.md) | Checkout Authority Consolidation | Accepted | 2026-03-13 |
| [ADR-002](002-dispatch-authority.md) | Dispatch Authority Consolidation | Accepted | 2026-03-13 |
| [ADR-003](003-kafka-dlt-naming.md) | Kafka DLT Naming Convention | Accepted | 2026-03-13 |
| [ADR-004](004-event-envelope.md) | Event Envelope Standard | Accepted | 2026-03-13 |
| [ADR-005](005-durable-idempotency.md) | Durable Idempotency Standard | Accepted | 2026-03-13 |
| [ADR-006](006-internal-service-auth.md) | Internal Service Authentication Hardening | Accepted | 2026-03-13 |
| [ADR-007](007-python-ci-standard.md) | Python Service CI Standard | Accepted | 2026-03-13 |
| [ADR-008](008-dispatch-optimizer-integration.md) | Dispatch Optimizer Integration Pattern | Accepted | 2026-03-13 |
| [ADR-009](009-ai-service-authentication.md) | AI Service Authentication | Accepted | 2026-03-14 |
| [ADR-010](010-per-service-token-rollout.md) | Per-Service Token Rollout | Accepted | 2026-03-14 |

### Authentication & Security (Wave 34)
| ADR | Title | Status | Date |
|-----|-------|--------|------|
| [ADR-011](011-admin-gateway-auth-model.md) | Admin-Gateway Auth Model (JWT from Identity Service) | Accepted | 2026-03-21 |
| [ADR-012](012-per-service-token-scoping.md) | Per-Service Token Scoping (Least-Privilege) | Accepted | 2026-03-21 |

### Caching & Performance (Wave 35)
| ADR | Title | Status | Date |
|-----|-------|--------|------|
| [ADR-013](013-feature-flag-cache-invalidation.md) | Feature-Flag Cache Invalidation (Redis Pub/Sub) | Accepted | 2026-03-21 |

### Governance & Operations (Wave 38)
| ADR | Title | Status | Date |
|-----|-------|--------|------|
| [ADR-014](014-reconciliation-authority.md) | Reconciliation Authority Model (PostgreSQL Ledger) | Accepted | 2026-03-21 |
| [ADR-015](015-slo-error-budget-policy.md) | SLO and Error-Budget Policy (Multi-Window Burn-Rate) | Accepted | 2026-03-21 |

## Process

1. Copy the template above into a new file named `NNN-short-title.md`.
2. Fill in all sections.
3. Open a pull request with the ADR for review.
4. Once approved and merged, update the index table in this README.
5. ADRs are immutable once accepted; if a decision changes, create a new ADR that supersedes the old one.
