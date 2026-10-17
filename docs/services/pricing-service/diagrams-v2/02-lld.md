# Pricing Service - Low-Level Design

## Component Architecture

```mermaid
graph TB
    HTTPRequest["🌐 HTTP Request<br/>(REST/gRPC)"]

    DispatcherServlet["DispatcherServlet<br/>(Spring MVC)"]

    subgraph controllers["Controllers"]
        PriceController["💰 PriceController<br/>@RestController"]
        CartPriceController["🛒 CartPriceController<br/>@RestController"]
        CouponController["🎫 CouponController<br/>@RestController"]
        PromotionController["🎁 PromotionController<br/>@RestController"]
        AdminController["⚙️ AdminPricingController<br/>@RestController"]
    end

    subgraph services["Service Layer"]
        PriceService["PriceService"]
        CartCalculationService["CartCalculationService"]
        PromotionService["PromotionService"]
        CouponService["CouponService"]
        RulesEngineService["RulesEngineService"]
    end

    subgraph engine["Pricing Engine Core"]
        RulesEngine["🔧 PricingRulesEngine<br/>(Rule evaluation)"]
        PriceCalculator["💰 PriceCalculator<br/>(Calculation logic)"]
        PromotionMatcher["🎁 PromotionMatcher<br/>(Promo eligibility)"]
        CouponValidator["🎫 CouponValidator<br/>(Code validation)"]
        PriceAggregator["📊 PriceAggregator<br/>(Final price assembly)"]
    end

    subgraph repositories["Repository Layer"]
        PriceRepo["PriceRepository"]
        RulesRepo["PriceRulesRepository"]
        PromotionRepo["PromotionRepository"]
        CouponRepo["CouponRepository"]
    end

    subgraph cache["Cache Layer"]
        PriceCache["⚡ PriceCacheService<br/>(Redis, TTL: 5min)"]
        PromotionCache["⚡ PromotionCacheService<br/>(Redis, TTL: 1min)"]
    end

    subgraph external["External Integrations"]
        InventoryClient["📦 InventoryServiceClient<br/>(gRPC)"]
        ConfigClient["⚙️ ConfigServiceClient<br/>(REST)"]
        KafkaProducer["📬 PriceEventProducer<br/>(Kafka)"]
    end

    HTTPRequest --> DispatcherServlet
    DispatcherServlet --> controllers

    PriceController --> PriceService
    CartPriceController --> CartCalculationService
    CouponController --> CouponService
    PromotionController --> PromotionService
    AdminController --> PromotionService

    PriceService --> RulesEngineService
    CartCalculationService --> RulesEngineService
    PromotionService --> PromotionMatcher
    CouponService --> CouponValidator

    RulesEngineService --> RulesEngine
    RulesEngine --> PriceCalculator
    PriceCalculator --> PromotionMatcher
    PromotionMatcher --> CouponValidator
    CouponValidator --> PriceAggregator

    PriceService --> PriceCache
    PriceService --> PriceRepo
    RulesEngineService --> RulesRepo
    PromotionService --> PromotionRepo
    PromotionService --> PromotionCache
    CouponService --> CouponRepo

    RulesEngine --> InventoryClient
    RulesEngine --> ConfigClient
    PriceAggregator --> KafkaProducer

    style RulesEngine fill:#4A90E2,color:#fff,stroke:#333,stroke-width:2px
    style PriceCalculator fill:#7ED321,color:#000,stroke:#333,stroke-width:2px
    style PromotionMatcher fill:#9013FE,color:#fff
    style CouponValidator fill:#F5A623,color:#000
    style PriceCache fill:#DC382D,color:#fff
```

## PricingRulesEngine Implementation

```mermaid
graph TD
    A["PricingRulesEngine.evaluate<br/>(PricingContext context)"]
    B["1. Load base price<br/>from catalog/cache"]
    C["2. Get applicable rules<br/>by priority order"]
    D{"Rules<br/>exist?"}
    E["Skip rules<br/>use base price"]
    F["3. Filter rules by conditions"]

    G["Rule Condition Types:"]
    G1["📍 LocationRule<br/>zone_id EQUALS metro-1"]
    G2["⏰ TimeRule<br/>hour BETWEEN 18-22"]
    G3["📈 DemandRule<br/>demand_factor > 1.5"]
    G4["📦 StockRule<br/>stock_level < 10"]
    G5["👤 SegmentRule<br/>user_segment IN [VIP, NEW]"]

    H["4. Sort by priority<br/>(lower = higher priority)"]
    I["5. Apply rules sequentially"]

    J["Rule Application:"]
    J1["PERCENTAGE_MARKUP<br/>price *= 1.10"]
    J2["FIXED_MARKUP<br/>price += 5.00"]
    J3["PERCENTAGE_DISCOUNT<br/>price *= 0.90"]
    J4["FIXED_DISCOUNT<br/>price -= 10.00"]
    J5["OVERRIDE<br/>price = rule.value"]

    K["6. Enforce min/max bounds<br/>min_price <= price <= max_price"]
    L["7. Return PriceResult<br/>with breakdown"]

    A --> B --> C --> D
    D -->|No| E --> L
    D -->|Yes| F

    F --> G
    G --> G1
    G --> G2
    G --> G3
    G --> G4
    G --> G5

    G1 --> H
    G2 --> H
    G3 --> H
    G4 --> H
    G5 --> H

    H --> I --> J
    J --> J1
    J --> J2
    J --> J3
    J --> J4
    J --> J5

    J1 --> K
    J2 --> K
    J3 --> K
    J4 --> K
    J5 --> K
    K --> L

    style A fill:#4A90E2,color:#fff,stroke:#333,stroke-width:2px
    style F fill:#7ED321,color:#000
    style H fill:#F5A623,color:#000
    style K fill:#FF6B6B,color:#fff
    style L fill:#9013FE,color:#fff
```

## CartCalculationService Implementation

```mermaid
graph TD
    A["CartCalculationService.calculate<br/>(CartRequest request)"]
    B["1. Extract cart items<br/>[{sku, qty, ...}]"]
    C["2. Batch fetch base prices<br/>from cache/DB"]

    D["3. For each item:"]
    D1["Get applicable rules"]
    D2["Apply location markup"]
    D3["Apply time-based pricing"]
    D4["Calculate line item total<br/>unit_price * quantity"]

    E["4. Calculate cart subtotal<br/>SUM(line_totals)"]

    F["5. Apply cart-level promotions:"]
    F1["BOGO (Buy One Get One)"]
    F2["Bundle discounts"]
    F3["Minimum order discounts"]
    F4["Free delivery threshold"]

    G["6. Calculate promotion savings"]
    H["7. Apply coupon if provided"]

    I{"Coupon<br/>valid?"}
    J["Apply coupon discount"]
    K["Skip coupon"]

    L["8. Calculate delivery fee"]
    M["9. Calculate taxes<br/>(GST/VAT)"]
    N["10. Build CartPriceResponse"]

    O["Response:"]
    O1["subtotal: ₹500"]
    O2["promo_discount: -₹50"]
    O3["coupon_discount: -₹25"]
    O4["delivery_fee: ₹30"]
    O5["tax: ₹40.95"]
    O6["total: ₹495.95"]
    O7["savings: ₹75"]

    A --> B --> C --> D
    D --> D1 --> D2 --> D3 --> D4
    D4 --> E --> F
    F --> F1
    F --> F2
    F --> F3
    F --> F4
    F1 --> G
    F2 --> G
    F3 --> G
    F4 --> G

    G --> H --> I
    I -->|Yes| J --> L
    I -->|No| K --> L

    L --> M --> N --> O
    O --> O1
    O --> O2
    O --> O3
    O --> O4
    O --> O5
    O --> O6
    O --> O7

    style A fill:#4A90E2,color:#fff,stroke:#333,stroke-width:2px
    style E fill:#7ED321,color:#000
    style G fill:#9013FE,color:#fff
    style J fill:#F5A623,color:#000
    style N fill:#4A90E2,color:#fff,stroke:#333,stroke-width:2px
```

## PromotionMatcher Implementation

```mermaid
graph TD
    A["PromotionMatcher.findApplicable<br/>(CartContext, List<Promotion>)"]
    B["1. Filter ACTIVE promotions<br/>status = ACTIVE"]
    C["2. Filter by date range<br/>start_date <= now <= end_date"]
    D["3. Filter by usage limits"]

    D1["Global limit check<br/>usage_count < max_usage"]
    D2["Per-user limit check<br/>user_usage < max_per_user"]

    E["4. Evaluate conditions:"]
    E1["MIN_CART_VALUE<br/>cart.total >= threshold"]
    E2["SPECIFIC_PRODUCTS<br/>cart contains SKUs"]
    E3["CATEGORY_MATCH<br/>cart has category items"]
    E4["USER_SEGMENT<br/>user in target segment"]
    E5["FIRST_ORDER<br/>user.order_count == 0"]
    E6["PAYMENT_METHOD<br/>payment in allowed list"]

    F["5. Sort by stackability"]
    G{"Stackable<br/>promos?"}
    H["Apply best single promo"]
    I["Stack compatible promos"]

    J["6. Calculate discount"]
    K["7. Return PromotionResult<br/>[{promo_id, discount, type}]"]

    A --> B --> C --> D
    D --> D1
    D --> D2
    D1 --> E
    D2 --> E

    E --> E1
    E --> E2
    E --> E3
    E --> E4
    E --> E5
    E --> E6

    E1 --> F
    E2 --> F
    E3 --> F
    E4 --> F
    E5 --> F
    E6 --> F

    F --> G
    G -->|No| H --> J
    G -->|Yes| I --> J
    J --> K

    style A fill:#4A90E2,color:#fff,stroke:#333,stroke-width:2px
    style E fill:#7ED321,color:#000
    style F fill:#9013FE,color:#fff
    style K fill:#F5A623,color:#000,stroke:#333,stroke-width:2px
```

## CouponValidator Implementation

```mermaid
graph TD
    A["CouponValidator.validate<br/>(String code, CartContext cart)"]
    B["1. Lookup coupon by code<br/>(case-insensitive)"]

    C{"Coupon<br/>found?"}
    D["❌ INVALID_CODE"]

    E["2. Check status"]
    F{"status ==<br/>ACTIVE?"}
    G["❌ COUPON_INACTIVE"]

    H["3. Check date validity"]
    I{"start <= now<br/><= end?"}
    J["❌ COUPON_EXPIRED"]

    K["4. Check usage limits"]
    L{"global_usage <<br/>max_usage?"}
    M["❌ USAGE_LIMIT_EXCEEDED"]

    N["5. Check per-user limit"]
    O{"user_usage <<br/>max_per_user?"}
    P["❌ USER_LIMIT_EXCEEDED"]

    Q["6. Validate conditions"]
    R{"min_cart_value<br/>met?"}
    S["❌ MIN_CART_NOT_MET"]

    T{"applicable<br/>products?"}
    U["❌ PRODUCTS_NOT_ELIGIBLE"]

    V["7. Calculate discount"]
    W["discount_type: PERCENTAGE"]
    W1["discount = cart.total * percent / 100"]
    X["discount_type: FIXED"]
    X1["discount = fixed_amount"]
    Y["8. Apply max_discount cap"]

    Z["✅ Return CouponResult<br/>{valid: true, discount: X}"]

    A --> B --> C
    C -->|No| D
    C -->|Yes| E --> F
    F -->|No| G
    F -->|Yes| H --> I
    I -->|No| J
    I -->|Yes| K --> L
    L -->|No| M
    L -->|Yes| N --> O
    O -->|No| P
    O -->|Yes| Q --> R
    R -->|No| S
    R -->|Yes| T
    T -->|No| U
    T -->|Yes| V

    V --> W --> W1 --> Y
    V --> X --> X1 --> Y
    Y --> Z

    style A fill:#4A90E2,color:#fff,stroke:#333,stroke-width:2px
    style D fill:#FF6B6B,color:#fff
    style G fill:#FF6B6B,color:#fff
    style J fill:#FF6B6B,color:#fff
    style M fill:#FF6B6B,color:#fff
    style P fill:#FF6B6B,color:#fff
    style S fill:#FF6B6B,color:#fff
    style U fill:#FF6B6B,color:#fff
    style Z fill:#7ED321,color:#000,stroke:#333,stroke-width:2px
```

## Cache Strategy

```mermaid
graph TB
    subgraph cacheKeys["Cache Key Patterns"]
        PK["price:{sku}:{zone_id}<br/>TTL: 5 min"]
        RK["rules:{zone_id}:{hour}<br/>TTL: 15 min"]
        PromK["promos:active:{category}<br/>TTL: 1 min"]
        CouponK["coupon:{code}<br/>TTL: 30 min"]
        UserCouponK["user:coupon:{user_id}:{code}<br/>TTL: 24 hr"]
    end

    subgraph cacheOps["Cache Operations"]
        Read["Read-through<br/>(cache miss → DB → cache)"]
        Write["Write-behind<br/>(async cache update)"]
        Invalidate["Event-driven invalidation<br/>(Kafka consumer)"]
    end

    subgraph eviction["Eviction Strategy"]
        LRU["LRU for price cache<br/>(high cardinality)"]
        TTL["TTL for promos/coupons<br/>(time-sensitive)"]
        Manual["Manual invalidation<br/>(admin updates)"]
    end

    PK --> Read
    RK --> Read
    PromK --> Write
    CouponK --> Read
    UserCouponK --> Write

    Read --> LRU
    Write --> TTL
    Invalidate --> Manual

    style PK fill:#DC382D,color:#fff
    style RK fill:#DC382D,color:#fff
    style PromK fill:#DC382D,color:#fff
    style CouponK fill:#DC382D,color:#fff
```

## Metrics Collection

```mermaid
graph LR
    Request["HTTP Request"]

    subgraph timing["Timing Metrics"]
        T1["price_calculation_duration_ms"]
        T2["cart_calculation_duration_ms"]
        T3["coupon_validation_duration_ms"]
        T4["promotion_matching_duration_ms"]
        T5["cache_lookup_duration_ms"]
        T6["db_query_duration_ms"]
    end

    subgraph counters["Counter Metrics"]
        C1["prices_calculated_total"]
        C2["carts_calculated_total"]
        C3["coupons_validated_total{status}"]
        C4["promotions_applied_total{promo_id}"]
        C5["cache_hits_total{cache_type}"]
        C6["cache_misses_total{cache_type}"]
        C7["rules_evaluated_total{rule_type}"]
    end

    subgraph gauges["Gauge Metrics"]
        G1["active_promotions_count"]
        G2["active_coupons_count"]
        G3["price_rules_count"]
        G4["cache_size_bytes"]
    end

    Request --> T1
    Request --> T2
    Request --> T3
    T1 --> C1
    T2 --> C2
    T3 --> C3
    T3 --> C5
    T3 --> C6

    style T1 fill:#9013FE,color:#fff
    style T2 fill:#9013FE,color:#fff
    style C3 fill:#7ED321,color:#000
    style C5 fill:#7ED321,color:#000
```

## SLO Latency Budget

```
┌─────────────────────────────────────────────────────────────┐
│  Pricing Service Request Timeline (P99 target: <200ms)      │
├─────────────────────────────────────────────────────────────┤
│ Request parsing & validation:          ~5ms                 │
│ Cache lookup (Redis):                  ~3ms (hit)           │
│                                        ~2ms (miss overhead) │
│                                                             │
│ If cache miss:                                              │
│   - DB query (base price):             ~15ms                │
│   - DB query (rules):                  ~10ms                │
│   - Rules evaluation:                  ~20ms                │
│                                                             │
│ Promotion matching:                    ~25ms                │
│ Coupon validation:                     ~15ms                │
│ Price aggregation:                     ~5ms                 │
│ Response serialization:                ~5ms                 │
│                                                             │
│ TOTAL P99 (cache hit):                 ~50ms                │
│ TOTAL P99 (cache miss):                ~100ms               │
│ TOTAL P99 (cart calculation):          ~150ms               │
│ BUFFER (200ms target):                 ~50ms                │
│ STATUS:                                ✅ WITHIN SLO        │
└─────────────────────────────────────────────────────────────┘
```

## Error Handling & Resilience

```mermaid
graph TD
    E1["Cache Unavailable"]
    E1a["Fallback to DB"]
    E1b["Log: CACHE_FALLBACK"]
    E1c["Increment cache_failures_total"]

    E2["Price Not Found"]
    E2a["Return 404"]
    E2b["Log: PRICE_NOT_FOUND"]

    E3["Rule Evaluation Error"]
    E3a["Use base price only"]
    E3b["Log: RULE_EVAL_ERROR"]
    E3c["Alert if >5% errors"]

    E4["Coupon Service Timeout"]
    E4a["Retry with backoff"]
    E4b["Circuit breaker OPEN"]
    E4c["Return coupon skipped"]

    E5["Price Floor Violation"]
    E5a["Enforce min_price"]
    E5b["Log: FLOOR_ENFORCED"]
    E5c["Track margin protection"]

    E1 --> E1a --> E1b --> E1c
    E2 --> E2a --> E2b
    E3 --> E3a --> E3b --> E3c
    E4 --> E4a --> E4b --> E4c
    E5 --> E5a --> E5b --> E5c

    style E1a fill:#F5A623,color:#000
    style E2a fill:#FF6B6B,color:#fff
    style E3a fill:#F5A623,color:#000
    style E4c fill:#F5A623,color:#000
    style E5a fill:#7ED321,color:#000
```
