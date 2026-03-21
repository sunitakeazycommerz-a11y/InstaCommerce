# AI Orchestrator Service - Data Model

```mermaid
erDiagram
    ASSIST_REQUEST ||--o{ CONVERSATION_STATE : initiates
    CONVERSATION_STATE ||--o{ GRAPH_NODE : contains
    CONVERSATION_STATE ||--o{ TOOL_CALL : executes

    ASSIST_REQUEST {
        string request_id PK
        string query
        uuid user_id
        string session_id FK
        json context
        boolean execute_tools
    }

    CONVERSATION_STATE {
        string session_id PK
        string query
        int tool_calls_used
        float cost_tokens
        timestamp created_at
        timestamp updated_at
        json checkpoint_data
    }

    GRAPH_NODE {
        string session_id FK
        string node_name PK
        string node_type
        json state_data
        timestamp executed_at
    }

    TOOL_CALL {
        string session_id FK
        int sequence PK
        string tool_name
        json input_args
        json output_result
        float latency_ms
        string circuit_breaker_state
    }

    INTENT {
        string session_id FK
        string category
        float confidence
        json attributes
    }

    GUARDRAIL_EVENT {
        string request_id FK
        string event_type
        boolean passed
        json details
        timestamp created_at
    }

    CIRCUIT_BREAKER_STATE {
        string tool_name PK
        string state
        int failure_count
        timestamp last_failure_at
        timestamp reset_at
    }

    RATE_LIMIT_ENTRY {
        uuid user_id PK
        int requests_count
        timestamp window_start
        timestamp window_end
    }

    CHECKPOINT {
        string session_id PK
        json state_snapshot
        timestamp created_at
        timestamp expires_at
    }

    METRICS_RECORD {
        string request_id PK
        string intent
        int tool_calls
        float cost_tokens
        float latency_ms
        timestamp recorded_at
    }

    OUTPUT_VALIDATION {
        string session_id FK
        string validation_type
        boolean passed
        json safety_issues
        json redacted_content
    }

    ASSIST_REQUEST ||--o{ INTENT : has
    ASSIST_REQUEST ||--o{ GUARDRAIL_EVENT : emits
    TOOL_CALL ||--o{ CIRCUIT_BREAKER_STATE : uses
    ASSIST_REQUEST ||--o{ RATE_LIMIT_ENTRY : checks
    CONVERSATION_STATE ||--o{ CHECKPOINT : saves_to
    ASSIST_REQUEST ||--o{ METRICS_RECORD : generates
    CONVERSATION_STATE ||--o{ OUTPUT_VALIDATION : validates
```

## Key Entities

| Entity | Purpose |
|--------|---------|
| **ASSIST_REQUEST** | Incoming assistant query |
| **CONVERSATION_STATE** | Multi-turn conversation context |
| **GRAPH_NODE** | LangGraph node execution history |
| **TOOL_CALL** | Individual tool invocation record |
| **INTENT** | Classified user intent with confidence |
| **GUARDRAIL_EVENT** | Rate limit, injection, PII detection events |
| **CIRCUIT_BREAKER_STATE** | Per-tool failure tracking |
| **CHECKPOINT** | Serialized state for resumption |
| **METRICS_RECORD** | Aggregated latency and tool metrics |
| **OUTPUT_VALIDATION** | Safety checks and PII redaction results |
