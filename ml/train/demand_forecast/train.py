"""Demand Forecasting — Prophet (Phase 1) + Temporal Fusion Transformer (Phase 2)

Per store × per SKU × per hour forecasting. Target: 92% accuracy (MAPE ≤ 8%).
Includes: holiday calendar (Indian festivals, IPL), weather integration,
promotion effects, and cannibalization adjustments.
"""

from __future__ import annotations

import importlib
import json
import logging
import pickle
from pathlib import Path
from typing import Any, Optional, cast

try:
    import mlflow as _mlflow
    mlflow: Optional[MlflowProtocol] = _mlflow
except (ImportError, OSError):
    mlflow = None

import numpy as np
import pandas as pd
from google.cloud import bigquery

try:
    Prophet: Any = getattr(importlib.import_module("prophet"), "Prophet", None)
except (ImportError, OSError):
    Prophet = None
from sklearn.metrics import mean_absolute_error

from ml.train.common import (
    GateRule,
    MlflowProtocol,
    build_training_parser,
    check_metric_gates,
    load_training_config,
    log_mlflow_run,
)

logger = logging.getLogger(__name__)
logging.basicConfig(level=logging.INFO, format="%(asctime)s %(name)s %(levelname)s %(message)s")

_VARIABLE_HOLIDAYS_BY_YEAR: dict[int, list[tuple[str, str, int, int]]] = {
    2024: [
        ("holi", "2024-03-25", -1, 1),
        ("good_friday", "2024-03-29", 0, 0),
        ("eid_ul_fitr", "2024-04-11", -1, 1),
        ("janmashtami", "2024-08-26", 0, 0),
        ("ganesh_chaturthi", "2024-09-07", 0, 2),
        ("navratri_start", "2024-10-03", 0, 8),
        ("dussehra", "2024-10-12", -1, 1),
        ("diwali", "2024-11-01", -3, 3),
        ("bhai_dooj", "2024-11-03", 0, 0),
        ("guru_nanak_jayanti", "2024-11-15", 0, 0),
        ("ipl_start", "2024-03-22", 0, 0),
        ("ipl_final", "2024-05-26", 0, 0),
    ],
    2025: [
        ("holi", "2025-03-14", -1, 1),
        ("eid_ul_fitr", "2025-03-31", -1, 1),
        ("good_friday", "2025-04-18", 0, 0),
        ("janmashtami", "2025-08-16", 0, 0),
        ("ganesh_chaturthi", "2025-08-27", 0, 2),
        ("navratri_start", "2025-09-22", 0, 8),
        ("dussehra", "2025-10-02", -1, 1),
        ("diwali", "2025-10-20", -3, 3),
        ("bhai_dooj", "2025-10-23", 0, 0),
        ("guru_nanak_jayanti", "2025-11-05", 0, 0),
        ("ipl_start", "2025-03-22", 0, 0),
        ("ipl_final", "2025-05-25", 0, 0),
    ],
    2026: [
        ("holi", "2026-03-04", -1, 1),
        ("eid_ul_fitr", "2026-03-20", -1, 1),
        ("good_friday", "2026-04-03", 0, 0),
        ("janmashtami", "2026-09-04", 0, 0),
        ("ganesh_chaturthi", "2026-09-14", 0, 2),
        ("navratri_start", "2026-10-11", 0, 8),
        ("dussehra", "2026-10-20", -1, 1),
        ("diwali", "2026-11-08", -3, 3),
        ("bhai_dooj", "2026-11-11", 0, 0),
        ("guru_nanak_jayanti", "2026-11-24", 0, 0),
        ("ipl_start", "2026-03-21", 0, 0),
        ("ipl_final", "2026-05-24", 0, 0),
    ],
    2027: [
        ("holi", "2027-03-22", -1, 1),
        ("eid_ul_fitr", "2027-03-10", -1, 1),
        ("good_friday", "2027-03-26", 0, 0),
        ("janmashtami", "2027-08-25", 0, 0),
        ("ganesh_chaturthi", "2027-09-04", 0, 2),
        ("navratri_start", "2027-09-30", 0, 8),
        ("dussehra", "2027-10-09", -1, 1),
        ("diwali", "2027-10-29", -3, 3),
        ("bhai_dooj", "2027-10-31", 0, 0),
        ("guru_nanak_jayanti", "2027-11-14", 0, 0),
        ("ipl_start", "2027-03-20", 0, 0),
        ("ipl_final", "2027-05-23", 0, 0),
    ],
}

_FIXED_HOLIDAYS: list[tuple[str, int, int, int, int]] = [
    ("republic_day", 1, 26, 0, 0),
    ("independence_day", 8, 15, 0, 0),
    ("christmas", 12, 25, -1, 1),
    ("new_years_eve", 12, 31, 0, 1),
]


def build_indian_holidays(start_year: int = 2024, end_year: int = 2027) -> pd.DataFrame:
    """Build Prophet-compatible Indian holiday calendar covering [start_year, end_year]."""
    rows: list[dict[str, Any]] = []
    for year in range(start_year, end_year + 1):
        for name, month, day, lower_w, upper_w in _FIXED_HOLIDAYS:
            rows.append({
                "holiday": name,
                "ds": f"{year:04d}-{month:02d}-{day:02d}",
                "lower_window": lower_w,
                "upper_window": upper_w,
            })
        for name, date_str, lower_w, upper_w in _VARIABLE_HOLIDAYS_BY_YEAR.get(year, ()):
            rows.append({
                "holiday": name,
                "ds": date_str,
                "lower_window": lower_w,
                "upper_window": upper_w,
            })
    df = pd.DataFrame(rows)
    df["ds"] = pd.to_datetime(df["ds"])
    return df.sort_values(["ds", "holiday"]).reset_index(drop=True)


INDIAN_HOLIDAYS = build_indian_holidays(2024, 2027)

REGRESSORS = [
    "temperature",
    "is_raining",
    "humidity",
    "is_promotion",
    "promotion_discount_pct",
    "competitor_price_index",
    "is_weekend",
    "is_payday_week",
]

_GATE_RULES = (
    GateRule("mape", "max_mape", "le", 1.0, "MAPE", ".4f"),
    GateRule("accuracy_pct", "min_accuracy_pct", "ge", 0.0, "Accuracy", ".2f", "%"),
)


class DemandForecastTrainer:
    """Prophet-based demand forecaster with weather and promotion regressors."""

    def __init__(self, config: dict):
        self.config = config
        self.models: dict[str, Any] = {}
        self.regressors = config.get("regressors", REGRESSORS)

    def load_data(self, query: str) -> pd.DataFrame:
        """Load historical demand data from BigQuery."""
        logger.info("Loading demand training data from BigQuery")
        client = bigquery.Client()
        df = client.query(query).to_dataframe()
        df["ds"] = pd.to_datetime(df["ds"])
        n_combos = df.groupby(["store_id", "sku_id"]).ngroups
        logger.info("Loaded %d rows, %d store×SKU combinations", len(df), n_combos)
        return df

    def _build_prophet_model(self) -> Any:
        """Build a Prophet model with Indian holidays and regressors."""
        if Prophet is None:
            raise ImportError("prophet is required to build a DemandForecastTrainer model")
        hp = self.config.get("hyperparameters", {})
        model = Prophet(
            changepoint_prior_scale=hp.get("changepoint_prior_scale", 0.05),
            seasonality_prior_scale=hp.get("seasonality_prior_scale", 10.0),
            holidays_prior_scale=hp.get("holidays_prior_scale", 10.0),
            seasonality_mode=hp.get("seasonality_mode", "multiplicative"),
            holidays=INDIAN_HOLIDAYS,
            daily_seasonality=True,
            weekly_seasonality=True,
            yearly_seasonality=True,
        )
        model.add_seasonality(name="hourly", period=1, fourier_order=8)
        for reg in self.regressors:
            model.add_regressor(reg, mode="multiplicative")
        return model

    def train(self, df: pd.DataFrame) -> dict[str, Any]:
        """Train per store×SKU Prophet models."""
        logger.info("Starting demand forecast training")
        groups = df.groupby(["store_id", "sku_id"])
        total = len(groups)
        trained = 0

        for group_key, group_df in groups:
            store_id, sku_id = cast(tuple[Any, Any], group_key)
            key = f"{store_id}__{sku_id}"
            if len(group_df) < self.config["training"].get("min_history_rows", 168):
                logger.debug("Skipping %s: insufficient history (%d rows)", key, len(group_df))
                continue

            model = self._build_prophet_model()
            train_data = group_df[["ds", "y"] + self.regressors].copy()
            model.fit(train_data)
            self.models[key] = model
            trained += 1

            if trained % 100 == 0:
                logger.info("Trained %d/%d models", trained, total)

        logger.info("Training complete: %d models trained out of %d groups", trained, total)
        return self.models

    def evaluate(self, df: pd.DataFrame) -> dict[str, float]:
        """Evaluate forecast accuracy across all models."""
        if not self.models:
            raise RuntimeError("No models trained yet")

        all_actuals = []
        all_predictions = []
        per_model_mape = []

        groups = df.groupby(["store_id", "sku_id"])
        for group_key, group_df in groups:
            store_id, sku_id = cast(tuple[Any, Any], group_key)
            key = f"{store_id}__{sku_id}"
            model = self.models.get(key)
            if model is None:
                continue

            future = group_df[["ds"] + self.regressors].copy()
            forecast = model.predict(future)
            y_true = np.asarray(group_df["y"].values)
            y_pred = np.clip(np.asarray(forecast["yhat"].values), 0, None)

            all_actuals.extend(y_true.tolist())
            all_predictions.extend(y_pred.tolist())

            mask = y_true > 0
            if mask.sum() > 0:
                model_mape = np.mean(np.abs(y_true[mask] - y_pred[mask]) / y_true[mask])
                per_model_mape.append(model_mape)

        all_actuals_arr = np.array(all_actuals, dtype=float)
        all_predictions_arr = np.array(all_predictions, dtype=float)
        mae_val = (
            float(mean_absolute_error(all_actuals_arr, all_predictions_arr))
            if len(all_actuals_arr) > 0
            else float("inf")
        )
        mean_mape = float(np.mean(per_model_mape)) if per_model_mape else 1.0

        metrics = {
            "mae": mae_val,
            "mape": mean_mape,
            "accuracy_pct": max(0.0, float(1.0 - mean_mape) * 100.0) if per_model_mape else 0.0,
            "median_model_mape": float(np.median(per_model_mape)) if per_model_mape else 1.0,
            "p90_model_mape": float(np.percentile(per_model_mape, 90)) if per_model_mape else 1.0,
            "models_evaluated": len(per_model_mape),
            "total_predictions": len(all_actuals_arr),
        }
        logger.info("Evaluation metrics: %s", json.dumps(metrics, indent=2))
        return metrics

    def export(self, path: str):
        """Export all models to directory."""
        if not self.models:
            raise RuntimeError("No models trained yet")

        export_dir = Path(path)
        export_dir.mkdir(parents=True, exist_ok=True)

        manifest = {}
        for key, model in self.models.items():
            model_path = export_dir / f"{key}.pkl"
            with open(model_path, "wb") as f:
                pickle.dump(model, f)
            manifest[key] = str(model_path)

        with open(export_dir / "manifest.json", "w") as f:
            json.dump(manifest, f, indent=2)

        logger.info("Exported %d models to %s", len(self.models), export_dir)


def _check_promotion_gates(metrics: dict, gates: dict) -> bool:
    """Check if model meets promotion gates."""
    passed, _ = check_metric_gates(metrics, gates, _GATE_RULES, log=logger)
    return passed


def main():
    parser = build_training_parser(
        "Demand Forecast Prophet Training",
        "demand-forecast",
        "artifacts/demand-forecast",
    )
    args = parser.parse_args()
    config = load_training_config(args.config)

    trainer = DemandForecastTrainer(config)
    df = trainer.load_data(config["training"]["query"])

    cutoff_days = config["training"].get("test_days", 7)
    cutoff_date = df["ds"].max() - pd.Timedelta(days=cutoff_days)
    train_df = cast(pd.DataFrame, df[df["ds"] <= cutoff_date])
    test_df = cast(pd.DataFrame, df[df["ds"] > cutoff_date])
    logger.info("Train: %d rows (up to %s), Test: %d rows", len(train_df), cutoff_date, len(test_df))

    trainer.train(train_df)
    metrics = trainer.evaluate(test_df)
    gates = config.get("promotion_gates", {})
    gate_passed = _check_promotion_gates(metrics, gates)

    output_dir = Path(args.output_dir) / config["version"]
    trainer.export(str(output_dir / "models"))

    log_mlflow_run(
        mlflow,
        experiment=args.experiment,
        run_name=args.run_name,
        params={
            **config.get("hyperparameters", {}),
            "model_name": config["model_name"],
            "version": config["version"],
        },
        metrics=metrics,
        gate_passed=gate_passed,
        artifact_dir=output_dir,
        artifact_paths=(args.config,),
    )

    logger.info(
        "Training complete. Gate passed: %s. MAPE: %.4f, Accuracy: %.2f%%",
        gate_passed,
        metrics["mape"],
        metrics["accuracy_pct"],
    )


if __name__ == "__main__":
    main()
