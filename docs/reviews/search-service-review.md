# Search Service — Deep Architecture & Code Review

> **Reviewer:** Senior Search & Discovery Engineer  
> **Service:** `services/search-service/`  
> **Date:** 2025-07-02  
> **Risk Level:** 🔴 HIGH — This service handles 70%+ of all traffic for a 20M+ user Q-commerce platform  
> **Overall Verdict:** Solid MVP foundation, but **not production-ready for Q-commerce scale**. Requires fundamental changes before 100K+ QPS peak load.

---

## Table of Contents

1. [Executive Summary](#1-executive-summary)
2. [Architecture Overview](#2-architecture-overview)
3. [Business Logic Review](#3-business-logic-review)
4. [SLA & Performance Review](#4-sla--performance-review)
5. [OpenSearch Migration Readiness](#5-opensearch-migration-readiness)
6. [Q-Commerce Competitive Gap Analysis](#6-q-commerce-competitive-gap-analysis)
7. [Security Review](#7-security-review)
8. [Prioritized Remediation Roadmap](#8-prioritized-remediation-roadmap)
9. [File-by-File Findings Index](#9-file-by-file-findings-index)

---

## 1. Executive Summary

### What's Good ✅
- Clean Spring Boot 3 / Java 21 architecture with proper layering
- Kafka-driven near-real-time indexing pipeline from catalog events
- Caffeine caching with per-cache TTL configuration
- ShedLock for distributed scheduled task coordination
- Proper GIN index on `tsvector` column with weighted fields (A/B/C)
- Graceful shutdown, health probes, OpenTelemetry tracing
- ZGC garbage collector choice (low-latency appropriate)
- DLQ (Dead Letter Queue) error handling for Kafka consumers
- Flyway migrations for schema management

### What's Critically Missing 🔴
| Gap | Impact | Competitor Benchmark |
|-----|--------|---------------------|
| No fuzzy matching / typo tolerance | Users searching "choclate" get zero results | Zepto/Blinkit: fuzzy via OpenSearch |
| No synonym handling | "curd" vs "dahi" vs "yogurt" — all different queries | Instacart: synonym dictionaries |
| No Hindi/regional language support | `to_tsvector('english', ...)` hardcoded — excludes ~60% of India | Blinkit: multilingual search |
| No stock-aware ranking | Out-of-stock items ranked equally with in-stock | Zepto: `availability × relevance × popularity` |
| No popularity/sales-velocity boosting | New products rank same as bestsellers | All competitors: popularity signals |
| No personalization | No user history, no reorder boost, no affinity | Blinkit: "reorder" integration |
| No spell correction / "did you mean" | Zero-results page has no recovery path | Industry standard feature |
| No SearchProvider abstraction | PostgreSQL tightly coupled — migration is a rewrite | Instacart: pluggable backends |
| ILIKE autocomplete (no trigram index) | Full table scan on `name` column at scale | All competitors: prefix trie / OpenSearch |
| Synchronous trending writes in search path | DB write on every search query — latency killer | Should be async/fire-and-forget |
| HikariCP pool size = 20 | Dangerously low for 70% traffic share service | Should be 50-100 for search workload |

### Severity Counts
- 🔴 **CRITICAL (P0):** 7 findings
- 🟠 **HIGH (P1):** 8 findings
- 🟡 **MEDIUM (P2):** 6 findings
- 🔵 **LOW (P3):** 4 findings

---

## 2. Architecture Overview

### Tech Stack
| Component | Technology |
|-----------|-----------|
| Runtime | Java 21, Spring Boot 3.x |
| Search Engine | PostgreSQL `tsvector` (full-text) + `ILIKE` (autocomplete) |
| Database | PostgreSQL via HikariCP (pool=20) |
| Messaging | Apache Kafka (catalog.events topic) |
| Caching | Caffeine (in-process, not distributed) |
| Scheduling | ShedLock + Spring @Scheduled |
| Observability | Micrometer + OpenTelemetry + Logstash JSON |
| Auth | JWT (RSA, asymmetric verification) |
| Migration | Flyway |
| Container | Eclipse Temurin 21 + Alpine, ZGC |

### File Structure (31 files)
```
src/main/
├── java/com/instacommerce/search/
│   ├── SearchServiceApplication.java
│   ├── config/
│   │   ├── CacheConfig.java          # Caffeine caches: searchResults, autocomplete, trending
│   │   ├── KafkaConfig.java          # Consumer factory + DLQ error handler
│   │   ├── SearchProperties.java     # JWT config properties
│   │   ├── SecurityConfig.java       # Stateless JWT auth, CORS
│   │   └── ShedLockConfig.java       # Distributed lock for scheduled tasks
│   ├── controller/
│   │   └── SearchController.java     # GET /search, /search/autocomplete, /search/trending
│   ├── domain/model/
│   │   ├── SearchDocument.java       # JPA entity → search_documents table
│   │   └── TrendingQuery.java        # JPA entity → trending_queries table
│   ├── dto/
│   │   ├── AutocompleteResult.java   # suggestion, category, productId
│   │   ├── ErrorDetail.java
│   │   ├── ErrorResponse.java
│   │   ├── FacetValue.java           # value, count
│   │   ├── SearchRequest.java        # Validated request record
│   │   ├── SearchResponse.java       # results, totalResults, page, totalPages, facets
│   │   └── SearchResult.java         # productId, name, brand, category, price, inStock, score
│   ├── exception/
│   │   ├── ApiException.java
│   │   ├── GlobalExceptionHandler.java
│   │   └── TraceIdProvider.java
│   ├── kafka/
│   │   └── CatalogEventConsumer.java # Handles ProductCreated/Updated/Delisted
│   ├── repository/
│   │   ├── SearchDocumentRepository.java  # Full-text search, autocomplete, facets
│   │   └── TrendingQueryRepository.java   # Trending query CRUD
│   ├── security/
│   │   ├── DefaultJwtService.java
│   │   ├── JwtAuthenticationFilter.java
│   │   ├── JwtKeyLoader.java
│   │   ├── JwtService.java           # Interface
│   │   ├── RestAccessDeniedHandler.java
│   │   └── RestAuthenticationEntryPoint.java
│   └── service/
│       ├── SearchIndexService.java    # Upsert/delete search documents
│       ├── SearchService.java         # Core search, autocomplete, faceting
│       └── TrendingService.java       # Record queries, get trending, cleanup
└── resources/
    ├── application.yml
    └── db/migration/
        ├── V1__create_search_documents.sql   # tsvector + GIN index + trigger
        ├── V2__create_trending_queries.sql   # Trending queries table
        └── V3__create_shedlock.sql           # Distributed lock table
```

---

## 3. Business Logic Review

### 3.1 Full-Text Search

**File:** `SearchDocumentRepository.java` (lines 21-46), `V1__create_search_documents.sql`

**Implementation:**
```sql
-- Trigger-based tsvector generation
NEW.search_vector :=
    setweight(to_tsvector('english', COALESCE(NEW.name, '')), 'A') ||
    setweight(to_tsvector('english', COALESCE(NEW.brand, '')), 'B') ||
    setweight(to_tsvector('english', COALESCE(NEW.category, '')), 'B') ||
    setweight(to_tsvector('english', COALESCE(NEW.description, '')), 'C');
```

```sql
-- Query using plainto_tsquery
WHERE sd.search_vector @@ plainto_tsquery('english', :query)
ORDER BY ts_rank(sd.search_vector, plainto_tsquery('english', :query)) DESC
```

#### 🔴 CRITICAL: Hardcoded 'english' Language Configuration

The `to_tsvector('english', ...)` and `plainto_tsquery('english', ...)` calls are hardcoded to English. For an Indian Q-commerce platform:

- **Hindi queries** ("दूध", "आटा", "चीनी") will produce empty tsvectors since PostgreSQL's `english` dictionary has no Hindi stemmer or stop-word list.
- **Hinglish queries** ("atta", "dahi", "paneer") may partially work as they pass through as unstemmed terms, but miss stemming benefits.
- **Regional languages** (Tamil, Telugu, Bengali, Marathi) — completely unsupported.
- PostgreSQL has **no built-in Hindi text search configuration**. This requires `unaccent` + custom dictionaries or migration to OpenSearch with ICU analysis.

**Recommendation:**
```sql
-- Short-term: Use 'simple' config as fallback for non-English
setweight(to_tsvector('simple', COALESCE(NEW.name, '')), 'A')
-- Long-term: Migrate to OpenSearch with ICU tokenizer + language-specific analyzers
```

#### 🟠 HIGH: No Fuzzy Matching / Typo Tolerance

`plainto_tsquery` does exact stem matching. Common grocery typos like:
- "choclate" → "chocolate" ❌ zero results
- "biscit" → "biscuit" ❌ zero results
- "amul" → "Amul" ✅ (case-insensitive by default in tsvector)

PostgreSQL offers `pg_trgm` extension with `similarity()` and `%` operator, but it's not used here. Even with it, fuzzy matching at scale is expensive in PostgreSQL — this is where OpenSearch/Elasticsearch excels.

#### 🟠 HIGH: No Synonym Handling

There is no synonym dictionary configured. In Q-commerce:
- "curd" = "dahi" = "yogurt"
- "cooking oil" = "tel" = "refined oil"
- "chips" = "wafers" = "crisps"

PostgreSQL supports `thesaurus` dictionaries in tsvector configs, but none are configured. OpenSearch synonym filters are far more maintainable (hot-reload without schema changes).

#### 🟡 MEDIUM: No Stemming Verification for Grocery Domain

The English Snowball stemmer works for general text but may not stem grocery-specific terms correctly. For example, "biscuits" → "biscuit" ✅, but "ghee" → "ghee" (no stemming needed, but no synonym to "clarified butter"). Domain-specific testing is needed.

#### 🟡 MEDIUM: `plainto_tsquery` vs `websearch_to_tsquery`

`plainto_tsquery` treats all words as AND. This means:
- "red bull" → must match BOTH "red" AND "bull"
- Better: `websearch_to_tsquery` (PG 11+) supports quoted phrases and OR logic naturally

```sql
-- Current (all AND):
plainto_tsquery('english', 'organic milk chocolate')
-- Better (supports "organic milk" OR chocolate):
websearch_to_tsquery('english', '"organic milk" OR chocolate')
```

### 3.2 Autocomplete

**File:** `SearchDocumentRepository.java` (lines 48-56)

```sql
SELECT sd.name AS suggestion, sd.category, sd.product_id
FROM search_documents sd
WHERE sd.name ILIKE :prefix || '%'
  AND sd.in_stock = TRUE
ORDER BY sd.name
LIMIT :limit
```

#### 🔴 CRITICAL: `ILIKE` Without Trigram Index — Full Table Scan

`ILIKE` with `prefix%` pattern can use a B-tree index with `text_pattern_ops`, but there is **no such index** in the migration. The existing `idx_search_documents_category` and `idx_search_documents_brand` indexes don't help here.

This is a **sequential scan** on `search_documents.name` for every autocomplete keystroke. At scale:
- 1M products → ~200ms+ per query (unacceptable for autocomplete)
- 10M products → seconds

**Required index (minimum):**
```sql
CREATE INDEX idx_search_documents_name_pattern 
    ON search_documents (name text_pattern_ops);
-- Or for case-insensitive:
CREATE INDEX idx_search_documents_name_trgm 
    ON search_documents USING GIN (name gin_trgm_ops);
```

#### 🟠 HIGH: No Ranking or Popularity in Autocomplete Suggestions

Autocomplete results are sorted alphabetically (`ORDER BY sd.name`). This means:
- Searching "mi" returns "Millet Flour" before "Milk" (even though milk has 1000x more searches)
- No popularity signal, no sales velocity, no trending boost

**Competitor approach:**
```sql
ORDER BY popularity_score DESC, sd.name ASC
```

#### 🟡 MEDIUM: No Deduplication in Autocomplete

If 50 products are named "Amul Butter 500g" with slight variations, autocomplete returns all 50. Should deduplicate by category or return category-level suggestions.

#### 🟡 MEDIUM: No Personalization Hooks

No user ID in autocomplete query. No ability to boost "previously ordered" or "frequently searched" items. Blinkit's autocomplete prominently features "reorder" suggestions.

### 3.3 Faceted Search

**File:** `SearchDocumentRepository.java` (lines 58-74), `SearchService.java` (lines 66-82)

#### 🔴 CRITICAL: Facet Counts Ignore Active Filters

The `facetByBrand` and `facetByCategory` queries only apply the text search filter, **not** the brand/category/price filters that the user has already applied:

```java
// SearchService.java line 46 - facets built with ONLY the query, not the filters
Map<String, List<FacetValue>> facets = buildFacets(query);
```

```sql
-- facetByBrand only filters by search_vector, not by category/price
SELECT sd.brand, COUNT(*) AS cnt
FROM search_documents sd
WHERE sd.search_vector @@ plainto_tsquery('english', :query)
GROUP BY sd.brand
```

**Impact:** When a user searches "milk" and filters by category="Dairy", the brand facet still shows counts across ALL categories. This is a UX data integrity issue — users see inflated counts that don't match actual results.

**Fix:** Pass all active filters to facet queries (standard faceted search behavior).

#### 🟠 HIGH: No Price Range Facet

There's brand and category faceting, but no price range facets (e.g., "₹0-100", "₹100-500", "₹500+"). Price range faceting is standard in e-commerce search.

#### 🟠 HIGH: 3 Separate Database Queries per Search Request

Each search call executes:
1. `fullTextSearch()` — main results
2. `facetByBrand()` — brand facets
3. `facetByCategory()` — category facets

That's **3 round-trips to PostgreSQL per search request**. At 70% traffic share, this triples the database load. These should be combined into a single query or computed server-side from the result set.

```sql
-- Single query approach (PostgreSQL):
SELECT 
    sd.*,
    ts_rank(sd.search_vector, q) AS rank,
    COUNT(*) OVER (PARTITION BY sd.brand) as brand_count,
    COUNT(*) OVER (PARTITION BY sd.category) as category_count
FROM search_documents sd, plainto_tsquery('english', :query) q
WHERE sd.search_vector @@ q
```

### 3.4 Trending Queries

**File:** `TrendingService.java`, `TrendingQueryRepository.java`, `V2__create_trending_queries.sql`

#### 🔴 CRITICAL: Synchronous DB Write on Every Search Query

```java
// SearchService.java line 49 — called inside the search() method
trendingService.recordQuery(query);
```

```java
// TrendingService.java — @Transactional, does SELECT + INSERT/UPDATE
@Transactional
@CacheEvict(value = "trending", allEntries = true)
public void recordQuery(String query) {
    trendingQueryRepository.findByQuery(normalized).ifPresentOrElse(
        TrendingQuery::incrementHitCount,
        () -> trendingQueryRepository.save(new TrendingQuery(normalized)));
}
```

**Problems:**
1. **Synchronous write in the search hot path** — adds DB latency to every search query
2. **SELECT + UPDATE pattern is not atomic** — race condition under concurrent load (two threads both SELECT, both see hitCount=5, both UPDATE to 6 instead of 7)
3. **`@CacheEvict(allEntries = true)` on every search** — invalidates the entire trending cache on every single query, making the cache nearly useless
4. **No batching** — every keystroke that triggers search writes to DB

**Fix (immediate):**
```java
// Use async fire-and-forget
@Async
public void recordQuery(String query) { ... }

// Use INSERT ON CONFLICT for atomic upsert
INSERT INTO trending_queries (query, hit_count, last_searched_at)
VALUES (:query, 1, now())
ON CONFLICT (query) DO UPDATE SET 
    hit_count = trending_queries.hit_count + 1,
    last_searched_at = now();
```

**Fix (proper):** Buffer queries in-memory (ConcurrentHashMap) and flush batch every 30s.

#### 🟡 MEDIUM: Trending Algorithm Is Just "All-Time Hit Count"

The trending query is `ORDER BY hit_count DESC` — this is a popularity leaderboard, not "trending." A query searched 1M times over 2 years will always beat a query searched 10K times in the last hour.

**Proper trending algorithm:**
```
trending_score = recent_count(24h) / (baseline_count(30d) / 30 + 1)
```
This surfaces spikes relative to baseline — actual trending behavior.

#### 🟡 MEDIUM: Privacy Concern — Raw Queries Stored Indefinitely

The `trending_queries` table stores raw search queries with no anonymization. While cleanup runs at 30 days, there are no PII scrubbing measures. If a user searches for personal/medical terms, these are stored in plaintext. Consider hashing or using only normalized, common queries.

### 3.5 Search Ranking

**File:** `SearchDocumentRepository.java` (lines 21-29)

#### 🔴 CRITICAL: Ranking Is Pure Text Relevance — No Business Signals

Current ranking: `ORDER BY ts_rank(search_vector, query) DESC`

This is purely text-relevance based. Critical missing signals for Q-commerce:

| Signal | Current | Required | Competitor |
|--------|---------|----------|------------|
| Text relevance | ✅ ts_rank | ✅ | All |
| Stock availability | ❌ | Boost in-stock by 10x | Zepto: hard filter + boost |
| Popularity/sales velocity | ❌ | `log(orders_last_7d + 1)` | All competitors |
| Recency (new products) | ❌ | Slight boost for 7-day new items | Blinkit |
| Price competitiveness | ❌ | Optional | Instacart |
| Margin/sponsored | ❌ | Business requirement | All (ads revenue) |
| Personalization | ❌ | User purchase history affinity | Blinkit, Instacart |

**Minimum viable ranking formula for Q-commerce:**
```sql
ORDER BY (
    ts_rank(sd.search_vector, q) * 0.3 +
    CASE WHEN sd.in_stock THEN 1.0 ELSE 0.0 END * 0.4 +
    COALESCE(log(sd.orders_last_7d + 1) / 5.0, 0) * 0.3
) DESC
```

#### 🟠 HIGH: Out-of-Stock Items Not Filtered or Demoted

The search query has no stock filter. Users will see out-of-stock products in results, which is a terrible Q-commerce UX where delivery promise is 10-30 minutes. In-stock items must either be filtered or heavily demoted.

### 3.6 Zero-Results Handling

#### 🔴 CRITICAL: No Zero-Results Fallback Strategy

There is **zero** handling for the "no results" scenario:

```java
// SearchService.java — returns empty list, no fallback
Page<Object[]> results = searchDocumentRepository.fullTextSearch(...);
// If results.isEmpty() → returns SearchResponse with empty results list
```

**Missing features:**
1. **No spell correction** — "chcolate" returns nothing, no "did you mean: chocolate?"
2. **No fallback to partial matching** — "organic almond milk chocolate" (4 terms AND'd) may return nothing; should fallback to best partial match
3. **No category fallback** — if searching "blue Bluetooth speaker" in a grocery app, should suggest "We don't carry electronics" or show trending items
4. **No related/popular items** — competitors show "Popular in Dairy" when dairy search returns 0

**Implementation approach:**
```java
if (results.isEmpty()) {
    // 1. Try fuzzy match with pg_trgm
    // 2. Try individual terms separately
    // 3. Return trending items as fallback
    // 4. Include "did you mean" suggestions
}
```

---

## 4. SLA & Performance Review

### 4.1 Query Latency — p99 < 200ms Target

**Verdict:** 🟠 **Achievable at small scale, will fail at 20M users**

| Factor | Assessment |
|--------|-----------|
| GIN index on tsvector | ✅ Properly indexed, supports fast @@ matching |
| `ts_rank()` computation | ⚠️ Computed per-row in result set — CPU intensive at high cardinality |
| 3 queries per search | 🔴 3× round-trip latency; p99 = max(p99_search, p99_brand_facet, p99_category_facet) |
| Caffeine cache | ✅ Helps for repeated queries (5-min TTL) |
| No query timeout | 🔴 No `statement_timeout` configured — one slow query blocks the connection pool |
| Connection pool = 20 | 🔴 Under peak load (10K QPS), pool exhaustion causes request queuing |

**Estimates:**
- **Cold query, 1M products:** ~50-100ms for text search + 50ms × 2 facets = 150-200ms ✅
- **Cold query, 10M products:** ~200-500ms for text search + 100ms × 2 facets = 400-700ms ❌
- **With cache hit:** <5ms ✅ (but cache evicted on every search due to trending CacheEvict bug)

### 4.2 Autocomplete Latency — p99 < 100ms Target

**Verdict:** 🔴 **Will fail without index**

```sql
-- Current: ILIKE without supporting index
WHERE sd.name ILIKE :prefix || '%'
```

- **1M products, no index:** ~100-300ms (sequential scan) ❌
- **1M products, with `text_pattern_ops` index:** ~5-20ms ✅
- **10M products, no index:** ~1-3 seconds ❌

The 2-minute Caffeine cache helps for repeated prefixes, but first-keystroke latency for any new prefix is unacceptable.

### 4.3 Indexing Pipeline Latency

**File:** `CatalogEventConsumer.java`, `SearchIndexService.java`

**Verdict:** ✅ **Near-real-time via Kafka (seconds)**

The Kafka consumer processes `ProductCreated`, `ProductUpdated`, `ProductDelisted` events and upserts/deletes documents synchronously. This is good:

| Aspect | Assessment |
|--------|-----------|
| Event-driven indexing | ✅ Near-real-time via Kafka |
| DLQ for failed events | ✅ `DeadLetterPublishingRecoverer` with 3 retries |
| Retry backoff | ⚠️ Fixed 1s backoff, not exponential |
| Batch processing | ❌ Events processed one-by-one, not batched |
| tsvector trigger | ✅ Automatic via PostgreSQL trigger on INSERT/UPDATE |
| Cache eviction | ✅ `@CacheEvict` on upsert/delete |

**Concern:** `@CacheEvict(value = {"searchResults", "autocomplete"}, allEntries = true)` in `SearchIndexService` evicts ALL search and autocomplete caches on any single product update. During a bulk catalog import (10K products), this means 10K full cache invalidations. Should use fine-grained eviction or a short TTL approach.

### 4.4 Connection Pooling

**File:** `application.yml` (lines 16-20)

```yaml
hikari:
  maximum-pool-size: 20
  minimum-idle: 5
  connection-timeout: 5000
  max-lifetime: 1800000
```

#### 🔴 CRITICAL: Pool Size = 20 Is Dangerously Low

This service handles **70%+ of all traffic**. Quick math:
- 20M users, assume 5% daily active = 1M DAU
- Peak QPS = ~10K-50K (Q-commerce peak during meals/evenings)
- 70% → 7K-35K search QPS
- Each search = 3 DB queries (search + 2 facets) = 21K-105K DB queries/sec
- 20 connections × avg query time 50ms = 400 queries/sec throughput max

**20 connections can only handle ~400 QPS** — the service will hit pool exhaustion and start queuing/timing out under any real load.

**Recommendations:**
```yaml
hikari:
  maximum-pool-size: 50      # Minimum for search service
  minimum-idle: 20            # Keep warm connections ready
  connection-timeout: 3000    # Fail fast, don't wait 5s
  max-lifetime: 1800000       # OK (30 min)
  leak-detection-threshold: 60000  # Add leak detection
```

Also critically missing:
- **No `statement_timeout`** — a runaway query can hold a connection forever
- **No read-replica configuration** — all search traffic hits the primary database

```yaml
# Add to application.yml for safety:
spring.datasource.hikari.data-source-properties:
  options: "-c statement_timeout=5000"  # 5s max query time
```

---

## 5. OpenSearch Migration Readiness

### 5.1 Abstraction Layer

**Verdict:** 🔴 **No abstraction — tightly coupled to PostgreSQL**

There is **no `SearchProvider` interface**. The service layer directly calls `SearchDocumentRepository` (Spring Data JPA) which contains native PostgreSQL queries:

```java
// SearchService.java — direct JPA repository calls
searchDocumentRepository.fullTextSearch(query, brand, category, ...);
searchDocumentRepository.autocomplete(prefix, limit);
searchDocumentRepository.facetByBrand(query);
```

Migrating to OpenSearch requires:

1. **Defining a `SearchProvider` interface:**
```java
public interface SearchProvider {
    SearchResponse search(SearchQuery query);
    List<AutocompleteResult> autocomplete(String prefix, int limit);
    Map<String, List<FacetValue>> facets(String query, SearchFilters filters);
}
```

2. **Implementing `PostgresSearchProvider` (current logic)**
3. **Implementing `OpenSearchProvider` (new)**
4. **Feature-flag based routing between providers**

**Effort estimate:** 2-3 sprints to add abstraction + OpenSearch implementation.

### 5.2 Index Schema Translation to OpenSearch

**Current PostgreSQL schema → OpenSearch mapping:**

```json
{
  "mappings": {
    "properties": {
      "product_id": { "type": "keyword" },
      "name": {
        "type": "text",
        "analyzer": "standard",
        "fields": {
          "keyword": { "type": "keyword" },
          "autocomplete": {
            "type": "text",
            "analyzer": "autocomplete_analyzer"
          },
          "hindi": {
            "type": "text",
            "analyzer": "hindi_analyzer"
          }
        }
      },
      "description": { "type": "text" },
      "brand": {
        "type": "text",
        "fields": { "keyword": { "type": "keyword" } }
      },
      "category": {
        "type": "text",
        "fields": { "keyword": { "type": "keyword" } }
      },
      "price_cents": { "type": "long" },
      "image_url": { "type": "keyword", "index": false },
      "in_stock": { "type": "boolean" },
      "popularity_score": { "type": "float" },
      "created_at": { "type": "date" },
      "updated_at": { "type": "date" }
    }
  }
}
```

**Schema translates well** — the data model is simple and maps cleanly. The main additions for OpenSearch would be:
- Multi-field mappings for autocomplete (edge n-gram analyzer)
- Keyword sub-fields for aggregations (facets)
- `popularity_score` field (missing from current schema)
- Hindi/regional language analyzers

### 5.3 What PostgreSQL tsvector Cannot Do

| Capability | PostgreSQL tsvector | OpenSearch/Elasticsearch |
|-----------|-------------------|------------------------|
| Fuzzy matching (Levenshtein) | ❌ Not built-in (requires `pg_trgm`) | ✅ `fuzziness: AUTO` |
| Phonetic matching | ❌ No phonetic plugin | ✅ Phonetic token filter |
| Synonym expansion at query time | ❌ Must rebuild tsvector | ✅ Synonym token filter (hot-reload) |
| Multi-language analysis | ⚠️ Limited built-in configs | ✅ ICU analysis, language-specific |
| Custom scoring functions | ⚠️ Basic `ts_rank` only | ✅ `function_score`, `script_score` |
| Nested/hierarchical facets | ❌ Manual SQL | ✅ Native aggregations |
| "Did you mean" suggestions | ❌ Not built-in | ✅ `suggest` API |
| Autocomplete (edge n-gram) | ❌ Must use `pg_trgm` | ✅ Completion suggester |
| Geo-aware search | ⚠️ PostGIS extension | ✅ Built-in geo queries |
| Horizontal scaling | ❌ Single-node read replicas | ✅ Sharding across nodes |
| Percolator (reverse search) | ❌ | ✅ Alert on new matching products |
| A/B testing ranking models | ❌ Complex | ✅ Named queries, explain API |
| Vector/semantic search | ❌ (pgvector exists but different) | ✅ k-NN, neural search |

### 5.4 OpenSearch Migration Strategy Recommendation

```
Phase 1 (2 weeks): Add SearchProvider interface + refactor
Phase 2 (3 weeks): Implement OpenSearch provider with dual-write
Phase 3 (2 weeks): Shadow traffic testing — run both, compare results
Phase 4 (1 week):  Feature-flag cutover with instant rollback
Phase 5 (ongoing): Add fuzzy, synonyms, Hindi, custom ranking
```

---

## 6. Q-Commerce Competitive Gap Analysis

### Feature Matrix: Instacommerce vs Competition

| Feature | Instacommerce (Current) | Zepto | Blinkit | Instacart |
|---------|------------------------|-------|---------|-----------|
| **Full-text search** | PostgreSQL tsvector | OpenSearch | OpenSearch | Elasticsearch |
| **Fuzzy / typo tolerance** | ❌ None | ✅ OpenSearch fuzziness | ✅ | ✅ |
| **Autocomplete** | ILIKE (no index) | OpenSearch completion | OpenSearch prefix | Custom trie |
| **Synonym handling** | ❌ None | ✅ Curated dictionaries | ✅ | ✅ |
| **Hindi/Regional** | ❌ English-only | ✅ Hindi + regional | ✅ Hindi | N/A (US) |
| **Ranking signals** | ts_rank only | availability × relevance × popularity | category-aware + personalized | relevance + dietary + aisle |
| **Stock-aware results** | ❌ Not filtered/boosted | ✅ Hard filter | ✅ Hard filter | ✅ Availability score |
| **Personalization** | ❌ None | ✅ User history | ✅ Reorder integration | ✅ Brand affinity |
| **Spell correction** | ❌ None | ✅ "Did you mean" | ✅ | ✅ |
| **Category-aware** | Basic category facet | ✅ Category taxonomy | ✅ Deep category tree | ✅ Aisle mapping |
| **Dietary filters** | ❌ None | ❌ | ❌ | ✅ Vegan/Gluten-free/Organic |
| **Peak QPS** | ~400 (pool-limited) | 100K+ | 50K+ | 200K+ |
| **Autocomplete latency** | >100ms (no index) | <30ms | <30ms | <20ms |
| **Cache strategy** | In-process Caffeine | Distributed Redis | Redis + CDN | Multi-tier |

### Critical Gaps for Feature Parity

1. **Search engine migration** — PostgreSQL cannot compete with OpenSearch at scale
2. **Ranking algorithm** — Need multi-signal scoring, not just text relevance
3. **Personalization infrastructure** — Need user behavior tracking pipeline
4. **Language support** — Hindi/regional is table-stakes for Indian market
5. **Distributed caching** — Caffeine is per-instance; need Redis for consistency across pods

---

## 7. Security Review

### 7.1 Good Practices ✅
- JWT with RSA asymmetric verification (public key only, no signing capability)
- Stateless session management
- Proper CORS configuration
- Search endpoints are `permitAll()` (appropriate for public search)
- Admin endpoints require `ROLE_ADMIN`
- Graceful error responses without stack trace leakage
- Secret Manager integration for credentials

### 7.2 Concerns

#### 🟠 HIGH: SQL Injection Risk in Native Queries

The native queries use Spring Data `@Param` binding, which is safe against SQL injection. However, the `ILIKE` autocomplete query concatenates the prefix with `%`:

```sql
WHERE sd.name ILIKE :prefix || '%'
```

This is safe because `@Param` properly escapes the parameter. But the `%` and `_` characters in user input are NOT escaped, meaning:
- Input `%` → `ILIKE '%%'` → matches everything (data exfiltration)
- Input `_` → `ILIKE '_%'` → matches any single character prefix

**Fix:** Escape `%` and `_` in the prefix before passing to the query:
```java
String safePrefix = prefix.replace("%", "\\%").replace("_", "\\_");
```

#### 🟡 MEDIUM: CORS `allowedOriginPatterns: *` with `allowCredentials: true`

This combination allows any origin to make credentialed requests. While search endpoints are public, this is risky if admin endpoints are added later. Should restrict to known origins.

#### 🟡 MEDIUM: No Rate Limiting

Search endpoints are `permitAll()` with no rate limiting. A single client can flood the service with requests, exhausting the connection pool (which is already undersized at 20).

---

## 8. Prioritized Remediation Roadmap

### Phase 0 — Immediate Hotfixes (Week 1) 🔴

| # | Finding | Fix | Effort |
|---|---------|-----|--------|
| 1 | Connection pool = 20 | Increase to 50+, add `statement_timeout` | 1 hour |
| 2 | Synchronous trending write | Make `recordQuery()` `@Async` or use in-memory buffer | 2 hours |
| 3 | Trending `@CacheEvict` invalidates on every query | Remove `@CacheEvict` from `recordQuery()`, let TTL handle it | 30 min |
| 4 | No autocomplete index | Add `text_pattern_ops` B-tree index on `name` | 1 hour |
| 5 | ILIKE wildcard characters not escaped | Escape `%` and `_` in autocomplete prefix | 30 min |
| 6 | No stock filter/boost in search | Add `AND sd.in_stock = TRUE` or boost in-stock items | 1 hour |

### Phase 1 — Core Search Quality (Weeks 2-4) 🟠

| # | Finding | Fix | Effort |
|---|---------|-----|--------|
| 7 | Facet counts ignore active filters | Pass all filters to facet queries | 1 day |
| 8 | 3 DB queries per search | Combine into single query with window functions | 2 days |
| 9 | No zero-results handling | Add fallback to trending + partial match | 3 days |
| 10 | No `SearchProvider` abstraction | Define interface, refactor current code behind it | 3 days |
| 11 | No popularity/sales signal in ranking | Add `popularity_score` column, composite ranking formula | 2 days |
| 12 | Autocomplete sorted alphabetically | Sort by popularity + recency | 1 day |
| 13 | Atomic upsert for trending | Use `INSERT ON CONFLICT` instead of SELECT+UPDATE | 1 day |
| 14 | Add rate limiting | Spring Boot rate limiter or API gateway rate limit | 1 day |

### Phase 2 — OpenSearch Migration (Weeks 5-10) 🟡

| # | Finding | Fix | Effort |
|---|---------|-----|--------|
| 15 | Migrate to OpenSearch | Implement OpenSearch provider, dual-write, shadow test | 5 weeks |
| 16 | Add fuzzy matching | OpenSearch `fuzziness: AUTO` | Included in #15 |
| 17 | Add synonym dictionaries | OpenSearch synonym filter with curated grocery synonyms | 1 week |
| 18 | Add Hindi/regional support | ICU analyzer + language-specific configurations | 2 weeks |
| 19 | Add "did you mean" | OpenSearch phrase suggester | 1 week |
| 20 | Distributed caching (Redis) | Replace Caffeine with Redis for cross-pod consistency | 1 week |

### Phase 3 — Advanced Features (Weeks 11-16) 🔵

| # | Finding | Fix | Effort |
|---|---------|-----|--------|
| 21 | Personalized ranking | User behavior pipeline → search ranking signals | 4 weeks |
| 22 | Category-aware search | Category taxonomy integration, category boosting | 2 weeks |
| 23 | Dietary/attribute filters | Extend schema with product attributes | 2 weeks |
| 24 | Search analytics dashboard | Query performance, zero-result rate, CTR tracking | 2 weeks |
| 25 | A/B testing for ranking | Feature flags + ranking model variants | 2 weeks |

---

## 9. File-by-File Findings Index

| File | Findings | Severity |
|------|----------|----------|
| `V1__create_search_documents.sql` | English-only tsvector, no autocomplete index, no popularity column | 🔴🟠 |
| `V2__create_trending_queries.sql` | No composite index for cleanup query, no TTL column for trending window | 🟡 |
| `V3__create_shedlock.sql` | OK — standard ShedLock schema | ✅ |
| `application.yml` | Pool size=20 too low, no statement_timeout, no read-replica config, tracing probability=1.0 in prod is expensive | 🔴🟠 |
| `SearchDocument.java` | Missing `popularityScore`, `tags`, `attributes` fields; `searchVector` mapped as String (should be ignored by JPA) | 🟡 |
| `TrendingQuery.java` | No `trendingScore` field, `incrementHitCount()` not thread-safe for JPA (dirty checking race) | 🟠 |
| `SearchDocumentRepository.java` | ILIKE autocomplete without index, facets ignore filters, no stock filter, `Object[]` return type fragile | 🔴🔴🟠 |
| `TrendingQueryRepository.java` | `findByQuery` + manual increment = non-atomic upsert, `findTopBy...` naming may not paginate correctly | 🟠 |
| `SearchService.java` | Synchronous trending write in hot path, `mapToSearchResult` hardcoded column indices (fragile), no zero-results handling | 🔴🟠 |
| `TrendingService.java` | `@CacheEvict` on every `recordQuery()` destroys cache, synchronous transaction in search path, no trending algorithm | 🔴🟡 |
| `SearchIndexService.java` | `@CacheEvict(allEntries=true)` is too aggressive for single-document updates | 🟡 |
| `SearchController.java` | Clean, well-validated. Missing rate limiting, no search-latency metric annotation | 🟡 |
| `CatalogEventConsumer.java` | No batch processing, fixed backoff not exponential, good DLQ handling | 🟡 |
| `CacheConfig.java` | In-process only (no distributed cache), cache sizes reasonable for single instance | 🟡 |
| `KafkaConfig.java` | Good DLQ config, missing `max.poll.records` tuning, no concurrency config | 🟡 |
| `SecurityConfig.java` | CORS too permissive with `*` + credentials, search endpoints correctly public | 🟡 |
| `SearchProperties.java` | Only JWT config, should include search-specific tuning (pool size, cache TTLs, etc.) | 🔵 |
| `ShedLockConfig.java` | OK — standard config | ✅ |
| `build.gradle.kts` | Missing OpenSearch client dependency, missing `pg_trgm` extension setup | 🔵 |
| `Dockerfile` | Good — ZGC, non-root user, health check, alpine. Missing JVM heap size tuning flags beyond MaxRAMPercentage | ✅ |
| `GlobalExceptionHandler.java` | Comprehensive exception handling | ✅ |
| `TraceIdProvider.java` | Good W3C traceparent support | ✅ |
| `JwtAuthenticationFilter.java` | Clean implementation, proper error response | ✅ |
| `DefaultJwtService.java` | Secure RSA verification | ✅ |
| `JwtKeyLoader.java` | Handles PEM and raw base64 formats | ✅ |
| `SearchRequest.java` | Not used by controller (controller uses @RequestParam directly) — dead code | 🔵 |

---

> **Bottom Line:** The search service is a clean, well-structured MVP that will serve the first 100K users well. But PostgreSQL tsvector hits a hard ceiling for Q-commerce at scale. The immediate priority is the Phase 0 hotfixes (connection pool, async trending, autocomplete index) followed by a planned OpenSearch migration. Without these changes, the service will become the primary bottleneck and customer experience pain point as the platform scales.
