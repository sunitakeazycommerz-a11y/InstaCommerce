# Pricing Service — Deep Architecture Review

**Service:** `services/pricing-service`
**Reviewer Role:** Senior Pricing & Promotions Architect
**Date:** 2025-07
**Stack:** Java 21, Spring Boot 3, PostgreSQL, Kafka, Caffeine cache, Resilience4j, Flyway, ShedLock
**Scale Context:** Q-commerce platform targeting 20M+ users

---

## Table of Contents

1. [Executive Summary](#1-executive-summary)
2. [Codebase Inventory](#2-codebase-inventory)
3. [Business Logic Review](#3-business-logic-review)
   - 3.1 Price Rule Engine
   - 3.2 Promotions
   - 3.3 Coupon System
   - 3.4 Flash Sales
   - 3.5 Surge Pricing
   - 3.6 Cart Pricing
   - 3.7 Price History & Audit
4. [SLA & Performance Review](#4-sla--performance-review)
   - 4.1 Latency Analysis
   - 4.2 Cache Invalidation
   - 4.3 Concurrent Coupon Redemption
5. [Missing Features](#5-missing-features)
6. [Q-Commerce Competitor Comparison](#6-q-commerce-competitor-comparison)
7. [Security Review](#7-security-review)
8. [Database Schema Review](#8-database-schema-review)
9. [Prioritized Action Items](#9-prioritized-action-items)

---

## 1. Executive Summary

The pricing-service is a **functional MVP** with a clean Spring Boot architecture, but it is **not production-ready for 20M+ users** in a competitive Q-commerce market. The core pricing pipeline (base price → multiplier → promotion → coupon) works, but the system has several **critical gaps**:

| Area | Verdict | Risk |
|------|---------|------|
| Price rule engine | ⚠️ Basic | No priority/conflict resolution, no rule stacking |
| Promotions | ⚠️ Limited | Only % and flat discounts; no BOGO, bundle, category-level |
| Coupon concurrency | 🔴 Critical | Race condition on `totalRedeemed` — no row-level locking |
| Flash sales | 🔴 Missing | No inventory-aware pricing, no time-windowed rush support |
| Surge pricing | 🔴 Missing | No demand-based or time-of-day pricing |
| Cart atomicity | ⚠️ Weak | Prices can change between calculate and checkout |
| Price history/audit | 🔴 Missing | No audit trail — regulatory risk |
| Cache invalidation | ⚠️ Delayed | Up to 60s stale promotions after start/end |
| Subscription/membership | 🔴 Missing | No tiered pricing, no subscription discounts |
| Location-based pricing | 🔴 Missing | No city/zone pricing |
| A/B pricing | 🔴 Missing | No feature flag integration |

**Overall Rating: 4/10 for production Q-commerce at scale.**

---

## 2. Codebase Inventory

| Layer | Files | Purpose |
|-------|-------|---------|
| **Domain** | `PriceRule`, `Promotion`, `Coupon`, `CouponRedemption`, `OutboxEvent` | JPA entities |
| **Repository** | `PriceRuleRepository`, `PromotionRepository`, `CouponRepository`, `CouponRedemptionRepository`, `OutboxEventRepository` | Data access |
| **Service** | `PricingService`, `PromotionService`, `CouponService`, `OutboxService` | Business logic |
| **Controller** | `PricingController`, `AdminPromotionController`, `AdminCouponController` | REST API |
| **Consumer** | `CatalogEventConsumer`, `CatalogProductEvent`, `EventEnvelope` | Kafka consumer for catalog events |
| **Config** | `SecurityConfig`, `CacheConfig`, `KafkaConfig`, `SchedulerConfig`, `PricingProperties` | Infrastructure |
| **Security** | `JwtAuthenticationFilter`, `DefaultJwtService`, `JwtKeyLoader`, `RestAuthenticationEntryPoint`, `RestAccessDeniedHandler` | JWT auth |
| **Exception** | `GlobalExceptionHandler`, `ApiException`, plus 4 specific exceptions | Error handling |
| **DTO** | 5 request records, 5 response records | API contracts |
| **Migrations** | V1–V5 | Schema (price_rules, promotions, coupons, coupon_redemptions, outbox_events, shedlock) |

Total: **~50 files** including build, Docker, config.

---

## 3. Business Logic Review

### 3.1 Price Rule Engine

**Location:** `PricingService.calculatePrice()`, `PriceRuleRepository.findActiveRuleByProductId()`

**How it works:**
```
final_price = base_price_cents × multiplier
```

The repository query selects the **most recently effective** active rule for a product:
```sql
WHERE product_id = :productId AND active = true
  AND effective_from <= :now
  AND (effective_to IS NULL OR effective_to > :now)
ORDER BY effective_from DESC LIMIT 1
```

**Issues Found:**

| # | Issue | Severity | Detail |
|---|-------|----------|--------|
| 1 | **No rule priority/precedence system** | 🔴 High | Rules are ordered only by `effective_from DESC`. If two rules overlap in time, the newest wins arbitrarily. There is no explicit `priority` column. |
| 2 | **No rule type differentiation in evaluation** | ⚠️ Medium | `rule_type` field exists (defaults to "STANDARD") but is **never used in selection logic**. Cannot distinguish SURGE vs FLASH_SALE vs STANDARD in queries. |
| 3 | **No rule stacking** | ⚠️ Medium | Only one rule per product is ever applied. Cannot compose rules (e.g., base price + seasonal multiplier + category surcharge). |
| 4 | **No conflict detection** | 🔴 High | Two admins can create overlapping rules for the same product with no validation or warning. |
| 5 | **ruleType is a String, not an enum** | ⚠️ Low | No compile-time safety. Typos in rule types will silently pass. |
| 6 | **`multiplier` precision limited to DECIMAL(5,2)** | ⚠️ Low | Max multiplier is 999.99. Fine for most cases, but a 0.001 precision surge multiplier (e.g., 1.005x) is impossible. |

**Recommendation:**
- Add a `priority INT NOT NULL DEFAULT 0` column to `price_rules`.
- Change query to `ORDER BY priority DESC, effective_from DESC LIMIT 1`.
- Add a unique constraint or validation to prevent overlapping rules of the same type and priority.
- Consider a chain-of-responsibility pattern for rule stacking.

---

### 3.2 Promotions

**Location:** `PromotionService`, `Promotion` entity

**Supported discount types:**
- `PERCENTAGE` — percentage off subtotal, with optional `max_discount_cents` cap
- `FLAT` (implicit else branch) — fixed amount off

**Discount calculation:**
```java
if ("PERCENTAGE".equalsIgnoreCase(discountType))
    discount = subtotal * value / 100
else
    discount = value  // treated as cents
discount = min(discount, maxDiscountCents)  // cap
discount = min(discount, subtotalCents)     // never go below zero
```

**Issues Found:**

| # | Issue | Severity | Detail |
|---|-------|----------|--------|
| 1 | **Only 2 discount types** | 🔴 High | No BOGO, buy-X-get-Y, bundle pricing, category-level discounts, free delivery, cashback, or percentage-off-specific-items. |
| 2 | **"Best promotion" selection is naive** | 🔴 High | `findApplicable()` returns promotions ordered by `discount_value DESC`. The service picks `applicablePromotions.get(0)` — the highest `discount_value`. But a 50% promotion may give less actual discount than a ₹200-flat promotion depending on cart total. Should compare **calculated** discount, not raw `discount_value`. |
| 3 | **No product/category scoping** | 🔴 High | Promotions are cart-wide only. Cannot target specific products, categories, brands, or SKUs. |
| 4 | **maxUses counter is not atomically incremented** | ⚠️ Medium | `Promotion.currentUses` exists but is **never incremented** when a promotion is applied. The `maxUses` check in the query works only at the DB query level, but the counter never advances. |
| 5 | **No stackability control** | ⚠️ Medium | No `stackable` flag. Currently only one promotion is applied, but there's no modeling for "this promo can stack with coupons" vs "exclusive". |
| 6 | **discountType is a String, not enum** | ⚠️ Low | "PERCENTAGE" is matched case-insensitively but could have typos. |
| 7 | **No promotion targeting (new users, first order, etc.)** | ⚠️ Medium | Common Q-commerce need. No user segment or cohort targeting. |

**Missing Promotion Types for Q-Commerce:**

| Type | Blinkit | Zepto | Instacart | Current |
|------|---------|-------|-----------|---------|
| Percentage off | ✅ | ✅ | ✅ | ✅ |
| Flat discount | ✅ | ✅ | ✅ | ✅ |
| BOGO (buy-one-get-one) | ✅ | ✅ | ✅ | ❌ |
| Buy X get Y free | ✅ | ✅ | ✅ | ❌ |
| Bundle pricing | ✅ | ❌ | ✅ | ❌ |
| Category-level discount | ✅ | ✅ | ✅ | ❌ |
| Free delivery threshold | ✅ | ✅ | ✅ | ❌ |
| Cashback/rewards | ✅ | ✅ | ✅ | ❌ |
| First-order discount | ✅ | ✅ | ✅ | ❌ |
| Referral discount | ✅ | ✅ | ✅ | ❌ |

---

### 3.3 Coupon System

**Location:** `CouponService`, `Coupon`, `CouponRedemption` entities

**Validation checks:**
1. ✅ Coupon exists (case-insensitive lookup)
2. ✅ Coupon is active
3. ✅ Linked promotion is active and within date window
4. ✅ Minimum order amount met
5. ✅ Total redemption limit check (`totalLimit` vs `totalRedeemed`)
6. ✅ Per-user limit check (counts `coupon_redemptions` rows)

**Issues Found:**

| # | Issue | Severity | Detail |
|---|-------|----------|--------|
| 1 | **🔴 CRITICAL: Race condition on `totalRedeemed` increment** | 🔴 Critical | `redeemCoupon()` line 108: `coupon.setTotalRedeemed(coupon.getTotalRedeemed() + 1)` is a **read-modify-write** without any locking. Two concurrent transactions can both read `totalRedeemed=99`, both set it to `100`, and the 100-limit coupon gets redeemed 101 times. This is the classic lost-update problem. |
| 2 | **No `SELECT ... FOR UPDATE` or `@Lock(PESSIMISTIC_WRITE)`** | 🔴 Critical | The `findByCodeIgnoreCase()` in `redeemCoupon()` uses a regular SELECT. No pessimistic lock is acquired before the check-then-act on `totalRedeemed`. |
| 3 | **No `@Version` for optimistic locking** | 🔴 Critical | The `Coupon` entity has no `@Version` column. Even if using `@Transactional`, JPA's default isolation level (READ_COMMITTED on PostgreSQL) does **not** prevent the lost-update race. |
| 4 | **Validate and redeem are separate calls** | ⚠️ Medium | `validateCoupon()` is `readOnly=true` (line 45) but `redeemCoupon()` re-fetches and re-validates. The gap between validate (during cart pricing) and redeem (at checkout) allows TOCTOU issues — coupon could expire or hit limit between the two calls. |
| 5 | **Single-use partial index may not fully work** | ⚠️ Medium | The unique index `idx_coupon_single_use` uses a subquery `WHERE coupon_id IN (SELECT id FROM coupons WHERE single_use = true)`. PostgreSQL partial indexes with subqueries in the WHERE clause are not supported — this index is effectively a standard unique index on `(coupon_id, user_id)` for ALL coupons, or it may not be created at all. Needs verification. |
| 6 | **No coupon code format validation** | ⚠️ Low | Coupon codes are uppercased but no format/length validation beyond `@NotBlank`. |

**Fix for Race Condition:**
```java
// Option A: Pessimistic locking (recommended for correctness)
@Query("SELECT c FROM Coupon c WHERE UPPER(c.code) = UPPER(:code)")
@Lock(LockModeType.PESSIMISTIC_WRITE)
Optional<Coupon> findByCodeForUpdate(@Param("code") String code);

// Option B: Atomic SQL update
@Modifying
@Query("UPDATE Coupon c SET c.totalRedeemed = c.totalRedeemed + 1 " +
       "WHERE c.id = :id AND (c.totalLimit IS NULL OR c.totalRedeemed < c.totalLimit)")
int incrementTotalRedeemed(@Param("id") UUID id);
// Returns 0 if limit was already reached — no race condition.
```

---

### 3.4 Flash Sales

**Verdict: 🔴 NOT SUPPORTED**

There is **zero flash sale infrastructure** in the codebase.

**What's needed for 100K concurrent users on a flash sale:**

| Requirement | Current State |
|-------------|---------------|
| Time-windowed pricing (e.g., 12:00–12:15 PM) | ❌ Only `effectiveFrom`/`effectiveTo` on price rules, but no special flash sale handling |
| Inventory-aware pricing (price only valid while stock > 0) | ❌ No inventory integration at all |
| Rate limiting per sale item | ❌ Global rate limiter only (100 req/60s is far too low for flash sales) |
| Pre-warming cache before sale starts | ❌ No cache warming mechanism |
| Atomic decrement of sale quantity | ❌ No sale quantity tracking |
| Queueing system for overflow | ❌ No queue |
| Countdown/availability signals | ❌ No WebSocket or SSE |

**At 100K concurrent users**, the current rate limiter of **100 requests per 60 seconds** would reject 99,900+ users immediately. Even without the rate limiter, 100K simultaneous DB queries through a 20-connection HikariCP pool would cause massive queueing.

**Recommendation:** Build a dedicated `FlashSaleService` with:
- Redis-backed inventory counters with `DECR` for atomic stock decrement
- Pre-computed price snapshots loaded into cache before sale starts
- Separate, higher rate limits for flash sale endpoints
- Kafka-based async order processing to absorb the spike

---

### 3.5 Surge Pricing

**Verdict: 🔴 NOT SUPPORTED**

The `PriceRule.multiplier` field could theoretically support surge pricing (e.g., `multiplier=1.5` for 1.5x pricing), but there is:

| Requirement | Current State |
|-------------|---------------|
| Time-of-day pricing | ❌ No time-slot awareness |
| Demand-based dynamic pricing | ❌ No demand signal ingestion |
| Weather/event-based surging | ❌ No external signal integration |
| Surge caps (ethical guardrails) | ❌ `multiplier` can be set to 999.99 with no upper-bound validation |
| Customer-visible surge indicator | ❌ No surge flag in API response |
| Surge pricing history/audit | ❌ No audit trail |
| Geographic surge (per-zone) | ❌ No location awareness |
| Gradual ramp-up/ramp-down | ❌ Multiplier is a step function, not a curve |

**Ethical concern:** Without caps, an admin could set `multiplier=10.0` during a natural disaster. Competitors like Zepto cap surge at 1.5x–2x and display a "high demand" badge.

**Recommendation:**
- Add `surge_multiplier_cap` to `PricingProperties` (configurable, e.g., max 2.0x).
- Build a `SurgeService` that ingests demand signals (orders/minute per zone) and computes multiplier.
- Add `isSurgePrice: boolean` and `surgeMultiplier: BigDecimal` to `PriceCalculationResponse`.
- Regulatory: Some jurisdictions require disclosure of surge pricing to consumers.

---

### 3.6 Cart Pricing

**Location:** `PricingService.calculateCartPrice()`

**Flow:**
```
1. For each CartItem → look up active PriceRule → compute unit price × quantity
2. Sum into subtotal
3. Find best applicable promotion → compute discount
4. If coupon provided → validate → compute coupon discount (on subtotal - promotion discount)
5. Total = max(0, subtotal - promotionDiscount - couponDiscount)
```

**Issues Found:**

| # | Issue | Severity | Detail |
|---|-------|----------|--------|
| 1 | **No price locking between calculate and checkout** | 🔴 High | `calculateCartPrice()` is read-only. Between this call and actual order placement, prices can change. A 30-second cache TTL means a price could shift mid-checkout flow. |
| 2 | **No idempotency or price quote token** | 🔴 High | The response doesn't include a price quote ID or TTL. The caller has no way to "lock" the calculated price. Standard practice: return a signed price token valid for N minutes. |
| 3 | **Cart items are not batched efficiently** | ⚠️ Medium | Each `CartItem` does a separate `findActiveRuleByProductId()` query. For a cart of 20 items, that's 20 individual DB queries (mitigated by cache, but cold cache = 20 queries). Should use `WHERE product_id IN (...)` batch query. |
| 4 | **Coupon discount applied to post-promotion subtotal** | ✅ Good | Line 79: coupon discount is calculated on `subtotalCents - promotionDiscount`, which correctly prevents double-discounting. |
| 5 | **No delivery fee calculation** | ⚠️ Medium | Cart pricing doesn't include delivery fees. In Q-commerce, delivery fee is a critical part of the price (Zepto/Blinkit vary delivery fee by cart value, distance, surge). |
| 6 | **No tax calculation** | ⚠️ Medium | No GST/tax computation. May be handled by a separate service, but should be noted. |
| 7 | **Integer overflow risk** | ⚠️ Low | `unitPrice * item.quantity()` on line 58 is `long * int`. Safe for normal cases but theoretically could overflow for enormous quantities at high prices. |

**Recommendation — Price Quote Pattern:**
```java
// Return a signed, time-limited price quote
PriceQuote {
    String quoteId;         // UUID
    Instant expiresAt;      // now + 10 minutes
    String signature;       // HMAC of quote content
    long totalCents;
    // ... rest of price breakdown
}
// At checkout, order-service sends quoteId → pricing-service verifies signature + expiry
```

---

### 3.7 Price History & Audit

**Verdict: 🔴 NOT IMPLEMENTED**

| Requirement | Current State |
|-------------|---------------|
| Price change audit trail | ❌ No `price_history` or `price_audit` table |
| Who changed the price | ❌ `PriceRule` has `createdAt` but no `updatedAt`, no `updatedBy` |
| Previous price preservation | ❌ Old rules are soft-deleted via `active=false` but no explicit history |
| Promotion change history | ❌ No audit on promotion updates |
| Regulatory compliance | 🔴 **Risk** — EU Omnibus Directive and Indian Consumer Protection Act require showing pre-discount price and price history |

**Current audit coverage:**
- `PriceRule.createdAt` — records creation time only.
- `Promotion.createdAt` — records creation time only.
- `CouponRedemption.redeemedAt` — records redemption time ✅.
- Service-level `log.info()` calls — ephemeral, not queryable.
- `OutboxEvent` — records events but is designed for event publishing, not audit.

**Recommendation:**
```sql
CREATE TABLE price_audit_log (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    entity_type VARCHAR(30) NOT NULL,      -- PRICE_RULE, PROMOTION, COUPON
    entity_id UUID NOT NULL,
    action VARCHAR(20) NOT NULL,           -- CREATE, UPDATE, DELETE
    changed_by UUID NOT NULL,              -- admin user ID
    old_value JSONB,
    new_value JSONB NOT NULL,
    changed_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_price_audit_entity ON price_audit_log (entity_type, entity_id, changed_at);
```

---

## 4. SLA & Performance Review

### 4.1 Latency Analysis

**Target:** p99 < 50ms cached, < 200ms for calculation

| Path | Cached? | Estimated Latency | Assessment |
|------|---------|-------------------|------------|
| `GET /pricing/products/{id}` | ✅ Caffeine (30s TTL, 10K max) | **~2–5ms cached**, ~10–20ms uncached | ✅ Meets target |
| `POST /pricing/calculate` (cart) | ❌ Not cached as a whole | **50–200ms** depending on cart size | ⚠️ Borderline |
| Cart + coupon validation | ❌ Coupon validation hits DB | **100–300ms** with coupon | 🔴 May exceed 200ms |

**Bottlenecks:**
1. **N+1 query pattern in cart pricing** — Each cart item triggers a separate `findActiveRuleByProductId()`. For 15 items × ~5ms each = 75ms just for price lookups.
2. **Promotion query** — `findActivePromotions()` is cached (60s TTL), so this is fast when warm.
3. **Coupon validation** — Two DB queries: `findByCodeIgnoreCase()` + `countByCouponIdAndUserId()` = ~10–20ms.
4. **HikariCP pool: 20 connections** — Under 100+ concurrent cart calculations, connection wait time will spike.

**Recommendations:**
- Add a batch query `findActiveRulesByProductIds(Set<UUID> productIds, Instant now)` to eliminate N+1.
- Cache coupon validation results briefly (5s) for repeat cart recalculations.
- Increase HikariCP pool to 30–50 for production scale.
- Add connection pool metrics to monitoring.

### 4.2 Cache Invalidation

**Cache Configuration (CacheConfig.java):**

| Cache | Max Size | TTL | Eviction |
|-------|----------|-----|----------|
| `productPrices` | 10,000 | 30 seconds | Write-based |
| `activePromotions` | 500 | 60 seconds | Write-based |
| `priceRules` | 10,000 | 30 seconds | Write-based (unused — no `@Cacheable` references this) |

**Issues:**

| # | Issue | Severity |
|---|-------|----------|
| 1 | **`activePromotions` cache is keyed `'all'`** — the `findApplicable()` method caches ALL active promotions under one key, ignoring the `cartTotalCents` parameter. This means the cache returns all promotions regardless of cart total, relying on the `.filter()` stream. Functionally correct but wastes memory. | ⚠️ Low |
| 2 | **Promotion start/end is stale for up to 60 seconds** — When a promotion's `endAt` passes, it remains in cache for up to 60s. Users could see and attempt to use an expired promotion. The `findApplicable()` filter checks `Instant now`, but the cache key `'all'` means the cached list is fixed at the `now` that was used when the cache was populated. **The `now` parameter is baked into the cache.** | 🔴 High |
| 3 | **`priceRules` cache is defined but never used** — No `@Cacheable(value = "priceRules")` annotation exists in the codebase. Dead config. | ⚠️ Low |
| 4 | **No event-driven cache eviction** — When a promotion is created/updated/deleted, `@CacheEvict(value = "activePromotions", allEntries = true)` is triggered. But this is **local to the single instance**. In a multi-instance deployment, other instances' caches remain stale for up to 60s. | 🔴 High |
| 5 | **`productPrices` cache not evicted on price rule changes** — No admin endpoint to create/update price rules (only Kafka consumer creates them). When a price rule changes, the cache serves stale prices for up to 30s. | ⚠️ Medium |

**Recommendations:**
- Use Redis as a shared cache layer instead of local Caffeine for multi-instance consistency.
- Or add Kafka-based cache invalidation events between instances.
- Fix the `activePromotions` cache to not bake in the `now` parameter — fetch all, filter by current time on every call, cache only the list of promotions.
- Remove unused `priceRules` cache definition.

### 4.3 Concurrent Coupon Redemption — Locking Analysis

**Current Implementation:** No locking whatsoever.

```java
// redeemCoupon() — CouponService.java lines 80-112
@Transactional
public void redeemCoupon(String code, UUID userId, UUID orderId, long discountCents) {
    Coupon coupon = couponRepository.findByCodeIgnoreCase(code);  // No lock
    if (coupon.getTotalRedeemed() >= coupon.getTotalLimit())      // Check in app
        throw ...;
    coupon.setTotalRedeemed(coupon.getTotalRedeemed() + 1);       // Read-modify-write
    couponRepository.save(coupon);                                 // Flush
}
```

**Race Condition Scenario:**

```
T1: reads totalRedeemed = 99 (limit = 100)
T2: reads totalRedeemed = 99 (limit = 100)
T1: sets totalRedeemed = 100, saves ✅
T2: sets totalRedeemed = 100, saves ✅  ← OVERWRITES T1's write!
Result: 101 redemptions with limit 100, totalRedeemed shows 100 (lost update)
```

**Analysis of Locking Options:**

| Approach | Correctness | Performance | Recommendation |
|----------|-------------|-------------|----------------|
| **Current (no locking)** | ❌ Broken | Fast (no contention) | Unacceptable |
| **Pessimistic (`SELECT FOR UPDATE`)** | ✅ Correct | Serializes per-coupon; ~5ms lock hold | ✅ Best for correctness with moderate traffic |
| **Optimistic (`@Version`)** | ✅ Correct with retry | Fast path, retry on conflict | ✅ Good for low-contention coupons |
| **Atomic SQL (`UPDATE ... WHERE redeemed < limit`)** | ✅ Correct | Best performance; single round-trip | ✅ **Best overall** — no ORM overhead |

**Recommended Fix (Atomic SQL):**
```sql
UPDATE coupons SET total_redeemed = total_redeemed + 1
WHERE id = :couponId
  AND (total_limit IS NULL OR total_redeemed < total_limit);
-- If affected_rows = 0, limit was reached.
```

This eliminates the race entirely and is the most performant option.

---

## 5. Missing Features

### 5.1 Subscription Pricing

**Status: 🔴 Not implemented**

No concept of recurring subscriptions exists in the domain model. For Q-commerce:
- Weekly grocery subscriptions with 5–10% discount
- Monthly plans (e.g., Blinkit's "BBNow" membership)
- Auto-replenishment pricing

**Needed:**
- `SubscriptionPlan` entity (plan_id, frequency, discount_percent, product_ids)
- Integration with order-service for recurring order generation
- Price override logic: if user has active subscription for a product, apply subscription price

### 5.2 Membership Tiers

**Status: 🔴 Not implemented**

No user tier or membership concept. The `PriceCalculationRequest` accepts `userId` but it's only used for coupon per-user limits.

**What competitors offer:**

| Feature | Blinkit | Zepto | Instacart |
|---------|---------|-------|-----------|
| Free membership tier | ✅ | ✅ | ✅ |
| Paid membership (delivery savings) | ✅ BBNow | ✅ Zepto Pass | ✅ Instacart+ |
| Member-only pricing | ✅ | ✅ | ✅ |
| Member-only promotions | ✅ | ✅ | ✅ |
| Loyalty points/cashback | ❌ | ✅ | ✅ |

**Needed:**
- User membership lookup (call to user-service or receive via JWT claims)
- `MembershipDiscount` entity (tier, discount_percent, applies_to_delivery, applies_to_items)
- Promotion targeting: `promotion.requiredMembershipTier`

### 5.3 Location-Based Pricing

**Status: 🔴 Not implemented**

The domain model has no concept of location, city, zone, or warehouse. In Q-commerce, prices can vary by:
- **City** (metro vs tier-2 vs tier-3)
- **Dark store / warehouse** (different costs)
- **Delivery zone** (last-mile distance)
- **Pincode** (tax jurisdiction differences)

**Needed:**
- Add `zone_id` or `city_id` to `PriceRule`
- Add `zone_id` to `PriceCalculationRequest`
- Zone-specific promotions

### 5.4 A/B Pricing (Price Experimentation)

**Status: 🔴 Not implemented**

No feature flag integration. No experiment assignment. Critical for:
- Testing price elasticity
- Evaluating promotion effectiveness
- Dynamic pricing algorithm tuning

**Needed:**
- Integration with feature flag service (LaunchDarkly, Unleash, or custom)
- `experiment_id` and `variant` fields in `PriceCalculationResponse`
- Metrics emission per experiment variant for analysis

---

## 6. Q-Commerce Competitor Comparison

### vs. Blinkit

| Feature | Blinkit | InstaCommerce |
|---------|---------|---------------|
| ₹1 deals (loss-leader pricing) | ✅ Product-level override with zero/negative margin | ❌ Possible via `base_price_cents=1` but no margin-aware guardrails |
| Time-limited offers (10-min windows) | ✅ Dedicated flash sale engine | ❌ Only `effectiveFrom/To` on price rules |
| Member-only pricing (BBNow) | ✅ Tier-gated price rules | ❌ No membership concept |
| Category combos ("Buy 2 Dairy, Save ₹30") | ✅ Category-level conditional discounts | ❌ No category/product scoping on promotions |
| Wallet cashback | ✅ Post-order cashback credited to wallet | ❌ No wallet/cashback integration |

### vs. Zepto

| Feature | Zepto | InstaCommerce |
|---------|-------|---------------|
| Dynamic delivery fee (₹0 above ₹199, ₹25 otherwise) | ✅ Cart-value-based delivery fee tiers | ❌ No delivery fee logic |
| Surge pricing (rain/peak hours) | ✅ Demand-based multiplier with cap + "High demand" badge | ❌ Multiplier field exists but no automation or cap |
| Zepto Pass (free delivery subscription) | ✅ Subscription-based delivery fee waiver | ❌ No subscription pricing |
| Bank offer integration (10% off with HDFC) | ✅ Payment-method-specific discounts | ❌ No payment integration |

### vs. Instacart

| Feature | Instacart | InstaCommerce |
|---------|-----------|---------------|
| Coupon stacking (retailer + platform + manufacturer) | ✅ Multi-layer coupon stack with complex precedence | ❌ Single coupon per cart |
| Retailer-specific pricing | ✅ Different prices per retailer for same product | ❌ No multi-seller/retailer concept |
| Loyalty pricing (Instacart+) | ✅ Member-exclusive prices + 5% credit back | ❌ No membership |
| Item-level coupons | ✅ Coupons applicable to specific items/brands | ❌ Coupons are cart-wide only |
| Price comparison across retailers | ✅ Shows prices at multiple stores | ❌ Single-price model |

---

## 7. Security Review

**Strengths:**
- ✅ JWT-based authentication with RSA public key verification
- ✅ Role-based access: admin endpoints require `ROLE_ADMIN`
- ✅ Public pricing endpoints correctly permit unauthenticated access
- ✅ Rate limiting via Resilience4j on all endpoints
- ✅ Stateless session management
- ✅ Secrets from GCP Secret Manager
- ✅ Structured error responses (no stack traces leaked)
- ✅ Actuator endpoints properly exposed (health, metrics only)

**Issues:**

| # | Issue | Severity |
|---|-------|----------|
| 1 | **CORS allows all origins** (`setAllowedOriginPatterns(List.of("*"))` with `setAllowCredentials(true)`) | 🔴 High — credentials with wildcard origins is a security risk. Should be restricted to known domains. |
| 2 | **Pricing endpoints are fully public** — No authentication required for `/pricing/calculate` or `/pricing/products/{id}`. Competitors' pricing can be scraped. | ⚠️ Medium |
| 3 | **Rate limit of 100/60s is too low for production, too high for scraping protection** — At 20M users, legitimate traffic could easily exceed 100/60s. But for scraping, it's not granular enough (per-IP or per-user would be better). | ⚠️ Medium |
| 4 | **No input sanitization on coupon codes beyond `@NotBlank`** | ⚠️ Low — SQL injection is prevented by JPA, but XSS through error messages is possible if codes are echoed. |

---

## 8. Database Schema Review

### V1: `price_rules`
- ✅ Good: Partial index `WHERE active = true` on `(product_id, active)`
- ❌ Missing: No `priority` column
- ❌ Missing: No `updated_at`, `updated_by` columns
- ❌ Missing: No `category_id` or `zone_id` for scoped pricing
- ⚠️ No composite unique constraint to prevent duplicate active rules

### V2: `promotions`
- ✅ Good: Partial index on `(active, start_at, end_at) WHERE active = true`
- ❌ Missing: No `target_type` (ALL, PRODUCT, CATEGORY, BRAND) and `target_ids` columns
- ❌ Missing: No `stackable` flag
- ❌ Missing: No `user_segment` or `membership_tier` targeting
- ❌ Missing: No `updated_at`

### V3: `coupons` + `coupon_redemptions`
- ✅ Good: `code` has UNIQUE constraint
- ✅ Good: Per-user redemption tracking
- ❌ **Partial index `idx_coupon_single_use` uses subquery — invalid in PostgreSQL**. This should be rewritten as a static condition or handled via application logic.
- ❌ Missing: No `@Version` column on coupons for optimistic locking
- ❌ Missing: No `first_order_only` flag
- ❌ Missing: No `valid_from`/`valid_until` on coupon itself (relies on promotion dates)

### V4: `outbox_events`
- ✅ Good: JSONB payload, partial index on unsent events
- ❌ Missing: No outbox poller/publisher scheduled task. The `OutboxService.recordEvent()` exists but nothing reads and publishes them to Kafka. The outbox pattern is incomplete.

### V5: `shedlock`
- ✅ Standard ShedLock table. Good for distributed scheduling.
- ❌ But no scheduled tasks are defined anywhere in the codebase. ShedLock is configured but unused.

---

## 9. Prioritized Action Items

### 🔴 P0 — Fix Before Going Live

| # | Action | Effort | Impact |
|---|--------|--------|--------|
| 1 | **Fix coupon redemption race condition** — Add `SELECT FOR UPDATE` or atomic SQL update on `coupons.total_redeemed` | 2 hours | Prevents financial loss from over-redemption |
| 2 | **Fix promotion "best discount" selection** — Compare calculated discount amounts, not raw `discount_value` | 1 hour | Prevents customers from getting wrong (smaller) discount |
| 3 | **Fix `activePromotions` cache staleness** — Cache the promotion list without baking in `Instant now`; filter by time on every call | 2 hours | Prevents expired promotions from being applied |
| 4 | **Fix CORS** — Restrict allowed origins to actual frontend domains | 30 min | Security vulnerability |
| 5 | **Fix partial index on `coupon_redemptions`** — PostgreSQL doesn't support subqueries in partial index WHERE clause; rewrite or use trigger-based enforcement | 1 hour | May silently fail at migration |

### 🟡 P1 — Required for Q-Commerce Launch

| # | Action | Effort | Impact |
|---|--------|--------|--------|
| 6 | Add price quote / price lock mechanism (signed token with TTL) | 3 days | Prevents price changes during checkout |
| 7 | Add price audit trail table + JPA entity listeners | 2 days | Regulatory compliance |
| 8 | Implement batch price rule lookup (`IN` clause) for cart pricing | 4 hours | Reduce cart pricing latency by 5–10x |
| 9 | Add product/category scoping to promotions | 3 days | Enable targeted promotions |
| 10 | Implement BOGO and buy-X-get-Y promotion types | 3 days | Table-stakes for Q-commerce |
| 11 | Add delivery fee calculation | 2 days | Core Q-commerce functionality |
| 12 | Multi-instance cache invalidation (Redis or Kafka events) | 3 days | Required for horizontal scaling |
| 13 | Complete the outbox pattern — add scheduled publisher | 1 day | Events are recorded but never published |

### 🟢 P2 — Competitive Feature Parity

| # | Action | Effort | Impact |
|---|--------|--------|--------|
| 14 | Surge pricing engine with demand signals and ethical caps | 2 weeks | Revenue optimization |
| 15 | Flash sale infrastructure (Redis counters, pre-warmed cache) | 2 weeks | Key growth driver |
| 16 | Membership tier pricing | 1 week | Monetization lever |
| 17 | Location-based pricing (zone/city) | 1 week | Market expansion |
| 18 | Subscription/recurring order pricing | 1 week | Retention lever |
| 19 | Coupon stacking rules (stackable/exclusive flags) | 3 days | Promotion flexibility |
| 20 | A/B pricing with feature flag integration | 1 week | Data-driven optimization |
| 21 | Bank/payment-method-specific offers | 1 week | Partnership revenue |
| 22 | User segment targeting (new user, dormant, high-value) | 1 week | Personalization |

---

## Appendix A: File-by-File Observations

<details>
<summary>Click to expand full file notes</summary>

### Domain Layer

| File | Notes |
|------|-------|
| `PriceRule.java` | No `@Version`, no `updatedAt`/`updatedBy`. `ruleType` is String, not enum. `multiplier` DECIMAL(5,2) limits precision. |
| `Promotion.java` | `currentUses` is never incremented. `discountType` is String. No product/category targeting. |
| `Coupon.java` | No `@Version` for optimistic locking. `totalRedeemed` is int (max 2.1B — fine). No own validity dates. |
| `CouponRedemption.java` | Clean. Properly tracks per-redemption data. |
| `OutboxEvent.java` | Standard outbox entity. No TTL or cleanup mechanism. |

### Service Layer

| File | Notes |
|------|-------|
| `PricingService.java` | N+1 query in cart pricing. No price locking. Good use of `RoundingMode.HALF_UP`. |
| `PromotionService.java` | Cache keyed on `'all'` with baked-in `now`. Best-promo selection is wrong. `currentUses` never updated. |
| `CouponService.java` | **Race condition on redemption.** Good validation flow otherwise. |
| `OutboxService.java` | Records events but nothing publishes them. Incomplete pattern. |

### Controller Layer

| File | Notes |
|------|-------|
| `PricingController.java` | Clean. Rate-limited. Returns correct DTOs. |
| `AdminPromotionController.java` | Proper `@PreAuthorize("hasRole('ADMIN')")`. Full CRUD. Soft-delete. |
| `AdminCouponController.java` | No update endpoint (only create + deactivate). May need update for `totalLimit` changes. |

### Repository Layer

| File | Notes |
|------|-------|
| `PriceRuleRepository.java` | `LIMIT 1` in JPQL — non-standard, may not work on all JPA providers. Use `FETCH FIRST 1 ROWS ONLY` or `Pageable`. |
| `PromotionRepository.java` | Orders by `discount_value DESC` — wrong metric for "best" promotion. |
| `CouponRepository.java` | Case-insensitive lookup is good. No `@Lock` variant for concurrent access. |
| `CouponRedemptionRepository.java` | Clean count queries. |

### Config Layer

| File | Notes |
|------|-------|
| `CacheConfig.java` | `priceRules` cache is defined but never used. Local Caffeine only — no distributed cache. |
| `KafkaConfig.java` | Dead letter topic routing is good. 3 retries with 1s backoff — reasonable. |
| `SchedulerConfig.java` | ShedLock configured but no `@Scheduled` methods exist in codebase. |
| `SecurityConfig.java` | CORS wildcard with credentials — security risk. Otherwise solid. |
| `PricingProperties.java` | Only JWT config. No pricing-specific properties (surge cap, cache TTLs, etc.). |

### Consumer Layer

| File | Notes |
|------|-------|
| `CatalogEventConsumer.java` | Creates base price rules with `basePriceCents=0` for new products. Admin must then set actual price. No cache eviction triggered. |

### Security Layer

| File | Notes |
|------|-------|
| `JwtAuthenticationFilter.java` | Solid implementation. Skips actuator/error paths. Clears context on failure. |
| `DefaultJwtService.java` | Handles `roles` as both List and String. Proper ROLE_ prefix normalization. |
| `JwtKeyLoader.java` | Handles PEM and raw Base64 keys. Good fallback to URL-safe decoder. |

### Migration Files

| File | Notes |
|------|-------|
| `V1__create_price_rules.sql` | Missing `priority`, `category_id`, `zone_id`, `updated_at` |
| `V2__create_promotions.sql` | Missing targeting columns, stackability flag |
| `V3__create_coupons.sql` | Partial index with subquery is invalid PostgreSQL syntax |
| `V4__create_outbox_events.sql` | No TTL/cleanup. Will grow unbounded. |
| `V5__create_shedlock.sql` | Standard. Correct. |

</details>

---

## Appendix B: Rate Limiter Capacity Analysis

Current config:
- `pricingLimiter`: 100 requests / 60 seconds = **1.67 RPS**
- `adminLimiter`: 50 requests / 60 seconds = **0.83 RPS**

At 20M users with 2% daily active and 5 price lookups per session:
- DAU: 400,000
- Price lookups/day: 2,000,000
- Peak hour (10% of daily): 200,000 / 3600 = **~56 RPS**

**The current rate limiter would reject 97% of legitimate traffic at peak.** These limits need to be increased to at least 500–1000 per 60s per instance, or moved to per-user/per-IP rate limiting.

---

*Review complete. The service provides a solid foundation but requires significant hardening for a production Q-commerce platform at scale. The P0 items (especially the coupon race condition and cache staleness) are blocking issues that must be fixed before any production deployment.*
