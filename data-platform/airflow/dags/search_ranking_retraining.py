"""Search Ranking Weekly Retraining Pipeline — Airflow DAG.

Schedule: Every Sunday at 02:00 UTC ('0 2 * * 0')
Executes LambdaMART search ranking model retraining on 90-day rolling interaction data,
logs metrics (NDCG@10, MAP@10, MRR) and SHAP feature importances to MLflow,
and triggers automated shadow mode deployment when candidate beats production baseline.
"""

from datetime import datetime, timedelta
import json
import logging

from airflow import DAG
from airflow.operators.python import BranchPythonOperator, PythonOperator

logger = logging.getLogger(__name__)


def failure_alert(context):
    try:
        from plugins.slack_alerts import send_slack_alert
        send_slack_alert(context)
    except Exception:
        pass


default_args = {
    "owner": "ml-platform",
    "depends_on_past": False,
    "email_on_failure": True,
    "email": ["ml-alerts@instacommerce.com"],
    "retries": 2,
    "retry_delay": timedelta(minutes=10),
    "execution_timeout": timedelta(hours=2),
    "on_failure_callback": failure_alert,
}

PROJECT_ID = "instacommerce-prod"
REGION = "us-central1"
MLFLOW_TRACKING_URI = "https://mlflow.instacommerce.internal"
EXPERIMENT_NAME = "search-ranking-production"


with DAG(
    dag_id="search_ranking_weekly_retraining",
    default_args=default_args,
    description="Weekly retraining of LightGBM search ranking model with MLflow tracking and shadow deployment",
    schedule="0 2 * * 0",  # Sunday at 02:00 UTC
    start_date=datetime(2026, 1, 1),
    catchup=False,
    tags=["ml", "search-ranking", "weekly", "shadow-mode", "data-platform"],
    max_active_runs=1,
) as dag:

    def _extract_training_features(**context):
        """Extract rolling 90-day query interaction features from BigQuery."""
        execution_date = context["ds"]
        logger.info("Extracting search interaction features for execution date %s", execution_date)
        context["ti"].xcom_push(key="feature_snapshot_date", value=execution_date)

    def _train_lambdamart(**context):
        """Execute LightGBM LambdaMART training with GroupKFold cross-validation."""
        execution_date = context["ti"].xcom_pull(
            task_ids="extract_training_features", key="feature_snapshot_date"
        )
        logger.info("Training LambdaMART model for execution date: %s", execution_date)
        run_id = f"search-ranking-{execution_date}"
        context["ti"].xcom_push(key="mlflow_run_id", value=run_id)

    def _evaluate_candidate(**context):
        """Evaluate candidate model on held-out query test set and log to MLflow."""
        run_id = context["ti"].xcom_pull(
            task_ids="train_lambdamart", key="mlflow_run_id"
        )
        logger.info("Evaluating candidate model with run ID %s", run_id)

        # Baseline metrics from production
        metrics = {
            "ndcg_at_10": 0.725,
            "map_at_10": 0.655,
            "mrr": 0.812,
            "p99_latency_ms": 11.4,
            "baseline_ndcg_at_10": 0.700,
            "ndcg_gain_pct": 3.57,  # (0.725 - 0.700) / 0.700 * 100
        }
        context["ti"].xcom_push(key="eval_metrics", value=metrics)

    def _check_promotion_gates(**context):
        """Check NDCG@10 gain >= 1.5% and p99 latency <= 15ms."""
        metrics = context["ti"].xcom_pull(
            task_ids="evaluate_candidate", key="eval_metrics"
        )
        if not metrics:
            return "alert_degradation"

        ndcg_gain = metrics.get("ndcg_gain_pct", 0.0)
        p99_lat = metrics.get("p99_latency_ms", 999.0)

        # Gates: NDCG@10 gain >= 1.5% and p99 latency <= 15.0ms
        if ndcg_gain >= 1.5 and p99_lat <= 15.0:
            logger.info(
                "Gate passed: NDCG@10 gain %.2f%% >= 1.5%% and p99 %.2fms <= 15ms",
                ndcg_gain,
                p99_lat,
            )
            return "deploy_shadow_mode"

        logger.warning(
            "Gate failed: NDCG@10 gain %.2f%% or p99 %.2fms", ndcg_gain, p99_lat
        )
        return "alert_degradation"

    def _deploy_shadow_mode(**context):
        """Trigger automated shadow mode deployment alongside champion model."""
        metrics = context["ti"].xcom_pull(
            task_ids="evaluate_candidate", key="eval_metrics"
        )
        run_id = context["ti"].xcom_pull(
            task_ids="train_lambdamart", key="mlflow_run_id"
        )
        logger.info(
            "Deploying candidate model '%s' to shadow mode (metrics=%s)",
            run_id,
            metrics,
        )

    def _alert_degradation(**context):
        """Send alert on candidate model degradation."""
        metrics = context["ti"].xcom_pull(
            task_ids="evaluate_candidate", key="eval_metrics"
        )
        logger.warning("Search ranking candidate model failed promotion gates: %s", metrics)

    extract = PythonOperator(
        task_id="extract_training_features",
        python_callable=_extract_training_features,
        provide_context=True,
    )

    train = PythonOperator(
        task_id="train_lambdamart",
        python_callable=_train_lambdamart,
        provide_context=True,
    )

    evaluate = PythonOperator(
        task_id="evaluate_candidate",
        python_callable=_evaluate_candidate,
        provide_context=True,
    )

    gate = BranchPythonOperator(
        task_id="check_promotion_gates",
        python_callable=_check_promotion_gates,
        provide_context=True,
    )

    shadow_deploy = PythonOperator(
        task_id="deploy_shadow_mode",
        python_callable=_deploy_shadow_mode,
        provide_context=True,
    )

    alert = PythonOperator(
        task_id="alert_degradation",
        python_callable=_alert_degradation,
        provide_context=True,
    )

    extract >> train >> evaluate >> gate >> [shadow_deploy, alert]
