# Checkout Orchestrator - ER Diagram & Storage Schema

## Database Schema (Flyway-Managed)

```mermaid
erDiagram
    CHECKOUT_IDEMPOTENCY_KEYS {
        uuid id PK "gen_random_uuid()"
        varchar(255) idempotency_key UK "Client-provided or auto-generated"
        text checkout_response "JSON-serialized CheckoutResponse"
        timestamptz created_at "DEFAULT now()"
        timestamptz expires_at "NOT NULL, typically now()+30min"
    }

    SHEDLOCK {
        varchar(64) name PK "Lock name: IdempotencyKeyCleanupJob"
        timestamptz lock_until "When lock expires"
        timestamptz locked_at "When lock was acquired"
        varchar(255) locked_by "Instance identifier"
    }
```

## Idempotency Keys Table Details

```mermaid
graph TB
    subgraph Schema["V1__create_checkout_idempotency_keys"]
        Table["checkout_idempotency_keys"]

        Col1["id UUID PRIMARY KEY\nDEFAULT gen_random_uuid()"]
        Col2["idempotency_key VARCHAR(255)\nUNIQUE NOT NULL"]
        Col3["checkout_response TEXT\nJSON-serialized"]
        Col4["created_at TIMESTAMPTZ\nDEFAULT now()"]
        Col5["expires_at TIMESTAMPTZ\nNOT NULL"]

        Index1["idx_checkout_idempotency_keys_key\nON idempotency_key"]
        Index2["idx_checkout_idempotency_keys_expires\nON expires_at"]
    end

    Table --> Col1
    Table --> Col2
    Table --> Col3
    Table --> Col4
    Table --> Col5

    Col2 --> Index1
    Col5 --> Index2

    style Table fill:#4f46e5,color:#fff
    style Index1 fill:#f59e0b,color:#000
    style Index2 fill:#f59e0b,color:#000
```

## ShedLock Table Details

```mermaid
graph TB
    subgraph Schema["V2__create_shedlock"]
        Table["shedlock"]

        Col1["name VARCHAR(64) PRIMARY KEY\nLock identifier"]
        Col2["lock_until TIMESTAMPTZ NOT NULL\nLock expiration time"]
        Col3["locked_at TIMESTAMPTZ NOT NULL\nLock acquisition time"]
        Col4["locked_by VARCHAR(255) NOT NULL\nInstance hostname/ID"]
    end

    subgraph Usage["ShedLock Usage"]
        Job["IdempotencyKeyCleanupJob"]
        LockName["name = 'IdempotencyKeyCleanupJob'"]
        LockConfig["lockAtLeastFor = PT5M\nlockAtMostFor = PT30M"]
    end

    Table --> Col1
    Table --> Col2
    Table --> Col3
    Table --> Col4

    Job --> LockName --> Table
    Job --> LockConfig

    style Table fill:#6366f1,color:#fff
```

## Checkout Response JSON Schema

```mermaid
graph TB
    subgraph CheckoutResponse["checkout_response (JSON)"]
        SuccessResponse["Success Response:\n{\n  orderId: 'ord_abc123',\n  status: 'COMPLETED',\n  totalCents: 4599,\n  estimatedDeliveryMinutes: 35\n}"]

        FailedResponse["Failed Response:\n{\n  orderId: null,\n  status: 'FAILED: Payment declined',\n  totalCents: 0,\n  estimatedDeliveryMinutes: 0\n}"]
    end

    style SuccessResponse fill:#dcfce7,color:#000
    style FailedResponse fill:#fecaca,color:#000
```

## Data Flow: Idempotency Key Lifecycle

```mermaid
graph TB
    subgraph Creation["Key Creation"]
        Request["POST /checkout"]
        Header["Idempotency-Key header"]
        Generate["Auto-generate UUID\n(if not provided)"]
        Lookup["SELECT from DB"]
    end

    subgraph NotFound["Key Not Found"]
        StartWorkflow["Start Temporal workflow"]
        Execute["Execute saga"]
        Complete["Workflow completes"]
        Insert["INSERT INTO\ncheckout_idempotency_keys"]
    end

    subgraph Found["Key Found"]
        CheckExpiry["Check expires_at"]
        Expired["expires_at < now()"]
        Valid["expires_at >= now()"]
        ReturnCached["Return cached response\n(fast path)"]
    end

    subgraph Cleanup["Cleanup (Hourly)"]
        CleanupJob["IdempotencyKeyCleanupJob"]
        Query["SELECT WHERE\nexpires_at < now() - 24h"]
        Delete["DELETE expired rows"]
    end

    Request --> Header --> Lookup
    Header -->|Missing| Generate --> Lookup

    Lookup -->|Not found| StartWorkflow
    StartWorkflow --> Execute --> Complete --> Insert

    Lookup -->|Found| CheckExpiry
    CheckExpiry --> Expired --> StartWorkflow
    CheckExpiry --> Valid --> ReturnCached

    CleanupJob --> Query --> Delete

    style Insert fill:#22c55e,color:#fff
    style ReturnCached fill:#3b82f6,color:#fff
    style Delete fill:#f59e0b,color:#000
```

## No Domain State Persistence

```mermaid
graph TB
    subgraph CheckoutOrchestrator["Checkout Orchestrator Service"]
        IdempotencyDB["PostgreSQL\n(idempotency keys only)"]
        NoOrderData["❌ No order data"]
        NoPaymentData["❌ No payment data"]
        NoInventoryData["❌ No inventory data"]
    end

    subgraph DownstreamServices["Domain Services (Source of Truth)"]
        CartService["cart-service\n→ cart DB"]
        PricingService["pricing-service\n→ quotes DB"]
        InventoryService["inventory-service\n→ reservations DB"]
        PaymentService["payment-service\n→ payments DB"]
        OrderService["order-service\n→ orders DB"]
    end

    subgraph TemporalCluster["Temporal Cluster"]
        WorkflowState["Workflow History\n(Event Sourcing)"]
        ActivityResults["Activity Results"]
        CompensationState["Compensation State"]
    end

    CheckoutOrchestrator --> DownstreamServices
    CheckoutOrchestrator --> TemporalCluster

    style IdempotencyDB fill:#4f46e5,color:#fff
    style NoOrderData fill:#fecaca,color:#000
    style NoPaymentData fill:#fecaca,color:#000
    style NoInventoryData fill:#fecaca,color:#000
    style WorkflowState fill:#6366f1,color:#fff
```

## Temporal Workflow State (External)

```mermaid
graph TB
    subgraph TemporalDB["Temporal Database (PostgreSQL/Cassandra)"]
        Namespace["namespace: default"]
        TaskQueue["taskQueue: CHECKOUT_ORCHESTRATOR_TASK_QUEUE"]

        WorkflowExecution["workflow_executions\n- workflow_id\n- run_id\n- state"]

        History["workflow_history_events\n- workflow_id\n- event_id\n- event_type\n- event_data"]

        Activities["activity_info\n- activity_id\n- schedule_id\n- state"]
    end

    subgraph EventTypes["Workflow History Event Types"]
        Started["WorkflowExecutionStarted"]
        ActivityScheduled["ActivityTaskScheduled"]
        ActivityCompleted["ActivityTaskCompleted"]
        ActivityFailed["ActivityTaskFailed"]
        WorkflowCompleted["WorkflowExecutionCompleted"]
        WorkflowFailed["WorkflowExecutionFailed"]
    end

    TemporalDB --> EventTypes

    style TemporalDB fill:#6366f1,color:#fff
    style WorkflowExecution fill:#e0e7ff,color:#000
```

## Prometheus Metrics Schema

```
Checkout Orchestrator Metrics
├─ checkout_workflow_started_total
│  └ Counter: Total workflows started
│
├─ checkout_workflow_completed_total{status}
│  └ Counter: Completed workflows
│     - status: success, failed
│
├─ checkout_workflow_duration_ms
│  └ Histogram: Workflow execution time
│     - p50, p95, p99, p99.9
│
├─ checkout_activity_duration_ms{activity}
│  └ Histogram: Activity execution time
│     - activity: cart, pricing, inventory, payment, order
│
├─ checkout_activity_retries_total{activity}
│  └ Counter: Activity retry count
│
├─ checkout_compensation_triggered_total{step}
│  └ Counter: Compensation executions
│     - step: releaseStock, voidPayment, refundPayment, cancelOrder
│
├─ checkout_idempotency_cache_hits_total
│  └ Counter: Idempotent request hits
│
├─ checkout_idempotency_cache_misses_total
│  └ Counter: Cache misses (new workflows)
│
├─ checkout_idempotency_keys_cleaned_total
│  └ Counter: Keys cleaned by job
│
└─ temporal_workflow_task_queue_poll_latency_ms
   └ Histogram: Temporal task poll latency
```

## Observability Pipeline

```mermaid
graph LR
    CO["Checkout Orchestrator"]

    subgraph Metrics["📊 Metrics Pipeline"]
        MPrometheus["Prometheus\n9090"]
        MGrafana["Grafana\n3000"]
        MAlerting["Alertmanager"]
    end

    subgraph Logs["📝 Logging Pipeline"]
        LElasticsearch["Elasticsearch"]
        LKibana["Kibana"]
    end

    subgraph Traces["📈 Tracing Pipeline"]
        TJaeger["Jaeger"]
        TCollector["OTEL Collector"]
    end

    subgraph TemporalUI["Temporal Observability"]
        TemporalWebUI["Temporal Web UI\n:8088"]
        WorkflowHistory["Workflow History"]
        ActivityDetails["Activity Details"]
    end

    CO -->|Micrometer| MPrometheus
    CO -->|Structured Logs| LElasticsearch
    CO -->|OpenTelemetry| TCollector

    MPrometheus --> MGrafana
    MPrometheus --> MAlerting

    LElasticsearch --> LKibana

    TCollector --> TJaeger

    CO -->|Temporal SDK| TemporalWebUI
    TemporalWebUI --> WorkflowHistory
    TemporalWebUI --> ActivityDetails

    style CO fill:#4f46e5,color:#fff
    style MPrometheus fill:#9333ea,color:#fff
    style TemporalWebUI fill:#6366f1,color:#fff
```

## Data Retention Policies

```mermaid
graph TB
    subgraph Retention["Data Retention"]
        IdempotencyKeys["checkout_idempotency_keys\n• Active: 30 minutes\n• Cleanup: 24h after expiry"]

        TemporalHistory["Temporal Workflow History\n• Default: 7 days\n• Configurable per namespace"]

        Metrics["Prometheus Metrics\n• Raw: 15 days\n• Downsampled: 90 days"]

        Logs["ELK Logs\n• Hot: 7 days\n• Warm: 30 days\n• Cold: 90 days"]
    end

    style IdempotencyKeys fill:#4f46e5,color:#fff
    style TemporalHistory fill:#6366f1,color:#fff
    style Metrics fill:#9333ea,color:#fff
    style Logs fill:#f59e0b,color:#000
```
