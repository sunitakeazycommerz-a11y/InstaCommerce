# Stream Processor Service

> **Go · Real-Time Kafka Stream Processing for Business Metrics**

Consumes events from multiple Kafka topics (orders, payments, riders, inventory) and computes real-time business metrics using sliding windows. Outputs aggregated counters and gauges to Redis for dashboard consumption and Prometheus for alerting. Includes a dedicated SLA monitor that detects delivery compliance breaches per zone.

## Architecture

```mermaid
graph LR
    KO[order.events] --> SP[Stream Processor :8108]
    KP[payment.events] --> SP
    KR[rider.events] --> SP
    KL[rider.location.updates] --> SP
    KI[inventory.events] --> SP
    SP --> RD[(Redis<br/>Aggregated Counters)]
    SP --> PROM[Prometheus /metrics]
    SP --> SLA[SLA Monitor<br/>Breach Alerts]
```

## Multi-Topic Consumption

```mermaid
flowchart TD
    subgraph Kafka Consumer Group
        C1[order.events consumer]
        C2[rider.events consumer]
        C3[rider.location.updates consumer]
        C4[payment.events consumer]
        C5[inventory.events consumer]
    end
    C1 --> OP[Order Processor]
    C2 --> RP[Rider Processor]
    C3 --> RP
    C4 --> PP[Payment Processor]
    C5 --> IP[Inventory Processor]
    OP --> RD[(Redis)]
    RP --> RD
    PP --> RD
    IP --> RD
    OP --> PROM[Prometheus]
    RP --> PROM
    PP --> PROM
    IP --> PROM
    OP --> SLA[SLA Monitor]
```

## Aggregation Pipeline

```mermaid
flowchart LR
    subgraph Order Processor
        O1[Orders per minute<br/>total / store / zone]
        O2[GMV running total<br/>daily]
        O3[Delivery duration<br/>histogram]
        O4[Cancellation rate]
    end
    subgraph Payment Processor
        P1[Payment success rate<br/>per method, 5-min window]
        P2[Revenue tracking]
        P3[Refund rate monitoring]
        P4[Failure code breakdown]
    end
    subgraph Rider Processor
        R1[Rider status counts<br/>active / idle / offline per zone]
        R2[Utilization rate]
        R3[Earnings tracker]
        R4[Location heatmap data]
    end
    subgraph Inventory Processor
        I1[Inventory velocity<br/>per SKU, 1hr window]
        I2[Stockout detection]
        I3["Cascade alert<br/>>10 SKUs at zero"]
    end
```

## SLA Monitoring

```mermaid
flowchart TD
    A[OrderDelivered Event] --> B[Calculate delivery time<br/>deliveredAt - placedAt]
    B --> C[Add to sliding window<br/>30-minute per zone]
    C --> D[Evict expired records]
    D --> E[Recalculate SLA %<br/>delivered ≤ 10 min / total]
    E --> F{SLA % < threshold<br/>and ≥ 5 orders?}
    F -->|Yes| G[Emit SLA Breach Alert<br/>zone, percentage, threshold]
    F -->|No| H[Update Prometheus gauge]
    G --> H
```

## Redis Output Keys

```mermaid
flowchart LR
    subgraph Orders
        OK1["orders:count:{minute}"]
        OK2["orders:store:{storeId}:{minute}"]
        OK3["orders:zone:{zoneId}:{minute}"]
        OK4["gmv:total:{date}"]
        OK5["delivery:time:{zoneId}:{minute}"]
        OK6["orders:cancelled:{zoneId}:{minute}"]
    end
    subgraph Riders
        RK1["riders:zone:{zoneId}:status"]
        RK2["riders:{riderId}:state"]
        RK3["riders:earnings:{riderId}:{date}"]
    end
    subgraph Payments
        PK1["payments:success_rate:{method}"]
        PK2["payments:revenue:{date}"]
    end
    subgraph Inventory
        IK1["inventory:velocity:{storeId}:{skuId}"]
        IK2["inventory:stockouts:{storeId}"]
    end
```

## Project Structure

```
stream-processor-service/
├── main.go                          # Kafka consumers, HTTP server, Redis init
├── processor/
│   ├── order_processor.go           # Order metrics: GMV, delivery time, cancellations
│   ├── payment_processor.go         # Payment success rate, revenue, refund monitoring
│   ├── rider_processor.go           # Rider status, utilization, earnings, location
│   ├── inventory_processor.go       # Stock velocity, stockout cascade detection
│   └── sla_monitor.go              # 30-min sliding window SLA compliance per zone
├── Dockerfile
└── go.mod
```

## Configuration

| Variable | Default | Description |
|---|---|---|
| `HTTP_PORT` | `8108` | HTTP listen port |
| `KAFKA_BROKERS` | `localhost:9092` | Comma-separated Kafka broker list |
| `CONSUMER_GROUP_ID` | `stream-processor` | Kafka consumer group ID |
| `REDIS_ADDR` | `localhost:6379` | Redis address |
| `REDIS_PASSWORD` | — | Redis password |

## Key Metrics

### Order Metrics
| Metric | Type | Description |
|---|---|---|
| `orders_total` | Counter | Orders by event_type, store_id, zone_id |
| `gmv_total_cents` | Counter | Gross Merchandise Value running total |
| `delivery_duration_minutes` | Histogram | Delivery time distribution by zone/store |
| `sla_compliance_ratio` | Gauge | SLA compliance percentage per zone |
| `order_cancellations_total` | Counter | Cancellations by store/zone |

### Payment Metrics
| Metric | Type | Description |
|---|---|---|
| `payments_total` | Counter | Payment events by type and method |
| `payments_revenue_total_cents` | Counter | Revenue running total |
| `payment_success_rate` | Gauge | Success rate per payment method |
| `payment_failures_by_code_total` | Counter | Failures by error code |
| `refunds_total` | Counter | Refunds by method |

### Rider Metrics
| Metric | Type | Description |
|---|---|---|
| `riders_by_status` | Gauge | Rider count by status and zone |
| `rider_deliveries_total` | Counter | Deliveries by rider/zone |
| `rider_earnings_total_cents` | Counter | Earnings by rider |
| `rider_location_updates_total` | Counter | Location pings received |

### Inventory Metrics
| Metric | Type | Description |
|---|---|---|
| `inventory_stock_updates_total` | Counter | Stock updates by type/store |
| `inventory_stockouts_total` | Counter | Stockout events per store |
| `inventory_cascade_alerts_total` | Counter | Cascade alerts (>10 SKUs at zero) |
| `inventory_velocity` | Gauge | Stock velocity per store/SKU |

### SLA Monitor Metrics
| Metric | Type | Description |
|---|---|---|
| `sla_window_percent` | Gauge | Current SLA % per zone (30-min window) |
| `sla_alerts_total` | Counter | SLA breach alerts emitted per zone |
| `sla_window_orders` | Gauge | Orders in current SLA window per zone |

## API Reference

### `GET /health`

Returns `{"status":"ok"}`.

### `GET /metrics`

Prometheus metrics endpoint.

## Build & Run

```bash
# Local
go build -o stream-processor .
KAFKA_BROKERS="localhost:9092" REDIS_ADDR="localhost:6379" ./stream-processor

# Docker
docker build -t stream-processor-service .
docker run -e KAFKA_BROKERS="..." -e REDIS_ADDR="..." -p 8108:8108 stream-processor-service
```

## Dependencies

- Go 1.22+
- `github.com/segmentio/kafka-go` (Kafka consumer)
- `github.com/redis/go-redis/v9` (Redis client)
- `github.com/prometheus/client_golang` (metrics with `promauto`)
