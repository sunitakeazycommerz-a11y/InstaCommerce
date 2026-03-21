# Wave 40 Phase 5: Data Mesh Reverse ETL (Week 4-5)

## Executive Summary
**Objective**: Build data mesh orchestrator for subscription management + activation sinks
**Timeline**: Week 4-5 (April 7-18)
**Output**: Real-time data activation to 3 sinks (DW, Marketing Automation, Analytics)
**SLO**: <5s end-to-end latency, 99.9% delivery accuracy

---

## Architecture

```
┌──────────────────────────────────────────────────────────────────┐
│                    Event Stream (Kafka)                          │
│  user.updated | order.placed | payment.completed                │
└────────────────────────┬─────────────────────────────────────────┘
                         │
                    ┌────▼────────┐
                    │ Orchestrator │ (Spring Boot service)
                    │   Service    │ subscriptions/rules/transform
                    └────┬────────┘
                         │
        ┌────────────────┼────────────────┐
        │                │                │
   ┌────▼────┐    ┌─────▼─────┐   ┌─────▼────┐
   │Data Sink │    │  Marketing │   │Analytics │
   │Warehouse │    │ Automation │   │ Platform │
   │(Snowflake)    │   Sink     │   │  Sink    │
   │batch/hourly   │(real-time) │   │(stream)  │
   └──────────┘    └────────────┘   └──────────┘
```

### Orchestrator Service Components

1. **Subscription Manager**
   - Define: Which events → which sink
   - Example: "All users: → DW (daily), Marketing if eligible"

2. **Transformation Engine**
   - Apply rules: PII masking, derived fields, aggregations
   - Example: "Add customer_lifetime_value computed field"

3. **Sink Connectors**
   - DW: Batch writer (hourly to Snowflake)
   - Marketing: Real-time API (HubSpot)
   - Analytics: Stream writer (BigQuery)

4. **Deduplication & State**
   - PostgreSQL: Track last delivery per event type + sink
   - Prevent: Duplicate activations, out-of-order delivery

---

## Implementation

### Phase 5.1: Orchestrator Service (Spring Boot)

**Endpoints**:

```java
POST /subscriptions
{
  "name": "new_user_to_dwh",
  "event_type": "user.created",
  "sink_id": "snowflake_dwh",
  "transform_rules": [
    {"mask_field": "email"},
    {"mask_field": "phone"},
    {"add_field": "ingestion_timestamp", "value": "now()"}
  ],
  "schedule": "hourly",
  "enabled": true
}

POST /transforms/{sub_id}/execute
// Dry-run: Show sample transformation output

GET /subscriptions
// List all active subscriptions

POST /subscriptions/{sub_id}/toggle
// Enable/disable without deleting
```

**Database Schema**:

```sql
CREATE TABLE subscriptions (
  id UUID PRIMARY KEY,
  name VARCHAR NOT NULL,
  event_type VARCHAR NOT NULL,  -- 'user.*', 'order.*', 'payment.*'
  sink_id VARCHAR NOT NULL,     -- 'snowflake_dwh', 'hubspot', 'bigquery'
  transform_rules JSONB,
  schedule VARCHAR,             -- 'immediate', 'batch_hourly', 'batch_daily'
  enabled BOOLEAN DEFAULT true,
  created_at TIMESTAMP,
  updated_at TIMESTAMP
);

CREATE TABLE activation_log (
  id UUID PRIMARY KEY,
  subscription_id UUID REFERENCES subscriptions,
  event_id VARCHAR NOT NULL,
  sink_id VARCHAR,
  status VARCHAR,               -- 'pending', 'delivered', 'failed'
  error_msg TEXT,
  created_at TIMESTAMP,
  delivered_at TIMESTAMP,
  UNIQUE(event_id, sink_id)    -- Deduplication key
);
```

### Phase 5.2: Data Warehouse Sink (Snowflake)

**Batch Writer** (hourly trigger):
```python
# Python lambda in orchestrator
def batch_to_snowflake(subscription_id, hour_start):
    events = db.query("""
        SELECT * FROM activation_log
        WHERE subscription_id = %s
        AND created_at >= %s
        AND status = 'delivered'
    """, subscription_id, hour_start)

    snowflake_conn.execute(f"""
        INSERT INTO {subscription.schema}.{subscription.table}
        SELECT {subscription.columns}
        FROM generated_data
    """)
    return {"rows": len(events), "batch_id": batch_id}
```

**Schema** (Snowflake):
```sql
CREATE TABLE instacommerce.users (
  user_id UUID,
  email VARCHAR MASKED,
  phone VARCHAR MASKED,
  created_at TIMESTAMP,
  updated_at TIMESTAMP,
  customer_lifetime_value DECIMAL(10,2),
  ingestion_timestamp TIMESTAMP
);

CREATE TABLE instacommerce.orders (
  order_id UUID,
  user_id UUID,
  order_value DECIMAL(10,2),
  status VARCHAR,
  created_at TIMESTAMP,
  ingestion_timestamp TIMESTAMP
);
```

### Phase 5.3: Marketing Automation Sink (HubSpot)

**Real-Time API Writer**:
```java
@Service
public class HubSpotSink {
  public void activate(Event event, Subscription sub) {
    Contact contact = transform(event, sub.rules); //PII masked

    try {
      hubspot.upsertContact(contact);
      log.info("Synced to HubSpot: " + contact.email);
    } catch (Exception e) {
      // Retry queue: exponential backoff
      retryQueue.enqueue(event, sub.id, 3); // 3 retries max
    }
  }
}

// Example: New user → HubSpot Contact
POST https://api.hubapi.com/crm/v3/objects/contacts
{
  "properties": {
    "email": "user@example.com",
    "firstname": "John",
    "hs_lead_status": "subscriber",
    "custom_customer_lifetime_value": 125.50
  }
}
```

**SLO**: <1 second per contact (p99)

### Phase 5.4: Analytics Sink (BigQuery)

**Stream Writer** (real-time):
```go
// Go service (can be separate or in orchestrator)
func streamToBigQuery(ctx context.Context, event Event) error {
  row := &BigQueryRow{
    EventID:        event.ID,
    EventType:      event.Type,
    UserID:         event.UserID,
    Timestamp:      event.Time,
    IngestionTime:  time.Now(),
  }

  inserter := client.Dataset("events").Table("raw").Inserter()
  err := inserter.Put(ctx, row)
  return err
}
```

**Schema** (BigQuery):
```sql
CREATE TABLE events.raw (
  event_id STRING,
  event_type STRING,
  user_id STRING,
  data JSON,
  timestamp TIMESTAMP,
  ingestion_time TIMESTAMP
);

-- Materialized view for analytics
CREATE MATERIALIZED VIEW events.hourly_user_activity AS
SELECT
  DATE_TRUNC(timestamp, HOUR) AS hour,
  event_type,
  COUNT(*) AS event_count,
  APPROX_DISTINCT_COUNT(user_id) AS unique_users
FROM events.raw
GROUP BY hour, event_type;
```

---

## Deployment Timeline

| Week | Task | Owner |
|------|------|-------|
| W4 Mon | Code orchestrator + DB schema | Data Engineer |
| W4 Tue | Build DW batch sink | Data Engineer |
| W4 Tue | Build marketing API sink | Data Engineer |
| W4 Wed | Build analytics stream sink | Data Engineer |
| W4 Thu | Integration testing (all sinks) | QA |
| W4 Fri | Staging validation | Data Lead |
| W5 Mon | Production deployment (phased) | Platform |
| W5 Tue | Activation: 10 subscriptions | Data Lead |
| W5 Wed | Monitoring + SLO validation | Data Lead |
| W5 Thu | Runbook documentation | Data Lead |

---

## Success Metrics

| Metric | Target | Status |
|--------|--------|--------|
| DW sync latency | <1 hr (hourly batch) | 📋 |
| Marketing latency | <5 sec (p99) | 📋 |
| Analytics latency | <30 sec (stream) | 📋 |
| End-to-end accuracy | 100% (no data loss) | 📋 |
| Deduplication effectiveness | 0 duplicates | 📋 |
| Delivery success rate | >99.9% | 📋 |

**Next**: Data mesh governance (Wave 41+)
