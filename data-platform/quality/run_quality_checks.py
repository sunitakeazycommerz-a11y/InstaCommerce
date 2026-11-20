"""Run data quality checks for all domains.

Loads Great Expectations suite definitions from YAML, executes validations
against BigQuery tables or local test fixtures, and reports results. Designed
to run as a CI step, Airflow task, or standalone CLI.

Usage:
    python run_quality_checks.py                     # Run all suites against BigQuery
    python run_quality_checks.py --fixtures          # Run validation against synthetic test fixtures
    python run_quality_checks.py --suite orders      # Run a single suite
    python run_quality_checks.py --project my-proj   # Override GCP project
"""
import argparse
import json
import logging
import os
import sys
from pathlib import Path
from typing import Any, Dict, List, Optional

import yaml

_AIRFLOW_DIR = str(Path(__file__).resolve().parents[1] / "airflow")
if _AIRFLOW_DIR not in sys.path:
    sys.path.insert(0, _AIRFLOW_DIR)

from plugins.sql_validation import validate_bq_identifier

try:
    from .validators import validate_expectation
except ImportError:
    from validators import validate_expectation

try:
    import great_expectations as gx
    from great_expectations.core.batch import RuntimeBatchRequest
    HAS_GX = True
except ImportError:
    gx = None
    RuntimeBatchRequest = None
    HAS_GX = False

try:
    import pandas as pd
    HAS_PANDAS = True
except ImportError:
    pd = None
    HAS_PANDAS = False

logger = logging.getLogger(__name__)

SUITES_DIR = Path(__file__).parent / "expectations"
FIXTURES_DIR = Path(__file__).parent / "fixtures"

SUITE_TABLE_MAP = {
    "orders_validation": "analytics.orders",
    "payments_validation": "analytics.payments",
    "inventory_validation": "analytics.inventory",
    "users_validation": "analytics.users",
    "daily_sales_validation": "marts.mart_daily_sales",
}

SUITE_FIXTURE_MAP = {
    "orders_validation": "orders.json",
    "payments_validation": "payments.json",
    "inventory_validation": "inventory.json",
    "users_validation": "users.json",
    "daily_sales_validation": "daily_sales.json",
}


def load_suite_config(suite_path: Path) -> dict:
    """Load and parse a YAML expectation suite definition."""
    with open(suite_path, "r", encoding="utf-8") as f:
        config = yaml.safe_load(f)
    if not config or "expectation_suite" not in config:
        raise ValueError(f"Invalid suite file: {suite_path}")
    return config["expectation_suite"]


def discover_suites(suite_filter: Optional[str] = None) -> List[Path]:
    """Discover suite YAML files in the expectations directory."""
    pattern = f"{suite_filter}_suite.yaml" if suite_filter else "*_suite.yaml"
    suites = sorted(SUITES_DIR.glob(pattern))
    if not suites:
        logger.warning("No suite files found matching pattern: %s", pattern)
    return suites


def load_fixture_data(fixtures_dir: Path, suite_name: str) -> List[Dict[str, Any]]:
    """Load fixture JSON file corresponding to a suite."""
    filename = SUITE_FIXTURE_MAP.get(suite_name)
    if not filename:
        raise ValueError(f"No fixture mapping defined for suite '{suite_name}'")
    fixture_path = fixtures_dir / filename
    if not fixture_path.exists():
        raise FileNotFoundError(f"Fixture file not found: {fixture_path}")
    with open(fixture_path, "r", encoding="utf-8") as f:
        data = json.load(f)
    if not isinstance(data, list):
        raise ValueError(f"Fixture data in {fixture_path} must be a JSON array of objects")
    return data


def run_suite_on_fixture(
    suite_config: Dict[str, Any],
    fixtures_dir: Path,
    strict_row_count: bool = False,
) -> Dict[str, Any]:
    """Execute expectation suite directly against test fixture data."""
    suite_name = suite_config["name"]
    table = SUITE_TABLE_MAP.get(suite_name, "unknown")
    records = load_fixture_data(fixtures_dir, suite_name)
    expectations = suite_config.get("expectations", [])

    failures: List[Dict[str, Any]] = []
    evaluated = 0

    for exp in expectations:
        evaluated += 1
        passed, fail_detail = validate_expectation(exp, records, strict_row_count=strict_row_count)
        if not passed and fail_detail:
            failures.append(fail_detail)

    success = len(failures) == 0
    summary = {
        "suite": suite_name,
        "table": table,
        "fixture": SUITE_FIXTURE_MAP.get(suite_name),
        "success": success,
        "evaluated_expectations": evaluated,
        "successful_expectations": evaluated - len(failures),
        "unsuccessful_expectations": len(failures),
        "failures": failures,
    }

    log_fn = logger.info if success else logger.error
    log_fn(
        "Suite %s [FIXTURE]: %s (%d/%d expectations passed)",
        suite_name,
        "PASSED" if success else "FAILED",
        summary["successful_expectations"],
        summary["evaluated_expectations"],
    )
    if not success:
        for f in failures:
            logger.error("  -> Failure: %s", f.get("error"))

    return summary


def build_bigquery_context(project: str, dataset: str) -> Any:
    """Build a Great Expectations DataContext with a BigQuery datasource."""
    project = validate_bq_identifier(project, "project")
    dataset = validate_bq_identifier(dataset, "dataset")
    if gx is None or RuntimeBatchRequest is None:
        raise RuntimeError("great_expectations is not installed. Install via pip install great-expectations")

    context = gx.get_context()
    datasource_config = {
        "name": "bigquery_datasource",
        "class_name": "Datasource",
        "module_name": "great_expectations.datasource",
        "execution_engine": {
            "class_name": "SqlAlchemyExecutionEngine",
            "module_name": "great_expectations.execution_engine",
            "connection_string": f"bigquery://{project}/{dataset}",
        },
        "data_connectors": {
            "runtime_connector": {
                "class_name": "RuntimeDataConnector",
                "module_name": "great_expectations.datasource.data_connector",
                "batch_identifiers": ["run_id"],
            }
        },
    }

    try:
        context.add_datasource(**datasource_config)
    except getattr(gx.exceptions, "DatasourceError", Exception):
        logger.info("Datasource already configured, reusing existing.")

    return context


def run_suite_bigquery(
    context: Any,
    suite_config: Dict[str, Any],
    project: str,
) -> Dict[str, Any]:
    """Run a single expectation suite against BigQuery table and return results summary."""
    suite_name = suite_config["name"]
    table = SUITE_TABLE_MAP.get(suite_name)
    if not table:
        raise ValueError(f"No table mapping for suite: {suite_name}")
    project = validate_bq_identifier(project, "project")
    table = validate_bq_identifier(table, "table")
    if gx is None or RuntimeBatchRequest is None:
        raise RuntimeError("great_expectations is not installed. Install via pip install great-expectations")

    try:
        suite = context.get_expectation_suite(suite_name)
    except getattr(gx.exceptions, "DataContextError", Exception):
        suite = context.add_expectation_suite(suite_name)

    for exp in suite_config.get("expectations", []):
        suite.add_expectation(
            gx.core.ExpectationConfiguration(
                expectation_type=exp["expectation_type"],
                kwargs=exp.get("kwargs", {}),
            )
        )
    context.save_expectation_suite(suite)

    batch_request = RuntimeBatchRequest(
        datasource_name="bigquery_datasource",
        data_connector_name="runtime_connector",
        data_asset_name=suite_name,
        runtime_parameters={"query": f"SELECT * FROM `{project}.{table}`"},
        batch_identifiers={"run_id": f"quality_check_{suite_name}"},
    )

    checkpoint_name = f"checkpoint_{suite_name}"
    checkpoint_config = {
        "name": checkpoint_name,
        "config_version": 1,
        "class_name": "SimpleCheckpoint",
        "validations": [
            {
                "batch_request": batch_request,
                "expectation_suite_name": suite_name,
            }
        ],
    }

    try:
        context.add_checkpoint(**checkpoint_config)
    except getattr(gx.exceptions, "CheckpointError", Exception):
        logger.info("Checkpoint %s exists, reusing.", checkpoint_name)

    results = context.run_checkpoint(checkpoint_name=checkpoint_name)

    success = results.success
    stats = results.statistics
    summary = {
        "suite": suite_name,
        "table": table,
        "success": success,
        "evaluated_expectations": stats.get("evaluated_expectations", 0),
        "successful_expectations": stats.get("successful_expectations", 0),
        "unsuccessful_expectations": stats.get("unsuccessful_expectations", 0),
    }

    log_fn = logger.info if success else logger.error
    log_fn(
        "Suite %s: %s (%d/%d expectations passed)",
        suite_name,
        "PASSED" if success else "FAILED",
        summary["successful_expectations"],
        summary["evaluated_expectations"],
    )

    return summary


def main():
    """Entry point for the quality checks runner."""
    parser = argparse.ArgumentParser(description="Run data quality checks")
    parser.add_argument(
        "--suite",
        type=str,
        default=None,
        help="Run a specific suite (e.g., 'orders', 'payments', 'inventory', 'users'). Omit to run all.",
    )
    parser.add_argument(
        "--fixtures",
        action="store_true",
        help="Run validations against local synthetic fixtures instead of live BigQuery.",
    )
    parser.add_argument(
        "--fixtures-dir",
        type=str,
        default=str(FIXTURES_DIR),
        help="Path to directory containing synthetic test fixture JSON files.",
    )
    parser.add_argument(
        "--strict-row-count",
        action="store_true",
        help="Enforce production row count bounds even in fixture mode.",
    )
    parser.add_argument(
        "--project",
        type=str,
        default=os.getenv("GCP_PROJECT", "instacommerce-prod"),
        help="GCP project ID (when running against BigQuery)",
    )
    parser.add_argument(
        "--dataset",
        type=str,
        default="analytics",
        help="BigQuery dataset name",
    )
    parser.add_argument(
        "--json-output",
        type=str,
        default=None,
        help="Optional path to write JSON summary of results.",
    )
    args = parser.parse_args()

    logging.basicConfig(
        level=logging.INFO,
        format="%(asctime)s [%(levelname)s] %(name)s: %(message)s",
    )

    suite_paths = discover_suites(args.suite)
    if not suite_paths:
        logger.error("No suites found. Exiting.")
        sys.exit(1)

    fixtures_path = Path(args.fixtures_dir)

    use_fixtures = bool(args.fixtures)
    context = None if use_fixtures else build_bigquery_context(args.project, args.dataset)

    results = []
    for path in suite_paths:
        logger.info("Executing suite: %s", path.stem)
        try:
            config = load_suite_config(path)
            if use_fixtures:
                summary = run_suite_on_fixture(
                    config,
                    fixtures_path,
                    strict_row_count=args.strict_row_count,
                )
            else:
                summary = run_suite_bigquery(context, config, args.project)
            results.append(summary)
        except Exception:
            logger.exception("Failed to run suite: %s", path.stem)
            results.append({"suite": path.stem, "success": False, "error": True})

    # Report summary
    total = len(results)
    passed = sum(1 for r in results if r.get("success"))
    failed = total - passed

    logger.info("=" * 60)
    logger.info("Quality Check Summary: %d/%d suites passed", passed, total)
    if failed > 0:
        logger.error("%d suite(s) FAILED", failed)
        for r in results:
            if not r.get("success"):
                logger.error("  - %s (failures: %s)", r.get("suite"), r.get("failures", []))
    else:
        logger.info("All expectation suites passed successfully.")

    if args.json_output:
        with open(args.json_output, "w", encoding="utf-8") as f:
            json.dump({"total": total, "passed": passed, "failed": failed, "results": results}, f, indent=2)

    if failed > 0:
        sys.exit(1)


if __name__ == "__main__":
    main()
