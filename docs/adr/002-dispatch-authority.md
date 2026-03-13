# ADR-002: Dispatch Authority Consolidation

## Status

Accepted

## Date

2026-03-13

## Context

The InstaCommerce platform has two independent rider assignment paths:

1. **fulfillment-service inline dispatch** (`DeliveryService.assignRider(PickTask)`): Triggered
   synchronously inside `PickService.publishPacked()`. Uses the fulfillment database's simple
   rider table with round-robin selection by least-recent dispatch.

2. **rider-fleet-service event-driven dispatch** (`FulfillmentEventConsumer` ->
   `RiderAssignmentService.assignRider()`): Triggered by consuming the `OrderPacked` Kafka
   event. Uses the rider-fleet database's rich data model with geo-proximity Haversine selection,
   rating-based scoring, and a unique constraint on `(order_id)` to prevent duplicates.

Both paths fire for the same order, potentially assigning two different riders from two
different databases with no cross-service coordination. Additional problems:

- **Double dispatch**: Two riders can be assigned to the same order.
- **Schema mismatch**: `storeId` is `String` in fulfillment but `UUID` in rider-fleet.
- **Missing geolocation**: `OrderPacked` event lacks `pickupLat`/`pickupLng` fields that
  rider-fleet requires for geo-proximity assignment.
- **Inconsistent selection**: Fulfillment uses round-robin; rider-fleet uses proximity + rating.

This was identified as issue C5-P0-10 in the Iteration 3 Principal Engineering Review.

## Decision

**`rider-fleet-service` is the sole dispatch authority.**

The fulfillment-service inline dispatch path is deprecated and disabled by default via the
`fulfillment.dispatch.inline-assignment-enabled` property (set to `false`).

## Consequences

### Positive

- Single source of truth for rider assignment with geo-proximity and rating-based selection.
- Eliminates duplicate rider assignment risk.
- Clears the path for dispatch-optimizer-service integration.
- Reduced operational surface area for dispatch debugging.

### Negative

- Until store location data is populated in `OrderPacked` events, rider-fleet-service
  skips assignment for events with missing coordinates.
- The deprecated code remains behind the feature flag until confirmed safe to remove.

### Migration Path

1. Set `FULFILLMENT_DISPATCH_INLINE_ASSIGNMENT_ENABLED=false` in all environments (Wave 23).
2. Monitor `fulfillment.dispatch.inline_assignment.invoked` metric for residual traffic.
3. Add store location data to `OrderPacked` event payload (Wave 24).
4. After 30 days of zero inline dispatch invocations, remove the deprecated code.
