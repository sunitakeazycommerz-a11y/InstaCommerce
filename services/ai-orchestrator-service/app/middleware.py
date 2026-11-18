"""Rate limiting and concurrency backpressure middleware for AI Orchestrator Service."""

from __future__ import annotations

import asyncio
import hashlib
import json
import logging
import math
from typing import Any, Awaitable, Callable, Dict, Optional

from fastapi import Request
from fastapi.responses import JSONResponse, Response

from app.config import Settings
from app.core.logging import log_event
from app.guardrails.rate_limiter import RATE_LIMIT_EXCEEDED


def _extract_client_ip(request: Request, settings: Settings) -> str:
    if settings.agent_trust_forwarded_for:
        forwarded_for = request.headers.get("x-forwarded-for")
        if forwarded_for:
            return forwarded_for.split(",", maxsplit=1)[0].strip()
    if request.client and request.client.host:
        return request.client.host
    return "unknown"


async def _extract_user_subject(request: Request, settings: Settings) -> Optional[str]:
    explicit_user = request.headers.get(settings.user_id_header_name)
    if explicit_user:
        return explicit_user.strip()
    content_type = request.headers.get("content-type", "")
    if "application/json" not in content_type.lower():
        return None
    try:
        payload = await request.json()
    except (json.JSONDecodeError, TypeError, ValueError):
        return None
    if not isinstance(payload, dict):
        return None
    raw_user_id = payload.get("user_id")
    if raw_user_id is None:
        return None
    return str(raw_user_id).strip() or None


def _rate_limited_response(scope: str, principal: str, meta: Dict[str, Any]) -> JSONResponse:
    retry_after_seconds = max(1, math.ceil(float(meta.get("retry_after_seconds", 1.0))))
    principal_hash = hashlib.sha256(principal.encode("utf-8")).hexdigest()[:12]
    log_event(
        "agent_rate_limited",
        level=logging.WARNING,
        scope=scope,
        principal_hash=principal_hash,
        retry_after_seconds=retry_after_seconds,
    )
    if RATE_LIMIT_EXCEEDED:
        try:
            RATE_LIMIT_EXCEEDED.labels(
                scope=scope, reason=meta.get("reason", "rate_limit_exceeded")
            ).inc()
        except Exception:
            logging.getLogger("ai_orchestrator.middleware").warning(
                "Failed to increment rate limit metric", exc_info=True
            )
    return JSONResponse(
        status_code=429,
        content={
            "detail": "Rate limit exceeded",
            "message": "Please wait a moment before sending another message.",
        },
        headers={
            "Retry-After": str(retry_after_seconds),
            "X-Agent-RateLimit-Scope": scope,
            "X-Agent-RateLimit-Limit": str(meta.get("limit", 0)),
            "X-Agent-RateLimit-Remaining": str(int(meta.get("remaining_tokens", 0))),
        },
    )


async def enforce_agent_guardrails(
    request: Request,
    call_next: Callable[[Request], Awaitable[Response]],
    settings: Settings,
) -> Response:
    if (
        not request.url.path.startswith(("/agent/", "/v2/agent/", "/v2/assist"))
        or request.url.path == "/v2/agent/health"
    ):
        return await call_next(request)

    client_ip = _extract_client_ip(request, settings)
    ip_limiter = getattr(request.app.state, "agent_ip_rate_limiter", None)
    ip_meta: Dict[str, Any] = {}
    if ip_limiter:
        ip_allowed, ip_meta = ip_limiter.allow(client_ip)
        if not ip_allowed:
            return _rate_limited_response("ip", client_ip, ip_meta)

    user_subject = await _extract_user_subject(request, settings)
    user_meta: Dict[str, Any] = {}
    user_limiter = getattr(request.app.state, "agent_user_rate_limiter", None)
    if user_subject and user_limiter:
        user_allowed, user_meta = user_limiter.allow(user_subject)
        if not user_allowed:
            return _rate_limited_response("user", user_subject, user_meta)

    semaphore = getattr(request.app.state, "agent_request_semaphore", None)
    acquired = False
    if semaphore:
        try:
            await asyncio.wait_for(
                semaphore.acquire(),
                timeout=max(settings.agent_queue_acquire_timeout_ms, 1) / 1000.0,
            )
            acquired = True
        except asyncio.TimeoutError:
            log_event("agent_backpressure_rejected", level=logging.WARNING, path=request.url.path)
            return JSONResponse(
                status_code=503,
                content={"detail": "Agent service is saturated; retry shortly"},
                headers={"Retry-After": "1"},
            )

    try:
        response = await call_next(request)
    finally:
        if acquired and semaphore:
            semaphore.release()

    if ip_meta:
        response.headers["X-Agent-IP-Limit"] = str(ip_meta.get("limit", 0))
        response.headers["X-Agent-IP-Remaining"] = str(int(ip_meta.get("remaining_tokens", 0)))
    if user_meta:
        response.headers["X-Agent-User-Limit"] = str(user_meta.get("limit", 0))
        response.headers["X-Agent-User-Remaining"] = str(int(user_meta.get("remaining_tokens", 0)))
    return response
