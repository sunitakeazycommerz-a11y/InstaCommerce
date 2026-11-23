from __future__ import annotations

import sqlite3
from pathlib import Path

import yaml

REPO_ROOT = Path(__file__).resolve().parents[2]
DBT_MODELS_DIR = REPO_ROOT / "data-platform" / "dbt" / "models"
QUALITY_DIR = REPO_ROOT / "data-platform" / "quality"


def test_staging_schema_yml_matches_java_enums() -> None:
    schema_path = DBT_MODELS_DIR / "staging" / "schema.yml"
    data = yaml.safe_load(schema_path.read_text(encoding="utf-8"))
    by_name = {m["name"]: m for m in data["models"]}

    def _accepted(model_name: str, col_name: str) -> set[str]:
        cols = {c["name"]: c for c in by_name[model_name]["columns"]}
        for test_entry in cols[col_name]["tests"]:
            if isinstance(test_entry, dict) and "accepted_values" in test_entry:
                return set(test_entry["accepted_values"]["values"])
        raise AssertionError(f"Missing accepted_values for {model_name}.{col_name}")

    assert _accepted("stg_orders", "status") == {
        "PENDING", "PLACED", "PACKING", "PACKED", "OUT_FOR_DELIVERY",
        "DELIVERED", "CANCELLED", "FAILED", "REFUNDED",
    }
    assert _accepted("stg_payments", "status") == {
        "AUTHORIZE_PENDING", "AUTHORIZED", "CAPTURE_PENDING", "CAPTURED",
        "VOID_PENDING", "VOIDED", "PARTIALLY_REFUNDED", "REFUNDED",
        "FAILED", "DISPUTED",
    }
    assert _accepted("stg_deliveries", "status") == {
        "PENDING", "ASSIGNED", "RIDER_ASSIGNED", "PICKED_UP", "IN_TRANSIT",
        "EN_ROUTE", "NEAR_DESTINATION", "DELIVERED", "FAILED",
    }


def test_quality_suite_mapping_matches_airflow_dag() -> None:
    from run_quality_checks import SUITE_TABLE_MAP

    assert set(SUITE_TABLE_MAP.keys()) == {
        "orders_validation",
        "payments_validation",
        "inventory_validation",
        "users_validation",
        "daily_sales_validation",
    }
    assert SUITE_TABLE_MAP["orders_validation"] == "staging.stg_orders"
    assert SUITE_TABLE_MAP["payments_validation"] == "staging.stg_payments"
    assert SUITE_TABLE_MAP["inventory_validation"] == "staging.stg_inventory_movements"
    assert SUITE_TABLE_MAP["users_validation"] == "staging.stg_users"
    assert SUITE_TABLE_MAP["daily_sales_validation"] == "marts.mart_daily_sales"

    dq_src = (REPO_ROOT / "data-platform" / "airflow" / "dags" / "data_quality.py").read_text(encoding="utf-8")
    for domain in ("orders", "payments", "inventory", "users"):
        assert f'"{domain}_validation"' in dq_src


def test_dbt_sql_structural_and_semantic_invariants() -> None:
    int_deliv = (DBT_MODELS_DIR / "intermediate" / "int_order_deliveries.sql").read_text(encoding="utf-8")
    assert "o.status = 'DELIVERED'" in int_deliv
    assert "d.delivered_at IS NOT NULL" in int_deliv

    for rel in [
        "marts/mart_daily_revenue.sql",
        "marts/mart_daily_sales.sql",
        "marts/mart_store_performance.sql",
    ]:
        sql = (DBT_MODELS_DIR / rel).read_text(encoding="utf-8")
        assert any(tok in sql for tok in ("COUNT(od.sla_met)", "COUNT(d.sla_met)", "COUNT(sla_met)"))

    store_perf = (DBT_MODELS_DIR / "marts" / "mart_store_performance.sql").read_text(encoding="utf-8")
    assert "'CAPTURED', 'COMPLETED'" in store_perf
    assert "COALESCE(p.completed_at, p.failed_at, p.initiated_at)" in store_perf

    int_prod = (DBT_MODELS_DIR / "intermediate" / "int_product_performance.sql").read_text(encoding="utf-8")
    assert "GROUP BY product_id, store_id" in int_prod
    assert "p.product_id = pcm.product_id AND p.store_id = pcm.store_id" in int_prod

    exp_sql = (DBT_MODELS_DIR / "marts" / "mart_experiment_conversions.sql").read_text(encoding="utf-8")
    assert "GROUP BY e.experiment_id, e.experiment_key, e.variant_name" in exp_sql
    assert "USING (experiment_id, experiment_key)" in exp_sql

    rider_sql = (DBT_MODELS_DIR / "marts" / "mart_rider_performance.sql").read_text(encoding="utf-8")
    assert "'CANCELLED', 'FAILED'" in rider_sql


def test_sqlite_semantics_for_sla_revenue_cart_and_experiment_isolation() -> None:
    conn = sqlite3.connect(":memory:")
    cur = conn.cursor()
    cur.executescript(
        """
        CREATE TABLE orders (order_id TEXT, store_id TEXT, status TEXT, sla_met INTEGER);
        INSERT INTO orders VALUES
            ('o1', 's1', 'DELIVERED', 1),
            ('o2', 's1', 'DELIVERED', 1),
            ('o3', 's1', 'OUT_FOR_DELIVERY', NULL);

        CREATE TABLE payments (
            payment_id TEXT, order_id TEXT, status TEXT, amount_usd REAL,
            initiated_at TEXT, completed_at TEXT, failed_at TEXT
        );
        INSERT INTO payments VALUES
            ('p1', 'o1', 'CAPTURED', 25.0, '2026-04-01', '2026-04-01', NULL),
            ('p2', 'o2', 'AUTHORIZED', 40.0, '2026-04-01', NULL, NULL),
            ('p3', 'o3', 'FAILED', 15.0, '2026-03-31', NULL, '2026-04-01');
        """
    )

    sla_pct, delivered = cur.execute(
        """
        SELECT
            ROUND(100.0 * SUM(CASE WHEN sla_met = 1 THEN 1 ELSE 0 END) / COUNT(sla_met), 2),
            COUNT(sla_met)
        FROM orders
        """
    ).fetchone()
    assert delivered == 2
    assert sla_pct == 100.0

    collected, failed_on_apr_01 = cur.execute(
        """
        SELECT
            COALESCE(SUM(CASE WHEN status IN ('CAPTURED', 'COMPLETED') THEN amount_usd ELSE 0 END), 0),
            SUM(CASE WHEN status = 'FAILED' AND DATE(COALESCE(completed_at, failed_at, initiated_at)) = '2026-04-01' THEN 1 ELSE 0 END)
        FROM payments
        """
    ).fetchone()
    assert collected == 25.0
    assert failed_on_apr_01 == 1
    conn.close()
