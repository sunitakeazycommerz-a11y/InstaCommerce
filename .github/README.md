# GitHub Configuration

CI/CD workflows, Dependabot configuration, and automation for the InstaCommerce platform.

## CI Pipeline

The CI pipeline (`.github/workflows/ci.yml`) uses **multi-service Java + Go build matrices** with change detection, security gates, and controlled deploy automation.

```mermaid
flowchart TD
    A[Push / PR to main, master, or develop; or manual dispatch] --> B[detect-changes]
    A --> C[security-scan]

    B -->|dynamic matrix| D[build-test matrix (Java)]
    B -->|dynamic matrix| G[go-build-test matrix (Go)]
    
    subgraph "Security Scan"
        C --> C1[Gitleaks - Secret Detection]
        C --> C2[Trivy - Vulnerability Scan]
    end

    subgraph "Change Detection"
        B --> B1[dorny/paths-filter]
        B1 --> B2{Which services changed?}
        B2 -->|none detected| B3[Build ALL Java/Go services]
        B2 -->|specific services| B4[Build only changed services]
    end

    subgraph "Build & Test Matrix (per service)"
        D --> D1[Setup JDK 21 + Gradle 8.7]
        D1 --> D2[gradle test]
        D2 --> D3[gradle bootJar]
        D3 -->|main/master only| D4[Docker build]
        D4 --> D5[Push to Artifact Registry]
    end

    D -->|main/master only| E[deploy-dev]
    G -->|main/master only| E
    
    subgraph "Deploy to Dev"
        E --> E1[Update values-dev.yaml image tags for changed services]
        E1 --> E2[Commit & push to main/master]
        E2 --> E3[ArgoCD auto-sync]
    end

    style A fill:#0d1117,color:#fff
    style D fill:#2ea44f,color:#fff
    style C fill:#da3633,color:#fff
    style E fill:#326ce5,color:#fff
```

### Java Services in the Build Matrix

The following 20 services are independently detected and built:

| Service | Path Filter |
|---------|-------------|
| identity-service | `services/identity-service/**` |
| catalog-service | `services/catalog-service/**` |
| inventory-service | `services/inventory-service/**` |
| order-service | `services/order-service/**` |
| payment-service | `services/payment-service/**` |
| fulfillment-service | `services/fulfillment-service/**` |
| notification-service | `services/notification-service/**` |
| search-service | `services/search-service/**` |
| pricing-service | `services/pricing-service/**` |
| cart-service | `services/cart-service/**` |
| checkout-orchestrator-service | `services/checkout-orchestrator-service/**` |
| warehouse-service | `services/warehouse-service/**` |
| rider-fleet-service | `services/rider-fleet-service/**` |
| routing-eta-service | `services/routing-eta-service/**` |
| wallet-loyalty-service | `services/wallet-loyalty-service/**` |
| audit-trail-service | `services/audit-trail-service/**` |
| fraud-detection-service | `services/fraud-detection-service/**` |
| config-feature-flag-service | `services/config-feature-flag-service/**` |
| mobile-bff-service | `services/mobile-bff-service/**` |
| admin-gateway-service | `services/admin-gateway-service/**` |

### Go Services in the Validation Matrix

The following 8 Go modules are validated with `go test ./...` and `go build ./...`:

| Service | Path Filter |
|---------|-------------|
| go-shared | `services/go-shared/**` |
| cdc-consumer-service | `services/cdc-consumer-service/**` |
| dispatch-optimizer-service | `services/dispatch-optimizer-service/**` |
| location-ingestion-service | `services/location-ingestion-service/**` |
| outbox-relay-service | `services/outbox-relay-service/**` |
| payment-webhook-service | `services/payment-webhook-service/**` |
| reconciliation-engine | `services/reconciliation-engine/**` |
| stream-processor-service | `services/stream-processor-service/**` |

Deploy tag updates in `values-dev.yaml` include changed Java services plus deployed Go services (`cdc-consumer`, `dispatch-optimizer-service`, `location-ingestion`, `outbox-relay`, `payment-webhook`, and `reconciliation-engine`) when their source modules change.

### Container Registry

Images are pushed to **Google Artifact Registry**:
```
asia-south1-docker.pkg.dev/instacommerce/images/<service-name>:<git-sha>
```

## How to Add a New Service to CI

1. **Add the path filter** in `ci.yml` under `steps.filter.with.filters`:
   ```yaml
   my-new-service: 'services/my-new-service/**'
   ```

2. **Add the matrix entry** in the `set-matrix` step:
   ```bash
   [[ "${{ steps.filter.outputs.my-new-service }}" == "true" ]] && changed_services+=("my-new-service")
   ```

3. **Add to the fallback list** (builds all when no changes detected):
   ```bash
   services=(... my-new-service)
   ```

4. **Ensure your service has**:
   - A `build.gradle.kts` with `bootJar` task
   - A `Dockerfile` in `services/my-new-service/`
   - Tests runnable via `gradle test`

## Dependabot Configuration

Dependabot (`.github/dependabot.yml`) is configured to automatically create PRs for dependency updates across the monorepo:

| Ecosystem | Directory Scope | Schedule | PR Limit |
|-----------|------------------|----------|----------|
| Gradle | `/` (root) | Weekly | 10 |
| GitHub Actions | `/` (root) | Weekly | 10 |
| Go modules | 8 module directories under `/services/*` | Weekly | 3 per directory |
| Docker | All service directories containing `Dockerfile` | Weekly | 3 per directory |

## Files

| File | Description |
|------|-------------|
| `workflows/ci.yml` | Multi-service CI pipeline with change detection, build matrix, and GitOps deploy |
| `dependabot.yml` | Automated dependency update configuration |
