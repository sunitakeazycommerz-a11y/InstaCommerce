"""Detect and block prompt injection attacks.

Multi-layer detection:
  1. Pattern matching against known injection signatures.
  2. Entropy analysis for unusual character distributions.
  3. Role-boundary violation detection (system/assistant impersonation).

Thread-safe — all methods are pure or use immutable state.
"""

import logging
import math
import os
import re
from collections import Counter
from typing import List, Tuple

logger = logging.getLogger(__name__)

# ---------------------------------------------------------------------------
# Configurable thresholds (via environment variables)
# ---------------------------------------------------------------------------
_PATTERN_CONFIDENCE: float = float(
    os.environ.get("INJECTION_PATTERN_CONFIDENCE", "0.9")
)
_ENTROPY_THRESHOLD: float = float(
    os.environ.get("INJECTION_ENTROPY_THRESHOLD", "4.5")
)
_ENTROPY_CONFIDENCE: float = float(
    os.environ.get("INJECTION_ENTROPY_CONFIDENCE", "0.6")
)
_ROLE_CONFIDENCE: float = float(
    os.environ.get("INJECTION_ROLE_CONFIDENCE", "0.85")
)
_MIN_TEXT_LENGTH_FOR_ENTROPY: int = int(
    os.environ.get("INJECTION_MIN_TEXT_LENGTH", "20")
)


class InjectionDetector:
    """Multi-layer prompt injection detection.

    Layer 1: Pattern matching (known injection patterns)
    Layer 2: Entropy analysis (unusual character distributions)
    Layer 3: Role boundary violation detection

    Usage::

        detector = InjectionDetector()
        is_injection, confidence, reason = detector.detect(user_input)
        if is_injection:
            # block or flag the request
            ...
    """

    KNOWN_PATTERNS: List[re.Pattern[str]] = [
        re.compile(p, re.IGNORECASE)
        for p in [
            r"ignore\s+(all\s+)?previous\s+instructions",
            r"you\s+are\s+now\s+",
            r"forget\s+(all|everything)",
            r"system\s*:\s*",
            r"\\n\\nHuman:",
            r"<\|im_start\|>",
            r"ADMIN\s+OVERRIDE",
            r"jailbreak",
            r"DAN\s+mode",
            r"pretend\s+you\s+are",
            r"do\s+anything\s+now",
            r"bypass\s+(all\s+)?restrictions",
            r"enter\s+(developer|god)\s+mode",
            r"reveal\s+(your|the)\s+(system\s+)?prompt",
        ]
    ]

    ROLE_BOUNDARY_PATTERNS: List[re.Pattern[str]] = [
        re.compile(p, re.IGNORECASE)
        for p in [
            r"^\s*system\s*:",
            r"^\s*assistant\s*:",
            r"\[INST\]",
            r"\[/INST\]",
            r"<\|system\|>",
            r"<\|assistant\|>",
            r"<\|user\|>",
            r"###\s*(System|Instruction)\s*:",
        ]
    ]

    # ------------------------------------------------------------------
    # Public API
    # ------------------------------------------------------------------

    def detect(self, text: str) -> Tuple[bool, float, str]:
        """Analyse *text* for prompt injection indicators.

        Returns
        -------
        is_injection : bool
            ``True`` when the text is classified as a likely injection.
        confidence : float
            Confidence score in ``[0.0, 1.0]``.
        reason : str
            Human-readable explanation of the detection result.
        """
        if not isinstance(text, str) or not text.strip():
            return False, 0.0, "empty_input"

        try:
            # Layer 1 — known patterns
            matched, pattern_reason = self._check_patterns(text)
            if matched:
                logger.warning(
                    "Injection pattern detected",
                    extra={"reason": pattern_reason, "confidence": _PATTERN_CONFIDENCE},
                )
                return True, _PATTERN_CONFIDENCE, pattern_reason

            # Layer 2 — entropy analysis
            is_anomalous, entropy_val = self._check_entropy(text)
            if is_anomalous:
                reason = f"high_entropy ({entropy_val:.2f})"
                logger.warning(
                    "Entropy anomaly detected",
                    extra={"entropy": entropy_val, "confidence": _ENTROPY_CONFIDENCE},
                )
                return True, _ENTROPY_CONFIDENCE, reason

            # Layer 3 — role boundary violation
            role_match, role_reason = self._check_role_boundaries(text)
            if role_match:
                logger.warning(
                    "Role boundary violation detected",
                    extra={"reason": role_reason, "confidence": _ROLE_CONFIDENCE},
                )
                return True, _ROLE_CONFIDENCE, role_reason

        except Exception:
            logger.exception("Injection detection failed — allowing request")
            return False, 0.0, "detection_error"

        return False, 0.0, "clean"

    # ------------------------------------------------------------------
    # Layer 1 — pattern matching
    # ------------------------------------------------------------------

    def _check_patterns(self, text: str) -> Tuple[bool, str]:
        """Return ``(True, description)`` if a known pattern matches."""
        for pattern in self.KNOWN_PATTERNS:
            if pattern.search(text):
                return True, f"known_pattern: {pattern.pattern}"
        return False, ""

    # ------------------------------------------------------------------
    # Layer 2 — entropy analysis
    # ------------------------------------------------------------------

    @staticmethod
    def _check_entropy(text: str) -> Tuple[bool, float]:
        """Flag text with abnormally high Shannon entropy.

        High entropy may indicate obfuscated injection payloads
        (base-64 blobs, encoded instructions, etc.).
        """
        if len(text) < _MIN_TEXT_LENGTH_FOR_ENTROPY:
            return False, 0.0

        freq = Counter(text)
        length = len(text)
        entropy = -sum(
            (count / length) * math.log2(count / length)
            for count in freq.values()
        )
        return entropy > _ENTROPY_THRESHOLD, entropy

    # ------------------------------------------------------------------
    # Layer 3 — role boundary violations
    # ------------------------------------------------------------------

    def _check_role_boundaries(self, text: str) -> Tuple[bool, str]:
        """Detect attempts to inject system/assistant role markers."""
        for pattern in self.ROLE_BOUNDARY_PATTERNS:
            if pattern.search(text):
                return True, f"role_boundary_violation: {pattern.pattern}"
        return False, ""
