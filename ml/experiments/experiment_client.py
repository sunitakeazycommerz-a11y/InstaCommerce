"""A/B Testing Experimentation Client & Consistent Hashing SDK.

Uses 32-bit MurmurHash3 consistent hashing identical to Java Guava's
`Hashing.murmur3_32_fixed()` to guarantee deterministic variant assignment
across devices, search queries, browsing sessions, and checkout screens.
"""

from __future__ import annotations

import datetime
import json
import logging
import uuid
from typing import Any, Callable, Dict, List, Optional

logger = logging.getLogger(__name__)


def murmur3_32(key: str, seed: int = 0) -> int:
    """Pure Python implementation of 32-bit MurmurHash3.

    Guarantees exact cross-language parity with Guava `Hashing.murmur3_32_fixed()`.
    """
    data = key.encode("utf-8")
    length = len(data)
    nblocks = length // 4
    h1 = seed & 0xFFFFFFFF
    c1 = 0xCC9E2D51
    c2 = 0x1B873593

    for i in range(nblocks):
        k1 = data[i * 4] | (data[i * 4 + 1] << 8) | (data[i * 4 + 2] << 16) | (data[i * 4 + 3] << 24)
        k1 = (k1 * c1) & 0xFFFFFFFF
        k1 = ((k1 << 15) | (k1 >> 17)) & 0xFFFFFFFF
        k1 = (k1 * c2) & 0xFFFFFFFF

        h1 ^= k1
        h1 = ((h1 << 13) | (h1 >> 19)) & 0xFFFFFFFF
        h1 = (h1 * 5 + 0xE6546B64) & 0xFFFFFFFF

    tail = data[nblocks * 4:]
    k1 = 0
    if len(tail) == 3:
        k1 ^= tail[2] << 16
    if len(tail) >= 2:
        k1 ^= tail[1] << 8
    if len(tail) >= 1:
        k1 ^= tail[0]
        k1 = (k1 * c1) & 0xFFFFFFFF
        k1 = ((k1 << 15) | (k1 >> 17)) & 0xFFFFFFFF
        k1 = (k1 * c2) & 0xFFFFFFFF
        h1 ^= k1

    h1 ^= length
    h1 ^= (h1 >> 16)
    h1 = (h1 * 0x85EBCA6B) & 0xFFFFFFFF
    h1 ^= (h1 >> 13)
    h1 = (h1 * 0xC2B2AE35) & 0xFFFFFFFF
    h1 ^= (h1 >> 16)
    return h1


def to_signed_32(val: int) -> int:
    """Convert unsigned 32-bit integer to signed 32-bit integer matching Java int."""
    if val >= 0x80000000:
        return val - 0x100000000
    return val


def compute_bucket_percent(assignment_key: str, experiment_salt: str) -> int:
    """Compute deterministic user experiment bucket [0..99] using MurmurHash3.

    Formula: (MurmurHash3(assignment_key + ":" + experiment_salt) & 0x7FFFFFFF) % 100
    """
    input_str = f"{assignment_key}:{experiment_salt}"
    h_unsigned = murmur3_32(input_str)
    h_signed = to_signed_32(h_unsigned)
    return (h_signed & 0x7FFFFFFF) % 100


class ExperimentClient:
    """Client for deterministic experiment variant evaluation and exposure auditing."""

    def __init__(
        self,
        event_emitter: Optional[Callable[[str, Dict[str, Any]], None]] = None,
        kafka_topic: str = "experiment.exposure.events",
    ) -> None:
        self._event_emitter = event_emitter
        self._kafka_topic = kafka_topic
        self._exposure_log: List[Dict[str, Any]] = []

    def evaluate(
        self,
        experiment_key: str,
        user_id: str,
        variants: List[Dict[str, Any]],
        salt: Optional[str] = None,
        context: Optional[Dict[str, Any]] = None,
    ) -> Dict[str, Any]:
        """Deterministically assign user to an experiment variant.

        Args:
            experiment_key: Canonical experiment identifier (e.g. "dynamic_pricing_v2").
            user_id: Customer UUID or assignment unit.
            variants: List of variants with 'name' and 'weight' (e.g. [{"name": "control", "weight": 50}, ...]).
            salt: Optional experiment salt for hashing isolation. Defaults to experiment_key.
            context: Additional context logged with exposure.

        Returns:
            Dict containing assigned variant name, bucket, and payload.
        """
        if not variants:
            return {"variant": "control", "bucket": 0, "payload": {}}

        experiment_salt = salt or experiment_key
        bucket = compute_bucket_percent(user_id, experiment_salt)

        total_weight = sum(int(v.get("weight", 0)) for v in variants)
        if total_weight <= 0:
            assigned = variants[0]
        else:
            scaled_bucket = (bucket * total_weight) // 100
            cumulative = 0
            assigned = variants[-1]
            for v in variants:
                cumulative += int(v.get("weight", 0))
                if scaled_bucket < cumulative:
                    assigned = v
                    break

        exposure_record = {
            "exposure_id": str(uuid.uuid4()),
            "timestamp": datetime.datetime.now(datetime.timezone.utc).isoformat(),
            "experiment_key": experiment_key,
            "variant_name": assigned["name"],
            "user_id": user_id,
            "bucket": bucket,
            "context": context or {},
        }
        self._exposure_log.append(exposure_record)

        if self._event_emitter:
            try:
                self._event_emitter(self._kafka_topic, exposure_record)
            except Exception as e:
                logger.warning("Failed to emit experiment exposure event: %s", e)

        return {
            "experiment_key": experiment_key,
            "variant": assigned["name"],
            "bucket": bucket,
            "payload": assigned.get("payload", {}),
            "exposure_id": exposure_record["exposure_id"],
        }

    def log_search_interaction(
        self,
        user_id: str,
        experiment_key: str,
        variant_name: str,
        query: str,
        clicked_product_id: Optional[str] = None,
    ) -> Dict[str, Any]:
        """Log search query interaction tagged with experiment variant."""
        record = {
            "event_type": "search_experiment_interaction",
            "timestamp": datetime.datetime.now(datetime.timezone.utc).isoformat(),
            "user_id": user_id,
            "experiment_key": experiment_key,
            "variant_name": variant_name,
            "query": query,
            "clicked_product_id": clicked_product_id,
        }
        self._exposure_log.append(record)
        return record

    def log_checkout_conversion(
        self,
        user_id: str,
        experiment_key: str,
        variant_name: str,
        order_id: str,
        total_cents: int,
    ) -> Dict[str, Any]:
        """Log checkout conversion event tagged with experiment variant."""
        record = {
            "event_type": "checkout_experiment_conversion",
            "timestamp": datetime.datetime.now(datetime.timezone.utc).isoformat(),
            "user_id": user_id,
            "experiment_key": experiment_key,
            "variant_name": variant_name,
            "order_id": order_id,
            "total_cents": total_cents,
        }
        self._exposure_log.append(record)
        return record

    def get_exposures(self) -> List[Dict[str, Any]]:
        return list(self._exposure_log)

    def clear_exposures(self) -> None:
        self._exposure_log.clear()
