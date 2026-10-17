---
name: coding-best-practices-guide
description: Use when implementing or refactoring code in InstaCommerce and you want to stay aligned with the repository's established patterns.
---

Use this skill to anchor implementation work in the patterns this repository already uses.

Stack-specific guidance:

## Java services

- Prefer the existing Spring Boot service shape: `build.gradle.kts`, `application.yml`, Flyway migrations, Actuator endpoints.
- Reuse common operational patterns already present in the repo:
  - Flyway for schema change management
  - outbox tables/events for async side effects
  - ShedLock for distributed scheduled work
  - JUnit Platform and existing test style for validation
- If a change crosses service boundaries, check `contracts/` and the relevant service README before changing payloads or event semantics.

## Go services

- Reuse `services/go-shared` before writing new auth, config, health, Kafka, observability, or resilient HTTP helpers.
- Preserve the standard Go service endpoints: `/health`, `/health/live`, `/health/ready`, `/metrics`.
- Keep module-local validation simple: `go test -race ./...` and `go build ./...`.

## Python, AI, ML, and data platform

- Preserve FastAPI entrypoints and per-service dependency management under each service directory.
- Keep ML behavior config-driven via `ml/train/*/config.yaml` and aligned with evaluation gates.
- Keep dbt models in the appropriate layer (`stg_`, `int_`, `mart_`) and avoid mixing orchestration logic into model SQL.
- Use Great Expectations, Airflow, and Beam/Dataflow in the role each subsystem already owns.

## Contracts and infrastructure

- For shared schemas, prefer additive changes or new versions over in-place breaking edits.
- If a service name or deploy key changes, update CI matrices, Helm mappings, and manifests together.

When in doubt, find the closest existing example in the repo and extend it instead of inventing a fresh pattern.
