# Order Service - Sequence Diagrams

## Complete Order Creation Sequence (via Checkout Orchestrator)

```mermaid
sequenceDiagram
    participant Customer as Customer
    participant BFF as Mobile BFF
    participant Checkout as Checkout Orchestrator<br/>(Temporal)
    participant Inventory as Inventory Service
    participant Payment as Payment Service
    participant Order as Order Service
    participant Pricing as Pricing Service
    participant DB as PostgreSQL
    participant Kafka as Kafka

    Customer->>BFF: 1. POST /checkout<br/>{cartId, paymentMethod, address}
    BFF->>BFF: 2. Validate JWT
    BFF->>Checkout: 3. Start CheckoutWorkflow<br/>{userId, cartId, ...}

    Note over Checkout: Temporal durable workflow

    Checkout->>Inventory: 4. Reserve stock<br/>for cart items
    Inventory-->>Checkout: 5. ReservationId

    Checkout->>Payment: 6. Process payment<br/>{amount, method}
    Payment-->>Checkout: 7. PaymentId

    Checkout->>Order: 8. POST /workflow/orders<br/>CreateOrderCommand
    Note over Order: Include reservationId,<br/>paymentId, quoteId

    Order->>Order: 9. Check idempotency key

    alt Duplicate order
        Order-->>Checkout: 9a. Return existing orderId
    else New order
        Order->>Pricing: 10. Validate quote<br/>(quoteId, token, amounts)
        Pricing-->>Order: 11. Quote valid ✓

        Order->>DB: 12. BEGIN transaction
        Order->>DB: 13. INSERT orders
        Order->>DB: 14. INSERT order_items
        Order->>DB: 15. INSERT order_status_history
        Order->>DB: 16. INSERT outbox_events<br/>(OrderCreated)
        Order->>DB: 17. COMMIT

        Order-->>Checkout: 18. 201 Created<br/>{orderId}
    end

    Checkout->>Order: 19. Update status → PLACED
    Order->>DB: 20. Update order status
    Order->>DB: 21. INSERT status history
    Order->>DB: 22. INSERT outbox<br/>(OrderPlaced, OrderStatusChanged)

    Note over DB,Kafka: Debezium CDC

    DB->>Kafka: 23. OrderCreated event
    DB->>Kafka: 24. OrderPlaced event

    Checkout-->>BFF: 25. Workflow complete<br/>{orderId, status}
    BFF-->>Customer: 26. 200 OK<br/>{orderId, confirmationNumber}
```

## Order Status Update Sequence (Fulfillment Event)

```mermaid
sequenceDiagram
    participant Fulfillment as Fulfillment Service
    participant Kafka as Kafka
    participant Consumer as OrderConsumer
    participant Order as OrderService
    participant StateMachine as OrderStateMachine
    participant DB as PostgreSQL
    participant Audit as AuditLogService

    Fulfillment->>Kafka: 1. Publish FulfillmentPacked<br/>{orderId, status: PACKED}

    Kafka->>Consumer: 2. Consume event
    Consumer->>Order: 3. advanceLifecycleFromFulfillment<br/>(orderId, PACKED, "fulfillment", note)

    Order->>DB: 4. SELECT * FROM orders<br/>WHERE id = ?

    alt Order in terminal state
        Order->>Order: 5a. Log: Ignoring event<br/>for CANCELLED/FAILED/DELIVERED
    else Order is PENDING
        Order->>Order: 5b. Throw InvalidOrderStateException
    else Order can progress
        Order->>Order: 5c. Calculate progression path<br/>[PACKING, PACKED]

        loop For each status in path
            Order->>StateMachine: 6. validate(current, next)
            StateMachine-->>Order: 7. Valid ✓
            Order->>DB: 8. UPDATE orders SET status = ?
            Order->>DB: 9. INSERT order_status_history
            Order->>Audit: 10. Log status change
            Order->>DB: 11. INSERT outbox_events<br/>(OrderStatusChanged)
        end

        Note over DB,Kafka: Debezium CDC

        DB->>Kafka: 12. OrderStatusChanged<br/>{from: PLACED, to: PACKING}
        DB->>Kafka: 13. OrderStatusChanged<br/>{from: PACKING, to: PACKED}
    end
```

## Order Cancellation Sequence (User-Initiated)

```mermaid
sequenceDiagram
    participant Customer as Customer
    participant BFF as Mobile BFF
    participant Order as Order Service
    participant DB as PostgreSQL
    participant Audit as AuditLogService
    participant Kafka as Kafka
    participant Payment as Payment Service<br/>(Consumer)

    Customer->>BFF: 1. POST /orders/{id}/cancel<br/>{reason: "Changed my mind"}
    BFF->>BFF: 2. Validate JWT, extract userId

    BFF->>Order: 3. POST /orders/{id}/cancel

    Order->>DB: 4. SELECT * FROM orders<br/>WHERE id = ? AND user_id = ?

    alt Order not found
        Order-->>BFF: 5a. 404 Not Found
        BFF-->>Customer: Order not found
    else Already cancelled
        Order-->>BFF: 5b. 204 No Content<br/>(idempotent)
        BFF-->>Customer: Order already cancelled
    else Status not in [PENDING, PLACED]
        Order-->>BFF: 5c. 409 Conflict<br/>Cannot cancel after packing
        BFF-->>Customer: Cannot cancel order
    else Can cancel
        Order->>Order: 6. Validate state transition
        Order->>DB: 7. UPDATE orders<br/>SET status = 'CANCELLED',<br/>cancellation_reason = ?
        Order->>DB: 8. INSERT order_status_history
        Order->>Audit: 9. Log ORDER_CANCELLED
        Order->>DB: 10. INSERT outbox_events<br/>(OrderCancelled)
        Order->>DB: 11. COMMIT

        Order-->>BFF: 12. 204 No Content
        BFF-->>Customer: 13. Order cancelled

        Note over DB,Kafka: Debezium CDC

        DB->>Kafka: 14. OrderCancelled<br/>{orderId, paymentId, totalCents, reason}

        Kafka->>Payment: 15. Consume OrderCancelled
        Payment->>Payment: 16. Initiate refund<br/>via PSP
    end
```

## Get Order Details Sequence

```mermaid
sequenceDiagram
    participant Client as Client
    participant Order as Order Service
    participant Auth as JwtAuthFilter
    participant Controller as OrderController
    participant Service as OrderService
    participant DB as PostgreSQL

    Client->>Order: 1. GET /orders/{id}<br/>Authorization: Bearer JWT

    Order->>Auth: 2. Validate JWT
    Auth->>Auth: 3. Extract principal (userId)
    Auth->>Auth: 4. Check ROLE_ADMIN authority
    Auth-->>Order: 5. SecurityContext set

    Order->>Controller: 6. Route to getOrder()
    Controller->>Controller: 7. Extract principal, isAdmin

    Controller->>Service: 8. getOrder(orderId, userId, isAdmin)

    alt Admin user
        Service->>DB: 9a. SELECT * FROM orders<br/>WHERE id = ?
    else Regular user
        Service->>DB: 9b. SELECT * FROM orders<br/>WHERE id = ? AND user_id = ?
    end

    alt Order not found
        Service-->>Controller: 10a. Throw OrderNotFoundException
        Controller-->>Client: 404 Not Found
    else Order found
        DB-->>Service: 10b. Order entity

        Service->>DB: 11. SELECT * FROM order_status_history<br/>WHERE order_id = ?<br/>ORDER BY created_at ASC
        DB-->>Service: 12. Status history list

        Service->>Service: 13. OrderMapper.toResponse<br/>(order, history)

        Service-->>Controller: 14. OrderResponse DTO
        Controller-->>Client: 15. 200 OK<br/>{order details, items, timeline}
    end
```

## Order Placed Event Sequence (Full Payload)

```mermaid
sequenceDiagram
    participant Checkout as Checkout Orchestrator
    participant Order as Order Service
    participant DB as PostgreSQL
    participant Debezium as Debezium CDC
    participant Kafka as Kafka
    participant Fulfillment as Fulfillment Service
    participant Notification as Notification Service
    participant Analytics as Analytics Service

    Checkout->>Order: 1. updateOrderStatus<br/>(orderId, PLACED, "workflow", "Payment confirmed")

    Order->>DB: 2. UPDATE orders SET status = 'PLACED'
    Order->>DB: 3. INSERT order_status_history

    Order->>Order: 4. Build OrderPlaced payload:<br/>{orderId, userId, storeId,<br/>paymentId, items[], totalCents,<br/>deliveryAddress, placedAt}

    Order->>DB: 5. INSERT outbox_events<br/>(OrderPlaced, OrderStatusChanged)
    Order->>DB: 6. COMMIT

    Order-->>Checkout: 7. Status updated

    Note over DB,Debezium: CDC captures outbox rows

    Debezium->>Kafka: 8. Publish to order.events

    par Parallel consumers
        Kafka->>Fulfillment: 9a. OrderPlaced
        Note over Fulfillment: Create fulfillment task<br/>Assign to store<br/>Start picking
    and
        Kafka->>Notification: 9b. OrderPlaced
        Note over Notification: Send SMS confirmation<br/>Push notification
    and
        Kafka->>Analytics: 9c. OrderPlaced
        Note over Analytics: Update dashboards<br/>Track conversion
    end
```

## Admin Status Override Sequence

```mermaid
sequenceDiagram
    participant Admin as Admin User
    participant Gateway as Admin Gateway
    participant Order as Order Service
    participant Auth as JwtAuthFilter
    participant Service as OrderService
    participant StateMachine as OrderStateMachine
    participant DB as PostgreSQL
    participant Audit as AuditLogService
    participant Kafka as Kafka

    Admin->>Gateway: 1. PUT /admin/orders/{id}/status<br/>{status: "CANCELLED", reason: "Fraud detected"}
    Gateway->>Gateway: 2. Validate admin JWT
    Gateway->>Order: 3. Forward request

    Order->>Auth: 4. Validate JWT
    Auth->>Auth: 5. Check ROLE_ADMIN
    Auth-->>Order: 6. Authorized

    Order->>Service: 7. cancelOrder(orderId, reason, "admin:userId", actorId)

    Service->>DB: 8. SELECT * FROM orders WHERE id = ?
    DB-->>Service: 9. Order entity

    Service->>StateMachine: 10. validate(current, CANCELLED)
    StateMachine-->>Service: 11. Valid ✓

    Service->>DB: 12. UPDATE orders<br/>SET status = 'CANCELLED',<br/>cancellation_reason = ?
    Service->>DB: 13. INSERT order_status_history
    Service->>Audit: 14. Log ORDER_CANCELLED<br/>(actorId = admin user)
    Service->>DB: 15. INSERT outbox_events

    Service->>DB: 16. COMMIT

    Service-->>Order: 17. Success
    Order-->>Gateway: 18. 204 No Content
    Gateway-->>Admin: 19. Order cancelled

    Note over DB,Kafka: Debezium CDC

    DB->>Kafka: 20. OrderCancelled event
```

## Concurrent Order Update Sequence (Optimistic Locking)

```mermaid
sequenceDiagram
    participant Worker1 as Fulfillment Worker 1
    participant Worker2 as Fulfillment Worker 2
    participant Order as Order Service
    participant DB as PostgreSQL

    Note over Worker1,Worker2: Same order, concurrent updates

    Worker1->>Order: 1. Update status → PACKING
    Worker2->>Order: 2. Update status → PACKED

    par Parallel execution
        Order->>DB: 3a. SELECT * FROM orders<br/>WHERE id = ?<br/>(version = 5)
    and
        Order->>DB: 3b. SELECT * FROM orders<br/>WHERE id = ?<br/>(version = 5)
    end

    Order->>Order: 4a. Validate PLACED → PACKING ✓
    Order->>Order: 4b. Validate PLACED → PACKED ✗<br/>(must go through PACKING)

    Order->>DB: 5a. UPDATE orders<br/>SET status = 'PACKING', version = 6<br/>WHERE id = ? AND version = 5
    DB-->>Order: 6a. 1 row updated ✓

    Order->>DB: 5b. UPDATE orders<br/>SET status = 'PACKED', version = 6<br/>WHERE id = ? AND version = 5
    DB-->>Order: 6b. 0 rows updated<br/>(version mismatch)

    Order-->>Worker1: 7a. Success
    Order-->>Worker2: 7b. OptimisticLockException<br/>(retry with fresh data)

    Note over Worker2: Worker 2 retries,<br/>sees PACKING status,<br/>updates to PACKED
```
