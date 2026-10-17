# Cross-Service Transaction Tracing Prompt Template

Use this prompt to trace a distributed cross-service transaction across multiple polyglot services in InstaCommerce.

---

## Prompt Template

```markdown
Please trace the end-to-end distributed transaction for `{{TRANSACTION_NAME}}` (e.g. Checkout Saga, Payment Settlement, Dark Store Picking & Delivery Dispatch).

Walk through the execution flow step-by-step:

1. **Transaction Entrypoint:**
   - Where is the flow initiated (Mobile BFF, Client App, Webhook, or Scheduled Job)?
   - What headers, correlation IDs, and idempotency keys are created?

2. **Step-by-Step Service Progression:**
   For each participant service:
   - What synchronous API or asynchronous event triggers it?
   - What local database state changes are committed?
   - What event envelope or activity completion is emitted?

3. **Compensation & Error Rollback:**
   - If Step N fails (e.g. Card Declined, Insufficient Stock, Downstream 503), how is rollback handled?
   - Detail the exact compensation steps executed (e.g., Temporal saga compensations or Kafka event compensations).
   - How are partial states or network partitions reconciled?

4. **Visual Execution Diagram:**
   - Render a comprehensive Mermaid `sequenceDiagram` mapping each service, database, outbox table, Kafka topic, and external partner (e.g. Stripe, Maps).

5. **Key Verification Checkpoints:**
   - Point to exact code files and methods handling each transition using clickable links.
```
