"""Run data quality checks for all domains.

Loads Great Expectations suite definitions from YAML, executes validations
against BigQuery tables, and reports results. Designed to run as an Airflow
task or standalone CLI.

Usage:
    python run_quality_checks.py                     # Run all suites
    python run_quality_checks.py --suite orders      # Run a single suite
    python run_quality_checks.py --project my-proj   # Override GCP project
"""
import argparse
import logging
import os
import sys
from pathlib import Path

import great_expectations as gx
from great_expectations.core.batch import RuntimeBatchRequest
import yaml

logger = logging.getLogger(__name__)

SUITES_DIR = Path(__file__).parent / "expectations"

SUITE_TABLE_MAP = {
    "orders_validation": "analytics.orders",
    "payments_validation": "analytics.payments",
    "inventory_validation": "analytics.inventory",
    "users_validation": "analytics.users",
}


def load_suite_config(suite_path: Path) -> dict:
    """Load and parse a YAML expectation suite definition."""
    with open(suite_path, "r", encoding="utf-8") as f:
        config = yaml.safe_load(f)
    if not config or "expectation_suite" not in config:
        raise ValueError(f"Invalid suite file: {suite_path}")
    return config["expectation_suite"]


def discover_suites(suite_filter: str | None = None) -> list[Path]:
    """Discover suite YAML files in the expectations directory."""
    pattern = f"{suite_filter}_suite.yaml" if suite_filter else "*_suite.yaml"
    suites = sorted(SUITES_DIR.glob(pattern))
    if not suites:
        logger.warning("No suite files found matching pattern: %s", pattern)
    return suites


def build_context(project: str, dataset: str) -> gx.DataContext:
    """Build a Great Expectations DataContext with a BigQuery datasource."""
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
    except gx.exceptions.DatasourceError:
        logger.info("Datasource already configured, reusing existing.")

    return context


def run_suite(
    context: gx.DataContext,
    suite_config: dict,
    project: str,
) -> dict:
    """Run a single expectation suite and return results summary."""
    suite_name = suite_config["name"]
    table = SUITE_TABLE_MAP.get(suite_name)
    if not table:
        raise ValueError(f"No table mapping for suite: {suite_name}")

    # Create or update the expectation suite
    try:
        suite = context.get_expectation_suite(suite_name)
    except gx.exceptions.DataContextError:
        suite = context.add_expectation_suite(suite_name)

    for exp in suite_config.get("expectations", []):
        suite.add_expectation(
            gx.core.ExpectationConfiguration(
                expectation_type=exp["expectation_type"],
                kwargs=exp.get("kwargs", {}),
            )
        )
    context.save_expectation_suite(suite)

    # Build batch request
    batch_request = RuntimeBatchRequest(
        datasource_name="bigquery_datasource",
        data_connector_name="runtime_connector",
        data_asset_name=suite_name,
        runtime_parameters={"query": f"SELECT * FROM `{project}.{table}`"},
        batch_identifiers={"run_id": f"quality_check_{suite_name}"},
    )

    # Run validation
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
    except gx.exceptions.CheckpointError:
        logger.info("Checkpoint %s exists, reusing.", checkpoint_name)

    results = context.run_checkpoint(checkpoint_name=checkpoint_name)

    # Summarize
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
        help="Run a specific suite (e.g., 'orders', 'payments'). Omit to run all.",
    )
    parser.add_argument(
        "--project",
        type=str,
        default=os.getenv("GCP_PROJECT", "instacommerce-prod"),
        help="GCP project ID",
    )
    parser.add_argument(
        "--dataset",
        type=str,
        default="analytics",
        help="BigQuery dataset name",
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

    context = build_context(args.project, args.dataset)

    results = []
    for path in suite_paths:
        logger.info("Running suite: %s", path.stem)
        try:
            config = load_suite_config(path)
            summary = run_suite(context, config, args.project)
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
                logger.error("  - %s", r.get("suite"))
        sys.exit(1)
    else:
        logger.info("All suites passed.")


if __name__ == "__main__":
    main()
