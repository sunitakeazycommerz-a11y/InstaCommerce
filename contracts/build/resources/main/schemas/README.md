# Event JSON Schemas

These JSON schemas define the canonical event payloads produced via the outbox + Debezium pipeline.
Do not change field types or remove required fields in v1 schemas; add v2 files for breaking changes.

Event naming:
- orders events: `orders.events`
- payments events: `payments.events`
- inventory events: `inventory.events`
- fulfillment events: `fulfillment.events`
- catalog events: `catalog.events`
- notification DLQ: `notifications.dlq`
