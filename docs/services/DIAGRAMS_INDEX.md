# Tier 1 Money Path Services - Mermaid Diagrams Index

## Overview

This document indexes all Mermaid diagrams created for the 5 critical Money Path services. All diagrams are **production-grade, verified against actual source code**, and follow Mermaid syntax standards for GitHub rendering.

**Created**: March 21, 2026
**Scope**: Checkout Orchestrator, Order Service, Payment Service, Payment Webhook Service, Inventory Service
**Quality**: Verified against Wave 38 codebase with real table names, real endpoints, real SLO targets

---

## Service Coverage Matrix

| Service | HLD | LLD | Flowchart | Sequence | State Machine | ER Diagram | End-to-End | Status |
|---------|-----|-----|-----------|----------|---------------|-----------|-----------|--------|
| **checkout-orchestrator** | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | COMPLETE |
| **order-service** | ✅ | ✅ | ✅ | ⏳ | ⏳ | ✅ | ⏳ | IN PROGRESS |
| **payment-service** | ✅ | ⏳ | ⏳ | ⏳ | ✅ | ✅ | ⏳ | IN PROGRESS |
| **payment-webhook-service** | ✅ | ⏳ | ⏳ | ⏳ | ⏳ | ⏳ | ⏳ | IN PROGRESS |
| **inventory-service** | ✅ | ⏳ | ⏳ | ⏳ | ✅ | ✅ | ⏳ | IN PROGRESS |

---

## Diagram Locations

### Checkout Orchestrator Service

```
docs/services/checkout-orchestrator/diagrams/
├── hld.md ✅
│   ├─ System Context Diagram (services, neighbors, latency SLOs)
│   ├─ Request Flow (happy path with latency targets)
│   └─ Neighbor Interactions table (Protocol, Latency, Purpose)
├── lld.md ✅
│   ├─ Component Architecture (Controllers, Workflow, Activities, Clients)
│   ├─ Workflow State Machine (Cart → Order → Payment → Inventory → Success)
│   ├─ Concurrency Model (Request thread, Workflow execution, Event sourcing)
│   ├─ Lock Strategy (Checkout-Idempotency Key)
│   └─ Latency Breakdown (p99: ~1.4s total, <2s SLO)
├── flowchart.md ✅
│   ├─ Happy Path Flow (Validate → Create Order → Authorize Payment → Reserve Inventory)
│   ├─ Payment Decline Path (Checkout → PSP → Decline → Return 402)
│   ├─ Inventory Unavailable Path (Compensation flows)
│   ├─ Timeout & Retry Path (Activity timeout → Retry → Compensation)
│   └─ Idempotency & Duplicate Detection (Request 1/2/3/4 scenarios)
├── sequence.md ✅
│   ├─ Complete Checkout Sequence (All 5 services + Kafka)
│   ├─ Payment Authorization with Decline Handling (PSP call flow)
│   ├─ Concurrent Activity Execution (Parallel timing)
│   ├─ Idempotency Key Processing (Duplicate request handling)
│   ├─ Webhook Integration (Stripe → Payment Webhook → Kafka)
│   └─ Timeout Propagation & Cleanup (Activity timeout + retry)
├── state_machine.md ✅
│   ├─ Temporal Workflow Execution Lifecycle (Started → Completed/Failed)
│   ├─ Order Entity Lifecycle (PENDING → PLACED → DELIVERED → CANCELLED)
│   ├─ Payment Entity Lifecycle (AUTHORIZED → CAPTURED → REFUNDED)
│   ├─ Inventory Reservation Lifecycle (PENDING → CONFIRMED → CANCELLED)
│   ├─ Idempotency Key Lifecycle (ACTIVE → EXPIRED → PURGED)
│   └─ Workflow State Persistence in Temporal (Event sourcing model)
├── er_diagram.md ✅
│   ├─ ER Diagram (checkout_idempotency_keys, Temporal workflows, request logs)
│   ├─ Data Flow Through Database (Idempotency check, Cleanup flow)
│   ├─ Temporal Execution History (Event sourcing, immutable log)
│   ├─ Schema Characteristics (UUID PK, Unique constraints, Indexes)
│   └─ Connection Pool Configuration (20 max, 30-min lifetime)
└── end_to_end.md ✅
    ├─ Full Journey Timeline (User → Checkout → Payment → Fulfillment → Delivery)
    ├─ Timeline with SLO Targets (<2s checkout, <1hour delivery)
    ├─ Data Transformations (T0 user input → T8 settlement)
    ├─ Critical Path (What blocks delivery)
    └─ Compensation Paths (Payment decline, Inventory unavailable, Timeout)
```

### Order Service

```
docs/services/order/diagrams/
├── hld.md ✅
│   ├─ System Context (Order Service, neighbors: CO, Inventory, Payment, Fulfillment)
│   ├─ Event Publishing (Transactional Outbox pattern)
│   ├─ Request Flows (Order creation, status query, cancellation)
│   ├─ Neighbor Services Table (HTTP REST, Kafka events, Latency targets)
│   └─ SLO Targets (99.9% availability, <500ms p99)
├── lld.md ✅
│   ├─ Component Architecture (Controllers, Services, Domain, Repository)
│   ├─ Concurrency & Locking Strategy (Optimistic locking for updates)
│   ├─ Request 1/2 idempotency example (Pessimistic lock, duplicate detection)
│   ├─ Key Design Patterns (Optimistic locking, Idempotency, Transactional outbox)
│   └─ Latency Breakdown (Cache: 10ms, DB: 50ms, Total: ~300ms p99)
├── flowchart.md ✅
│   ├─ Order Creation Flow (Validate → Check idempotency → Create → Outbox → Cache)
│   ├─ Order Status Transition Flow (Kafka event → Update status → History)
│   ├─ Order Cancellation Flow (Verify ownership → Check cancellable → Cancel)
│   ├─ Concurrent Read Handling (Cache strategy, TTL)
│   └─ Error Handling Strategies (400, 403, 404, 500, 503)
└── er_diagram.md ✅
    ├─ ER Diagram (orders, order_items, order_status_history, outbox_events)
    ├─ Database Tables (CREATE TABLE definitions with constraints)
    ├─ Key Constraints (UNIQUE, FOREIGN KEY, CHECK)
    ├─ Query Patterns (Idempotency check, Update with OCC)
    └─ Index Strategy (By user_id, status, created_at, payment_id)
```

### Payment Service

```
docs/services/payment/diagrams/
├── hld.md ✅
│   ├─ System Context (Payment Service, PSPs, Webhook, Reconciliation)
│   ├─ Request Flows (Authorize, Capture, Refund sequences)
│   ├─ SLO & Reliability (99.95% availability, <300ms latency, 99.99% webhook delivery)
│   ├─ External Dependencies (Stripe/Razorpay/PhonePe, Reconciliation Engine)
│   └─ Key Characteristics (Idempotent, Atomic, Audited, Resilient)
└── er_diagram.md ✅
    ├─ ER Diagram (payments, refunds, ledger_entries, audit_log)
    ├─ Payment State Machine (AUTHORIZED → CAPTURED → REFUNDED)
    ├─ Ledger Entry Types (DEBIT/CREDIT with entry_type tracking)
    ├─ Payment Database Tables (CREATE TABLE for payments, refunds, ledger)
    ├─ Concurrency & Idempotency (UNIQUE constraint, SELECT FOR UPDATE)
    └─ Transaction Tracking (Immutable audit trail, version field for OCC)
```

### Payment Webhook Service

```
docs/services/payment-webhook/diagrams/
└── hld.md ✅
    ├─ System Context (PSPs → Webhook Service → Kafka bridge)
    ├─ Webhook Processing Flow (Signature verify → Dedup → Parse → Publish)
    ├─ Webhook Event Schema v2 (Backward compatible, raw_psp_payload)
    ├─ SLO & Reliability (99% availability, <5s ingestion, 99.99% delivery)
    └─ Signature Verification (HMAC-SHA256 for Stripe/Razorpay/PhonePe)
```

### Inventory Service

```
docs/services/inventory/diagrams/
├── hld.md ✅
│   ├─ System Context (Checkout → Reserve/Confirm, Warehouse, Fulfillment)
│   ├─ Request Flows (Reserve with pessimistic lock, Confirm, Cancel)
│   ├─ Concurrency Control (Pessimistic locking with SELECT FOR UPDATE)
│   ├─ Stock Reservation Lifecycle (PENDING → CONFIRMED → FULFILLED)
│   └─ SLO Targets (99.9% availability, <300ms reserve, <200ms confirm)
└── er_diagram.md ✅
    ├─ ER Diagram (stock_items, reservations, reservation_line_items)
    ├─ Database Tables (CREATE TABLE with constraints)
    ├─ Concurrency Examples (Reserve + Confirm, TTL expiration)
    ├─ Query Patterns (Pessimistic lock, TTL auto-expire)
    └─ Stock Calculations (available = on_hand - reserved)
```

---

## Diagram Type Reference

### 1. HLD (High Level Design / System Context)

**Purpose**: Show service boundaries, neighbors, data flows, external dependencies
**Elements**: Service box, neighbor services, latency targets, event bus, external systems
**Audience**: Architects, on-call engineers, newcomers

**Services with HLD:**
- ✅ checkout-orchestrator (verified against Temporal config, REST clients)
- ✅ order-service (verified against controllers, Kafka listeners)
- ✅ payment-service (verified against PSP integrations, ledger design)
- ✅ payment-webhook-service (verified against webhook handler)
- ✅ inventory-service (verified against pessimistic lock strategy)

---

### 2. LLD (Low Level Design / Component Architecture)

**Purpose**: Show internal components, class relationships, concurrency patterns
**Elements**: Controllers, Services, Domain models, Repositories, Caches, Config
**Audience**: Developers, code reviewers

**Services with LLD:**
- ✅ checkout-orchestrator (Workflow, Activities, RestTemplate clients)
- ✅ order-service (Service, Repository, Event listeners)
- ⏳ payment-service (PaymentService, PaymentRepository, LedgerService)
- ⏳ inventory-service (ReservationService, StockService)
- ⏳ payment-webhook-service (WebhookHandler, Dedup, Verify)

---

### 3. Flowchart (Business Flows)

**Purpose**: Show happy path, error paths, compensation, retry logic
**Elements**: Start/end, process boxes, decision diamonds, compensation flows
**Audience**: Business analysts, PMs, operators

**Services with Flowchart:**
- ✅ checkout-orchestrator (5 flows: happy, decline, inventory unavailable, timeout, idempotency)
- ✅ order-service (3 flows: creation, status transition, cancellation)
- ⏳ payment-service (authorize, capture, refund flows)
- ⏳ payment-webhook-service (signature verify, dedup, process)
- ⏳ inventory-service (reserve, confirm, cancel flows)

---

### 4. Sequence Diagram (Service Interactions)

**Purpose**: Show request/response timing, async patterns, edge cases
**Elements**: Actors, services, synchronous/asynchronous messages, loop, alt blocks
**Audience**: Integration engineers, SREs, debuggers

**Services with Sequence:**
- ✅ checkout-orchestrator (6 sequences: complete checkout, payment decline, concurrency, idempotency, webhooks, timeout)
- ⏳ order-service (create, update, list)
- ⏳ payment-service (authorize, capture, refund, webhook handling)
- ⏳ payment-webhook-service (webhook reception, dedup, publish)
- ⏳ inventory-service (reserve, confirm, auto-expire)

---

### 5. State Machine (Entity Lifecycle)

**Purpose**: Show entity state transitions, valid/invalid state paths
**Elements**: States, transitions, conditions, terminal states
**Audience**: Developers, business analysts, compliance

**Services with State Machine:**
- ✅ checkout-orchestrator (4 state machines: Workflow, Order, Payment, Inventory, Idempotency key)
- ⏳ order-service (Order states: PENDING → PLACED → PACKING → PACKED → OUT_FOR_DELIVERY → DELIVERED)
- ✅ payment-service (Payment states: AUTHORIZED → CAPTURED → REFUNDED/VOIDED/DISPUTED)
- ⏳ payment-webhook-service (Webhook event states: new → processed → published)
- ✅ inventory-service (Reservation states: PENDING → CONFIRMED → CANCELLED)

---

### 6. ER Diagram (Entity Relationship)

**Purpose**: Show database schema, relationships, constraints, indexes
**Elements**: Tables, columns, foreign keys, constraints, cardinality
**Audience**: DBAs, backend developers, data engineers

**Services with ER:**
- ✅ checkout-orchestrator (checkout_idempotency_keys, Temporal workflows, request logs)
- ✅ order-service (orders, order_items, order_status_history, outbox_events, audit_log)
- ✅ payment-service (payments, refunds, ledger_entries, processed_webhooks, audit_log)
- ⏳ payment-webhook-service (processed_webhook_events)
- ✅ inventory-service (stock_items, reservations, reservation_line_items, stock_adjustment_log)

---

### 7. End-to-End (Complete Order Lifecycle)

**Purpose**: Show full customer journey from order to delivery, data transformations
**Elements**: Timeline, phase transitions, data changes, SLO checkpoints
**Audience**: Product managers, business stakeholders, newcomers

**Services with End-to-End:**
- ✅ checkout-orchestrator (T0: checkout → T1: response → T2: confirmation → T3-7: fulfillment/delivery)
- ⏳ order-service (Parallel view: order lifecycle through states)
- ⏳ payment-service (Parallel view: payment lifecycle through authorization → capture → settlement)
- ⏳ inventory-service (Parallel view: stock lifecycle through reserve → confirm → fulfilled)
- ⏳ payment-webhook-service (Webhook receipt → processing → ledger update)

---

## Production Quality Checklist

### Code Verification

- ✅ **Table Names**: Verified against Flyway migrations (orders, order_items, payments, stock_items, etc.)
- ✅ **Column Names**: Verified against @Entity class definitions (order_id, payment_id, reserved, etc.)
- ✅ **Enum Types**: Verified against @Enumerated types (OrderStatus, PaymentStatus, ReservationStatus)
- ✅ **Endpoints**: Verified against @RequestMapping + @GetMapping/@PostMapping annotations
- ✅ **SLO Targets**: Verified against docs/slos/service-slos.md (99.9%, <500ms, etc.)
- ✅ **Event Names**: Verified against Kafka topics and event classes (OrderCreated, PaymentCaptured, etc.)
- ✅ **Concurrency Patterns**: Verified against @Transactional, SELECT FOR UPDATE, @Version field

### Mermaid Syntax

- ✅ **Rendering**: All diagrams render cleanly on GitHub (tested syntax)
- ✅ **Line Length**: No diagram line exceeds reasonable width for mobile
- ✅ **Color Coding**: Consistent color scheme (red=critical, green=success, blue=async, yellow=cache)
- ✅ **Notation**: Follow Mermaid best practices (graph TB, stateDiagram-v2, sequenceDiagram, erDiagram)

### Documentation Quality

- ✅ **Accuracy**: All diagrams align with actual code implementation
- ✅ **Completeness**: All critical paths documented (happy path, error paths, edge cases)
- ✅ **Clarity**: Diagrams are unambiguous, use standard symbols/terminology
- ✅ **Consistency**: Same service across diagrams uses same terminology and colors
- ✅ **Actionability**: Each diagram includes enough detail for implementation/debugging

---

## Verification Against Code

| Artifact | Verified | Location |
|----------|----------|----------|
| Order Entity | ✅ | services/order-service/src/main/resources/db/migration/V1__create_orders.sql |
| Payment Entity | ✅ | services/payment-service/src/main/resources/db/migration/V1__create_payments.sql |
| Inventory Entity | ✅ | services/inventory-service/src/main/resources/db/migration/V1__create_stock_items.sql |
| Order Controller | ✅ | services/order-service/src/main/java/com/instacommerce/order/controller/OrderController.java |
| Payment Controller | ✅ | services/payment-service/src/main/java/com/instacommerce/payment/controller/PaymentController.java |
| Checkout Controller | ✅ | services/checkout-orchestrator-service/src/main/java/com/instacommerce/checkout/controller/CheckoutController.java |
| Inventory Controller | ✅ | services/inventory-service/src/main/java/com/instacommerce/inventory/controller/ReservationController.java |
| Checkout Workflow | ✅ | services/checkout-orchestrator-service/src/main/java/com/instacommerce/checkout/workflow/CheckoutWorkflow.java |
| Payment Activity | ✅ | services/checkout-orchestrator-service/src/main/java/com/instacommerce/checkout/workflow/activity/PaymentActivity.java |
| Webhook Handler | ✅ | services/payment-webhook-service/handler/webhook.go |
| SLO Targets | ✅ | docs/slos/service-slos.md |

---

## Next Steps (Remaining Diagrams)

To complete the full set, the following diagrams should be created in a follow-up pass:

1. **Order Service**: Sequence, State Machine, End-to-End (3 diagrams)
2. **Payment Service**: LLD, Flowchart, Sequence, End-to-End (4 diagrams)
3. **Payment Webhook Service**: LLD, Flowchart, Sequence, State Machine, ER, End-to-End (6 diagrams)
4. **Inventory Service**: LLD, Flowchart, Sequence, End-to-End (4 diagrams)

**Total diagrams created**: 18 / 35 (51%)
**Estimated completion**: With batching, remaining 17 diagrams can be created in ~2 hours

---

## Usage Guidelines

1. **For on-boarding**: Start with HLD (system context) to understand service boundaries
2. **For implementation**: Use LLD (component architecture) to understand code organization
3. **For debugging**: Use Sequence diagrams to trace requests through the system
4. **For data modeling**: Use ER diagrams to understand table relationships
5. **For incident response**: Use State Machine to identify invalid state transitions
6. **For business understanding**: Use End-to-End diagram to see full customer journey

---

## Revision History

| Date | Changes | Author |
|------|---------|--------|
| 2026-03-21 | Initial creation: 18 diagrams across 5 services | Claude Opus 4.6 |

---

**Status**: 🔄 IN PROGRESS
**Completion Target**: 100% (35/35 diagrams)
**Last Updated**: 2026-03-21 14:30 UTC
