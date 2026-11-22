"""CLV Prediction — BG/NBD (frequency/recency) + Gamma-Gamma (monetary)

Probabilistic model for Customer Lifetime Value prediction.
Segments: Platinum >$500, Gold $200-500, Silver $50-200, Bronze <$50.

BG/NBD models purchase frequency/churn probability.
Gamma-Gamma models average transaction value.
Combined: expected revenue over a time horizon.
"""
from __future__ import annotations

import json
import logging
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
from scipy.optimize import minimize
from scipy.special import gammaln
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

CLV_SEGMENTS = {
    "platinum": {"min": 500, "max": float("inf")},
    "gold": {"min": 200, "max": 500},
    "silver": {"min": 50, "max": 200},
    "bronze": {"min": 0, "max": 50},
}

_GATE_RULES = (
    GateRule("mae", "max_mae", "le", float("inf"), "MAE", ".2f"),
    GateRule("correlation", "min_correlation", "ge", 0.0, "Correlation", ".4f"),
)


class CLVPredictionTrainer:
    """BG/NBD + Gamma-Gamma CLV predictor with customer segmentation."""

    def __init__(self, config: dict):
        self.config = config
        self.bgf_params: dict[str, float] = {}
        self.ggf_params: dict[str, float] = {}
        self.segments = config.get("segments", CLV_SEGMENTS)

    def load_data(self, query: str) -> pd.DataFrame:
        """Load customer transaction data from BigQuery."""
        logger.info("Loading CLV training data from BigQuery")
        client = bigquery.Client()
        df = client.query(query).to_dataframe()
        df["order_date"] = pd.to_datetime(df["order_date"])
        logger.info("Loaded %d transactions, %d customers", len(df), df["customer_id"].nunique())
        return df

    def _build_rfm(self, df: pd.DataFrame, observation_end: pd.Timestamp) -> pd.DataFrame:
        """Build RFM summary from transactions for customers with >= 1 repeat order."""
        customer_first = cast(pd.Series, df.groupby("customer_id")["order_date"].min())
        customer_first.name = "first_purchase"
        customer_last = cast(pd.Series, df.groupby("customer_id")["order_date"].max())
        customer_last.name = "last_purchase"
        customer_count = cast(pd.Series, df.groupby("customer_id")["order_date"].count())
        customer_count.name = "total_orders"

        rfm = pd.concat([customer_first, customer_last, customer_count], axis=1)
        rfm["frequency"] = rfm["total_orders"] - 1
        rfm["recency"] = (rfm["last_purchase"] - rfm["first_purchase"]).dt.days
        rfm["recency_days"] = rfm["recency"]
        rfm["days_since_last_order"] = (observation_end - rfm["last_purchase"]).dt.days
        rfm["T"] = (observation_end - rfm["first_purchase"]).dt.days

        repeat_purchases = df.merge(customer_first.reset_index(), on="customer_id")
        repeat_purchases = repeat_purchases[
            repeat_purchases["order_date"] > repeat_purchases["first_purchase"]
        ]
        monetary = cast(
            pd.Series,
            repeat_purchases.groupby("customer_id")["order_value"].mean(),
        ).to_frame("monetary_value")
        rfm = rfm.join(monetary, how="left")
        rfm["monetary_value"] = rfm["monetary_value"].fillna(0)
        rfm = rfm[rfm["frequency"] > 0].reset_index()

        logger.info(
            "RFM summary: %d customers, avg frequency: %.2f, avg recency: %.1f days",
            len(rfm),
            rfm["frequency"].mean(),
            rfm["recency"].mean(),
        )
        return rfm

    def _fit_bgnbd(self, rfm: pd.DataFrame) -> dict[str, float]:
        """Fit BG/NBD model parameters using maximum likelihood estimation."""
        logger.info("Fitting BG/NBD model")

        def _bgnbd_log_likelihood(params, frequency, recency, T):
            r, alpha, a, b = params
            if r <= 0 or alpha <= 0 or a <= 0 or b <= 0:
                return 1e10
            common = (
                gammaln(r + frequency)
                - gammaln(r)
                + frequency * np.log(alpha)
                - np.log(a + b + np.maximum(frequency, 1) - 1)
            )
            ln_A_0 = common + np.log(b) - (r + frequency) * np.log(alpha + T)
            ln_A_1 = common + np.log(a) - (r + frequency) * np.log(alpha + recency)
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
        """Fit Gamma-Gamma model for monetary value prediction."""
        logger.info("Fitting Gamma-Gamma model")

        def _gg_log_likelihood(params, frequency, monetary_value):
            p, q, v = params
            if p <= 0 or q <= 0 or v <= 0:
                return 1e10
            mask = (monetary_value > 0) & (frequency > 0)
            x, m = frequency[mask], monetary_value[mask]
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
        params = {"p": float(result.x[0]), "q": float(result.x[1]), "v": float(result.x[2])}
        self.ggf_params = params
        logger.info("Gamma-Gamma params: %s", json.dumps(params, indent=2))
        return params

    def train(self, df: pd.DataFrame):
        """Train BG/NBD and Gamma-Gamma models."""
        logger.info("Starting CLV model training")
        observation_end = cast(pd.Timestamp, pd.Timestamp(str(df["order_date"].max())))
        rfm = self._build_rfm(df, observation_end)
        self._fit_bgnbd(rfm)
        self._fit_gamma_gamma(rfm)
        logger.info("CLV model training complete")

    def predict_clv(self, rfm: pd.DataFrame, horizon_days: int = 365) -> pd.DataFrame:
        """Predict CLV for each customer over the given time horizon."""
        if not self.bgf_params or not self.ggf_params:
            raise RuntimeError("Models not trained yet")

        r, alpha = self.bgf_params["r"], self.bgf_params["alpha"]
        expected_purchases = (r + rfm["frequency"]) / (alpha + rfm["T"]) * horizon_days

        p, q, v = self.ggf_params["p"], self.ggf_params["q"], self.ggf_params["v"]
        x, m_x = rfm["frequency"], rfm["monetary_value"]
        raw_denom = p * x + q - 1
        expected_monetary = pd.Series(
            np.where(
                np.abs(raw_denom) > 1e-9,
                ((p * m_x * x) + (q - 1) * v) / np.where(np.abs(raw_denom) > 1e-9, raw_denom, 1.0),
                m_x,
            ),
            index=rfm.index,
        ).clip(lower=0)

        clv = expected_purchases * expected_monetary
        result = rfm[["customer_id"]].copy()
        result["predicted_clv"] = clv
        result["predicted_purchases"] = expected_purchases
        result["predicted_avg_value"] = expected_monetary
        result["segment"] = [self._assign_segment(float(val)) for val in clv]
        return cast(pd.DataFrame, result)

    def _assign_segment(self, clv: float) -> str:
        """Assign customer to CLV segment."""
        if not np.isfinite(clv) or clv < 0:
            return "bronze"
        for segment, bounds in self.segments.items():
            if bounds["min"] <= clv < bounds.get("max", float("inf")):
                return segment
        return "bronze"

    def evaluate(self, train_df: pd.DataFrame, test_df: pd.DataFrame) -> dict[str, Any]:
        """Evaluate CLV predictions against actual future spend."""
        if not self.bgf_params or not self.ggf_params:
            raise RuntimeError("Models not trained yet")

        observation_end = cast(pd.Timestamp, pd.Timestamp(str(train_df["order_date"].max())))
        rfm = self._build_rfm(train_df, observation_end)
        horizon_days = (test_df["order_date"].max() - observation_end).days if len(test_df) > 0 else 1
        predictions = self.predict_clv(rfm, horizon_days=max(horizon_days, 1))

        actual_spend = cast(
            pd.Series,
            test_df.groupby("customer_id")["order_value"].sum(),
        ).to_frame("actual_clv").reset_index()

        eval_df = predictions.merge(actual_spend, on="customer_id", how="inner")
        if eval_df.empty:
            return {
                "mae": float("inf"),
                "median_abs_error": float("inf"),
                "correlation": 0.0,
                "customers_evaluated": 0,
                "segment_distribution": {},
            }

        actual_series = cast(pd.Series, eval_df["actual_clv"])
        pred_series = cast(pd.Series, eval_df["predicted_clv"])
        raw_corr = float(actual_series.corr(pred_series)) if len(eval_df) >= 2 else 0.0
        corr = raw_corr if np.isfinite(raw_corr) else 0.0

        metrics: dict[str, Any] = {
            "mae": float(mean_absolute_error(actual_series, pred_series)),
            "median_abs_error": float(np.median(np.abs(actual_series - pred_series))),
            "correlation": corr,
            "customers_evaluated": len(eval_df),
            "segment_distribution": predictions["segment"].value_counts(normalize=True).to_dict(),
        }
        for segment in self.segments:
            seg_df = eval_df[eval_df["segment"] == segment]
            if len(seg_df) > 0:
                metrics[f"mae_{segment}"] = float(
                    mean_absolute_error(seg_df["actual_clv"], seg_df["predicted_clv"])
                )

        logger.info(
            "Evaluation metrics: %s",
            json.dumps({k: val for k, val in metrics.items() if not isinstance(val, dict)}, indent=2),
        )
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
    passed, _ = check_metric_gates(metrics, gates, _GATE_RULES, log=logger)
    return passed


def main():
    parser = build_training_parser(
        "CLV Prediction BG/NBD + Gamma-Gamma Training",
        "clv-prediction",
        "artifacts/clv-prediction",
    )
    args = parser.parse_args()
    config = load_training_config(args.config)

    trainer = CLVPredictionTrainer(config)
    df = trainer.load_data(config["training"]["query"])

    holdout_days = config["training"].get("holdout_days", 90)
    cutoff = df["order_date"].max() - pd.Timedelta(days=holdout_days)
    train_df = cast(pd.DataFrame, df[df["order_date"] <= cutoff])
    test_df = cast(pd.DataFrame, df[df["order_date"] > cutoff])
    logger.info("Train: %d transactions, Test: %d transactions", len(train_df), len(test_df))

    trainer.train(train_df)
    metrics = trainer.evaluate(train_df, test_df)
    gate_passed = _check_promotion_gates(metrics, config.get("promotion_gates", {}))

    observation_end = cast(pd.Timestamp, pd.Timestamp(str(df["order_date"].max())))
    rfm = trainer._build_rfm(df, observation_end)
    clv_predictions = trainer.predict_clv(rfm, horizon_days=365)
    logger.info("Segment distribution:\n%s", clv_predictions["segment"].value_counts())

    output_dir = Path(args.output_dir) / config["version"]
    trainer.export(str(output_dir))
    clv_predictions.to_parquet(output_dir / "clv_predictions.parquet", index=False)

    log_mlflow_run(
        mlflow,
        experiment=args.experiment,
        run_name=args.run_name,
        params={
            "model_name": config["model_name"],
            "version": config["version"],
            **{f"bgnbd_{k}": v for k, v in trainer.bgf_params.items()},
            **{f"gg_{k}": v for k, v in trainer.ggf_params.items()},
        },
        metrics=metrics,
        gate_passed=gate_passed,
        artifact_dir=output_dir,
        artifact_paths=(args.config,),
    )

    logger.info(
        "Training complete. Gate passed: %s. MAE: %.2f, Correlation: %.4f",
        gate_passed,
        metrics["mae"],
        metrics["correlation"],
    )


if __name__ == "__main__":
    main()
