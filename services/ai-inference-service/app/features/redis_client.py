"""Canonical Online Redis Feature Store Client for Real-time Inference.

Retrieves and caches low-latency user and product features for ranking and
pricing inference models. Guarantees 100% offline-to-online parity by
aggregating features strictly over completed historical days [T-30d, T-1d],
excluding the current calendar day/session.
"""

from __future__ import annotations

import datetime
import json
import logging
import math
from typing import Any, Dict, List, Optional, Sequence, cast

from app.core.telemetry import (
    create_child_trace_context,
    feature_span,
    get_current_trace_context,
)
from app.features.parity import compute_trailing_conversion_rate, evaluate_feature_parity
from app.features.redis_parsing import (
    decode_redis_text,
    parse_hash_value,
    parse_json_dict,
    parse_user_hash,
)
from app.features.store import FeatureStoreClient

try:
    import redis as _redis_lib
    redis_lib: Any = _redis_lib
except ImportError:
    redis_lib = None

logger = logging.getLogger(__name__)

_decode_redis_text = decode_redis_text
_parse_hash_value = parse_hash_value


class RedisFeatureStoreClient(FeatureStoreClient):
    """Canonical Redis online feature store client with cache and in-memory fallback."""

    backend = "redis"

    def __init__(
        self,
        redis_host: str = "localhost",
        redis_port: int = 6379,
        redis_db: int = 0,
        redis_client: Optional[Any] = None,
        redis_url: Optional[str] = None,
        prefix: str = "user:features",
        cache: Optional[Any] = None,
        fallback_store: Optional[Dict[str, Dict[str, Any]]] = None,
    ) -> None:
        self.redis_host = redis_host
        self.redis_port = redis_port
        self.redis_db = redis_db
        self.redis_url = redis_url
        self._prefix = prefix.rstrip(":") if prefix else "user:features"
        self._in_memory_store: Dict[str, str] = {}
        self._fallback_store: Dict[str, Dict[str, Any]] = dict(fallback_store or {})
        self.cache = cache
        self._client = redis_client
        self.last_span: Optional[Dict[str, Any]] = None
        self.recorded_spans: List[Dict[str, Any]] = []

        if self._client is None and redis_lib is not None:
            try:
                if redis_url:
                    self._client = redis_lib.Redis.from_url(
                        redis_url,
                        socket_connect_timeout=0.2,
                        decode_responses=True,
                    )
                else:
                    self._client = redis_lib.Redis(
                        host=redis_host,
                        port=redis_port,
                        db=redis_db,
                        socket_connect_timeout=0.2,
                        decode_responses=True,
                    )
                self._client.ping()
                logger.info("Connected to Redis feature store at %s:%d", redis_host, redis_port)
            except Exception as e:
                logger.warning("Redis unavailable (%s), falling back to in-memory store", e)
                self._client = None

    def _record_span(self, span_info: Dict[str, Any]) -> None:
        self.last_span = span_info
        self.recorded_spans.append(span_info)

    def get_propagation_headers(self) -> Dict[str, str]:
        """Return W3C traceparent and X-Trace-Id headers from the latest feature-store span."""
        if self.last_span is not None:
            return {
                "traceparent": str(self.last_span["traceparent"]),
                "X-Trace-Id": str(self.last_span["X-Trace-Id"]),
            }
        ctx = get_current_trace_context() or create_child_trace_context()
        return {
            "traceparent": ctx["traceparent"],
            "X-Trace-Id": ctx["trace_id"],
        }

    def set_fallback_features(self, user_id: str, features: Dict[str, Any]) -> None:
        """Populate in-memory and optional L1 cache fallback features for a user."""
        self._fallback_store[user_id] = dict(features)
        if self.cache is not None and hasattr(self.cache, "set"):
            try:
                self.cache.set(f"user:features:{user_id}", dict(features))
            except Exception as exc:
                logger.warning("Failed to populate cache fallback for %s: %s", user_id, exc)

    def _get_raw(self, key: str) -> Optional[str]:
        if self._client is not None:
            try:
                res = self._client.get(key)
                if isinstance(res, bytes):
                    return res.decode("utf-8")
                return cast(Optional[str], res)
            except Exception as e:
                logger.warning("Redis get error for %s: %s", key, e)
        return self._in_memory_store.get(key)

    def _set_raw(self, key: str, val: str, ttl: int = 86400) -> None:
        if self._client is not None:
            try:
                self._client.set(key, val, ex=ttl)
                return
            except Exception as e:
                logger.warning("Redis set error for %s: %s", key, e)
        self._in_memory_store[key] = val

    def _hgetall_raw(self, key: str) -> Optional[Dict[Any, Any]]:
        if self._client is not None:
            try:
                res = self._client.hgetall(key)
                if isinstance(res, dict):
                    return cast(Dict[Any, Any], res)
            except Exception as e:
                logger.warning("Redis hgetall error for %s: %s", key, e)
        return None

    @staticmethod
    def _parse_user_hash(raw_hash: Dict[Any, Any]) -> Dict[str, Any]:
        return parse_user_hash(raw_hash)

    @staticmethod
    def _parse_json_dict(raw: Optional[str]) -> Optional[Dict[str, Any]]:
        return parse_json_dict(raw)

    def get_user_features(self, user_id: str, traceparent: Optional[str] = None) -> Dict[str, Any]:
        """Fetch user online feature vector, preferring Go ETL Redis hashes."""
        cache_key = f"user:features:{user_id}"
        with feature_span(
            "get_user_features",
            user_id,
            traceparent_override=traceparent,
            on_span_created=self._record_span,
        ):
            raw_hash = self._hgetall_raw(cache_key)
            if raw_hash:
                parsed_hash = self._parse_user_hash(raw_hash)
                if parsed_hash:
                    self._update_fallback_caches(user_id, cache_key, parsed_hash)
                    return parsed_hash

            parsed_json = self._parse_json_dict(self._get_raw(f"user_features:{user_id}"))
            if parsed_json is not None:
                self._update_fallback_caches(user_id, cache_key, parsed_json)
                return parsed_json

        if self.cache is not None and hasattr(self.cache, "get"):
            try:
                cached = self.cache.get(cache_key) or self.cache.get(user_id)
                if isinstance(cached, dict) and cached:
                    return dict(cached)
            except Exception as exc:
                logger.warning("Feature cache lookup failed for %s: %s", user_id, exc)

        if user_id in self._fallback_store:
            return dict(self._fallback_store[user_id])

        return {
            "user_id": user_id,
            "order_count_30d": 0,
            "avg_basket_cents": 0.0,
            "conversion_rate_30d": 0.0,
            "is_fallback": True,
        }

    def _update_fallback_caches(self, user_id: str, cache_key: str, features: Dict[str, Any]) -> None:
        if self.cache is not None and hasattr(self.cache, "set"):
            try:
                self.cache.set(cache_key, dict(features))
            except Exception as exc:
                logger.warning("Failed to update cache for %s: %s", user_id, exc)
        if self._fallback_store:
            self._fallback_store[user_id] = dict(features)

    def set_user_features(
        self,
        user_id: str,
        features: Dict[str, Any],
        ttl_seconds: int = 86400,
        traceparent: Optional[str] = None,
    ) -> None:
        """Store user online feature vector in Redis."""
        key = f"user_features:{user_id}"
        with feature_span(
            "set_user_features",
            user_id,
            traceparent_override=traceparent,
            on_span_created=self._record_span,
        ):
            self._set_raw(key, json.dumps(features), ttl=ttl_seconds)

    def get_product_features(self, product_id: str, traceparent: Optional[str] = None) -> Dict[str, Any]:
        """Fetch product online feature vector."""
        with feature_span(
            "get_product_features",
            product_id,
            traceparent_override=traceparent,
            on_span_created=self._record_span,
        ):
            parsed_json = self._parse_json_dict(self._get_raw(f"product_features:{product_id}"))
            if parsed_json is not None:
                return parsed_json
        return {
            "product_id": product_id,
            "conversion_rate_30d": 0.0,
            "sales_velocity_7d": 0.0,
            "is_fallback": True,
        }

    def set_product_features(
        self,
        product_id: str,
        features: Dict[str, Any],
        ttl_seconds: int = 86400,
        traceparent: Optional[str] = None,
    ) -> None:
        """Store product online feature vector in Redis."""
        key = f"product_features:{product_id}"
        with feature_span(
            "set_product_features",
            product_id,
            traceparent_override=traceparent,
            on_span_created=self._record_span,
        ):
            self._set_raw(key, json.dumps(features), ttl=ttl_seconds)

    def get_features(self, entity_id: str, feature_names: Sequence[str] = ()) -> Dict[str, float]:
        """Fetch float feature dictionary for entity_id compatible with FeatureStoreClient."""
        raw_features = self.get_user_features(entity_id)
        if raw_features.get("is_fallback") and self._client is not None and self._prefix != "user:features":
            prefixed_key = f"{self._prefix}:{entity_id}" if self._prefix else entity_id
            with feature_span(
                "get_features",
                entity_id,
                on_span_created=self._record_span,
            ):
                if feature_names and hasattr(self._client, "hmget"):
                    try:
                        values = cast(List[Any], self._client.hmget(prefixed_key, list(feature_names)))
                        return {
                            name: float(value)
                            for name, value in zip(feature_names, values)
                            if value is not None
                        }
                    except Exception as exc:
                        logger.warning("Redis hmget failed for %s: %s", prefixed_key, exc)
                raw_prefixed = self._hgetall_raw(prefixed_key)
                if raw_prefixed:
                    raw_features = self._parse_user_hash(raw_prefixed)

        result: Dict[str, float] = {}
        keys_to_extract = list(feature_names) if feature_names else list(raw_features.keys())
        for name in keys_to_extract:
            if name in ("is_fallback", "user_id", "product_id"):
                continue
            val = raw_features.get(name)
            if isinstance(val, (int, float)) and not isinstance(val, bool) and math.isfinite(float(val)):
                result[name] = float(val)
        return result

    def health(self) -> Dict[str, str]:
        if self._client is not None and hasattr(self._client, "ping"):
            try:
                self._client.ping()
                return {"backend": self.backend, "status": "ok"}
            except Exception as exc:
                logger.warning("Redis health ping failed: %s", exc)
                return {"backend": self.backend, "status": "degraded"}
        if self._client is not None:
            return {"backend": self.backend, "status": "ok"}
        return {"backend": self.backend, "status": "fallback"}

    def close(self) -> None:
        if self._client is not None and hasattr(self._client, "close"):
            try:
                self._client.close()
            except Exception as exc:
                logger.warning("Failed to close Redis feature store client: %s", exc)

    @staticmethod
    def compute_trailing_conversion_rate(
        orders: List[Dict[str, Any]],
        sessions: List[Dict[str, Any]],
        reference_date: Optional[datetime.date] = None,
    ) -> float:
        """Compute 30-day conversion rate strictly excluding the current calendar day."""
        return compute_trailing_conversion_rate(orders, sessions, reference_date=reference_date)

    def run_daily_parity_check(
        self,
        offline_features_map: Dict[str, Dict[str, float]],
        max_drift_pct: float = 1.0,
    ) -> Dict[str, Any]:
        """Perform daily automated feature parity audit across user samples."""
        return evaluate_feature_parity(
            self.get_user_features,
            offline_features_map,
            max_drift_pct=max_drift_pct,
        )
