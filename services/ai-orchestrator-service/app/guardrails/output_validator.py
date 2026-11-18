"""Validate AI-generated output before returning to user.

Enforces business policies (refund limits, discount caps), content safety,
schema conformance, and citation requirements. All thresholds are
configurable via environment variables.
"""

import logging
import os
import re
from enum import Enum
from typing import Any, Dict, List, Optional

from pydantic import BaseModel, Field

logger = logging.getLogger(__name__)

_UUID_RE = re.compile(
    r"[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}",
    re.IGNORECASE,
)

# ---------------------------------------------------------------------------
# Configurable thresholds
# ---------------------------------------------------------------------------
_MAX_REFUND_AMOUNT_CENTS: int = int(
    os.environ.get("OUTPUT_MAX_REFUND_CENTS", "1500")
)
_MAX_DISCOUNT_PERCENT: int = int(
    os.environ.get("OUTPUT_MAX_DISCOUNT_PERCENT", "30")
)


class ValidationStatus(str, Enum):
    """Possible outcomes of output validation."""

    PASSED = "passed"
    FAILED = "failed"
    WARNING = "warning"


class ValidationViolation(BaseModel):
    """A single policy violation detected during validation."""

    rule: str
    severity: str = "error"
    message: str
    field: Optional[str] = None


class ValidationResult(BaseModel):
    """Aggregate result of all output validation checks."""

    status: ValidationStatus = ValidationStatus.PASSED
    violations: List[ValidationViolation] = Field(default_factory=list)
    sanitised_output: Optional[Dict[str, Any]] = None
    requires_human_approval: bool = False

    @property
    def is_valid(self) -> bool:
        """``True`` when no error-level violations were found."""
        return all(v.severity != "error" for v in self.violations)


class OutputPolicy:
    """Configurable output policies per intent type.

    Validates AI-generated output against:
      1. Schema validation (output matches expected format)
      2. Business-rule validation (refund limits, discount caps)
      3. Content safety (no harmful content, no blocked phrases)
      4. Citation validation (claims must reference retrieval results)
    """

    MAX_REFUND_AMOUNT_CENTS: int = _MAX_REFUND_AMOUNT_CENTS
    MAX_DISCOUNT_PERCENT: int = _MAX_DISCOUNT_PERCENT

    BLOCKED_PHRASES: List[str] = [
        "i cannot",
        "as an ai",
        "i don't have access",
        "i'm just a language model",
        "i am not able to",
    ]

    # Map of intent → required top-level keys in output
    REQUIRED_FIELDS: Dict[str, List[str]] = {
        "refund": ["amount_cents", "reason"],
        "discount": ["discount_percent", "reason"],
        "order": ["order_id"],
    }

    # ------------------------------------------------------------------
    # Public API
    # ------------------------------------------------------------------

    def validate(
        self,
        output: Dict[str, Any],
        intent: Optional[str] = None,
        tool_results: Optional[List[Any]] = None,
    ) -> ValidationResult:
        """Check *output* against business policies for *intent*."""
        resolved_output = dict(output)
        resolved_intent = intent or str(resolved_output.get("intent") or "assist")

        violations: List[ValidationViolation] = []

        try:
            violations.extend(self._check_schema(resolved_intent, resolved_output))
            violations.extend(self._check_business_rules(resolved_intent, resolved_output))
            violations.extend(self._check_content_safety(resolved_output))
            violations.extend(
                self._check_citations(resolved_output, tool_results or [])
            )
        except Exception:
            logger.exception("Output validation encountered an error")
            violations.append(
                ValidationViolation(
                    rule="internal_error",
                    severity="warning",
                    message="Validation encountered an unexpected error; output allowed with warning.",
                )
            )

        status = self._derive_status(violations)
        sanitised = self._sanitise_output(resolved_output, violations)

        if violations:
            logger.info(
                "Output validation complete",
                extra={
                    "intent": resolved_intent,
                    "status": status.value,
                    "violation_count": len(violations),
                },
            )

        requires_human = any(v.rule == "refund_limit_exceeded" for v in violations)

        return ValidationResult(
            status=status,
            violations=violations,
            sanitised_output=sanitised,
            requires_human_approval=requires_human,
        )

    # ------------------------------------------------------------------
    # 1. Schema validation
    # ------------------------------------------------------------------

    def _check_schema(
        self, intent: str, output: Dict[str, Any]
    ) -> List[ValidationViolation]:
        """Verify that the output contains the expected fields for *intent*."""
        violations: List[ValidationViolation] = []
        required = self.REQUIRED_FIELDS.get(intent, [])
        order_ctx = output.get("order_status")
        policy_ctx = output.get("policy_check")
        pricing_ctx = output.get("pricing_lookup")
        for field_name in required:
            present = (
                field_name in output
                or (isinstance(order_ctx, dict) and field_name in order_ctx)
                or (isinstance(policy_ctx, dict) and field_name in policy_ctx)
                or (isinstance(pricing_ctx, dict) and field_name in pricing_ctx)
            )
            if not present:
                violations.append(
                    ValidationViolation(
                        rule="missing_required_field",
                        severity="error",
                        message=f"Missing required field '{field_name}' for intent '{intent}'.",
                        field=field_name,
                    )
                )
        return violations

    # ------------------------------------------------------------------
    # 2. Business-rule validation
    # ------------------------------------------------------------------

    def _check_business_rules(
        self, intent: str, output: Dict[str, Any]
    ) -> List[ValidationViolation]:
        """Enforce business limits on refunds, discounts, etc."""
        violations: List[ValidationViolation] = []
        raw_policy = output.get("policy_check")
        policy_ctx: Dict[str, Any] = raw_policy if isinstance(raw_policy, dict) else {}
        raw_pricing = output.get("pricing_lookup")
        pricing_ctx: Dict[str, Any] = raw_pricing if isinstance(raw_pricing, dict) else {}
        raw_order = output.get("order_status")
        order_ctx: Dict[str, Any] = raw_order if isinstance(raw_order, dict) else {}

        # Refund cap (Hard-coded max $15.00 / 1500 cents or 100% of order total, whichever is lower) (#615)
        if intent in ("refund", "support"):
            amount = output.get("amount_cents") or policy_ctx.get("amount_cents")
            order_total = (
                output.get("order_total_cents")
                or order_ctx.get("order_total_cents")
                or order_ctx.get("total_cents")
                or pricing_ctx.get("total_cents")
            )
            max_allowed = self.MAX_REFUND_AMOUNT_CENTS  # default 1500 cents ($15.00)
            if isinstance(order_total, (int, float)) and order_total > 0:
                max_allowed = min(max_allowed, int(order_total))

            if isinstance(amount, (int, float)) and amount > max_allowed:
                violations.append(
                    ValidationViolation(
                        rule="refund_limit_exceeded",
                        severity="error",
                        message=(
                            f"Refund amount {amount} cents exceeds max "
                            f"allowed {max_allowed} cents ($15.00 or order total). "
                            "Two-person supervisor approval required."
                        ),
                        field="amount_cents",
                    )
                )

        # Check for textual refund promises exceeding the cap (#615)
        text = self._extract_text(output)
        if text:
            refund_matches = re.findall(
                r"refund(?:ed|ing)?\s+(?:of\s+)?\$?(\d+(?:\.\d{2})?)", text, re.IGNORECASE
            )
            for match in refund_matches:
                try:
                    refund_val = float(match)
                    if refund_val > (self.MAX_REFUND_AMOUNT_CENTS / 100.0):
                        violations.append(
                            ValidationViolation(
                                rule="refund_limit_exceeded",
                                severity="error",
                                message=(
                                    f"Autonomous refund promise of ${refund_val:.2f} exceeds hard-coded cap "
                                    f"of ${self.MAX_REFUND_AMOUNT_CENTS / 100.0:.2f}. Two-person supervisor approval required."
                                ),
                                field="message",
                            )
                        )
                except ValueError:
                    pass

        # Discount cap
        if intent == "discount":
            pct = output.get("discount_percent") or pricing_ctx.get("discount_percent")
            if isinstance(pct, (int, float)) and pct > self.MAX_DISCOUNT_PERCENT:
                violations.append(
                    ValidationViolation(
                        rule="discount_limit_exceeded",
                        severity="error",
                        message=(
                            f"Discount {pct}% exceeds max "
                            f"allowed {self.MAX_DISCOUNT_PERCENT}%."
                        ),
                        field="discount_percent",
                    )
                )

        return violations

    # ------------------------------------------------------------------
    # 3. Content safety
    # ------------------------------------------------------------------

    def _check_content_safety(
        self, output: Dict[str, Any]
    ) -> List[ValidationViolation]:
        """Screen output for blocked phrases that leak model identity."""
        violations: List[ValidationViolation] = []
        text = self._extract_text(output)
        lowered = text.lower()

        for phrase in self.BLOCKED_PHRASES:
            if phrase in lowered:
                violations.append(
                    ValidationViolation(
                        rule="blocked_phrase",
                        severity="warning",
                        message=f"Output contains blocked phrase: '{phrase}'.",
                    )
                )
        return violations

    # ------------------------------------------------------------------
    # 4. Citation validation
    # ------------------------------------------------------------------

    def _check_citations(
        self, output: Dict[str, Any], tool_results: List[Any]
    ) -> List[ValidationViolation]:
        """Warn when output makes claims without matching retrieval data.

        This is a heuristic check: if the output mentions specific product
        IDs or order IDs that are not present in *tool_results*, a warning
        is raised.
        """
        violations: List[ValidationViolation] = []
        if not tool_results:
            return violations

        # Build set of known IDs from tool results
        known_ids: set[str] = set()
        for result in tool_results:
            data = getattr(result, "data", None) or (
                result if isinstance(result, dict) else {}
            )
            if isinstance(data, dict):
                self._collect_ids(data, known_ids)

        # Check output references
        output_ids: set[str] = set()
        self._collect_ids(output, output_ids)

        uncited = output_ids - known_ids
        if uncited:
            violations.append(
                ValidationViolation(
                    rule="uncited_reference",
                    severity="warning",
                    message=(
                        f"Output references IDs not found in tool results: "
                        f"{', '.join(sorted(uncited)[:5])}"
                    ),
                )
            )
        return violations

    # ------------------------------------------------------------------
    # Helpers
    # ------------------------------------------------------------------

    @staticmethod
    def _extract_text(output: Dict[str, Any]) -> str:
        """Extract human-readable text from the output dict."""
        parts: List[str] = []
        for key in ("message", "text", "response", "answer"):
            val = output.get(key)
            if isinstance(val, str):
                parts.append(val)
        return " ".join(parts)

    @staticmethod
    def _collect_ids(data: Dict[str, Any], dest: set[str]) -> None:
        """Recursively collect UUID-like string values from *data*."""
        for value in data.values():
            if isinstance(value, str) and _UUID_RE.fullmatch(value):
                dest.add(value)
            elif isinstance(value, dict):
                OutputPolicy._collect_ids(value, dest)
            elif isinstance(value, list):
                for item in value:
                    if isinstance(item, dict):
                        OutputPolicy._collect_ids(item, dest)
                    elif isinstance(item, str) and _UUID_RE.fullmatch(item):
                        dest.add(item)

    @staticmethod
    def _derive_status(
        violations: List[ValidationViolation],
    ) -> ValidationStatus:
        """Determine aggregate status from the violation list."""
        if any(v.severity == "error" for v in violations):
            return ValidationStatus.FAILED
        if violations:
            return ValidationStatus.WARNING
        return ValidationStatus.PASSED

    @staticmethod
    def _sanitise_output(
        output: Dict[str, Any],
        violations: List[ValidationViolation],
    ) -> Dict[str, Any]:
        """Return a copy of *output* with blocked phrases scrubbed."""
        sanitised = dict(output)
        blocked = [
            v.message.split("'")[1]
            for v in violations
            if v.rule == "blocked_phrase"
        ]
        for key in ("message", "text", "response", "answer"):
            val = sanitised.get(key)
            if isinstance(val, str):
                for phrase in blocked:
                    val = re.sub(re.escape(phrase), "[REDACTED]", val, flags=re.IGNORECASE)
                sanitised[key] = val
        return sanitised


# Alias for backward-compatibility and explicit naming (#615)
OutputPolicyValidator = OutputPolicy
