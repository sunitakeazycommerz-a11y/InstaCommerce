"""Unit tests for the data-platform Great Expectations quality check runner."""
import os
import sys

quality_dir = os.path.abspath(os.path.join(os.path.dirname(__file__), ".."))
if quality_dir not in sys.path:
    sys.path.insert(0, quality_dir)

from test_quality_checks import (  # noqa: E402
    test_build_bigquery_context_rejects_invalid_identifiers,
    test_discover_single_suite,
    test_discover_suites,
    test_load_fixtures_and_validate_all_suites,
    test_run_suite_bigquery_rejects_invalid_project_and_table,
    test_validate_expectation_between,
    test_validate_expectation_null_check,
    test_validate_expectation_regex,
    test_validate_expectation_unique_check,
    test_validate_expectation_value_set,
)

__all__ = [
    "test_build_bigquery_context_rejects_invalid_identifiers",
    "test_discover_single_suite",
    "test_discover_suites",
    "test_load_fixtures_and_validate_all_suites",
    "test_run_suite_bigquery_rejects_invalid_project_and_table",
    "test_validate_expectation_between",
    "test_validate_expectation_null_check",
    "test_validate_expectation_regex",
    "test_validate_expectation_unique_check",
    "test_validate_expectation_value_set",
]
