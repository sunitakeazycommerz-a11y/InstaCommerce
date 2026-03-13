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

| ADR | Title | Status | Date |
|-----|-------|--------|------|
| *None yet -- ADR-001 will be created by Track A (checkout authority)* | | | |

## Process

1. Copy the template above into a new file named `NNN-short-title.md`.
2. Fill in all sections.
3. Open a pull request with the ADR for review.
4. Once approved and merged, update the index table in this README.
5. ADRs are immutable once accepted; if a decision changes, create a new ADR that supersedes the old one.
