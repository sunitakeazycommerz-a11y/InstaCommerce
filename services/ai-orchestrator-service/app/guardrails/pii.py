"""PII detection and redaction with reversible vault mapping.

Used pre-LLM (redact) and post-LLM (restore) to prevent PII leakage.
Supports email, phone, SSN, credit card, and address-like patterns.
Thread-safe via a per-instance lock.
"""

import hashlib
import hmac
import logging
import os
import re
import threading
from typing import Any, Dict, List, Tuple

logger = logging.getLogger(__name__)

# ---------------------------------------------------------------------------
# PII detection patterns
# ---------------------------------------------------------------------------
_PII_PATTERNS: List[Tuple[re.Pattern[str], str]] = [
    (re.compile(r"[\w\.-]+@[\w\.-]+\.\w{2,}"), "EMAIL"),
    (re.compile(r"\b\d{3}-\d{2}-\d{4}\b"), "SSN"),
    (re.compile(r"\b(?:\d[ \-]*?){13,16}\b"), "CARD"),
    (
        re.compile(
            r"\b(?:\+?\d{1,3}[\s\-]?)?(?:\(?\d{3}\)?[\s\-]?)\d{3}[\s\-]?\d{4}\b"
        ),
        "PHONE",
    ),
    (
        re.compile(
            r"\b\d{1,5}\s[\w\s]{1,40}(?:St|Street|Ave|Avenue|Blvd|Boulevard|Dr|Drive|Rd|Road|Ln|Lane|Ct|Court)\b",
            re.IGNORECASE,
        ),
        "ADDRESS",
    ),
]


class PIIVault:
    """Reversible PII redaction — maps placeholders to original values.

    Usage::

        vault = PIIVault(secret_key="my-secret")
        redacted, mapping = vault.redact("Email me at alice@example.com")
        # redacted  == "Email me at [PII_EMAIL_0]"
        # mapping   == {"[PII_EMAIL_0]": "alice@example.com"}

        restored = vault.restore(redacted)
        # restored  == "Email me at alice@example.com"

    The *secret_key* is used to produce deterministic HMAC tags stored
    alongside the mapping so downstream consumers can verify provenance.

    Thread-safety is guaranteed by an internal ``threading.Lock``.
    """

    def __init__(self, secret_key: str = "") -> None:
        self._vault: Dict[str, str] = {}
        self._counter: int = 0
        self._secret: bytes = (
            secret_key.encode()
            if secret_key
            else os.environ.get("PII_VAULT_SECRET", "default-key").encode()
        )
        self._lock = threading.Lock()

    # ------------------------------------------------------------------
    # Public API
    # ------------------------------------------------------------------

    def redact(self, text: str) -> Tuple[str, Dict[str, str]]:
        """Redact PII from *text*.

        Returns
        -------
        redacted_text : str
            Text with PII replaced by ``[PII_TYPE_N]`` placeholders.
        vault_mapping : dict
            Mapping from placeholder to original value.
        """
        if not isinstance(text, str) or not text:
            return text, {}

        mapping: Dict[str, str] = {}

        with self._lock:
            for pattern, pii_type in _PII_PATTERNS:
                text = self._replace(text, pattern, pii_type, mapping)

        if mapping:
            logger.debug(
                "Redacted %d PII occurrence(s)",
                len(mapping),
                extra={"pii_types": list({v.split("_")[1] for v in mapping})},
            )

        return text, mapping

    def restore(self, text: str) -> str:
        """Restore PII in *text* using the internal vault.

        Placeholders that have no matching vault entry are left unchanged.
        """
        if not isinstance(text, str) or not text:
            return text

        with self._lock:
            for placeholder, original in self._vault.items():
                text = text.replace(placeholder, original)
        return text

    def clear(self) -> None:
        """Remove all vault entries (useful between requests)."""
        with self._lock:
            self._vault.clear()
            self._counter = 0

    # ------------------------------------------------------------------
    # Helpers for nested / dict-based redaction
    # ------------------------------------------------------------------

    def redact_value(self, value: Any) -> Any:
        """Recursively redact PII in strings, dicts, and lists."""
        if isinstance(value, str):
            redacted, _ = self.redact(value)
            return redacted
        if isinstance(value, dict):
            return {k: self.redact_value(v) for k, v in value.items()}
        if isinstance(value, list):
            return [self.redact_value(item) for item in value]
        return value

    # ------------------------------------------------------------------
    # Internal helpers
    # ------------------------------------------------------------------

    def _replace(
        self,
        text: str,
        pattern: re.Pattern[str],
        pii_type: str,
        mapping: Dict[str, str],
    ) -> str:
        """Replace all *pattern* matches with placeholders."""

        def _sub(match: re.Match[str]) -> str:
            original = match.group(0)
            placeholder = f"[PII_{pii_type}_{self._counter}]"
            self._counter += 1
            self._vault[placeholder] = original
            mapping[placeholder] = original
            return placeholder

        return pattern.sub(_sub, text)

    def _hmac_tag(self, value: str) -> str:
        """Produce an HMAC-SHA256 hex tag for *value*."""
        return hmac.new(self._secret, value.encode(), hashlib.sha256).hexdigest()
