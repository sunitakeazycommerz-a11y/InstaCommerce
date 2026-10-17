# InstaCommerce Agent Operating System (`AGENTS.md`)

> Authoritative staff-level operating instructions for autonomous AI agents and engineers working in the InstaCommerce polyglot monorepo.
> Follow these instructions to deliver high-quality, staff-grade software while conserving model tokens and maintaining repository integrity.

---

## 1. Staff-Level Engineering Rules

1. **Staff-Level Rigor**:
   - Always evaluate and articulate architectural tradeoffs, failure modes, rollback strategy, observability, security, performance, and long-term maintenance costs for any non-trivial change.
   - Distinguish must-fix risks (data loss, race conditions, breaking API changes, schema mismatch) from follow-up improvements. Do not bury critical risks in style commentary.
2. **Context & Blast Radius**:
   - For any change, trace blast radius across:
     - `contracts/` (Protobuf & JSON event schemas)
     - Relevant service `README.md` files
     - `data-platform/` (dbt models, Airflow DAGs, Beam pipelines)
     - `ml/` (training configs, feature stores, evaluation gates)
     - `deploy/helm/` and `argocd/` (Helm templates, values overlays, GitOps apps)
     - `.github/workflows/ci.yml` (path filters, test matrices, deploy keys)
3. **Canonical Sources of Truth**:
   - Microservice identifiers: `settings.gradle.kts` (Java), `services/*/go.mod` (Go), and `.github/workflows/ci.yml` (CI matrices).
   - Domain vocabulary & service topology: [GLOSSARY.md](file:///Users/omkarkumar/InstaCommerce/GLOSSARY.md).
   - Architectural decisions: [docs/adr/](file:///Users/omkarkumar/InstaCommerce/docs/adr/).
   - Event topics & envelope: [TopicNames.java](file:///Users/omkarkumar/InstaCommerce/contracts/src/main/java/com/instacommerce/contracts/topics/TopicNames.java) and [EventEnvelope.v1.json](file:///Users/omkarkumar/InstaCommerce/contracts/src/main/resources/schemas/common/EventEnvelope.v1.json).

---

## 2. Token-Saving Protocol (How Agents Must Navigate)

Agents consume excessive tokens when reading large files blindly or running broad commands. Follow these hard rules to save tokens:

1. **Outline Before Ingesting**:
   - NEVER read a 500+ line file without first inspecting its symbol outline.
   - Run `./scripts/lsp-walkthrough outline <file_path>` to view classes, methods, routes, and line numbers.
2. **Read in Targeted Slices**:
   - Use `StartLine` and `EndLine` parameters with `view_file`. Slice out 50–150 lines around the specific method or struct rather than reading the entire file.
3. **Ripgrep with Exact File Types**:
   - Use targeted flags (e.g., `git grep -n "pattern" -- "services/order-service/**"`), not recursive scans of the entire repository.
4. **Target Narrow Validation Loops**:
   - Do NOT run `./gradlew test` across all 20 Java services. Run `./gradlew :services:<service-name>:test --tests "<Class>.<method>"`.
   - In Go, test only the affected package: `cd services/<service> && go test -race ./pkg/<package>`.
   - In Python, target the specific test: `cd services/<service> && pytest tests/test_file.py::test_fn -v`.
5. **Rely on `GLOSSARY.md`**:
   - Use the domain terms defined in [GLOSSARY.md](file:///Users/omkarkumar/InstaCommerce/GLOSSARY.md) rather than writing long exploratory explanations.

---

## 3. Monorepo Command Quick Reference

### Java / Spring Boot 4 Services

```bash
# Build stubs & contracts
./gradlew :contracts:build

# Compile all Java services without tests
./gradlew build -x test

# Test a single Java service
./gradlew :services:<service-name>:test

# Test a single test class or method
./gradlew :services:<service-name>:test --tests "com.instacommerce.<package>.<ClassName>.<methodName>"

# Run service locally
./gradlew :services:<service-name>:bootRun
```

### Go Services

```bash
# Test single Go service with race detector
cd services/<service-name> && go test -race ./...

# Run single Go test by regex name
cd services/<service-name> && go test -race ./... -run '^TestSpecificName$'

# Build Go service binary
cd services/<service-name> && go build ./...
```

### Python / FastAPI Services

```bash
# Run pytest for AI service
cd services/<python-service> && pytest -v

# Run single pytest function
cd services/<python-service> && pytest tests/test_file.py::test_name -v

# Run AI service locally with reload
cd services/ai-orchestrator-service && uvicorn app.main:app --host 0.0.0.0 --port 8100 --reload
cd services/ai-inference-service && uvicorn app.main:app --host 0.0.0.0 --port 8000 --reload
```

### Data Platform & ML

```bash
# dbt dependencies & layer runs
cd data-platform/dbt && dbt deps
cd data-platform/dbt && dbt run --select staging
cd data-platform/dbt && dbt run --select intermediate
cd data-platform/dbt && dbt run --select marts

# Targeted dbt test
cd data-platform/dbt && dbt test --select <model_name>
```

### Infrastructure & GitOps

```bash
# Local development infrastructure (Postgres, Redis, Kafka, Debezium, Temporal)
docker-compose up -d

# Check monorepo consistency and drift
./scripts/check-monorepo-consistency.sh

# LSP code navigation engine health
./scripts/lsp-walkthrough status
```

---

## 4. Microservice Drift Prevention Checklist

When modifying or adding components, enforce alignment against the monorepo baseline:

| Dimension | Baseline Standard | Verification Step |
| --- | --- | --- |
| **Spring Boot / Java** | Spring Boot 4.0.0 BOM, Java 21, Flyway migrations | Check `build.gradle.kts` and `src/main/resources/db/migration/` |
| **Go Toolchain** | Go 1.24+ / 1.25, use `services/go-shared` | Inspect `go.mod` and replace directives |
| **Ports** | Dedicated port per service (see [GLOSSARY.md](file:///Users/omkarkumar/InstaCommerce/GLOSSARY.md)) | Check `application.yml`, Dockerfile, and Helm values |
| **Health Endpoints** | Java: `/actuator/health/readiness`<br>Go: `/health/ready`, `/health/live`<br>Python: `/health` | Curl endpoints or check router registrations |
| **Outbox Pattern** | Transactional `outbox_events` table for stateful services publishing domain events | Verify Flyway migration creates outbox table and indexes |
| **Event Envelope** | Conforms to `EventEnvelope.v1.json` with `eventId`, `eventType`, `correlation_id` | Validate serialized JSON payload structure |
| **CI Path Filters** | Update `.github/workflows/ci.yml` when files/services are added or renamed | Ensure path filter, matrix, and Helm deploy key exist |
| **Helm Mapping** | Update `deploy/helm/values.yaml` with correct service key | Verify mapped names (e.g. `cdc-consumer`, `payment-webhook`) |

---

## 5. Skills & Agents Inventory

Specialized skills and agents in `.agents/` and `.github/`:

### Skills

- **`code-review`**: Two-axis review (spec fidelity + software engineering standards & smells).
- **`architecture-review`**: Staff-level evaluation of boundaries, sagas, CDC, and failure modes.
- **`tool-use`**: High-leverage, token-efficient repository navigation with LSP and search.
- **`doc-search`**: Fast indexing and retrieval of authoritative ADRs and architecture specs.
- **`testing-unit`**: Unit test creation, TDD Red-Green-Refactor loop, and mock isolation.
- **`testing-integration`**: Cross-service Testcontainers, Temporal test environment, and Kafka tests.
- **`pipeline-skills`**: dbt layered modeling, Airflow DAGs, and Great Expectations quality gates.
- **`cd-skills`**: Helm values overlays, ArgoCD sync waves, and canary rollout/rollback.
- **`infra-skills`**: GCP Terraform modules, GKE configuration, Cloud SQL, and state hygiene.
- **`automation-skills`**: Idempotent bash scripting, CI automation, and operational wizards.
- **`monorepo-conventions`**: Standard conventions for packages, REST APIs, logging, and metrics.
- **`monorepo-consistency`**: Detection and remediation of drift across microservices and configs.
- **`diagnosing-bugs`**: Disciplined 5-phase debugging loop for hard bugs and regressions.
- **`code-walkthrough`**: Deep symbol navigation and call hierarchy tracing across services.
- **`wizard`**: Author interactive CLI wizards in bash to safely guide operators through migrations.
- **`architecture-guide`**: High-signal architecture guidance and service boundary analysis.
- **`review-guide`**: Pre-merge risk assessment and correctness checklist reviewer.
- **`coding-best-practices-guide`**: Pattern enforcement guide for Java, Go, Python, and contracts.
- **`efficient-tools-use-guide`**: Low-noise, high-leverage workflows for exploring and validating changes.
- **`domain-modeling`**: Active ubiquitous language sharpening, invariant validation, and GLOSSARY/ADR updates.
- **`codebase-design`**: John Ousterhout deep module discipline, information hiding, seams, and Design-It-Twice.
- **`pr`**: High-signal pull request authoring, before/after evidence, and merge danger evaluation.
- **`verify-this`**: Falsifiable verification loop with baseline and treatment artifact comparison (`cursor-team-kit`).
- **`deslop` / `unslop`**: Eliminates unnecessary AI boilerplate, defensive bloat, chatty comments, and type casts (`cursor-team-kit`, `pstack`).
- **`thermo-nuclear-review`**: Deep security, concurrency, breaking changes, and correctness audit for branches/diffs (`thermos`).
- **`thermo-nuclear-code-quality-review`**: Harsh code quality rubric and interface minimalism enforcement (`thermos`).
- **`architect`**: Spec-first architecture, type sketching, and candidate comparison before writing code (`pstack`).
- **`interrogate`**: Adversarial lead-level code quality interrogation against strict rubrics (`pstack`).
- **`why` / `how`**: Code archaeology (commit history and author intent) and subsystem architectural explanations (`pstack`).
- **`blast-radius`**: Deep blast-radius analysis across services, contracts, and data platform (`pstack`).
- **`figure-it-out`**: Structured hypothesis-driven playbook design for ambiguous or complex tasks (`pstack`).
- **`poteto-mode`**: Multi-phase autonomous engineering playbooks for features, bugs, investigations, and refactoring (`pstack`).
- **`pstack-principles`**: Core engineering principles (boundary discipline, root causes, domain modeling, verifiable units) (`pstack`).
- **`check-compiler-errors`**: Fast, targeted compiler error triage and resolution (`cursor-team-kit`).
- **`fix-ci`**: Reproduces and fixes CI workflow failures systematically (`cursor-team-kit`).
- **`fix-merge-conflicts`**: Step-by-step merge conflict resolution (`cursor-team-kit`).
- **`make-pr-easy-to-review`**: Reviewer-first PR summaries and change maps (`cursor-team-kit`).
- **`cli-for-agents`**: Guidance and review patterns for designing agent-friendly CLI tools (`cli-for-agent`).
- **`check-agent-compatibility`**: Scans repo agent-readiness for boot paths, validation loops, and docs fidelity (`agent-compatibility`).
- **`continual-learning`**: Transcript-driven incremental memory updates into `AGENTS.md` (`continual-learning`).
- **`advisor`**: Multi-model second opinion before critical decisions or when stuck (`advisor`).
- **`ralph-loop`**: Self-referential iterative development loop until completion promise is fulfilled (`ralph-loop`).

### Agents

- **`monorepo-consistency`**: Autonomous agent for scanning and fixing monorepo drift across services, configs, and docs.
- **`architecture-guide`**: Staff-level architecture consultant for big-picture design and migration planning.
- **`code-walkthrough-guide`**: Interactive walkthrough guide for tracing execution paths and state transitions.
- **`review-guide`**: Pre-merge risk assessment and correctness checklist reviewer.
- **`coding-best-practices-guide`**: Pattern enforcement guide for Java, Go, Python, and contracts.
- **`thermo-nuclear-review`**: Comprehensive branch audit subagent for security, bugs, and breaking changes (`thermos`).
- **`thermo-nuclear-code-quality-review`**: Subagent assessing diffs against harsh maintainability and simplicity rubrics (`thermos`).
- **`poteto-agent`**: High-rigor delegate subagent for poteto-mode multi-phase execution (`pstack`).
- **`ci-watcher`**: Background watcher for GitHub Actions CI runs and log triage (`cursor-team-kit`).
- **`advisor-subagent`**: High-reasoning second-opinion subagent for critical checkpoints (`advisor`).
- **`agents-memory-updater`**: Incremental transcript reader that updates `AGENTS.md` with durable preferences (`continual-learning`).
- **`compatibility-scan-review`**: Subagent auditing codebase startup, validation loops, and docs for agent compatibility (`agent-compatibility`).
