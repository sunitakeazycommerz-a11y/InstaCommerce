from __future__ import annotations

import re
from typing import Any, List, Tuple

EMAIL_PATTERN: re.Pattern[str] = re.compile(r"[\w\.-]+@[\w\.-]+\.\w{2,}")
SSN_PATTERN: re.Pattern[str] = re.compile(r"\b\d{3}-\d{2}-\d{4}\b")
CARD_PATTERN: re.Pattern[str] = re.compile(r"\b(?:\d[ \-]*?){13,16}\b")
OTP_PREFIX_PATTERN: re.Pattern[str] = re.compile(
    r"\b(?:otp|one[- ]time[- ]pass(?:word|code)|verification\s+code|"
    r"passcode|security\s+code|2fa\s+code|pin\s+code)"
    r"(?:\s*(?:is|for|code|=|:|#|-)\s*|\s+)\d{4,8}\b",
    re.IGNORECASE,
)
OTP_SUFFIX_PATTERN: re.Pattern[str] = re.compile(
    r"\b\d{4,8}\s+(?:is\s+(?:my|the|your)\s+)?(?:otp|verification\s+code|passcode|2fa\s+code)\b",
    re.IGNORECASE,
)
PHONE_PATTERN: re.Pattern[str] = re.compile(
    r"(?<!\w)(?:"
    r"\+\d{1,3}[\s.-]?(?:\(\d{2,4}\)[\s.-]?|\d{2,4}[\s.-]?)*\d{4,10}"
    r"|"
    r"(?:\(\d{3}\)[\s.-]?|\d{3}[\s.-])\d{3}[\s.-]\d{4}"
    r"|"
    r"(?:\+91[\-\s]?)?(?:0|91)?[6789]\d{9}"
    r")\b"
)
IPV4_PATTERN: re.Pattern[str] = re.compile(r"\b(?:\d{1,3}\.){3}\d{1,3}\b")

SECRET_PATTERNS: List[Tuple[re.Pattern[str], str]] = [
    (re.compile(r"\bBearer\s+[A-Za-z0-9\-._~+/]+=*", re.IGNORECASE), "TOKEN"),
    (
        re.compile(
            r"\b(?:password|passwd|secret|api[_-]?key|internal[_-]?token|"
            r"access[_-]?token|refresh[_-]?token)\s*[:=]\s*[\"']?[^\s\"',}]{4,}[\"']?",
            re.IGNORECASE,
        ),
        "SECRET",
    ),
]

CORE_PII_PATTERNS: List[Tuple[re.Pattern[str], str]] = [
    (EMAIL_PATTERN, "EMAIL"),
    (SSN_PATTERN, "SSN"),
    (CARD_PATTERN, "CARD"),
    (OTP_PREFIX_PATTERN, "OTP"),
    (OTP_SUFFIX_PATTERN, "OTP"),
    (PHONE_PATTERN, "PHONE"),
    (IPV4_PATTERN, "IP"),
]

SENSITIVE_FIELD_NAMES: frozenset[str] = frozenset(
    {
        "password",
        "passwd",
        "secret",
        "api_key",
        "apikey",
        "llm_api_key",
        "internal_service_token",
        "x-internal-token",
        "authorization",
        "token",
        "access_token",
        "refresh_token",
        "pii_vault_mapping",
        "otp",
        "card_number",
        "cvv",
        "ssn",
    }
)


def scrub_pii_and_secrets(text: str, *, include_secrets: bool = True) -> str:
    if not text:
        return text
    scrubbed = text
    if include_secrets:
        for pattern, label in SECRET_PATTERNS:
            scrubbed = pattern.sub(f"[REDACTED_{label}]", scrubbed)
    for pattern, label in CORE_PII_PATTERNS:
        scrubbed = pattern.sub(f"[REDACTED_{label}]", scrubbed)
    return scrubbed


def scrub_structured_value(value: Any, *, include_secrets: bool = True) -> Any:
    if isinstance(value, str):
        return scrub_pii_and_secrets(value, include_secrets=include_secrets)
    if isinstance(value, dict):
        result: dict[Any, Any] = {}
        for k, v in value.items():
            if isinstance(k, str) and k.strip().lower() in SENSITIVE_FIELD_NAMES:
                result[k] = "[REDACTED_SECRET]"
            else:
                result[k] = scrub_structured_value(v, include_secrets=include_secrets)
        return result
    if isinstance(value, list):
        return [scrub_structured_value(item, include_secrets=include_secrets) for item in value]
    if isinstance(value, tuple):
        return tuple(scrub_structured_value(item, include_secrets=include_secrets) for item in value)
    return value
