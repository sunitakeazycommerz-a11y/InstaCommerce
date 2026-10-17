# InstaCommerce Developer Onboarding & Engineering Guide

> **Repository:** `InstaCommerce` Monorepo  
> **Source of Truth:** `.github/copilot-instructions.md`, `settings.gradle.kts`, `docker-compose.yml`, and `.github/workflows/ci.yml`.

---

## 1. Local Development Prerequisites

Ensure your workstation has the matching language runtimes and container engine installed:

| Tool / Runtime | Required Version | Verification Command | Purpose |
|---|---|---|---|
| **JDK** | Java 21 (Temurin / OpenJDK) | `java --version` | Required for all 20 Spring Boot 4.0 services |
| **Go** | Go 1.22+ | `go version` | Required for the 8 streaming Go microservices |
| **Python** | Python 3.13 | `python3 --version` | Required for FastAPI AI/ML microservices |
| **Docker** | Docker 24+ & Docker Compose | `docker compose version` | Required for local PostgreSQL, Redis, Kafka, Temporal |
| **Gradle** | Gradle 8.10+ (or use `./gradlew`) | `./gradlew --version` | Root multi-project build tool |

---

## 2. Bootstrapping the Local Environment

InstaCommerce runs a unified local infrastructure stack via `docker-compose.yml`:

```bash
# 1. Start all core infrastructure services in background
docker-compose up -d

# Containers started:
# • instacommerce-postgres (Port 5432) -> Automatically runs scripts/init-dbs.sql (17 DBs)
# • instacommerce-redis    (Port 6379) -> Redis 7 in-memory cache
# • instacommerce-kafka    (Port 9092) -> Kafka in KRaft mode (No ZooKeeper required)
# • instacommerce-debezium (Port 8083) -> Kafka Connect with Debezium CDC
# • instacommerce-temporal (Port 7233) -> Temporal auto-setup server
# • instacommerce-temporal-ui (Port 8233) -> Temporal Web UI
# • instacommerce-kafka-ui (Port 8090) -> Kafka UI Web Console
```

### Verifying Local Infrastructure:
```bash
# Check PostgreSQL has all 17 databases:
docker exec -it instacommerce-postgres psql -U postgres -c "\l"

# Check Kafka is accepting connections:
docker exec -it instacommerce-kafka kafka-topics --bootstrap-server localhost:9092 --list
```

---

## 3. Building & Testing Across Stacks

### 3.1 Java Services (Spring Boot 4.0 Multi-Project)

The root [`settings.gradle.kts`](file:///Users/omkarkumar/InstaCommerce/settings.gradle.kts) coordinates all 20 Java services and the contracts library.

```bash
# Build the shared contracts module first (Protobuf + JSON Schemas)
./gradlew :contracts:build

# Build and run a specific microservice (e.g., order-service)
./gradlew :services:order-service:build
./gradlew :services:order-service:bootRun

# Run unit and integration tests for a specific service
./gradlew :services:payment-service:test

# Run tests with info logging
./gradlew :services:order-service:test --info

# Build standalone executable bootJar
./gradlew :services:order-service:bootJar
```

### 3.2 Go Microservices & `go-shared`

Each Go service maintains an independent `go.mod` file:

```bash
# Enter any Go service directory
cd services/outbox-relay-service

# Download dependencies
go mod download

# Run unit tests with race condition detector
go test -race ./...

# Build local binary
go build -o outbox-relay ./cmd/...

# Run go vet and static checks
go vet ./...
```

### 3.3 Python AI & ML Services

Located under `services/ai-orchestrator-service/` and `services/ai-inference-service/`:

```bash
cd services/ai-inference-service

# Create and activate virtual environment
python3 -m venv .venv
source .venv/bin/activate

# Install dependencies
pip install -r requirements.txt
pip install pytest httpx pytest-asyncio

# Run tests
pytest -v tests/

# Run the FastAPI server locally
uvicorn app.main:app --host 0.0.0.0 --port 8000 --reload
```

---

## 4. How to Add a New Domain Event & Schema

All domain events in InstaCommerce are **contract-first**:

1. **Create the JSON Schema:**
   Add your schema under `contracts/src/main/resources/schemas/<domain>/<EventName>.v1.json`:
   ```json
   {
     "$schema": "https://json-schema.org/draft/2020-12/schema",
     "title": "OrderDeliveredEvent",
     "type": "object",
     "required": ["orderId", "deliveredAt"],
     "properties": {
       "orderId": { "type": "string", "format": "uuid" },
       "deliveredAt": { "type": "string", "format": "date-time" }
     }
   }
   ```
2. **Register the Kafka Topic:**
   Add topic constant to [`contracts/src/main/java/com/instacommerce/contracts/topics/TopicNames.java`](file:///Users/omkarkumar/InstaCommerce/contracts/src/main/java/com/instacommerce/contracts/topics/TopicNames.java).
3. **Compile and Validate:**
   ```bash
   ./gradlew :contracts:build
   ```
   CI automatically checks all JSON schemas for syntax and breaking schema changes.

---

## 5. How to Add a Flyway Database Migration

Every stateful Java service owns its migrations under `services/<service-name>/src/main/resources/db/migration/`:

1. **Naming Standard:**
   `V<NextNumber>__<short_description>.sql` (e.g. `V12__add_rider_vehicle_type.sql`).
2. **Best Practices:**
   - Always make column additions nullable or provide explicit `DEFAULT` values.
   - For high-volume tables (`orders`, `payments`, `outbox_events`), create indexes with `CONCURRENTLY` in production scripts to avoid table locks.
   - Never edit a previously applied Flyway migration script; always write a new forward migration.

---

## 6. GitHub Actions CI Expectations & PR Workflow

1. **Branch Naming:**
   - Feature: `feat/<service>-<short-description>`
   - Bugfix: `fix/<service>-<short-description>`
2. **Path-Filtered Matrix Build:**
   - CI uses `dorny/paths-filter`. Modifying only `order-service` builds only `order-service`.
   - Modifying `services/go-shared/**` triggers re-testing across **all 8 Go services**.
3. **Security Gates in CI:**
   - **Gitleaks:** Scans PR commits for leaked API tokens, passwords, or private keys.
   - **Trivy:** Scans filesystem and built Docker container images for High/Critical CVEs.
   - **Contract Validation:** Validates JSON schemas and checks for breaking Protobuf changes.
4. **GitOps Deployment:**
   - When PRs merge to `main`, CI updates the image tag in `deploy/helm/values-dev.yaml` and commits with `[skip ci]`.
   - ArgoCD automatically syncs and deploys the new revision to GKE.
