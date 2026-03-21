# Checkout Orchestrator Service - High-Level Design

## Deployed Topology

```mermaid
graph TB
    Client["Client<br/>(Mobile/Web)"]
    APIGateway["API Gateway<br/>(Istio)"]
    CO["checkout-orchestrator-service<br/>Port: 8089"]
    TW["Temporal Worker<br/>(Task Queue:<br/>CHECKOUT_ORCHESTRATOR_TQ)"]
    TS["Temporal Server<br/>:7233"]

    Cart["cart-service<br/>:8084"]
    Inventory["inventory-service<br/>:8083"]
    Payment["payment-service<br/>:8086"]
    Pricing["pricing-service<br/>:8087"]
    Order["order-service<br/>:8085"]

    DB["PostgreSQL<br/>checkout DB"]

    Client -->|HTTPS| APIGateway
    APIGateway -->|REST| CO
    CO -->|Read Idempotency| DB
    CO -->|Start Workflow| TS
    TW -->|Register| TS
    TW -->|Activity Call| Cart
    TW -->|Activity Call| Inventory
    TW -->|Activity Call| Payment
    TW -->|Activity Call| Pricing
    TW -->|Activity Call| Order
    Cart --> Cart
    Inventory --> Inventory
    Payment --> Payment
    Pricing --> Pricing
    Order --> Order

    style CO fill:#FFE5B4
    style TS fill:#E6E6FA
    style TW fill:#B4E5FF
```

## Traffic Flow

1. **Client** sends POST /checkout request to API Gateway with `Idempotency-Key` header
2. **Checkout Controller** validates JWT + checks idempotency cache in PostgreSQL
3. **If cache hit**: Returns cached response immediately
4. **If cache miss**:
   - Generates workflowId = "checkout-{principal}-{idempotencyKey}"
   - Starts Temporal workflow via WorkflowClient
   - Workflow executes activities for cart → inventory → payment → pricing → order
   - Caches response in PostgreSQL with 30-min TTL
5. **Temporal Server** orchestrates activities across distributed services

## Key Dependencies

```
checkout-orchestrator-service
├── Temporal Server (workflow orchestration engine)
├── PostgreSQL (idempotency cache + ShedLock)
├── cart-service (sync HTTP, circuit breaker)
├── inventory-service (sync HTTP, circuit breaker)
├── payment-service (sync HTTP, circuit breaker)
├── pricing-service (sync HTTP, circuit breaker)
└── order-service (sync HTTP, circuit breaker)
```

## Failure Handling

- **Circuit Breaker**: 50% failure threshold → open after 10 calls, retry after 30s
- **Idempotent Retries**: Same idempotencyKey always returns same response
- **Timeout**: 5-minute workflow timeout
- **DLQ**: Failed workflows surface via Temporal DLQ (manual intervention required)
