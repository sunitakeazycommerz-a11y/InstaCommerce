"""Unit tests for the data-platform Great Expectations quality check runner."""
from unittest.mock import MagicMock

import pytest

import run_quality_checks
from run_quality_checks import (
    build_bigquery_context,
    discover_suites,
    load_suite_config,
    run_suite_bigquery,
    run_suite_on_fixture,
    validate_expectation,
    FIXTURES_DIR,
)


def test_discover_suites():
    """Verify all domain expectation suites are discovered."""
    suites = discover_suites()
    suite_names = [s.name for s in suites]
    assert "orders_suite.yaml" in suite_names
    assert "payments_suite.yaml" in suite_names
    assert "inventory_suite.yaml" in suite_names
    assert "users_suite.yaml" in suite_names
    assert "daily_sales_suite.yaml" in suite_names


def test_discover_single_suite():
    """Verify filtering by single suite works."""
    suites = discover_suites("orders")
    assert len(suites) == 1
    assert suites[0].name == "orders_suite.yaml"


def test_load_fixtures_and_validate_all_suites():
    """Verify that all default fixtures pass their respective expectation suites."""
    suites = discover_suites()
    assert len(suites) == 5

    for suite_path in suites:
        config = load_suite_config(suite_path)
        summary = run_suite_on_fixture(config, FIXTURES_DIR)
        assert summary["success"] is True, f"Suite {suite_path.stem} failed: {summary.get('failures')}"
        assert summary["unsuccessful_expectations"] == 0
        assert summary["successful_expectations"] > 0


def test_validate_expectation_null_check():
    """Verify expect_column_values_to_not_be_null catches nulls."""
    exp = {
        "expectation_type": "expect_column_values_to_not_be_null",
        "kwargs": {"column": "user_id"},
    }
    records = [{"user_id": "usr-1"}, {"user_id": None}]
    passed, detail = validate_expectation(exp, records)
    assert passed is False
    assert detail is not None
    assert detail["column"] == "user_id"
    assert detail["unexpected_count"] == 1


def test_validate_expectation_unique_check():
    """Verify expect_column_values_to_be_unique catches duplicates."""
    exp = {
        "expectation_type": "expect_column_values_to_be_unique",
        "kwargs": {"column": "order_id"},
    }
    records = [{"order_id": "ord-1"}, {"order_id": "ord-2"}, {"order_id": "ord-1"}]
    passed, detail = validate_expectation(exp, records)
    assert passed is False
    assert detail is not None
    assert "ord-1" in detail["unexpected_values"]


def test_validate_expectation_value_set():
    """Verify expect_column_values_to_be_in_set catches unauthorized enums."""
    exp = {
        "expectation_type": "expect_column_values_to_be_in_set",
        "kwargs": {"column": "status", "value_set": ["PLACED", "CANCELLED"]},
    }
    records = [{"status": "PLACED"}, {"status": "INVALID_STATUS"}]
    passed, detail = validate_expectation(exp, records)
    assert passed is False
    assert detail is not None
    assert "INVALID_STATUS" in detail["unexpected_values"]


def test_validate_expectation_between():
    """Verify expect_column_values_to_be_between catches out-of-range numbers."""
    exp = {
        "expectation_type": "expect_column_values_to_be_between",
        "kwargs": {"column": "amount", "min_value": 10, "max_value": 100},
    }
    records = [{"amount": 50}, {"amount": 5}, {"amount": 150}]
    passed, detail = validate_expectation(exp, records)
    assert passed is False
    assert detail is not None
    assert len(detail["unexpected_samples"]) == 2


def test_validate_expectation_regex():
    """Verify expect_column_values_to_match_regex catches malformed strings."""
    exp = {
        "expectation_type": "expect_column_values_to_match_regex",
        "kwargs": {"column": "email", "regex": r"^[a-zA-Z0-9_.+-]+@[a-zA-Z0-9-]+\.[a-zA-Z0-9-.]+$"},
    }
    records = [
        {"email": "valid@instacommerce.test"},
        {"email": "invalid-email-without-at-sign"},
    ]
    passed, detail = validate_expectation(exp, records)
    assert passed is False
    assert detail is not None
    assert "invalid-email-without-at-sign" in detail["unexpected_values"]


@pytest.mark.parametrize(
    "bad_project,bad_dataset",
    [
        ("instacommerce-prod`; DROP TABLE orders--", "analytics"),
        ("instacommerce-prod", "analytics; DELETE FROM users"),
        ("", "analytics"),
        ("..", "analytics"),
        ("instacommerce-prod", "analytics..orders"),
        ("bad project", "analytics"),
    ],
)
def test_build_bigquery_context_rejects_invalid_identifiers(bad_project, bad_dataset):
    """Verify build_bigquery_context rejects unsafe BigQuery project/dataset identifiers."""
    with pytest.raises(ValueError, match="Invalid BigQuery"):
        build_bigquery_context(bad_project, bad_dataset)


def test_run_suite_bigquery_rejects_invalid_project_and_table(monkeypatch):
    """Verify run_suite_bigquery validates project and table before SQL interpolation."""
    suite_config = {"name": "orders_validation", "expectations": []}
    mock_context = MagicMock()

    with pytest.raises(ValueError, match="Invalid BigQuery"):
        run_suite_bigquery(mock_context, suite_config, "proj`; DROP TABLE orders--")

    monkeypatch.setitem(
        run_quality_checks.SUITE_TABLE_MAP,
        "orders_validation",
        "analytics.orders`; DROP TABLE users--",
    )
    with pytest.raises(ValueError, match="Invalid BigQuery"):
        run_suite_bigquery(mock_context, suite_config, "instacommerce-prod")


def test_validate_expectation_pair_type_mean_gap_and_fail_closed():
    ok_pair, _ = validate_expectation(
        {
            "expectation_type": "expect_column_pair_values_a_to_be_greater_than_or_equal_to_b",
            "kwargs": {"column_A": "order_count", "column_B": "on_time_orders", "or_equal": True},
        },
        [{"order_count": 10, "on_time_orders": 10}, {"order_count": 5, "on_time_orders": 3}],
    )
    assert ok_pair is True

    bad_pair, pair_detail = validate_expectation(
        {
            "expectation_type": "expect_column_pair_values_a_to_be_greater_than_or_equal_to_b",
            "kwargs": {"column_A": "order_count", "column_B": "on_time_orders"},
        },
        [{"order_count": 4, "on_time_orders": 9}],
    )
    assert bad_pair is False
    assert pair_detail is not None and pair_detail["column_A"] == "order_count"

    bad_type, _ = validate_expectation(
        {
            "expectation_type": "expect_column_values_to_be_of_type",
            "kwargs": {"column": "total_cents", "type_": "integer"},
        },
        [{"total_cents": True}],
    )
    assert bad_type is False

    bad_mean, _ = validate_expectation(
        {
            "expectation_type": "expect_column_mean_to_be_between",
            "kwargs": {"column": "latency_ms", "min_value": 10, "max_value": 200},
        },
        [{"latency_ms": 500}, {"latency_ms": 600}],
    )
    assert bad_mean is False

    bad_gap, _ = validate_expectation(
        {
            "expectation_type": "expect_no_event_time_gaps",
            "kwargs": {"column": "event_time", "max_gap_minutes": 15},
        },
        [
            {"event_time": "2026-10-18T10:00:00Z"},
            {"event_time": "2026-10-18T10:45:00Z"},
        ],
    )
    assert bad_gap is False

    unsupported_ok, unsupported_detail = validate_expectation(
        {"expectation_type": "expect_nonexistent_custom_rule", "kwargs": {"column": "id"}},
        [{"id": "a"}],
    )
    assert unsupported_ok is False
    assert unsupported_detail is not None and "Unsupported" in unsupported_detail["error"]


@pytest.mark.parametrize(
    "bad_val",
    [True, False, float("nan"), float("inf"), float("-inf")],
)
def test_between_and_mean_reject_bool_and_non_finite(bad_val):
    between_ok, _ = validate_expectation(
        {
            "expectation_type": "expect_column_values_to_be_between",
            "kwargs": {"column": "amount", "min_value": 0, "max_value": 100},
        },
        [{"amount": 10}, {"amount": bad_val}],
    )
    assert between_ok is False

    mean_ok, _ = validate_expectation(
        {
            "expectation_type": "expect_column_mean_to_be_between",
            "kwargs": {"column": "amount", "min_value": 0, "max_value": 100},
        },
        [{"amount": 10}, {"amount": bad_val}],
    )
    assert mean_ok is False


def test_event_time_gaps_normalizes_timezones_and_fails_on_empty_records():
    empty_ok, empty_detail = validate_expectation(
        {
            "expectation_type": "expect_no_event_time_gaps",
            "kwargs": {"column": "event_time", "max_gap_minutes": 15},
        },
        [],
    )
    assert empty_ok is False
    assert empty_detail is not None and "No records" in empty_detail["error"]

    explicit_empty_ok, _ = validate_expectation(
        {
            "expectation_type": "expect_no_event_time_gaps",
            "kwargs": {"column": "event_time", "max_gap_minutes": 15, "allow_empty": True},
        },
        [],
    )
    assert explicit_empty_ok is True

    mixed_tz_ok, mixed_detail = validate_expectation(
        {
            "expectation_type": "expect_no_event_time_gaps",
            "kwargs": {"column": "event_time", "max_gap_minutes": 15},
        },
        [
            {"event_time": "2026-10-18T10:00:00Z"},
            {"event_time": "2026-10-18T10:05:00"},
            {"event_time": "2026-10-18T15:40:00+05:30"},
        ],
    )
    assert mixed_tz_ok is True, mixed_detail

    mixed_tz_gap, _ = validate_expectation(
        {
            "expectation_type": "expect_no_event_time_gaps",
            "kwargs": {"column": "event_time", "max_gap_minutes": 15},
        },
        [
            {"event_time": "2026-10-18T10:00:00Z"},
            {"event_time": "2026-10-18T10:40:00"},
        ],
    )
    assert mixed_tz_gap is False


def test_main_does_not_silently_downgrade_to_fixtures_on_bigquery_failure(monkeypatch):
    monkeypatch.setattr("sys.argv", ["run_quality_checks.py", "--suite", "orders"])
    monkeypatch.delenv("GOOGLE_APPLICATION_CREDENTIALS", raising=False)
    monkeypatch.delenv("CI_BIGQUERY_ENABLED", raising=False)
    monkeypatch.setattr(
        run_quality_checks,
        "build_bigquery_context",
        MagicMock(side_effect=RuntimeError("BigQuery unavailable")),
    )
    with pytest.raises(RuntimeError, match="BigQuery unavailable"):
        run_quality_checks.main()


def test_main_runs_fixtures_only_when_explicitly_requested(monkeypatch, tmp_path):
    out_file = tmp_path / "summary.json"
    monkeypatch.setattr(
        "sys.argv",
        ["run_quality_checks.py", "--fixtures", "--suite", "orders", "--json-output", str(out_file)],
    )
    build_mock = MagicMock()
    monkeypatch.setattr(run_quality_checks, "build_bigquery_context", build_mock)
    run_quality_checks.main()
    build_mock.assert_not_called()
    assert out_file.exists()
