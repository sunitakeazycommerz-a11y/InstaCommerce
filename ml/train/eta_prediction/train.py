"""ETA Prediction — LightGBM Regression

Target: ±5min -> ±1.5min accuracy.
Three-stage model: pre-order (estimated at checkout), post-assign (after rider
assigned), in-transit (real-time updates during delivery).
"""

from __future__ import annotations

import json
import logging
from pathlib import Path
from typing import Any, Optional

try:
    import lightgbm as _lgb
    lgb: Any = _lgb
except (ImportError, OSError):
    lgb = None

try:
    import mlflow as _mlflow
    mlflow: Optional[MlflowProtocol] = _mlflow
except (ImportError, OSError):
    mlflow = None

import numpy as np
import pandas as pd
from google.cloud import bigquery
from sklearn.metrics import mean_absolute_error, mean_squared_error, r2_score
from sklearn.model_selection import KFold

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

STAGE_FEATURES = {
    "pre_order": [
        "store_id_encoded",
        "hour_of_day",
        "day_of_week",
        "is_weekend",
        "is_holiday",
        "active_orders_at_store",
        "store_avg_prep_time_7d",
        "store_prep_time_p90_7d",
        "item_count",
        "complex_item_count",
        "estimated_prep_complexity",
        "store_distance_km",
        "zone_avg_delivery_time_1h",
        "zone_avg_delivery_time_24h",
        "available_riders_in_zone",
        "weather_condition_encoded",
        "temperature",
        "is_raining",
        "is_peak_hour",
        "surge_multiplier",
        "traffic_index",
        "city_encoded",
        "zone_encoded",
    ],
    "post_assign": [
        "rider_id_encoded",
        "rider_avg_delivery_time_7d",
        "rider_delivery_time_p90_7d",
        "rider_current_batch_size",
        "rider_distance_to_store_km",
        "rider_estimated_arrival_min",
        "store_prep_remaining_min",
        "store_queue_position",
        "route_distance_km",
        "route_estimated_time_min",
        "num_traffic_signals",
        "road_type_encoded",
        "elevation_change_m",
        "rider_vehicle_type_encoded",
        "rider_experience_days",
        "rider_rating",
    ],
    "in_transit": [
        "elapsed_time_min",
        "remaining_distance_km",
        "current_speed_kmh",
        "avg_speed_so_far_kmh",
        "stops_remaining",
        "current_traffic_index",
        "gps_accuracy_m",
        "is_on_expected_route",
        "deviation_distance_m",
        "time_at_current_stop_min",
    ],
}

_GATE_RULES = (
    GateRule("mae_min", "max_mae_min", "le", float("inf"), "MAE", ".4f"),
    GateRule("within_2min_pct", "min_within_2min_pct", "ge", 0.0, "Within-2min", ".4f"),
)


class ETAPredictionTrainer:
    """Three-stage LightGBM ETA predictor: pre-order, post-assign, in-transit."""

    def __init__(self, config: dict):
        self.config = config
        self.models: dict[str, Any] = {}
        self.stage = config.get("stage", "pre_order")

    def load_data(self, query: str) -> pd.DataFrame:
        """Load training data from BigQuery."""
        logger.info("Loading ETA training data from BigQuery")
        client = bigquery.Client()
        df = client.query(query).to_dataframe()
        logger.info("Loaded %d rows for stage '%s'", len(df), self.stage)
        return df

    def _get_features(self, stage: str) -> list[str]:
        """Get feature columns for the specified stage."""
        return self.config.get("features", {}).get(stage, STAGE_FEATURES.get(stage, []))

    def train(self, df: pd.DataFrame) -> Any:
        """Train LightGBM regression model for the configured stage."""
        if lgb is None:
            raise ImportError("lightgbm is required for ETAPredictionTrainer.train")
        logger.info("Training ETA model for stage: %s", self.stage)
        hp = self.config["hyperparameters"]
        label_col = self.config["training"]["label_column"]
        features = self._get_features(self.stage)

        X = df[features]
        y = df[label_col]

        kf = KFold(n_splits=5, shuffle=True, random_state=42)
        fold_maes = []
        best_mae = float("inf")
        best_model = None

        for fold, (train_idx, val_idx) in enumerate(kf.split(X)):
            logger.info("Training fold %d/5", fold + 1)
            X_train, X_val = X.iloc[train_idx], X.iloc[val_idx]
            y_train, y_val = y.iloc[train_idx], y.iloc[val_idx]

            model = lgb.LGBMRegressor(
                objective="regression_l1",
                metric="mae",
                num_leaves=hp.get("num_leaves", 63),
                learning_rate=hp.get("learning_rate", 0.05),
                n_estimators=hp.get("n_estimators", 500),
                min_child_samples=hp.get("min_child_samples", 20),
                subsample=hp.get("subsample", 0.8),
                colsample_bytree=hp.get("colsample_bytree", 0.8),
                reg_alpha=hp.get("reg_alpha", 0.1),
                reg_lambda=hp.get("reg_lambda", 1.0),
                random_state=42,
                n_jobs=-1,
            )

            model.fit(
                X_train,
                y_train,
                eval_set=[(X_val, y_val)],
                callbacks=[
                    lgb.early_stopping(stopping_rounds=50),
                    lgb.log_evaluation(period=25),
                ],
            )

            val_pred = model.predict(X_val)
            fold_mae = mean_absolute_error(y_val, val_pred)
            fold_maes.append(fold_mae)
            logger.info("Fold %d MAE: %.4f min", fold + 1, fold_mae)

            if fold_mae < best_mae:
                best_mae = fold_mae
                best_model = model

        if best_model is None:
            raise RuntimeError(f"Training failed to produce a valid model for stage {self.stage}")

        self.models[self.stage] = best_model
        logger.info("Best MAE across folds: %.4f min, Mean MAE: %.4f min", best_mae, np.mean(fold_maes))
        return best_model

    def evaluate(self, df: pd.DataFrame) -> dict[str, Any]:
        """Evaluate model. Return MAE, RMSE, R², percentile accuracies."""
        model = self.models.get(self.stage)
        if model is None:
            raise RuntimeError(f"Model not trained for stage '{self.stage}'")

        features = self._get_features(self.stage)
        label_col = self.config["training"]["label_column"]
        X = df[features]
        y_true = np.asarray(df[label_col].values)
        y_pred = np.asarray(model.predict(X))
        errors = np.abs(y_true - y_pred)

        metrics: dict[str, Any] = {
            "mae_min": float(mean_absolute_error(y_true, y_pred)),
            "rmse_min": float(np.sqrt(mean_squared_error(y_true, y_pred))),
            "r2_score": float(r2_score(y_true, y_pred)),
            "median_abs_error_min": float(np.median(errors)),
            "p90_abs_error_min": float(np.percentile(errors, 90)),
            "p95_abs_error_min": float(np.percentile(errors, 95)),
            "within_1min_pct": float(np.mean(errors <= 1.0)),
            "within_2min_pct": float(np.mean(errors <= 2.0)),
            "within_5min_pct": float(np.mean(errors <= 5.0)),
            "stage": self.stage,
        }
        logger.info("Evaluation metrics: %s", json.dumps(metrics, indent=2))
        return metrics

    def export(self, path: str):
        """Export model to file for serving."""
        model = self.models.get(self.stage)
        if model is None:
            raise RuntimeError(f"Model not trained for stage '{self.stage}'")
        export_path = Path(path)
        export_path.parent.mkdir(parents=True, exist_ok=True)
        model.booster_.save_model(str(export_path))
        logger.info("Model exported to %s", export_path)

    def feature_importance(self) -> dict[str, float]:
        """Return gain-based feature importance."""
        model = self.models.get(self.stage)
        if model is None:
            raise RuntimeError(f"Model not trained for stage '{self.stage}'")
        features = self._get_features(self.stage)
        return dict(zip(features, model.feature_importances_.tolist()))


def _check_promotion_gates(metrics: dict, gates: dict) -> bool:
    """Check if model meets promotion gates."""
    passed, _ = check_metric_gates(metrics, gates, _GATE_RULES, log=logger)
    return passed


def main():
    parser = build_training_parser(
        "ETA Prediction LightGBM Training",
        "eta-prediction",
        "artifacts/eta-prediction",
    )
    parser.add_argument("--stage", type=str, default=None, help="Override stage (pre_order, post_assign, in_transit)")
    args = parser.parse_args()

    config = load_training_config(args.config)
    if args.stage:
        config["stage"] = args.stage

    stage = config.get("stage", "pre_order")
    trainer = ETAPredictionTrainer(config)
    df = trainer.load_data(config["training"]["query"])
    if "stage" in df.columns:
        df = df[df["stage"] == stage]

    val_split = config["training"].get("validation_split", 0.2)
    shuffled = df.sample(frac=1, random_state=42)
    split_idx = int(len(shuffled) * (1 - val_split))
    train_df = shuffled.iloc[:split_idx]
    test_df = shuffled.iloc[split_idx:]
    logger.info("Train: %d rows, Test: %d rows", len(train_df), len(test_df))

    trainer.train(train_df)
    metrics = trainer.evaluate(test_df)
    gates = config.get("promotion_gates", {})
    gate_passed = _check_promotion_gates(metrics, gates)
    importance = trainer.feature_importance()

    output_dir = Path(args.output_dir) / config["version"] / stage
    model_path = output_dir / "model.lgb"
    trainer.export(str(model_path))

    log_mlflow_run(
        mlflow,
        experiment=args.experiment,
        run_name=args.run_name,
        params={
            **config["hyperparameters"],
            "model_name": config["model_name"],
            "version": config["version"],
            "stage": stage,
        },
        metrics=metrics,
        gate_passed=gate_passed,
        dicts={"feature_importance.json": importance},
        artifact_paths=(model_path, args.config),
    )

    logger.info(
        "Training complete. Stage: %s, Gate passed: %s. MAE: %.2f min",
        stage,
        gate_passed,
        metrics["mae_min"],
    )


if __name__ == "__main__":
    main()
