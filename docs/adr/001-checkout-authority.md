# ADR-001: Checkout Authority Consolidation

## Status

Accepted

## Date

2026-03-13

## Context

The InstaCommerce platform has two independent Temporal checkout workflows:

1. **checkout-orchestrator-service** (`CHECKOUT_ORCHESTRATOR_TASK_QUEUE`): The intended
   primary checkout path. Validates the cart server-side, calculates prices via
   pricing-service, applies coupons, generates workflow-stable payment idempotency
   keys, and implements sophisticated 3-tier payment compensation (refund if captured,
   try refund then void if capture attempted, void if only authorized).

2. **order-service** (`CHECKOUT_TASK_QUEUE`): A legacy checkout path that accepts
   client-supplied prices without server-side validation, does not call pricing-service
   or apply coupons, uses simple string-concatenation idempotency keys, and has simpler
   compensation logic (void only).

Having two checkout authorities creates multiple risks:

- **Price manipulation**: The order-service path trusts client-supplied totals.
- **Coupon bypass**: The order-service path never calls `redeemCoupon()`.
- **Idempotency collisions**: Different key formats between the two paths.
- **Operational confusion**: Two saga implementations to monitor and debug.
- **Inconsistent compensation**: Different failure recovery behaviors.

This was identified as issue C2-P0-04 in the Iteration 3 Principal Engineering Review.

## Decision

**`checkout-orchestrator-service` is the sole checkout authority.**

The order-service checkout path is deprecated and disabled by default via the
`order.checkout.direct-saga-enabled` property (set to `false`). The order-service
`POST /checkout` endpoint returns HTTP 410 GONE when the legacy path is disabled.

The Temporal worker, workflow client, and worker factory beans in order-service are
conditionally created only when `direct-saga-enabled=true`, ensuring no wasted
resources when the legacy path is off.

## Consequences

### Positive

- Single source of truth for checkout logic, pricing, and payment orchestration.
- Server-side price validation eliminates price manipulation risk.
- Consistent, workflow-stable payment idempotency keys.
- Reduced operational surface area and simpler incident response.

### Negative

- Any clients still pointing at `POST /orders/checkout` must migrate to the
  checkout-orchestrator endpoint. The HTTP 410 GONE response makes this explicit.
- The legacy code remains in the codebase (behind the feature flag) until we confirm
  zero residual traffic, at which point it can be fully removed.

### Migration Path

1. Set `ORDER_CHECKOUT_DIRECT_SAGA_ENABLED=false` in all environments (Wave 22).
2. Monitor `checkout.legacy_path.invocations` metric for any residual traffic.
3. After 30 days of zero traffic, remove the legacy checkout code entirely.
