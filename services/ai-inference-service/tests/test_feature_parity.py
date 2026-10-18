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
