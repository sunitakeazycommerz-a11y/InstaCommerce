"""LLM client, cached retrieval provider, and payload helper functions."""

from __future__ import annotations

import hashlib
import json
import logging
import time
from typing import Any, Dict, List, Optional, Tuple
from uuid import UUID

import httpx
from pydantic import ValidationError

from app.config import Settings
from app.core.logging import sanitize_llm_payload
from app.guardrails.injection import wrap_user_input
from app.schemas import ToolCall

logger = logging.getLogger("ai_orchestrator.llm")


class RetrievalProvider:
    async def retrieve(self, query: str, context: Dict[str, Any]) -> List[Dict[str, Any]]:
        return []


class CachedRetrievalProvider(RetrievalProvider):
    def __init__(self, ttl_seconds: float, max_entries: int, max_results: int) -> None:
        self._ttl_seconds = ttl_seconds
        self._max_entries = max_entries
        self._max_results = max_results
        self._cache: Dict[str, Tuple[float, List[Dict[str, Any]]]] = {}

    async def retrieve(self, query: str, context: Dict[str, Any]) -> List[Dict[str, Any]]:
        if not query:
            return []
        key = self._cache_key(query, context)
        now = time.monotonic()
        cached = self._cache.get(key)
        if cached and now - cached[0] <= self._ttl_seconds:
            return cached[1]
        data = await self._fetch(query, context)
        if self._max_results:
            data = data[: self._max_results]
        self._cache[key] = (now, data)
        self._prune(now)
        return data

    async def _fetch(self, query: str, context: Dict[str, Any]) -> List[Dict[str, Any]]:
        return []

    def _cache_key(self, query: str, context: Dict[str, Any]) -> str:
        digest = hashlib.sha256(
            json.dumps({"query": query, "context": context}, sort_keys=True).encode("utf-8")
        )
        return digest.hexdigest()

    def _prune(self, now: float) -> None:
        expired = [
            key
            for key, (timestamp, _) in self._cache.items()
            if now - timestamp > self._ttl_seconds
        ]
        for key in expired:
            self._cache.pop(key, None)
        if len(self._cache) <= self._max_entries:
            return
        oldest = sorted(self._cache.items(), key=lambda item: item[1][0])
        for key, _ in oldest[: max(len(self._cache) - self._max_entries, 0)]:
            self._cache.pop(key, None)


class LlmClient:
    def __init__(self, config: Settings) -> None:
        self._enabled = config.llm_api_url is not None
        self._url = str(config.llm_api_url) if config.llm_api_url else None
        self._headers: Dict[str, str] = {}
        if config.llm_api_key:
            self._headers["Authorization"] = f"Bearer {config.llm_api_key}"
        self._model = config.llm_model
        self._client = (
            httpx.AsyncClient(timeout=httpx.Timeout(config.request_timeout_seconds))
            if self._enabled
            else None
        )

    async def close(self) -> None:
        if self._client:
            await self._client.aclose()

    async def generate(self, endpoint: str, payload: Dict[str, Any]) -> Optional[Dict[str, Any]]:
        if not self._enabled or not self._client or not self._url:
            return None
        try:
            sanitized_payload = sanitize_llm_payload(dict(payload))
            if "query" in sanitized_payload and isinstance(sanitized_payload["query"], str):
                sanitized_payload["query"] = wrap_user_input(sanitized_payload["query"])
            response = await self._client.post(
                self._url,
                json={"endpoint": endpoint, "input": sanitized_payload, "model": self._model},
                headers=self._headers,
            )
            response.raise_for_status()
            data = response.json()
            if isinstance(data, dict):
                return data
        except (httpx.RequestError, httpx.HTTPStatusError, ValueError) as exc:
            logger.warning("LLM hook failed: %s", exc.__class__.__name__)
        return None


def derive_request_id(explicit: Optional[str], payload: Dict[str, Any], prefix: str) -> str:
    if explicit:
        return explicit
    digest = hashlib.sha256(json.dumps(payload, sort_keys=True).encode("utf-8")).hexdigest()
    return f"{prefix}_{digest[:12]}"


def parse_uuid(value: Any) -> Optional[UUID]:
    if not value:
        return None
    try:
        return UUID(str(value))
    except (TypeError, ValueError):
        return None


def parse_tool_calls(raw: Any) -> List[ToolCall]:
    if not isinstance(raw, list):
        return []
    tool_calls: List[ToolCall] = []
    for item in raw:
        try:
            tool_calls.append(ToolCall.model_validate(item))
        except ValidationError:
            continue
    return tool_calls


def normalize_candidates(product_id: UUID, candidates: List[UUID], limit: int) -> List[UUID]:
    seen = set()
    normalized: List[UUID] = []
    for candidate in candidates:
        if candidate == product_id or candidate in seen:
            continue
        seen.add(candidate)
        normalized.append(candidate)
        if len(normalized) >= limit:
            break
    return normalized


def extract_product_ids(data: Dict[str, Any]) -> List[UUID]:
    results: List[UUID] = []
    candidates = data.get("results") or data.get("content") or []
    if isinstance(candidates, list):
        for item in candidates:
            if isinstance(item, dict):
                product_id = parse_uuid(item.get("id"))
                if product_id:
                    results.append(product_id)
    return results
