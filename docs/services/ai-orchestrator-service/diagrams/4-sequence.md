# AI Orchestrator Service - Request Sequence

```mermaid
sequenceDiagram
    actor Client as BFF / Admin Gateway
    participant API as FastAPI Endpoint
    participant GUARD as Guardrails
    participant INTENT as IntentClassifier
    participant GRAPH as LangGraph Engine
    participant TOOLS as ToolRegistry
    participant REDIS as Redis Checkpoint
    participant PROM as Prometheus

    Client ->> API: POST /assist {query}
    activate API

    API ->> GUARD: validate_input(request)
    activate GUARD
    GUARD ->> GUARD: check_rate_limit()
    GUARD ->> GUARD: redact_pii()
    GUARD ->> GUARD: detect_injection()
    GUARD -->> API: bool
    deactivate GUARD

    alt Guardrails Fail
        API -->> Client: 400 or 429
    else Guardrails Pass
        API ->> INTENT: classify(query)
        activate INTENT
        INTENT ->> INTENT: score_keywords()
        alt High Confidence
            INTENT -->> API: Intent
        else Low Confidence & LLM Available
            INTENT ->> INTENT: call_llm_api()
            INTENT -->> API: Intent
        end
        deactivate INTENT

        API ->> REDIS: load_checkpoint(session_id)
        activate REDIS
        REDIS -->> API: Optional[LangGraphState]
        deactivate REDIS

        alt Checkpoint Found
            API ->> GRAPH: resume_state(state)
        else New Session
            API ->> GRAPH: initialize_state(intent)
        end

        activate GRAPH
        loop Until Terminal State
            GRAPH ->> GRAPH: execute_node()
            GRAPH ->> TOOLS: check_tool_available(tool_name)
            activate TOOLS
            TOOLS ->> TOOLS: check_circuit_breaker()
            TOOLS -->> GRAPH: bool
            deactivate TOOLS

            alt Tool Available & CB Open
                GRAPH ->> TOOLS: execute_tool(name, args)
                activate TOOLS
                TOOLS ->> TOOLS: apply_timeout()
                TOOLS -->> GRAPH: result
                deactivate TOOLS
                GRAPH ->> GRAPH: update_state(result)
            else Tool Unavailable or CB Open
                GRAPH ->> GRAPH: use_fallback_response()
            end

            GRAPH ->> GUARD: validate_output(response)
            activate GUARD
            GUARD -->> GRAPH: bool
            deactivate GUARD

            alt Output Invalid
                GRAPH ->> GUARD: redact_unsafe()
            end

            GRAPH ->> GRAPH: check_completion()
        end

        GRAPH -->> API: final_state
        deactivate GRAPH

        API ->> REDIS: save_checkpoint(session_id, state)
        activate REDIS
        REDIS -->> API: void
        deactivate REDIS

        API ->> PROM: record_metrics(latency, tool_calls)
        activate PROM
        PROM -->> API: void
        deactivate PROM

        API -->> Client: 200 {response}
    end

    deactivate API
```

## Sequence Patterns

- **Input Validation First**: Guardrails block before any processing
- **Intent Classification**: Keyword-based fast path, LLM fallback for ambiguity
- **Checkpoint Resumption**: Restore conversation context from Redis
- **Iterative Graph Execution**: Multi-step reasoning with per-tool circuit breakers
- **Graceful Fallbacks**: Tool failures trigger fallback responses without breaking flow
- **Output Validation**: PII redaction and safety checks before response
- **Metrics Emission**: Latency, tool calls, and cost tracking
