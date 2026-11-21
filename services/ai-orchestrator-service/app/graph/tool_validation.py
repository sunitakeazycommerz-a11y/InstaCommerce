"""URL, SSRF, and path-traversal validation helpers for orchestrator tools."""

from __future__ import annotations

import ipaddress
import logging
import posixpath
import re
from typing import Any, Dict, Optional, Tuple
from urllib.parse import SplitResult, unquote, urlsplit

from app.graph.circuit_breaker import ToolDescriptor

logger = logging.getLogger("ai_orchestrator.tools")

_ALLOWED_PATH_PREFIXES: Tuple[str, ...] = (
    "/search",
    "/products",
    "/pricing",
    "/inventory",
    "/cart",
    "/orders",
    "/api/pricing",
    "/api/inventory",
    "/api/carts",
    "/api/orders",
)
_TRAVERSAL_INDICATORS: Tuple[str, ...] = (
    "..",
    "/etc/",
    ".env",
    "%2e%2e",
    "%2f",
    "%5c",
    "\\",
    "\x00",
)
_SCHEME_PATTERN = re.compile(r"^[a-zA-Z][a-zA-Z0-9+.\-]*:")
_PARAM_SCHEME_INDICATORS: Tuple[str, ...] = (
    "://",
    "javascript:",
    "data:",
    "file:",
    "gopher:",
)
_PATH_PARAM_PATTERN = re.compile(r"\A[a-zA-Z0-9_.\-]+\Z")
_UNRESOLVED_PLACEHOLDER_RE = re.compile(r"\{[^}]+\}")
_BLOCKED_METADATA_HOSTS = frozenset({"169.254.169.254", "metadata.google.internal", "metadata.goog"})


def _unquote_iteratively(raw: str, rounds: int = 3) -> str:
    current = raw
    for _ in range(rounds):
        decoded = unquote(current)
        if decoded == current:
            break
        current = decoded
    return current


def _has_traversal(value: str) -> bool:
    for candidate in (value, _unquote_iteratively(value)):
        lowered = candidate.lower()
        if any(ind in lowered for ind in _TRAVERSAL_INDICATORS):
            return True
    return False


def _is_permitted_path(normalized_path: str) -> bool:
    return any(
        normalized_path == prefix or normalized_path.startswith(f"{prefix}/")
        for prefix in _ALLOWED_PATH_PREFIXES
    )


def _validate_base_url(base_url: str) -> Tuple[Optional[SplitResult], Optional[str]]:
    parsed = urlsplit(base_url)
    if parsed.scheme not in ("http", "https") or not parsed.netloc:
        return None, "Security violation: invalid or non-HTTP tool base URL scheme"
    if parsed.username is not None or parsed.password is not None:
        return None, "Security violation: userinfo not allowed in tool base URL"
    hostname = (parsed.hostname or "").rstrip(".").lower()
    if not hostname or hostname in _BLOCKED_METADATA_HOSTS:
        return None, "Security violation: blocked SSRF target in tool base URL"
    try:
        ip = ipaddress.ip_address(hostname)
    except ValueError:
        ip = None
    if isinstance(ip, ipaddress.IPv6Address) and ip.ipv4_mapped is not None:
        ip = ip.ipv4_mapped
    if ip is not None and (ip.is_link_local or ip.is_unspecified or ip.is_multicast):
        return None, "Security violation: blocked SSRF IP range in tool base URL"
    return parsed, None


def _validate_and_build_url(
    tool: ToolDescriptor,
    params: Optional[Dict[str, Any]],
    path_params: Optional[Dict[str, str]],
) -> Tuple[Optional[str], Optional[str]]:
    parsed_base, base_err = _validate_base_url(tool.base_url)
    if base_err or parsed_base is None:
        logger.error("security.invalid_base_url", extra={"tool": tool.name})
        return None, base_err or "Security violation: invalid tool base URL"

    path = tool.path
    if (
        not path.startswith("/")
        or path.startswith("//")
        or "://" in path
        or "?" in path
        or "#" in path
        or any(ch in path for ch in ("\r", "\n", "\x00", "\\"))
        or _SCHEME_PATTERN.match(path.lstrip("/"))
        or _has_traversal(path)
    ):
        logger.error("security.invalid_tool_path", extra={"tool": tool.name})
        return None, "Security violation: invalid or unsafe tool path"

    if path_params:
        for key, raw_value in path_params.items():
            value = raw_value if isinstance(raw_value, str) else str(raw_value)
            decoded = _unquote_iteratively(value)
            if (
                _has_traversal(value)
                or "/" in value
                or "/" in decoded
                or "\\" in value
                or "\\" in decoded
                or value.startswith(".")
                or decoded.startswith(".")
                or "://" in value
                or "://" in decoded
                or _SCHEME_PATTERN.match(value)
                or _SCHEME_PATTERN.match(decoded)
            ):
                logger.error(
                    "security.path_traversal_attempt",
                    extra={"tool": tool.name, "param_key": key},
                )
                return None, "Security violation: path traversal detected in parameter"
            if not _PATH_PARAM_PATTERN.fullmatch(value):
                logger.error(
                    "security.invalid_path_param_chars",
                    extra={"tool": tool.name, "param_key": key},
                )
                return None, "Security violation: invalid characters in path parameter"
            path = path.replace(f"{{{key}}}", value)

    if _UNRESOLVED_PLACEHOLDER_RE.search(path):
        logger.error("security.missing_path_param", extra={"tool": tool.name})
        return None, "Missing required path parameter"

    if params:
        for key, raw_val in params.items():
            val_str = str(raw_val)
            if _has_traversal(val_str):
                logger.error(
                    "security.path_traversal_attempt_in_params",
                    extra={"tool": tool.name, "param_key": key},
                )
                return None, "Security violation: path traversal detected in query params"
            for candidate in (val_str.lower(), _unquote_iteratively(val_str).lower()):
                if any(ind in candidate for ind in _PARAM_SCHEME_INDICATORS):
                    logger.error(
                        "security.scheme_injection_in_params",
                        extra={"tool": tool.name, "param_key": key},
                    )
                    return None, "Security violation: scheme injection detected in query params"

    normalized_path = posixpath.normpath(path)
    if normalized_path.startswith("//") or not _is_permitted_path(normalized_path):
        logger.error(
            "security.unauthorized_path_access",
            extra={"tool": tool.name, "path": normalized_path},
        )
        return None, "Security violation: path not in permitted API whitelist"

    url = f"{tool.base_url.rstrip('/')}{normalized_path}"
    parsed_url = urlsplit(url)
    if (
        parsed_url.scheme != parsed_base.scheme
        or parsed_url.netloc != parsed_base.netloc
        or parsed_url.path != normalized_path
        or parsed_url.query
        or parsed_url.fragment
    ):
        logger.error("security.url_integrity_violation", extra={"tool": tool.name})
        return None, "Security violation: resolved URL failed integrity check"

    return url, None
