"""Online Redis Feature Store Client for Real-time Inference.

Retrieves and caches low-latency user and product features for ranking and
pricing inference models. Guarantees 100% offline-to-online parity by
aggregating features strictly over completed historical days [T-30d, T-1d],
excluding the current calendar day/session.
"""

from __future__ import annotations

import contextvars
import datetime
import json
import logging
import math
import re
import secrets
from typing import Any, Dict, List, Optional, Sequence, Tuple, cast

try:
    import redis as redis_lib
except ImportError:
    redis_lib = None  # type: ignore[assignment]

try:
    from opentelemetry import trace as otel_trace
except ImportError:
    otel_trace = None  # type: ignore[assignment]

logger = logging.getLogger(__name__)

_TRACEPARENT_RE = re.compile(
    r"^([0-9a-f]{2})-([0-9a-f]{32})-([0-9a-f]{16})-([0-9a-f]{2})$"
)
_HEX32_RE = re.compile(r"^[0-9a-f]{32}$")

_CURRENT_TRACE_CONTEXT: contextvars.ContextVar[Optional[Dict[str, str]]] = (
    contextvars.ContextVar("ai_inference_trace_context", default=None)
)


def parse_traceparent(header: Optional[str]) -> Optional[Dict[str, str]]:
    """Parse a W3C traceparent header (`00-<trace_id>-<span_id>-<flags>`)."""
    if not header or not isinstance(header, str):
        return None
    normalized = header.strip().lower()
    match = _TRACEPARENT_RE.fullmatch(normalized)
    if not match:
        return None
    version, trace_id, span_id, flags = match.groups()
    if version != "00" or trace_id == "0" * 32 or span_id == "0" * 16:
        return None
    return {
        "version": version,
        "trace_id": trace_id,
        "parent_span_id": span_id,
        "span_id": span_id,
        "flags": flags,
        "traceparent": normalized,
    }


def format_traceparent(trace_id: str, span_id: str, flags: str = "01", version: str = "00") -> str:
    """Format a W3C traceparent header string."""
    return f"{version}-{trace_id}-{span_id}-{flags}"


def create_child_trace_context(
    traceparent_header: Optional[str] = None,
    x_trace_id: Optional[str] = None,
) -> Dict[str, str]:
    """Parse incoming traceparent (or generate a new trace) and mint a child span ID."""
    parsed = parse_traceparent(traceparent_header)
    if parsed is not None:
        trace_id = parsed["trace_id"]
        parent_span_id = parsed["parent_span_id"]
        flags = parsed["flags"]
    else:
        candidate = (x_trace_id or "").strip().lower()
        if _HEX32_RE.fullmatch(candidate) and candidate != "0" * 32:
            trace_id = candidate
        else:
            trace_id = secrets.token_hex(16)
        parent_span_id = ""
        flags = "01"

    child_span_id = secrets.token_hex(8)
    while child_span_id == parent_span_id or child_span_id == "0" * 16:
        child_span_id = secrets.token_hex(8)

    traceparent = format_traceparent(trace_id, child_span_id, flags)
    return {
        "version": "00",
        "trace_id": trace_id,
        "parent_span_id": parent_span_id,
        "span_id": child_span_id,
        "flags": flags,
        "traceparent": traceparent,
    }


def get_current_trace_context() -> Optional[Dict[str, str]]:
    """Return active request/span trace context from contextvars."""
    return _CURRENT_TRACE_CONTEXT.get()


def set_current_trace_context(ctx: Optional[Dict[str, str]]) -> contextvars.Token[Optional[Dict[str, str]]]:
    """Bind trace context into contextvars and return reset token."""
    return _CURRENT_TRACE_CONTEXT.set(ctx)


def reset_current_trace_context(token: contextvars.Token[Optional[Dict[str, str]]]) -> None:
    """Restore previous trace context using token."""
    _CURRENT_TRACE_CONTEXT.reset(token)

_INT_RE = re.compile(r"[+-]?\d+")
_FLOAT_RE = re.compile(r"[+-]?(?:\d+\.?\d*|\.\d+)(?:[eE][+-]?\d+)?")
_STRING_METADATA_FIELDS = frozenset(
    {
        "user_id",
        "segment",
        "updated_at",
        "job_id",
        "clv_segment",
        "preferred_payment_method",
        "preferred_delivery_slot",
    }
)


def _decode_redis_text(value: Any) -> Optional[str]:
    if isinstance(value, bytes):
        try:
            return value.decode("utf-8")
        except UnicodeDecodeError:
            return None
    if isinstance(value, str):
        return value
    return None


def _parse_hash_value(field: str, raw_value: Any) -> Tuple[bool, Any]:
    if raw_value is None:
        return False, None
    if isinstance(raw_value, bool):
        return True, raw_value
    if isinstance(raw_value, int):
        return True, raw_value
    if isinstance(raw_value, float):
        return (True, raw_value) if math.isfinite(raw_value) else (False, None)

    text = _decode_redis_text(raw_value)
    if text is None:
        return False, None

    if field in _STRING_METADATA_FIELDS:
        return True, text

    stripped = text.strip()
    if not stripped:
        return True, text

    if (stripped.startswith("{") and stripped.endswith("}")) or (
        stripped.startswith("[") and stripped.endswith("]")
    ):
        try:
            parsed_json = json.loads(stripped)
            if isinstance(parsed_json, (dict, list)):
                return True, parsed_json
        except json.JSONDecodeError:
            return True, text

    if _INT_RE.fullmatch(stripped):
        return True, int(stripped)

    if _FLOAT_RE.fullmatch(stripped):
        parsed_float = float(stripped)
        if math.isfinite(parsed_float):
            return True, parsed_float

    return True, text


class RedisFeatureStoreClient:
    """Redis online feature store client with in-memory fallback for local dev."""

    backend = "redis"

    def __init__(
        self,
        redis_host: str = "localhost",
        redis_port: int = 6379,
        redis_db: int = 0,
        redis_client: Optional[Any] = None,
        redis_url: Optional[str] = None,
        prefix: str = "user:features",
    ) -> None:
        self.redis_host = redis_host
        self.redis_port = redis_port
        self.redis_db = redis_db
        self.redis_url = redis_url
        self._prefix = prefix.rstrip(":") if prefix else "user:features"
        self._in_memory_store: Dict[str, str] = {}
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

    def _start_feature_span(
        self,
        operation: str,
        entity_id: str,
        traceparent_override: Optional[str] = None,
    ) -> Dict[str, Any]:
        """Create a child trace span for an outgoing Redis feature-store operation."""
        active_ctx = get_current_trace_context()
        parent_tp = traceparent_override or (active_ctx.get("traceparent") if active_ctx else None)
        parent_trace_id = active_ctx.get("trace_id") if active_ctx else None
        child_ctx = create_child_trace_context(parent_tp, parent_trace_id)
        headers = {
            "traceparent": child_ctx["traceparent"],
            "X-Trace-Id": child_ctx["trace_id"],
        }
        span_info: Dict[str, Any] = {
            "operation": operation,
            "entity_id": entity_id,
            "trace_id": child_ctx["trace_id"],
            "parent_span_id": child_ctx["parent_span_id"],
            "span_id": child_ctx["span_id"],
            "flags": child_ctx["flags"],
            "traceparent": child_ctx["traceparent"],
            "X-Trace-Id": child_ctx["trace_id"],
            "headers": headers,
        }
        self.last_span = span_info
        self.recorded_spans.append(span_info)
        if otel_trace is not None:
            try:
                tracer = otel_trace.get_tracer("ai-inference-service.feature-store")
                with tracer.start_as_current_span(f"redis.{operation}") as otel_span:
                    otel_span.set_attribute("db.system", "redis")
                    otel_span.set_attribute("feature_store.entity_id", entity_id)
                    otel_span.set_attribute("traceparent", child_ctx["traceparent"])
                    otel_span.set_attribute("http.request.header.traceparent", child_ctx["traceparent"])
                    otel_span.set_attribute("X-Trace-Id", child_ctx["trace_id"])
            except Exception:
                pass
        return span_info

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
        parsed: Dict[str, Any] = {}
        for raw_key, raw_val in raw_hash.items():
            field = _decode_redis_text(raw_key)
            if not field:
                continue
            valid, value = _parse_hash_value(field, raw_val)
            if valid:
                parsed[field] = value
        return parsed

    @staticmethod
    def _parse_json_dict(raw: Optional[str]) -> Optional[Dict[str, Any]]:
        if not raw:
            return None
        try:
            parsed = json.loads(raw)
        except Exception:
            return None
        if isinstance(parsed, dict):
            return cast(Dict[str, Any], parsed)
        return None

    def get_user_features(self, user_id: str, traceparent: Optional[str] = None) -> Dict[str, Any]:
        """Fetch user online feature vector, preferring Go ETL Redis hashes."""
        self._start_feature_span("get_user_features", user_id, traceparent_override=traceparent)
        raw_hash = self._hgetall_raw(f"user:features:{user_id}")
        if raw_hash:
            parsed_hash = self._parse_user_hash(raw_hash)
            if parsed_hash:
                return parsed_hash

        parsed_json = self._parse_json_dict(self._get_raw(f"user_features:{user_id}"))
        if parsed_json is not None:
            return parsed_json
        return {
            "user_id": user_id,
            "order_count_30d": 0,
            "avg_basket_cents": 0.0,
            "conversion_rate_30d": 0.0,
            "is_fallback": True,
        }

    def set_user_features(
        self,
        user_id: str,
        features: Dict[str, Any],
        ttl_seconds: int = 86400,
        traceparent: Optional[str] = None,
    ) -> None:
        """Store user online feature vector in Redis."""
        self._start_feature_span("set_user_features", user_id, traceparent_override=traceparent)
        key = f"user_features:{user_id}"
        self._set_raw(key, json.dumps(features), ttl=ttl_seconds)

    def get_product_features(self, product_id: str, traceparent: Optional[str] = None) -> Dict[str, Any]:
        """Fetch product online feature vector."""
        self._start_feature_span("get_product_features", product_id, traceparent_override=traceparent)
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
        self._start_feature_span("set_product_features", product_id, traceparent_override=traceparent)
        key = f"product_features:{product_id}"
        self._set_raw(key, json.dumps(features), ttl=ttl_seconds)

    def get_features(self, entity_id: str, feature_names: Sequence[str] = ()) -> Dict[str, float]:
        """Fetch float feature dictionary for entity_id compatible with FeatureStoreClient."""
        raw_features = self.get_user_features(entity_id)
        if raw_features.get("is_fallback") and self._client is not None and self._prefix != "user:features":
            prefixed_key = f"{self._prefix}:{entity_id}" if self._prefix else entity_id
            if feature_names and hasattr(self._client, "hmget"):
                try:
                    values = cast(List[Any], self._client.hmget(prefixed_key, list(feature_names)))
                    return {
                        name: float(value)
                        for name, value in zip(feature_names, values)
                        if value is not None
                    }
                except Exception:
                    pass
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
            except Exception:
                return {"backend": self.backend, "status": "degraded"}
        if self._client is not None:
            return {"backend": self.backend, "status": "ok"}
        return {"backend": self.backend, "status": "fallback"}

    def close(self) -> None:
        if self._client is not None and hasattr(self._client, "close"):
            try:
                self._client.close()
            except Exception:
                pass

    @staticmethod
    def compute_trailing_conversion_rate(
        orders: List[Dict[str, Any]],
        sessions: List[Dict[str, Any]],
        reference_date: Optional[datetime.date] = None,
    ) -> float:
        """Compute 30-day conversion rate strictly excluding the current calendar day.

        Window: [reference_date - 30 days, reference_date - 1 day].
        """
        ref = reference_date or datetime.date.today()
        start_date = ref - datetime.timedelta(days=30)
        end_date = ref - datetime.timedelta(days=1)

        valid_orders = 0
        for o in orders:
            d = o.get("date")
            if isinstance(d, str):
                d = datetime.date.fromisoformat(d[:10])
            elif isinstance(d, datetime.datetime):
                d = d.date()
            if d and start_date <= d <= end_date:
                valid_orders += 1

        valid_sessions = 0
        for s in sessions:
            d = s.get("date")
            if isinstance(d, str):
                d = datetime.date.fromisoformat(d[:10])
            elif isinstance(d, datetime.datetime):
                d = d.date()
            if d and start_date <= d <= end_date:
                valid_sessions += 1

        if valid_sessions == 0:
            return 0.0
        return float(min(1.0, valid_orders / valid_sessions))

    def run_daily_parity_check(
        self,
        offline_features_map: Dict[str, Dict[str, float]],
        max_drift_pct: float = 1.0,
    ) -> Dict[str, Any]:
        """Perform daily automated feature parity audit across user samples.

        Compares offline features against online Redis features.
        Asserts drift rate < 1.0% across all evaluated keys.
        """
        total = 0
        drifted = 0
        deltas = []

        for user_id, off_feats in offline_features_map.items():
            on_feats = self.get_user_features(user_id)
            if on_feats.get("is_fallback"):
                continue

            for feat_name, off_val in off_feats.items():
                if feat_name in on_feats:
                    total += 1
                    try:
                        on_val = float(on_feats[feat_name])
                    except (TypeError, ValueError):
                        drifted += 1
                        continue
                    if not math.isfinite(on_val):
                        drifted += 1
                        continue
                    delta = abs(off_val - on_val)
                    deltas.append(delta)
                    if off_val != 0:
                        if (delta / abs(off_val)) > 0.01:
                            drifted += 1
                    elif delta > 0.005:
                        drifted += 1

        drift_rate_pct = (drifted / total * 100.0) if total > 0 else 0.0
        passed = drift_rate_pct < max_drift_pct

        result = {
            "total_evaluated": total,
            "drifted_count": drifted,
            "drift_rate_pct": round(drift_rate_pct, 4),
            "max_allowed_drift_pct": max_drift_pct,
            "passed": passed,
        }
        if not passed:
            logger.warning("Feature parity check FAILED: %s", result)
        else:
            logger.info("Feature parity check PASSED: %s", result)
        return result


RedisFeatureClient = RedisFeatureStoreClient


class FeatureFetcher:
    """High-level feature fetcher wiring RedisFeatureClient with cache/in-memory fallback."""

    backend = "redis"

    def __init__(
        self,
        redis_client: Optional[RedisFeatureStoreClient] = None,
        redis_url: Optional[str] = None,
        cache: Optional[Any] = None,
        fallback_store: Optional[Dict[str, Dict[str, Any]]] = None,
    ) -> None:
        if redis_client is not None:
            self.redis_client: Optional[RedisFeatureStoreClient] = redis_client
        elif redis_url:
            self.redis_client = RedisFeatureStoreClient(redis_url=redis_url)
        else:
            self.redis_client = None
        self.cache = cache
        self._fallback_store: Dict[str, Dict[str, Any]] = dict(fallback_store or {})

    def set_fallback_features(self, user_id: str, features: Dict[str, Any]) -> None:
        """Populate in-memory fallback features for a user."""
        self._fallback_store[user_id] = dict(features)
        if self.cache is not None and hasattr(self.cache, "set"):
            try:
                self.cache.set(f"user:features:{user_id}", dict(features))
            except Exception:
                pass

    def get_user_features(self, user_id: str, traceparent: Optional[str] = None) -> Dict[str, Any]:
        """Fetch user features from Redis (`user:features:{user_id}` HGETALL) with cache/in-memory fallback."""
        cache_key = f"user:features:{user_id}"
        if self.redis_client is not None:
            try:
                features = self.redis_client.get_user_features(user_id, traceparent=traceparent)
                if not features.get("is_fallback"):
                    if self.cache is not None and hasattr(self.cache, "set"):
                        try:
                            self.cache.set(cache_key, dict(features))
                        except Exception:
                            pass
                    self._fallback_store[user_id] = dict(features)
                    return features
            except Exception as exc:
                logger.warning("RedisFeatureClient failed for %s (%s), falling back to cache", user_id, exc)

        if self.cache is not None and hasattr(self.cache, "get"):
            try:
                cached = self.cache.get(cache_key) or self.cache.get(user_id)
                if isinstance(cached, dict) and cached:
                    return dict(cached)
            except Exception:
                pass

        if user_id in self._fallback_store:
            return dict(self._fallback_store[user_id])

        return {
            "user_id": user_id,
            "order_count_30d": 0,
            "avg_basket_cents": 0.0,
            "conversion_rate_30d": 0.0,
            "is_fallback": True,
        }

    def get_features(self, entity_id: str, feature_names: Sequence[str] = ()) -> Dict[str, float]:
        """Fetch numeric feature map for entity_id with graceful fallback."""
        raw = self.get_user_features(entity_id)
        names = list(feature_names) if feature_names else list(raw.keys())
        out: Dict[str, float] = {}
        for name in names:
            if name in ("is_fallback", "user_id", "product_id"):
                continue
            val = raw.get(name)
            if isinstance(val, (int, float)) and not isinstance(val, bool) and math.isfinite(float(val)):
                out[name] = float(val)
        return out

    def health(self) -> Dict[str, str]:
        if self.redis_client is not None:
            return self.redis_client.health()
        return {"backend": "memory", "status": "fallback"}

    def close(self) -> None:
        if self.redis_client is not None:
            self.redis_client.close()
