"""CLV Prediction — BG/NBD (frequency/recency) + Gamma-Gamma (monetary)

Probabilistic model for Customer Lifetime Value prediction.
Segments: Platinum >$500, Gold $200-500, Silver $50-200, Bronze <$50.

BG/NBD models purchase frequency/churn probability.
Gamma-Gamma models average transaction value.
Combined: expected revenue over a time horizon.
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
from sklearn.metrics import mean_absolute_error

logger = logging.getLogger(__name__)
logging.basicConfig(level=logging.INFO, format="%(asctime)s %(name)s %(levelname)s %(message)s")

CLV_SEGMENTS = {
    "platinum": {"min": 500, "max": float("inf")},
    "gold": {"min": 200, "max": 500},
    "silver": {"min": 50, "max": 200},
    "bronze": {"min": 0, "max": 50},
}


class CLVPredictionTrainer:
    """BG/NBD + Gamma-Gamma CLV predictor with customer segmentation."""

    def __init__(self, config: dict):
        self.config = config
        self.bgf_params: dict[str, float] = {}
        self.ggf_params: dict[str, float] = {}
        self.segments = config.get("segments", CLV_SEGMENTS)

    def load_data(self, query: str) -> pd.DataFrame:
        """Load customer transaction data from BigQuery.

        Expected columns: customer_id, order_date, order_value.
        """
        logger.info("Loading CLV training data from BigQuery")
        client = bigquery.Client()
        df = client.query(query).to_dataframe()
        df["order_date"] = pd.to_datetime(df["order_date"])
        logger.info("Loaded %d transactions, %d customers", len(df), df["customer_id"].nunique())
        return df

    def _build_rfm(self, df: pd.DataFrame, observation_end: pd.Timestamp) -> pd.DataFrame:
        """Build RFM (Recency, Frequency, Monetary) summary from transactions.

        Returns DataFrame with columns:
        - frequency: number of repeat purchases (total purchases - 1)
        - recency: time between first and last purchase (in days)
        - T: time between first purchase and observation end (in days)
        - monetary_value: average order value (excluding first purchase)
        """
        customer_first = df.groupby("customer_id")["order_date"].min().rename("first_purchase")
        customer_last = df.groupby("customer_id")["order_date"].max().rename("last_purchase")
        customer_count = df.groupby("customer_id")["order_date"].count().rename("total_orders")

        rfm = pd.concat([customer_first, customer_last, customer_count], axis=1)
        rfm["frequency"] = rfm["total_orders"] - 1
        rfm["recency"] = (rfm["last_purchase"] - rfm["first_purchase"]).dt.days
        rfm["T"] = (observation_end - rfm["first_purchase"]).dt.days

        # Monetary value: average of repeat purchases
        repeat_purchases = df.merge(
            customer_first.reset_index(),
            on="customer_id",
        )
        repeat_purchases = repeat_purchases[
            repeat_purchases["order_date"] > repeat_purchases["first_purchase"]
        ]
        monetary = (
            repeat_purchases.groupby("customer_id")["order_value"]
            .mean()
            .rename("monetary_value")
        )
        rfm = rfm.join(monetary, how="left")
        rfm["monetary_value"] = rfm["monetary_value"].fillna(0)

        # Filter to customers with at least 1 repeat purchase for Gamma-Gamma
        rfm = rfm[rfm["frequency"] > 0]
        rfm = rfm.reset_index()

        logger.info(
            "RFM summary: %d customers, avg frequency: %.2f, avg recency: %.1f days",
            len(rfm), rfm["frequency"].mean(), rfm["recency"].mean(),
        )
        return rfm

    def _fit_bgnbd(self, rfm: pd.DataFrame) -> dict[str, float]:
        """Fit BG/NBD model parameters using maximum likelihood estimation.

        Simplified implementation — production should use lifetimes library.
        Parameters: r, alpha (purchase rate gamma prior), a, b (dropout beta prior).
        """
        logger.info("Fitting BG/NBD model")

        # Initialize parameters
        from scipy.optimize import minimize

        def _bgnbd_log_likelihood(params, frequency, recency, T):
            r, alpha, a, b = params
            if r <= 0 or alpha <= 0 or a <= 0 or b <= 0:
                return 1e10

            from scipy.special import gammaln, betaln

            ln_A_0 = (
                gammaln(r + frequency)
                - gammaln(r)
                + frequency * np.log(alpha)
                + np.log(a)
                - np.log(b + np.maximum(frequency, 1) - 1)
                - (r + frequency) * np.log(alpha + T)
            )

            ln_A_1 = (
                gammaln(r + frequency)
                - gammaln(r)
                + frequency * np.log(alpha)
            )

            ll = np.sum(np.logaddexp(ln_A_0, ln_A_1))
            return -ll if np.isfinite(ll) else 1e10

        result = minimize(
            _bgnbd_log_likelihood,
            x0=[1.0, 1.0, 1.0, 1.0],
            args=(rfm["frequency"].values, rfm["recency"].values, rfm["T"].values),
            method="Nelder-Mead",
            options={"maxiter": 5000},
        )

        params = {
            "r": float(result.x[0]),
            "alpha": float(result.x[1]),
            "a": float(result.x[2]),
            "b": float(result.x[3]),
        }
        self.bgf_params = params
        logger.info("BG/NBD params: %s", json.dumps(params, indent=2))
        return params

    def _fit_gamma_gamma(self, rfm: pd.DataFrame) -> dict[str, float]:
        """Fit Gamma-Gamma model for monetary value prediction.

        Parameters: p, q (shape), v (scale) for the gamma prior on spend.
        """
        logger.info("Fitting Gamma-Gamma model")

        from scipy.optimize import minimize
        from scipy.special import gammaln

        def _gg_log_likelihood(params, frequency, monetary_value):
            p, q, v = params
            if p <= 0 or q <= 0 or v <= 0:
                return 1e10

            x = frequency
            m = monetary_value
            mask = (m > 0) & (x > 0)
            x, m = x[mask], m[mask]

            ll = np.sum(
                gammaln(p * x + q)
                - gammaln(p * x)
                - gammaln(q)
                + q * np.log(v)
                + (p * x - 1) * np.log(m)
                + (p * x) * np.log(x)
                - (p * x + q) * np.log(x * m + v)
            )
            return -ll if np.isfinite(ll) else 1e10

        result = minimize(
            _gg_log_likelihood,
            x0=[1.0, 1.0, 1.0],
            args=(rfm["frequency"].values, rfm["monetary_value"].values),
            method="Nelder-Mead",
            options={"maxiter": 5000},
        )

        params = {
            "p": float(result.x[0]),
            "q": float(result.x[1]),
            "v": float(result.x[2]),
        }
        self.ggf_params = params
        logger.info("Gamma-Gamma params: %s", json.dumps(params, indent=2))
        return params

    def train(self, df: pd.DataFrame):
        """Train BG/NBD and Gamma-Gamma models."""
        logger.info("Starting CLV model training")
        observation_end = df["order_date"].max()
        rfm = self._build_rfm(df, observation_end)
        self._fit_bgnbd(rfm)
        self._fit_gamma_gamma(rfm)
        logger.info("CLV model training complete")

    def predict_clv(self, rfm: pd.DataFrame, horizon_days: int = 365) -> pd.DataFrame:
        """Predict CLV for each customer over the given time horizon."""
        if not self.bgf_params or not self.ggf_params:
            raise RuntimeError("Models not trained yet")

        # Predicted number of future transactions (simplified)
        r, alpha = self.bgf_params["r"], self.bgf_params["alpha"]
        expected_purchases = (
            (r + rfm["frequency"])
            / (alpha + rfm["T"])
            * horizon_days
        )

        # Predicted average monetary value
        p, q, v = self.ggf_params["p"], self.ggf_params["q"], self.ggf_params["v"]
        expected_monetary = (
            (q - 1) / (p * rfm["frequency"] + q - 1)
            * (v + rfm["frequency"] * rfm["monetary_value"])
            / (v + rfm["frequency"] * rfm["monetary_value"])
            * rfm["monetary_value"]
        )
        # Fallback for edge cases
        expected_monetary = expected_monetary.clip(lower=0)

        clv = expected_purchases * expected_monetary

        result = rfm[["customer_id"]].copy()
        result["predicted_clv"] = clv.values
        result["predicted_purchases"] = expected_purchases.values
        result["predicted_avg_value"] = expected_monetary.values
        result["segment"] = result["predicted_clv"].apply(self._assign_segment)

        return result

    def _assign_segment(self, clv: float) -> str:
        """Assign customer to CLV segment."""
        for segment, bounds in self.segments.items():
            if bounds["min"] <= clv < bounds.get("max", float("inf")):
                return segment
        return "bronze"

    def evaluate(self, train_df: pd.DataFrame, test_df: pd.DataFrame) -> dict[str, float]:
        """Evaluate CLV predictions against actual future spend."""
        if not self.bgf_params or not self.ggf_params:
            raise RuntimeError("Models not trained yet")

        observation_end = train_df["order_date"].max()
        rfm = self._build_rfm(train_df, observation_end)

        horizon_days = (test_df["order_date"].max() - observation_end).days
        predictions = self.predict_clv(rfm, horizon_days=max(horizon_days, 1))

        # Actual spend in test period
        actual_spend = (
            test_df.groupby("customer_id")["order_value"]
            .sum()
            .rename("actual_clv")
        )

        eval_df = predictions.merge(actual_spend.reset_index(), on="customer_id", how="inner")

        metrics = {
            "mae": float(mean_absolute_error(eval_df["actual_clv"], eval_df["predicted_clv"])),
            "median_abs_error": float(np.median(np.abs(eval_df["actual_clv"] - eval_df["predicted_clv"]))),
            "correlation": float(eval_df["actual_clv"].corr(eval_df["predicted_clv"])),
            "customers_evaluated": len(eval_df),
        }

        # Segment distribution
        segment_dist = predictions["segment"].value_counts(normalize=True).to_dict()
        metrics["segment_distribution"] = segment_dist

        # Per-segment accuracy
        for segment in self.segments:
            seg_df = eval_df[eval_df["segment"] == segment]
            if len(seg_df) > 0:
                metrics[f"mae_{segment}"] = float(
                    mean_absolute_error(seg_df["actual_clv"], seg_df["predicted_clv"])
                )

        logger.info("Evaluation metrics: %s", json.dumps(
            {k: v for k, v in metrics.items() if not isinstance(v, dict)}, indent=2
        ))
        return metrics

    def export(self, path: str):
        """Export model parameters."""
        if not self.bgf_params:
            raise RuntimeError("Models not trained yet")

        export_dir = Path(path)
        export_dir.mkdir(parents=True, exist_ok=True)

        model_data = {
            "bgnbd_params": self.bgf_params,
            "gamma_gamma_params": self.ggf_params,
            "segments": self.segments,
        }

        with open(export_dir / "model_params.json", "w") as f:
            json.dump(model_data, f, indent=2)

        logger.info("Model exported to %s", export_dir)


def _check_promotion_gates(metrics: dict, gates: dict) -> bool:
    """Check if model meets promotion gates."""
    passed = True
    max_mae = gates.get("max_mae", float("inf"))
    if metrics["mae"] > max_mae:
        logger.warning("MAE %.2f above gate %.2f", metrics["mae"], max_mae)
        passed = False
    min_correlation = gates.get("min_correlation", 0.0)
    if metrics["correlation"] < min_correlation:
        logger.warning("Correlation %.4f below gate %.4f", metrics["correlation"], min_correlation)
        passed = False
    return passed


def main():
    parser = argparse.ArgumentParser(description="CLV Prediction BG/NBD + Gamma-Gamma Training")
    parser.add_argument("--config", type=str, required=True, help="Path to config YAML")
    parser.add_argument("--experiment", type=str, default="clv-prediction", help="MLflow experiment name")
    parser.add_argument("--output-dir", type=str, default="artifacts/clv-prediction", help="Output directory")
    parser.add_argument("--run-name", type=str, default=None, help="MLflow run name")
    args = parser.parse_args()

    with open(args.config, "r") as f:
        config = yaml.safe_load(f)

    mlflow.set_experiment(args.experiment)

    with mlflow.start_run(run_name=args.run_name):
        mlflow.log_param("model_name", config["model_name"])
        mlflow.log_param("version", config["version"])

        trainer = CLVPredictionTrainer(config)

        # Load data
        query = config["training"]["query"]
        df = trainer.load_data(query)

        # Time-based split: train on older data, evaluate on recent
        holdout_days = config["training"].get("holdout_days", 90)
        cutoff = df["order_date"].max() - pd.Timedelta(days=holdout_days)
        train_df = df[df["order_date"] <= cutoff]
        test_df = df[df["order_date"] > cutoff]
        logger.info("Train: %d transactions, Test: %d transactions", len(train_df), len(test_df))

        # Train
        trainer.train(train_df)

        # Log model parameters
        mlflow.log_params({f"bgnbd_{k}": v for k, v in trainer.bgf_params.items()})
        mlflow.log_params({f"gg_{k}": v for k, v in trainer.ggf_params.items()})

        # Evaluate
        metrics = trainer.evaluate(train_df, test_df)
        loggable = {k: v for k, v in metrics.items() if isinstance(v, (int, float))}
        mlflow.log_metrics(loggable)

        # Promotion gates
        gates = config.get("promotion_gates", {})
        gate_passed = _check_promotion_gates(metrics, gates)
        mlflow.log_metric("promotion_gate_passed", int(gate_passed))

        # Generate full CLV predictions
        observation_end = train_df["order_date"].max()
        rfm = trainer._build_rfm(df, observation_end)
        clv_predictions = trainer.predict_clv(rfm, horizon_days=365)
        logger.info("Segment distribution:\n%s", clv_predictions["segment"].value_counts())

        # Export model
        output_dir = Path(args.output_dir) / config["version"]
        trainer.export(str(output_dir))
        clv_predictions.to_parquet(output_dir / "clv_predictions.parquet", index=False)
        mlflow.log_artifacts(str(output_dir))
        mlflow.log_artifact(args.config)

        logger.info(
            "Training complete. Gate passed: %s. MAE: %.2f, Correlation: %.4f",
            gate_passed,
            metrics["mae"],
            metrics["correlation"],
        )


if __name__ == "__main__":
    main()
