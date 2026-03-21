# Search Service - Resilience & Error Handling

## Resilience4j Configuration

### Circuit Breaker

The service is read-only (no external calls), but Kafka consumption has error handling:

```yaml
resilience4j:
  circuitbreaker:
    instances:
      catalogConsumer:
        slidingWindowSize: 100
        failureRateThreshold: 50
        slowCallRateThreshold: 100
        slowCallDurationThreshold: 2000ms
        waitDurationInOpenState: 30s
        permittedNumberOfCallsInHalfOpenState: 3
```

### Timeout Strategy

| Operation | Timeout | Rationale |
|-----------|---------|-----------|
| DB statement | 5s | PostgreSQL `statement_timeout` |
| Kafka poll | 3s | Consumer group rebalancing |
| Cache get | 50ms | Caffeine is in-process (fast) |
| Metrics export | 10s | OTLP export can be slow |

### Retry Policy

**Kafka Consumer Retries:**
```
Attempt 1: Immediate
Attempt 2: After 1s (exponential backoff)
Attempt 3: After 2s
After 3 failures: Send to DLT (dead-letter topic)
```

### Fallback Strategies

1. **If Kafka consumer lags**: Serve cached results (TTL: 300s)
2. **If PostgreSQL unavailable**: Circuit breaker opens
   - Return cached results for 30s
   - Reject new requests with 503 Service Unavailable
3. **If cache fills up**: Evict oldest entries (LRU, max 10K entries)

---

## Error Handling Scenarios

### Scenario 1: Malformed Kafka Event

**Event:** `{"eventType": null, "payload": {...}}`

**Handling:**
```java
if (eventType == null) {
    log.warn("Received catalog event with no eventType, skipping");
    // Offset automatically committed (skip message)
    return;
}
```

**Result:** Message is discarded, consumer continues

---

### Scenario 2: Database Constraint Violation

**Example:** Duplicate product_id (race condition with other consumer)

**Handling:**
```sql
-- DB constraint: UNIQUE(product_id)
-- On conflict:
INSERT INTO search_documents (..., product_id, ...)
VALUES (..., '550e8400-...', ...)
ON CONFLICT (product_id) DO UPDATE
SET name = EXCLUDED.name, updated_at = now();
-- Always succeeds (upsert semantics)
```

**Result:** Update succeeds, consistency maintained

---

### Scenario 3: Kafka Consumer Lag Spike

**Cause:** Database write rate > 1000/sec, PostgreSQL slow

**Handling:**
- Kafka consumer automatically throttles (batches)
- Lag increases but eventually catches up
- Monitoring alert: If lag > 10000 messages for 5min
- Manual intervention: Add read replica or optimize queries

---

## Observability & Debugging

### Health Check Endpoints

**Liveness:** `GET /actuator/health/live`
```json
{
  "status": "UP"
}
```
Checks: JVM heap, GC pauses (not external services)

**Readiness:** `GET /actuator/health/ready`
```json
{
  "status": "UP",
  "components": {
    "db": { "status": "UP" },
    "kafkaConsumer": {
      "status": "UP",
      "lag": 125,
      "groupId": "search-service"
    }
  }
}
```
Checks: Database connectivity, Kafka consumer group assigned

### Metrics to Monitor

```
search.query.duration_ms (histogram)
  - p50, p99, max
  - Alert if p99 > 200ms

cache.caffeine.hitRate (gauge)
  - Alert if < 90% (cache thrashing)

kafka.consumer.lag (gauge)
  - Alert if > 5000 messages (data staleness)

db.hikari.connections.active (gauge)
  - Alert if > 40 (pool saturation)

search.autocomplete.duration_ms
  - Alert if p99 > 100ms

jvm.memory.used (gauge)
  - Alert if > 1.5Gi (OOM risk)
```

### Debugging Commands

**Check Kafka consumer lag:**
```bash
kafka-consumer-groups --bootstrap-server kafka:9092 \
  --group search-service \
  --describe

Output:
GROUP             TOPIC              PARTITION  CURRENT-OFFSET  LOG-END-OFFSET  LAG
search-service    catalog.events     0          1234            1239            5
search-service    inventory.events   0          5678            5680            2
```

**View failed events (DLT):**
```bash
kafka-console-consumer --bootstrap-server kafka:9092 \
  --topic catalog.events.DLT \
  --from-beginning | jq .
```

**Check database slow queries:**
```sql
-- PostgreSQL slow query log
SELECT query, mean_exec_time, calls
FROM pg_stat_statements
WHERE query LIKE '%search_documents%'
ORDER BY mean_exec_time DESC
LIMIT 10;
```

**Rebuild search index from scratch:**
```sql
-- Clear all search documents
DELETE FROM search_documents;
TRUNCATE trending_queries;
-- Kafka consumer will repopulate via replayed events
-- OR manually replay via admin API endpoint
```

---

## Runbook: Common Issues & Resolution

### Issue: Search latency spike (p99 > 500ms)

**Symptoms:**
- Spike in search response time
- Cache hit rate still high
- No errors in logs

**Diagnosis:**
```bash
# Check PostgreSQL slow queries
psql search -c "SELECT query, total_time, calls FROM pg_stat_statements WHERE query LIKE '%search_documents%' ORDER BY total_time DESC LIMIT 5;"

# Check table size
psql search -c "SELECT pg_size_pretty(pg_total_relation_size('search_documents'));"

# Check index usage
psql search -c "SELECT * FROM pg_stat_user_indexes WHERE relname = 'search_documents' ORDER BY idx_scan DESC;"
```

**Root Cause Possibilities:**
1. tsvector index fragmentation (GIN index bloated)
2. Missing ANALYZE statistics
3. Concurrent writes bloocking reads (table lock)
4. Network latency to database

**Resolution:**
```sql
-- Rebuild GIN index
REINDEX INDEX idx_search_documents_search_vector;

-- Update statistics
ANALYZE search_documents;

-- Check for long-running transactions
SELECT pid, usename, query, query_start FROM pg_stat_activity WHERE state != 'idle' AND query_start < now() - interval '5 min';

-- If transaction blocking: terminate it (careful!)
SELECT pg_terminate_backend(pid);
```

---

### Issue: Cache hit rate dropping (< 85%)

**Symptoms:**
- More cache misses than expected
- Database query count increases
- Possible OOM in progress

**Diagnosis:**
```bash
# Check Prometheus metrics
curl http://localhost:8086/actuator/metrics/cache.caffeine.hitRate

# Check JVM heap
curl http://localhost:8086/actuator/metrics/jvm.memory.used
```

**Root Cause Possibilities:**
1. Cache evictions due to size limit (10K entries)
2. High cardinality queries (many unique search terms)
3. High traffic spike
4. Memory pressure

**Resolution:**
```bash
# Increase cache size in application.yml
spring.cache.caffeine.spec: maximumSize=20000,expireAfterWrite=300s

# Redeploy service
kubectl set image deployment/search-service search-service=search-service:v1.2.4

# Monitor hit rate recovery
watch -n 1 'curl -s http://localhost:8086/actuator/metrics/cache.caffeine.hitRate | jq .measurements'
```

---

### Issue: DLQ has messages (failed events)

**Symptoms:**
- Alerts: "Search service DLQ spike detected"
- Search index not updating for new products

**Diagnosis:**
```bash
# Inspect DLT messages
kafka-console-consumer --bootstrap-server kafka:9092 \
  --topic catalog.events.DLT \
  --from-beginning \
  --max-messages 5 | jq .

# Check database for errors
psql search -c "SELECT * FROM pg_log WHERE message LIKE '%product%' AND created_at > now() - interval '1 hour';"
```

**Root Cause Possibilities:**
1. Malformed events from upstream (catalog-service bug)
2. Database constraint violation (duplicate product_id race condition)
3. Data type mismatch (e.g., UUID vs string)
4. Poison pill event (permanently invalid)

**Resolution:**
```bash
# Manual replay of valid messages from DLT
# 1. Identify valid message
# 2. Fix upstream issue
# 3. Send message back to catalog.events for reprocessing
# OR skip permanently if poison pill

# Redeploy catalog-service if bug found
kubectl rollout restart deployment/catalog-service
```

