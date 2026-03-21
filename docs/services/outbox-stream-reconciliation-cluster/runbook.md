# Outbox Relay & Stream Processor Cluster - Deployment & Operations

## Outbox Relay Service Operations

### Pre-Deployment

- [ ] PostgreSQL `outbox_events` table exists with partial index on `(created_at) WHERE sent=false`
- [ ] Kafka brokers reachable from pod (test: `kafka-topics.sh --list`)
- [ ] All 13 producer services have Flyway migration V1 (creates `outbox_events` table)
- [ ] Outbox relay image built: `go build -o outbox-relay .`
- [ ] Go 1.24+ version confirmed

### Deployment

```bash
# 1. Build multi-stage Docker image
cd services/outbox-relay-service
docker build -t gcr.io/my-project/outbox-relay:v1.0.0 .

# 2. Push to registry
docker push gcr.io/my-project/outbox-relay:v1.0.0

# 3. Deploy to Kubernetes
helm install outbox-relay deploy/helm/ \
  --set image.tag=v1.0.0 \
  --set env.DATABASE_URL=postgres://user:pass@cloudsql-proxy:5432/instacommerce \
  --set env.KAFKA_BROKERS=kafka-broker-1:9092,kafka-broker-2:9092 \
  -n instacommerce

# 4. Verify pod is running
kubectl wait --for=condition=Ready pod -l app=outbox-relay -n instacommerce --timeout=30s

# 5. Check logs
kubectl logs -f deployment/outbox-relay-service -n instacommerce
```

### Monitoring

**Key Metrics**:
```
outbox.relay.count{}                    # Events successfully relayed (counter)
outbox.relay.failures{}                 # Relay errors (counter)
outbox.relay.lag.seconds{}              # Time from creation to relay (histogram)
```

**Health Endpoints**:
- `GET /health` → Liveness probe (always 200)
- `GET /health/ready` → Readiness probe (200 if DB + Kafka OK)

### Failure Recovery

**Scenario 1: Kafka broker down**
```bash
# Relay will retry automatically
# Monitor lag metric:
kubectl logs deployment/outbox-relay-service -n instacommerce | grep -i "kafka"

# If lag_seconds grows beyond 60s, check broker status
kubectl exec -it statefulset/kafka-0 -n kafka -- \
  kafka-broker-api-versions.sh --bootstrap-server localhost:9092
```

**Scenario 2: PostgreSQL connection lost**
```bash
# Pod will restart automatically (readiness probe returns 503)
# Check logs
kubectl logs deployment/outbox-relay-service -n instacommerce | grep -i "database\|error"

# Force restart if stuck
kubectl delete pod -l app=outbox-relay -n instacommerce
```

**Scenario 3: Stuck event blocks outbox**
```bash
# If same event_id appears repeatedly in error logs:
# 1. Identify stuck event
stuck_id=$(kubectl logs deployment/outbox-relay -n instacommerce | grep -oP 'event_id=\K[^,}]+' | tail -1)

# 2. Manually mark as sent (RISKY - may lose event)
kubectl exec -it deployment/identity-service -n instacommerce -- \
  psql -h cloudsql-proxy -d instacommerce -c \
  "UPDATE outbox_events SET sent=true WHERE id='$stuck_id';"

# 3. Restart relay to continue processing
kubectl rollout restart deployment/outbox-relay-service -n instacommerce
```

---

## Stream Processor Service Operations

### Pre-Deployment

- [ ] Kafka brokers and topics exist: `orders.events`, `payments.events`, etc. (7 topics)
- [ ] Redis 7.0+ instance running and reachable
- [ ] Go 1.24+ environment available
- [ ] Consumer group `stream-processor` can be created

### Deployment

```bash
# 1. Build Docker image
cd services/stream-processor-service
docker build -t gcr.io/my-project/stream-processor:v1.0.0 .

# 2. Push to registry
docker push gcr.io/my-project/stream-processor:v1.0.0

# 3. Deploy
helm install stream-processor deploy/helm/ \
  --set image.tag=v1.0.0 \
  --set env.KAFKA_BROKERS=kafka:9092 \
  --set env.REDIS_ADDR=redis-cluster.redis:6379 \
  --set replicas.min=2 \
  --set replicas.max=20 \
  -n instacommerce

# 4. Verify HPA configured for Kafka consumer lag
kubectl get hpa stream-processor -n instacommerce
```

### Scaling Strategy

**Horizontal Pod Autoscaler**:
```yaml
# deploy/helm/templates/hpa.yaml
apiVersion: autoscaling/v2
kind: HorizontalPodAutoscaler
metadata:
  name: stream-processor
spec:
  scaleTargetRef:
    apiVersion: apps/v1
    kind: Deployment
    name: stream-processor
  minReplicas: 2
  maxReplicas: 20
  metrics:
    - type: Resource
      resource:
        name: cpu
        target:
          type: Utilization
          averageUtilization: 70
    - type: Pods
      pods:
        metric:
          name: kafka_consumer_lag
        target:
          type: AverageValue
          averageValue: "5000"  # Scale up if lag > 5000 per replica
```

### Monitoring

**Critical Metrics**:
```
orders_total{event_type, store_id}           # Order counter
payment_success_rate{method}                 # 5-min window gauge
sla_compliance_ratio{zone_id}                # SLA % per zone
inventory_cascade_alerts_total{store_id}     # Stockout cascade count
kafka_consumer_lag{topic, partition}         # Consumer group lag
```

**Alerts**:
```yaml
- alert: HighKafkaConsumerLag
  expr: kafka_consumer_lag > 10000
  for: 5m
  action: Scale up stream-processor replicas

- alert: SLABreach
  expr: sla_compliance_ratio{zone_id=~".+"} < 0.90
  for: 1m
  action: Page on-call; check fulfillment service latency

- alert: PaymentSuccessRateDrop
  expr: payment_success_rate{method="upi"} < 0.80
  for: 5m
  action: Check payment service; review failed transactions
```

### Failure Recovery

**Scenario 1: Redis unavailable**
```bash
# Stream processor will log errors but continue
# Prometheus metrics still updated (in-memory state)
# Redis writes skipped until recovery

# Recovery: Restart Redis
kubectl restart statefulset/redis -n instacommerce

# Catch-up: Allow 5-min window repopulation
watch -n 1 'kubectl logs deployment/stream-processor -n instacommerce | grep -i "redis\|error" | tail -5'
```

**Scenario 2: Kafka consumer lag spike**
```bash
# 1. Check consumer group status
kubectl exec -it deployment/kafka-tools -n kafka -- \
  kafka-consumer-groups.sh --bootstrap-server kafka:9092 --group stream-processor --describe

# 2. Check if consumer is stuck
kafka-consumer-groups.sh ... --describe | grep stream-processor | awk '{print $NF}'
# If CURRENT_OFFSET == LOG_END_OFFSET, consumer is caught up

# 3. Trigger auto-scaling
kubectl patch hpa stream-processor -n instacommerce -p '{"spec":{"minReplicas":5}}'

# 4. Monitor catch-up
watch -n 2 'kubectl get pods -l app=stream-processor -n instacommerce | grep Running | wc -l'
```

**Scenario 3: SLA alert channel overflow (dropped alerts)**
```bash
# Alert channel capacity is 100; excess alerts dropped
# Solution: Increase channel buffer in code

# Temporary: Acknowledge alert manually
# Permanent: PR to increase SLAMonitor buffer size from 100 to 1000
```

---

## Transactional Outbox Pattern Deep Dive

### Why Transactional Outbox?

**Problem without outbox**: Dual-write problem
```
order-service:
  1. INSERT Order
  2. Publish to Kafka  ← What if step 2 fails?
     → Event lost or duplicated
```

**Solution with outbox**: Single transaction
```
order-service:
  BEGIN TX
    1. INSERT Order
    2. INSERT outbox_events {sent=false}
  COMMIT TX  ← Atomic: both succeed or both fail

outbox-relay:
  Poll every 1s:
    SELECT … WHERE sent=false FOR UPDATE SKIP LOCKED
    Publish to Kafka
    UPDATE … SET sent=true
```

### At-Least-Once Guarantee

**Idempotent Producer Settings** (Go sarama library):
```go
config.Version = sarama.V2_5_0_0         // Broker-side dedup support
config.RequiredAcks = sarama.WaitForAll  // Wait for all ISR
config.Idempotent = true                 // Enable idempotency
config.MaxOpenRequests = 1               // Preserve ordering
```

**Deduplication Window**: Within single producer session (PID + sequence)
- Duplicates possible across relay restarts
- **Consumer must be idempotent**: dedup on `event_id` header

### Database Index Strategy

```sql
-- Effective for relay's query: ORDER BY created_at WHERE sent=false LIMIT 100
CREATE INDEX idx_outbox_unsent ON outbox_events (created_at)
  WHERE sent = false;

-- Avoid:  CREATE INDEX idx_outbox_sent_created
--         ON outbox_events (sent, created_at)  [too broad]
```

---

## Event Flow Walkthrough (End-to-End)

```
1. order-service (Java/Spring Boot)
   ├─ placeOrder() called by REST endpoint
   ├─ @Transactional begins
   ├─ INSERT Order (domain aggregate)
   ├─ INSERT outbox_events {id=uuid, aggregate_type='Order',
   │                        event_type='OrderPlaced', sent=false}
   └─ COMMIT (both rows committed atomically)

2. outbox-relay-service (Go)
   ├─ Ticker fires every 1s
   ├─ BEGIN TX
   ├─ SELECT outbox_events WHERE sent=false ORDER BY created_at LIMIT 100
   │  → Finds newly inserted row
   ├─ resolveKafkaTopic('Order') → 'orders.events'
   ├─ buildEventMessage() → JSON envelope with event_id, eventType, payload
   ├─ Kafka SyncProducer.SendMessage(
   │     topic='orders.events',
   │     key='order-12345',          # per-order ordering
   │     value=JSON_envelope,
   │     acks=WaitForAll
   │  )
   ├─ UPDATE outbox_events SET sent=true WHERE id=uuid
   ├─ COMMIT (rows only updated if Kafka ack received)
   └─ Record lag metric: time.Since(created_at)

3. Kafka broker
   ├─ Message replicated to all ISR (min 3 brokers)
   ├─ Returned offset to producer
   └─ Message available to consumers

4. Consumer (e.g., notification-service, stream-processor, audit-trail)
   ├─ Poll from 'orders.events' topic
   ├─ Deserialize JSON envelope
   ├─ Process event (send notification, update metrics, audit log)
   ├─ CommitMessages() to consumer group
   └─ Lag metric decreases
```

---

## Deployment Validation Checklist

### Outbox Relay

- [ ] Pod starts successfully: `kubectl logs deployment/outbox-relay -n instacommerce | grep "started"`
- [ ] Connects to PostgreSQL: log message confirms connection pool initialized
- [ ] Connects to Kafka: readiness probe returns 200
- [ ] First event relayed: `outbox.relay.count` increments from 0
- [ ] Lag metric reasonable: `outbox.relay.lag.seconds` p50 < 5s

### Stream Processor

- [ ] Pod starts successfully: log confirms consumer group initialized
- [ ] Subscribes to all 7 topics: check Kafka consumer group
- [ ] Processes events: `orders_total` counter increments
- [ ] Redis writes: check Redis keys exist: `redis-cli KEYS "orders:*"`
- [ ] Metrics exported: `GET /metrics` returns Prometheus format

### Integration Test (End-to-End)

```bash
# 1. Insert test outbox event
kubectl exec -it deployment/order-service -n instacommerce -- \
  psql -c "INSERT INTO outbox_events
    (aggregate_type, aggregate_id, event_type, payload, sent)
    VALUES ('Order', 'test-123', 'OrderPlaced', '{\"test\":true}', false);"

# 2. Wait for relay (1-2 seconds)
sleep 2

# 3. Verify event published to Kafka
kubectl exec -it deployment/kafka-tools -n kafka -- \
  kafka-console-consumer.sh --bootstrap-server kafka:9092 --topic orders.events \
  --from-beginning --max-messages 1 | jq .

# 4. Verify outbox marked sent
kubectl exec -it deployment/order-service -n instacommerce -- \
  psql -c "SELECT sent FROM outbox_events WHERE aggregate_id='test-123';"
# Output: sent | true
```

---

## Performance Tuning

### Outbox Relay Configuration

| Parameter | Default | Tuning Guide |
|-----------|---------|--------------|
| `OUTBOX_POLL_INTERVAL` | 1s | Increase if CPU high; decrease if lag growing |
| `OUTBOX_BATCH_SIZE` | 100 | Increase to 500 for high throughput; decrease for low latency |
| `KAFKA_MAX_OPEN_REQUESTS` | 1 | Must stay 1 for idempotent producer correctness |
| `SHUTDOWN_TIMEOUT` | 20s | Increase if batch processing slow (> 20s) |

**Example: High-throughput tuning**
```bash
kubectl set env deployment/outbox-relay-service \
  -n instacommerce \
  OUTBOX_POLL_INTERVAL=2s \
  OUTBOX_BATCH_SIZE=500
```

### Stream Processor Configuration

| Parameter | Default | Tuning Guide |
|-----------|---------|--------------|
| `MinBytes` | 1 KB | Increase if network latency high (< 10 ms) |
| `CommitInterval` | 1s | Decrease to 500ms for lower latency; increase to 2s for throughput |
| Replicas (HPA) | min=2, max=20 | Adjust max based on peak traffic forecast |

---

## Known Issues & Workarounds

### Issue 1: Relay DLQ Not Implemented
**Symptom**: Single bad event blocks entire outbox for that aggregate type
**Workaround**: Manually mark event as sent (see Scenario 3 above)
**Permanent Fix**: Wave 35 will implement DLQ topic

### Issue 2: Stream Processor Not Idempotent
**Symptom**: Redelivered events double-count metrics (e.g., GMV counted twice)
**Workaround**: Monitor `outbox.relay.failures` metric; keep low to minimize dupes
**Permanent Fix**: Wave 36 will implement event_id-based dedup

### Issue 3: Topic Naming Inconsistency
**Symptom**: Stream processor subscribes to both `order.events` and `orders.events`
**Workaround**: Consolidate producers to use canonical names only
**Permanent Fix**: ADR-pending to standardize on plural topic names

---

## Runbook References

- [Outbox Relay Troubleshooting](/services/outbox-relay-service/README.md#failure-modes)
- [Stream Processor Scaling Guide](/services/stream-processor-service/README.md#runout-and-rollback-notes)
- [Kafka Operations](https://kafka.apache.org/documentation/#operations)
- [Go Observability Guide](https://go.dev/blog/profiling-go-programs)
