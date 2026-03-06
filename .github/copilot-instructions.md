# Copilot Instructions for InstaCommerce

## Build, test, and verification commands

- Start local infrastructure with `docker-compose up -d`. This brings up PostgreSQL, Redis, Kafka, Debezium Kafka Connect, Temporal, Temporal UI, and Kafka UI from `docker-compose.yml`; `scripts/init-dbs.sql` is mounted automatically for local database bootstrap.
- Java/Spring Boot services are the Gradle multi-project modules listed in `settings.gradle.kts`.
  - Build all Java services: `./gradlew build -x test`
  - Run all Java tests: `./gradlew test`
  - Run one service's tests: `./gradlew :services:<service-name>:test`
  - Run one Java test class or method: `./gradlew :services:<service-name>:test --tests "com.instacommerce.<package>.<ClassName>"` or `--tests "com.instacommerce.<package>.<ClassName>.<methodName>"`
  - Run one service locally: `./gradlew :services:<service-name>:bootRun`
  - Rebuild shared contracts and generated stubs: `./gradlew :contracts:build`
- Go services are independent modules under `services/*/go.mod`.
  - Full local validation for one Go service: `cd services/<service-name> && go test -race ./... && go build ./...`
  - Run one Go test by name: `cd services/<service-name> && go test -race ./... -run '^TestName$'`
  - CI uses the same per-service flow without `-race`: `go test ./...` then `go build ./...`
  - If you change `services/go-shared`, CI treats that as a shared dependency and revalidates every Go module.
- Python services are FastAPI apps with per-service `requirements.txt`.
  - AI orchestrator: `cd services/ai-orchestrator-service && pip install -r requirements.txt && uvicorn app.main:app --host 0.0.0.0 --port 8100 --reload`
  - AI inference: `cd services/ai-inference-service && pip install -r requirements.txt && uvicorn app.main:app --host 0.0.0.0 --port 8000 --reload`
  - Python tests: `cd services/<python-service> && pytest -v`
  - Single pytest: `cd services/<python-service> && pytest path/to/test_file.py::test_name -v`
- Data platform commands live under `data-platform/`.
  - `cd data-platform/dbt && dbt deps`
  - `cd data-platform/dbt && dbt run --select staging`
  - `cd data-platform/dbt && dbt run --select intermediate`
  - `cd data-platform/dbt && dbt run --select marts`
  - `cd data-platform/dbt && dbt test`
  - Run one dbt model/test slice: `cd data-platform/dbt && dbt test --select <model-or-selector>`
- `.github/workflows/ci.yml` is the source of truth for what PRs actually execute. CI has build/test jobs plus security gates (Gitleaks, Trivy, dependency review), but no dedicated repo-wide lint task is wired into the workflow.

## High-level architecture

- This is a polyglot microservice monorepo. `settings.gradle.kts` defines 20 Java/Spring Boot services; `services/*/go.mod` adds 8 Go services; `services/ai-orchestrator-service` and `services/ai-inference-service` are Python/FastAPI services.
- The front door is an API layer behind Istio ingress. `mobile-bff-service` and `admin-gateway-service` sit in front of transactional domain services such as identity, catalog, inventory, cart, pricing, order, payment, fulfillment, warehouse, rider fleet, wallet/loyalty, fraud, config/feature flags, and notifications.
- Distributed workflows use Temporal. `checkout-orchestrator-service` is intentionally a pure saga/orchestration service with durable workflow state, while transactional state remains in downstream services such as order, inventory, and payment.
- Cross-service messaging is event-driven. Stateful Java services commonly persist domain changes plus outbox rows in PostgreSQL, Debezium relays those changes into Kafka, and canonical event/proto definitions live in `contracts/`.
- The Go services cover ingestion and operational pipelines: CDC consumption, outbox relaying, payment webhooks, reconciliation, dispatch optimization, location ingestion, and stream processing. `services/go-shared` holds reusable auth, config, health, Kafka, HTTP client, and observability packages for that layer.
- Analytics and ML are first-class subsystems. `data-platform/` contains dbt, Airflow, Apache Beam/Dataflow, and Great Expectations assets; `ml/` contains training, evaluation, feature-store, and serving code; the AI services expose LangGraph orchestration and model inference APIs on top of that stack.
- Delivery is GitOps-based. Docker images are built per service, Helm manifests live in `deploy/helm`, ArgoCD syncs environment changes, and Terraform under `infra/terraform` manages the GCP platform.

## Key conventions

- Use `settings.gradle.kts` and `.github/workflows/ci.yml` as the authoritative list of service/module identifiers. Human-facing docs sometimes use shorter display names, but the real module paths are the ones under `services/` and the CI path filters.
- Java services follow a repeatable structure: `build.gradle.kts`, `src/main/resources/application.yml`, and `src/main/resources/db/migration/V*__*.sql`. If you change persistence or startup behavior, expect Flyway migrations and environment-driven `application.yml` changes rather than ad hoc schema edits.
- Outbox and ShedLock are recurring infrastructure patterns in stateful Java services. Many services have both `create_outbox*` and `create_shedlock*` migrations, so cross-service side effects are usually modeled as outbox events plus scheduled/background jobs instead of direct coupling.
- Event changes are contract-first. Keep the standard event envelope fields (`event_id`, `event_type`, `aggregate_id`, `schema_version`, `source_service`, `correlation_id`, `timestamp`, `payload`) aligned with `contracts/README.md`. Additive changes stay within the current schema version; breaking changes create a new `vN` schema file instead of rewriting the existing one.
- Health and metrics endpoints are stack-specific and consistent across services: Java services use Spring Actuator (`/actuator/health/readiness`, `/actuator/prometheus`), while Go services use `/health`, `/health/ready`, `/health/live`, and `/metrics`.
- Java tests use the JUnit Platform via Gradle, and representative services wire in Testcontainers/PostgreSQL for integration-style coverage. Prefer existing Gradle/JUnit/Testcontainers patterns over adding a new Java test harness.
- PR and `develop` CI runs only the services matched by path filters; `main` and `master` pushes run the full matrices. If you add or rename a service, update the filter list, the service matrix, and any deploy-name mapping in `.github/workflows/ci.yml`.
- Some Go module names differ from their Helm/deploy keys. Current mappings in `.github/workflows/ci.yml` include `cdc-consumer-service -> cdc-consumer`, `location-ingestion-service -> location-ingestion`, and `payment-webhook-service -> payment-webhook`.
- For deeper domain behavior, start with `docs/README.md` and the matching `docs/reviews/*-review.md` file before inferring system behavior from a single service in isolation.

## Additional Copilot customizations in this repo

- Repo-wide cross-cutting guidance lives here; path-specific rules live under `.github/instructions/`.
- Specialized guide agents live under `.github/agents/` for architecture, review, coding best practices, and efficient tool use.
- Reusable task skills live under `.github/skills/` for the same areas when you want a deeper workflow than a one-line instruction file can provide.
