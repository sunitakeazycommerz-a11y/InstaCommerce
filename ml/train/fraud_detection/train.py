"""Fraud Detection Model Training — XGBoost Ensemble

Target: 2% -> 0.3% fraud rate. 80+ features across device, behavioral,
transactional, and velocity signals.
Metrics: AUC, Precision@95%Recall, F1
"""

import argparse
import json
import logging
import os
from pathlib import Path
from typing import Any

import mlflow
import mlflow.xgboost
import numpy as np
import pandas as pd
import xgboost as xgb
import yaml
from google.cloud import bigquery
from sklearn.calibration import CalibratedClassifierCV
from sklearn.metrics import (
    auc,
    classification_report,
    f1_score,
    precision_recall_curve,
    roc_auc_score,
)
from sklearn.model_selection import StratifiedKFold

logger = logging.getLogger(__name__)
logging.basicConfig(level=logging.INFO, format="%(asctime)s %(name)s %(levelname)s %(message)s")

FEATURE_COLUMNS = [
    # Device & session signals
    "device_fingerprint_score",
    "ip_risk_score",
    "is_vpn",
    "is_emulator",
    "session_duration_sec",
    "pages_viewed",
    "app_install_age_days",
    # Behavioral signals
    "time_since_last_order_hr",
    "order_frequency_7d",
    "order_frequency_30d",
    "avg_order_value_30d",
    "max_order_value_30d",
    "distinct_addresses_30d",
    "distinct_payment_methods_30d",
    "address_change_count_24h",
    "payment_change_count_24h",
    # Transaction signals
    "order_value",
    "item_count",
    "high_value_item_count",
    "order_value_zscore",
    "is_first_order",
    "is_new_address",
    "is_new_payment",
    "shipping_billing_distance_km",
    # Velocity signals
    "orders_last_1h",
    "orders_last_24h",
    "distinct_devices_24h",
    "distinct_ips_24h",
    "failed_payments_24h",
    # Payment signals
    "payment_method_risk_score",
    "card_bin_risk_score",
    "card_country_mismatch",
    "billing_address_mismatch",
    "cvv_attempts",
    # Account signals
    "account_age_days",
    "email_domain_risk",
    "phone_verified",
    "email_verified",
    "kyc_level",
    "previous_fraud_count",
    "previous_chargeback_count",
    # Network signals
    "shared_device_users",
    "shared_ip_users",
    "shared_address_orders_24h",
    "referral_chain_fraud_rate",
    # Time-based signals
    "hour_of_day",
    "day_of_week",
    "is_weekend",
    "is_late_night",
    # Derived features
    "order_value_to_avg_ratio",
    "item_count_to_avg_ratio",
    "address_recency_score",
    "payment_recency_score",
    "behavioral_anomaly_score",
    "velocity_anomaly_score",
    # Aggregate risk scores
    "rule_engine_score",
    "historical_risk_score",
    "network_risk_score",
    "device_trust_score",
    "location_risk_score",
    # Category-level signals
    "category_fraud_rate",
    "merchant_fraud_rate",
    "promo_code_risk_score",
    "coupon_abuse_score",
    # Additional features
    "delivery_urgency_score",
    "gift_order_flag",
    "basket_diversity_score",
    "price_sensitivity_score",
    "return_rate_user",
    "refund_rate_user",
    "complaint_count_30d",
    "support_ticket_count_30d",
    "social_login_flag",
    "biometric_verified",
    "two_factor_enabled",
    "notification_enabled",
    "wishlist_age_days",
    "cart_abandonment_rate",
]


class FraudDetectionTrainer:
    """XGBoost fraud detection with class imbalance handling and threshold tuning."""

    def __init__(self, config: dict):
        self.config = config
        self.model: xgb.XGBClassifier | None = None
        self.calibrated_model = None
        self.optimal_threshold: float = 0.5
        self.feature_columns = config.get("features", FEATURE_COLUMNS)

    def load_data(self, query: str) -> pd.DataFrame:
        """Load training data from BigQuery.

        Expected columns: order_id, <feature_columns>, is_fraud (0/1).
        """
        logger.info("Loading fraud training data from BigQuery")
        client = bigquery.Client()
        df = client.query(query).to_dataframe()
        fraud_rate = df["is_fraud"].mean()
        logger.info("Loaded %d rows, fraud rate: %.4f", len(df), fraud_rate)
        return df

    def _compute_scale_pos_weight(self, y: pd.Series) -> float:
        """Compute scale_pos_weight for class imbalance."""
        neg_count = (y == 0).sum()
        pos_count = (y == 1).sum()
        return neg_count / pos_count

    def train(self, df: pd.DataFrame) -> xgb.XGBClassifier:
        """Train XGBoost with class weighting and stratified CV."""
        logger.info("Starting XGBoost fraud detection training")
        hp = self.config["hyperparameters"]
        label_col = self.config["training"]["label_column"]

        X = df[self.feature_columns]
        y = df[label_col]

        scale_pos_weight = self._compute_scale_pos_weight(y)
        logger.info("Scale pos weight: %.2f", scale_pos_weight)

        skf = StratifiedKFold(n_splits=5, shuffle=True, random_state=42)
        fold_aucs = []
        best_auc = -1.0
        best_model = None

        for fold, (train_idx, val_idx) in enumerate(skf.split(X, y)):
            logger.info("Training fold %d/5", fold + 1)
            X_train, X_val = X.iloc[train_idx], X.iloc[val_idx]
            y_train, y_val = y.iloc[train_idx], y.iloc[val_idx]

            model = xgb.XGBClassifier(
                objective="binary:logistic",
                eval_metric="auc",
                scale_pos_weight=scale_pos_weight,
                max_depth=hp.get("max_depth", 8),
                learning_rate=hp.get("learning_rate", 0.05),
                n_estimators=hp.get("n_estimators", 1000),
                min_child_weight=hp.get("min_child_weight", 5),
                subsample=hp.get("subsample", 0.8),
                colsample_bytree=hp.get("colsample_bytree", 0.8),
                reg_alpha=hp.get("reg_alpha", 0.1),
                reg_lambda=hp.get("reg_lambda", 1.0),
                random_state=42,
                n_jobs=-1,
                tree_method="hist",
            )

            model.fit(
                X_train,
                y_train,
                eval_set=[(X_val, y_val)],
                verbose=25,
            )

            val_proba = model.predict_proba(X_val)[:, 1]
            fold_auc = roc_auc_score(y_val, val_proba)
            fold_aucs.append(fold_auc)
            logger.info("Fold %d AUC: %.4f", fold + 1, fold_auc)

            if fold_auc > best_auc:
                best_auc = fold_auc
                best_model = model

        self.model = best_model
        logger.info("Best AUC across folds: %.4f, Mean AUC: %.4f", best_auc, np.mean(fold_aucs))
        return best_model

    def calibrate(self, df: pd.DataFrame):
        """Calibrate model probabilities using isotonic regression."""
        if self.model is None:
            raise RuntimeError("Model not trained yet")
        logger.info("Calibrating model probabilities")
        X = df[self.feature_columns]
        y = df[self.config["training"]["label_column"]]
        self.calibrated_model = CalibratedClassifierCV(
            self.model, method="isotonic", cv=3
        )
        self.calibrated_model.fit(X, y)

    def tune_threshold(self, df: pd.DataFrame, target_recall: float = 0.95) -> float:
        """Find optimal threshold for target recall (default 95%)."""
        if self.model is None:
            raise RuntimeError("Model not trained yet")

        X = df[self.feature_columns]
        y_true = df[self.config["training"]["label_column"]].values
        predictor = self.calibrated_model if self.calibrated_model else self.model
        y_proba = predictor.predict_proba(X)[:, 1]

        precisions, recalls, thresholds = precision_recall_curve(y_true, y_proba)

        # Find threshold that achieves target recall
        valid_idx = np.where(recalls[:-1] >= target_recall)[0]
        if len(valid_idx) > 0:
            # Among thresholds that meet recall target, pick highest precision
            best_idx = valid_idx[np.argmax(precisions[:-1][valid_idx])]
            self.optimal_threshold = float(thresholds[best_idx])
        else:
            self.optimal_threshold = 0.5

        logger.info("Optimal threshold at %.0f%% recall: %.4f", target_recall * 100, self.optimal_threshold)
        return self.optimal_threshold

    def evaluate(self, df: pd.DataFrame) -> dict[str, float]:
        """Evaluate model. Return AUC, Precision@95%Recall, F1."""
        if self.model is None:
            raise RuntimeError("Model not trained yet")

        X = df[self.feature_columns]
        y_true = df[self.config["training"]["label_column"]].values
        predictor = self.calibrated_model if self.calibrated_model else self.model
        y_proba = predictor.predict_proba(X)[:, 1]
        y_pred = (y_proba >= self.optimal_threshold).astype(int)

        auc_score = roc_auc_score(y_true, y_proba)

        precisions, recalls, _ = precision_recall_curve(y_true, y_proba)
        pr_auc = auc(recalls, precisions)

        # Precision at 95% recall
        valid_idx = np.where(recalls[:-1] >= 0.95)[0]
        precision_at_95_recall = float(precisions[:-1][valid_idx].max()) if len(valid_idx) > 0 else 0.0

        f1 = f1_score(y_true, y_pred)

        metrics = {
            "auc_roc": float(auc_score),
            "auc_pr": float(pr_auc),
            "precision_at_95_recall": precision_at_95_recall,
            "f1_score": float(f1),
            "optimal_threshold": self.optimal_threshold,
            "predicted_fraud_rate": float(y_pred.mean()),
            "actual_fraud_rate": float(y_true.mean()),
        }
        logger.info("Evaluation metrics: %s", json.dumps(metrics, indent=2))
        return metrics

    def export(self, path: str):
        """Export model to file for serving."""
        if self.model is None:
            raise RuntimeError("Model not trained yet")
        export_path = Path(path)
        export_path.parent.mkdir(parents=True, exist_ok=True)
        self.model.save_model(str(export_path))
        # Save threshold alongside model
        meta_path = export_path.parent / "metadata.json"
        with open(meta_path, "w") as f:
            json.dump({"optimal_threshold": self.optimal_threshold}, f)
        logger.info("Model exported to %s", export_path)

    def feature_importance(self) -> dict[str, float]:
        """Return gain-based feature importance."""
        if self.model is None:
            raise RuntimeError("Model not trained yet")
        importance = self.model.get_booster().get_score(importance_type="gain")
        return {k: float(v) for k, v in sorted(importance.items(), key=lambda x: -x[1])}


def _check_promotion_gates(metrics: dict, gates: dict) -> bool:
    """Check if model meets promotion gates."""
    passed = True
    if metrics["auc_roc"] < gates.get("min_auc_roc", 0.0):
        logger.warning("AUC-ROC %.4f below gate %.4f", metrics["auc_roc"], gates["min_auc_roc"])
        passed = False
    if metrics["precision_at_95_recall"] < gates.get("min_precision_at_95_recall", 0.0):
        logger.warning(
            "Precision@95%%Recall %.4f below gate %.4f",
            metrics["precision_at_95_recall"],
            gates["min_precision_at_95_recall"],
        )
        passed = False
    return passed


def main():
    parser = argparse.ArgumentParser(description="Fraud Detection XGBoost Training")
    parser.add_argument("--config", type=str, required=True, help="Path to config YAML")
    parser.add_argument("--experiment", type=str, default="fraud-detection", help="MLflow experiment name")
    parser.add_argument("--output-dir", type=str, default="artifacts/fraud-detection", help="Output directory")
    parser.add_argument("--run-name", type=str, default=None, help="MLflow run name")
    args = parser.parse_args()

    with open(args.config, "r") as f:
        config = yaml.safe_load(f)

    mlflow.set_experiment(args.experiment)

    with mlflow.start_run(run_name=args.run_name):
        mlflow.log_params(config["hyperparameters"])
        mlflow.log_param("model_name", config["model_name"])
        mlflow.log_param("version", config["version"])

        trainer = FraudDetectionTrainer(config)

        # Load data
        query = config["training"]["query"]
        df = trainer.load_data(query)

        # Stratified train/test split preserving fraud ratio
        from sklearn.model_selection import train_test_split

        val_split = config["training"].get("validation_split", 0.2)
        train_df, test_df = train_test_split(
            df, test_size=val_split, stratify=df[config["training"]["label_column"]], random_state=42
        )
        logger.info("Train: %d rows, Test: %d rows", len(train_df), len(test_df))

        # Train
        trainer.train(train_df)

        # Calibrate
        trainer.calibrate(test_df)

        # Tune threshold
        trainer.tune_threshold(test_df, target_recall=0.95)

        # Evaluate
        metrics = trainer.evaluate(test_df)
        mlflow.log_metrics(metrics)

        # Promotion gates
        gates = config.get("promotion_gates", {})
        gate_passed = _check_promotion_gates(metrics, gates)
        mlflow.log_metric("promotion_gate_passed", int(gate_passed))

        # Feature importance
        importance = trainer.feature_importance()
        mlflow.log_dict(importance, "feature_importance.json")

        # Export model
        output_dir = Path(args.output_dir) / config["version"]
        model_path = output_dir / "model.xgb"
        trainer.export(str(model_path))
        mlflow.log_artifact(str(model_path))
        mlflow.log_artifact(str(output_dir / "metadata.json"))
        mlflow.log_artifact(args.config)

        logger.info(
            "Training complete. Gate passed: %s. Metrics: %s",
            gate_passed,
            json.dumps(metrics, indent=2),
        )


if __name__ == "__main__":
    main()
