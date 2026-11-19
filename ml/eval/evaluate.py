"""Unified Model Evaluation Framework

Production evaluation with promotion gates, bias checks, and reporting.
Supports all InstaCommerce ML models with configurable metric thresholds.
"""

from __future__ import annotations

import argparse
import json
import logging
import os
import pickle
from dataclasses import dataclass, field
from pathlib import Path
from typing import Any, Sequence

try:
    import mlflow as _mlflow  # type: ignore
    mlflow: Any = _mlflow
except (ImportError, OSError):
    mlflow: Any = None
try:
    import lightgbm as _lgb  # type: ignore
    lgb: Any = _lgb
except (ImportError, OSError):
    lgb: Any = None
try:
    import xgboost as _xgb  # type: ignore
    xgb: Any = _xgb
except Exception:
    xgb: Any = None
import numpy as np
import pandas as pd
import yaml

from ml.eval.metrics import (
    compute_evaluation_metrics,
    compute_fairness_metrics,
    evaluate_promotion_gates,
)

logger = logging.getLogger(__name__)
logging.basicConfig(level=logging.INFO, format="%(asctime)s %(name)s %(levelname)s %(message)s")


@dataclass
class BiasReport:
    """Report on model fairness and bias metrics."""

    demographic_parity: dict[str, Any] = field(default_factory=dict)
    equalized_odds: dict[str, float] = field(default_factory=dict)
    disparate_impact: dict[str, float] = field(default_factory=dict)
    passed: bool = True
    details: list[str] = field(default_factory=list)

    def to_dict(self) -> dict:
        return {
            "demographic_parity": self.demographic_parity,
            "equalized_odds": self.equalized_odds,
            "disparate_impact": self.disparate_impact,
            "passed": self.passed,
            "details": self.details,
        }


@dataclass
class EvalReport:
    """Comprehensive evaluation report for a model."""

    model_name: str
    version: str
    metrics: dict[str, float] = field(default_factory=dict)
    promotion_gates: dict[str, Any] = field(default_factory=dict)
    gates_passed: bool = False
    bias_report: BiasReport | None = None
    metadata: dict[str, Any] = field(default_factory=dict)

    def to_dict(self) -> dict:
        return {
            "model_name": self.model_name,
            "version": self.version,
            "metrics": self.metrics,
            "promotion_gates": self.promotion_gates,
            "gates_passed": self.gates_passed,
            "bias_report": self.bias_report.to_dict() if self.bias_report else None,
            "metadata": self.metadata,
        }

    def to_json(self, path: str):
        with open(path, "w") as f:
            json.dump(self.to_dict(), f, indent=2, default=str)


class ModelEvaluator:
    """Unified evaluator for all InstaCommerce ML models."""

    SUPPORTED_METRICS = {
        "auc_roc", "auc_pr", "f1_score", "precision", "recall", "accuracy",
        "false_positive_rate", "precision_at_95_recall",
        "ndcg_at_10", "map_at_10", "mrr", "hit_at_10",
        "mae", "mae_min", "rmse", "mape", "r2_score",
        "within_2min_pct", "accuracy_pct", "correlation",
    }

    NON_FEATURE_COLUMNS = {
        "label",
        "target",
        "y",
        "is_fraud",
        "relevance_label",
        "query_id",
        "group_id",
        "group",
        "user_id",
        "customer_id",
        "order_id",
        "product_id",
        "sku_id",
        "item_id",
        "rider_id",
        "store_id",
        "id",
        "timestamp",
        "event_time",
        "created_at",
    }

    def __init__(self):
        pass

    @classmethod
    def _select_feature_columns(
        cls,
        model: Any,
        test_data: pd.DataFrame,
        extra_exclude: tuple[str, ...] = (),
    ) -> list[str]:
        if hasattr(model, "feature_names_in_"):
            in_cols = [str(c) for c in model.feature_names_in_ if c in test_data.columns]
            if in_cols:
                return in_cols
        excluded = cls.NON_FEATURE_COLUMNS | set(extra_exclude)
        return [
            c
            for c in test_data.columns
            if c not in excluded and not str(c).endswith("_id")
        ]

    def evaluate(
        self,
        model: Any,
        test_data: pd.DataFrame,
        gates_config: dict | None = None,
        model_name: str = "unknown",
        version: str = "v0",
        task_type: str | None = None,
        extra_metrics: dict[str, float] | None = None,
    ) -> EvalReport:
        """Run metrics, check promotion gates, and generate report."""
        logger.info("Evaluating model: %s %s", model_name, version)
        gates_config = gates_config or {}
        report = EvalReport(model_name=model_name, version=version)

        resolved_task_type = task_type or gates_config.get("task_type")

        feature_cols = self._select_feature_columns(model, test_data)
        if hasattr(model, "predict_proba") and resolved_task_type != "regression":
            y_pred_proba = model.predict_proba(test_data[feature_cols])[:, 1]
            y_pred = (y_pred_proba >= 0.5).astype(int)
        elif hasattr(model, "predict"):
            y_pred = model.predict(test_data[feature_cols])
            y_pred_proba = None
        else:
            raise ValueError("Model must have predict or predict_proba method")

        label_col = None
        for candidate in ("label", "target", "y", "is_fraud", "relevance_label"):
            if candidate in test_data.columns:
                label_col = candidate
                break
        if label_col is None:
            raise ValueError("No label column found in test data")

        group_values = None
        for group_col in ("query_id", "group_id", "group", "user_id"):
            if group_col in test_data.columns:
                group_values = list(test_data[group_col].values)
                break

        y_true = np.asarray(test_data[label_col].values)
        metrics = self._compute_metrics(
            y_true,
            y_pred,
            y_pred_proba,
            task_type=resolved_task_type,
            groups=group_values,
        )
        if extra_metrics:
            metrics.update(extra_metrics)
        report.metrics = metrics

        gates = gates_config.get("promotion_gates", {})
        report.promotion_gates = gates
        passed, gate_failures = evaluate_promotion_gates(metrics, gates)
        report.gates_passed = passed

        report.metadata = {
            "test_samples": len(test_data),
            "label_column": label_col,
            "feature_count": len(feature_cols),
            "task_type": resolved_task_type,
            "gate_failures": gate_failures,
        }

        logger.info("Evaluation complete. Gates passed: %s", report.gates_passed)
        return report

    def _compute_metrics(
        self,
        y_true: np.ndarray,
        y_pred: np.ndarray,
        y_pred_proba: np.ndarray | None,
        task_type: str | None = None,
        groups: Sequence[Any] | None = None,
    ) -> dict[str, float]:
        """Compute appropriate metrics based on prediction type."""
        return compute_evaluation_metrics(
            y_true,
            y_pred,
            y_pred_proba,
            task_type=task_type,
            groups=groups,
        )

    def _check_gates(self, metrics: dict, gates: dict) -> bool:
        """Check if all promotion gates are met (fail-closed on missing gate metrics)."""
        passed, _ = evaluate_promotion_gates(metrics, gates)
        return passed

    def bias_check(
        self,
        model: Any,
        test_data: pd.DataFrame,
        protected_attributes: list[str] | None = None,
    ) -> BiasReport:
        """Run bias and fairness checks on model predictions.

        Checks:
        - Demographic parity: P(Y_hat=1 | A=a) should be similar across groups
        - Equalized odds: TPR and FPR should be similar across groups
        - Disparate impact: ratio of selection rates >= 0.8 (4/5 rule)

        Args:
            model: Trained model with predict method.
            test_data: Test data including protected attribute columns.
            protected_attributes: List of column names for protected attributes.

        Returns:
            BiasReport with fairness metrics.
        """
        report = BiasReport()

        if protected_attributes is None:
            protected_attributes = [
                col for col in test_data.columns
                if col in ("gender", "age_bucket", "city_tier", "language")
            ]

        if not protected_attributes:
            report.details.append("No protected attributes found for bias check")
            return report

        feature_cols = self._select_feature_columns(
            model, test_data, extra_exclude=tuple(protected_attributes)
        )

        if hasattr(model, "predict_proba"):
            y_pred = (model.predict_proba(test_data[feature_cols])[:, 1] >= 0.5).astype(int)
        else:
            y_pred = model.predict(test_data[feature_cols])

        # Detect label column
        label_col = None
        for candidate in ("label", "target", "y", "is_fraud"):
            if candidate in test_data.columns:
                label_col = candidate
                break

        y_true = np.asarray(test_data[label_col].values) if label_col else None
        dp, eo, di, passed, details = compute_fairness_metrics(
            test_data=test_data,
            y_pred=y_pred,
            y_true=y_true,
            protected_attributes=protected_attributes,
        )
        report.demographic_parity = dp
        report.equalized_odds = eo
        report.disparate_impact = di
        report.passed = passed
        report.details.extend(details)

        logger.info("Bias check complete. Passed: %s", report.passed)
        return report


def evaluate_model(
    model: Any,
    test_data: pd.DataFrame,
    gates_config: dict | None = None,
    model_name: str = "unknown",
    version: str = "v0",
    task_type: str | None = None,
) -> EvalReport:
    """Module-level convenience wrapper around ModelEvaluator.evaluate."""
    return ModelEvaluator().evaluate(
        model=model,
        test_data=test_data,
        gates_config=gates_config,
        model_name=model_name,
        version=version,
        task_type=task_type,
    )


def main():
    parser = argparse.ArgumentParser(description="Unified Model Evaluation")
    parser.add_argument("--model-path", type=str, required=True, help="Path to model artifact")
    parser.add_argument("--test-data", type=str, required=True, help="Path to test data (parquet)")
    parser.add_argument("--gates-config", type=str, required=True, help="Path to config YAML with promotion gates")
    parser.add_argument("--output", type=str, default="eval_report.json", help="Output report path")
    parser.add_argument("--bias-check", action="store_true", help="Run bias checks")
    parser.add_argument(
        "--protected-attrs",
        type=str,
        nargs="*",
        default=None,
        help="Protected attribute columns for bias check",
    )
    args = parser.parse_args()

    with open(args.gates_config, "r") as f:
        config = yaml.safe_load(f)

    # Load test data
    test_data = pd.read_parquet(args.test_data)
    logger.info("Loaded test data: %d rows, %d columns", len(test_data), len(test_data.columns))

    # Load model (detect format from extension)
    model_path = Path(args.model_path)
    if model_path.suffix == ".xgb":
        if xgb is None:
            raise ImportError("xgboost is unavailable")
        model = xgb.XGBClassifier()
        model.load_model(str(model_path))
    elif model_path.suffix == ".lgb":
        if lgb is None:
            raise ImportError("lightgbm is unavailable")
        model = lgb.Booster(model_file=str(model_path))
    elif model_path.suffix == ".pkl":
        with open(model_path, "rb") as f:
            model = pickle.load(f)
    else:
        raise ValueError(f"Unsupported model format: {model_path.suffix}")

    evaluator = ModelEvaluator()
    report = evaluator.evaluate(
        model=model,
        test_data=test_data,
        gates_config=config,
        model_name=config.get("model_name", "unknown"),
        version=config.get("version", "v0"),
    )

    if args.bias_check:
        bias_report = evaluator.bias_check(
            model=model,
            test_data=test_data,
            protected_attributes=args.protected_attrs,
        )
        report.bias_report = bias_report

    report.to_json(args.output)
    logger.info("Report saved to %s", args.output)

    # Log to MLflow if tracking URI is set
    if mlflow is not None and os.environ.get("MLFLOW_TRACKING_URI"):
        with mlflow.start_run():
            mlflow.log_metrics(report.metrics)
            mlflow.log_metric("gates_passed", int(report.gates_passed))
            mlflow.log_artifact(args.output)


if __name__ == "__main__":
    main()
