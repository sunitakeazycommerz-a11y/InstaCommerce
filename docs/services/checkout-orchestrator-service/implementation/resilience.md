# Checkout Orchestrator Service - Resilience & Retry Logic

## Circuit Breaker Configuration

All downstream service calls are protected by Resilience4j Circuit Breakers.

### Circuit Breaker Instances

**All instances share identical configuration**:

```yaml
resilience4j:
  circuitbreaker:
    instances:
      cartService:
        registerHealthIndicator: true
        slidingWindowType: COUNT_BASED
        slidingWindowSize: 20
        minimumNumberOfCalls: 10
        failureRateThreshold: 50
        waitDurationInOpenState: 30s
        permittedNumberOfCallsInHalfOpenState: 5
        automaticTransitionFromOpenToHalfOpenEnabled: true
        recordExceptions:
          - java.io.IOException
          - java.net.SocketTimeoutException
          - org.springframework.web.client.RestClientException

      # Same config for:
      inventoryService
      orderService
      paymentService
      pricingService
```

### State Machine

```
       CLOSED (healthy)
         ↓ ↑
    (failure rate > 50%)
         ↓ ↑
    OPEN (30s) ← automaticTransitionFromOpenToHalfOpenEnabled: true
         ↓
    HALF_OPEN (trial mode)
         ↓
    5 successful calls → CLOSED
    OR 1 failure → OPEN
```

### Metrics

- **Sliding Window**: 20 calls (COUNT_BASED)
- **Minimum Calls**: 10 (require 10+ calls before evaluating failure rate)
- **Failure Threshold**: 50% (open if ≥50% of calls fail)
- **Wait Duration**: 30 seconds (stay OPEN before transitioning to HALF_OPEN)
- **Half-Open Trials**: 5 calls (permit 5 calls in trial mode)

### Recorded Exceptions

- `java.io.IOException` - Network I/O errors
- `java.net.SocketTimeoutException` - Connection timeout
- `org.springframework.web.client.RestClientException` - HTTP client errors (4xx, 5xx)

### Ignored Exceptions

- None configured (all exceptions are recorded)

---

## Timeout Configuration

### HTTP Connection Timeouts (Per Service)

```yaml
checkout:
  clients:
    cart:
      connect-timeout: 5000ms  # 5 sec to establish connection
      read-timeout: 10000ms    # 10 sec to read response
    inventory:
      connect-timeout: 5000ms
      read-timeout: 10000ms
    payment:
      connect-timeout: 5000ms
      read-timeout: 10000ms
    pricing:
      connect-timeout: 5000ms
      read-timeout: 10000ms
    order:
      connect-timeout: 5000ms
      read-timeout: 10000ms
```

### Temporal Workflow Timeout

```yaml
temporal:
  # Workflow execution timeout: 5 minutes
  # If workflow doesn't complete within 5 min → automatic termination
  # Default in code: Duration.ofMinutes(5)
```

---

## Retry Policy (Temporal Activities)

**Built into Temporal**: Activities retry automatically per Temporal retry policy.

**Default Retry Behavior** (if not overridden):
- **maxAttempts**: 3 (hardcoded in CheckoutController)
- **backoffMultiplier**: 2 (exponential backoff)
- **initialInterval**: 1 second

**Activity-Level Code**:
```java
WorkflowOptions options = WorkflowOptions.newBuilder()
    .setWorkflowExecutionTimeout(Duration.ofMinutes(5))  // 5-min timeout
    .build();
```

---

## Fallback Strategy

Checkout Orchestrator does **NOT** use explicit fallbacks. Instead:

1. **If Circuit Breaker OPEN**: Return 503 Service Unavailable
2. **If Activity Timeout**: Workflow times out after 5 minutes
3. **If HTTP Timeout**: Connection timeout → IOException → retry via Resilience4j
4. **If All Retries Fail**: Workflow fails with error

**No Degraded Mode**: Checkout cannot proceed without all services available.

---

## Health Indicators

### Circuit Breaker Health

```json
{
  "status": "UP",
  "components": {
    "circuitBreakerCartService": {
      "status": "UP",
      "details": {
        "state": "CLOSED",
        "failureRate": 0.0,
        "bufferedCalls": 5,
        "failedCalls": 0,
        "successfulCalls": 5
      }
    },
    "circuitBreakerInventoryService": {
      "state": "OPEN",
      "failureRate": 75.0,
      "bufferedCalls": 4,
      "failedCalls": 3,
      "successfulCalls": 1
    }
  }
}
```

**Health Probe Endpoint**: `/actuator/health`

---

## Monitoring & Alerts

### Metrics Published

- `resilience4j.circuitbreaker.calls` - Tagged by state (closed, open, half_open)
- `resilience4j.circuitbreaker.buffered.calls` - Current buffer size
- `resilience4j.circuitbreaker.failure.rate` - Failure percentage

### Prometheus Queries

```promql
# Circuit breaker state
resilience4j_circuitbreaker_state{name="cartService"}

# Failure rate
resilience4j_circuitbreaker_failure_rate{name="cartService"}

# Buffered calls
resilience4j_circuitbreaker_buffered_calls{name="cartService"}
```

### Recommended Alerts

- **Alert**: CircuitBreaker OPEN state for >2 min
- **Alert**: Failure rate >60% on any service
- **Alert**: Workflow execution timeout >10% of requests

---

## Recovery Procedures

### Manual Circuit Breaker Reset

**Actuator Endpoint** (if enabled):
```bash
POST /actuator/circuitbreakers/cartService/reset
```

**Or programmatically**:
```java
circuitBreaker.reset();
```

### Temporal Workflow Recovery

**Query stuck workflow**:
```
temporal workflow query \
  --workflow-id="checkout-user123-abc456" \
  --query-type="getStatus"
```

**Terminate workflow** (if needed):
```
temporal workflow terminate \
  --workflow-id="checkout-user123-abc456" \
  --reason="manual termination for testing"
```

---

## Example: Failure Cascade

```
1. Payment Service starts returning 503
2. CircuitBreakerPaymentService tracks failures
3. After 10 calls: 7 failures → 70% failure rate > 50% threshold
4. CircuitBreaker transitions to OPEN
5. Next checkout request: Immediate CallNotPermittedException (no HTTP call)
6. User receives 503 Service Unavailable immediately
7. Wait 30 seconds (waitDurationInOpenState)
8. Next request enters HALF_OPEN state
9. If payment-service recovered: 5 successful calls → CLOSED
10. If still failing: Back to OPEN, repeat cycle
```

**Total recovery time**: 30 seconds + monitoring latency
