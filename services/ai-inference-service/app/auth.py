"""Internal service authentication middleware.

Validates X-Internal-Service and X-Internal-Token headers on all
non-health endpoints using constant-time comparison to prevent
timing side-channel attacks.

Supports per-service tokens via INTERNAL_SERVICE_ALLOWED_CALLERS (JSON map).
Falls back to the shared INTERNAL_SERVICE_TOKEN during migration (see ADR-010).
"""

import hmac
import json
import logging
import os
from typing import Callable

from starlette.middleware.base import BaseHTTPMiddleware
from starlette.requests import Request
from starlette.responses import JSONResponse

logger = logging.getLogger(__name__)

SKIP_PATHS = frozenset({
    "/health", "/health/ready", "/health/live",
    "/metrics", "/openapi.json", "/docs", "/redoc",
})


class InternalServiceAuthMiddleware(BaseHTTPMiddleware):
    """Validates internal service-to-service authentication.

    Mirrors the Java InternalServiceAuthFilter and Go InternalAuthMiddleware
    patterns: constant-time token comparison via X-Internal-Service +
    X-Internal-Token headers.

    Per-service tokens (INTERNAL_SERVICE_ALLOWED_CALLERS) take precedence
    over the shared token (INTERNAL_SERVICE_TOKEN) to support gradual
    migration to per-caller credentials.
    """

    def __init__(self, app, expected_token: str | None = None):
        super().__init__(app)
        self._expected_token = expected_token or os.getenv("INTERNAL_SERVICE_TOKEN", "")
        self._allowed_callers: dict[str, str] = {}
        callers_json = os.getenv("INTERNAL_SERVICE_ALLOWED_CALLERS", "")
        if callers_json:
            try:
                self._allowed_callers = json.loads(callers_json)
            except json.JSONDecodeError:
                logger.warning("Failed to parse INTERNAL_SERVICE_ALLOWED_CALLERS")
        if not self._expected_token and not self._allowed_callers:
            logger.warning(
                "No service tokens configured -- all authenticated requests will be rejected"
            )

    async def dispatch(self, request: Request, call_next: Callable):
        if request.url.path in SKIP_PATHS:
            return await call_next(request)

        service_name = request.headers.get("x-internal-service")
        token = request.headers.get("x-internal-token")

        if not service_name or not token:
            logger.warning(
                "auth.missing_headers path=%s remote=%s",
                request.url.path,
                request.client.host if request.client else "unknown",
            )
            return JSONResponse(
                status_code=401,
                content={
                    "error": "UNAUTHORIZED",
                    "message": "Missing internal service authentication headers",
                },
            )

        if not self._is_valid_token(service_name, token):
            logger.warning(
                "auth.invalid_token path=%s service=%s remote=%s",
                request.url.path,
                service_name,
                request.client.host if request.client else "unknown",
            )
            return JSONResponse(
                status_code=403,
                content={
                    "error": "FORBIDDEN",
                    "message": "Invalid internal service token",
                },
            )

        request.state.internal_service = service_name
        return await call_next(request)

    def _is_valid_token(self, service_name: str, token: str) -> bool:
        """Check per-service token first, then fall back to shared token."""
        # Per-service token takes precedence
        per_service_token = self._allowed_callers.get(service_name)
        if per_service_token:
            return hmac.compare_digest(token, per_service_token)
        # Fall back to shared token during migration
        if self._expected_token:
            return hmac.compare_digest(token, self._expected_token)
        return False
