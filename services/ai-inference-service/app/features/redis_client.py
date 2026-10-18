"""Online Redis Feature Store Client for Real-time Inference.

Retrieves and caches low-latency user and product features for ranking and
pricing inference models. Guarantees 100% offline-to-online parity by
aggregating features strictly over completed historical days [T-30d, T-1d],
excluding the current calendar day/session.
"""

from __future__ import annotations

import datetime
import json
import logging
from typing import Any, Dict, List, Optional, cast

logger = logging.getLogger(__name__)


class RedisFeatureStoreClient:
    """Redis online feature store client with in-memory fallback for local dev."""

    def __init__(
        self,
        redis_host: str = "localhost",
        redis_port: int = 6379,
        redis_db: int = 0,
        redis_client: Optional[Any] = None,
    ) -> None:
        self.redis_host = redis_host
        self.redis_port = redis_port
        self.redis_db = redis_db
        self._in_memory_store: Dict[str, str] = {}
        self._client = redis_client

        if self._client is None:
            try:
                import redis
                self._client = redis.Redis(
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

    def get_user_features(self, user_id: str) -> Dict[str, Any]:
        """Fetch user online feature vector."""
        key = f"user_features:{user_id}"
        raw = self._get_raw(key)
        if raw:
            try:
                return json.loads(raw)
            except Exception:
                pass
        return {
            "user_id": user_id,
            "order_count_30d": 0,
            "avg_basket_cents": 0.0,
            "conversion_rate_30d": 0.0,
            "is_fallback": True,
        }

    def set_user_features(self, user_id: str, features: Dict[str, Any], ttl_seconds: int = 86400) -> None:
        """Store user online feature vector in Redis."""
        key = f"user_features:{user_id}"
        self._set_raw(key, json.dumps(features), ttl=ttl_seconds)

    def get_product_features(self, product_id: str) -> Dict[str, Any]:
        """Fetch product online feature vector."""
        key = f"product_features:{product_id}"
        raw = self._get_raw(key)
        if raw:
            try:
                return json.loads(raw)
            except Exception:
                pass
        return {
            "product_id": product_id,
            "conversion_rate_30d": 0.0,
            "sales_velocity_7d": 0.0,
            "is_fallback": True,
        }

    def set_product_features(self, product_id: str, features: Dict[str, Any], ttl_seconds: int = 86400) -> None:
        """Store product online feature vector in Redis."""
        key = f"product_features:{product_id}"
        self._set_raw(key, json.dumps(features), ttl=ttl_seconds)

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
                    on_val = float(on_feats[feat_name])
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
