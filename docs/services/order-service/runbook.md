# Order Service - Deployment & Runbook

## Deployment Overview

**Environment**: GKE / Google Kubernetes Engine (asia-south1)
**Namespace**: money-path
**Replicas**: 3 (HA)

---

## Local Development

### Prerequisites

```bash
# PostgreSQL 16
docker run -d --name order-db \
  -e POSTGRES_DB=orders \
  -e POSTGRES_USER=postgres \
  -e POSTGRES_PASSWORD=postgres \
  -p 5432:5432 \
  postgres:16

# Kafka (for event publishing)
docker run -d --name kafka \
  -p 9092:9092 \
  confluentinc/cp-kafka:latest

# Debezium (CDC connector)
docker run -d --name debezium \
  -p 8083:8083 \
  debezium/connect:latest
```

### Build & Run

```bash
./gradlew :services:order-service:build
./gradlew :services:order-service:bootRun --args='--server.port=8085'

# Verify
curl http://localhost:8085/actuator/health
```

---

## Docker Build

```dockerfile
FROM eclipse-temurin:21-jre-alpine
WORKDIR /app
COPY build/libs/order-service-*.jar app.jar
EXPOSE 8085
ENTRYPOINT ["java", "-jar", "app.jar"]
```

**Build & Push**:
```bash
docker build -t gcr.io/instacommerce/order-service:v1.0.0 .
docker push gcr.io/instacommerce/order-service:v1.0.0
```

---

## Kubernetes Deployment

**Key Configuration**:
- Replicas: 3
- CPU: 500m request, 1500m limit
- Memory: 512Mi request, 1Gi limit
- Port: 8085

**Deploy via ArgoCD**:
```bash
argocd app create order-service \
  --repo https://github.com/instacommerce/infra \
  --path deploy/order-service \
  --dest-namespace money-path

argocd app sync order-service
```

---

## Health Checks

### Liveness

```bash
curl http://localhost:8085/actuator/health/live
# { "status": "UP" }
```

### Readiness

```bash
curl http://localhost:8085/actuator/health/ready
# { "status": "UP", "components": { "db": { "status": "UP" } } }
```

---

## Scaling

### Horizontal (Replicas)

```bash
kubectl scale deployment order-service --replicas=5 -n money-path
```

### Vertical (Resources)

Edit deployment manifest:
```yaml
resources:
  requests:
    memory: "1Gi"
    cpu: "1000m"
  limits:
    memory: "2Gi"
    cpu: "2000m"
```

---

## Database Migrations

Flyway migrations run automatically on startup:

```bash
./gradlew :services:order-service:flywayMigrate \
  -Dflyway.url=jdbc:postgresql://localhost:5432/orders \
  -Dflyway.user=postgres \
  -Dflyway.password=postgres
```

**Verify**:
```bash
psql -h localhost -U postgres -d orders \
  -c "SELECT version, description, success FROM flyway_schema_history;"
```

---

## Troubleshooting

### Issue: High Order Creation Latency

```bash
# Check database slow queries
kubectl logs -n money-path -l app=order-service --tail=100 | grep "SLOW"

# Check query performance
psql -h order-db -U postgres -d orders \
  -c "EXPLAIN ANALYZE SELECT * FROM orders WHERE user_id = 'xxx';"
```

### Issue: Outbox Events Stuck (Not Publishing)

```sql
-- Check unpublished events
SELECT COUNT(*) FROM outbox_events WHERE sent = false;

-- Manual publish (if CDC down)
SELECT * FROM outbox_events WHERE sent = false LIMIT 10;

-- Update CDC connector offset
curl -X POST http://debezium-connect:8083/connectors/order-cdc/restart
```

### Issue: Duplicate Orders (Unique Constraint)

```bash
# Check for duplicate idempotency keys
psql -h order-db -U postgres -d orders \
  -c "SELECT idempotency_key, COUNT(*) FROM orders GROUP BY idempotency_key HAVING COUNT(*) > 1;"

# This indicates duplicate checkouts - expected in high load
# Checkout orchestrator should deduplicate via Temporal
```

### Issue: Connection Pool Exhaustion

```bash
# Check active connections
kubectl exec -it deployment/order-service -n money-path -- \
  curl localhost:8085/actuator/metrics/jdbc.connections.active

# If maxed out, restart
kubectl rollout restart deployment/order-service -n money-path
```

---

## Rollback

```bash
# ArgoCD rollback
argocd app rollback order-service 1  # Rollback to previous revision

# Or manual Kubernetes rollback
kubectl rollout undo deployment/order-service -n money-path
```

---

## Performance Tuning

### Database Index Optimization

```sql
-- Check slow queries
SELECT query, calls, mean_time FROM pg_stat_statements
WHERE query LIKE '%orders%'
ORDER BY mean_time DESC
LIMIT 10;

-- Add missing indexes
CREATE INDEX idx_orders_user_created ON orders(user_id, created_at DESC);
CREATE INDEX idx_outbox_created ON outbox_events(created_at DESC) WHERE sent = false;
```

### Batch Operations

For bulk order creation:
```java
// Use batch inserts (if applicable)
@Query(value = "INSERT INTO order_items (order_id, product_id, quantity, ...) VALUES " +
               "(:items)", nativeQuery = true)
public void batchInsertItems(@Param("items") List<OrderItem> items);
```

---

## Monitoring & Alerts

### Key Metrics

```bash
# Order creation rate
curl http://localhost:8085/actuator/prometheus | grep order_created_total

# Database connection pool
curl http://localhost:8085/actuator/prometheus | grep jdbc_connections

# Outbox lag
psql -c "SELECT COUNT(*) FROM outbox_events WHERE sent = false;"
```

### Prometheus Alerts

```yaml
- alert: OrderServiceHighLatency
  expr: histogram_quantile(0.99, http_request_duration_seconds{service="order-service"}) > 2
  for: 5m
  annotations:
    summary: "Order service P99 latency > 2 seconds"

- alert: OutboxEventBacklog
  expr: order_service_outbox_unsent_events_total > 1000
  for: 10m
  annotations:
    summary: "Order service has > 1000 unpublished events"

- alert: DatabaseConnectionPoolLow
  expr: (jdbc_connections_active / jdbc_connections_max) > 0.8
  for: 5m
  annotations:
    summary: "Order service connection pool > 80% utilized"
```

---

## Emergency Procedures

### Disable Order Creation (Circuit Breaker)

```bash
# Set environment variable to skip order creation validation
kubectl set env deployment/order-service \
  ORDER_CREATION_VALIDATION_ENABLED=false \
  -n money-path
```

### Manually Publish Outbox Events

```sql
-- For all unpublished events
SELECT * FROM outbox_events
WHERE sent = false
ORDER BY created_at
LIMIT 100;

-- Convert to Kafka events and publish via Kafka CLI
kafka-console-producer.sh --broker-list kafka:9092 --topic orders.events < events.jsonl

-- Mark as published
UPDATE outbox_events SET sent = true WHERE sent = false;
```

### Force Database Failover

```bash
# Create snapshot of current DB
gcloud sql backups create \
  --instance=order-db-prod

# Promote read replica
gcloud sql instances promote-replica order-db-replica

# Update connection string
kubectl set env deployment/order-service \
  ORDER_DB_URL=jdbc:postgresql://order-db-replica:5432/orders \
  -n money-path
```
