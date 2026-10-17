---
name: code-walkthrough
description: Deep-dive code walkthrough for a specific file or component using LSP symbol navigation and AST analysis.
---

# Code Walkthrough Prompt Template

Perform an in-depth, staff-level code walkthrough of `$TARGET_FILE`.

## Walkthrough Checklist:
1. **LSP Symbol Outline:**
   - Execute `./scripts/lsp-walkthrough outline $TARGET_FILE` to extract structural classes, interfaces, methods, annotations, and routes.
2. **Component Purpose & Architecture:**
   - Explain the architectural role (Controller, Service, Domain Aggregate, Workflow, Consumer, Repository).
   - What business problem does this file solve in InstaCommerce?
3. **Execution & Call Flow:**
   - Map entry points and parameter validations.
   - Trace external dependencies and downstream calls (REST, gRPC, Temporal, Database).
   - Detail transactional boundaries (`@Transactional`), optimistic locking (`@Version`), and error-handling strategies.
4. **State Machine & Events:**
   - If the component mutates state, document valid transitions and invariants.
   - Identify domain events written to `outbox_events` or Kafka topics.
5. **Observability & Operational Invariants:**
   - Detail exposed metrics, health checks, and potential failure modes (e.g. timeouts, pool starvation).
