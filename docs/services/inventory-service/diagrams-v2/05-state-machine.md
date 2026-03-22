# Inventory Service - State Machine Diagrams

## Reservation Status State Machine (Complete Lifecycle)

```mermaid
stateDiagram-v2
    [*] --> PENDING: POST /inventory/reserve<br/>Stock reserved successfully

    PENDING --> CONFIRMED: POST /inventory/confirm<br/>Order payment successful<br/>Stock physically decremented

    PENDING --> CANCELLED: POST /inventory/cancel<br/>User or system cancellation<br/>Reserved stock released

    PENDING --> EXPIRED: Expiry job runs<br/>TTL exceeded (default 15min)<br/>Reserved stock released

    PENDING --> EXPIRED: Confirm/Cancel on expired<br/>Auto-expire before action

    CONFIRMED --> [*]: Terminal state<br/>Stock committed to order

    CANCELLED --> [*]: Terminal state<br/>Stock returned to pool

    EXPIRED --> [*]: Terminal state<br/>Stock returned to pool

    note right of PENDING
        Soft hold on stock
        - reserved count incremented
        - on_hand unchanged
        - expiresAt set (TTL)
        
        Can transition to:
        - CONFIRMED (happy path)
        - CANCELLED (user action)
        - EXPIRED (timeout)
    end note

    note right of CONFIRMED
        Hard commit
        - on_hand decremented
        - reserved decremented
        - Stock physically allocated
        
        No further transitions
    end note

    note right of CANCELLED
        Manual release
        - reserved decremented
        - on_hand unchanged
        - Stock back in available pool
        
        StockReleased event published
        reason: CANCELLED
    end note

    note right of EXPIRED
        Automatic release
        - reserved decremented
        - on_hand unchanged
        - Stock back in available pool
        
        StockReleased event published
        reason: EXPIRED
    end note
```

## Stock Level State Machine

```mermaid
stateDiagram-v2
    [*] --> AVAILABLE: Initial stock<br/>on_hand > 0, reserved = 0

    AVAILABLE --> RESERVED: Reserve request<br/>reserved += qty<br/>available decreases

    RESERVED --> AVAILABLE: Cancel/Expire<br/>reserved -= qty<br/>Stock returns to pool

    RESERVED --> COMMITTED: Confirm reservation<br/>on_hand -= qty<br/>reserved -= qty

    COMMITTED --> AVAILABLE: Replenishment<br/>on_hand += qty<br/>Stock replenished

    AVAILABLE --> OUT_OF_STOCK: Reservations exhaust stock<br/>available = 0

    OUT_OF_STOCK --> AVAILABLE: Replenishment or<br/>cancellation releases stock

    RESERVED --> OUT_OF_STOCK: Further reservations<br/>bring available to 0

    AVAILABLE --> LOW_STOCK: available <= threshold<br/>(default: 10 units)

    LOW_STOCK --> AVAILABLE: Replenishment brings<br/>available > threshold

    LOW_STOCK --> OUT_OF_STOCK: Reservations continue<br/>available = 0

    OUT_OF_STOCK --> LOW_STOCK: Partial replenishment<br/>0 < available <= threshold

    note right of AVAILABLE
        available = on_hand - reserved
        available > threshold
        
        Normal operations
        Can accept new reservations
    end note

    note right of RESERVED
        Some stock held
        reserved > 0
        
        Pending checkout confirmations
        Stock committed but not decremented
    end note

    note right of LOW_STOCK
        0 < available <= threshold
        
        LowStockAlert published
        Triggers replenishment workflow
    end note

    note right of OUT_OF_STOCK
        available = 0
        
        Cannot accept new reservations
        InsufficientStockException
    end note

    note right of COMMITTED
        on_hand physically reduced
        
        Stock allocated to orders
        Will be fulfilled/shipped
    end note
```

## Reservation Request Processing State Machine

```mermaid
stateDiagram-v2
    [*] --> RECEIVED: POST /inventory/reserve<br/>Request received

    RECEIVED --> VALIDATED: Validate request body<br/>storeId, idempotencyKey, items

    VALIDATED --> IDEMPOTENCY_CHECK: Query by idempotencyKey

    IDEMPOTENCY_CHECK --> IDEMPOTENT_RETURN: ✅ Existing reservation<br/>Return previous response

    IDEMPOTENCY_CHECK --> SORTING: No existing reservation

    SORTING --> LOCKING: Sort items by productId<br/>(deadlock prevention)

    LOCKING --> STOCK_LOCKED: Acquire PESSIMISTIC_WRITE<br/>on each stock row

    STOCK_LOCKED --> LOCK_TIMEOUT: ❌ Lock timeout<br/>(5000ms exceeded)

    LOCK_TIMEOUT --> ERROR_503: Return 503<br/>Service Unavailable

    STOCK_LOCKED --> AVAILABILITY_CHECK: All locks acquired

    AVAILABILITY_CHECK --> INSUFFICIENT_STOCK: ❌ available < requested<br/>for any item

    INSUFFICIENT_STOCK --> ERROR_409: Return 409 Conflict<br/>{productId, available, requested}

    AVAILABILITY_CHECK --> RESERVING: ✅ All items available

    RESERVING --> RESERVED_UPDATED: Increment reserved<br/>for each stock row

    RESERVED_UPDATED --> RESERVATION_CREATED: Create Reservation entity<br/>status = PENDING<br/>expiresAt = now + TTL

    RESERVATION_CREATED --> LINE_ITEMS_CREATED: Create ReservationLineItems

    LINE_ITEMS_CREATED --> OUTBOX_WRITTEN: Write StockReserved<br/>to outbox

    OUTBOX_WRITTEN --> LOW_STOCK_CHECK: Check threshold

    LOW_STOCK_CHECK --> LOW_STOCK_ALERT: ✅ available <= threshold<br/>Write LowStockAlert

    LOW_STOCK_CHECK --> COMMITTING: No low stock

    LOW_STOCK_ALERT --> COMMITTING

    COMMITTING --> COMMITTED: Transaction COMMIT

    COMMITTED --> SUCCESS_200: Return 200 OK<br/>{reservationId, expiresAt}

    SUCCESS_200 --> [*]

    IDEMPOTENT_RETURN --> [*]
    ERROR_409 --> [*]
    ERROR_503 --> [*]

    note right of LOCKING
        Lock order by productId
        prevents deadlocks when
        concurrent requests reserve
        overlapping products
    end note

    note right of OUTBOX_WRITTEN
        Same transaction as
        reservation creation
        Guarantees at-least-once
        event delivery via CDC
    end note
```

## Confirmation Processing State Machine

```mermaid
stateDiagram-v2
    [*] --> RECEIVED: POST /inventory/confirm<br/>{reservationId}

    RECEIVED --> RESERVATION_LOCKED: SELECT FOR UPDATE<br/>reservation row

    RESERVATION_LOCKED --> NOT_FOUND: ❌ No reservation

    NOT_FOUND --> ERROR_404: Return 404 Not Found

    RESERVATION_LOCKED --> STATUS_CHECK: Reservation found

    STATUS_CHECK --> INVALID_STATUS: ❌ status != PENDING<br/>(CONFIRMED, CANCELLED, EXPIRED)

    INVALID_STATUS --> ERROR_409: Return 409 Conflict<br/>{currentStatus, action: confirm}

    STATUS_CHECK --> EXPIRY_CHECK: ✅ status == PENDING

    EXPIRY_CHECK --> AUTO_EXPIRE: ❌ expiresAt < now

    AUTO_EXPIRE --> RELEASE_RESERVED: Release reserved stock

    RELEASE_RESERVED --> SET_EXPIRED: Update status = EXPIRED

    SET_EXPIRED --> PUBLISH_RELEASED: Publish StockReleased<br/>reason: EXPIRED

    PUBLISH_RELEASED --> ERROR_410: Return 410 Gone

    EXPIRY_CHECK --> STOCK_LOCKING: ✅ Not expired

    STOCK_LOCKING --> STOCK_LOCKED: Lock stock rows<br/>(sorted by productId)

    STOCK_LOCKED --> DECREMENTING: All locks acquired

    DECREMENTING --> ON_HAND_UPDATED: on_hand -= qty<br/>for each line item

    ON_HAND_UPDATED --> RESERVED_UPDATED: reserved -= qty<br/>for each line item

    RESERVED_UPDATED --> LOW_STOCK_CHECK: Check threshold<br/>for each item

    LOW_STOCK_CHECK --> STATUS_UPDATED: Update status = CONFIRMED

    STATUS_UPDATED --> OUTBOX_WRITTEN: Write StockConfirmed<br/>to outbox

    OUTBOX_WRITTEN --> COMMITTING: Transaction COMMIT

    COMMITTING --> SUCCESS_204: Return 204 No Content

    SUCCESS_204 --> [*]
    ERROR_404 --> [*]
    ERROR_409 --> [*]
    ERROR_410 --> [*]

    note right of DECREMENTING
        Stock physically removed
        - on_hand: physical count
        - reserved: soft holds
        Both decremented together
    end note

    note right of AUTO_EXPIRE
        Race condition handling:
        If TTL expired between
        reserve and confirm,
        auto-expire and return 410
    end note
```

## Expiry Job Processing State Machine

```mermaid
stateDiagram-v2
    [*] --> SCHEDULED: Cron trigger<br/>every 60 seconds

    SCHEDULED --> LOCK_ATTEMPT: Attempt ShedLock<br/>inventory_reservation_expiry

    LOCK_ATTEMPT --> LOCK_ACQUIRED: ✅ Lock obtained<br/>(15min duration)

    LOCK_ATTEMPT --> SKIPPED: ❌ Lock held<br/>by another instance

    SKIPPED --> [*]

    LOCK_ACQUIRED --> QUERY_EXPIRED: SELECT reservations<br/>WHERE status = PENDING<br/>AND expires_at < NOW()

    QUERY_EXPIRED --> NO_EXPIRED: Empty result set

    NO_EXPIRED --> LOCK_RELEASED: Nothing to process

    QUERY_EXPIRED --> PROCESS_BATCH: Expired reservations found

    PROCESS_BATCH --> LOCK_RESERVATION: SELECT FOR UPDATE<br/>single reservation

    LOCK_RESERVATION --> CHECK_STATUS: Reservation locked

    CHECK_STATUS --> ALREADY_PROCESSED: ❌ status != PENDING<br/>(concurrent processing)

    ALREADY_PROCESSED --> NEXT_RESERVATION: Skip to next

    CHECK_STATUS --> PROCESS_ITEMS: ✅ Still PENDING

    PROCESS_ITEMS --> LOCK_STOCKS: Lock stock rows<br/>(sorted by productId)

    LOCK_STOCKS --> RELEASE_RESERVED: reserved -= qty<br/>for each item

    RELEASE_RESERVED --> UPDATE_STATUS: status = EXPIRED

    UPDATE_STATUS --> WRITE_OUTBOX: StockReleased event<br/>reason: EXPIRED

    WRITE_OUTBOX --> COMMIT_TXN: Transaction COMMIT

    COMMIT_TXN --> LOG_EXPIRED: Log: Expired {reservationId}

    LOG_EXPIRED --> NEXT_RESERVATION

    NEXT_RESERVATION --> MORE_CHECK: More reservations?

    MORE_CHECK --> LOCK_RESERVATION: Yes

    MORE_CHECK --> LOCK_RELEASED: No

    LOCK_RELEASED --> JOB_COMPLETE: Release ShedLock

    JOB_COMPLETE --> [*]

    note right of LOCK_ATTEMPT
        ShedLock ensures
        single instance execution
        across K8s replicas
    end note

    note right of CHECK_STATUS
        Double-check pattern:
        Another instance may have
        processed while we were
        waiting for lock
    end note

    note right of COMMIT_TXN
        Each reservation processed
        in its own transaction
        Failure doesn't affect others
    end note
```

## Outbox Event State Machine

```mermaid
stateDiagram-v2
    [*] --> CREATED: INSERT into outbox_events<br/>processed = false

    CREATED --> COMMITTED: Parent transaction COMMIT

    COMMITTED --> CDC_CAPTURED: Debezium reads<br/>WAL/binlog

    CDC_CAPTURED --> TRANSFORMING: Transform to<br/>Kafka message

    TRANSFORMING --> PUBLISHED: Publish to<br/>Kafka topic

    PUBLISHED --> ACKNOWLEDGED: Kafka ACK received

    ACKNOWLEDGED --> CLEANUP_ELIGIBLE: Mark for cleanup

    CLEANUP_ELIGIBLE --> CLEANED: OutboxCleanupJob<br/>deletes old events

    CLEANED --> [*]

    COMMITTED --> CDC_RETRY: ❌ CDC failure<br/>(network, Kafka down)

    CDC_RETRY --> CDC_CAPTURED: Retry on recovery<br/>At-least-once delivery

    note right of CREATED
        Same transaction as
        domain operation
        Atomic guarantee
    end note

    note right of PUBLISHED
        Event topics:
        - inventory.stock.reserved
        - inventory.stock.confirmed
        - inventory.stock.released
        - inventory.stock.adjusted
        - inventory.stock.low-alert
    end note

    note right of CLEANUP_ELIGIBLE
        OutboxCleanupJob runs
        @Scheduled with ShedLock
        Deletes events > 7 days old
    end note
```

## Rate Limiter State Machine

```mermaid
stateDiagram-v2
    [*] --> ACTIVE: Service started<br/>Caffeine cache initialized

    ACTIVE --> CHECK_REQUEST: Request arrives<br/>tryAcquire(clientIp)

    CHECK_REQUEST --> GET_COUNTER: Get count from<br/>Caffeine cache

    GET_COUNTER --> COUNTER_EXISTS: Key exists<br/>within 1-min window

    GET_COUNTER --> COUNTER_NEW: Key not found<br/>or expired

    COUNTER_NEW --> CREATE_COUNTER: Initialize counter = 1<br/>expireAfterWrite = 1min

    CREATE_COUNTER --> ALLOWED: ✅ Request allowed

    COUNTER_EXISTS --> WITHIN_LIMIT: count < limit<br/>(default: 100)

    WITHIN_LIMIT --> INCREMENT: count += 1

    INCREMENT --> ALLOWED

    COUNTER_EXISTS --> EXCEEDED: count >= limit

    EXCEEDED --> DENIED: ❌ Request denied

    ALLOWED --> CONTINUE: Continue to<br/>inventoryService

    DENIED --> RETURN_429: Return 429<br/>Too Many Requests

    CONTINUE --> [*]
    RETURN_429 --> [*]

    note right of GET_COUNTER
        In-memory Caffeine cache
        Per-IP tracking
        1-minute sliding window
    end note

    note right of WITHIN_LIMIT
        Default limit: 100 req/min
        Configurable via
        application.yml
    end note

    note right of DENIED
        Only /inventory/check
        is rate limited
        Admin endpoints exempt
    end note
```
