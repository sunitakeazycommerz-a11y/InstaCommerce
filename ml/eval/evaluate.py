"""Unified Model Evaluation Framework

Production evaluation with promotion gates, bias checks, and reporting.
Supports all InstaCommerce ML models with configurable metric thresholds.
"""

import argparse
import json
import logging
import os
from dataclasses import dataclass, field
from pathlib import Path
from typing import Any

import mlflow
import numpy as np
import pandas as pd
import yaml

logger = logging.getLogger(__name__)
logging.basicConfig(level=logging.INFO, format="%(asctime)s %(name)s %(levelname)s %(message)s")


@dataclass
class BiasReport:
    """Report on model fairness and bias metrics."""

    demographic_parity: dict[str, float] = field(default_factory=dict)
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
        "auc_roc", "auc_pr", "f1_score", "precision", "recall",
        "ndcg_at_10", "map_at_10", "mrr", "hit_at_10",
        "mae", "rmse", "mape", "r2_score",
        "accuracy_pct", "correlation",
    }

    def __init__(self):
        pass

    def evaluate(
        self,
        model: Any,
        test_data: pd.DataFrame,
        gates_config: dict,
        model_name: str = "unknown",
        version: str = "v0",
    ) -> EvalReport:
        """Run metrics, check promotion gates, and generate report.

        Args:
            model: Trained model object with a predict method.
            test_data: Test DataFrame with features and labels.
            gates_config: Config dict with promotion_gates section.
            model_name: Name of the model.
            version: Model version string.

        Returns:
            EvalReport with metrics, gate results, and metadata.
        """
        logger.info("Evaluating model: %s %s", model_name, version)
        report = EvalReport(model_name=model_name, version=version)

        # Compute predictions
        feature_cols = [c for c in test_data.columns if c not in ("label", "target", "y", "is_fraud", "relevance_label")]
        if hasattr(model, "predict_proba"):
            y_pred_proba = model.predict_proba(test_data[feature_cols])[:, 1]
            y_pred = (y_pred_proba >= 0.5).astype(int)
        elif hasattr(model, "predict"):
            y_pred = model.predict(test_data[feature_cols])
            y_pred_proba = None
        else:
            raise ValueError("Model must have predict or predict_proba method")

        # Detect label column
        label_col = None
        for candidate in ("label", "target", "y", "is_fraud", "relevance_label"):
            if candidate in test_data.columns:
                label_col = candidate
                break
        if label_col is None:
            raise ValueError("No label column found in test data")

        y_true = test_data[label_col].values

        # Compute metrics based on task type
        metrics = self._compute_metrics(y_true, y_pred, y_pred_proba)
        report.metrics = metrics

        # Check promotion gates
        gates = gates_config.get("promotion_gates", {})
        report.promotion_gates = gates
        report.gates_passed = self._check_gates(metrics, gates)

        report.metadata = {
            "test_samples": len(test_data),
            "label_column": label_col,
            "feature_count": len(feature_cols),
        }

        logger.info("Evaluation complete. Gates passed: %s", report.gates_passed)
        return report

    def _compute_metrics(
        self,
        y_true: np.ndarray,
        y_pred: np.ndarray,
        y_pred_proba: np.ndarray | None,
    ) -> dict[str, float]:
        """Compute appropriate metrics based on prediction type."""
        from sklearn.metrics import (
            accuracy_score,
            f1_score,
            mean_absolute_error,
            mean_squared_error,
            precision_score,
            r2_score,
            recall_score,
            roc_auc_score,
        )

        metrics = {}

        # Determine if classification or regression
        unique_labels = np.unique(y_true)
        is_classification = len(unique_labels) <= 20 and all(
            float(v).is_integer() for v in unique_labels
        )

        if is_classification:
            metrics["accuracy"] = float(accuracy_score(y_true, y_pred))
            metrics["f1_score"] = float(f1_score(y_true, y_pred, average="weighted", zero_division=0))
            metrics["precision"] = float(precision_score(y_true, y_pred, average="weighted", zero_division=0))
            metrics["recall"] = float(recall_score(y_true, y_pred, average="weighted", zero_division=0))

            if y_pred_proba is not None and len(unique_labels) == 2:
                metrics["auc_roc"] = float(roc_auc_score(y_true, y_pred_proba))
        else:
            metrics["mae"] = float(mean_absolute_error(y_true, y_pred))
            metrics["rmse"] = float(np.sqrt(mean_squared_error(y_true, y_pred)))
            metrics["r2_score"] = float(r2_score(y_true, y_pred))

            # MAPE (avoid division by zero)
            mask = y_true != 0
            if mask.sum() > 0:
                metrics["mape"] = float(np.mean(np.abs(y_true[mask] - y_pred[mask]) / np.abs(y_true[mask])))

        return metrics

    def _check_gates(self, metrics: dict, gates: dict) -> bool:
        """Check if all promotion gates are met."""
        passed = True
        for gate_key, gate_value in gates.items():
            if gate_key.startswith("min_"):
                metric_name = gate_key[4:]
                actual = metrics.get(metric_name, metrics.get(gate_key, None))
                if actual is not None and actual < gate_value:
                    logger.warning(
                        "Gate FAILED: %s = %.4f < %.4f (min)",
                        metric_name, actual, gate_value,
                    )
                    passed = False
            elif gate_key.startswith("max_"):
                metric_name = gate_key[4:]
                actual = metrics.get(metric_name, metrics.get(gate_key, None))
                if actual is not None and actual > gate_value:
                    logger.warning(
                        "Gate FAILED: %s = %.4f > %.4f (max)",
                        metric_name, actual, gate_value,
                    )
                    passed = False
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

        feature_cols = [
            c for c in test_data.columns
            if c not in ("label", "target", "y", "is_fraud", "relevance_label") + tuple(protected_attributes)
        ]

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

        y_true = test_data[label_col].values if label_col else None

        for attr in protected_attributes:
            if attr not in test_data.columns:
                continue

            groups = test_data[attr].unique()
            selection_rates = {}

            for group in groups:
                mask = test_data[attr] == group
                group_pred = y_pred[mask]
                selection_rates[str(group)] = float(group_pred.mean())

                if y_true is not None:
                    group_true = y_true[mask]
                    tp = ((group_pred == 1) & (group_true == 1)).sum()
                    fn = ((group_pred == 0) & (group_true == 1)).sum()
                    fp = ((group_pred == 1) & (group_true == 0)).sum()
                    tn = ((group_pred == 0) & (group_true == 0)).sum()

                    tpr = float(tp / (tp + fn)) if (tp + fn) > 0 else 0.0
                    fpr = float(fp / (fp + tn)) if (fp + tn) > 0 else 0.0
                    report.equalized_odds[f"{attr}_{group}_tpr"] = tpr
                    report.equalized_odds[f"{attr}_{group}_fpr"] = fpr

            report.demographic_parity[attr] = selection_rates

            # Disparate impact (4/5 rule)
            rates = list(selection_rates.values())
            if max(rates) > 0:
                di_ratio = min(rates) / max(rates)
                report.disparate_impact[attr] = float(di_ratio)
                if di_ratio < 0.8:
                    report.passed = False
                    report.details.append(
                        f"Disparate impact for {attr}: {di_ratio:.4f} < 0.8"
                    )

        logger.info("Bias check complete. Passed: %s", report.passed)
        return report


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
        import xgboost as xgb
        model = xgb.XGBClassifier()
        model.load_model(str(model_path))
    elif model_path.suffix == ".lgb":
        import lightgbm as lgb
        model = lgb.Booster(model_file=str(model_path))
    elif model_path.suffix == ".pkl":
        import pickle
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
    if os.environ.get("MLFLOW_TRACKING_URI"):
        with mlflow.start_run():
            mlflow.log_metrics(report.metrics)
            mlflow.log_metric("gates_passed", int(report.gates_passed))
            mlflow.log_artifact(args.output)


if __name__ == "__main__":
    main()
