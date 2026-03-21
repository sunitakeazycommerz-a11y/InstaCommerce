# AI Orchestrator Service - Low-Level Design

```mermaid
classDiagram
    class FastAPIApp {
        +settings: Settings
        +graph: LangGraphInstance
        -logger: Logger
        +post_assist(request) AssistResponse
        +post_substitute(request) SubstituteResponse
        +get_health()
    }

    class Settings {
        -llm_api_url: str
        -llm_api_key: str
        -llm_model: str
        -redis_url: str
        -checkpoint_fallback_enabled: bool
        -pii_redaction_enabled: bool
        -rate_limit_enabled: bool
        -max_tool_calls: int
        -timeout_seconds: float
    }

    class IntentClassifier {
        -keyword_scorers: Dict
        -llm_client: Optional
        +classify(query: str) Intent
        +score_keywords(query) float
    }

    class Intent {
        -category: str
        -confidence: float
        -attributes: Dict
    }

    class Guardrails {
        -pii_redactor: PiiRedactor
        -injection_detector: InjectionDetector
        -rate_limiter: TokenBucketRateLimiter
        -budget_enforcer: BudgetEnforcer
        +validate_input(request) bool
        +validate_output(response) bool
        +check_rate_limit(user_id) bool
        +check_budget(tool_calls_used) bool
    }

    class LangGraphState {
        -query: str
        -conversation_history: List
        -intent: Intent
        -context: Dict
        -tool_calls_used: int
        -cost_tokens: float
    }

    class GraphNode {
        -name: str
        +execute(state: LangGraphState) LangGraphState
    }

    class ToolRegistry {
        -tools: Dict[str, Tool]
        -circuit_breakers: Dict[str, CircuitBreaker]
        +get_tool(name: str) Tool
        +execute_tool(name: str, args) Any
        +is_tool_available(name: str) bool
    }

    class Tool {
        -name: str
        -description: str
        -circuit_breaker: CircuitBreaker
        -timeout_ms: int
        +execute(**kwargs) Any
        +is_allowed() bool
    }

    class CircuitBreaker {
        -state: str
        -failure_count: int
        -last_failure_time: timestamp
        -threshold: int
        -timeout: int
        +allow() bool
        +record_success()
        +record_failure()
    }

    class CheckpointSaver {
        -redis_client: Optional
        -fallback_store: Dict
        +save(session_id: str, state: LangGraphState)
        +load(session_id: str) LangGraphState
    }

    class MetricsCollector {
        -requests_counter: Counter
        -latency_histogram: Histogram
        -tool_calls_counter: Counter
        -cost_gauge: Gauge
        +record_request(intent, latency)
        +record_tool_call(tool_name, latency)
    }

    class PiiRedactor {
        -patterns: List[Tuple]
        -enabled: bool
        +redact(value: Any) Any
        +redact_text(text: str) str
    }

    FastAPIApp --> Settings
    FastAPIApp --> IntentClassifier
    FastAPIApp --> Guardrails
    FastAPIApp --> LangGraphState
    IntentClassifier --> Intent
    Guardrails --> PiiRedactor
    LangGraphState --> GraphNode
    ToolRegistry --> Tool
    Tool --> CircuitBreaker
    CheckpointSaver --> FastAPIApp
    MetricsCollector --> FastAPIApp
```

## Component Responsibilities

| Component | Responsibility |
|-----------|-----------------|
| **FastAPIApp** | HTTP server, request routing |
| **IntentClassifier** | Query classification (keyword + optional LLM) |
| **Guardrails** | Input/output validation, rate limiting, budget enforcement |
| **LangGraphState** | Orchestration state tracking |
| **ToolRegistry** | Manages 8 read-only tools with circuit breakers |
| **CircuitBreaker** | Per-tool failure tracking and fallback |
| **CheckpointSaver** | Conversation state persistence (Redis + fallback) |
| **PiiRedactor** | Email, SSN, card, phone masking |
| **MetricsCollector** | Prometheus metrics emission |
