# Search Service - Kafka Events

## Consumed Topics

### Topic: `catalog.events`
**Consumer Group:** `search-service`
**Partitions:** 6 (default)
**Replication Factor:** 3
**Retention:** 7 days (604800000 ms)

#### Event: ProductCreated

**Event Type:** `ProductCreated`

**Envelope Structure:**
```json
{
  "id": "event-id-uuid",
  "eventType": "ProductCreated",
  "aggregateType": "Product",
  "aggregateId": "product-id-uuid",
  "eventTime": "2025-03-21T10:30:00Z",
  "schemaVersion": "1.0",
  "sourceService": "catalog-service",
  "correlationId": "request-id-uuid",
  "payload": {
    "productId": "550e8400-e29b-41d4-a716-446655440000",
    "name": "Fresh Milk 1L",
    "description": "High-quality fresh milk",
    "brand": "Brand A",
    "category": "Dairy",
    "priceCents": 5000,
    "imageUrl": "https://cdn.example.com/milk.jpg",
    "inStock": true,
    "storeId": "store-id-uuid"
  }
}
```

**Handler:** `CatalogEventConsumer.handleProductUpsert()`

**Action:**
```sql
INSERT INTO search_documents
  (product_id, name, description, brand, category, price_cents, image_url, in_stock, store_id)
VALUES (...)
ON CONFLICT (product_id) DO UPDATE
SET name = EXCLUDED.name,
    description = EXCLUDED.description,
    ...
-- Trigger automatically recalculates search_vector
```

**Error Handling:**
- On parse error: log and skip (offset committed)
- On DB insert failure: throw exception → Kafka retry (3x) → DLQ

---

#### Event: ProductUpdated

**Event Type:** `ProductUpdated`

Same as ProductCreated (UPSERT semantics).

---

#### Event: ProductDelisted

**Event Type:** `ProductDelisted` or `ProductDeactivated`

**Envelope:**
```json
{
  "id": "event-id-uuid",
  "eventType": "ProductDelisted",
  "aggregateType": "Product",
  "aggregateId": "product-id-uuid",
  "payload": {
    "productId": "550e8400-e29b-41d4-a716-446655440000"
  }
}
```

**Handler:** `CatalogEventConsumer.handleProductDelisted()`

**Action:**
```sql
DELETE FROM search_documents WHERE product_id = ?
```

**Error Handling:** Same as ProductCreated

---

### Topic: `inventory.events`
**Consumer Group:** `search-service`
**Partitions:** 3 (default)
**Replication Factor:** 3
**Retention:** 7 days

#### Event: ProductStockChanged

**Event Type:** `ProductStockChanged`

**Envelope:**
```json
{
  "id": "event-id-uuid",
  "eventType": "ProductStockChanged",
  "aggregateType": "Inventory",
  "aggregateId": "product-id-uuid",
  "eventTime": "2025-03-21T10:30:00Z",
  "schemaVersion": "1.0",
  "sourceService": "inventory-service",
  "correlationId": "request-id-uuid",
  "payload": {
    "productId": "550e8400-e29b-41d4-a716-446655440000",
    "quantity": 150,
    "inStock": true
  }
}
```

**Handler:** `InventoryEventConsumer.handleStockChanged()`

**Action:**
```sql
UPDATE search_documents SET in_stock = ? WHERE product_id = ?
```

---

## Published Topics

**None** — Search service is read-only (no outgoing events).

---

## Dead-Letter Topics

### `catalog.events.DLT`
**Retention:** 30 days (for debugging)

**When DLQ is written:**
1. CatalogEventConsumer receives message
2. Kafka retries 3 times (exponential backoff)
3. After 3 failures, message is sent to `catalog.events.DLT`
4. Alert: `search-service-dlq-alarm` triggers

**Root Cause Analysis:**
- Malformed JSON in payload
- SQL constraint violations (e.g., non-existent store_id)
- Database connectivity issues (transient)
- Poison pill events (permanently invalid)

**Recovery:**
```bash
# View DLT messages
kafka-console-consumer --bootstrap-server kafka:9092 \
  --topic catalog.events.DLT \
  --from-beginning

# Replay a specific message (manual)
# Send back to catalog.events for reprocessing
```

### `inventory.events.DLT`
Same as above for inventory events.

---

## Event Ordering Guarantees

- **Per Product:** FIFO (same product_id always goes to same partition by key)
- **Cross-Product:** No ordering guarantee (independent partitions)
- **Implication:** Stock and product updates for same product_id are ordered, but parallel updates to different products may arrive out of order

---

## Event Idempotency

Search service consumer is **idempotent** because:
1. **Search documents:** Upsert (INSERT ON CONFLICT UPDATE) — safe to replay
2. **Deletes:** DELETE WHERE product_id = ? — safe to replay (idempotent)
3. **Stock updates:** UPDATE with fixed state — safe to replay

**Verification:**
```bash
# Send same event twice, verify same result
$ curl -X POST http://localhost:8086/internal/event-replay \
  -d '{"eventId": "...", "productId": "..."}'
# Result: First call creates/updates, second call is no-op (same state)
```

---

## Event Schema Versioning

Current schema version: `1.0`

**If schema changes:**
1. Bump `schemaVersion` in new events
2. Consumer handles both versions:
   ```java
   if (schemaVersion.equals("1.0")) {
     // Parse v1
   } else if (schemaVersion.equals("2.0")) {
     // Parse v2
   }
   ```
3. Old events continue to work until retention expires

