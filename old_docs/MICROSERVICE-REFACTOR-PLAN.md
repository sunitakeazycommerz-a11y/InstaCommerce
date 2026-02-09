# Instacommerce — Microservice Decomposition & Refactor Plan

**Date:** 2026-02-07  
**Target Scale:** 20M+ users, 500K+ daily orders, 10-minute delivery SLA  
**Benchmarks:** Zepto (4-service to 40+ split), Blinkit (domain-driven decomposition), Instacart (marketplace model), DoorDash (microservice per domain), Swiggy Instamart (hybrid)

---

## Part 1: Current Architecture Analysis

### Current Service Map (7 Services)

```
┌─────────────┐     ┌──────────────┐     ┌─────────────────┐
│  identity    │────▶│   catalog    │     │   inventory      │
│  (auth/user) │     │  (products)  │     │  (stock/reserve) │
└──────┬───────┘     └──────────────┘     └────────┬────────┘
       │                                           │
       │         ┌──────────────┐                  │
       └────────▶│    order     │◀─────────────────┘
                 │  (saga/crud) │
                 └──────┬───────┘
                        │
              ┌─────────┴─────────┐
              ▼                   ▼
       ┌──────────────┐   ┌───────────────┐
       │   payment    │   │  fulfillment  │
       │  (stripe)    │   │ (pick/deliver)│
       └──────────────┘   └───────────────┘
                                │
                          ┌─────▼──────┐
                          │notification │
                          │ (email/sms) │
                          └────────────┘
```

### Bounded Context Violations Identified

1. **Order service does too much** — Cart management, checkout orchestration (Temporal saga), order CRUD, order state machine, and event publishing are all in one service. At Zepto/Blinkit scale, checkout orchestration alone handles 50K+ concurrent sagas during flash sales.

2. **Fulfillment service conflates 3 domains** — Pick operations (warehouse), rider management (fleet), and delivery tracking (logistics) are coupled. Blinkit separates warehouse ops from rider fleet entirely.

3. **Catalog owns pricing** — Dynamic pricing, promotions, and coupons should be separate (Instacart model). Flash sales with 100K+ concurrent users need independent scaling.

4. **No dedicated search** — Search is a SQL query inside catalog. At 20M users, 70%+ traffic starts with search. Zepto runs a dedicated search service on OpenSearch.

5. **Notification is a monolith** — Email, SMS, Push, and WhatsApp are fundamentally different delivery mechanisms with different SLAs, rate limits, and provider integrations.

6. **No cart service** — Cart is ephemeral state managed inside order-service. Cart operations (add/remove/update) are the highest-TPS operations in Q-commerce.

7. **Audit logging distributed** — Every service writes its own audit_log table. No centralized, tamper-evident audit trail. Compliance requires a single source of truth.

### Cross-Service Coupling (Sync REST Calls)

| Caller | Callee | Method | Risk |
|--------|--------|--------|------|
| order → payment | authorize/capture/void | REST (blocking) | Payment down = checkout down |
| order → inventory | reserve/confirm/cancel | REST (blocking) | Inventory down = checkout down |
| order → cart | clear cart | REST (blocking) | Cart down = checkout fails cleanup |
| fulfillment → payment | refund | REST (blocking) | Payment down = substitution stuck |
| fulfillment → order | updateStatus | REST (blocking) | Order down = delivery stuck |
| fulfillment → inventory | check stock | REST (blocking) | Inventory down = substitution stuck |
| notification → identity | user lookup, preferences | REST (blocking) | Identity down = no notifications |
| notification → order | order lookup | REST (blocking) | Order down = no refund notifications |

**All of these should be async events or gRPC with circuit breakers.**

### Single Points of Failure

1. PostgreSQL per service (no read replicas configured)
2. Kafka (single cluster, no MirrorMaker)
3. Temporal (single namespace, no multi-region)
4. No Redis (referenced in health checks but unconfigured)
5. Single GKE region deployment

---

## Part 2: Target Architecture — 18 Services

### Domain Decomposition Map

```
Platform Layer:
  ┌──────────────┐  ┌──────────────┐  ┌──────────────┐  ┌──────────────┐
  │  API Gateway  │  │  Config/FF   │  │  Audit Trail │  │  Media CDN   │
  │  (BFF)        │  │  Service     │  │  Service     │  │  Service     │
  └──────────────┘  └──────────────┘  └──────────────┘  └──────────────┘

User Domain:
  ┌──────────────┐  ┌──────────────┐
  │  Identity    │  │  Wallet &    │
  │  Service     │  │  Loyalty     │
  └──────────────┘  └──────────────┘

Commerce Domain:
  ┌──────────────┐  ┌──────────────┐  ┌──────────────┐  ┌──────────────┐
  │  Catalog     │  │  Search      │  │  Pricing &   │  │  Cart        │
  │  Service     │  │  Service     │  │  Promotions  │  │  Service     │
  └──────────────┘  └──────────────┘  └──────────────┘  └──────────────┘

Transaction Domain:
  ┌──────────────┐  ┌──────────────┐  ┌──────────────┐
  │  Checkout    │  │  Order       │  │  Payment     │
  │  Orchestrator│  │  Service     │  │  Service     │
  └──────────────┘  └──────────────┘  └──────────────┘

Logistics Domain:
  ┌──────────────┐  ┌──────────────┐  ┌──────────────┐  ┌──────────────┐
  │  Warehouse   │  │  Inventory   │  │  Rider/Fleet │  │  Routing &   │
  │  (Dark Store)│  │  Service     │  │  Service     │  │  ETA Service │
  └──────────────┘  └──────────────┘  └──────────────┘  └──────────────┘

Communication Domain:
  ┌──────────────┐  ┌──────────────┐
  │  Notification│  │  Fraud       │
  │  Hub         │  │  Detection   │
  └──────────────┘  └──────────────┘
```

---

### Service 1: Identity Service (KEEP — refine)
**Bounded Context:** Authentication, authorization, user profile, notification preferences  
**Data Ownership:** `users`, `refresh_tokens`, `notification_preferences`, `outbox_events`  
**Scale Tier:** P0 (auth is on every request path)  
**Changes from current:**
- Add Redis-backed JWT blacklist
- Add password change/reset endpoints
- Add social login (Google/Apple)
- Expose phone in user response for SMS routing
- Add MFA/2FA support
- SLO: 99.99% availability, p99 < 50ms

### Service 2: Catalog Service (KEEP — slim down)
**Bounded Context:** Product master data, categories, product lifecycle  
**Data Ownership:** `products`, `categories`  
**Changes:** Remove search (→ Search Service), remove pricing logic (→ Pricing Service)  
**APIs:** CRUD products, category tree, product detail by ID  
**Events Published:** `ProductCreated`, `ProductUpdated`, `ProductDelisted`  
**Scale Tier:** P1 — heavily cached, low write volume  
**SLO:** 99.95%, p99 < 100ms (cache hit), p99 < 500ms (miss)

### Service 3: Search Service (NEW — split from Catalog)
**Bounded Context:** Product discovery, autocomplete, faceted search  
**Data Ownership:** OpenSearch/Elasticsearch index (read model, projected from catalog events)  
**Why Separate:** Search is the #1 traffic driver in Q-commerce (70%+ of sessions). Zepto's search handles 100K+ QPS during peak. Needs independent scaling, different data store (OpenSearch), different deployment cadence.  
**APIs:**
- `GET /search?q=milk&category=dairy&brand=amul&minPrice=&maxPrice=` — full-text + faceted
- `GET /search/autocomplete?q=mi` — prefix suggestions
- `GET /search/trending` — popular queries
**Events Consumed:** `catalog.events` (ProductCreated/Updated/Delisted) → index sync  
**Technology:** OpenSearch with synonym dictionaries, phonetic analysis for Hindi/regional languages  
**Scale Tier:** P0 — auto-scale on QPS  
**SLO:** 99.95%, p99 < 200ms, autocomplete p99 < 100ms

### Service 4: Pricing & Promotions Service (NEW — split from Catalog)
**Bounded Context:** Dynamic pricing, promotions, coupons, flash sales, surge pricing  
**Data Ownership:** `price_rules`, `promotions`, `coupons`, `coupon_redemptions`, `price_history`  
**Why Separate:** Flash sales (Blinkit's "10-min sale") need independent scaling. Price calculations are CPU-intensive with complex rule engines. Pricing changes hourly; catalog changes weekly. Different ownership teams.  
**APIs:**
- `POST /pricing/calculate` — cart price calculation with applicable promotions
- `GET /pricing/products/{id}` — current price + promotions
- CRUD `/admin/promotions`, `/admin/coupons`
**Events Published:** `PriceChanged`, `PromotionActivated`, `PromotionExpired`  
**Events Consumed:** `catalog.events` (ProductCreated — set base price)  
**Reference:** Instacart's pricing engine handles 50+ price rule types  
**Scale Tier:** P0 (every cart view, every checkout)  
**SLO:** 99.99%, p99 < 50ms (cached), p99 < 200ms (calculation)

### Service 5: Cart Service (NEW — split from Order)
**Bounded Context:** Ephemeral cart state, cart validation, cart-to-checkout transition  
**Data Ownership:** Redis-backed cart (no SQL DB — cart is ephemeral)  
**Why Separate:** Cart is the highest TPS operation — every product tap is a cart mutation. Zepto handles 500K+ cart ops/minute during peak. Cart requires Redis (not SQL), different scaling profile (memory-bound, not CPU-bound), and sub-10ms latency.  
**APIs:**
- `POST /cart/items` — add item
- `PATCH /cart/items/{id}` — update quantity
- `DELETE /cart/items/{id}` — remove item
- `GET /cart` — get current cart
- `POST /cart/validate` — check stock + prices before checkout
**Data Store:** Redis with hash-per-user, TTL 24h  
**Events Published:** `CartAbandoned` (scheduled detection)  
**Scale Tier:** P0  
**SLO:** 99.99%, p99 < 20ms

### Service 6: Checkout Orchestrator (NEW — split from Order)
**Bounded Context:** Saga orchestration for checkout, retry/compensation logic  
**Data Ownership:** Temporal workflow state (no separate DB)  
**Why Separate:** Checkout is the most complex flow — it coordinates inventory, payment, order creation, cart clearing, and notification. It has a fundamentally different scaling profile (long-running, stateful workflows) vs. order CRUD (short, stateless). Temporal workers need independent scaling and resource tuning.  
**Flow:**
1. Validate cart (Cart Service)
2. Calculate prices (Pricing Service)
3. Reserve inventory (Inventory Service)
4. Authorize payment (Payment Service)
5. Create order (Order Service)
6. Confirm reservation + Capture payment
7. Clear cart (Cart Service)
8. Publish OrderPlaced
**Compensation:** Reverse each step on failure  
**Technology:** Temporal (current), consider Cadence at extreme scale  
**Scale Tier:** P0  
**SLO:** 99.95%, p99 < 5s end-to-end

### Service 7: Order Service (KEEP — slim down)
**Bounded Context:** Order lifecycle, order state machine, order history  
**Data Ownership:** `orders`, `order_items`, `order_status_history`  
**Changes:** Remove checkout saga (→ Checkout Orchestrator), remove cart (→ Cart Service)  
**APIs:**
- `GET /orders` — user order history (paginated)
- `GET /orders/{id}` — order detail
- `POST /orders/{id}/cancel` — user-initiated cancel
- Admin CRUD
**Events Published:** `OrderCreated`, `OrderCancelled`, `OrderStatusChanged`  
**Events Consumed:** `checkout.events` (OrderPlaced → create order record)  
**Scale Tier:** P1  
**SLO:** 99.95%, p99 < 200ms

### Service 8: Payment Service (KEEP — harden)
**Bounded Context:** Payment processing, refunds, ledger, PSP integration  
**Data Ownership:** `payments`, `refunds`, `ledger_entries`, `outbox_events`  
**Changes:**
- Extract PSP calls from @Transactional
- Fix refund ledger direction
- Add pessimistic locking on refund
- Add payment method vault (saved cards/UPI)
- Add reconciliation job
**APIs:** authorize, capture, void, refund, webhook  
**Events Published:** `PaymentAuthorized`, `PaymentCaptured`, `PaymentRefunded`, `PaymentVoided`  
**Scale Tier:** P0 (PCI scope — minimal surface area)  
**SLO:** 99.99%, p99 < 2s (PSP dependent)

### Service 9: Inventory Service (KEEP — add events)
**Bounded Context:** Stock levels, reservations, warehouse-level tracking  
**Data Ownership:** `stock_items`, `reservations`  
**Critical Fix:** Add outbox pattern + event publishing  
**APIs:** Reserve, confirm, cancel, check availability, adjust stock  
**Events Published:** `StockReserved`, `StockConfirmed`, `StockReleased`, `LowStockAlert`  
**Scale Tier:** P0 (every checkout reserves stock)  
**SLO:** 99.99%, p99 < 100ms

### Service 10: Warehouse/Dark Store Service (NEW — split from Fulfillment)
**Bounded Context:** Store management, zone mapping, store capacity, operating hours  
**Data Ownership:** `stores`, `store_zones`, `store_capacity`, `store_hours`  
**Why Separate:** Blinkit operates 500+ dark stores. Store management (onboarding, capacity planning, zone mapping) is an operational concern distinct from order fulfillment. Different teams manage stores vs. pick operations.  
**APIs:**
- CRUD `/admin/stores`
- `GET /stores/nearest?lat=&lng=` — zone-based store lookup
- `GET /stores/{id}/capacity` — real-time capacity
**Events Published:** `StoreOpened`, `StoreClosed`, `CapacityChanged`  
**Scale Tier:** P2 (low TPS, high availability)  
**SLO:** 99.9%, p99 < 200ms

### Service 11: Fulfillment/Pick Service (KEEP — slim down to warehouse ops)
**Bounded Context:** Pick task management, packing, quality checks  
**Data Ownership:** `pick_tasks`, `pick_items`  
**Changes:** Remove rider management (→ Rider Service), remove delivery tracking (→ Routing Service)  
**APIs:** Create pick task, update item status, mark packed  
**Events Published:** `OrderPacked`, `ItemSubstituted`, `ItemMissing`  
**Events Consumed:** `order.events` (OrderPlaced → create pick task)  
**Scale Tier:** P1  
**SLO:** 99.95%, p99 < 200ms

### Service 12: Rider/Fleet Service (NEW — split from Fulfillment)
**Bounded Context:** Rider lifecycle, availability, assignment, earnings, ratings  
**Data Ownership:** `riders`, `rider_shifts`, `rider_earnings`, `rider_ratings`  
**Why Separate:** Zepto manages 50K+ riders. Fleet management (onboarding, shift management, earnings, compliance) is a complex domain. Rider assignment algorithm needs independent scaling during peak hours. Different team (ops/fleet) vs. warehouse team.  
**APIs:**
- CRUD `/admin/riders`
- `POST /riders/{id}/availability` — toggle availability
- `GET /riders/available?storeId=` — available riders
- `POST /riders/assign` — optimal rider assignment
- `GET /riders/{id}/earnings` — earnings dashboard
**Events Published:** `RiderAssigned`, `RiderAvailable`, `RiderOffline`  
**Events Consumed:** `fulfillment.events` (OrderPacked → trigger rider assignment)  
**Scale Tier:** P0 (rider assignment is on critical delivery path)  
**SLO:** 99.99%, p99 < 500ms (includes assignment algorithm)

### Service 13: Routing & ETA Service (NEW)
**Bounded Context:** Delivery tracking, ETA calculation, route optimization  
**Data Ownership:** `deliveries`, `delivery_tracking`, `eta_cache` (Redis)  
**Why Separate:** DoorDash's routing service handles 10M+ ETA calculations/day. ETA is compute-intensive (Google Maps API), needs caching, and has a completely different scaling profile. Real-time tracking via WebSocket/SSE needs dedicated infra.  
**APIs:**
- `GET /deliveries/{id}/track` — real-time location (WebSocket)
- `GET /deliveries/{id}/eta` — current ETA
- `POST /deliveries/{id}/status` — rider status updates (picked up, near, delivered)
**External Integrations:** Google Maps Distance Matrix API, MapMyIndia  
**Events Published:** `DeliveryStarted`, `OrderDelivered`, `ETAUpdated`  
**Events Consumed:** `rider.events` (RiderAssigned → create delivery)  
**Scale Tier:** P0  
**SLO:** 99.95%, p99 < 300ms (ETA), WebSocket 99.9%

### Service 14: Notification Hub (KEEP — redesign)
**Bounded Context:** Multi-channel notification delivery, templates, user preferences  
**Data Ownership:** `notification_log`, templates  
**Changes:**
- Fix Thread.sleep → scheduled retry
- Fix Kafka error handlers
- Add device token management for PUSH
- Add WhatsApp channel (Gupshup/Twilio)
- Add in-app notification via WebSocket
- Cache user lookups
**Channels:** Email (SES), SMS (Twilio), Push (FCM/APNs), WhatsApp, In-App  
**Events Consumed:** `order.events`, `payment.events`, `fulfillment.events`, `rider.events`  
**Scale Tier:** P1 (async, can tolerate brief delays)  
**SLO:** 99.9%, delivery within 30s of event

### Service 15: Wallet & Loyalty Service (NEW)
**Bounded Context:** Wallet balance, cashback, loyalty points, referral credits  
**Data Ownership:** `wallets`, `wallet_transactions`, `loyalty_points`, `referral_codes`  
**Why Separate:** Blinkit and Zepto both have wallet/cashback systems. It's a financial domain (needs double-entry like payments), has its own compliance requirements, and drives user retention. Different team (growth/monetization).  
**APIs:**
- `GET /wallet/balance` — current balance
- `POST /wallet/topup` — add funds
- `POST /wallet/debit` — use for payment
- `GET /wallet/transactions` — transaction history
- `GET /loyalty/points` — points balance
**Events Published:** `WalletCredited`, `WalletDebited`, `PointsEarned`  
**Events Consumed:** `order.events` (OrderDelivered → credit loyalty points)  
**Scale Tier:** P1  
**SLO:** 99.99%, p99 < 100ms

### Service 16: Audit Trail Service (NEW)
**Bounded Context:** Centralized, immutable audit log aggregation  
**Data Ownership:** `audit_events` (append-only, write-once)  
**Why Separate:** Compliance (PCI-DSS Req 10, GDPR Art 30) requires tamper-evident audit trails. Current approach (per-service audit_log tables) is fragile — any service can delete/modify its own logs. Centralized service with append-only storage (immutable DB or S3+Athena) provides true tamper evidence.  
**APIs:**
- `POST /audit/events` — ingest (internal only)
- `GET /admin/audit/events?userId=&action=&from=&to=` — query (admin only)
- `GET /admin/audit/export` — compliance export
**Data Store:** PostgreSQL with REVOKE UPDATE/DELETE + S3 cold archive  
**Events Consumed:** All `*.events` topics (audit consumer)  
**Scale Tier:** P2 (high volume writes, low-frequency reads)  
**SLO:** 99.9%, zero data loss

### Service 17: Fraud Detection Service (NEW)
**Bounded Context:** Real-time fraud scoring, rule engine, velocity checks  
**Data Ownership:** `fraud_rules`, `fraud_scores`, `fraud_signals`, `blocked_entities`  
**Why Separate:** DoorDash blocks $100M+ in fraud annually. Fraud detection needs ML models, velocity checks (10+ orders from same device in 5 min), and real-time scoring. Different expertise (data science team), independent deployment.  
**APIs:**
- `POST /fraud/score` — real-time scoring (called during checkout)
- `POST /fraud/report` — flag suspicious activity
- CRUD `/admin/fraud/rules`
**Events Consumed:** `order.events`, `payment.events`, `identity.events`  
**Events Published:** `FraudDetected`, `AccountBlocked`  
**Scale Tier:** P0 (on checkout critical path, must be fast)  
**SLO:** 99.99%, p99 < 100ms

### Service 18: Config & Feature Flag Service (NEW)
**Bounded Context:** Feature toggles, A/B testing, dark launches, gradual rollouts  
**Data Ownership:** `feature_flags`, `experiments`, `flag_overrides`  
**Why Separate:** Every Q-commerce company at scale uses feature flags. LaunchDarkly-like capability in-house reduces vendor dependency. Enables gradual rollout of the microservice decomposition itself.  
**APIs:**
- `GET /flags/{key}?userId=&context=` — evaluate flag
- `GET /flags/bulk?keys=` — batch evaluation
- CRUD `/admin/flags`
**Data Store:** Redis (hot path) + PostgreSQL (source of truth)  
**Scale Tier:** P0 (every service, every request potentially)  
**SLO:** 99.99%, p99 < 10ms

---

## Part 3: Data Strategy

### Database-Per-Service Enforcement

| Service | Database | Justification |
|---------|----------|---------------|
| Identity | PostgreSQL | Relational user data, ACID for auth |
| Catalog | PostgreSQL | Relational product data |
| Search | OpenSearch | Full-text search, facets, autocomplete |
| Pricing | PostgreSQL + Redis | Rules in PG, cached prices in Redis |
| Cart | Redis (primary) | Ephemeral, high TPS, sub-10ms |
| Checkout | Temporal (managed state) | Saga state managed by Temporal |
| Order | PostgreSQL (partitioned by month) | Historical orders, range queries |
| Payment | PostgreSQL (PCI-isolated) | PCI-DSS scope isolation |
| Inventory | PostgreSQL | Strong consistency for stock |
| Warehouse | PostgreSQL | Relational store data |
| Fulfillment | PostgreSQL | Pick task lifecycle |
| Rider/Fleet | PostgreSQL + Redis | Rider state in Redis, history in PG |
| Routing/ETA | Redis + Google Maps | Real-time location cache |
| Notification | PostgreSQL | Notification log, templates |
| Wallet | PostgreSQL | Financial — double-entry ledger |
| Audit | PostgreSQL (append-only) + S3 | Immutable, archived to cold storage |
| Fraud | PostgreSQL + Redis | Rules in PG, velocity counters in Redis |
| Config/FF | PostgreSQL + Redis | Source of truth + hot cache |

### CQRS Patterns

| Domain | Write Model | Read Model | Sync Mechanism |
|--------|-------------|------------|----------------|
| Catalog → Search | PostgreSQL | OpenSearch | CDC via Debezium |
| Order → Analytics | PostgreSQL | BigQuery/Redshift | Kafka → Data Lake |
| Inventory → Catalog | PostgreSQL (stock) | Redis (available count) | Event-driven |
| Payment → Ledger | PostgreSQL (entries) | Materialized views | Triggers/scheduled |

### Cache Architecture

```
L1 (In-Process):  Caffeine — 10s TTL, 1K entries per service
L2 (Distributed):  Redis Cluster — 5-60min TTL, 100K entries
L3 (CDN):          Cloud CDN — product images, static catalog pages

Hot paths:
- Product detail:  L1 (10s) → L2 (5min) → PostgreSQL
- Search results:  L2 (30s) → OpenSearch
- Cart:            Redis primary (no cache — it IS the store)
- User profile:    L1 (30s) → L2 (5min) → PostgreSQL
- Feature flags:   L1 (10s) → L2 (1min) → PostgreSQL
- Pricing:         L1 (10s) → L2 (5min) → PostgreSQL
```

---

## Part 4: Infrastructure Evolution

### Service Mesh Topology

```
Internet → Cloud Armor (WAF) → Global LB → Istio Ingress Gateway
                                                    │
                     ┌──────────────────────────────┤
                     │                              │
              API Gateway (BFF)              API Gateway (Admin)
                     │                              │
         ┌───────────┴───────────┐          ┌──────┴──────┐
    Mobile BFF           Web BFF       Admin Dashboard   Internal APIs
         │                   │                │
    ┌────┴────┐        ┌────┴────┐      ┌───┴───┐
    │ Identity │       │ Catalog │      │ All   │
    │ Cart     │       │ Search  │      │ Admin │
    │ Order    │       │ Pricing │      │ APIs  │
    └──────────┘       └─────────┘      └───────┘
```

### API Gateway Pattern — Backend for Frontend (BFF)

- **Mobile BFF:** Aggregates cart + pricing + product in single call. Optimized payloads (smaller JSON). Push notification token registration.
- **Web BFF:** Server-rendered catalog pages. WebSocket for live tracking. Larger payloads with richer product data.
- **Admin BFF:** Dashboard aggregation. Bulk operations. Report generation.

### Kafka Topic Architecture

```
Topics (partitioned by orderId or userId):

User Domain:
  identity.events      (6 partitions, 7d retention)

Commerce Domain:
  catalog.events       (6 partitions, 3d retention)
  search.index.events  (12 partitions, 1d retention)
  pricing.events       (6 partitions, 3d retention)
  cart.events          (12 partitions, 1d retention)

Transaction Domain:
  checkout.events      (12 partitions, 7d retention)
  order.events         (12 partitions, 30d retention)
  payment.events       (12 partitions, 30d retention)

Logistics Domain:
  inventory.events     (12 partitions, 7d retention)
  fulfillment.events   (6 partitions, 7d retention)
  rider.events         (12 partitions, 7d retention)
  delivery.events      (12 partitions, 7d retention)

Platform:
  notification.events  (6 partitions, 3d retention)
  audit.events         (12 partitions, 90d retention)
  fraud.events         (6 partitions, 30d retention)

DLQ:
  *.events.dlq         (per-topic DLQ, 30d retention)
```

### Multi-Region Strategy (Phase 2)

```
Primary Region: asia-south1 (Mumbai)
  - All services, read-write databases
  - Temporal server

Secondary Region: asia-south2 (Delhi)
  - Read replicas for catalog, order, identity
  - Warm standby for payment, inventory
  - Redis replica for cache

DR Region: asia-southeast1 (Singapore)
  - Cold standby
  - S3 cross-region replication for backups

Kafka: MirrorMaker 2 for cross-region replication
DNS: Cloud DNS with health-check-based failover
```

### Auto-Scaling Policies

| Service | Metric | Target | Min | Max |
|---------|--------|--------|-----|-----|
| Identity | CPU 60% | - | 3 | 12 |
| Search | CPU 50%, RPS | 1000 RPS | 4 | 20 |
| Cart | Memory 70% | - | 4 | 16 |
| Checkout | CPU 60%, active workflows | - | 3 | 12 |
| Inventory | CPU 50% | - | 3 | 10 |
| Payment | CPU 40% | - | 3 | 8 |
| Rider | CPU 60%, assignment latency | p99 < 500ms | 3 | 10 |
| Notification | Kafka consumer lag | < 1000 | 2 | 8 |

---

## Part 5: Migration Strategy

### Phase 0 — Fix Critical Production Issues (Week 1-2)
*No service splits. Fix what's broken.*
- [ ] Extract PSP calls from @Transactional (payment)
- [ ] Fix refund ledger direction (payment)
- [ ] Add pessimistic lock on refund (payment)
- [ ] Add REST client timeouts everywhere (all services)
- [ ] Fix Thread.sleep in notification retry
- [ ] Add Kafka error handlers with DLQ
- [ ] Add inventory outbox event publishing
- [ ] Replace unbounded ConcurrentHashMap rate limiters
- [ ] Fix /auth/revoke authentication
- [ ] Add K8s rolling update strategy + pod anti-affinity
- [ ] Add Istio DestinationRules with circuit breaking

### Phase 1 — Extract Cart Service (Week 3-4)
*Highest-TPS operation, clearest bounded context, lowest risk.*
1. Deploy Cart Service (Redis-backed)
2. Feature-flag new cart endpoints
3. Migrate cart operations from order-service (strangler fig)
4. Remove cart code from order-service
5. Update checkout orchestrator to call Cart Service

### Phase 2 — Extract Search Service (Week 5-6)
*Second-highest traffic, independent data store.*
1. Deploy OpenSearch cluster
2. Set up CDC pipeline: catalog PostgreSQL → Debezium → OpenSearch
3. Deploy Search Service with OpenSearch queries
4. Feature-flag search traffic: PostgreSQL → OpenSearch
5. Monitor and validate search quality
6. Remove search from catalog-service

### Phase 3 — Extract Checkout Orchestrator (Week 7-8)
*Decouple saga from order CRUD.*
1. Deploy Checkout Orchestrator as separate Temporal worker
2. Move workflow/activity code from order-service
3. Checkout API → new service; order-service becomes read/write CRUD
4. Update Temporal task queue routing

### Phase 4 — Split Fulfillment (Week 9-12)
*Extract rider/fleet and routing.*
1. Deploy Rider Service (rider CRUD + assignment)
2. Deploy Routing/ETA Service (delivery tracking + ETA)
3. Migrate rider tables from fulfillment DB
4. Fulfillment retains pick/pack only
5. Add WebSocket for real-time tracking

### Phase 5 — New Domain Services (Week 13-16)
*Additive — no existing code migration.*
1. Deploy Pricing & Promotions Service
2. Deploy Wallet & Loyalty Service
3. Deploy Fraud Detection Service
4. Deploy Audit Trail Service (centralized)
5. Deploy Config/Feature Flag Service

### Phase 6 — Platform Hardening (Week 17-20)
1. Multi-region deployment (Mumbai + Delhi)
2. Chaos engineering (Chaos Mesh)
3. Load testing (k6/Locust — target 10x current traffic)
4. Security hardening (WAF rules, NetworkPolicies, pod security)
5. PCI-DSS SAQ-A audit
6. GDPR readiness audit

### Rollback Strategy
Every phase uses feature flags. If new service fails:
1. Feature flag routes traffic back to old code path
2. Old service still runs in parallel for 2 weeks minimum
3. Data sync ensures both paths are consistent
4. Rollback is instant (flag flip, no deployment needed)

---

## Part 6: Organizational Alignment

### Team Topology

| Team | Type | Services Owned | Size |
|------|------|----------------|------|
| Platform | Platform | API Gateway, Config/FF, Audit Trail, CI/CD, Infra | 5-7 |
| Identity & Growth | Stream-aligned | Identity, Wallet/Loyalty | 4-5 |
| Discovery | Stream-aligned | Catalog, Search, Pricing | 5-7 |
| Checkout | Stream-aligned | Cart, Checkout Orchestrator, Order | 5-7 |
| Payments | Stream-aligned | Payment, Fraud Detection | 4-5 |
| Logistics | Stream-aligned | Inventory, Warehouse, Fulfillment, Rider, Routing | 7-9 |
| Communication | Stream-aligned | Notification Hub, Media | 3-4 |
| SRE | Enabling | Observability, Incident Response, Capacity | 4-5 |

### Service Ownership Model (per DoorDash engineering)
- Each service has ONE owning team
- Owning team sets SLOs, manages on-call, approves changes
- Cross-team changes require RFC + owning team review
- Service catalog maintained in Backstage/OpsLevel

### SLO/SLI Per Service Tier

| Tier | Services | Availability | Latency (p99) | Error Budget |
|------|----------|-------------|---------------|-------------|
| P0 — Critical Path | Identity, Cart, Checkout, Payment, Inventory, Search, Rider, Fraud | 99.99% | < 200ms | 4.3 min/month |
| P1 — Important | Order, Catalog, Fulfillment, Pricing, Notification | 99.95% | < 500ms | 21.9 min/month |
| P2 — Support | Warehouse, Wallet, Audit, Config, Media | 99.9% | < 1s | 43.8 min/month |

### On-Call Rotation
- **P0 services:** 24/7 on-call, 5-minute response SLA, PagerDuty
- **P1 services:** Business hours + on-call, 15-minute response SLA
- **P2 services:** Business hours, 1-hour response SLA

---

## Summary: Current 7 → Target 18 Services

| # | Current Service | Target Decomposition |
|---|----------------|---------------------|
| 1 | identity-service | Identity Service (refined) |
| 2 | catalog-service | Catalog Service (slimmed) + **Search Service** + **Pricing Service** |
| 3 | inventory-service | Inventory Service (with events) + **Warehouse Service** |
| 4 | order-service | **Cart Service** + **Checkout Orchestrator** + Order Service (slimmed) |
| 5 | payment-service | Payment Service (hardened) + **Fraud Detection** |
| 6 | fulfillment-service | Fulfillment/Pick Service + **Rider Service** + **Routing/ETA Service** |
| 7 | notification-service | Notification Hub (redesigned) |
| — | (new) | **Wallet & Loyalty**, **Audit Trail**, **Config & Feature Flags** |

**Total: 7 → 18 services, phased over 20 weeks with zero-downtime migration using feature flags and strangler fig pattern.**
