"""Structural and mathematical tests for dbt models in data-platform/dbt."""
from __future__ import annotations

import math
from pathlib import Path
import re
import sqlite3
from typing import Any, Dict, List, Optional, Set, Tuple

import pytest
import yaml


DBT_DIR = Path(__file__).resolve().parents[1] / "dbt"
MODELS_DIR = DBT_DIR / "models"
SOURCES_YML = MODELS_DIR / "staging" / "sources.yml"
DBT_PROJECT_YML = DBT_DIR / "dbt_project.yml"

_SOURCE_RE = re.compile(r"\{\{\s*source\(\s*['\"]([^'\"]+)['\"]\s*,\s*['\"]([^'\"]+)['\"]\s*\)\s*\}\}")
_REF_RE = re.compile(r"ref\(\s*['\"]([^'\"]+)['\"]\s*\)")
_CONFIG_INCREMENTAL_RE = re.compile(r"materialized\s*=\s*['\"]incremental['\"]")


def _load_registered_sources() -> Set[Tuple[str, str]]:
    data = yaml.safe_load(SOURCES_YML.read_text(encoding="utf-8"))
    registered: Set[Tuple[str, str]] = set()
    for src in data.get("sources", []):
        src_name = src["name"]
        for tbl in src.get("tables", []):
            registered.add((src_name, tbl["name"]))
    return registered


def _discover_model_names() -> Set[str]:
    return {p.stem for p in MODELS_DIR.rglob("*.sql")}


def _collect_incremental_models_from_project() -> Set[str]:
    project_cfg = yaml.safe_load(DBT_PROJECT_YML.read_text(encoding="utf-8"))
    incremental: Set[str] = set()

    def _walk(node: Any, name: Optional[str] = None) -> None:
        if not isinstance(node, dict):
            return
        if node.get("+materialized") == "incremental" and name is not None:
            incremental.add(name)
        for key, val in node.items():
            if not str(key).startswith("+"):
                _walk(val, str(key))

    _walk(project_cfg.get("models", {}))
    return incremental


def _safe_divide(numerator: Optional[float], denominator: Optional[float]) -> Optional[float]:
    if numerator is None or denominator is None or denominator == 0:
        return None
    return float(numerator) / float(denominator)


def _sqlite_sqrt(value: Optional[float]) -> Optional[float]:
    if value is None or value < 0:
        return None
    return math.sqrt(float(value))


def _expected_pooled_z(
    c_treat: int,
    n_treat: int,
    c_ctrl: int,
    n_ctrl: int,
) -> Optional[float]:
    if n_treat <= 30 or n_ctrl <= 30:
        return None
    p_treat = c_treat / n_treat
    p_ctrl = c_ctrl / n_ctrl
    p_pool = (c_treat + c_ctrl) / (n_treat + n_ctrl)
    se_sq = p_pool * (1.0 - p_pool) * (1.0 / n_treat + 1.0 / n_ctrl)
    if se_sq <= 0.0:
        return None
    return (p_treat - p_ctrl) / math.sqrt(se_sq)


def test_all_dbt_sources_and_refs_are_registered() -> None:
    registered_sources = _load_registered_sources()
    assert ("analytics", "fact_experiment_exposures") in registered_sources

    model_names = _discover_model_names()
    sql_files = sorted(MODELS_DIR.rglob("*.sql"))
    assert len(sql_files) >= 21

    for sql_file in sql_files:
        text = sql_file.read_text(encoding="utf-8")
        for src_name, tbl_name in _SOURCE_RE.findall(text):
            assert (src_name, tbl_name) in registered_sources, (
                f"{sql_file.name} references unregistered source ({src_name!r}, {tbl_name!r})"
            )
        for ref_name in _REF_RE.findall(text):
            assert ref_name in model_names, (
                f"{sql_file.name} references unknown model {ref_name!r}"
            )

    for yml_file in sorted(MODELS_DIR.rglob("schema.yml")):
        text = yml_file.read_text(encoding="utf-8")
        for ref_name in _REF_RE.findall(text):
            assert ref_name in model_names, (
                f"{yml_file} references unknown model {ref_name!r}"
            )


def test_all_incremental_dbt_models_have_is_incremental_guard() -> None:
    project_incremental = _collect_incremental_models_from_project()
    sql_files = sorted(MODELS_DIR.rglob("*.sql"))
    checked = 0

    for sql_file in sql_files:
        text = sql_file.read_text(encoding="utf-8")
        is_inc = sql_file.stem in project_incremental or bool(_CONFIG_INCREMENTAL_RE.search(text))
        if is_inc:
            checked += 1
            assert "is_incremental()" in text, (
                f"Incremental model {sql_file.name} is missing an is_incremental() guard"
            )
        if "{{ this }}" in text:
            assert "is_incremental()" in text, (
                f"Model {sql_file.name} references {{{{ this }}}} without is_incremental() guard"
            )

    assert checked >= 1


def test_mart_order_fulfillment_columns_match_int_order_deliveries() -> None:
    int_sql = (MODELS_DIR / "intermediate" / "int_order_deliveries.sql").read_text(encoding="utf-8")
    mart_sql = (MODELS_DIR / "marts" / "mart_order_fulfillment.sql").read_text(encoding="utf-8")
    for required_col in ("zone_id", "status", "picked_up_at", "updated_at"):
        assert required_col in int_sql, f"int_order_deliveries.sql missing {required_col}"
    assert "d.picked_up_at AS picked_at" in mart_sql
    assert "d.updated_at" in mart_sql


def test_mart_experiment_conversions_z_score_sql_matches_two_proportion_pooled_test() -> None:
    sql_text = (MODELS_DIR / "marts" / "mart_experiment_conversions.sql").read_text(encoding="utf-8")
    assert "control_converted_users" in sql_text
    assert "p_pool" in sql_text

    marker = "control_benchmark AS ("
    assert marker in sql_text
    tail_sql = "WITH " + marker + sql_text.split(marker, 1)[1]
    tail_sql = tail_sql.replace("CURRENT_TIMESTAMP()", "'2026-01-01T00:00:00Z'")

    full_sql = (
        sql_text.replace("{{ source('analytics', 'fact_experiment_exposures') }}", "fact_experiment_exposures")
        .replace("{{ ref('stg_orders') }}", "stg_orders")
        .replace("CURRENT_TIMESTAMP()", "'2026-01-01T00:00:00Z'")
    )

    conn = sqlite3.connect(":memory:")
    conn.create_function("SAFE_DIVIDE", 2, _safe_divide)
    conn.create_function("SQRT", 1, _sqlite_sqrt)
    conn.execute(
        """
        CREATE TABLE aggregated_metrics (
            experiment_id TEXT,
            experiment_key TEXT,
            variant_name TEXT,
            total_users INTEGER,
            converted_users INTEGER,
            total_orders INTEGER,
            total_gmv_usd REAL,
            conversion_rate REAL,
            revenue_per_user_usd REAL
        )
        """
    )

    rows: List[Tuple[str, str, str, int, int, int, float, float, float]] = [
        ("exp-1", "checkout_v2", "control", 1000, 100, 105, 2500.0, 100 / 1000, 2.5),
        ("exp-1", "checkout_v2", "variant_a", 1000, 130, 140, 3400.0, 130 / 1000, 3.4),
        ("exp-1", "checkout_v2", "variant_b", 800, 64, 70, 1600.0, 64 / 800, 2.0),
        ("exp-1", "checkout_v2", "variant_eq", 500, 50, 52, 1250.0, 50 / 500, 2.5),
        ("exp-1", "checkout_v2", "variant_small", 25, 10, 10, 250.0, 10 / 25, 10.0),
        ("exp-2", "zero_conv", "control", 100, 0, 0, 0.0, 0.0, 0.0),
        ("exp-2", "zero_conv", "variant_zero", 100, 0, 0, 0.0, 0.0, 0.0),
    ]
    conn.executemany(
        "INSERT INTO aggregated_metrics VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)",
        rows,
    )

    cur = conn.execute(tail_sql)
    by_key_variant: Dict[Tuple[str, str], Optional[float]] = {
        (row[1], row[2]): row[12] for row in cur.fetchall()
    }

    assert by_key_variant[("checkout_v2", "control")] is None
    assert by_key_variant[("checkout_v2", "variant_small")] is None
    assert by_key_variant[("zero_conv", "variant_zero")] is None
    assert by_key_variant[("checkout_v2", "variant_eq")] == pytest.approx(0.0, abs=1e-12)

    expected_a = _expected_pooled_z(130, 1000, 100, 1000)
    expected_b = _expected_pooled_z(64, 800, 100, 1000)
    actual_a = by_key_variant[("checkout_v2", "variant_a")]
    actual_b = by_key_variant[("checkout_v2", "variant_b")]
    assert expected_a is not None and expected_b is not None
    assert actual_a is not None and actual_b is not None
    assert actual_a == pytest.approx(expected_a, rel=1e-9)
    assert actual_b == pytest.approx(expected_b, rel=1e-9)
    assert actual_a > 2.0
    assert actual_b < 0.0

    conn.execute(
        """
        CREATE TABLE fact_experiment_exposures (
            experiment_id TEXT,
            experiment_key TEXT,
            variant_name TEXT,
            user_id TEXT,
            exposed_at TEXT
        )
        """
    )
    conn.execute(
        """
        CREATE TABLE stg_orders (
            order_id TEXT,
            user_id TEXT,
            total_cents INTEGER,
            placed_at TEXT,
            status TEXT
        )
        """
    )
    exposures = [
        ("exp-e2e", "fast_checkout", "control", f"c-{i}", "2026-10-01T10:00:00Z")
        for i in range(40)
    ] + [
        ("exp-e2e", "fast_checkout", "variant_a", f"v-{i}", "2026-10-01T10:00:00Z")
        for i in range(50)
    ]
    orders = [
        (f"ord-c-{i}", f"c-{i}", 1500, "2026-10-01T11:00:00Z", "DELIVERED")
        for i in range(8)
    ] + [
        (f"ord-v-{i}", f"v-{i}", 1800, "2026-10-01T11:00:00Z", "DELIVERED")
        for i in range(20)
    ] + [
        ("ord-pre", "c-39", 1500, "2026-09-30T11:00:00Z", "DELIVERED"),
        ("ord-cancel", "c-38", 1500, "2026-10-01T12:00:00Z", "CANCELLED"),
    ]
    conn.executemany("INSERT INTO fact_experiment_exposures VALUES (?, ?, ?, ?, ?)", exposures)
    conn.executemany("INSERT INTO stg_orders VALUES (?, ?, ?, ?, ?)", orders)

    e2e_rows = conn.execute(full_sql).fetchall()
    conn.close()

    e2e_by_variant = {row[2]: row for row in e2e_rows}
    assert e2e_by_variant["control"][3] == 40
    assert e2e_by_variant["control"][4] == 8
    assert e2e_by_variant["variant_a"][3] == 50
    assert e2e_by_variant["variant_a"][4] == 20
    expected_e2e = _expected_pooled_z(20, 50, 8, 40)
    assert expected_e2e is not None
    assert e2e_by_variant["variant_a"][12] == pytest.approx(expected_e2e, rel=1e-9)


def test_int_order_deliveries_updated_at_handles_null_order_timestamp() -> None:
    sql_text = (MODELS_DIR / "intermediate" / "int_order_deliveries.sql").read_text(encoding="utf-8")
    expected_expr = (
        "GREATEST(COALESCE(o._cdc_updated_at, d._cdc_updated_at), "
        "COALESCE(d._cdc_updated_at, o._cdc_updated_at)) AS updated_at"
    )
    assert expected_expr in sql_text

    conn = sqlite3.connect(":memory:")
    conn.create_function(
        "GREATEST",
        2,
        lambda a, b: None if (a is None or b is None) else max(a, b),
    )
    conn.execute("CREATE TABLE stg_orders (order_id TEXT, _cdc_updated_at TEXT)")
    conn.execute("CREATE TABLE stg_deliveries (order_id TEXT, _cdc_updated_at TEXT)")
    conn.executemany(
        "INSERT INTO stg_orders VALUES (?, ?)",
        [
            ("ord-both", "2026-10-01T10:00:00Z"),
            ("ord-null-o", None),
            ("ord-null-d", "2026-10-01T09:00:00Z"),
            ("ord-both-null", None),
        ],
    )
    conn.executemany(
        "INSERT INTO stg_deliveries VALUES (?, ?)",
        [
            ("ord-both", "2026-10-01T11:00:00Z"),
            ("ord-null-o", "2026-10-01T12:00:00Z"),
            ("ord-null-d", None),
            ("ord-both-null", None),
        ],
    )
    rows = conn.execute(
        f"""
        SELECT
            o.order_id,
            {expected_expr}
        FROM stg_orders o
        LEFT JOIN stg_deliveries d ON o.order_id = d.order_id
        """
    ).fetchall()
    conn.close()

    by_id = dict(rows)
    assert by_id["ord-both"] == "2026-10-01T11:00:00Z"
    assert by_id["ord-null-o"] == "2026-10-01T12:00:00Z"
    assert by_id["ord-null-d"] == "2026-10-01T09:00:00Z"
    assert by_id["ord-both-null"] is None
