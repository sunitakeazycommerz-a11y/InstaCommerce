"""ML Model Training — runs daily at 04:00 UTC.
Trains all scheduled models, evaluates, and promotes if gates pass.
"""
import json
import math
from datetime import datetime, timedelta
from typing import Any, Callable, Mapping, Optional

from airflow import DAG
from airflow.operators.python import BranchPythonOperator, PythonOperator
from airflow.utils.task_group import TaskGroup
from plugins.slack_alerts import send_slack_alert

try:
    import mlflow  # type: ignore[import-untyped]
except ImportError:
    mlflow = None  # type: ignore[assignment]

try:
    from google.cloud import aiplatform, storage
except ImportError:
    aiplatform = None  # type: ignore[assignment]
    storage = None  # type: ignore[assignment]

_GateEvaluator = Callable[[Mapping[str, Any], Mapping[str, Any]], tuple[bool, list[str]]]
_shared_evaluate_gates: Optional[_GateEvaluator]
try:
    from ml.eval.metrics import evaluate_promotion_gates as _shared_evaluate_gates
except ImportError:
    _shared_evaluate_gates = None

_METRIC_ALIASES: dict[str, tuple[str, ...]] = {
    "mae_min": ("mae", "mae_minutes"),
    "mae_minutes": ("mae", "mae_min"),
    "mae": ("mae_min", "mae_minutes"),
    "ndcg_at_10": ("ndcg@10",),
    "ndcg@10": ("ndcg_at_10",),
    "hit_at_10": ("hit@10",),
    "hit@10": ("hit_at_10",),
}


def _resolve_fallback_metric(
    metrics: Mapping[str, Any],
    metric_name: str,
    gate_key: str,
) -> Optional[float]:
    candidates: list[str] = [metric_name]
    if gate_key and gate_key not in candidates:
        candidates.append(gate_key)
    candidates.extend(_METRIC_ALIASES.get(metric_name, ()))
    if "_at_" in metric_name:
        candidates.append(metric_name.replace("_at_", "@"))
    if "@" in metric_name:
        candidates.append(metric_name.replace("@", "_at_"))
    for key in candidates:
        val = metrics.get(key)
        if isinstance(val, (int, float)) and not isinstance(val, bool) and math.isfinite(float(val)):
            return float(val)
    return None


def _evaluate_model_gates(metrics: dict, gates: dict) -> bool:
    if _shared_evaluate_gates is not None:
        passed, _ = _shared_evaluate_gates(metrics, gates)
        return passed
    for gate_key, threshold in gates.items():
        if not isinstance(threshold, (int, float)) or isinstance(threshold, bool) or not math.isfinite(float(threshold)):
            continue
        if gate_key.startswith("min_"):
            val = _resolve_fallback_metric(metrics, gate_key[4:], gate_key)
            if val is None or val < float(threshold):
                return False
        elif gate_key.startswith("max_"):
            val = _resolve_fallback_metric(metrics, gate_key[4:], gate_key)
            if val is None or val > float(threshold):
                return False
    return True


def failure_alert(context):
    try:
        send_slack_alert(context)
    except Exception:
        pass

default_args = {
    "owner": "ml-eng",
    "depends_on_past": False,
    "email_on_failure": True,
    "email": ["ml-alerts@instacommerce.com"],
    "retries": 1,
    "retry_delay": timedelta(minutes=15),
    "execution_timeout": timedelta(hours=3),
    "on_failure_callback": failure_alert,
}

PROJECT_ID = "instacommerce-prod"
REGION = "us-central1"
MLFLOW_TRACKING_URI = "https://mlflow.instacommerce.internal"

MODEL_CONFIGS = {
    "demand_forecast": {
        "training_image": f"gcr.io/{PROJECT_ID}/demand-forecast:latest",
        "machine_type": "n1-standard-8",
        "accelerator_type": "NVIDIA_TESLA_T4",
        "accelerator_count": 1,
        "metric": "rmse",
        "threshold": 0.15,
        "direction": "minimize",
        "promotion_gates": {"max_rmse": 0.15, "max_mape": 0.08, "min_accuracy_pct": 92.0},
    },
    "delivery_eta": {
        "training_image": f"gcr.io/{PROJECT_ID}/delivery-eta:latest",
        "machine_type": "n1-standard-4",
        "accelerator_type": None,
        "accelerator_count": 0,
        "metric": "mae_minutes",
        "threshold": 3.0,
        "direction": "minimize",
        "promotion_gates": {"max_mae_minutes": 3.0, "min_within_2min_pct": 0.85},
    },
    "search_ranking": {
        "training_image": f"gcr.io/{PROJECT_ID}/search-ranking:latest",
        "machine_type": "n1-standard-8",
        "accelerator_type": "NVIDIA_TESLA_T4",
        "accelerator_count": 1,
        "metric": "ndcg@10",
        "threshold": 0.75,
        "direction": "maximize",
        "promotion_gates": {"min_ndcg@10": 0.75},
    },
    "fraud_detection": {
        "training_image": f"gcr.io/{PROJECT_ID}/fraud-detection:latest",
        "machine_type": "n1-standard-4",
        "accelerator_type": None,
        "accelerator_count": 0,
        "metric": "f1_score",
        "threshold": 0.90,
        "direction": "maximize",
        "promotion_gates": {"min_f1_score": 0.90, "max_false_positive_rate": 0.05},
    },
}


with DAG(
    dag_id="ml_training",
    default_args=default_args,
    description="Train, evaluate, and promote ML models daily at 04:00 UTC",
    schedule="0 4 * * *",
    start_date=datetime(2026, 1, 1),
    catchup=False,
    tags=["ml", "training", "vertex-ai", "data-platform"],
    max_active_runs=1,
) as dag:

    def _train_model(model_name: str, config: dict, **context):
        """Submit training job to Vertex AI."""
        if aiplatform is None:
            raise ImportError("google.cloud.aiplatform unavailable")

        aiplatform.init(
            project=PROJECT_ID,
            location=REGION,
            experiment=model_name,
        )

        worker_pool_specs = [
            {
                "machine_spec": {
                    "machine_type": config["machine_type"],
                    **(
                        {
                            "accelerator_type": config["accelerator_type"],
                            "accelerator_count": config["accelerator_count"],
                        }
                        if config["accelerator_type"]
                        else {}
                    ),
                },
                "replica_count": 1,
                "container_spec": {
                    "image_uri": config["training_image"],
                    "args": [
                        f"--execution-date={context['ds']}",
                        f"--project={PROJECT_ID}",
                        f"--model-name={model_name}",
                    ],
                },
            }
        ]

        job = aiplatform.CustomJob(
            display_name=f"{model_name}-{context['ds']}",
            worker_pool_specs=worker_pool_specs,
            labels={"model": model_name, "pipeline": "ml-training"},
        )

        job.run(sync=True, restart_job_on_worker_restart=True)

        context["ti"].xcom_push(
            key=f"{model_name}_job_id", value=job.resource_name
        )

    def _evaluate_model(model_name: str, config: dict, **context):
        """Evaluate trained model and log metrics to MLflow."""
        if mlflow is None or storage is None:
            raise ImportError("mlflow or google.cloud.storage unavailable")

        mlflow.set_tracking_uri(MLFLOW_TRACKING_URI)
        mlflow.set_experiment(model_name)

        with mlflow.start_run(run_name=f"{model_name}-{context['ds']}"):
            # Evaluation metrics come from the training job output
            job_id = context["ti"].xcom_pull(
                task_ids=f"{model_name}_group.train_{model_name}",
                key=f"{model_name}_job_id",
            )

            client = storage.Client(project=PROJECT_ID)
            bucket = client.bucket(f"{PROJECT_ID}-ml-artifacts")
            blob = bucket.blob(
                f"{model_name}/{context['ds']}/metrics.json"
            )
            metrics = json.loads(blob.download_as_text())

            mlflow.log_metrics(metrics)
            mlflow.log_param("execution_date", context["ds"])
            mlflow.log_param("job_id", job_id)

            primary_metric = metrics.get(config["metric"])
            context["ti"].xcom_push(
                key=f"{model_name}_primary_metric", value=primary_metric
            )
            context["ti"].xcom_push(
                key=f"{model_name}_metrics", value=metrics
            )

    def _check_promotion_gate(model_name: str, config: dict, **context):
        metrics = context["ti"].xcom_pull(
            task_ids=f"{model_name}_group.evaluate_{model_name}",
            key=f"{model_name}_metrics",
        )
        gates = config.get("promotion_gates")
        if isinstance(metrics, dict) and isinstance(gates, dict) and gates:
            if _evaluate_model_gates(metrics, gates):
                return f"{model_name}_group.promote_{model_name}"
            return f"{model_name}_group.skip_promote_{model_name}"

        primary_metric = context["ti"].xcom_pull(
            task_ids=f"{model_name}_group.evaluate_{model_name}",
            key=f"{model_name}_primary_metric",
        )

        if (
            not isinstance(primary_metric, (int, float))
            or isinstance(primary_metric, bool)
            or not math.isfinite(float(primary_metric))
        ):
            return f"{model_name}_group.skip_promote_{model_name}"

        if config["direction"] == "minimize":
            passes = float(primary_metric) <= config["threshold"]
        else:
            passes = float(primary_metric) >= config["threshold"]

        if passes:
            return f"{model_name}_group.promote_{model_name}"
        return f"{model_name}_group.skip_promote_{model_name}"

    def _promote_model(model_name: str, **context):
        """Promote model to production serving endpoint."""
        if aiplatform is None:
            raise ImportError("google.cloud.aiplatform unavailable")

        aiplatform.init(project=PROJECT_ID, location=REGION)

        model = aiplatform.Model.upload(
            display_name=f"{model_name}-{context['ds']}",
            artifact_uri=f"gs://{PROJECT_ID}-ml-artifacts/{model_name}/{context['ds']}/model",
            serving_container_image_uri=f"gcr.io/{PROJECT_ID}/{model_name}-serving:latest",
            labels={"model": model_name, "promoted_date": context["ds"]},
        )

        endpoint = aiplatform.Endpoint.list(
            filter=f'display_name="{model_name}-endpoint"',
        )[0]

        endpoint.deploy(
            model=model,
            traffic_percentage=100,
            machine_type="n1-standard-4",
            min_replica_count=1,
            max_replica_count=3,
        )

    def _skip_promote(model_name: str, **context):
        """Log that model did not pass quality gates."""
        metric_val = context["ti"].xcom_pull(
            task_ids=f"{model_name}_group.evaluate_{model_name}",
            key=f"{model_name}_primary_metric",
        )
        msg = (
            f":no_entry: *Model `{model_name}` not promoted*\n"
            f"• Metric value: `{metric_val}`\n"
            f"• Did not pass quality gate."
        )
        send_slack_alert(context, message=msg, level="warning")

    for model_name, config in MODEL_CONFIGS.items():
        with TaskGroup(group_id=f"{model_name}_group") as model_group:

            train = PythonOperator(
                task_id=f"train_{model_name}",
                python_callable=_train_model,
                op_kwargs={"model_name": model_name, "config": config},
                provide_context=True,
            )

            evaluate = PythonOperator(
                task_id=f"evaluate_{model_name}",
                python_callable=_evaluate_model,
                op_kwargs={"model_name": model_name, "config": config},
                provide_context=True,
            )

            gate = BranchPythonOperator(
                task_id=f"gate_{model_name}",
                python_callable=_check_promotion_gate,
                op_kwargs={"model_name": model_name, "config": config},
                provide_context=True,
            )

            promote = PythonOperator(
                task_id=f"promote_{model_name}",
                python_callable=_promote_model,
                op_kwargs={"model_name": model_name},
                provide_context=True,
            )

            skip = PythonOperator(
                task_id=f"skip_promote_{model_name}",
                python_callable=_skip_promote,
                op_kwargs={"model_name": model_name},
                provide_context=True,
            )

            train >> evaluate >> gate >> [promote, skip]
