"""Structured JSON logging and PII redaction helpers."""

from __future__ import annotations

import json
import logging
import re
from datetime import datetime, timezone
from typing import Any, Dict, Tuple

from app.config import Settings

logger = logging.getLogger("ai_orchestrator")

STANDARD_LOG_RECORD_ATTRS = {
    "args",
    "asctime",
    "created",
    "exc_info",
    "exc_text",
    "filename",
    "funcName",
    "levelname",
    "levelno",
    "lineno",
    "message",
    "module",
    "msecs",
    "msg",
    "name",
    "pathname",
    "process",
    "processName",
    "relativeCreated",
    "stack_info",
    "thread",
    "threadName",
}


class PiiRedactor:
    def __init__(self, enabled: bool = True) -> None:
        self._enabled = enabled
        self._patterns: Tuple[Tuple[re.Pattern[str], str], ...] = (
            (re.compile(r"[\w\.-]+@[\w\.-]+\.\w+"), "[REDACTED_EMAIL]"),
            (re.compile(r"\b\d{3}-?\d{2}-?\d{4}\b"), "[REDACTED_SSN]"),
            (re.compile(r"\b(?:\d[ -]*?){13,16}\b"), "[REDACTED_CARD]"),
            (
                re.compile(r"(?:\+91[\-\s]?)?[0]?(?:91)?[6789]\d{9}\b"),
                "[REDACTED_PHONE]",
            ),
            (
                re.compile(r"\b(?:\+?\d{1,3}[ -]?)?(?:\(?\d{3}\)?[ -]?)\d{3}[ -]?\d{4}\b"),
                "[REDACTED_PHONE]",
            ),
        )

    def redact_text(self, value: str) -> str:
        if not self._enabled:
            return value
        redacted = value
        for pattern, replacement in self._patterns:
            redacted = pattern.sub(replacement, redacted)
        return redacted

    def redact(self, value: Any) -> Any:
        if not self._enabled:
            return value
        if isinstance(value, str):
            return self.redact_text(value)
        if isinstance(value, dict):
            return {key: self.redact(val) for key, val in value.items()}
        if isinstance(value, list):
            return [self.redact(item) for item in value]
        return value


class JsonLogFormatter(logging.Formatter):
    def __init__(self, active_redactor: PiiRedactor) -> None:
        super().__init__()
        self._redactor = active_redactor

    def format(self, record: logging.LogRecord) -> str:
        payload: Dict[str, Any] = {
            "timestamp": datetime.now(timezone.utc).isoformat(),
            "level": record.levelname,
            "logger": record.name,
            "message": record.getMessage(),
        }
        if record.exc_info:
            payload["exc_info"] = self.formatException(record.exc_info)
        for key, value in record.__dict__.items():
            if key in STANDARD_LOG_RECORD_ATTRS:
                continue
            payload[key] = value
        payload = self._redactor.redact(payload)
        return json.dumps(payload, default=str)


_default_settings = Settings()
redactor = PiiRedactor(enabled=_default_settings.pii_redaction_enabled)


def log_event(message: str, level: int = logging.INFO, **fields: Any) -> None:
    logger.log(level, message, extra=redactor.redact(fields))


def configure_logging(config: Settings | None = None) -> None:
    cfg = config or _default_settings
    level = getattr(logging, cfg.log_level.upper(), logging.INFO)
    handler = logging.StreamHandler()
    handler.setFormatter(JsonLogFormatter(redactor))
    logging.basicConfig(level=level, handlers=[handler])


def sanitize_llm_payload(payload: Dict[str, Any]) -> Dict[str, Any]:
    return redactor.redact(payload)
