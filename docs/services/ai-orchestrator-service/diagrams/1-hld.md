# AI Orchestrator Service - High-Level Design

```mermaid
flowchart TD
    subgraph Client["Client Layer"]
        MobileApp["Mobile App"]
        WebApp["Web App"]
    end

    subgraph BFF["BFF Layer"]
        MOBILE_BFF["mobile-bff-service<br/>:8089"]
        ADMIN_GW["admin-gateway-service<br/>:8090"]
    end

    subgraph ORCH["ai-orchestrator-service :8100"]
        direction TB
        API["FastAPI Endpoints<br/>/assist, /substitute"]
        INTENT["Intent Classifier<br/>(keyword scorer +<br/>LLM fallback)"]
        GUARD["Guardrails<br/>(PII, injection,<br/>rate limit, budget)"]
        GRAPH["LangGraph<br/>State Machine"]
        TOOLS["Tool Executor<br/>(8 read-only tools)"]
        REDIS_CP["Redis Checkpoints<br/>(conversation state)"]
        PROM["Prometheus<br/>metrics"]
    end

    subgraph DomainServices["Domain Services (read-only)"]
        CAT["catalog-service"]
        PRC["pricing-service"]
        INV["inventory-service"]
        CART["cart-service"]
        ORD["order-service"]
    end

    subgraph External["External Dependencies"]
        LLM["OpenAI GPT-4o<br/>(optional)"]
        REDIS["Redis<br/>checkpoints"]
    end

    MobileApp --> MOBILE_BFF
    WebApp --> ADMIN_GW
    MOBILE_BFF --> API
    ADMIN_GW --> API

    API --> INTENT
    INTENT --> GUARD
    GUARD --> GRAPH
    GRAPH --> TOOLS
    TOOLS --> CAT
    TOOLS --> PRC
    TOOLS --> INV
    TOOLS --> CART
    TOOLS --> ORD

    GRAPH -.->|"optional LLM"| LLM
    GRAPH --> REDIS_CP
    REDIS_CP --> REDIS
    API --> PROM

    API -->|"response"| MOBILE_BFF
    API -->|"response"| ADMIN_GW
```

## Key Characteristics

- **LangGraph Orchestration**: Multi-step agentic conversation with stateful checkpointing
- **Intent Classification**: Deterministic keyword scorer + optional LLM
- **Read-Only Design**: All 8 tools are read-only; no mutations allowed
- **Policy Gates**: Cost, latency, tool-call budgets per request
- **Guardrails**: PII redaction, prompt injection detection, output validation
- **Resilience**: Per-tool circuit breakers, timeouts, retries
- **Observability**: OpenTelemetry tracing + structured JSON logging with PII redaction
