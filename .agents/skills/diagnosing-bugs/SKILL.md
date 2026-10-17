---
name: diagnosing-bugs
description: Disciplined 5-phase bug diagnosis loop (reproduce, hypothesize, instrument, fix, regression-test) preventing guesswork.
---

# Diagnosing Bugs Skill: Disciplined Troubleshooting Loop

Use this skill when diagnosing tricky production bugs, flaky tests, race conditions, memory leaks, or performance regressions across the InstaCommerce platform.

---

## 1. The 5-Phase Diagnosis Loop

Do not guess or apply random edits. Follow the gated 5-phase loop:

```
  ┌──────────────────────────────────────────────────────────────┐
  │ Phase 1: Build Minimal Failing Feedback Loop (Red Test)      │
  ├──────────────────────────────────────────────────────────────┤
  │ Phase 2: Formulate Falsifiable Hypotheses                    │
  ├──────────────────────────────────────────────────────────────┤
  │ Phase 3: Instrument & Trace (LSP, Metrics, Structured Logs)  │
  ├──────────────────────────────────────────────────────────────┤
  │ Phase 4: Apply Minimal Surgical Fix (Green Test)             │
  ├──────────────────────────────────────────────────────────────┤
  │ Phase 5: Regression Guard & Blast Radius Check               │
  └──────────────────────────────────────────────────────────────┘
```

---

## 2. Phase-by-Phase Execution

### Phase 1: Minimal Failing Feedback Loop
- **Rule**: Never touch production code until you have a reproducible test or script that fails consistently on `main`.
- In Java: Write a `@Test` method in the corresponding test class reproducing the failure.
- In Go: Write a table-driven test with the exact failure payload.
- In Python: Write a targeted pytest function.
- Verify the test fails with the exact error observed in production.

### Phase 2: Formulate Falsifiable Hypotheses
- List 2–3 concrete hypotheses for why the failure occurs:
  - *Hypothesis A*: Concurrent inventory reservations without row locks allow over-allocation.
  - *Hypothesis B*: Temporal workflow history replay throws nondeterminism error due to unseeded `UUID.randomUUID()` in workflow definition.
  - *Hypothesis C*: Kafka consumer offset commit fails on slow consumer processing timeout.

### Phase 3: Instrument & Trace
- Inspect the call hierarchy and symbol definitions using `./scripts/lsp-walkthrough outline <file>`.
- Add temporary structured log statements with `correlation_id` or attach a debugger.
- Inspect Grafana dashboards and Prometheus metric buckets to check if the error correlates with latency or resource saturation.

### Phase 4: Surgical Fix
- Make the smallest possible code change that turns the Phase 1 test **GREEN**.
- Verify no existing tests were broken in the module:
  ```bash
  # Java
  ./gradlew :services:<service-name>:test
  # Go
  cd services/<service-name> && go test -race ./...
  # Python
  cd services/<service-name> && pytest -v
  ```

### Phase 5: Regression Guard & Blast Radius Check
- Ensure the new test is checked in to prevent regressions.
- Trace downstream impact: Does this fix change event serialization, REST response shapes, or database schema?
- Remove temporary debugging instrumentation before committing.

