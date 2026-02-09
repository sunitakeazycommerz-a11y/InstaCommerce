# 03 — Inventory & Cart Services (inventory-service, cart-service)

> **Bounded Contexts**: Stock Management / Shopping Cart  
> **Runtime**: Java 21 + Spring Boot 4.0.x on GKE  
> **Inventory datastore**: Cloud SQL for PostgreSQL 15  
> **Cart datastore**: GCP Memorystore for Redis 7 + Cloud SQL (snapshots)  
> **Ports**: inventory 8083/9083 | cart 8084/9084

---

## 1. Architecture Overview

```
┌──────────────────────────────────────────────────────────────────────────┐
│                        API Gateway (Istio)                               │
└───────────┬──────────────────────────────┬───────────────────────────────┘
            │                              │
   ┌────────▼─────────┐          ┌─────────▼──────────┐
   │  inventory-service│          │   cart-service      │
   │                   │          │                     │
   │ ┌───────────────┐ │          │ ┌────────────────┐  │
   │ │ StockController│ │         │ │ CartController  │  │
   │ │ ReserveCtrl    │ │         │ └───────┬────────┘  │
   │ └───────┬───────┘ │          │         │           │
   │         │         │          │ ┌───────▼────────┐  │
   │ ┌───────▼───────┐ │          │ │  CartService   │  │
   │ │InventoryService│ │         │ └───┬──────┬─────┘  │
   │ │ReservationSvc  │ │         │     │      │        │
   │ └───────┬───────┘ │          │     │      │        │
   │         │         │          │     │      │        │
   │ ┌───────▼───────┐ │          │ ┌───▼──┐ ┌─▼──────┐│
   │ │ PostgreSQL     │ │          │ │Redis │ │Postgres││
   │ │ (inventory_db) │ │          │ │(live)│ │(snap)  ││
   │ └───────────────┘ │          │ └──────┘ └────────┘│
   │         │         │          │                     │
   └─────────┼─────────┘          └──────────┬──────────┘
             │                               │
             │    gRPC / REST calls           │
             │◄──────────────────────────────►│
             │                               │
     ┌───────▼───────┐              ┌────────▼────────┐
     │ Kafka          │              │ pricing-service  │
     │ inventory.events│             │ (compute prices) │
     └───────────────┘              └─────────────────┘
```

---

## 2. INVENTORY SERVICE — Package Structure

```
com.instacommerce.inventory
├── InventoryServiceApplication.java
├── config/
│   ├── SecurityConfig.java
│   ├── SchedulerConfig.java        # @EnableScheduling for reservation expiry
│   └── GrpcServerConfig.java       # gRPC server for internal calls
├── controller/
│   ├── StockController.java        # GET /inventory/check, POST /inventory/adjust
│   └── ReservationController.java  # POST /reserve, /confirm, /cancel
├── grpc/
│   ├── InventoryGrpcService.java   # gRPC endpoint for reserve/confirm/cancel
│   └── proto/
│       └── inventory.proto
├── dto/
│   ├── request/
│   │   ├── StockAdjustRequest.java     # {product_id, store_id, delta, reason}
│   │   ├── StockCheckRequest.java      # {store_id, items[{product_id, qty}]}
│   │   ├── ReserveRequest.java         # {idempotency_key, store_id, items[]}
│   │   ├── ConfirmRequest.java         # {reservation_id}
│   │   └── CancelRequest.java          # {reservation_id}
│   ├── response/
│   │   ├── StockCheckResponse.java     # {items[{product_id, available, on_hand}]}
│   │   ├── ReserveResponse.java        # {reservation_id, expires_at, items[]}
│   │   └── ErrorResponse.java
│   └── mapper/
│       └── InventoryMapper.java
├── domain/
│   ├── model/
│   │   ├── StockItem.java              # (product_id, store_id, on_hand, reserved)
│   │   ├── Reservation.java            # aggregate
│   │   ├── ReservationLineItem.java
│   │   └── StockAdjustmentLog.java     # audit trail
│   └── valueobject/
│       └── StockLevel.java             # enforces on_hand >= 0 invariant
├── service/
│   ├── InventoryService.java           # adjust, check availability
│   ├── ReservationService.java         # reserve, confirm, cancel
│   └── ReservationExpiryJob.java       # @Scheduled — release expired reservations
├── repository/
│   ├── StockItemRepository.java
│   ├── ReservationRepository.java
│   └── StockAdjustmentLogRepository.java
├── exception/
│   ├── InsufficientStockException.java
│   ├── ReservationNotFoundException.java
│   ├── ReservationExpiredException.java
│   └── GlobalExceptionHandler.java
└── infrastructure/
    ├── lock/
    │   └── PessimisticLockHelper.java  # utility for SELECT FOR UPDATE
    └── metrics/
        └── InventoryMetrics.java
```

---

## 3. Inventory Database Schema

### V1__create_stock_items.sql
```sql
CREATE TABLE stock_items (
    id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    product_id  UUID         NOT NULL,
    store_id    VARCHAR(50)  NOT NULL,
    on_hand     INT          NOT NULL DEFAULT 0,
    reserved    INT          NOT NULL DEFAULT 0,
    updated_at  TIMESTAMPTZ  NOT NULL DEFAULT now(),
    version     BIGINT       NOT NULL DEFAULT 0,
    CONSTRAINT uq_stock_product_store UNIQUE (product_id, store_id),
    CONSTRAINT chk_on_hand_non_negative CHECK (on_hand >= 0),
    CONSTRAINT chk_reserved_non_negative CHECK (reserved >= 0),
    CONSTRAINT chk_reserved_le_on_hand CHECK (reserved <= on_hand)
);

CREATE INDEX idx_stock_store    ON stock_items (store_id);
CREATE INDEX idx_stock_product  ON stock_items (product_id);
```

### V2__create_reservations.sql
```sql
CREATE TYPE reservation_status AS ENUM ('PENDING', 'CONFIRMED', 'CANCELLED', 'EXPIRED');

CREATE TABLE reservations (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    idempotency_key VARCHAR(64)       NOT NULL,
    store_id        VARCHAR(50)       NOT NULL,
    status          reservation_status NOT NULL DEFAULT 'PENDING',
    expires_at      TIMESTAMPTZ       NOT NULL,
    created_at      TIMESTAMPTZ       NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ       NOT NULL DEFAULT now(),
    CONSTRAINT uq_reservation_idempotency UNIQUE (idempotency_key)
);

CREATE TABLE reservation_line_items (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    reservation_id  UUID    NOT NULL REFERENCES reservations(id) ON DELETE CASCADE,
    product_id      UUID    NOT NULL,
    quantity        INT     NOT NULL,
    CONSTRAINT chk_qty_positive CHECK (quantity > 0)
);

CREATE INDEX idx_reservations_status  ON reservations (status) WHERE status = 'PENDING';
CREATE INDEX idx_reservations_expiry  ON reservations (expires_at) WHERE status = 'PENDING';
CREATE INDEX idx_res_line_reservation ON reservation_line_items (reservation_id);
```

### V3__create_stock_adjustment_log.sql
```sql
CREATE TABLE stock_adjustment_log (
    id          BIGSERIAL PRIMARY KEY,
    product_id  UUID         NOT NULL,
    store_id    VARCHAR(50)  NOT NULL,
    delta       INT          NOT NULL,
    reason      VARCHAR(100) NOT NULL,   -- MANUAL, RESERVATION, CONFIRM, CANCEL, EXPIRY
    reference_id VARCHAR(255),           -- reservation_id or admin request ID
    actor_id    UUID,
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT now()
);
```

---

## 4. Reservation Logic (Critical — exact implementation)

### Reserve (all-or-nothing, pessimistic locking)
```java
@Service
@RequiredArgsConstructor
public class ReservationService {
    
    private final ReservationRepository reservationRepo;
    private final StockItemRepository stockRepo;
    private final EntityManager em;
    
    private static final Duration RESERVATION_TTL = Duration.ofMinutes(5);

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public ReserveResponse reserve(ReserveRequest request) {
        // 1. Idempotency check
        Optional<Reservation> existing = reservationRepo.findByIdempotencyKey(request.getIdempotencyKey());
        if (existing.isPresent()) {
            return mapToResponse(existing.get());  // return same result, don't re-reserve
        }
        
        // 2. Lock and check each item (ordered by product_id to avoid deadlocks)
        List<ReserveRequest.Item> sortedItems = request.getItems().stream()
            .sorted(Comparator.comparing(ReserveRequest.Item::getProductId))
            .toList();
        
        for (ReserveRequest.Item item : sortedItems) {
            // CRITICAL: SELECT ... FOR UPDATE to lock the row
            StockItem stock = em.createQuery(
                "SELECT s FROM StockItem s WHERE s.productId = :pid AND s.storeId = :sid",
                StockItem.class)
                .setParameter("pid", item.getProductId())
                .setParameter("sid", request.getStoreId())
                .setLockMode(LockModeType.PESSIMISTIC_WRITE)
                .getSingleResult();
            
            int available = stock.getOnHand() - stock.getReserved();
            if (available < item.getQuantity()) {
                throw new InsufficientStockException(item.getProductId(), available, item.getQuantity());
            }
        }
        
        // 3. All items available — apply reservations
        Reservation reservation = Reservation.builder()
            .idempotencyKey(request.getIdempotencyKey())
            .storeId(request.getStoreId())
            .status(ReservationStatus.PENDING)
            .expiresAt(Instant.now().plus(RESERVATION_TTL))
            .build();
        
        List<ReservationLineItem> lineItems = new ArrayList<>();
        for (ReserveRequest.Item item : sortedItems) {
            StockItem stock = stockRepo.findByProductIdAndStoreId(item.getProductId(), request.getStoreId());
            stock.setReserved(stock.getReserved() + item.getQuantity());
            stockRepo.save(stock);
            
            lineItems.add(ReservationLineItem.builder()
                .reservation(reservation)
                .productId(item.getProductId())
                .quantity(item.getQuantity())
                .build());
        }
        
        reservation.setLineItems(lineItems);
        reservationRepo.save(reservation);
        
        return mapToResponse(reservation);
    }
}
```

### Confirm
```java
@Transactional
public void confirm(UUID reservationId) {
    Reservation res = reservationRepo.findById(reservationId)
        .orElseThrow(() -> new ReservationNotFoundException(reservationId));
    
    if (res.getStatus() == ReservationStatus.CONFIRMED) return; // idempotent
    if (res.getStatus() != ReservationStatus.PENDING) {
        throw new IllegalStateException("Cannot confirm reservation in status " + res.getStatus());
    }
    
    // Move reserved → committed (decrease on_hand permanently, decrease reserved)
    for (ReservationLineItem item : res.getLineItems()) {
        StockItem stock = stockRepo.findByProductIdAndStoreId(item.getProductId(), res.getStoreId());
        stock.setOnHand(stock.getOnHand() - item.getQuantity());
        stock.setReserved(stock.getReserved() - item.getQuantity());
        stockRepo.save(stock);
    }
    
    res.setStatus(ReservationStatus.CONFIRMED);
    reservationRepo.save(res);
}
```

### Cancel / Expiry
```java
@Transactional
public void cancel(UUID reservationId) {
    Reservation res = reservationRepo.findById(reservationId)
        .orElseThrow(() -> new ReservationNotFoundException(reservationId));
    
    if (res.getStatus() == ReservationStatus.CANCELLED ||
        res.getStatus() == ReservationStatus.EXPIRED) return; // idempotent
    
    // Release reserved stock
    for (ReservationLineItem item : res.getLineItems()) {
        StockItem stock = stockRepo.findByProductIdAndStoreId(item.getProductId(), res.getStoreId());
        stock.setReserved(stock.getReserved() - item.getQuantity());
        stockRepo.save(stock);
    }
    
    res.setStatus(ReservationStatus.CANCELLED);
    reservationRepo.save(res);
}

// Background job — runs every 30 seconds
@Scheduled(fixedRate = 30_000)
@Transactional
public void expireStaleReservations() {
    List<Reservation> expired = reservationRepo
        .findByStatusAndExpiresAtBefore(ReservationStatus.PENDING, Instant.now());
    
    for (Reservation res : expired) {
        cancel(res.getId()); // reuses cancel logic
        res.setStatus(ReservationStatus.EXPIRED); // override CANCELLED -> EXPIRED
        reservationRepo.save(res);
    }
}
```

---

## 5. Inventory REST & gRPC API

### REST Endpoints
| Method | Path | Auth | Description |
|--------|------|------|-------------|
| POST | /inventory/check | Internal | Check availability |
| POST | /inventory/adjust | ROLE_ADMIN | Adjust stock |
| POST | /inventory/reserve | Internal | Reserve stock (idempotent) |
| POST | /inventory/confirm | Internal | Confirm reservation |
| POST | /inventory/cancel | Internal | Cancel reservation |

### gRPC Proto (inventory.proto)
```protobuf
syntax = "proto3";
package inventory.v1;

option java_package = "com.instacommerce.inventory.grpc";
option java_multiple_files = true;

service InventoryService {
  rpc CheckAvailability(CheckRequest) returns (CheckResponse);
  rpc Reserve(ReserveRequest) returns (ReserveResponse);
  rpc Confirm(ConfirmRequest) returns (ConfirmResponse);
  rpc Cancel(CancelRequest) returns (CancelResponse);
}

message CheckRequest {
  string store_id = 1;
  repeated Item items = 2;
}
message Item {
  string product_id = 1;
  int32 quantity = 2;
}
message CheckResponse {
  repeated ItemAvailability items = 1;
}
message ItemAvailability {
  string product_id = 1;
  int32 available = 2;
  int32 on_hand = 3;
  bool sufficient = 4;
}

message ReserveRequest {
  string idempotency_key = 1;
  string store_id = 2;
  repeated Item items = 3;
}
message ReserveResponse {
  string reservation_id = 1;
  string expires_at = 2;  // ISO 8601
}

message ConfirmRequest { string reservation_id = 1; }
message ConfirmResponse { bool success = 1; }

message CancelRequest { string reservation_id = 1; }
message CancelResponse { bool success = 1; }
```

---

## 6. CART SERVICE — Package Structure

```
com.instacommerce.cart
├── CartServiceApplication.java
├── config/
│   ├── SecurityConfig.java              # JWT validation
│   ├── RedisConfig.java                 # Lettuce client, connection pool
│   └── FeignConfig.java                 # Feign client for Pricing, Inventory
├── controller/
│   └── CartController.java             # /cart, /cart/items, /cart/checkout
├── dto/
│   ├── request/
│   │   ├── AddItemRequest.java          # {product_id, quantity}
│   │   └── CheckoutRequest.java         # {store_id, idempotency_key, coupon_code?}
│   ├── response/
│   │   ├── CartResponse.java            # full cart with pricing
│   │   ├── CartItemResponse.java
│   │   ├── CheckoutResponse.java        # {reservation_id, pricing_breakdown}
│   │   └── ErrorResponse.java
│   └── mapper/
│       └── CartMapper.java
├── domain/
│   ├── model/
│   │   ├── Cart.java                    # userId, Map<productId, CartItem>
│   │   └── CartItem.java                # productId, quantity, addedAt
│   └── valueobject/
│       └── CartKey.java                 # userId-based Redis key
├── service/
│   ├── CartService.java                 # add, remove, get, clear
│   ├── CartPricingService.java          # calls pricing-service for totals
│   └── CartCheckoutService.java         # calls inventory reserve
├── client/
│   ├── PricingClient.java              # Feign client interface
│   ├── InventoryClient.java            # Feign client interface
│   └── CatalogClient.java             # Feign client for product details
├── repository/
│   ├── CartRedisRepository.java         # Redis Hash operations
│   └── CartSnapshotRepository.java      # JPA for Postgres snapshots
├── exception/
│   ├── CartEmptyException.java
│   ├── CartLimitExceededException.java
│   ├── ProductNotFoundException.java
│   └── GlobalExceptionHandler.java
└── infrastructure/
    ├── redis/
    │   └── RedisCartSerializer.java     # JSON serialization for cart
    └── metrics/
        └── CartMetrics.java
```

---

## 7. Cart Redis Data Model

### Key Format
```
cart:{userId}   →  Redis Hash
```

### Hash Structure
```
Field: "{product_id}"
Value: '{"productId":"uuid","quantity":2,"addedAt":"2026-02-06T10:00:00Z"}'
```

### Operations
```java
@Repository
@RequiredArgsConstructor
public class CartRedisRepository {
    
    private final StringRedisTemplate redis;
    private final ObjectMapper objectMapper;
    
    private static final Duration CART_TTL = Duration.ofDays(30);
    
    private String cartKey(UUID userId) {
        return "cart:" + userId;
    }
    
    public void addItem(UUID userId, CartItem item) {
        String key = cartKey(userId);
        redis.opsForHash().put(key, item.getProductId().toString(), serialize(item));
        redis.expire(key, CART_TTL);
    }
    
    public void removeItem(UUID userId, UUID productId) {
        redis.opsForHash().delete(cartKey(userId), productId.toString());
    }
    
    public Map<UUID, CartItem> getCart(UUID userId) {
        Map<Object, Object> entries = redis.opsForHash().entries(cartKey(userId));
        // deserialize each entry to CartItem
    }
    
    public void clear(UUID userId) {
        redis.delete(cartKey(userId));
    }
    
    public long size(UUID userId) {
        return redis.opsForHash().size(cartKey(userId));
    }
}
```

### Cart Snapshot (PostgreSQL, for recovery)
```sql
CREATE TABLE cart_snapshots (
    user_id     UUID PRIMARY KEY,
    cart_json   JSONB NOT NULL,
    updated_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);
```

Snapshot every 5 minutes or on checkout:
```java
@Scheduled(fixedRate = 300_000)
public void snapshotActiveCarts() {
    // iterate recently modified carts from Redis, write to Postgres
}
```

---

## 8. Cart REST API

| Method | Path | Auth | Description |
|--------|------|------|-------------|
| GET | /cart | User | Get current cart with prices |
| POST | /cart/items | User | Add/update item |
| DELETE | /cart/items/{productId} | User | Remove item |
| POST | /cart/checkout | User | Start checkout |
| DELETE | /cart | User | Clear cart |

### GET /cart — Response 200
```json
{
  "userId": "user-uuid",
  "items": [
    {
      "productId": "uuid-1",
      "productName": "Amul Milk 500ml",
      "quantity": 2,
      "unitPriceCents": 3200,
      "lineTotalCents": 6400,
      "available": true,
      "availableQuantity": 15,
      "imageUrl": "https://cdn.insta.local/amul-milk.webp"
    }
  ],
  "subtotalCents": 6400,
  "totalItems": 2,
  "couponCode": null,
  "discountCents": 0,
  "totalCents": 6400,
  "currency": "INR"
}
```

### POST /cart/items — Request
```json
{ "productId": "uuid-1", "quantity": 2 }
```

### POST /cart/checkout — Request
```json
{
  "storeId": "store-mumbai-01",
  "idempotencyKey": "checkout-uuid-unique",
  "couponCode": "FIRST10"
}
```

### POST /cart/checkout — Response 200
```json
{
  "reservationId": "res-uuid",
  "expiresAt": "2026-02-06T10:05:00Z",
  "pricingBreakdown": {
    "subtotalCents": 6400,
    "couponDiscount": { "code": "FIRST10", "discountCents": 640 },
    "totalCents": 5760,
    "currency": "INR"
  }
}
```

### POST /cart/checkout — Response 409 (out of stock)
```json
{
  "code": "INSUFFICIENT_STOCK",
  "message": "Some items are not available",
  "details": [
    { "productId": "uuid-2", "requested": 3, "available": 1 }
  ]
}
```

---

## 9. GCP Cloud Guidelines

### Inventory — Cloud SQL
- Instance: `inventory-db`, db-custom-2-8192
- Connection via Cloud SQL Auth Proxy sidecar
- **Connection pool**: HikariCP, max pool = 20 (to avoid connection starvation under load)
- Enable `pg_stat_statements` for slow query analysis

### Cart — GCP Memorystore (Redis 7)
- Instance: `cart-redis`, tier: Standard (HA with replica)
- Memory: 4 GB (supports ~500K carts of avg 10 items each)
- Connect via private IP from GKE pods (no Auth Proxy needed for Memorystore)
- **Failover**: Standard tier provides automatic failover to replica

### application.yml (inventory-service, prod)
```yaml
spring:
  application:
    name: inventory-service
  datasource:
    url: jdbc:postgresql:///${DB_NAME}?cloudSqlInstance=${CLOUD_SQL_INSTANCE}&socketFactory=com.google.cloud.sql.postgres.SocketFactory
    username: ${DB_USER}
    password: ${sm://inventory-db-password}
    hikari:
      maximum-pool-size: 20
      minimum-idle: 5
      connection-timeout: 3000
  jpa:
    hibernate:
      ddl-auto: validate
    open-in-view: false
  flyway:
    enabled: true

reservation:
  ttl-minutes: 5
  expiry-check-interval-ms: 30000
```

### application.yml (cart-service, prod)
```yaml
spring:
  application:
    name: cart-service
  data:
    redis:
      host: ${REDIS_HOST}        # Memorystore private IP
      port: 6379
      lettuce:
        pool:
          max-active: 50
          max-idle: 20
          min-idle: 5
  datasource:
    url: jdbc:postgresql:///${DB_NAME}?cloudSqlInstance=${CLOUD_SQL_INSTANCE}&socketFactory=com.google.cloud.sql.postgres.SocketFactory
    username: ${DB_USER}
    password: ${sm://cart-db-password}

cart:
  max-items: 50
  max-quantity-per-item: 20
  snapshot-interval-ms: 300000
  ttl-days: 30
```

---

## 10. Concurrency & Safety

### Deadlock Prevention
- Always lock stock_items rows in **sorted order by product_id** to prevent deadlock cycles.
- Use `LockModeType.PESSIMISTIC_WRITE` (SELECT … FOR UPDATE) — never optimistic locking for reservation.

### Invariants (enforced at DB + application layer)
```
∀ stock_item: on_hand ≥ 0
∀ stock_item: reserved ≥ 0
∀ stock_item: reserved ≤ on_hand
∀ stock_item: available = on_hand - reserved ≥ 0
```

### Concurrency Test (mandatory)
```java
@Test
void twoParallelReservationsForLastItem_onlyOneSucceeds() throws Exception {
    // Setup: product P1 at store S1 with on_hand=1, reserved=0
    stockItemRepository.save(StockItem.builder()
        .productId(P1).storeId(S1).onHand(1).reserved(0).build());
    
    ExecutorService executor = Executors.newFixedThreadPool(2);
    CountDownLatch latch = new CountDownLatch(1);
    AtomicInteger successCount = new AtomicInteger(0);
    AtomicInteger failCount = new AtomicInteger(0);
    
    for (int i = 0; i < 2; i++) {
        final String key = "idem-" + i;
        executor.submit(() -> {
            latch.await();
            try {
                reservationService.reserve(new ReserveRequest(key, S1, List.of(new Item(P1, 1))));
                successCount.incrementAndGet();
            } catch (InsufficientStockException e) {
                failCount.incrementAndGet();
            }
        });
    }
    
    latch.countDown();  // fire both simultaneously
    executor.awaitTermination(10, TimeUnit.SECONDS);
    
    assertThat(successCount.get()).isEqualTo(1);
    assertThat(failCount.get()).isEqualTo(1);
    
    StockItem stock = stockItemRepository.findByProductIdAndStoreId(P1, S1);
    assertThat(stock.getOnHand()).isEqualTo(1);
    assertThat(stock.getReserved()).isEqualTo(1);
    // NEGATIVE STOCK NEVER HAPPENS
}
```

---

## 11. Observability

### Metrics
| Metric | Type | Labels |
|--------|------|--------|
| `inventory_reserve_total` | Counter | status={success,insufficient_stock,error} |
| `inventory_reserve_duration_seconds` | Histogram | |
| `inventory_confirm_total` | Counter | |
| `inventory_cancel_total` | Counter | reason={manual,expiry} |
| `inventory_expiry_job_duration_seconds` | Histogram | |
| `inventory_stock_available` | Gauge | store_id, product_id |
| `cart_add_item_total` | Counter | |
| `cart_checkout_total` | Counter | status={success,stock_error,error} |
| `cart_size` | Histogram | | avg items per cart |
| `cart_redis_latency_seconds` | Histogram | operation={get,put,delete} |

### SLOs
| SLO | Target |
|-----|--------|
| Reserve p95 latency | < 100ms |
| Reserve availability | 99.95% |
| Cart GET p95 latency | < 50ms |
| Cart checkout p95 | < 500ms (includes pricing + reserve) |

---

## 12. Error Codes

| HTTP | Code | Service | When |
|------|------|---------|------|
| 400 | VALIDATION_ERROR | Both | Bad input |
| 404 | PRODUCT_NOT_FOUND | Inventory | No stock record |
| 404 | RESERVATION_NOT_FOUND | Inventory | Bad reservation_id |
| 409 | INSUFFICIENT_STOCK | Inventory | Not enough available |
| 409 | RESERVATION_EXPIRED | Inventory | Reservation past TTL |
| 400 | CART_EMPTY | Cart | Checkout with empty cart |
| 400 | CART_LIMIT_EXCEEDED | Cart | > 50 items or > 20 per item |
| 500 | INTERNAL_ERROR | Both | Unexpected |

---

## 13. Testing Strategy

### Unit Tests
- `ReservationService`: reserve success, insufficient stock, idempotency, confirm, cancel, double-cancel
- `CartService`: add/remove, quantity update, max limits, clear

### Integration Tests (Testcontainers)
- PostgreSQL container for inventory: test reservation lifecycle
- Redis container for cart: test add/get/remove/clear
- **Concurrency test**: 10 threads reserving same stock (section 10)

### Contract Tests
- gRPC proto validation between cart-service and inventory-service
- REST response JSON shapes

---

## 14. Helm Values

```yaml
# inventory-service
replicaCount: 2
resources:
  requests: { cpu: 500m, memory: 512Mi }
  limits:   { cpu: 1500m, memory: 1Gi }
hpa:
  enabled: true
  minReplicas: 2
  maxReplicas: 8
  targetCPUUtilization: 70

# cart-service
replicaCount: 2
resources:
  requests: { cpu: 250m, memory: 384Mi }
  limits:   { cpu: 1000m, memory: 768Mi }
hpa:
  enabled: true
  minReplicas: 2
  maxReplicas: 10
  targetCPUUtilization: 65
```

---

## 15. Agent Instructions (CRITICAL)

### MUST DO
1. Use the **exact package structures** in sections 2 and 6.
2. Reservation MUST use `SELECT ... FOR UPDATE` with rows sorted by product_id to prevent deadlocks.
3. Reservation MUST be all-or-nothing: if any item is insufficient, no items are reserved.
4. Idempotency: duplicate reserve with same key returns same result (HTTP 200, not error).
5. Confirm and cancel MUST be idempotent: calling twice has no additional effect.
6. Cart uses **Redis Hash** (not Redis String with full object). Key = `cart:{userId}`, field = `productId`.
7. Cart max: 50 distinct items, 20 max per item. Enforce in service layer.
8. Cart GET calls pricing-service to compute current prices (never cache prices in cart).
9. All money values in **cents (long)**. Currency field always present.
10. Write the concurrency test from section 10 — it is mandatory.

### MUST NOT DO
1. Do NOT use optimistic locking (`@Version`) for reservation — use pessimistic exclusively.
2. Do NOT store computed prices in Redis cart — always fetch fresh from pricing-service on GET.
3. Do NOT skip the expiry scheduler — reservations must auto-expire.
4. Do NOT expose inventory endpoints to external users — internal/admin only.
5. Do NOT use `SERIALIZABLE` isolation level — use `READ_COMMITTED` with explicit row locking.

### DEFAULTS
- Reservation TTL: 5 minutes
- Expiry check interval: 30 seconds
- Cart max items: 50
- Cart max per item: 20
- Cart Redis TTL: 30 days
- Cart snapshot interval: 5 minutes
- Local Redis: `localhost:6379`
- Local Postgres inventory DB: `jdbc:postgresql://localhost:5432/inventory`
- Local Postgres cart DB: `jdbc:postgresql://localhost:5432/cart`

### ESCALATION
Add `// TODO(AGENT):` for:
- Whether to use Temporal timers instead of `@Scheduled` for reservation expiry
- Whether to support multi-store carts (items from different stores)
- Whether to add WebSocket/SSE for real-time stock updates to frontend
