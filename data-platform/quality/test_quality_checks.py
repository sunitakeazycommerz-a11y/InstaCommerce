"""Unit tests for the data-platform Great Expectations quality check runner."""
import json
import tempfile
from pathlib import Path

import pytest
import yaml

from run_quality_checks import (
    discover_suites,
    load_fixture_data,
    load_suite_config,
    run_suite_on_fixture,
    validate_expectation,
    FIXTURES_DIR,
    SUITES_DIR,
)


def test_discover_suites():
    """Verify all 4 domain expectation suites are discovered."""
    suites = discover_suites()
    suite_names = [s.name for s in suites]
    assert "orders_suite.yaml" in suite_names
    assert "payments_suite.yaml" in suite_names
    assert "inventory_suite.yaml" in suite_names
    assert "users_suite.yaml" in suite_names


def test_discover_single_suite():
    """Verify filtering by single suite works."""
    suites = discover_suites("orders")
    assert len(suites) == 1
    assert suites[0].name == "orders_suite.yaml"


def test_load_fixtures_and_validate_all_suites():
    """Verify that all default fixtures pass their respective expectation suites."""
    suites = discover_suites()
    assert len(suites) == 4

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
