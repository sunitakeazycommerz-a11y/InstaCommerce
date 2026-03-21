# AI Orchestrator Service - Conversation State Machine

```mermaid
stateDiagram-v2
    [*] --> RequestReceived

    RequestReceived --> InputValidation: route_request
    InputValidation --> GuardrailCheck: parse_json
    GuardrailCheck --> RateLimitCheck: validate_format
    RateLimitCheck --> Classified: rate_ok
    Classified --> IntentRecognition: extract_intent

    RateLimitCheck --> Rejected: rate_limit_exceeded
    Rejected --> ErrorResponse: 429
    ErrorResponse --> [*]

    GuardrailCheck --> Injection: pii_redaction_failed
    Injection --> Rejected
    Rejected --> ErrorResponse

    IntentRecognition --> StateLoaded: classify_intent
    StateLoaded --> GraphInit: load_checkpoint
    GraphInit --> NodeExecution: init_state

    StateLoaded --> CheckpointFound: checkpoint_exists
    CheckpointFound --> Resume: state_loaded
    Resume --> NodeExecution

    NodeExecution --> SelectTool: execute_node
    SelectTool --> ToolCheck: tool_available
    ToolCheck --> CircuitBreakerCheck: tool_allowed
    CircuitBreakerCheck --> ExecuteTool: cb_closed
    ExecuteTool --> ToolResult: execution_complete

    ToolCheck --> Fallback: tool_not_allowed
    CircuitBreakerCheck --> Fallback: cb_open
    ExecuteTool --> Fallback: timeout_or_error
    Fallback --> FallbackResponse: fallback_generated

    ToolResult --> UpdateState: result_received
    FallbackResponse --> UpdateState: fallback_used
    UpdateState --> OutputValidation: state_updated

    OutputValidation --> SafetyCheck: validate_output
    SafetyCheck --> Redaction: safety_failed
    Redaction --> FinalState: pii_redacted
    SafetyCheck --> FinalState: safety_passed

    FinalState --> Complete: terminal_state
    Complete --> CheckpointSave: conversation_done
    CheckpointSave --> MetricsRecord: checkpoint_saved
    MetricsRecord --> ResponseSend: metrics_recorded
    ResponseSend --> [*]

    FinalState --> Continue: more_steps
    Continue --> NodeExecution

    note right of InputValidation
        Check rate limit,
        redact PII,
        detect injection
    end note

    note right of IntentRecognition
        Keyword scorer or
        LLM-based classification
    end note

    note right of CircuitBreakerCheck
        Per-tool circuit breaker
        with failure tracking
    end note

    note right of OutputValidation
        Safety checks and
        PII redaction before response
    end note
```

## State Transitions

- **RequestReceived→InputValidation**: New request arrives
- **InputValidation→GuardrailCheck**: JSON parsing succeeds
- **GuardrailCheck→RateLimitCheck**: PII redaction successful
- **RateLimitCheck→Classified**: Rate limit OK
- **Classified→StateLoaded**: Intent determined
- **StateLoaded→NodeExecution**: Conversation state loaded/initialized
- **SelectTool→ExecuteTool**: Circuit breaker closed
- **ExecuteTool→UpdateState**: Tool execution succeeds
- **UpdateState→Complete**: Terminal state reached
- **Complete→ResponseSend**: Checkpoint saved and metrics recorded
