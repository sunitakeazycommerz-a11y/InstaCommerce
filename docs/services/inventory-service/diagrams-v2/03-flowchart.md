# Inventory Service - Request Flowcharts

## Stock Reservation Flow

```mermaid
flowchart TD
    A["🌐 POST /inventory/reserve<br/>{storeId, idempotencyKey, items}"]
    B["Validate request body<br/>@Valid ReserveRequest"]
    C{{"Request<br/>valid?"}}
    D["❌ Return 400<br/>Validation errors"]
    E["Check idempotencyKey<br/>findByIdempotencyKey()"]
    F{{"Existing<br/>reservation?"}}
    G["Return existing response<br/>(idempotent replay)"]
    H["Sort items by productId<br/>(deadlock prevention)"]
    I["Begin transaction<br/>READ_COMMITTED isolation"]
    J["For each item:<br/>Lock stock row"]
    K["SELECT * FROM stock_items<br/>WHERE product_id = ? AND store_id = ?<br/>FOR UPDATE"]
    L["Calculate available<br/>= on_hand - reserved"]
    M{{"available >=<br/>requested?"}}
    N["❌ InsufficientStockException<br/>Roll back transaction"]
    O["Return 409 Conflict<br/>{productId, available, requested}"]
    P["Increment reserved<br/>stock.reserved += quantity"]
    Q["Create Reservation<br/>status = PENDING<br/>expiresAt = now + 15min"]
    R["Create ReservationLineItems"]
    S["Save all entities"]
    T["Write to outbox<br/>StockReserved event"]
    U["Check low stock<br/>available <= threshold?"]
    V{{"Low stock?"}}
    W["Write LowStockAlert<br/>to outbox"]
    X["Commit transaction"]
    Y["✅ Return 200 OK<br/>{reservationId, expiresAt, items}"]

    A --> B --> C
    C -->|No| D
    C -->|Yes| E
    E --> F
    F -->|Yes| G
    F -->|No| H
    H --> I --> J --> K --> L --> M
    M -->|No| N --> O
    M -->|Yes| P
    P --> Q --> R --> S --> T --> U --> V
    V -->|Yes| W --> X
    V -->|No| X
    X --> Y

    style K fill:#F5A623,color:#000
    style N fill:#FF6B6B,color:#fff
    style Y fill:#7ED321,color:#000
    style T fill:#9013FE,color:#fff
```

## Stock Confirmation Flow

```mermaid
flowchart TD
    A["🌐 POST /inventory/confirm<br/>{reservationId}"]
    B["Validate reservationId"]
    C["Lock reservation row<br/>findByIdForUpdate()"]
    D{{"Reservation<br/>found?"}}
    E["❌ Return 404<br/>ReservationNotFound"]
    F["Check status<br/>current status"]
    G{{"status ==<br/>PENDING?"}}
    H["❌ Return 409<br/>ReservationStateException<br/>{current status, action: confirm}"]
    I["Check expiration<br/>expiresAt vs now"]
    J{{"Expired?"}}
    K["Call expireReservation()<br/>Release reserved stock"]
    L["❌ Return 410 Gone<br/>ReservationExpired"]
    M["Sort line items by productId"]
    N["For each line item:<br/>Lock stock row"]
    O["Decrement on_hand<br/>stock.onHand -= quantity"]
    P["Decrement reserved<br/>stock.reserved -= quantity"]
    Q["Check low stock threshold"]
    R["Update reservation<br/>status = CONFIRMED"]
    S["Save all entities"]
    T["Write to outbox<br/>StockConfirmed event"]
    U["Commit transaction"]
    V["✅ Return 204 No Content"]

    A --> B --> C --> D
    D -->|No| E
    D -->|Yes| F --> G
    G -->|No| H
    G -->|Yes| I --> J
    J -->|Yes| K --> L
    J -->|No| M --> N --> O --> P --> Q --> R --> S --> T --> U --> V

    style C fill:#F5A623,color:#000
    style E fill:#FF6B6B,color:#fff
    style H fill:#FF6B6B,color:#fff
    style L fill:#FF6B6B,color:#fff
    style T fill:#9013FE,color:#fff
    style V fill:#7ED321,color:#000
```

## Stock Cancellation Flow

```mermaid
flowchart TD
    A["🌐 POST /inventory/cancel<br/>{reservationId}"]
    B["Validate reservationId"]
    C["Lock reservation row<br/>findByIdForUpdate()"]
    D{{"Reservation<br/>found?"}}
    E["❌ Return 404<br/>ReservationNotFound"]
    F["Check if expired PENDING"]
    G{{"PENDING &&<br/>expired?"}}
    H["Call expireReservation()"]
    I["❌ Return 410 Gone<br/>ReservationExpired"]
    J["Check status"]
    K{{"status ==<br/>PENDING?"}}
    L["❌ Return 409<br/>ReservationStateException<br/>{current status, action: cancel}"]
    M["Sort line items by productId"]
    N["For each line item:<br/>Lock stock row"]
    O["Release reserved<br/>stock.reserved -= quantity"]
    P["Update reservation<br/>status = CANCELLED"]
    Q["Save all entities"]
    R["Write to outbox<br/>StockReleased event<br/>reason: CANCELLED"]
    S["Commit transaction"]
    T["✅ Return 204 No Content"]

    A --> B --> C --> D
    D -->|No| E
    D -->|Yes| F --> G
    G -->|Yes| H --> I
    G -->|No| J --> K
    K -->|No| L
    K -->|Yes| M --> N --> O --> P --> Q --> R --> S --> T

    style C fill:#F5A623,color:#000
    style E fill:#FF6B6B,color:#fff
    style I fill:#FF6B6B,color:#fff
    style L fill:#FF6B6B,color:#fff
    style R fill:#9013FE,color:#fff
    style T fill:#7ED321,color:#000
```

## Stock Availability Check Flow

```mermaid
flowchart TD
    A["🌐 POST /inventory/check<br/>{storeId, items}"]
    B["Extract client IP<br/>X-Forwarded-For header"]
    C["Check rate limit<br/>rateLimitService.tryAcquire()"]
    D{{"Rate limit<br/>exceeded?"}}
    E["❌ Return 429<br/>Too Many Requests"]
    F["Extract productIds from items"]
    G["Batch query stock<br/>findByStoreIdAndProductIdIn()"]
    H["For each requested item:"]
    I["Look up stock from results"]
    J{{"Stock<br/>found?"}}
    K["❌ ProductNotFoundException<br/>{productId, storeId}"]
    L["Calculate available<br/>= on_hand - reserved"]
    M["Build StockCheckItemResponse<br/>{productId, available,<br/>requested, sufficient}"]
    N["Aggregate all responses"]
    O["✅ Return 200 OK<br/>StockCheckResponse"]

    A --> B --> C --> D
    D -->|Yes| E
    D -->|No| F --> G --> H --> I --> J
    J -->|No| K
    J -->|Yes| L --> M --> N --> O

    style C fill:#F5A623,color:#000
    style E fill:#FF6B6B,color:#fff
    style K fill:#FF6B6B,color:#fff
    style O fill:#7ED321,color:#000
```

## Manual Stock Adjustment Flow

```mermaid
flowchart TD
    A["🌐 POST /inventory/adjust<br/>{productId, storeId, delta, reason}"]
    B["@PreAuthorize hasRole ADMIN"]
    C{{"Has ADMIN<br/>role?"}}
    D["❌ Return 403 Forbidden"]
    E["Lock stock row<br/>PESSIMISTIC_WRITE"]
    F{{"Stock<br/>found?"}}
    G["❌ Return 404<br/>ProductNotFound"]
    H["Resolve actorId<br/>from SecurityContext"]
    I["Calculate new on_hand<br/>= current + delta"]
    J{{"newOnHand < 0?"}}
    K["❌ Return 400<br/>Resulting stock cannot be negative"]
    L{{"reserved ><br/>newOnHand?"}}
    M["❌ Return 400<br/>Cannot reduce below reserved"]
    N["Update stock.onHand"]
    O["Create StockAdjustmentLog<br/>{delta, reason, referenceId, actorId}"]
    P["Save stock + log"]
    Q["Audit log<br/>STOCK_ADJUSTED"]
    R["Write to outbox<br/>StockAdjusted event"]
    S{{"delta < 0?"}}
    T["Check low stock threshold"]
    U{{"Low stock?"}}
    V["Write LowStockAlert<br/>to outbox"]
    W["Commit transaction"]
    X["✅ Return 200 OK<br/>StockCheckItemResponse"]

    A --> B --> C
    C -->|No| D
    C -->|Yes| E --> F
    F -->|No| G
    F -->|Yes| H --> I --> J
    J -->|Yes| K
    J -->|No| L
    L -->|Yes| M
    L -->|No| N
    N --> O --> P --> Q --> R --> S
    S -->|Yes| T --> U
    S -->|No| W
    U -->|Yes| V --> W
    U -->|No| W
    W --> X

    style B fill:#FF6B6B,color:#fff
    style E fill:#F5A623,color:#000
    style D fill:#FF6B6B,color:#fff
    style K fill:#FF6B6B,color:#fff
    style M fill:#FF6B6B,color:#fff
    style R fill:#9013FE,color:#fff
    style X fill:#7ED321,color:#000
```

## Reservation Expiry Job Flow

```mermaid
flowchart TD
    A["⏰ ReservationExpiryJob<br/>@Scheduled(cron)"]
    B["Acquire ShedLock<br/>15min lock duration"]
    C{{"Lock<br/>acquired?"}}
    D["Skip execution<br/>(another instance running)"]
    E["Query expired reservations<br/>status = PENDING<br/>expiresAt < now"]
    F["For each expired reservation:"]
    G["Lock reservation<br/>findByIdForUpdate()"]
    H["Double-check still PENDING"]
    I{{"Still<br/>PENDING?"}}
    J["Skip (already processed)"]
    K["Sort line items by productId"]
    L["For each line item:<br/>Lock stock row"]
    M["Release reserved<br/>stock.reserved -= quantity"]
    N["Update reservation<br/>status = EXPIRED"]
    O["Save entities"]
    P["Write to outbox<br/>StockReleased event<br/>reason: EXPIRED"]
    Q["Commit transaction"]
    R["Log: Expired reservation {id}"]
    S["Continue to next"]
    T["Release ShedLock"]
    U["✅ Job complete"]

    A --> B --> C
    C -->|No| D
    C -->|Yes| E --> F --> G --> H --> I
    I -->|No| J --> S
    I -->|Yes| K --> L --> M --> N --> O --> P --> Q --> R --> S
    S --> F
    F -->|All done| T --> U

    style A fill:#4A90E2,color:#fff
    style B fill:#F5A623,color:#000
    style P fill:#9013FE,color:#fff
    style U fill:#7ED321,color:#000
```

## Batch Stock Adjustment Flow

```mermaid
flowchart TD
    A["🌐 POST /inventory/adjust-batch<br/>{storeId, items, reason}"]
    B["@PreAuthorize hasRole ADMIN"]
    C["Sort items by productId"]
    D["Check for duplicate productIds"]
    E{{"Duplicates<br/>found?"}}
    F["❌ Return 400<br/>Duplicate productId in batch"]
    G["For each item (sorted):<br/>Lock stock row"]
    H["Validate all items:<br/>- newOnHand >= 0<br/>- reserved <= newOnHand"]
    I{{"All items<br/>valid?"}}
    J["❌ Return 400<br/>Roll back entire batch"]
    K["Apply all changes atomically"]
    L["Save all stocks"]
    M["Create & save adjustment logs"]
    N["Audit log<br/>STOCK_ADJUSTED_BATCH"]
    O["For each item:<br/>Write StockAdjusted event"]
    P["For decreased items:<br/>Check low stock"]
    Q["Commit transaction"]
    R["✅ Return 200 OK<br/>StockCheckResponse (all items)"]

    A --> B --> C --> D --> E
    E -->|Yes| F
    E -->|No| G --> H --> I
    I -->|No| J
    I -->|Yes| K --> L --> M --> N --> O --> P --> Q --> R

    style C fill:#7ED321,color:#000
    style F fill:#FF6B6B,color:#fff
    style J fill:#FF6B6B,color:#fff
    style K fill:#336791,color:#fff
    style R fill:#7ED321,color:#000
```
