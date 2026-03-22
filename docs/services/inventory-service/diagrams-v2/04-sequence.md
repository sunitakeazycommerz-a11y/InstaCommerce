# Inventory Service - Sequence Diagrams

## Checkout Reservation Sequence (Complete Flow)

```mermaid
sequenceDiagram
    participant Client as Checkout Orchestrator<br/>(Temporal)
    participant Gateway as API Gateway
    participant Inventory as Inventory Service
    participant DB as PostgreSQL
    participant Outbox as Outbox Table
    participant Debezium as Debezium CDC
    participant Kafka as Kafka

    Client->>Gateway: 1. POST /inventory/reserve<br/>{storeId, idempotencyKey, items}
    Gateway->>Inventory: 2. Forward request

    Inventory->>Inventory: 3. Validate request

    Inventory->>DB: 4. SELECT * FROM reservations<br/>WHERE idempotency_key = ?
    DB-->>Inventory: 5. No existing reservation

    Inventory->>Inventory: 6. Sort items by productId<br/>(deadlock prevention)

    loop For each item (sorted order)
        Inventory->>DB: 7. SELECT * FROM stock_items<br/>WHERE product_id = ? AND store_id = ?<br/>FOR UPDATE
        DB-->>Inventory: 8. Stock row (locked)
        Inventory->>Inventory: 9. Check available >= requested
    end

    alt Insufficient stock
        Inventory-->>Client: 409 Conflict<br/>{productId, available, requested}
    end

    loop For each item
        Inventory->>DB: 10. UPDATE stock_items<br/>SET reserved = reserved + ?
    end

    Inventory->>DB: 11. INSERT INTO reservations<br/>{id, idempotency_key, store_id,<br/>status=PENDING, expires_at}

    Inventory->>DB: 12. INSERT INTO reservation_line_items<br/>(per item)

    Inventory->>Outbox: 13. INSERT INTO outbox_events<br/>{event_type=StockReserved, payload}

    Inventory->>Inventory: 14. Check low stock threshold
    opt Available <= threshold
        Inventory->>Outbox: 15. INSERT INTO outbox_events<br/>{event_type=LowStockAlert}
    end

    Inventory->>DB: 16. COMMIT transaction
    DB-->>Inventory: 17. Transaction committed

    Inventory-->>Client: 18. 200 OK<br/>{reservationId, expiresAt, items}

    Note over Outbox,Kafka: Async CDC processing

    Debezium->>Outbox: 19. Read outbox changes (CDC)
    Debezium->>Kafka: 20. Publish StockReserved event
    Debezium->>Outbox: 21. Mark event as processed
```

## Stock Confirmation Sequence

```mermaid
sequenceDiagram
    participant Orchestrator as Checkout Orchestrator
    participant Inventory as Inventory Service
    participant DB as PostgreSQL
    participant Outbox as Outbox Table
    participant Kafka as Kafka

    Orchestrator->>Inventory: 1. POST /inventory/confirm<br/>{reservationId}

    Inventory->>DB: 2. SELECT * FROM reservations<br/>WHERE id = ? FOR UPDATE
    DB-->>Inventory: 3. Reservation (locked)<br/>status=PENDING

    alt Reservation not found
        Inventory-->>Orchestrator: 404 Not Found
    end

    Inventory->>Inventory: 4. Check status == PENDING

    alt Invalid status
        Inventory-->>Orchestrator: 409 Conflict<br/>{currentStatus, action: confirm}
    end

    Inventory->>Inventory: 5. Check not expired<br/>expiresAt > now

    alt Expired
        Inventory->>DB: 6. Release reserved stock
        Inventory->>DB: 7. UPDATE status = EXPIRED
        Inventory-->>Orchestrator: 410 Gone<br/>ReservationExpired
    end

    Inventory->>Inventory: 8. Sort line items by productId

    loop For each line item (sorted)
        Inventory->>DB: 9. SELECT * FROM stock_items<br/>FOR UPDATE
        DB-->>Inventory: 10. Stock row (locked)
        Inventory->>DB: 11. UPDATE stock_items<br/>SET on_hand = on_hand - qty,<br/>reserved = reserved - qty
    end

    Inventory->>Inventory: 12. Check low stock for each item

    Inventory->>DB: 13. UPDATE reservations<br/>SET status = CONFIRMED

    Inventory->>Outbox: 14. INSERT INTO outbox_events<br/>{event_type=StockConfirmed}

    Inventory->>DB: 15. COMMIT

    Inventory-->>Orchestrator: 16. 204 No Content

    Note over Inventory,Kafka: Stock physically decremented<br/>Reserved count released<br/>Order can proceed to fulfillment

    Kafka->>Kafka: 17. StockConfirmed event published<br/>via Debezium CDC
```

## Stock Cancellation Sequence

```mermaid
sequenceDiagram
    participant Orchestrator as Checkout Orchestrator
    participant Inventory as Inventory Service
    participant DB as PostgreSQL
    participant Outbox as Outbox Table
    participant Kafka as Kafka

    Note over Orchestrator: Payment failed or<br/>user abandoned cart

    Orchestrator->>Inventory: 1. POST /inventory/cancel<br/>{reservationId}

    Inventory->>DB: 2. SELECT * FROM reservations<br/>WHERE id = ? FOR UPDATE
    DB-->>Inventory: 3. Reservation (locked)

    alt Reservation not found
        Inventory-->>Orchestrator: 404 Not Found
    end

    Inventory->>Inventory: 4. Check PENDING && expired

    alt Already expired
        Inventory->>DB: 5. Release and set EXPIRED
        Inventory-->>Orchestrator: 410 Gone
    end

    Inventory->>Inventory: 6. Check status == PENDING

    alt Invalid status
        Inventory-->>Orchestrator: 409 Conflict<br/>{currentStatus, action: cancel}
    end

    Inventory->>Inventory: 7. Sort line items by productId

    loop For each line item (sorted)
        Inventory->>DB: 8. SELECT * FROM stock_items<br/>FOR UPDATE
        DB-->>Inventory: 9. Stock row (locked)
        Inventory->>DB: 10. UPDATE stock_items<br/>SET reserved = reserved - qty
    end

    Inventory->>DB: 11. UPDATE reservations<br/>SET status = CANCELLED

    Inventory->>Outbox: 12. INSERT INTO outbox_events<br/>{event_type=StockReleased,<br/>reason: CANCELLED}

    Inventory->>DB: 13. COMMIT

    Inventory-->>Orchestrator: 14. 204 No Content

    Note over Inventory: Stock returned to available pool<br/>Can be reserved by other orders

    Kafka->>Kafka: 15. StockReleased event published
```

## Stock Availability Check Sequence

```mermaid
sequenceDiagram
    participant MobileBFF as Mobile BFF
    participant Inventory as Inventory Service
    participant RateLimiter as RateLimitService
    participant DB as PostgreSQL

    MobileBFF->>Inventory: 1. POST /inventory/check<br/>{storeId, items: [{productId, qty}]}

    Inventory->>Inventory: 2. Extract client IP<br/>from X-Forwarded-For

    Inventory->>RateLimiter: 3. tryAcquire(clientIp)
    RateLimiter->>RateLimiter: 4. Check Caffeine cache<br/>requests in 1-min window

    alt Rate limit exceeded
        RateLimiter-->>Inventory: false
        Inventory-->>MobileBFF: 429 Too Many Requests
    end

    RateLimiter-->>Inventory: 5. true (allowed)

    Inventory->>Inventory: 6. Extract productIds from items

    Inventory->>DB: 7. SELECT * FROM stock_items<br/>WHERE store_id = ?<br/>AND product_id IN (?, ?, ?)
    Note over Inventory,DB: Single batch query<br/>No locking (read-only)

    DB-->>Inventory: 8. Stock rows

    loop For each requested item
        Inventory->>Inventory: 9. Find stock in results
        alt Product not found
            Inventory-->>MobileBFF: 404 Not Found<br/>{productId, storeId}
        end
        Inventory->>Inventory: 10. Calculate available<br/>= on_hand - reserved
        Inventory->>Inventory: 11. Build response item<br/>{productId, available,<br/>requested, sufficient}
    end

    Inventory-->>MobileBFF: 12. 200 OK<br/>{items: [{productId, available, sufficient}]}
```

## Manual Stock Adjustment Sequence

```mermaid
sequenceDiagram
    participant Admin as Admin Gateway
    participant Inventory as Inventory Service
    participant Auth as SecurityContext
    participant DB as PostgreSQL
    participant Outbox as Outbox Table
    participant Audit as AuditLogService

    Admin->>Inventory: 1. POST /inventory/adjust<br/>{productId, storeId, delta, reason}
    Note over Admin,Inventory: JWT with ROLE_ADMIN

    Inventory->>Auth: 2. @PreAuthorize hasRole ADMIN
    Auth-->>Inventory: 3. Authorization passed

    Inventory->>DB: 4. SELECT * FROM stock_items<br/>WHERE product_id = ? AND store_id = ?<br/>FOR UPDATE (5000ms timeout)

    alt Product not found
        Inventory-->>Admin: 404 Not Found
    end

    DB-->>Inventory: 5. Stock row (locked)

    Inventory->>Auth: 6. Get principal (user_id)
    Auth-->>Inventory: 7. actorId = user_123

    Inventory->>Inventory: 8. Calculate newOnHand<br/>= onHand + delta

    alt newOnHand < 0
        Inventory-->>Admin: 400 Bad Request<br/>Resulting stock cannot be negative
    end

    alt reserved > newOnHand
        Inventory-->>Admin: 400 Bad Request<br/>Cannot reduce below reserved
    end

    Inventory->>DB: 9. UPDATE stock_items<br/>SET on_hand = ?

    Inventory->>DB: 10. INSERT INTO stock_adjustment_logs<br/>{productId, storeId, delta,<br/>reason, referenceId, actorId}

    Inventory->>Audit: 11. log(STOCK_ADJUSTED, details)
    Audit->>DB: 12. INSERT INTO audit_logs

    Inventory->>Outbox: 13. INSERT INTO outbox_events<br/>{event_type=StockAdjusted}

    opt delta < 0
        Inventory->>Inventory: 14. Check low stock threshold
        opt available <= threshold
            Inventory->>Outbox: 15. INSERT LowStockAlert
        end
    end

    Inventory->>DB: 16. COMMIT

    Inventory-->>Admin: 17. 200 OK<br/>{productId, onHand, reserved, available}
```

## Reservation Expiry Job Sequence

```mermaid
sequenceDiagram
    participant Scheduler as Spring Scheduler
    participant Job as ReservationExpiryJob
    participant ShedLock as ShedLock
    participant DB as PostgreSQL
    participant Outbox as Outbox Table

    Note over Scheduler: Runs every minute<br/>@Scheduled(fixedRate = 60000)

    Scheduler->>Job: 1. Trigger run()

    Job->>ShedLock: 2. Attempt lock acquisition<br/>inventory_reservation_expiry
    ShedLock->>DB: 3. INSERT/UPDATE shedlock<br/>WHERE locked_until < now

    alt Lock not acquired
        DB-->>ShedLock: Lock held by another instance
        ShedLock-->>Job: Lock failed
        Job-->>Scheduler: Skip execution
    end

    ShedLock-->>Job: 4. Lock acquired (15min duration)

    Job->>DB: 5. SELECT * FROM reservations<br/>WHERE status = 'PENDING'<br/>AND expires_at < NOW()

    DB-->>Job: 6. Expired reservations list

    loop For each expired reservation
        Job->>DB: 7. SELECT * FROM reservations<br/>WHERE id = ? FOR UPDATE
        DB-->>Job: 8. Reservation (locked)

        Job->>Job: 9. Double-check still PENDING
        alt Already processed
            Note over Job: Skip (concurrent processing)
        end

        Job->>Job: 10. Sort line items by productId

        loop For each line item
            Job->>DB: 11. SELECT stock FOR UPDATE
            Job->>DB: 12. UPDATE stock_items<br/>SET reserved = reserved - qty
        end

        Job->>DB: 13. UPDATE reservations<br/>SET status = 'EXPIRED'

        Job->>Outbox: 14. INSERT StockReleased event<br/>reason: EXPIRED

        Job->>DB: 15. COMMIT
    end

    Job->>ShedLock: 16. Release lock
    ShedLock->>DB: 17. UPDATE locked_until = NOW()

    Job-->>Scheduler: 18. Job complete
```

## Replenishment Trigger Sequence

```mermaid
sequenceDiagram
    participant Inventory as Inventory Service
    participant Outbox as Outbox Table
    participant Debezium as Debezium CDC
    participant Kafka as Kafka
    participant Warehouse as Warehouse Service
    participant WMS as Warehouse Management System

    Note over Inventory: Stock drops below threshold<br/>during reserve or adjust

    Inventory->>Inventory: 1. Calculate available<br/>= on_hand - reserved

    Inventory->>Inventory: 2. Check threshold<br/>available <= lowStockThreshold

    alt Low stock detected
        Inventory->>Outbox: 3. INSERT INTO outbox_events<br/>{event_type: LowStockAlert,<br/>payload: {productId, warehouseId,<br/>currentQuantity, threshold}}

        Inventory->>Inventory: 4. COMMIT (with main transaction)

        Debezium->>Outbox: 5. CDC captures outbox insert

        Debezium->>Kafka: 6. Publish to<br/>inventory.stock.low-alert

        Kafka->>Warehouse: 7. Consume LowStockAlert

        Warehouse->>Warehouse: 8. Determine replenishment qty<br/>based on demand forecast

        Warehouse->>WMS: 9. Create transfer order<br/>{fromWarehouse, toDarkstore,<br/>productId, qty}

        WMS-->>Warehouse: 10. Transfer order created

        Note over Warehouse: Physical transfer initiated<br/>from central warehouse to darkstore

        Warehouse->>Inventory: 11. POST /inventory/adjust<br/>{productId, storeId, delta: +qty,<br/>reason: REPLENISHMENT,<br/>referenceId: transfer_order_id}

        Inventory->>Inventory: 12. Process adjustment<br/>(as documented above)

        Inventory-->>Warehouse: 13. 200 OK
    end
```
