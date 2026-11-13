"""Unit tests for offline-to-online feature store parity and conversion rate windowing."""

import datetime
from app.features.redis_client import RedisFeatureStoreClient
from ml.features.feature_definitions import (
    WINDOW_30D_EXCLUDING_TODAY,
    compute_user_conversion_rate_30d,
)


def test_window_excludes_current_calendar_day():
    today = datetime.date(2026, 10, 18)
    start_d, end_d = WINDOW_30D_EXCLUDING_TODAY.get_date_bounds(today)

    assert end_d == datetime.date(2026, 10, 17)
    assert start_d == datetime.date(2026, 9, 18)
    assert (end_d - start_d).days == 29  # 30 days inclusive


def test_conversion_rate_calculation_excludes_today_purchases():
    ref = datetime.date(2026, 10, 18)

    # 1 historical order in window, 1 same-day order (should be excluded)
    orders = [
        {"date": "2026-10-10", "order_id": "ord-1"},
        {"date": "2026-10-18", "order_id": "ord-2"},  # today
    ]
    sessions = [
        {"date": "2026-10-05", "session_id": "sess-1"},
        {"date": "2026-10-10", "session_id": "sess-2"},
        {"date": "2026-10-18", "session_id": "sess-3"},  # today
    ]

    rate = compute_user_conversion_rate_30d(orders, sessions, reference_date=ref)
    # Only sess-1 and sess-2 are in historical window (2 sessions), and ord-1 is in window (1 order)
    # rate = 1 / 2 = 0.5
    assert rate == 0.5


def test_redis_feature_store_parity_check():
    client = RedisFeatureStoreClient()

    # Pre-populate 100 users in Redis
    offline_map = {}
    for i in range(100):
        uid = f"user_{i}"
        rate = round(0.15 + (i * 0.001), 4)
        client.set_user_features(uid, {
            "user_id": uid,
            "conversion_rate_30d": rate,
            "order_count_30d": 5,
        })
        offline_map[uid] = {
            "conversion_rate_30d": rate,
            "order_count_30d": 5.0,
        }

    parity = client.run_daily_parity_check(offline_map, max_drift_pct=1.0)
    assert parity["passed"] is True
    assert parity["drift_rate_pct"] == 0.0
    assert parity["total_evaluated"] == 200


class _FakeRedisClient:
    def __init__(self) -> None:
        self.hashes: dict[str, dict[object, object]] = {}
        self.strings: dict[str, object] = {}
        self.calls: list[tuple[str, str]] = []
        self.hgetall_error: Exception | None = None
        self.get_error: Exception | None = None

    def hgetall(self, key: str) -> dict[object, object]:
        self.calls.append(("hgetall", key))
        if self.hgetall_error is not None:
            raise self.hgetall_error
        return dict(self.hashes.get(key, {}))

    def get(self, key: str) -> object:
        self.calls.append(("get", key))
        if self.get_error is not None:
            raise self.get_error
        return self.strings.get(key)

    def set(self, key: str, value: str, ex: int | None = None) -> None:
        self.calls.append(("set", key))
        self.strings[key] = value


def test_get_user_features_reads_go_hash_format_first():
    fake = _FakeRedisClient()
    fake.hashes["user:features:user-456"] = {
        "clv": "12500.50",
        "category_affinity": '{"dairy":0.85,"produce":0.92}',
        "last_purchase_days": "2",
        "order_count_30d": "7",
        "conversion_rate_30d": "0.25",
        "segment": "HIGH_VALUE",
        "updated_at": "2026-11-13T10:00:00Z",
        "job_id": "12345",
    }
    fake.strings["user_features:user-456"] = '{"clv": 1.0, "segment": "LEGACY"}'

    client = RedisFeatureStoreClient(redis_client=fake)
    features = client.get_user_features("user-456")

    assert fake.calls == [("hgetall", "user:features:user-456")]
    assert features["clv"] == 12500.5
    assert isinstance(features["clv"], float)
    assert features["conversion_rate_30d"] == 0.25
    assert isinstance(features["conversion_rate_30d"], float)
    assert features["last_purchase_days"] == 2
    assert isinstance(features["last_purchase_days"], int)
    assert features["order_count_30d"] == 7
    assert isinstance(features["order_count_30d"], int)
    assert features["category_affinity"] == {"dairy": 0.85, "produce": 0.92}
    assert features["segment"] == "HIGH_VALUE"
    assert features["updated_at"] == "2026-11-13T10:00:00Z"
    assert features["job_id"] == "12345"
    assert isinstance(features["job_id"], str)


def test_get_user_features_decodes_bytes_hash_entries_and_passes_parity():
    fake = _FakeRedisClient()
    fake.hashes["user:features:user-bytes"] = {
        b"clv": b"850.00",
        b"order_count_30d": b"4",
        b"conversion_rate_30d": b"0.125",
        b"segment": b"GOLD",
        b"updated_at": b"2026-11-13T10:00:00Z",
        b"job_id": b"job-sync-9",
    }

    client = RedisFeatureStoreClient(redis_client=fake)
    features = client.get_user_features("user-bytes")
    assert features["clv"] == 850.0
    assert isinstance(features["clv"], float)
    assert features["order_count_30d"] == 4
    assert isinstance(features["order_count_30d"], int)
    assert features["segment"] == "GOLD"

    parity = client.run_daily_parity_check(
        {"user-bytes": {"clv": 850.0, "order_count_30d": 4.0, "conversion_rate_30d": 0.125}}
    )
    assert parity["passed"] is True
    assert parity["total_evaluated"] == 3
    assert parity["drifted_count"] == 0


def test_get_user_features_falls_back_to_legacy_json_when_hash_missing_or_errors():
    fake = _FakeRedisClient()
    fake.strings["user_features:user-legacy"] = (
        '{"user_id": "user-legacy", "order_count_30d": 3, "avg_basket_cents": 1950.5, "conversion_rate_30d": 0.2}'
    )

    client = RedisFeatureStoreClient(redis_client=fake)
    features = client.get_user_features("user-legacy")

    assert fake.calls == [
        ("hgetall", "user:features:user-legacy"),
        ("get", "user_features:user-legacy"),
    ]
    assert features["order_count_30d"] == 3
    assert features["avg_basket_cents"] == 1950.5

    fake.calls.clear()
    fake.hgetall_error = RuntimeError("WRONGTYPE")
    features_after_err = client.get_user_features("user-legacy")
    assert fake.calls == [
        ("hgetall", "user:features:user-legacy"),
        ("get", "user_features:user-legacy"),
    ]
    assert features_after_err["order_count_30d"] == 3


def test_get_user_features_missing_user_returns_fallback():
    fake = _FakeRedisClient()
    client = RedisFeatureStoreClient(redis_client=fake)

    features = client.get_user_features("missing-user")

    assert features == {
        "user_id": "missing-user",
        "order_count_30d": 0,
        "avg_basket_cents": 0.0,
        "conversion_rate_30d": 0.0,
        "is_fallback": True,
    }


def test_get_user_features_handles_corrupt_entries_gracefully():
    fake = _FakeRedisClient()
    fake.strings["user_features:corrupt-json"] = "{not valid json"
    fake.strings["user_features:non-dict-json"] = "[1, 2, 3]"

    client = RedisFeatureStoreClient(redis_client=fake)

    assert client.get_user_features("corrupt-json")["is_fallback"] is True
    assert client.get_user_features("non-dict-json")["is_fallback"] is True

    fake.hashes["user:features:corrupt-hash"] = {
        b"\xff\xfe": b"bad-key",
        "bad_bytes": b"\xff\xfe",
        "clv": "nan",
        "category_affinity": "{broken",
        "order_count_30d": "not-an-int",
        "segment": "SILVER",
    }
    parsed = client.get_user_features("corrupt-hash")
    assert parsed["clv"] == "nan"
    assert parsed["category_affinity"] == "{broken"
    assert parsed["order_count_30d"] == "not-an-int"
    assert parsed["segment"] == "SILVER"
    assert "bad_bytes" not in parsed

    parity = client.run_daily_parity_check(
        {"corrupt-hash": {"order_count_30d": 5.0}},
        max_drift_pct=1.0,
    )
    assert parity["total_evaluated"] == 1
    assert parity["drifted_count"] == 1
    assert parity["passed"] is False

    fake.hashes["user:features:all-corrupt-bytes"] = {b"\xff": b"val"}
    assert client.get_user_features("all-corrupt-bytes")["is_fallback"] is True
