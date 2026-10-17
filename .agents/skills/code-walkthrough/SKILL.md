---
name: code-walkthrough
description: Deep-dive code walkthrough guide for Java, Go, and Python microservices in InstaCommerce using Language Server Protocol (LSP) and AST tools.
---

# Code Walkthrough Guide (Java, Go, Python)

Use this skill when onboarding engineers, conducting architecture reviews, tracing distributed transactions, or auditing domain models across the 30 InstaCommerce microservices.

---

## 1. Quick Tooling Reference (`scripts/lsp-walkthrough`)

The repository includes a unified code-walkthrough CLI that wraps globally installed Language Server Protocol (LSP) engines (`jdtls`, `gopls`, `pyright`):

```bash
# 1. Check health of language servers across Java, Go, and Python
./scripts/lsp-walkthrough status

# 2. Extract structural symbol outline (classes, structs, functions, routes, line numbers)
./scripts/lsp-walkthrough outline <file_path>

# 3. Jump to definition of a symbol
./scripts/lsp-walkthrough def <file_path> <line> <col>

# 4. Find all usages / references across the module
./scripts/lsp-walkthrough refs <file_path> <line> <col>

# 5. Run static diagnostics & type checking
./scripts/lsp-walkthrough check <file_or_directory>

# 6. Generate an architectural markdown walkthrough report for any source file
./scripts/lsp-walkthrough walkthrough <file_path>
```

---

## 2. Direct LSP Commands by Language

### 🐹 Go (`gopls v0.23.0`)
All 8 streaming Go microservices (`services/*`) and `services/go-shared` are natively supported by `gopls`:

```bash
# Symbol Outline for any Go file
gopls symbols services/go-shared/pkg/health/handler.go

# Jump to declaration
gopls definition services/go-shared/pkg/health/handler.go:46:6

# Find all references across Go packages
gopls references services/go-shared/pkg/health/handler.go:46:6

# Check diagnostics / compile errors
gopls check services/outbox-relay-service/...

# Call Hierarchy (Callers / Callees)
gopls call_hierarchy services/go-shared/pkg/kafka/producer.go:35:10
```

### 🐍 Python (`pyright v1.1.414` & AST)
FastAPI services (`services/ai-orchestrator-service/`, `services/ai-inference-service/`, and `ml/`):

```bash
# Run type checks and missing import diagnostics
pyright services/ai-inference-service/app/main.py

# Type-check entire service
pyright services/ai-orchestrator-service/app/

# Generate structural outline of classes, methods, and FastAPI routes
./scripts/lsp-walkthrough outline services/ai-orchestrator-service/app/main.py
```

### ☕ Java (`jdtls v1.61.0` & Spring AST)
All 20 Spring Boot 4.0 microservices (`services/*`) and `contracts/`:

```bash
# Generate complete class, annotation, and method inventory
./scripts/lsp-walkthrough outline services/order-service/src/main/java/com/instacommerce/order/domain/model/Order.java

# Generate end-to-end controller and endpoint walkthrough
./scripts/lsp-walkthrough walkthrough services/order-service/src/main/java/com/instacommerce/order/controller/OrderController.java

# Validate compilation using narrow Gradle task
./gradlew :services:order-service:compileJava
```

---

## 3. Four Core Walkthrough Methodologies

### Pattern A: Top-Down Walkthrough (API Ingress $\rightarrow$ Persistence)
Use when understanding how an external HTTP/REST or gRPC request is handled:
1. **Controller Layer:** Run `./scripts/lsp-walkthrough outline <Controller.java>`. Identify `@GetMapping`, `@PostMapping`, security annotations (`@PreAuthorize`), and DTO inputs.
2. **Application Service:** Inspect transaction boundaries (`@Transactional`), idempotency checks, and orchestration logic.
3. **Domain Aggregate:** Inspect business state transitions (e.g. `OrderStateMachine.java`), invariants, and optimistic locking (`@Version`).
4. **Persistence & Outbox:** Inspect JPA repositories, SQL queries, and `outbox_events` table writes.

### Pattern B: Bottom-Up Walkthrough (Database Schema $\rightarrow$ Business API)
Use when understanding data models, financial ledgers, or inventory locking:
1. **Flyway Migrations:** Read `services/<service>/src/main/resources/db/migration/V*__*.sql`.
2. **JPA Entity / Go Struct:** Map database columns to fields, unique constraints, and check constraints.
3. **Domain Methods:** Walk through methods mutating state; verify that business invariants cannot be bypassed.
4. **API Representation:** Trace how domain entities are transformed into external DTO responses.

### Pattern C: Event-Driven Walkthrough (Async Choreography)
Use when tracing asynchronous domain events:
1. **Producer:** Locate `outbox_events` insert in the stateful service.
2. **Outbox Relay:** Trace `services/outbox-relay-service` polling `SELECT ... FOR UPDATE SKIP LOCKED` and publishing to Kafka.
3. **Kafka Contract:** Inspect event schema in `contracts/src/main/resources/schemas/<domain>/` and topic name in `contracts/src/main/java/com/instacommerce/contracts/topics/TopicNames.java`.
4. **Consumer:** Trace consumer loop (e.g., `FulfillmentEventConsumer.java`), verifying idempotency keys and error handling / `.DLT` routing.

### Pattern D: Saga-Orchestrated Walkthrough (Temporal Workflows)
Use when walking through checkout, refunds, or multi-step coordination:
1. **Workflow Interface & Implementation:** Inspect `CheckoutWorkflowImpl.java`.
2. **Activity Sequence:** Walk through sequential activities (Cart $\rightarrow$ Pricing $\rightarrow$ Inventory $\rightarrow$ Payment $\rightarrow$ Order).
3. **Compensations:** Verify LIFO compensation registrations (`saga.addCompensation(...)`) and smart rollback logic (void vs. refund).
4. **Idempotency Stability:** Verify that operation keys are bound to durable workflow IDs via `PaymentOperationKeys.java`.

---

## 4. Staff-Level Code Walkthrough Checklist

During any deep-dive walkthrough, always verify and highlight:
- [ ] **Transaction Isolation:** Are external network calls (PSPs, HTTP clients) kept strictly outside database transactions?
- [ ] **Idempotency Guarantees:** Does mutating logic enforce idempotency keys via unique database constraints?
- [ ] **Locking & Concurrency:** Are concurrent mutations guarded by `@Version` optimistic locking or `SKIP LOCKED` row-level locks?
- [ ] **Failure Blast Radius:** If downstream calls timeout or fail, does the service fail fast, execute compensation, or degrade gracefully?
- [ ] **Observability:** Are metrics (Micrometer/Prometheus) and structured log contexts (`correlation_id`, `trace_id`) injected?
