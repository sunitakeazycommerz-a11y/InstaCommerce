# Code Walkthrough Prompt Template

Use this prompt to perform an in-depth, staff-level code walkthrough of a specific class, function, or component in InstaCommerce.

---

## Prompt Template

```markdown
Please conduct a thorough, staff-level code walkthrough of `{{FILE_PATH}}`.

Context & Objectives:
1. **Structural Outline:**
   - Execute `./scripts/lsp-walkthrough outline {{FILE_PATH}}` to map all classes, methods, annotations, and routes.
2. **Architectural Role:**
   - What bounded context does this file belong to?
   - Is it an API controller, application service, aggregate root, JPA repository, Kafka consumer, or workflow activity?
3. **Core Business Invariants & State Mutations:**
   - What business invariants are enforced?
   - How are race conditions, concurrent updates, and idempotency handled?
4. **Network & Persistence Boundaries:**
   - Are database transactions (@Transactional / db.Begin) cleanly decoupled from external I/O?
   - Does it write to an outbox table or produce directly to Kafka?
5. **Observability & Error Handling:**
   - What metrics, logging, and error codes are emitted?
   - What is the blast radius if an unhandled exception occurs?

Please provide your walkthrough using:
- Clear Mermaid sequence/flow diagrams where state transitions or network calls occur.
- Clickable markdown links to exact line ranges (e.g., `[OrderStateMachine.java](file://...)`).
- A concluding Staff-Level Assessment table highlighting tradeoffs, invariants, and rollback posture.
```
