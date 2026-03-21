# Search Service - API Reference

## Endpoints Summary

| Method | Path | Purpose | Auth | Cache |
|--------|------|---------|------|-------|
| GET | `/search` | Full-text product search | JWT | 300s |
| GET | `/search/autocomplete` | Search prefix completion | JWT | 300s |
| GET | `/search/trending` | Trending search queries | JWT | 300s |
| GET | `/actuator/health/live` | Liveness probe | None | No |
| GET | `/actuator/health/ready` | Readiness probe | None | No |
| GET | `/actuator/metrics` | Prometheus metrics | None | No |

---

## Request/Response Contracts

### 1. Full-Text Search

**Endpoint:** `GET /search`

**Request Parameters:**

```json
{
  "query": "string",              // Required: 1-256 chars
  "brand": "string?",             // Optional filter
  "category": "string?",          // Optional filter
  "minPriceCents": "long?",       // Optional: >= 0
  "maxPriceCents": "long?",       // Optional: >= minPriceCents
  "page": "integer",              // Default: 0 (0-based)
  "size": "integer"               // Default: 20 (1-100)
}
```

**Response: 200 OK**

```json
{
  "results": [
    {
      "productId": "550e8400-e29b-41d4-a716-446655440000",
      "name": "Fresh Milk 1L",
      "brand": "Brand A",
      "category": "Dairy",
      "priceCents": 5000,
      "imageUrl": "https://cdn.example.com/image.jpg",
      "inStock": true,
      "rank": 0.95
    }
  ],
  "total": 150,
  "page": 0,
  "totalPages": 8,
  "facets": {
    "brand": [
      {
        "value": "Brand A",
        "count": 45
      },
      {
        "value": "Brand B",
        "count": 32
      }
    ],
    "category": [
      {
        "value": "Dairy",
        "count": 78
      }
    ]
  }
}
```

**Error Responses:**

- `400 Bad Request` — Invalid query length, invalid parameters
- `401 Unauthorized` — Missing or invalid JWT
- `500 Internal Server Error` — Database query failed, Kafka consumer lag too high

**Cache Key:** `searchResults:{query}_{brand}_{category}_{minPrice}_{maxPrice}_{page}_{size}`

**Caching Rules:**
- Only cached if query is non-null and non-blank
- TTL: 300 seconds
- Size limit: 10,000 cache entries

---

### 2. Autocomplete

**Endpoint:** `GET /search/autocomplete`

**Request Parameters:**

```json
{
  "prefix": "string",    // Required: 1-128 chars (min 2 for completion)
  "limit": "integer"     // Default: 10 (1-50)
}
```

**Response: 200 OK**

```json
[
  {
    "name": "Fresh Milk 1L",
    "brand": "Brand A",
    "productId": "550e8400-e29b-41d4-a716-446655440000"
  },
  {
    "name": "Milk Powder 500g",
    "brand": "Brand B",
    "productId": "550e8400-e29b-41d4-a716-446655440001"
  }
]
```

**Behavior:**
- Prefix < 2 characters → returns empty list (no DB query)
- SQL LIKE pattern matching: `prefix%` (ILIKE for case-insensitivity)
- Results ordered by product name

**Cache Key:** `autocomplete:{prefix}_{limit}`

**Caching Rules:**
- Only cached if prefix length >= 2
- TTL: 300 seconds

---

### 3. Trending Queries

**Endpoint:** `GET /search/trending`

**Request Parameters:**

```json
{
  "limit": "integer"     // Default: 10 (1-50)
}
```

**Response: 200 OK**

```json
[
  "milk",
  "bread",
  "chicken",
  "oil",
  "rice"
]
```

**Ordering:** Descending by hit_count (most popular first)

**Note:** Asynchronously populated by SearchService when recordQuery() is called

---

## Error Response Format

All error responses follow this contract:

```json
{
  "timestamp": "2025-03-21T10:30:00Z",
  "status": 400,
  "error": "Bad Request",
  "message": "Query must be between 1 and 256 characters",
  "path": "/search"
}
```

---

## Rate Limiting

- **Default:** No global rate limit (controlled by Istio)
- **Istio Limit:** 10,000 RPS per pod
- **DB Connection Pool:** 50 connections max
- **Query Timeout:** 5 seconds (PostgreSQL `statement_timeout`)

---

## Authentication

All endpoints (except actuator) require JWT bearer token:

```
Authorization: Bearer eyJhbGciOiJSUzI1NiIsInR5cCI6IkpXVCJ9...
```

**Token Validation:**
- Issuer: `instacommerce-identity`
- Public key: GCP Secret Manager (`jwt-rsa-public-key`)
- Algorithm: RS256
- Scope: `search:read` (built-in Spring Security)

---

## Health Checks

### Liveness Probe

**Endpoint:** `GET /actuator/health/live`

**Response: 200 OK**

```json
{
  "status": "UP"
}
```

Indicates pod is alive (checks JVM heap, GC pauses, but NOT external services).

### Readiness Probe

**Endpoint:** `GET /actuator/health/ready`

**Response: 200 OK**

```json
{
  "status": "UP",
  "components": {
    "db": {
      "status": "UP",
      "details": {
        "database": "PostgreSQL",
        "validationQuery": "isValid()"
      }
    },
    "kafkaConsumer": {
      "status": "UP",
      "details": {
        "groupId": "search-service",
        "lag": 125  // lag in milliseconds or message count
      }
    }
  }
}
```

Indicates service is ready to accept traffic (checks DB connection, Kafka consumer group is assigned).

---

## Pagination

Searches return paginated results using **zero-based indexing**:

```json
{
  "page": 0,           // Current page (0-based)
  "size": 20,          // Items per page
  "total": 150,        // Total number of results
  "totalPages": 8      // Ceil(total / size)
}
```

**Offset Calculation:** `offset = page * size`

**Example:** Page 2, size 20 → offset = 40 (skip first 40 items)

---

## Faceted Search

Facets are computed on-demand per search query:

```json
{
  "facets": {
    "brand": [
      { "value": "Brand A", "count": 45 },
      { "value": "Brand B", "count": 32 }
    ],
    "category": [
      { "value": "Dairy", "count": 78 }
    ]
  }
}
```

**Facet Computation:**
- Executed for each search (see `buildFacets()` in SearchService)
- Cached alongside search results
- If faceting fails, search still succeeds (graceful degradation)

---

## Metrics Exposed

**Endpoint:** `GET /actuator/metrics`

Key metrics:

- `search.query.count` — Total search queries executed
- `search.query.duration_ms` — Search query latency (timer)
- `search.autocomplete.count` — Autocomplete requests
- `search.trending.count` — Trending requests
- `cache.caffeine.hits` — Cache hits
- `cache.caffeine.misses` — Cache misses
- `cache.caffeine.evictions` — Cache evictions (TTL expiry)
- `kafka.consumer.lag` — Consumer lag (catalog.events, inventory.events groups)
- `jvm.memory.used` — JVM heap usage
- `jvm.gc.pause` — GC pause time

Access individual metric:

```
GET /actuator/metrics/search.query.count
```

