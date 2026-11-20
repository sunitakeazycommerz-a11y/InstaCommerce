from __future__ import annotations

from dataclasses import dataclass
import logging
from typing import Any, Dict, List, Optional

from app.core.redaction import scrub_pii_and_secrets
from app.graph.state import AgentState, ToolResult
from app.guardrails import OutputPolicy, PIIVault

logger = logging.getLogger("ai_orchestrator.output_guard")

_STRUCTURED_CONTEXT_KEYS = (
    "pricing_lookup",
    "inventory_check",
    "policy_check",
    "order_status",
)


def _classify_tool_output_key(name: str) -> Optional[str]:
    if name.startswith("pricing"):
        return "pricing_lookup"
    if name.startswith("inventory"):
        return "inventory_check"
    if name.startswith("order"):
        return "order_status"
    if "policy" in name:
        return "policy_check"
    return None


def extract_structured_tool_outputs(
    tool_results: List[ToolResult],
    tool_calls: Optional[List[Any]] = None,
    context: Optional[Dict[str, Any]] = None,
) -> Dict[str, Any]:
    """Extract structured dicts (pricing_lookup, inventory_check, policy_check, order_status)."""
    structured: Dict[str, Any] = {}
    ctx = context or {}

    for key in _STRUCTURED_CONTEXT_KEYS:
        val = ctx.get(key)
        if isinstance(val, dict):
            structured[key] = dict(val)

    for tr in tool_results:
        name = getattr(tr, "tool_name", None) or getattr(tr, "name", "")
        data = getattr(tr, "data", None)
        if not isinstance(data, dict):
            continue
        output_key = _classify_tool_output_key(str(name))
        if output_key is not None:
            structured[output_key] = dict(data)

    if tool_calls:
        for tc in tool_calls:
            tc_name = getattr(tc, "name", "") if not isinstance(tc, dict) else tc.get("name", "")
            tc_args = getattr(tc, "arguments", {}) if not isinstance(tc, dict) else tc.get("arguments", {})
            if isinstance(tc_args, dict):
                output_key = _classify_tool_output_key(str(tc_name))
                if output_key is not None and output_key not in structured:
                    structured[output_key] = dict(tc_args)

    return structured


def redact_output_text(text: str, vault: Optional[PIIVault] = None) -> str:
    """Scrub secrets and PII deterministically from outbound response text."""
    scrubbed = scrub_pii_and_secrets(text, include_secrets=False)
    active_vault = vault or PIIVault()
    redacted, _ = active_vault.redact(scrubbed)
    return redacted


@dataclass(frozen=True)
class OutputEvaluationResult:
    response: str
    is_valid: bool
    error_messages: List[str]
    citations: List[str]


def evaluate_output_policy(
    state: AgentState,
    *,
    response_text: Optional[str] = None,
    output_policy: Optional[OutputPolicy] = None,
    vault: Optional[PIIVault] = None,
) -> OutputEvaluationResult:
    """Redact response PII and validate structured tool outputs against OutputPolicy."""
    candidate_text = state.response if response_text is None else response_text
    redacted = redact_output_text(candidate_text, vault=vault)
    structured = extract_structured_tool_outputs(
        state.tool_results,
        tool_calls=getattr(state, "tool_calls", None),
        context=state.context,
    )
    output_payload: Dict[str, Any] = {
        "intent": state.intent.value,
        "response": redacted,
        "message": redacted,
        **structured,
    }
    policy = output_policy or OutputPolicy()
    val_result = policy.validate(
        output_payload,
        intent=state.intent.value,
        tool_results=state.tool_results,
    )

    if not val_result.is_valid:
        error_msgs = [v.message for v in val_result.violations if v.severity == "error"]
        return OutputEvaluationResult(
            response=redacted,
            is_valid=False,
            error_messages=error_msgs,
            citations=list(state.response_citations),
        )

    sanitized_text = redacted
    if val_result.sanitised_output:
        if "response" in val_result.sanitised_output:
            sanitized_text = str(val_result.sanitised_output["response"])
        elif "message" in val_result.sanitised_output:
            sanitized_text = str(val_result.sanitised_output["message"])

    citations = list(state.response_citations)
    if state.retrieval_results and not citations:
        citations = [r.doc_id for r in state.retrieval_results]

    return OutputEvaluationResult(
        response=sanitized_text,
        is_valid=True,
        error_messages=[],
        citations=citations,
    )


def validate_output(state: AgentState) -> AgentState:
    """Validate and sanitise the response before delivery."""
    try:
        evaluation = evaluate_output_policy(state)
        if not evaluation.is_valid:
            logger.warning(
                "node.validate_output.violations",
                extra={"request_id": state.request_id, "violations": evaluation.error_messages},
            )
            return state.model_copy(
                update={
                    "response": evaluation.response,
                    "needs_escalation": True,
                    "escalation_reason": f"Output policy violations: {'; '.join(evaluation.error_messages)}",
                    "escalation_category": "output_policy",
                    "errors": state.errors + evaluation.error_messages,
                    "current_node": "validate_output",
                    "completed_nodes": state.completed_nodes + ["validate_output"],
                }
            )

        response = evaluation.response
        if not response.strip():
            response = "I'm sorry, I wasn't able to generate a response. Please try rephrasing your question."

        logger.info(
            "node.validate_output",
            extra={
                "request_id": state.request_id,
                "citations_count": len(evaluation.citations),
                "response_length": len(response),
            },
        )

        return state.model_copy(
            update={
                "response": response,
                "response_citations": evaluation.citations,
                "current_node": "validate_output",
                "completed_nodes": state.completed_nodes + ["validate_output"],
            }
        )
    except Exception as exc:
        logger.exception("node.validate_output.error", extra={"request_id": state.request_id})
        return state.model_copy(
            update={
                "errors": state.errors + [f"validate_output: {exc}"],
                "response": "An error occurred while preparing your response. Please try again.",
                "current_node": "validate_output",
                "completed_nodes": state.completed_nodes + ["validate_output"],
            }
        )
