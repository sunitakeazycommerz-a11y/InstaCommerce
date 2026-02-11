"""Demand Forecasting — Prophet (Phase 1) + Temporal Fusion Transformer (Phase 2)

Per store × per SKU × per hour forecasting. Target: 92% accuracy (MAPE ≤ 8%).
Includes: holiday calendar (Indian festivals, IPL), weather integration,
promotion effects, and cannibalization adjustments.
"""

import argparse
import json
import logging
import os
from pathlib import Path
from typing import Any

import mlflow
import numpy as np
import pandas as pd
import yaml
from google.cloud import bigquery
from prophet import Prophet
from sklearn.metrics import mean_absolute_error, mean_absolute_percentage_error

logger = logging.getLogger(__name__)
logging.basicConfig(level=logging.INFO, format="%(asctime)s %(name)s %(levelname)s %(message)s")

# Indian holiday calendar for Prophet
INDIAN_HOLIDAYS = pd.DataFrame({
    "holiday": [
        "republic_day", "holi", "good_friday", "eid_ul_fitr",
        "independence_day", "janmashtami", "ganesh_chaturthi",
        "navratri_start", "dussehra", "diwali", "bhai_dooj",
        "guru_nanak_jayanti", "christmas", "new_years_eve",
        "ipl_start", "ipl_final",
    ],
    "ds": pd.to_datetime([
        "2024-01-26", "2024-03-25", "2024-03-29", "2024-04-11",
        "2024-08-15", "2024-08-26", "2024-09-07",
        "2024-10-03", "2024-10-12", "2024-11-01", "2024-11-03",
        "2024-11-15", "2024-12-25", "2024-12-31",
        "2024-03-22", "2024-05-26",
    ]),
    "lower_window": [0, -1, 0, -1, 0, 0, 0, 0, -1, -3, 0, 0, -1, 0, 0, 0],
    "upper_window": [0, 1, 0, 1, 0, 0, 2, 8, 1, 3, 0, 0, 1, 1, 0, 0],
})

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


class DemandForecastTrainer:
    """Prophet-based demand forecaster with weather and promotion regressors."""

    def __init__(self, config: dict):
        self.config = config
        self.models: dict[str, Prophet] = {}
        self.regressors = config.get("regressors", REGRESSORS)

    def load_data(self, query: str) -> pd.DataFrame:
        """Load historical demand data from BigQuery.

        Expected columns: store_id, sku_id, ds (timestamp), y (demand),
        plus regressor columns.
        """
        logger.info("Loading demand training data from BigQuery")
        client = bigquery.Client()
        df = client.query(query).to_dataframe()
        df["ds"] = pd.to_datetime(df["ds"])
        n_combos = df.groupby(["store_id", "sku_id"]).ngroups
        logger.info("Loaded %d rows, %d store×SKU combinations", len(df), n_combos)
        return df

    def _build_prophet_model(self) -> Prophet:
        """Build a Prophet model with Indian holidays and regressors."""
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

        # Add hourly seasonality for intra-day patterns
        model.add_seasonality(name="hourly", period=1, fourier_order=8)

        # Add regressors
        for reg in self.regressors:
            model.add_regressor(reg, mode="multiplicative")

        return model

    def train(self, df: pd.DataFrame) -> dict[str, Prophet]:
        """Train per store×SKU Prophet models."""
        logger.info("Starting demand forecast training")
        groups = df.groupby(["store_id", "sku_id"])
        total = len(groups)
        trained = 0

        for (store_id, sku_id), group_df in groups:
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
        for (store_id, sku_id), group_df in groups:
            key = f"{store_id}__{sku_id}"
            model = self.models.get(key)
            if model is None:
                continue

            future = group_df[["ds"] + self.regressors].copy()
            forecast = model.predict(future)
            y_true = group_df["y"].values
            y_pred = forecast["yhat"].values

            # Clip negative predictions to zero (demand can't be negative)
            y_pred = np.clip(y_pred, 0, None)

            all_actuals.extend(y_true.tolist())
            all_predictions.extend(y_pred.tolist())

            # Per-model MAPE (avoid division by zero)
            mask = y_true > 0
            if mask.sum() > 0:
                model_mape = np.mean(np.abs(y_true[mask] - y_pred[mask]) / y_true[mask])
                per_model_mape.append(model_mape)

        all_actuals = np.array(all_actuals)
        all_predictions = np.array(all_predictions)

        metrics = {
            "mae": float(mean_absolute_error(all_actuals, all_predictions)),
            "mape": float(np.mean(per_model_mape)) if per_model_mape else 0.0,
            "accuracy_pct": float(1.0 - np.mean(per_model_mape)) * 100 if per_model_mape else 0.0,
            "median_model_mape": float(np.median(per_model_mape)) if per_model_mape else 0.0,
            "p90_model_mape": float(np.percentile(per_model_mape, 90)) if per_model_mape else 0.0,
            "models_evaluated": len(per_model_mape),
            "total_predictions": len(all_actuals),
        }
        logger.info("Evaluation metrics: %s", json.dumps(metrics, indent=2))
        return metrics

    def export(self, path: str):
        """Export all models to directory."""
        if not self.models:
            raise RuntimeError("No models trained yet")
        import pickle

        export_dir = Path(path)
        export_dir.mkdir(parents=True, exist_ok=True)

        manifest = {}
        for key, model in self.models.items():
            model_path = export_dir / f"{key}.pkl"
            with open(model_path, "wb") as f:
                pickle.dump(model, f)
            manifest[key] = str(model_path)

        manifest_path = export_dir / "manifest.json"
        with open(manifest_path, "w") as f:
            json.dump(manifest, f, indent=2)

        logger.info("Exported %d models to %s", len(self.models), export_dir)


def _check_promotion_gates(metrics: dict, gates: dict) -> bool:
    """Check if model meets promotion gates."""
    passed = True
    max_mape = gates.get("max_mape", 1.0)
    if metrics["mape"] > max_mape:
        logger.warning("MAPE %.4f above gate %.4f", metrics["mape"], max_mape)
        passed = False
    min_accuracy = gates.get("min_accuracy_pct", 0.0)
    if metrics["accuracy_pct"] < min_accuracy:
        logger.warning("Accuracy %.2f%% below gate %.2f%%", metrics["accuracy_pct"], min_accuracy)
        passed = False
    return passed


def main():
    parser = argparse.ArgumentParser(description="Demand Forecast Prophet Training")
    parser.add_argument("--config", type=str, required=True, help="Path to config YAML")
    parser.add_argument("--experiment", type=str, default="demand-forecast", help="MLflow experiment name")
    parser.add_argument("--output-dir", type=str, default="artifacts/demand-forecast", help="Output directory")
    parser.add_argument("--run-name", type=str, default=None, help="MLflow run name")
    args = parser.parse_args()

    with open(args.config, "r") as f:
        config = yaml.safe_load(f)

    mlflow.set_experiment(args.experiment)

    with mlflow.start_run(run_name=args.run_name):
        mlflow.log_params({
            k: v for k, v in config.get("hyperparameters", {}).items()
            if isinstance(v, (str, int, float, bool))
        })
        mlflow.log_param("model_name", config["model_name"])
        mlflow.log_param("version", config["version"])

        trainer = DemandForecastTrainer(config)

        # Load data
        query = config["training"]["query"]
        df = trainer.load_data(query)

        # Time-based split: train on history, test on recent period
        cutoff_days = config["training"].get("test_days", 7)
        cutoff_date = df["ds"].max() - pd.Timedelta(days=cutoff_days)
        train_df = df[df["ds"] <= cutoff_date]
        test_df = df[df["ds"] > cutoff_date]
        logger.info("Train: %d rows (up to %s), Test: %d rows", len(train_df), cutoff_date, len(test_df))

        # Train
        trainer.train(train_df)

        # Evaluate
        metrics = trainer.evaluate(test_df)
        mlflow.log_metrics({k: v for k, v in metrics.items() if isinstance(v, (int, float))})

        # Promotion gates
        gates = config.get("promotion_gates", {})
        gate_passed = _check_promotion_gates(metrics, gates)
        mlflow.log_metric("promotion_gate_passed", int(gate_passed))

        # Export models
        output_dir = Path(args.output_dir) / config["version"]
        trainer.export(str(output_dir / "models"))
        mlflow.log_artifacts(str(output_dir))
        mlflow.log_artifact(args.config)

        logger.info(
            "Training complete. Gate passed: %s. MAPE: %.4f, Accuracy: %.2f%%",
            gate_passed,
            metrics["mape"],
            metrics["accuracy_pct"],
        )


if __name__ == "__main__":
    main()
