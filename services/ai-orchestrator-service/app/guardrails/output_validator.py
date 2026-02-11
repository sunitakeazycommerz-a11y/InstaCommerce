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

# ---------------------------------------------------------------------------
# Configurable thresholds
# ---------------------------------------------------------------------------
_MAX_REFUND_AMOUNT_CENTS: int = int(
    os.environ.get("OUTPUT_MAX_REFUND_CENTS", "50000")
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
        intent: str,
        output: Dict[str, Any],
        tool_results: Optional[List[Any]] = None,
    ) -> ValidationResult:
        """Check *output* against business policies for *intent*.

        Parameters
        ----------
        intent:
            The classified intent of the user query (e.g. ``"refund"``).
        output:
            The AI-generated output dictionary.
        tool_results:
            Optional list of tool results used for citation validation.

        Returns
        -------
        ValidationResult
            Aggregate result with any violations and the sanitised output.
        """
        violations: List[ValidationViolation] = []

        try:
            violations.extend(self._check_schema(intent, output))
            violations.extend(self._check_business_rules(intent, output))
            violations.extend(self._check_content_safety(output))
            violations.extend(
                self._check_citations(output, tool_results or [])
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
        sanitised = self._sanitise_output(output, violations)

        if violations:
            logger.info(
                "Output validation complete",
                extra={
                    "intent": intent,
                    "status": status.value,
                    "violation_count": len(violations),
                },
            )

        return ValidationResult(
            status=status,
            violations=violations,
            sanitised_output=sanitised,
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
        for field in required:
            if field not in output:
                violations.append(
                    ValidationViolation(
                        rule="missing_required_field",
                        severity="error",
                        message=f"Missing required field '{field}' for intent '{intent}'.",
                        field=field,
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

        # Refund cap
        if intent == "refund":
            amount = output.get("amount_cents")
            if isinstance(amount, (int, float)) and amount > self.MAX_REFUND_AMOUNT_CENTS:
                violations.append(
                    ValidationViolation(
                        rule="refund_limit_exceeded",
                        severity="error",
                        message=(
                            f"Refund amount {amount} cents exceeds max "
                            f"allowed {self.MAX_REFUND_AMOUNT_CENTS} cents."
                        ),
                        field="amount_cents",
                    )
                )

        # Discount cap
        if intent == "discount":
            pct = output.get("discount_percent")
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
        import re

        _UUID_RE = re.compile(
            r"[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}",
            re.IGNORECASE,
        )
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
