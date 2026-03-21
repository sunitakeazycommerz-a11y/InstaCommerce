# Checkout Orchestrator Service - Deployment & Runbook

## Deployment Overview

**Environment**: GKE / Google Kubernetes Engine (asia-south1)
**Infra**: Istio service mesh, ArgoCD continuous deployment

---

## Local Development Setup

### Prerequisites

```bash
# Java 21
java -version
# openjdk version "21" 2023-09-19

# Docker
docker --version

# Temporal CLI (optional)
temporal --version
```

### Start Dependencies

```bash
# PostgreSQL (checkout database)
docker run -d \
  --name checkout-db \
  -e POSTGRES_DB=checkout \
  -e POSTGRES_USER=postgres \
  -e POSTGRES_PASSWORD=postgres \
  -p 5432:5432 \
  postgres:16

# Kafka (for other services)
docker run -d \
  --name kafka \
  -p 9092:9092 \
  confluentinc/cp-kafka:latest

# Temporal Server
docker run -d \
  --name temporal \
  -p 7233:7233 \
  -p 8233:8233 \
  temporalio/temporal:latest

# Temporal Web UI
docker run -d \
  --name temporal-ui \
  -p 8080:8080 \
  -e TEMPORAL_ADDRESS=temporal:7233 \
  temporalio/ui:latest
```

### Build & Run Locally

```bash
# Build
cd /Users/omkarkumar/InstaCommerce
./gradlew :services:checkout-orchestrator-service:build

# Run
./gradlew :services:checkout-orchestrator-service:bootRun \
  --args='--server.port=8089'

# Verify
curl http://localhost:8089/actuator/health
```

---

## Docker Build

**Dockerfile Path**: `services/checkout-orchestrator-service/Dockerfile`

```dockerfile
FROM eclipse-temurin:21-jre-alpine
WORKDIR /app
COPY build/libs/checkout-orchestrator-service-*.jar app.jar
EXPOSE 8089
ENTRYPOINT ["java", "-jar", "app.jar"]
```

**Build Image**:
```bash
cd services/checkout-orchestrator-service
docker build -t gcr.io/instacommerce/checkout-orchestrator:v1.0.0 .

# Push to registry
docker push gcr.io/instacommerce/checkout-orchestrator:v1.0.0
```

---

## Kubernetes Deployment

**Manifest Path**: `deploy/checkout-orchestrator/deployment.yaml`

```yaml
apiVersion: apps/v1
kind: Deployment
metadata:
  name: checkout-orchestrator
  namespace: money-path
spec:
  replicas: 3
  selector:
    matchLabels:
      app: checkout-orchestrator
  template:
    metadata:
      labels:
        app: checkout-orchestrator
    spec:
      containers:
      - name: checkout-orchestrator
        image: gcr.io/instacommerce/checkout-orchestrator:v1.0.0
        ports:
        - containerPort: 8089
        env:
        - name: SERVER_PORT
          value: "8089"
        - name: CHECKOUT_DB_URL
          valueFrom:
            secretKeyRef:
              name: checkout-db-secrets
              key: url
        - name: TEMPORAL_HOST
          value: "temporal-server.infra"
        resources:
          requests:
            memory: "512Mi"
            cpu: "500m"
          limits:
            memory: "1Gi"
            cpu: "1500m"
        livenessProbe:
          httpGet:
            path: /actuator/health/live
            port: 8089
          initialDelaySeconds: 30
          periodSeconds: 10
        readinessProbe:
          httpGet:
            path: /actuator/health/ready
            port: 8089
          initialDelaySeconds: 20
          periodSeconds: 5
```

**Deploy via ArgoCD**:
```bash
argocd app create checkout-orchestrator \
  --repo https://github.com/instacommerce/infra \
  --path deploy/checkout-orchestrator \
  --dest-server https://kubernetes.default.svc \
  --dest-namespace money-path

argocd app sync checkout-orchestrator
```

---

## Health Checks

### Liveness Probe

**Endpoint**: `GET /actuator/health/live`

```bash
curl http://localhost:8089/actuator/health/live
```

**Response (healthy)**:
```json
{
  "status": "UP"
}
```

### Readiness Probe

**Endpoint**: `GET /actuator/health/ready`

**Depends On**: Database connectivity, Temporal server connection

```bash
curl http://localhost:8089/actuator/health/ready
```

**Response (ready)**:
```json
{
  "status": "UP",
  "components": {
    "db": {
      "status": "UP"
    }
  }
}
```

### Metrics

**Endpoint**: `GET /actuator/prometheus`

```bash
curl http://localhost:8089/actuator/prometheus | grep checkout
```

---

## Scaling

### Horizontal Scaling (Kubernetes)

```bash
# Scale to 5 replicas
kubectl scale deployment checkout-orchestrator \
  --replicas=5 \
  -n money-path

# Check rollout
kubectl rollout status deployment/checkout-orchestrator \
  -n money-path
```

### Vertical Scaling (Resources)

Edit deployment manifest:
```yaml
resources:
  requests:
    memory: "1Gi"       # Increase from 512Mi
    cpu: "1000m"        # Increase from 500m
  limits:
    memory: "2Gi"       # Increase from 1Gi
    cpu: "2000m"        # Increase from 1500m
```

---

## Rollback Procedure

### If Deployment Fails

```bash
# Check rollout history
kubectl rollout history deployment/checkout-orchestrator \
  -n money-path

# Rollback to previous version
kubectl rollout undo deployment/checkout-orchestrator \
  -n money-path

# Or rollback to specific revision
kubectl rollout undo deployment/checkout-orchestrator \
  --to-revision=3 \
  -n money-path
```

### Via ArgoCD

```bash
# View ArgoCD app status
argocd app get checkout-orchestrator

# Sync to previous version
argocd app sync checkout-orchestrator \
  --revision=<previous-git-commit>
```

---

## Database Migrations

### Apply Flyway Migrations

Migrations run automatically on startup:

```yaml
spring:
  flyway:
    enabled: true
    locations: classpath:db/migration
```

### Manual Migration Execution

```bash
./gradlew :services:checkout-orchestrator-service:flywayMigrate \
  -Dflyway.url=jdbc:postgresql://localhost:5432/checkout \
  -Dflyway.user=postgres \
  -Dflyway.password=postgres
```

### Verify Migrations

```bash
psql -h localhost -U postgres -d checkout -c "SELECT * FROM flyway_schema_history;"
```

---

## Troubleshooting

### Issue: High Checkout Latency (>2.5s)

```bash
# Check downstream service latencies
kubectl logs -n money-path -l app=checkout-orchestrator \
  --tail=100 | grep "latency"

# Check circuit breaker state
curl http://localhost:8089/actuator/health | jq '.components.circuitbreaker'

# Check Temporal workflow status
temporal workflow list \
  --namespace instacommerce \
  | grep checkout
```

### Issue: Idempotency Key Cache Miss (Duplicate Checkouts)

```sql
-- Check cache table
SELECT COUNT(*) FROM checkout_idempotency_keys
WHERE expires_at > now();

-- Check for expired entries
SELECT COUNT(*) FROM checkout_idempotency_keys
WHERE expires_at < now();

-- Manual cleanup
DELETE FROM checkout_idempotency_keys
WHERE expires_at < now();
```

### Issue: Circuit Breaker OPEN (Service Unavailable)

```bash
# Check which service is failing
curl http://localhost:8089/actuator/health/live \
  | jq '.components.circuitBreaker'

# Restart the failing downstream service
kubectl rollout restart deployment/payment-service \
  -n money-path

# Monitor recovery
watch -n 2 'curl http://localhost:8089/actuator/health/live | jq'
```

---

## Performance Tuning

### Database Connection Pool

```yaml
spring:
  datasource:
    hikari:
      maximum-pool-size: 20  # Increase if connections exhaust
      minimum-idle: 5        # Warm pool connections
      connection-timeout: 5000ms
      max-lifetime: 1800000ms  # 30 min
```

### Idempotency Cache Cleanup

Add scheduled cleanup:
```java
@Scheduled(fixedRate = 600000)  // Every 10 minutes
@SchedulerLock(name = "idempotency_cleanup")
public void cleanupExpiredKeys() {
    idempotencyKeyRepository.deleteExpired(Instant.now());
}
```

### Temporal Settings

```yaml
temporal:
  service-address: "temporal-server.infra:7233"
  namespace: instacommerce
  task-queue: CHECKOUT_ORCHESTRATOR_TASK_QUEUE
```

---

## Emergency Procedures

### Disable Checkout (Circuit Breaker Manual Override)

```bash
# Kill circuit breaker health indicator
kubectl set env deployment/checkout-orchestrator \
  CHECKOUT_CIRCUIT_BREAKER_DISABLED=true \
  -n money-path
```

### Reset All Circuit Breakers

```bash
curl -X POST \
  http://checkout-orchestrator:8089/actuator/circuitbreakers/cartService/reset
curl -X POST \
  http://checkout-orchestrator:8089/actuator/circuitbreakers/inventoryService/reset
curl -X POST \
  http://checkout-orchestrator:8089/actuator/circuitbreakers/paymentService/reset
curl -X POST \
  http://checkout-orchestrator:8089/actuator/circuitbreakers/pricingService/reset
curl -X POST \
  http://checkout-orchestrator:8089/actuator/circuitbreakers/orderService/reset
```

---

## Monitoring & Alerting

### Key Metrics to Monitor

1. **Checkout Success Rate**: Should be >99.9%
2. **P99 Latency**: Should be <2.5s
3. **Circuit Breaker State**: Should be CLOSED for all services
4. **Idempotency Cache Hit Rate**: Should be >50% during traffic spikes

### Prometheus Alerts

```yaml
- alert: CheckoutOrchestratorHighErrorRate
  expr: rate(http_requests_total{job="checkout-orchestrator",status=~"5.."}[5m]) > 0.001
  for: 5m
  annotations:
    summary: "Checkout orchestrator error rate >0.1%"

- alert: CircuitBreakerOpen
  expr: resilience4j_circuitbreaker_state{name!~".*Service"} > 0
  for: 2m
  annotations:
    summary: "Circuit breaker {{ $labels.name }} is OPEN"
```
