# Mobile BFF Service - Operational Runbook

## Service Metadata
- **Canonical Port:** `8080`
- **Module:** `services/mobile-bff-service`
- **Framework:** Spring Boot 4 / WebFlux (Reactive Netty)
- **Readiness Probe:** `http://localhost:8080/actuator/health/readiness`
- **Liveness Probe:** `http://localhost:8080/actuator/health/liveness`
- **Local Startup:** `./gradlew :services:mobile-bff-service:bootRun --args='--server.port=8080'`

---

## Operational Procedures

### 1. Health & Upstream Circuit Breakers
```bash
# Check service health and downstream dependencies
curl -s http://localhost:8080/actuator/health | jq .

# Verify circuit breaker states
curl -s http://localhost:8080/actuator/prometheus | grep resilience4j_circuitbreaker_state
```

### 2. High Latency / Netty EventLoop Saturation
**Symptom:** P99 mobile response latency $> 500$ ms.
**Resolution:**
1. Check Redis cache latency (`redis-cli -h $REDIS_HOST --latency`).
2. Inspect slow upstream service response times (catalog `:8082`, search `:8088`, cart `:8090`).
3. If thread pools are blocked by synchronous operations, scale out deployment replicas:
   ```bash
   kubectl scale deploy mobile-bff-service -n instacommerce --replicas=5
   ```
