# Checkout Orchestrator Service - API Contract

## REST Endpoints

### Initiate Checkout

**Method**: `POST`
**Path**: `/checkout`
**Auth**: JWT Bearer token required

**Request Headers**:
```
Authorization: Bearer <JWT_TOKEN>
Idempotency-Key: <UUID or string> (optional)
Content-Type: application/json
```

**Request Body**:
```json
{
  "userId": "550e8400-e29b-41d4-a716-446655440000",
  "cartId": "550e8400-e29b-41d4-a716-446655440001",
  "currency": "INR",
  "deliveryAddressId": "550e8400-e29b-41d4-a716-446655440002"
}
```

**Response (200 OK)**:
```json
{
  "orderId": "550e8400-e29b-41d4-a716-446655440003",
  "status": "COMPLETED",
  "message": "Checkout successful",
  "totalCents": 99999
}
```

**Response (202 Accepted)** - Duplicate workflow detected:
```json
{
  "orderId": null,
  "status": "CHECKOUT_ALREADY_IN_PROGRESS: <CURRENT_WORKFLOW_STATUS>",
  "message": "Checkout already in progress for this idempotency key",
  "totalCents": 0
}
```

**Response (400 Bad Request)**:
```json
{
  "error": "VALIDATION_ERROR",
  "message": "Cannot checkout for another user",
  "details": {
    "principal": "user123",
    "requestedUserId": "user456"
  }
}
```

**Response (503 Service Unavailable)** - Circuit breaker open:
```json
{
  "error": "SERVICE_UNAVAILABLE",
  "message": "Downstream service (cart-service) is currently unavailable",
  "retryAfter": "30"
}
```

**Response (504 Gateway Timeout)**:
```json
{
  "error": "TIMEOUT",
  "message": "Checkout operation exceeded 5 minute timeout"
}
```

---

### Get Checkout Status

**Method**: `GET`
**Path**: `/checkout/{workflowId}/status`
**Auth**: JWT Bearer token (optional)

**Path Parameters**:
```
workflowId: string (format: "checkout-{userId}-{idempotencyKey}")
```

**Response (200 OK)**:
```json
{
  "workflowId": "checkout-550e8400-e29b-41d4-a716-446655440000-abc123",
  "status": "RUNNING"
}
```

**Status Values**:
- `RUNNING` - Workflow currently executing
- `COMPLETED` - Workflow finished successfully
- `FAILED` - Workflow failed with error
- `UNKNOWN` - Workflow ID not found or expired

---

## DTO Definitions

### CheckoutRequest
```java
public record CheckoutRequest(
    UUID userId,
    UUID cartId,
    String currency,
    UUID deliveryAddressId
) {}
```

### CheckoutResponse
```java
public record CheckoutResponse(
    UUID orderId,
    String status,
    long totalCents,
    long discountCents
) {}
```

### Idempotency Key Format

**Header Name**: `Idempotency-Key`
**Format**: UUID or string (max 64 chars)
**Behavior**:
- If provided by client: Used as-is
- If not provided: Generated as UUID.randomUUID()
- Cache TTL: 30 minutes from creation
- Duplicate detection: Exact string match

---

## Error Codes

| Code | HTTP | Description | Retry? |
|------|------|-------------|--------|
| VALIDATION_ERROR | 400 | Invalid input data | No |
| FORBIDDEN | 403 | Principal mismatch | No |
| SERVICE_UNAVAILABLE | 503 | Downstream service down | Yes |
| TIMEOUT | 504 | 5-minute execution timeout | Yes |
| CHECKOUT_IN_PROGRESS | 202 | Duplicate workflow detected | Yes (poll status) |
| INTERNAL_ERROR | 500 | Unexpected server error | Yes |

---

## Resilience4j Configuration (Per Service)

### cartService, inventoryService, paymentService, pricingService, orderService

```yaml
resilience4j:
  circuitbreaker:
    instances:
      cartService:
        slidingWindowType: COUNT_BASED
        slidingWindowSize: 20          # Track last 20 calls
        minimumNumberOfCalls: 10       # Need 10+ calls to evaluate
        failureRateThreshold: 50       # Open if failure rate > 50%
        waitDurationInOpenState: 30s   # Stay open 30 seconds
        permittedNumberOfCallsInHalfOpenState: 5  # Trial 5 calls in HALF_OPEN
        automaticTransitionFromOpenToHalfOpenEnabled: true
        recordExceptions:
          - java.io.IOException
          - java.net.SocketTimeoutException
          - org.springframework.web.client.RestClientException
```

---

## Integration Examples

### cURL

```bash
# Initiate checkout
curl -X POST http://localhost:8089/checkout \
  -H "Authorization: Bearer <JWT>" \
  -H "Idempotency-Key: 550e8400-e29b-41d4-a716-446655440000" \
  -H "Content-Type: application/json" \
  -d '{
    "userId": "550e8400-e29b-41d4-a716-446655440000",
    "cartId": "550e8400-e29b-41d4-a716-446655440001",
    "currency": "INR",
    "deliveryAddressId": "550e8400-e29b-41d4-a716-446655440002"
  }'

# Check status
curl -X GET "http://localhost:8089/checkout/checkout-user123-abc456/status" \
  -H "Authorization: Bearer <JWT>"
```

### Java RestTemplate

```java
RestTemplate restTemplate = new RestTemplate();
HttpHeaders headers = new HttpHeaders();
headers.setBearerAuth(jwtToken);
headers.set("Idempotency-Key", UUID.randomUUID().toString());

HttpEntity<CheckoutRequest> request = new HttpEntity<>(
    new CheckoutRequest(...),
    headers
);

ResponseEntity<CheckoutResponse> response = restTemplate.postForEntity(
    "http://checkout-service:8089/checkout",
    request,
    CheckoutResponse.class
);
```
