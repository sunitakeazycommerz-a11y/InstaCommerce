# ML Platform Productionization Guide — InstaCommerce Q-Commerce

**Iteration:** 3  
**Status:** Implementation guide  
**Audience:** ML Platform, Data Engineering, SRE, Service Owners, Principal Engineers  
**Scope:** Feature and model lineage, offline-online consistency, training/eval gates, shadow mode, promotion, rollback, drift monitoring, and model ownership — all grounded in the existing `ml/`, `services/ai-inference-service/`, `services/ai-orchestrator-service/`, and `data-platform/` codebase.

**Companion reviews:**
- `docs/reviews/PRINCIPAL-ENGINEERING-REVIEW-ITERATION-3-2026-03-06.md` (§4.6, §5 P1 ML items)
- `docs/reviews/PRINCIPAL-ENGINEERING-IMPLEMENTATION-GUIDE-PLATFORM-WISE-2026-03-06.md` (§11)
- `docs/reviews/data-platform-ml-design.md` (§5, §6, §7)
- `docs/reviews/ITER3-DATAFLOW-PLATFORM-DIAGRAMS-2026.md`

---

## 0. What the codebase actually has vs. what production requires

Before prescribing fixes, it is useful to state clearly what exists and what is missing. The scaffolding is genuinely solid; the gaps are in operational plumbing that connects the scaffolding to reality.

| Capability | What exists today | What is missing |
|---|---|---|
| Model registry | `ml/serving/model_registry.py` — in-process dict + kill switch + A/B weights | Durable version index; no persistence across restarts; no lineage to training run |
| Eval gates | `ml/eval/evaluate.py` — `min_*`/`max_*` threshold checks against local metrics | Not called from the Airflow DAG `ml_training.py` gate task; DAG checks only one primary metric |
| Shadow mode | `ml/serving/shadow_mode.py` — async thread pool, 1 s timeout, 10 % tolerance | Agreement state is in-process only; no persistence; agreement rate is never written to a durable store; no automatic promotion trigger |
| Drift monitoring | `ml/serving/monitoring.py` — PSI over a 1-D feature array | PSI is per-call and never aggregated; no scheduled batch PSI job; no per-feature baseline written at training time |
| Feature lineage | `ml/feature_store/feature_views/*.yaml` reference `model_refs` | Feature-to-training-run linkage is not machine-readable; no lineage graph; no version pinning between feature view version and model artifact |
| Offline-online consistency | Feature SQL under `ml/feature_store/sql/` writes to BigQuery; `ml_feature_refresh` pushes to Vertex AI online store | No hash or row-count reconciliation between offline snapshot used for training and the online values served at inference; no skew test |
| Promotion | `ml_training.py` `_promote_model()` deploys to Vertex AI endpoint with `traffic_percentage=100` | Zero-traffic canary step; no shadow gate check; no gradual rollout; rollback is not scriptable |
| Rollback | Kill switch in `model_registry.py` forces `DEGRADED` → rule-based fallback | No rollback to a previous ONNX artifact; kill switch kills ML entirely, not to a prior version |
| Model ownership | `owner:` fields in feature view YAMLs and model card template | Not enforced; no `CODEOWNERS` entries; no runbook reference per model |

The following sections give concrete implementation steps that fix each gap against the existing code paths.

---

## 1. Feature and model lineage

### 1.1 The lineage problem in q-commerce

The six InstaCommerce models (ETA, Fraud, Search Ranking, Demand Forecast, Personalization, CLV) each depend on features that are computed in multiple places: BigQuery batch SQL under `ml/feature_store/sql/`, Beam streaming pipelines under `data-platform/streaming/pipelines/`, and the `ml_feature_refresh` Airflow DAG. When a model gives wrong predictions — and in q-commerce the consequences are a misquoted ETA, a falsely-blocked order, or a misranked search result — the first question ops asks is "what feature values did this prediction use, and where were they computed?" Without lineage that question is unanswerable within an SLA.

### 1.2 Lineage record structure

Add a `lineage` block to every `ml/train/*/config.yaml`. The goal is to make the training job itself emit a machine-readable record that ties:
- the training dataset (BigQuery table + snapshot timestamp)
- the feature view version (YAML file SHA + semantic version)
- the model artifact URI (GCS path)
- the MLflow run ID
- the Airflow DAG run ID

```yaml
# ml/train/eta_prediction/config.yaml — add this block
lineage:
  feature_view: ml/feature_store/feature_views/eta_prediction_view.yaml
  feature_view_version: "1.2.0"          # bump on any feature addition/removal
  training_dataset_table: analytics.training_delivery_times
  snapshot_strategy: point_in_time       # alternatives: latest, fixed_date
  # Populated at runtime by the training script:
  training_run_id: ""                    # MLflow run ID written here
  training_dag_run_id: ""               # Airflow run_id XCommed in
  artifact_gcs_uri: ""                   # gs://instacommerce-ml/...
  feature_sha: ""                        # SHA256 of the feature view YAML
```

### 1.3 Training script lineage emission

Extend every `ml/train/*/train.py` to write the lineage record before registering the model. The pattern below applies to all six training scripts:

```python
# ml/train/eta_prediction/train.py  (add after model.fit)
import hashlib, json, os
from pathlib import Path

def _emit_lineage(config: dict, run_id: str, artifact_uri: str) -> dict:
    feature_view_path = Path(config["lineage"]["feature_view"])
    feature_sha = hashlib.sha256(feature_view_path.read_bytes()).hexdigest()

    lineage = {
        "model_name": config["model_name"],
        "version": config["version"],
        "feature_view": str(feature_view_path),
        "feature_view_version": config["lineage"]["feature_view_version"],
        "feature_sha": feature_sha,
        "training_dataset_table": config["lineage"]["training_dataset_table"],
        "snapshot_strategy": config["lineage"]["snapshot_strategy"],
        "training_run_id": run_id,
        "training_dag_run_id": os.getenv("AIRFLOW_RUN_ID", "local"),
        "artifact_gcs_uri": artifact_uri,
        "trained_at": datetime.utcnow().isoformat(),
    }

    # Write to GCS alongside the model artifact
    from google.cloud import storage
    bucket = storage.Client().bucket("instacommerce-ml-artifacts")
    blob = bucket.blob(f"{config['model_name']}/{config['version']}/lineage.json")
    blob.upload_from_string(json.dumps(lineage, indent=2))

    # Also log to MLflow
    mlflow.log_dict(lineage, "lineage.json")
    return lineage
```

### 1.4 Prediction-time lineage tagging

Every `PredictionResult` already carries `model_version` (see `ml/serving/predictor.py`). Extend the result to carry the lineage reference so that downstream consumers and logs can look up the full provenance:

```python
# ml/serving/predictor.py — extend PredictionResult
@dataclass
class PredictionResult:
    output: Dict[str, Any]
    model_version: str
    model_name: str
    latency_ms: float
    is_fallback: bool = False
    feature_importance: Optional[Dict[str, float]] = None
    # ADD: lineage reference
    lineage_uri: Optional[str] = None   # gs://instacommerce-ml-artifacts/<model>/<ver>/lineage.json
    feature_view_version: Optional[str] = None
```

The `BasePredictor._load_model` implementations should read `lineage.json` from GCS on startup and store `lineage_uri` and `feature_view_version` on the predictor instance.

### 1.5 Feature view versioning protocol

Feature view YAMLs under `ml/feature_store/feature_views/` must be versioned with semantic version bumps tied to breaking vs. non-breaking changes:

| Change type | Version bump | Required action |
|---|---|---|
| Add new optional feature | minor (1.x.0 → 1.x+1.0) | Retrain within 30 days |
| Remove or rename feature | major (1.x.0 → 2.0.0) | Immediately retrain and replace; old version archived |
| Change feature dtype | major | Same as remove |
| Change SQL computation logic (same name) | minor | Retrain; log skew check against old values |
| Change TTL or serving config only | patch | No retrain needed |

When a major version bump occurs, the old feature view YAML is kept as `eta_prediction_view.v1.yaml` (archived, read-only) to support rollback and post-hoc debugging of historical predictions.

### 1.6 Lineage in the Airflow DAG

The `ml_training` DAG (`data-platform/airflow/dags/ml_training.py`) must pass the DAG run ID into the training container and capture the emitted lineage URI:

```python
# ml_training.py — extend _train_model worker_pool_specs.args
"args": [
    f"--execution-date={context['ds']}",
    f"--project={PROJECT_ID}",
    f"--model-name={model_name}",
    f"--airflow-run-id={context['run_id']}",   # ADD
],
```

And after `_promote_model` succeeds, XCom the lineage URI:

```python
def _promote_model(model_name: str, **context):
    # ... existing Vertex AI upload ...
    lineage_uri = f"gs://{PROJECT_ID}-ml-artifacts/{model_name}/{context['ds']}/lineage.json"
    context["ti"].xcom_push(key=f"{model_name}_lineage_uri", value=lineage_uri)
```

---

## 2. Offline-online consistency

### 2.1 The skew problem in q-commerce

Training-serving skew is the single most common cause of silent model degradation in production. For InstaCommerce it takes two forms:

1. **Feature value skew:** The SQL in `ml/feature_store/sql/` that computed the training data is not exactly the same computation used to populate the Vertex AI online store (pushed by `ml_feature_refresh`). Even a minor difference — e.g., `INTERVAL 30 DAY` vs. `INTERVAL 30 * 24 HOUR` in different time zones — produces features the model has never trained on.

2. **Point-in-time (PIT) skew:** Training uses historical snapshots with PIT joins to prevent label leakage. Serving uses the latest values. If the PIT logic is different from the serving logic, the model sees a different feature distribution at training vs. inference.

### 2.2 Single-source SQL principle

The authoritative feature computation must live in **one** place and be executed by both the training pipeline and the online refresh pipeline. The recommended layout:

```
ml/feature_store/sql/
  user_features.sql          ← canonical SQL, used for BOTH batch training and online refresh
  store_features.sql
  product_features.sql
  rider_features.sql
  search_features.sql

ml/feature_store/sql/pit/
  user_features_pit.sql      ← PIT wrapper for training (adds AS_OF_TIMESTAMP join)
  rider_features_pit.sql
```

The PIT wrapper calls the canonical SQL and adds a `WHERE event_time <= :as_of_ts` predicate rather than duplicating the aggregation logic. The online refresh calls the canonical SQL with `WHERE event_time <= CURRENT_TIMESTAMP()`.

### 2.3 Consistency validation job

Add a post-refresh validation task to `ml_feature_refresh.py` that checks a statistical sample of entity-level feature values between the offline BigQuery snapshot and the online Redis/Vertex AI store:

```python
# data-platform/airflow/dags/ml_feature_refresh.py — add after push_to_online_store

def _validate_online_offline_consistency(feature_name: str, sample_size: int = 500, **context):
    """
    For 500 randomly sampled entity IDs, compare the value in BigQuery (offline)
    vs. the value in the Vertex AI online store.  Fails the DAG if:
    - Mean absolute relative error (MARE) > 1% for any continuous feature
    - Exact-match rate < 99% for any categorical feature
    """
    from google.cloud import aiplatform, bigquery

    bq_client = bigquery.Client(project=PROJECT_ID)
    aiplatform.init(project=PROJECT_ID, location=FEATURE_STORE_REGION)

    # Sample entity IDs from BigQuery
    sample_query = f"""
        SELECT entity_id
        FROM `{PROJECT_ID}.features.{feature_name}`
        ORDER BY RAND()
        LIMIT {sample_size}
    """
    entity_ids = [row["entity_id"] for row in bq_client.query(sample_query).result()]

    # Fetch offline values from BigQuery
    offline_query = f"""
        SELECT * FROM `{PROJECT_ID}.features.{feature_name}`
        WHERE entity_id IN UNNEST(@entity_ids)
    """
    job_config = bigquery.QueryJobConfig(
        query_parameters=[bigquery.ArrayQueryParameter("entity_ids", "STRING", entity_ids)]
    )
    offline_rows = {r["entity_id"]: dict(r) for r in bq_client.query(offline_query, job_config=job_config).result()}

    # Fetch online values from Vertex AI
    entity_type = aiplatform.featurestore.EntityType(
        entity_type_name=feature_name,
        featurestore_id=FEATURE_STORE_ID,
    )
    online_rows = entity_type.read(entity_ids=entity_ids)

    skew_violations = []
    for entity_id in entity_ids:
        offline = offline_rows.get(entity_id, {})
        online = online_rows.get(entity_id, {})
        for col, offline_val in offline.items():
            if col in ("entity_id", "_updated_at"):
                continue
            online_val = online.get(col)
            if isinstance(offline_val, float) and offline_val != 0:
                mare = abs(offline_val - (online_val or 0)) / abs(offline_val)
                if mare > 0.01:
                    skew_violations.append(
                        f"{feature_name}.{col}: offline={offline_val:.4f} online={online_val} MARE={mare:.4f}"
                    )
            elif offline_val != online_val:
                skew_violations.append(
                    f"{feature_name}.{col}: offline={offline_val!r} online={online_val!r}"
                )

    if skew_violations:
        raise ValueError(
            f"Offline-online skew detected in {feature_name}:\n" + "\n".join(skew_violations[:20])
        )

    context["ti"].xcom_push(
        key=f"{feature_name}_consistency_ok", value=True
    )
```

Wire this as a task group after `push_to_online_store` and before `validate_freshness`.

### 2.4 Feature hash pinning for training

At training time, before fetching the training dataset from BigQuery, write a SHA256 hash of the feature SQL files that will be used. Store this hash in the lineage record. At serving time, if the hash of the currently-deployed SQL does not match the hash in the model's lineage record, emit a `WARNING` log and increment a `ml_feature_sql_hash_mismatch_total` counter. This gives ops a lagging indicator of drift between the SQL used to train a model and the SQL now serving it.

```python
# ml/serving/predictor.py — add to BasePredictor.__init__
import hashlib
from pathlib import Path

def _compute_feature_sql_hash(feature_sql_paths: list[str]) -> str:
    h = hashlib.sha256()
    for path in sorted(feature_sql_paths):
        h.update(Path(path).read_bytes())
    return h.hexdigest()
```

### 2.5 Streaming feature consistency (Beam pipelines)

The Beam pipelines in `data-platform/streaming/pipelines/` compute real-time features (e.g., `zone_avg_delivery_time_1h` for ETA, `orders_last_1h` for fraud) that feed the online store but are **not** reproduced in the batch training SQL. This is a form of training-serving skew that is invisible at feature view level.

**Fix:** For each streaming-computed feature used in model training, add a `source: streaming` annotation to the feature group YAML and ensure the training SQL uses a BigQuery Materialized View that is the historical equivalent of the streaming window:

```yaml
# ml/feature_store/feature_groups/store_features.yaml — add annotation
features:
  - name: zone_avg_delivery_time_1h
    dtype: float64
    source: streaming                         # ADD: tells training pipeline to use MV
    training_materialized_view: analytics.mv_zone_delivery_time_1h
    description: "1-hour rolling avg delivery time in zone (Beam window)"
```

---

## 3. Training and evaluation gates

### 3.1 Current gate gap

`ml/eval/evaluate.py` (`ModelEvaluator._check_gates`) implements a solid `min_*`/`max_*` threshold system and is well-tested. The problem is that the Airflow DAG `ml_training.py` does **not** call `ModelEvaluator`. It defines its own single-metric check in `_check_promotion_gate()` that reads one `config["metric"]` + `config["threshold"]`. The multi-metric gates defined in each `ml/train/*/config.yaml` `promotion_gates` block are therefore never evaluated by the automated pipeline.

### 3.2 DAG gate task — wire `ModelEvaluator`

Replace `_check_promotion_gate` in `ml_training.py` with a call to the shared evaluator:

```python
# data-platform/airflow/dags/ml_training.py

def _check_promotion_gate(model_name: str, config: dict, **context):
    """Gate task: run ModelEvaluator against all promotion_gates in config.yaml."""
    import json
    from google.cloud import storage

    PROJECT_ID = "instacommerce-prod"

    # Load full config from the training image's config.yaml (stored alongside artifact)
    gcs_client = storage.Client(project=PROJECT_ID)
    bucket = gcs_client.bucket(f"{PROJECT_ID}-ml-artifacts")
    gates_blob = bucket.blob(f"{model_name}/{context['ds']}/config.yaml")
    import yaml
    full_config = yaml.safe_load(gates_blob.download_as_text())

    # Load metrics emitted by training job
    metrics_blob = bucket.blob(f"{model_name}/{context['ds']}/metrics.json")
    metrics = json.loads(metrics_blob.download_as_text())

    # Re-use ModelEvaluator gate logic (pure function, no model object needed)
    from ml.eval.evaluate import ModelEvaluator
    evaluator = ModelEvaluator()
    passed = evaluator._check_gates(metrics, full_config.get("promotion_gates", {}))

    # Also enforce minimum improvement over current production model
    min_improvement_pct = full_config.get("promotion_gates", {}).get("min_improvement_pct", 0.0)
    if min_improvement_pct > 0.0:
        champion_metrics_blob = bucket.blob(f"{model_name}/champion/metrics.json")
        if champion_metrics_blob.exists():
            champion_metrics = json.loads(champion_metrics_blob.download_as_text())
            primary_metric = config["metric"]
            champion_val = champion_metrics.get(primary_metric)
            challenger_val = metrics.get(primary_metric)
            if champion_val and challenger_val:
                direction = config["direction"]
                if direction == "minimize":
                    improvement = (champion_val - challenger_val) / champion_val * 100
                else:
                    improvement = (challenger_val - champion_val) / champion_val * 100
                if improvement < min_improvement_pct:
                    passed = False

    context["ti"].xcom_push(key=f"{model_name}_gates_passed", value=passed)
    context["ti"].xcom_push(key=f"{model_name}_metrics", value=metrics)

    if passed:
        return f"{model_name}_group.promote_{model_name}"
    return f"{model_name}_group.skip_promote_{model_name}"
```

### 3.3 Gate definitions per model

The existing `config.yaml` gate blocks are already well-structured. The only additions needed are:

**ETA (`ml/train/eta_prediction/config.yaml`):**
```yaml
promotion_gates:
  max_mae_min: 1.5                  # already exists
  min_within_2min_pct: 0.85         # already exists
  min_improvement_pct: 5.0          # already exists
  max_p95_latency_ms: 10.0          # ADD: serving latency SLA gate
  max_regression_pct: 0.0           # ADD: zero regressions allowed on held-out Q-commerce segments
```

**Fraud (`ml/train/fraud_detection/config.yaml`):**
```yaml
promotion_gates:
  min_auc_roc: 0.98                 # already exists
  min_precision_at_95_recall: 0.70  # already exists
  max_false_positive_rate: 0.05     # already exists
  min_improvement_pct: 1.0          # already exists
  max_auc_regression_vs_champion: 0.005  # ADD: block if AUC drops vs champion
  bias_check_required: true         # ADD: bias gate must pass
```

**Search Ranking (`ml/train/search_ranking/config.yaml`):**
```yaml
promotion_gates:
  min_ndcg_at_10: 0.65              # already exists
  min_improvement_pct: 2.0          # already exists
  min_mrr: 0.45                     # ADD
  min_hit_at_10: 0.80               # ADD
  max_null_result_rate: 0.05        # ADD: < 5% of queries must return zero results after ranking
```

### 3.4 Shadow-based gate (gating on live agreement, not just offline metrics)

A model that passes offline gates can still behave incorrectly on the live distribution. The promotion gate must also pass the shadow agreement check before hard deployment. This is the **champion/challenger gate**:

```
Offline gates pass ──► Enter Shadow ──► Shadow agreement ≥ 95% for 48h ──► Canary 5% ──► Full promotion
                                │
                                └──► Shadow agreement < 95% ──► Skip promote, investigate
```

Implementation: after `_promote_model` currently does `traffic_percentage=100`, change to first register the new model at 0% traffic and promote to shadow status only. The shadow agreement check (§4 below) then governs whether to proceed to canary.

### 3.5 Bias gate integration

`ml/eval/evaluate.py` already implements `ModelEvaluator.bias_check()` with demographic parity, equalized odds, and disparate impact (4/5 rule). The bias check is enabled via `--bias-check` CLI flag but is never called from the Airflow pipeline. For fraud detection and personalization models (which carry disparate-impact risk), add a bias gate task to the DAG:

```python
# ml_training.py — add for fraud and personalization models only
def _run_bias_gate(model_name: str, protected_attrs: list[str], **context):
    gates_passed = context["ti"].xcom_pull(
        task_ids=f"{model_name}_group.gate_{model_name}",
        key=f"{model_name}_gates_passed",
    )
    if not gates_passed:
        return  # Already blocked

    # Load test data and model artifact, run bias check
    from ml.eval.evaluate import ModelEvaluator
    evaluator = ModelEvaluator()
    # ... load model and test_data from GCS ...
    bias_report = evaluator.bias_check(model, test_data, protected_attributes=protected_attrs)
    if not bias_report.passed:
        context["ti"].xcom_push(key=f"{model_name}_gates_passed", value=False)
        # Alert
        from plugins.slack_alerts import send_slack_alert
        send_slack_alert(context, message=f"Bias gate failed for {model_name}: {bias_report.details}", level="warning")
```

---

## 4. Shadow mode — making it durable and promotion-governing

### 4.1 Current shadow mode gap

`ml/serving/shadow_mode.py` is architecturally correct: it runs the shadow predictor in a thread pool with a 1 s timeout, computes agreement (10% relative tolerance), and logs structured comparisons. The critical gap is that `_comparison_count` and `_agreement_count` are **in-process variables**. They reset on every service restart. The 48-hour agreement-rate window mentioned in the README has no durable backing store. The agreement rate can never actually govern an automated promotion because there is no persistent state to query.

### 4.2 Durable shadow state in Redis

Extend `ShadowRunner` to write comparison counts to Redis using atomic INCR, so agreement state survives restarts and can be queried by a promotion checker job:

```python
# ml/serving/shadow_mode.py — add Redis backing

import redis

class ShadowRunner:
    def __init__(
        self,
        timeout_s: float = _SHADOW_TIMEOUT_S,
        redis_client: Optional[redis.Redis] = None,
    ) -> None:
        self._timeout_s = timeout_s
        self._comparison_count = 0
        self._agreement_count = 0
        self._redis = redis_client  # None = in-process only (test mode)

    def _persist_comparison(self, model_name: str, shadow_version: str, agrees: bool) -> None:
        """Persist comparison result to Redis for durable agreement tracking."""
        if self._redis is None:
            return
        key_prefix = f"shadow:{model_name}:{shadow_version}"
        pipe = self._redis.pipeline()
        pipe.incr(f"{key_prefix}:total")
        if agrees:
            pipe.incr(f"{key_prefix}:agreed")
        # Use sorted set with UNIX timestamp as score for time-windowed queries
        import time
        ts = time.time()
        pipe.zadd(f"{key_prefix}:timeline", {f"{ts}:{int(agrees)}": ts})
        # Expire timeline entries older than 7 days
        pipe.zremrangebyscore(f"{key_prefix}:timeline", 0, ts - 7 * 86400)
        pipe.execute()

    def get_windowed_agreement_rate(
        self, model_name: str, shadow_version: str, window_hours: float = 48.0
    ) -> Optional[float]:
        """Return agreement rate over the last `window_hours` from Redis."""
        if self._redis is None:
            return self.agreement_rate
        import time
        key = f"shadow:{model_name}:{shadow_version}:timeline"
        cutoff = time.time() - window_hours * 3600
        entries = self._redis.zrangebyscore(key, cutoff, "+inf")
        if not entries:
            return None
        total = len(entries)
        agreed = sum(1 for e in entries if e.decode().endswith(":1"))
        return agreed / total
```

### 4.3 Shadow metrics in Prometheus

Extend `ModelMonitor` to expose shadow agreement as a Prometheus gauge so Grafana can alert on agreement drops:

```python
# ml/serving/monitoring.py — add shadow metrics
self.shadow_agreement_rate: Gauge = Gauge(
    "model_shadow_agreement_rate",
    "Shadow vs. production agreement rate (rolling 48h)",
    labelnames=["model", "shadow_version"],
    namespace="ml",
)
self.shadow_latency_overhead: Histogram = Histogram(
    "model_shadow_latency_overhead_ms",
    "Latency overhead of shadow inference in milliseconds",
    labelnames=["model"],
    buckets=(1, 5, 10, 25, 50, 100, 250, 500, 1000),
    namespace="ml",
)
```

### 4.4 Shadow sample routing (not all traffic)

Running shadow inference on every request adds CPU and memory pressure. For high-throughput models (ETA at ~50K req/min peak, fraud at ~30K req/min), sample shadow traffic:

```python
# ml/serving/shadow_mode.py — add sampling
class ShadowRunner:
    def __init__(self, timeout_s=1.0, sample_rate=0.10, redis_client=None):
        self._sample_rate = sample_rate  # 10% of traffic by default

    def run_shadow(self, production_result, shadow_predictor, features):
        import random
        if random.random() > self._sample_rate:
            return None  # Skip shadow for this request
        # ... rest of existing code ...
```

Set shadow sample rates by model:

| Model | Shadow sample rate | Rationale |
|---|---|---|
| ETA | 10% | High volume; 10% gives >300K comparisons/day |
| Fraud | 100% | Low volume; safety-critical; must see every case |
| Search Ranking | 5% | Very high volume; 5% still gives statistical power |
| Demand Forecast | N/A | Offline batch model; shadow via offline replay |
| Personalization | 10% | High volume; 10% sufficient |
| CLV | N/A | Offline batch; shadow via offline replay |

### 4.5 Automated shadow promotion trigger

Add a Cloud Scheduler job (or an Airflow sensor task) that polls the Redis shadow state every 6 hours and promotes to canary when the 48-hour agreement rate exceeds the threshold:

```python
# data-platform/airflow/dags/ml_training.py — add shadow promotion sensor

def _check_shadow_agreement(model_name: str, **context):
    """Poll Redis for 48h shadow agreement. If >= 95%, proceed to canary."""
    import redis
    r = redis.Redis.from_url(os.environ["REDIS_URL"])
    shadow_version = context["ti"].xcom_pull(
        task_ids=f"{model_name}_group.promote_{model_name}",
        key=f"{model_name}_shadow_version",
    )
    if not shadow_version:
        return f"{model_name}_group.skip_canary_{model_name}"

    import time
    cutoff = time.time() - 48 * 3600
    entries = r.zrangebyscore(
        f"shadow:{model_name}:{shadow_version}:timeline", cutoff, "+inf"
    )
    if len(entries) < 1000:  # Minimum 1000 comparisons required
        return f"{model_name}_group.shadow_pending_{model_name}"

    agreed = sum(1 for e in entries if e.decode().endswith(":1"))
    rate = agreed / len(entries)
    context["ti"].xcom_push(key=f"{model_name}_shadow_agreement", value=rate)

    if rate >= 0.95:
        return f"{model_name}_group.canary_{model_name}"
    return f"{model_name}_group.skip_canary_{model_name}"
```

---

## 5. Promotion — canary-first, not instant

### 5.1 Current promotion gap

`_promote_model()` in `ml_training.py` calls `endpoint.deploy(traffic_percentage=100)`. This is a hard cutover. For a q-commerce platform with 500K daily orders, a defective model promotion at 100% traffic can affect all users before the error rate alarm fires. The promotion should follow the same principles as a service rollout: canary first.

### 5.2 Promotion stages

```
Shadow (0% serving) ──[48h, ≥95% agreement]──► Canary 5% ──[4h, SLOs green]──► 50% ──[2h]──► 100%
                                                     │
                                                     └──[SLO breach]──► Auto-rollback to previous champion
```

### 5.3 Canary promotion implementation

Replace the current `_promote_model` with a three-task sequence: shadow register → canary → full promote:

```python
# ml_training.py — revised promotion sequence

def _register_shadow_version(model_name: str, **context):
    """Upload model to Vertex AI at 0% traffic (shadow position)."""
    from google.cloud import aiplatform
    aiplatform.init(project=PROJECT_ID, location=REGION)

    model = aiplatform.Model.upload(
        display_name=f"{model_name}-{context['ds']}-shadow",
        artifact_uri=f"gs://{PROJECT_ID}-ml-artifacts/{model_name}/{context['ds']}/model",
        serving_container_image_uri=f"gcr.io/{PROJECT_ID}/{model_name}-serving:latest",
        labels={"model": model_name, "stage": "shadow", "date": context["ds"]},
    )
    endpoint = aiplatform.Endpoint.list(filter=f'display_name="{model_name}-endpoint"')[0]
    # Deploy at 0% traffic alongside existing production model
    endpoint.deploy(model=model, traffic_percentage=0, machine_type="n1-standard-4")

    context["ti"].xcom_push(key=f"{model_name}_shadow_model_id", value=model.resource_name)
    context["ti"].xcom_push(key=f"{model_name}_shadow_version", value=context["ds"])


def _canary_promote(model_name: str, canary_pct: int = 5, **context):
    """Shift 5% of traffic to the shadow model (canary phase)."""
    from google.cloud import aiplatform
    aiplatform.init(project=PROJECT_ID, location=REGION)

    shadow_model_id = context["ti"].xcom_pull(
        task_ids=f"{model_name}_group.register_shadow_{model_name}",
        key=f"{model_name}_shadow_model_id",
    )
    endpoint = aiplatform.Endpoint.list(filter=f'display_name="{model_name}-endpoint"')[0]
    endpoint.update_traffic_split(
        traffic_split={shadow_model_id: canary_pct, "previous": 100 - canary_pct}
    )
    context["ti"].xcom_push(key=f"{model_name}_canary_model_id", value=shadow_model_id)


def _full_promote(model_name: str, **context):
    """Promote to 100% after canary SLOs verified. Archive previous champion."""
    from google.cloud import aiplatform, storage
    aiplatform.init(project=PROJECT_ID, location=REGION)

    shadow_model_id = context["ti"].xcom_pull(
        task_ids=f"{model_name}_group.canary_{model_name}",
        key=f"{model_name}_canary_model_id",
    )
    endpoint = aiplatform.Endpoint.list(filter=f'display_name="{model_name}-endpoint"')[0]
    endpoint.update_traffic_split(traffic_split={shadow_model_id: 100})

    # Archive previous champion metrics for rollback comparison
    gcs = storage.Client(project=PROJECT_ID)
    bucket = gcs.bucket(f"{PROJECT_ID}-ml-artifacts")
    champion_src = bucket.blob(f"{model_name}/champion/metrics.json")
    if champion_src.exists():
        bucket.copy_blob(champion_src, bucket, f"{model_name}/previous-champion/metrics.json")

    # Write new champion pointer
    champion_pointer = {"model_id": shadow_model_id, "promoted_at": context["ds"]}
    bucket.blob(f"{model_name}/champion/pointer.json").upload_from_string(
        json.dumps(champion_pointer)
    )
```

### 5.4 SLO verification between canary and full promote

The `_check_shadow_agreement` sensor (§4.5) covers model agreement. The canary stage additionally requires:

- `ml_prediction_latency_seconds{model=X, quantile="0.95"} < SLA_MS / 1000` — must not regress vs. champion
- `ml_error_rate{model=X} < 0.005` — < 0.5% prediction errors
- `ml_drift_psi{model=X} < 0.1` — no immediate drift signal on canary traffic

These three checks can be encoded as a Prometheus query task in the DAG or as a Grafana alert that blocks the canary→full gate via an Airflow ExternalTaskSensor.

---

## 6. Rollback

### 6.1 Current rollback gap

The current `ModelRegistry.kill()` in `ml/serving/model_registry.py` forces the predictor to `DEGRADED` status, causing `predict()` to return the rule-based fallback. This is not rollback — it is a kill switch that degrades serving quality to a heuristic. It is appropriate for an emergency stop but not for the routine "new model is underperforming vs. champion" case.

True rollback means: revert serving to the **previous champion ONNX artifact** without touching the rule-based fallback. The kill switch should remain for true emergencies; rollback is a separate code path.

### 6.2 Rollback protocol

Define three rollback tiers:

| Tier | Trigger | Action | Owner | RTO |
|---|---|---|---|---|
| T1 — Instant kill | p99 latency > 2× SLA OR error rate > 5% | `registry.kill(model_name)` → rule-based fallback | On-call (automated alert) | < 30 s |
| T2 — Version rollback | Champion underperforms vs. previous champion on drift or SLO metrics | Re-deploy previous champion artifact to 100% traffic | ML Platform on-call | < 5 min |
| T3 — Feature rollback | Feature computation bug introduced in a SQL change | Revert the feature SQL git change, re-run `ml_feature_refresh` DAG | Data Engineering + ML Platform | < 30 min |

### 6.3 Version rollback script

```python
# scripts/ml_rollback.py  (add to ml/ or scripts/)
"""
Usage: python scripts/ml_rollback.py --model eta-prediction --env prod
Reverts serving to the previous champion artifact recorded in GCS.
"""

import argparse, json, os
from google.cloud import aiplatform, storage

def rollback(model_name: str, env: str):
    project = f"instacommerce-{env}"
    aiplatform.init(project=project, location="us-central1")
    gcs = storage.Client(project=project)
    bucket = gcs.bucket(f"{project}-ml-artifacts")

    # Read previous champion pointer
    prev_blob = bucket.blob(f"{model_name}/previous-champion/pointer.json")
    if not prev_blob.exists():
        raise RuntimeError(f"No previous champion recorded for {model_name}. Use kill switch instead.")
    prev = json.loads(prev_blob.download_as_text())
    prev_model_id = prev["model_id"]

    # Shift 100% traffic back to previous champion
    endpoint = aiplatform.Endpoint.list(filter=f'display_name="{model_name}-endpoint"')[0]
    endpoint.update_traffic_split(traffic_split={prev_model_id: 100})
    print(f"Rolled back {model_name} to {prev_model_id} ({prev['promoted_at']})")

    # Emit a Slack alert
    import requests
    slack_url = os.environ.get("SLACK_WEBHOOK_ML_ALERTS")
    if slack_url:
        requests.post(slack_url, json={
            "text": f":rewind: *ML rollback executed*\n• Model: `{model_name}`\n• Reverted to: `{prev_model_id}`"
        })

if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("--model", required=True)
    parser.add_argument("--env", default="prod")
    args = parser.parse_args()
    rollback(args.model, args.env)
```

### 6.4 ModelRegistry rollback support

Extend `ModelRegistry` to load and swap artifacts at runtime without a kill switch:

```python
# ml/serving/model_registry.py — add rollback method
def rollback_to_version(self, model_name: str, version: str) -> bool:
    """Load and activate a specific previous model version in-process.

    This is a soft rollback: the model artifact for `version` is loaded
    from disk (or GCS) and replaces the current active predictor.
    The kill switch is NOT activated — serving quality is preserved.

    Returns True if rollback succeeded, False if the version could not be loaded.
    """
    predictor = self._models.get(model_name)
    if predictor is None:
        raise KeyError(f"Model '{model_name}' is not registered")

    success = predictor.load(version=version)
    if success:
        logger.warning(
            "Model rolled back",
            extra={"model": model_name, "version": version},
        )
    else:
        logger.error(
            "Rollback failed — activating kill switch",
            extra={"model": model_name, "version": version},
        )
        self.kill(model_name)
    return success
```

---

## 7. Drift monitoring

### 7.1 Current drift gap

`ModelMonitor.check_drift()` correctly computes PSI for a 1-D feature array. The gap is:

1. `check_drift` is only called if the predictor explicitly invokes it — the existing predictor implementations do not call it.
2. There is no baseline distribution stored alongside the model artifact. The `baseline_features` parameter has no default — callers must supply it.
3. PSI is computed on each request in isolation, not across a window of requests.
4. There is no concept of output drift (prediction score distribution shift) separate from input feature drift.

### 7.2 Baseline storage at training time

Extend the training scripts to compute and store per-feature baseline distributions as part of the model artifact:

```python
# ml/train/eta_prediction/train.py — add after model.fit, before export

def _compute_and_store_baseline(X_train: pd.DataFrame, artifact_dir: str):
    """Compute baseline distribution stats for PSI at serving time."""
    import json
    import numpy as np

    baseline = {}
    for col in X_train.select_dtypes(include=[np.number]).columns:
        vals = X_train[col].dropna().values
        percentiles = np.percentile(vals, np.linspace(0, 100, 11))  # 10 bins
        baseline[col] = {
            "breakpoints": percentiles.tolist(),
            "distribution": np.histogram(vals, bins=percentiles)[0].tolist(),
            "mean": float(np.mean(vals)),
            "std": float(np.std(vals)),
            "p5": float(np.percentile(vals, 5)),
            "p95": float(np.percentile(vals, 95)),
        }

    with open(f"{artifact_dir}/feature_baseline.json", "w") as f:
        json.dump(baseline, f)
```

### 7.3 Serving-time PSI with baseline

Extend `BasePredictor._load_model` to load the baseline and extend `ModelMonitor` to use it:

```python
# ml/serving/predictor.py — add to BasePredictor
def _load_baseline(self, artifact_dir: Path) -> dict:
    baseline_path = artifact_dir / "feature_baseline.json"
    if baseline_path.exists():
        import json
        return json.loads(baseline_path.read_text())
    return {}
```

```python
# ml/serving/monitoring.py — extend check_drift to use stored baseline
def check_drift_against_baseline(
    self,
    recent_features: dict[str, list],
    baseline: dict,
) -> dict[str, float]:
    """Compute PSI for each feature against the training baseline."""
    psi_scores = {}
    for feature_name, recent_vals in recent_features.items():
        if feature_name not in baseline:
            continue
        b = baseline[feature_name]
        breakpoints = np.array(b["breakpoints"])
        baseline_dist = np.array(b["distribution"], dtype=float)

        recent_arr = np.array(recent_vals, dtype=float)
        recent_dist = np.histogram(recent_arr, bins=breakpoints)[0].astype(float)

        eps = 1e-6
        bp = baseline_dist / (baseline_dist.sum() + eps) + eps
        rp = recent_dist / (recent_dist.sum() + eps) + eps
        psi = float(np.sum((rp - bp) * np.log(rp / bp)))
        psi_scores[feature_name] = psi

        self.drift_score.labels(model=self.model_name).set(psi)
        if psi > _PSI_THRESHOLD_ALERT:
            logger.warning(
                "Feature drift: PSI > 0.2",
                extra={"model": self.model_name, "feature": feature_name, "psi": round(psi, 4)},
            )

    return psi_scores
```

### 7.4 Scheduled drift job (not just per-request)

Real drift detection cannot rely on per-request PSI because q-commerce traffic is bursty — rush-hour PSI is expected to be high vs. off-peak baseline. Add a scheduled Airflow task that runs PSI on hourly batches:

```python
# data-platform/airflow/dags/monitoring_alerts.py — add drift check DAG task

def _run_hourly_drift_check(model_name: str, **context):
    """Run PSI on the last hour of prediction logs vs. training baseline."""
    from google.cloud import bigquery
    import json, numpy as np

    bq = bigquery.Client(project=PROJECT_ID)
    # Read last hour of logged feature vectors from BigQuery prediction logs
    query = f"""
        SELECT feature_vector
        FROM `{PROJECT_ID}.ml_logs.prediction_logs`
        WHERE model_name = '{model_name}'
          AND logged_at >= TIMESTAMP_SUB(CURRENT_TIMESTAMP(), INTERVAL 1 HOUR)
        LIMIT 10000
    """
    rows = list(bq.query(query).result())
    if len(rows) < 100:
        return  # Insufficient data

    feature_vectors = [json.loads(r["feature_vector"]) for r in rows]
    # ... aggregate per-feature lists and call check_drift_against_baseline ...
    # Emit result to monitoring_alerts table for alerting
```

### 7.5 Output drift — prediction score distribution

Input drift (PSI on features) is a leading indicator. Output drift (shift in prediction score distribution) is a direct signal of model behavior change. Add output PSI alongside input PSI:

```python
# ml/serving/monitoring.py
self.output_drift_score: Gauge = Gauge(
    "model_output_drift_psi",
    "PSI of prediction score distribution vs. training baseline",
    labelnames=["model"],
    namespace="ml",
)
```

For binary classifiers (fraud detection): baseline on training score distribution.  
For regression (ETA): baseline on `actual_eta_min - predicted_eta_min` residual distribution.  
For ranking (search): baseline on NDCG score distribution across queries.

### 7.6 Drift thresholds by model and Q-commerce context

Q-commerce has extreme time-of-day and day-of-week patterns. Rigid PSI thresholds will produce false alarms during lunch peaks vs. midnight baseline. Use time-stratified baselines:

| Model | Baseline stratification | PSI warn | PSI alert | Retrain trigger |
|---|---|---|---|---|
| ETA | By hour-of-day bucket (0–6, 7–12, 13–18, 19–23) AND weekday/weekend | 0.1 | 0.2 | 3 consecutive alert windows |
| Fraud | By `is_weekend`, `is_payday_week` (from `config.yaml` demand features) | 0.1 | 0.2 | 2 consecutive alert windows |
| Search Ranking | By query volume bucket (low/medium/high) | 0.15 | 0.25 | 3 consecutive alert windows |
| Demand Forecast | By store tier | 0.1 | 0.2 | Weekly retrain regardless |
| Personalization | By user cohort (new, active, churned) | 0.15 | 0.25 | 2 consecutive alert windows |
| CLV | By clv_segment | 0.2 | 0.3 | Monthly retrain regardless |

---

## 8. Model ownership

### 8.1 Current ownership gap

The `owner:` field exists in feature view YAMLs (e.g., `owner: routing-team` in `eta_prediction_view.yaml`, `owner: fraud-team` in `fraud_detection_view.yaml`). The model card template (`ml/mlops/model_card_template.md`) has an `{{OWNER}}` field. But ownership is not enforced anywhere: no `CODEOWNERS` entries, no required reviewers for model config changes, no runbook links.

### 8.2 CODEOWNERS for ML artifacts

Add the following to `.github/CODEOWNERS`:

```
# ML Platform
ml/                                         @instacommerce/ml-platform
ml/train/eta_prediction/                    @instacommerce/routing-team @instacommerce/ml-platform
ml/train/fraud_detection/                   @instacommerce/fraud-team @instacommerce/ml-platform
ml/train/search_ranking/                    @instacommerce/search-team @instacommerce/ml-platform
ml/train/demand_forecast/                   @instacommerce/inventory-team @instacommerce/ml-platform
ml/train/personalization/                   @instacommerce/product-team @instacommerce/ml-platform
ml/train/clv_prediction/                    @instacommerce/growth-team @instacommerce/ml-platform
ml/feature_store/feature_views/             @instacommerce/ml-platform
ml/feature_store/sql/                       @instacommerce/data-engineering @instacommerce/ml-platform
data-platform/airflow/dags/ml_*.py         @instacommerce/data-engineering @instacommerce/ml-platform
services/ai-inference-service/              @instacommerce/ml-platform
services/ai-orchestrator-service/           @instacommerce/ai-platform
```

### 8.3 Model card requirements at promotion

The model card template (`ml/mlops/model_card_template.md`) must be filled and committed to GCS alongside the artifact before `_promote_model` proceeds. Add a pre-promotion validation:

```python
# ml_training.py — add before _full_promote
def _validate_model_card(model_name: str, **context):
    from google.cloud import storage
    gcs = storage.Client(project=PROJECT_ID)
    bucket = gcs.bucket(f"{PROJECT_ID}-ml-artifacts")
    card_blob = bucket.blob(f"{model_name}/{context['ds']}/model_card.md")
    if not card_blob.exists():
        raise ValueError(f"Model card missing for {model_name}/{context['ds']}. Cannot promote.")

    card_text = card_blob.download_as_text()
    required_sections = ["## Overview", "## Performance Metrics", "## Monitoring", "## Limitations"]
    missing = [s for s in required_sections if s not in card_text]
    if missing:
        raise ValueError(f"Model card incomplete — missing: {missing}")
```

Required model card fields for each InstaCommerce model:

| Field | ETA | Fraud | Search | Demand | Personalization | CLV |
|---|---|---|---|---|---|---|
| Owner team | routing-team | fraud-team | search-team | inventory-team | product-team | growth-team |
| On-call runbook URL | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ |
| Kill switch feature flag | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ |
| Fallback behavior | zone_historical_avg | rules_based | popularity_rank | store_avg_demand | top_sellers | segment_avg |
| Retraining frequency | Daily | Daily | Daily | Daily | Weekly | Monthly |
| Bias check required | No | Yes | No | No | Yes | No |
| PII in features | No | Yes (device FP) | No | No | No | No |

### 8.4 Kill switch via feature flags

The model card template already references `{{KILL_SWITCH_FLAG}}`. Wire the kill switch to the existing `config-feature-flag-service`:

```yaml
# Example: kill switch for ETA prediction model
flags:
  ml.eta.kill_switch:
    enabled: false
    description: "When true, ETA predictor is forced to DEGRADED state (rule-based fallback)"
    owner: routing-team
    runbook: https://wiki.instacommerce.com/runbooks/ml/eta-rollback
```

The predictor's `load()` method checks this flag on startup and on a 30-second poll:

```python
# ml/serving/predictor.py — add flag check
def _check_kill_switch(self) -> bool:
    """Return True if the kill switch flag is active."""
    import requests
    try:
        resp = requests.get(
            f"http://config-service/api/v1/flags/ml.{self.model_name}.kill_switch",
            timeout=0.5,
        )
        return resp.json().get("enabled", False)
    except Exception:
        return False  # Fail open: don't kill on config service unavailability
```

---

## 9. Q-commerce-specific implementation notes

### 9.1 ETA model — three-stage serving architecture

The ETA model has three serving stages (from `eta_prediction_view.yaml`): `pre_order` (zone estimate, 5-minute refresh), `post_assign` (committed ETA with rider data, per-event), and `in_transit` (live countdown, per GPS ping). Each stage has different feature freshness requirements and different drift baselines.

**Key implementation constraint:** the three stages cannot share a single PSI baseline or a single shadow comparison. Register three separate `ModelMonitor` instances, one per stage, with stage-specific feature sets and PSI thresholds.

**Online-offline consistency risk:** `rider_avg_delivery_time_7d` and `zone_familiarity_score` are computed from `rider_features.sql` with a 7-day window. During peak hours, these values can be up to 4 hours stale (feature refresh cadence from `ml_feature_refresh`). Add a serving-time staleness check:

```python
# ml/serving/eta_predictor.py — add to predict()
freshness_ok = self._monitor.check_freshness(
    feature_timestamps=features.get("_feature_timestamps", {}),
    max_age_s=4 * 3600 if stage == "post_assign" else 3600,  # 4h for post_assign, 1h for pre_order
)
if not freshness_ok and stage == "post_assign":
    # Fall back to pre_order estimate + buffer
    return self.rule_based_fallback(features)
```

### 9.2 Fraud model — q-commerce velocity risks

The fraud model (`fraud_detection/config.yaml`) uses velocity features (`orders_last_1h`, `orders_last_24h`, `distinct_devices_24h`) that are computed by the Beam pipeline (`order_events_pipeline.py`). These streaming features have no batch equivalent in the training SQL.

**Mandatory action:** add `source: streaming` annotation to these features and ensure the training pipeline materializes a BigQuery MV `mv_fraud_velocity_features` that replays the Beam window logic using event-time windowing (not processing time). The Beam pipelines currently use processing time (per the Iter3 review finding); this must be fixed before the MV is trustworthy.

**Threshold drift:** the fraud model's `scoring.thresholds` (`auto_approve: 30`, `soft_review: 70`, `block: 100`) in the feature view YAML are not model outputs — they are business-logic thresholds applied to the model score. These thresholds must be versioned separately from the model artifact and must not require a model retrain to change. Store them in `config-feature-flag-service`:

```yaml
flags:
  ml.fraud.score_thresholds:
    auto_approve: 30
    soft_review: 70
    block: 100
    owner: fraud-team
    runbook: https://wiki.instacommerce.com/runbooks/ml/fraud-thresholds
```

### 9.3 Search ranking — null result rate as a promotion gate

The search ranking model (LambdaMART, `min_ndcg_at_10: 0.65`) ranks products for a query. If a new model version increases precision on high-click queries but decreases recall on long-tail queries, it can increase the null result rate. A 5% null result rate gate is added in §3.3. Implement it in the evaluation task:

```python
# Compute null result rate on the held-out search test set
query_results = test_data.groupby("query_id")["predicted_rank"].count()
null_results = (query_results == 0).sum()
null_result_rate = null_results / len(query_results)
metrics["null_result_rate"] = float(null_result_rate)
```

### 9.4 Demand forecast — holiday calendar as a lineage artifact

The demand forecast model (`ml/train/demand_forecast/config.yaml`) includes a `holidays` block with 14 Indian calendar events (Diwali, Holi, Dussehra, etc.). The holiday calendar is embedded in the training config. If a new holiday is added or dates change, the model must be retrained — but there is no version control for the holiday calendar itself.

**Fix:** move the holiday calendar to a versioned artifact stored in GCS:

```
gs://instacommerce-ml-artifacts/shared/holiday_calendars/india_2026.json
gs://instacommerce-ml-artifacts/shared/holiday_calendars/india_2027.json
```

Reference it from the training config:
```yaml
holidays:
  calendar_ref: gs://instacommerce-ml-artifacts/shared/holiday_calendars/india_2026.json
  calendar_version: "2026.1"
```

Include `calendar_version` in the lineage record. When the calendar is updated, the lineage mismatch metric (§2.4) fires, prompting a retrain.

### 9.5 Personalization and CLV — cold-start entities

Both models (`personalization_view.yaml`, CLV) require historical order data that new users do not have. The rule-based fallback in `PersonalizationPredictor.rule_based_fallback()` and `CLVPredictor.rule_based_fallback()` must handle cold-start gracefully. The fallback should use city-tier + time-of-day popularity vectors, not a single static list. Encode this in the model card as a formal limitation and in the feature view:

```yaml
# ml/feature_store/feature_views/personalization_view.yaml — add cold start config
cold_start:
  threshold_order_count: 3          # Users with < 3 orders are cold-start
  fallback_strategy: city_tier_popularity  # Use city-tier top-sellers
  fallback_feature_view: ml/feature_store/feature_views/popularity_view.yaml
```

---

## 10. Observability runbook

### 10.1 Prometheus alert rules (add to `monitoring/` configuration)

```yaml
# monitoring/alerts/ml-platform.yaml

groups:
  - name: ml-platform
    rules:
      # ETA model latency SLA
      - alert: MLETALatencyBreached
        expr: histogram_quantile(0.95, ml_model_prediction_latency_seconds_bucket{model="eta"}) > 0.010
        for: 5m
        labels:
          severity: warning
          team: routing-team
        annotations:
          summary: "ETA model p95 latency > 10ms"
          runbook: https://wiki.instacommerce.com/runbooks/ml/eta-rollback

      # Fraud model error rate
      - alert: MLFraudHighErrorRate
        expr: ml_model_error_rate{model="fraud"} > 0.005
        for: 2m
        labels:
          severity: critical
          team: fraud-team
        annotations:
          summary: "Fraud model error rate > 0.5%"

      # Any model significant drift
      - alert: MLInputDriftAlert
        expr: ml_model_drift_psi > 0.2
        for: 10m
        labels:
          severity: warning
          team: ml-platform
        annotations:
          summary: "{{ $labels.model }} PSI > 0.2 — feature distribution drift"
          runbook: https://wiki.instacommerce.com/runbooks/ml/drift-response

      # Shadow agreement drop
      - alert: MLShadowAgreementDrop
        expr: ml_model_shadow_agreement_rate < 0.90
        for: 30m
        labels:
          severity: warning
          team: ml-platform
        annotations:
          summary: "{{ $labels.model }} shadow agreement < 90% over last 30m"

      # Feature staleness
      - alert: MLFeatureStale
        expr: |
          (time() - ml_feature_last_updated_timestamp) > 3600
        for: 5m
        labels:
          severity: warning
          team: data-engineering
        annotations:
          summary: "ML features stale > 1 hour for {{ $labels.feature }}"

      # Fallback rate (predictors using rule-based fallback)
      - alert: MLHighFallbackRate
        expr: |
          rate(ml_model_prediction_total{is_fallback="True"}[5m]) /
          rate(ml_model_prediction_total[5m]) > 0.05
        for: 5m
        labels:
          severity: critical
          team: ml-platform
        annotations:
          summary: "{{ $labels.model }} fallback rate > 5%"
```

### 10.2 Grafana dashboard layout

Each model should have a dedicated Grafana dashboard with four rows:

| Row | Panels |
|---|---|
| Serving health | p50/p95/p99 latency, error rate, fallback rate, prediction volume |
| Feature health | Freshness (age per feature group), online-offline skew score, feature computation job lag |
| Drift | PSI per feature (top 10 by PSI), output score distribution, drift trend over 7 days |
| Shadow/Promotion | Shadow agreement rate (48h window), comparison count, canary traffic split |

---

## 11. Implementation sequencing

The following wave plan maps to the broader Iter3 program (Wave 4: data/ML validation):

### Wave 4a — Foundation (2 weeks)

| Task | File | Owner |
|---|---|---|
| Add `lineage` block to all 6 `config.yaml` files | `ml/train/*/config.yaml` | ML Platform |
| Emit `lineage.json` from all 6 `train.py` files | `ml/train/*/train.py` | ML Platform |
| Extend `PredictionResult` with `lineage_uri` | `ml/serving/predictor.py` | ML Platform |
| Wire `ModelEvaluator._check_gates` into DAG gate task | `ml_training.py` | Data Eng + ML Platform |
| Add `CODEOWNERS` entries for `ml/` and `services/ai-*` | `.github/CODEOWNERS` | ML Platform |

### Wave 4b — Consistency and drift (2 weeks)

| Task | File | Owner |
|---|---|---|
| Add online-offline consistency validation task to `ml_feature_refresh` | `ml_feature_refresh.py` | Data Eng |
| Compute and store `feature_baseline.json` in training | `ml/train/*/train.py` | ML Platform |
| Extend `ModelMonitor.check_drift` to use stored baseline | `ml/serving/monitoring.py` | ML Platform |
| Add time-stratified drift baselines | `ml/serving/monitoring.py` | ML Platform |
| Fix Beam pipelines to use event time (pre-condition for fraud MV) | `data-platform/streaming/pipelines/` | Data Eng |

### Wave 4c — Shadow and promotion (2 weeks)

| Task | File | Owner |
|---|---|---|
| Add Redis backing to `ShadowRunner` | `ml/serving/shadow_mode.py` | ML Platform |
| Add shadow Prometheus metrics to `ModelMonitor` | `ml/serving/monitoring.py` | ML Platform |
| Replace `_promote_model` with shadow→canary→full sequence | `ml_training.py` | ML Platform + SRE |
| Add `rollback_to_version` to `ModelRegistry` | `ml/serving/model_registry.py` | ML Platform |
| Write and test `scripts/ml_rollback.py` | `scripts/ml_rollback.py` | ML Platform + SRE |

### Wave 4d — Ownership and governance (1 week)

| Task | File | Owner |
|---|---|---|
| Fill model card for all 6 models | `ml/mlops/` | Service owners |
| Add kill switch flags to config-feature-flag-service | `config-feature-flag-service` | ML Platform |
| Add Prometheus alert rules | `monitoring/alerts/ml-platform.yaml` | SRE + ML Platform |
| Move holiday calendar to versioned GCS artifact | `ml/train/demand_forecast/` | ML Platform |
| Add `max_p95_latency_ms`, `bias_check_required`, output drift gates | `ml/train/*/config.yaml` | ML Platform |

---

## 12. What not to do

The following actions look like improvements but carry high risk in the current state of the platform:

**Do not** grant the AI Orchestrator (`services/ai-orchestrator-service`) write access to order, inventory, or payment state until the ML platform correctness in §1–9 above is verified and the orchestrator's own guardrails (PII, injection, budget) are independently audited. The LangGraph tools currently call catalog, cart, inventory, and pricing read-only; keep them read-only until Wave 6.

**Do not** run batch drift detection on the raw prediction log table until prediction logging itself is verified end-to-end. The `ai-inference-service` endpoints need structured feature vector logging added first (the current models log outputs but not the full feature vectors sent to ONNX Runtime).

**Do not** change the MLflow stage names (Staging → Production → Archived) unless the Airflow DAG stage-transition logic is updated in the same PR. The existing DAG reads `"champion"` and `"previous-champion"` GCS path conventions; MLflow stage names are a second inventory that can drift.

**Do not** run the bias gate for all models. ETA and demand forecasting have no direct disparate-impact risk in their current feature sets. Running bias checks on them adds operational friction without safety benefit. Limit bias checks to fraud detection and personalization (§3.5).
