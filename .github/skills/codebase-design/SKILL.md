---
name: codebase-design
description: John Ousterhout deep module discipline, interface minimalism, seam discovery, information hiding, and Design-It-Twice methodology.
---

# Codebase Design Skill: Deep Modules & Interface Leverage

Use this skill when designing new interfaces, refactoring complex packages, extracting reusable modules, or identifying testable seams across Java, Go, and Python services in InstaCommerce.

---

## 1. Core Principles (Ousterhout Discipline)

Software design is the art of managing complexity through **deep modules**:

```
Shallow Module (Anti-pattern)           Deep Module (Ideal)
┌─────────────────────────────────┐      ┌───────────────┐
│     Wide / Leaky Interface      │      │ Narrow API    │
├─────────────────────────────────┤      ├───────────────┤
│    Thin implementation logic    │      │               │
└─────────────────────────────────┘      │ Substantial   │
Complexity is pushed to caller!          │ hidden logic, │
                                         │ caching,      │
                                         │ concurrency,  │
                                         │ resilience    │
                                         └───────────────┘
                                         High leverage!
```

1. **Deep Modules**: A module provides powerful functionality through a simple, minimal interface.
2. **Information Hiding**: Internal data structures, caching mechanisms, retry policies, and database queries must not leak through the interface.
3. **Define Clear Seams**:
   - A *seam* is a boundary where module behavior can be isolated, intercepted, or replaced without editing internal code (e.g., interfaces for payment gateways, storage drivers, Kafka event publishers).
4. **Locality of Change**:
   - Changes to a single concept should affect only a single module. Avoid shotgun surgery.
5. **Design It Twice**:
   - For any non-trivial module, conceptualize at least two distinct interface designs before writing code. Compare them for API surface area, depth, and seam placement.

---

## 2. Dependency Classification (Testing Across Seams)

When designing module interfaces, classify every external dependency:

| Dependency Type | Example in InstaCommerce | Isolation Strategy | Testing Approach |
|---|---|---|---|
| **In-Process** | Domain calculation, pricing rules | Pure function / record | Fast unit tests |
| **Local-Substitutable** | Redis cache, PostgreSQL DB | Interface / Repository seam | In-memory or Testcontainers |
| **Remote-Owned** | Internal microservice (e.g., `payment-service`) | HTTP/gRPC Client interface | WireMock / Contract tests |
| **True-External** | Stripe, OSRM, Twilio, Bank API | Gateway Adapter with circuit breaker | Recorded stubs / Mock gateway |

---

## 3. Design-It-Twice Protocol

When creating a new service capability or core abstraction:

1. **Approach A (Consumer-Centric)**:
   - Design the API from the perspective of the caller. What is the single method call that accomplishes the entire operation?
2. **Approach B (Composition-Centric)**:
   - Design the API by decomposing responsibilities into pipeline stages or strategy providers.
3. **Evaluation Matrix**:
   - **Interface Width**: Which interface has fewer methods and parameters?
   - **Leakage**: Does the caller need to know about HTTP headers, SQL transactions, or retry backoffs?
   - **Depth**: Which design maximizes hidden implementation per line of interface code?

---

## 4. Code Smells to Eliminate

- **Pass-Through Methods**: Methods that do nothing except forward arguments to another class with the same signature.
- **Leaky Temporal Coupling**: Methods that must be called in a strict order (`init()`, `configure()`, `execute()`) instead of a single cohesive call.
- **Config Bloat / Flag Arguments**: Boolean arguments (`doAsync`, `skipValidation`) that split method behavior in half. Create two distinct, deep methods instead.

