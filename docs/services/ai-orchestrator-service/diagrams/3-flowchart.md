# AI Orchestrator Service - Request Flowchart

```mermaid
flowchart TD
    A["Incoming Request<br/>/assist or /substitute"] --> B["Parse JSON"]
    B --> C["Generate RequestID"]
    C --> D{Rate<br/>Limit?}
    D -->|Exceeded| E["Return 429<br/>Too Many Requests"]
    D -->|OK| F["PII Redact Input"]
    F --> G["Check Prompt<br/>Injection"]
    G -->|Injection Detected| H["Return 400<br/>Malformed Input"]
    G -->|OK| I["Classify Intent"]

    I --> J["Lookup Keywords"]
    J --> K{Confidence><br/>threshold?}
    K -->|Yes| L["Use<br/>Keyword-Based"]
    K -->|No| M{LLM<br/>Available?}
    M -->|Yes| N["Call External<br/>LLM API"]
    M -->|No| O["Return Default<br/>Category"]

    L --> P["Load or Init<br/>LangGraph State"]
    N --> P
    O --> P

    P --> Q["Check Tool<br/>Call Budget"]
    Q -->|Budget Exceeded| R["Escalate to<br/>Human Agent"]
    Q -->|Budget OK| S["Execute Graph<br/>Step"]

    S --> T["Select Next<br/>Node/Tool"]
    T --> U{Tool<br/>Allowed?}
    U -->|Tool Blocked| R
    U -->|Tool OK| V["Check Circuit<br/>Breaker"]
    V -->|CB Open| W["Use Fallback<br/>Response"]
    V -->|CB Closed| X["Execute Tool<br/>with Timeout"]

    X --> Y{Tool<br/>Success?}
    Y -->|Failure| Z["Increment<br/>CB Failure"]
    Z --> W
    Y -->|Success| AA["Record Result"]
    AA --> AB{More<br/>Steps?}

    AB -->|Yes| S
    AB -->|No| AC["Validate Output<br/>PII/Safety"]
    AC -->|Failed| AD["Redact Unsafe<br/>Sections"]
    AC -->|Passed| AE["Build Response"]
    W --> AE
    AD --> AE

    AE --> AF["Save Checkpoint<br/>to Redis"]
    AF --> AG["Record Metrics<br/>latency + tools"]
    AG --> AH["Return 200<br/>with response"]

    E --> AI["HTTP Response"]
    H --> AI
    R --> AI
    AH --> AI
```

## Flow Details

1. **Input Validation**: Rate limit check, PII redaction, injection detection
2. **Intent Classification**: Keyword scoring or optional LLM call
3. **State Management**: Load or initialize LangGraph conversation state
4. **Budget Enforcement**: Check remaining tool calls, cost tokens
5. **Graph Execution**: Iterate through state machine nodes
6. **Tool Selection**: Determine next tool to execute
7. **Resilience**: Circuit breaker per tool, timeouts, fallbacks
8. **Output Safety**: PII redaction, content validation
9. **Checkpoint**: Save conversation state to Redis
10. **Metrics**: Record latency and tool usage
