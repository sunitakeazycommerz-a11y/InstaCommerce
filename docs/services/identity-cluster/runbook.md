# Identity Cluster - Runbook & Operations Guide

## Pre-Deployment Checklist

### Code Review & Testing
- [ ] Code review approved in GitHub (require 2+ approvals for production changes)
- [ ] All unit tests passing: `./gradlew :services:identity-service:test`
- [ ] Integration tests passing with PostgreSQL: `./gradlew :services:identity-service:test --tests "*IT"`
- [ ] Security scanning completed: `./gradlew build dependencyCheckAnalyze`
- [ ] OWASP dependency check passes (no HIGH/CRITICAL CVEs)

### Database Migrations
- [ ] Flyway migration reviewed for backward compatibility
- [ ] Migration tested against production schema replica
- [ ] Rollback procedure documented and tested
- [ ] Estimated migration time < 5 min (or use blue-green strategy)

### Secrets & Configuration
- [ ] JWT RSA keys rotated (if applicable): new keys in GCP Secret Manager
- [ ] Database credentials updated in Secret Manager
- [ ] All environment variables defined in `values-prod.yaml`
- [ ] CORS origins list updated (if changed)
- [ ] Rate limit thresholds reviewed

### Infrastructure
- [ ] PostgreSQL backup created (automated daily, but manual backup before major change)
- [ ] Kafka `identity.events` topic exists with 3 replicas
- [ ] Istio Gateway rules verified: mTLS enabled, rate limiting configured
- [ ] Load balancer health checks configured (HTTP GET `/actuator/health/readiness`)
- [ ] Network policies allow identity-service → Cloud SQL traffic

### Observability
- [ ] Prometheus alert rules reviewed and activated
- [ ] Datadog/observability platform dashboards created/updated
- [ ] On-call runbook updated with new known issues
- [ ] OpenTelemetry collector endpoint confirmed reachable

---

## Deployment Procedures

### Standard Rolling Deployment (Zero-Downtime)

**Prerequisites**: min 2 replicas in production cluster

```bash
# 1. Build and push new image
./gradlew :services:identity-service:bootJar
docker build -t gcr.io/my-project/identity-service:v1.2.3 services/identity-service/
docker push gcr.io/my-project/identity-service:v1.2.3

# 2. Update Helm values
cat > deploy/helm/values-prod.yaml <<EOF
identity-service:
  image:
    tag: v1.2.3
    pullPolicy: IfNotPresent
  replicaCount: 5
  ...
EOF

# 3. Apply via ArgoCD (or manual Helm)
helm upgrade identity-service deploy/helm/ -f deploy/helm/values-prod.yaml -n instacommerce

# 4. Monitor rollout
kubectl rollout status deployment/identity-service -n instacommerce --timeout=5m
kubectl logs -f deployment/identity-service -n instacommerce

# 5. Verify
curl -H "Authorization: Bearer $TEST_TOKEN" http://identity-service.instacommerce.svc:8080/users/me
```

**Expected behavior**: Old pods drain requests (30s timeout), new pods come up, JWKS endpoint remains available throughout.

### Blue-Green Deployment (For Large Schema Changes)

**Situation**: Flyway migration takes > 5 min or previous rollback failed

```bash
# 1. Deploy new version to staging environment
helm install identity-service-green deploy/helm/ \
  --set-string=metadata.labels.version=green \
  -n instacommerce

# 2. Run smoke tests
curl -H "Authorization: Bearer $TEST_TOKEN" \
  http://identity-service-green.instacommerce.svc:8080/actuator/health/readiness

# 3. Switch traffic (Istio VirtualService)
kubectl patch vs identity-service -n instacommerce -p '{
  "spec": {
    "hosts": ["*"],
    "http": [{
      "route": [{
        "destination": {
          "host": "identity-service-green"
        }
      }]
    }]
  }
}'

# 4. Verify success for 5 minutes
watch -n 1 'kubectl logs -f deployment/identity-service-green -n instacommerce | grep ERROR'

# 5. Clean up old (blue) deployment
helm uninstall identity-service -n instacommerce
helm rename identity-service-green identity-service
```

### Emergency Rollback

**Situation**: New version has critical bug; must rollback immediately

```bash
# 1. Immediate traffic switch (revert VirtualService)
kubectl patch vs identity-service -n instacommerce -p '{
  "spec": {
    "http": [{
      "route": [{
        "destination": {
          "host": "identity-service"
        },
        "weight": 0
      }]
    }]
  }
}'

# 2. Revert Helm release
helm rollback identity-service -1 -n instacommerce
kubectl rollout status deployment/identity-service -n instacommerce

# 3. If Flyway migration caused issue, rollback database
# (Manual SQL: see migration rollback notes)

# 4. Alert team & start incident
kubectl annotate event identity-service-rollback \
  -n instacommerce \
  reason="CRITICAL_BUG" \
  message="Reverted to v1.2.1"

# 5. Post-incident review scheduled
```

---

## Health Check Procedures

### Liveness Probe (Is pod alive?)

```bash
# Direct test
curl http://localhost:8081/actuator/health/liveness

# Expected response (200 OK)
{
  "status": "UP"
}

# Kubernetes probe
kubectl get pods -n instacommerce -o custom-columns=\
NAME:.metadata.name,\
READY:.status.conditions[?(@.type==\"Ready\")].status,\
STARTED:.status.conditions[?(@.type==\"ContainersReady\")].status
```

### Readiness Probe (Is pod ready to receive traffic?)

```bash
# Direct test (includes database connectivity)
curl http://localhost:8081/actuator/health/readiness

# Expected response (200 OK if DB is reachable)
{
  "status": "UP",
  "components": {
    "db": {
      "status": "UP",
      "details": {
        "database": "PostgreSQL",
        "hello": 1
      }
    }
  }
}

# If readiness fails (503)
kubectl describe pod identity-service-xyz -n instacommerce | grep "Readiness"
```

### Application Health (Detailed)

```bash
# Metrics endpoint
curl http://localhost:8081/actuator/prometheus | grep -E "auth_login_total|auth_refresh_total"

# Expected output
auth_login_total{result="success"} 1234
auth_login_total{result="failure"} 45
auth_refresh_total 2345

# Connection pool status
curl http://localhost:8081/actuator/prometheus | grep "hikaricp"

hikaricp_connections{pool="HikariPool-1"} 5
hikaricp_connections_max{pool="HikariPool-1"} 20
hikaricp_connections_idle{pool="HikariPool-1"} 3
```

---

## Troubleshooting Guide

### Problem: 429 Too Many Requests (Rate Limit Hit)

**Symptom**: Login requests return 429 with `Retry-After: 60` header

**Root Cause Analysis**:
```bash
# 1. Check rate limit metrics
curl http://localhost:8081/actuator/prometheus | grep "rate_limiter"

# 2. Check pod logs for rate limit threshold
kubectl logs deployment/identity-service -n instacommerce | grep -i "rate"

# 3. Identify client IP
curl http://localhost:8081/actuator/prometheus | grep "client_ip"
```

**Resolution**:
```bash
# Temporary: Increase rate limit threshold
kubectl set env deployment/identity-service \
  -n instacommerce \
  IDENTITY_LOGIN_RATE_LIMIT=10/60 \  # 10 requests per 60 seconds
  IDENTITY_REGISTER_RATE_LIMIT=5/60

# Permanent: Update values-prod.yaml
echo "loginRateLimit: 10/60" >> deploy/helm/values-prod.yaml
helm upgrade identity-service deploy/helm/ -f deploy/helm/values-prod.yaml -n instacommerce
```

**Note**: Rate limiting is pod-local (Caffeine cache). In multi-replica deployment, attacker can bypass by hitting different pods. Account lockout (DB-persisted) is the backstop.

### Problem: JWT Validation Failures (401 Unauthorized)

**Symptom**: Valid-looking JWT tokens rejected by downstream services

**Root Cause Analysis**:
```bash
# 1. Check JWKS endpoint availability
curl http://identity-service:8080/.well-known/jwks.json | jq .

# 2. Verify JWT structure manually
# Use https://jwt.io and paste token; check payload.iss and payload.aud

# 3. Check RequestAuthentication policy
kubectl get RequestAuthentication -n instacommerce
kubectl describe ra/identity-policy -n instacommerce

# 4. Inspect pod logs for JWT validation errors
kubectl logs deployment/identity-service -n instacommerce | grep -i "jwt\|signature"
```

**Resolution**:
```bash
# If JWKS endpoint is stale:
kubectl rollout restart deployment/identity-service -n instacommerce

# If JWT signing failed:
kubectl exec -it deployment/identity-service -n instacommerce -- \
  curl http://localhost:8081/.well-known/jwks.json

# If downstream service can't reach JWKS:
kubectl logs deployment/api-gateway -n instacommerce | grep "jwks"
# → Check network policy: allow identity-service:8080/tcp from all pods
```

### Problem: Database Connection Pool Exhaustion

**Symptom**: Requests timeout, logs show "Timeout waiting for idle object"

**Root Cause Analysis**:
```bash
# 1. Check connection pool metrics
curl http://localhost:8081/actuator/prometheus | grep "hikaricp_connections"

hikaricp_connections{pool="HikariPool-1"} 20  # All 20 connections in use!
hikaricp_connections_pending{pool="HikariPool-1"} 10

# 2. Check active database connections from pod
kubectl exec deployment/identity-service -n instacommerce -- \
  psql -h $IDENTITY_DB_URL -U $IDENTITY_DB_USER -c "SELECT count(*) FROM pg_stat_activity WHERE datname='identity_db';"

# 3. Check for slow queries blocking connections
kubectl exec deployment/identity-service -n instacommerce -- \
  psql -h $IDENTITY_DB_URL -c "SELECT pid, usename, query, query_start FROM pg_stat_activity WHERE state='active';"
```

**Resolution**:
```bash
# Immediate: Increase pool size
kubectl set env deployment/identity-service -n instacommerce \
  SPRING_DATASOURCE_HIKARI_MAXIMUMPOOLSIZE=30

# Investigate: Check for N+1 queries in code
grep -r "findBy" src/main/java/com/instacommerce/identity/ | head -20

# Permanent: Optimize slow queries
# → Add database indexes
# → Implement query caching
# → Use connection pooling optimization patterns
```

### Problem: Outbox Events Not Published (GDPR Erasure Stalled)

**Symptom**: User deleted via `DELETE /users/me`, but erasure event never reaches other services

**Root Cause Analysis**:
```bash
# 1. Check outbox table for stuck events
kubectl exec -it deployment/identity-service -n instacommerce -- \
  psql -h $IDENTITY_DB_URL -c \
  "SELECT id, event_type, created_at FROM outbox_events WHERE sent=false ORDER BY created_at LIMIT 10;"

# 2. Check outbox relay logs
kubectl logs deployment/outbox-relay-service -n instacommerce | grep -i "error\|failed" | tail -20

# 3. Check Kafka topic reachability
kubectl exec -it deployment/outbox-relay-service -n instacommerce -- \
  kafka-console-consumer.sh --bootstrap-server kafka:9092 --topic identity.events --from-beginning --max-messages 1
```

**Resolution**:
```bash
# If relay pod is down:
kubectl get pods -n instacommerce | grep outbox-relay
kubectl describe pod outbox-relay-xyz -n instacommerce

# If Kafka is down:
kubectl logs statefulset/kafka -n instacommerce

# If stuck event blocking relay:
# Option 1: Skip stuck event (dangerous)
kubectl exec -it deployment/identity-service -n instacommerce -- \
  psql -h $IDENTITY_DB_URL -c \
  "UPDATE outbox_events SET sent=true WHERE id='<stuck-uuid>';"

# Option 2: Retry entire batch
kubectl exec -it deployment/outbox-relay-service -n instacommerce -- \
  kill -USR1 <relay-pid>  # Signal graceful reload
```

---

## Scaling Operations

### Scale Up (Handle Traffic Spike)

```bash
# 1. Horizontal scaling via HPA
kubectl get hpa identity-service -n instacommerce

# 2. Check current replicas and metrics
kubectl get deployment identity-service -n instacommerce \
  -o jsonpath='{.spec.replicas}' && echo " current replicas"

# 3. Manual scale if HPA is not fast enough
kubectl scale deployment identity-service \
  --replicas=10 \
  -n instacommerce

# 4. Monitor pod startup
watch -n 2 'kubectl get pods -n instacommerce -l app=identity-service | grep Running'

# 5. Verify readiness
kubectl wait --for=condition=Ready pod \
  -l app=identity-service \
  -n instacommerce \
  --timeout=5m
```

### Scale Down (Cost Optimization)

```bash
# 1. Drain connections gracefully
kubectl scale deployment identity-service \
  --replicas=2 \
  -n instacommerce

# 2. Verify no surge in error rates
kubectl logs deployment/identity-service -n instacommerce | grep -c "ERROR"
```

---

## Database Maintenance

### Backup & Recovery

```bash
# 1. Automated backup (daily, managed by Cloud SQL)
gcloud sql backups list --instance=identity-db-prod

# 2. Manual backup before major change
gcloud sql backups create \
  --instance=identity-db-prod \
  --description="Pre-v1.2.3-deployment"

# 3. Restore from backup (if needed)
gcloud sql backups restore <backup-id> \
  --backup-instance=identity-db-prod
```

### Statistics & Vacuuming

```bash
# 1. Analyze query plans (check index usage)
kubectl exec -it deployment/identity-service -n instacommerce -- \
  psql -h $IDENTITY_DB_URL -c "ANALYZE;"

# 2. Check table bloat
kubectl exec -it deployment/identity-service -n instacommerce -- \
  psql -h $IDENTITY_DB_URL -c \
  "SELECT schemaname, tablename, pg_size_pretty(pg_total_relation_size(schemaname||'.'||tablename)) \
   FROM pg_tables WHERE schemaname='public' ORDER BY pg_total_relation_size DESC LIMIT 10;"

# 3. Cleanup old audit logs (retention: 730 days)
kubectl exec -it deployment/identity-service -n instacommerce -- \
  psql -h $IDENTITY_DB_URL -c \
  "DELETE FROM audit_log WHERE created_at < now() - interval '730 days';"
```

---

## Monitoring & Alerting

### Key Metrics to Watch

| Metric | Threshold | Alert | Action |
|--------|-----------|-------|--------|
| `auth.login.total{result="failure"}` rate | > 10/min | Warning | Check for brute force |
| `auth.refresh.total` latency p95 | > 500ms | Warning | Check BCrypt latency |
| `hikaricp.connections.pending` | > 5 | Warning | Check for connection leak |
| `http.server.requests` 5xx errors | > 0.5% | Critical | Check logs for exceptions |
| Database pool exhaustion | > 80% | Warning | Scale pods or increase pool |
| JWT key unavailable | — | Critical | Check Secret Manager access |

### Prometheus Alert Rules

```yaml
groups:
  - name: identity-service
    rules:
      - alert: HighLoginFailureRate
        expr: rate(auth_login_total{result="failure"}[5m]) > 10
        for: 5m
        annotations:
          summary: "High login failure rate detected"

      - alert: DatabasePoolExhaustion
        expr: hikaricp_connections{pool="HikariPool-1"} / hikaricp_connections_max{pool="HikariPool-1"} > 0.8
        for: 2m
        annotations:
          summary: "Database connection pool > 80% utilization"
```

---

## Incident Response

### Page On-Call (Critical)

- [ ] JWKS endpoint unreachable (503)
- [ ] Database down (readiness probe fails)
- [ ] Authentication failures > 50% of requests
- [ ] Cascading failures in dependent services

### Warning (Non-Critical)

- [ ] High login failure rate
- [ ] Connection pool > 80% utilization
- [ ] Audit log write latency > 1 second
- [ ] Outbox relay lag > 5 minutes

---

## References & Resources

- [Prometheus Alerting](https://prometheus.io/docs/alerting/latest/overview/)
- [Kubernetes Troubleshooting Guide](https://kubernetes.io/docs/tasks/debug-application-cluster/debug-application/)
- [Spring Boot Actuator](https://spring.io/guides/gs/actuator-service/)
- [PostgreSQL Administration](https://www.postgresql.org/docs/15/admin.html)
