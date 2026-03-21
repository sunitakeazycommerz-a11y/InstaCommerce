# Order Service - API Contract

## REST Endpoints

### List Orders (Paginated)

**Method**: `GET`
**Path**: `/orders`
**Auth**: JWT Bearer token required

**Query Parameters**:
```
page: int (default 0)
size: int (default 20, max 100)
sort: string (default "created_at,desc")
```

**Request**:
```bash
GET /orders?page=0&size=20&sort=created_at,desc HTTP/1.1
Authorization: Bearer <JWT>
```

**Response (200 OK)**:
```json
{
  "content": [
    {
      "id": "550e8400-e29b-41d4-a716-446655440000",
      "userId": "550e8400-e29b-41d4-a716-446655440001",
      "status": "DELIVERED",
      "totalCents": 99999,
      "currency": "INR",
      "createdAt": "2026-03-21T10:30:00Z"
    }
  ],
  "totalElements": 150,
  "totalPages": 8,
  "currentPage": 0,
  "pageSize": 20
}
```

---

### Get Order Details

**Method**: `GET`
**Path**: `/orders/{id}`
**Auth**: JWT Bearer token required

**Path Parameters**:
```
id: UUID
```

**Response (200 OK)**:
```json
{
  "id": "550e8400-e29b-41d4-a716-446655440000",
  "userId": "550e8400-e29b-41d4-a716-446655440001",
  "storeId": "STORE-NYC-001",
  "status": "OUT_FOR_DELIVERY",
  "subtotalCents": 100000,
  "discountCents": 500,
  "totalCents": 99500,
  "currency": "INR",
  "couponCode": "SAVE10",
  "reservationId": "550e8400-e29b-41d4-a716-446655440002",
  "paymentId": "550e8400-e29b-41d4-a716-446655440003",
  "cancellationReason": null,
  "items": [
    {
      "id": "550e8400-e29b-41d4-a716-446655440004",
      "productId": "PROD-001",
      "productName": "Fresh Bread",
      "productSku": "BREAD-001",
      "quantity": 2,
      "unitPriceCents": 40000,
      "lineTotalCents": 80000,
      "pickedStatus": "PICKED"
    },
    {
      "id": "550e8400-e29b-41d4-a716-446655440005",
      "productId": "PROD-002",
      "productName": "Milk",
      "productSku": "MILK-001",
      "quantity": 1,
      "unitPriceCents": 19500,
      "lineTotalCents": 19500,
      "pickedStatus": "PICKED"
    }
  ],
  "createdAt": "2026-03-21T08:00:00Z",
  "updatedAt": "2026-03-21T10:15:00Z"
}
```

**Response (403 Forbidden)** - User trying to access another user's order:
```json
{
  "error": "FORBIDDEN",
  "message": "Cannot access order for another user"
}
```

---

### Get Order Status Only

**Method**: `GET`
**Path**: `/orders/{id}/status`
**Auth**: JWT Bearer token (optional)

**Response (200 OK)**:
```json
{
  "id": "550e8400-e29b-41d4-a716-446655440000",
  "status": "OUT_FOR_DELIVERY",
  "updatedAt": "2026-03-21T10:15:00Z"
}
```

---

### Cancel Order

**Method**: `POST`
**Path**: `/orders/{id}/cancel`
**Auth**: JWT Bearer token required

**Request Body**:
```json
{
  "reason": "Changed my mind about the items"
}
```

**Response (204 No Content)**:
```
HTTP/1.1 204 No Content
```

**Response (400 Bad Request)** - Cannot cancel after packing:
```json
{
  "error": "INVALID_STATE",
  "message": "Cannot cancel order with status PACKED"
}
```

---

## Internal API (Temporal Activity)

### Create Order (Called by checkout-orchestrator)

**Method**: `POST`
**Path**: `/orders` (Internal only)

**Request Body**:
```json
{
  "userId": "550e8400-e29b-41d4-a716-446655440001",
  "storeId": "STORE-NYC-001",
  "idempotencyKey": "550e8400-e29b-41d4-a716-446655440002",
  "items": [
    {
      "productId": "PROD-001",
      "productName": "Fresh Bread",
      "productSku": "BREAD-001",
      "quantity": 2,
      "unitPriceCents": 40000
    }
  ],
  "couponCode": "SAVE10",
  "reservationId": "550e8400-e29b-41d4-a716-446655440003",
  "paymentId": "550e8400-e29b-41d4-a716-446655440004"
}
```

**Response (201 Created)**:
```json
{
  "id": "550e8400-e29b-41d4-a716-446655440000",
  "status": "PENDING",
  "totalCents": 99500
}
```

---

## Error Codes

| Code | HTTP | Description |
|------|------|-------------|
| NOT_FOUND | 404 | Order ID doesn't exist |
| FORBIDDEN | 403 | Principal mismatch |
| INVALID_STATE | 400 | Cannot cancel after packing |
| CONFLICT | 409 | Duplicate idempotency key |
| INTERNAL_ERROR | 500 | Database/CDC error |

---

## DTO Definitions

### OrderSummaryResponse
```java
public record OrderSummaryResponse(
    UUID id,
    UUID userId,
    OrderStatus status,
    long totalCents,
    String currency,
    Instant createdAt
) {}
```

### OrderResponse
```java
public record OrderResponse(
    UUID id,
    UUID userId,
    String storeId,
    OrderStatus status,
    long subtotalCents,
    long discountCents,
    long totalCents,
    String currency,
    String couponCode,
    UUID reservationId,
    UUID paymentId,
    String cancellationReason,
    List<OrderItemResponse> items,
    Instant createdAt,
    Instant updatedAt
) {}
```

### CancelOrderRequest
```java
public record CancelOrderRequest(
    String reason
) {}
```
