"""Structured JSON logging and PII redaction helpers."""

from __future__ import annotations

import json
import logging
from datetime import datetime, timezone
from typing import Any, Dict

from app.config import Settings
from app.core.redaction import scrub_pii_and_secrets, scrub_structured_value

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

    def redact_text(self, value: str) -> str:
        if not self._enabled:
            return value
        return scrub_pii_and_secrets(value, include_secrets=True)

    def redact(self, value: Any) -> Any:
        if not self._enabled:
            return value
        return scrub_structured_value(value, include_secrets=True)


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
            if key in STANDARD_LOG_RECORD_ATTRS or key == "pii_vault_mapping":
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
