# Search Service - End-to-End Complete Flow

## Customer Journey: Discover → Add to Cart → Checkout

```
T+0:    Customer opens app
        Permissions granted: location (GPS)
        App shows home screen

T+2s:   Customer taps search bar
        Keyboard appears
        Types: "fresh tom" (while typing)

T+3s:   Autocomplete triggered
        → search-service /autocomplete?prefix=fresh%20tom
        → Redis cache hit (popular prefixes)
        → Returns: ["fresh tomatoes", "fresh tomato paste", ...]

T+4s:   Customer selects "fresh tomatoes"
        Taps search button

T+5s:   Auto-complete selection
        Triggers full search:
        → POST /search?q=fresh%20tomatoes&location=...

T+50ms:  search-service receives request
         Extracts: q="fresh tomatoes", lat/lon, user_location
         Checks Redis: search_results:fresh_tomatoes:LAT_LON (5min TTL)

T+100ms: Cache miss
         Query Elasticsearch:
         - match(name, description) = "fresh"
         - AND "tomatoes"
         - filter: in_stock = true
         - geo_filter: within 5km
         - sort: relevance

T+150ms: ES returns ~500 results
         search-service applies ranking:
         1. Relevance score (full-text match)
         2. Store distance (closer = higher)
         3. Inventory level (more stock = higher)
         4. Store rating (5-star stores prioritized)
         → Select top 20

T+180ms: Cache results in Redis (TTL 5min)
         Emit SearchIndexEvent (for analytics)

T+200ms: Return JSON to mobile-bff:
         {
           results: [
             { id: SKU1, name: "Organic Tomatoes", store: A,
               distance: 2km, availability: in_stock, price: $4.99 },
             { id: SKU2, name: "Standard Tomatoes", store: B,
               distance: 3km, availability: low_stock, price: $2.99 },
             ...
           ],
           filters: { store, price_range, rating },
           query_time_ms: 45,
           result_count: 20
         }

T+250ms: mobile-bff formats UI
         Shows map with store locations
         "2 minutes to Store A"
         High-res product images

T+2s:    Customer scrolls through results
         Clicks on first result: "Organic Tomatoes"

T+3s:    Product detail page loads
         Shows: price, store location, reviews, quantity selector
         "Add to Cart" button

T+5s:    Customer selects quantity (2), taps "Add to Cart"
         → cart-service POST /cart/items

T+6s:    Cart updated (async Kafka event)
         → inventory-service reserves 2 units from Store A (pessimistic lock)

T+7s:    Customer navigates to cart
         Reviews: 2 x Organic Tomatoes, $9.98 (with tax/delivery)

T+8s:    Customer taps "Checkout"
         Checkout orchestrator begins:
         → Order-service creates order
         → Payment-service authorizes payment
         → Fulfillment-service assigns rider

T+13s:   Order complete
         Email: "Order confirmed, arriving in 15 min"
         Rider: notification to pick up order
```

## Search Index Consistency

```
GOAL: Ensure search results reflect catalog truth

Catalog-service: Source of Truth
  ↓ (ProductCreatedEvent, ProductUpdatedEvent, ProductDeletedEvent)
  ↓
Kafka topic: catalog.events
  ↓ (Partition by product_id for ordering)
  ↓
search-service Consumer Group
  Consuming at offset tracked in Kafka
  ↓
Elasticsearch Cluster
  Replica sync across data nodes
  ↓
Redis Cache (5min TTL)
  Stores computed results (ranked, filtered)
```

## Resilience & Degradation

```
NORMAL MODE (ES + Redis both healthy):
  Search → Redis hit (cache) → <10ms response
  Search → Redis miss → ES query → <200ms response
  Availability: 99%
  SLO: <200ms p99

DEGRADED MODE (ES down, only Redis):
  Search → Redis cache hit → <10ms response
  Search → Redis cache miss → Fallback to PostgreSQL → <500ms response
  Availability: 99.9% (never down)
  SLO: <500ms p99 (warning alert fired)
  Warning: "Limited search results (index not updated)"

RECOVERY:
  ES Back Online → Re-consume Kafka backlog
  Kafka tracks search-service offset
  ~1-5 min to catch up (depends on backlog size)
  Once caught up, ES becomes primary again
```

---

**Critical Parameters**:
- Redis TTL: 5 minutes (balance: freshness vs caching benefits)
- ES replication: All shards must be healthy (<200ms SLO)
- PostgreSQL fallback: Full-table scan on search_index (acceptable at <500ms)
- Batch size: 100 products/sec (indexing throughput)
- Autocomplete cache: 24 hour TTL (rarely changes)
