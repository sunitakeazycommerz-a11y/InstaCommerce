# Service Deep-Dive Walkthrough Prompt Template

Use this prompt to walk through an entire microservice in the monorepo from end to end.

---

## Prompt Template

```markdown
Please conduct an end-to-end, multi-layered code walkthrough of the service `{{SERVICE_NAME}}` (located at `{{SERVICE_PATH}}`).

Structure the walkthrough into the following sequential layers:

1. **Business Context & Domain Responsibilities:**
   - What core Q-Commerce problem does this service solve?
   - What SLAs, throughput targets, and availability requirements apply?

2. **API & Ingress Layer:**
   - Outline all incoming controllers / HTTP handlers using `./scripts/lsp-walkthrough outline <controller_file>`.
   - Document exposed endpoints, request/response DTOs, and RBAC / JWT security claims.

3. **Domain Core & State Invariants:**
   - Walk through the primary aggregate roots, models, and domain state machines.
   - Explain lifecycle transitions and guards against illegal transitions.

4. **Persistence & Data Tier:**
   - Inspect Flyway migrations (`db/migration/`) or database schemas.
   - Analyze table constraints, indexes, connection pool configs, and Transactional Outbox tables.

5. **Asynchronous Messaging & Distributed Sagas:**
   - List all Kafka topics produced and consumed (referencing `TopicNames.java`).
   - If applicable, explain Temporal workflow activities or Debezium CDC ingestion streams.

6. **Failure Modes, Resilience & Observability:**
   - How are timeouts, retries, and circuit breakers configured?
   - What health probes (`/actuator/health`, `/health/ready`), Prometheus metrics, and traces are exported?
   - Provide the specific local test commands (Gradle / Go test / Pytest) to validate this service.
```
