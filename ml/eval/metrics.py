from __future__ import annotations

import logging
import math
from typing import Any, Mapping, Optional, Sequence, cast

import numpy as np
from sklearn.metrics import (
    accuracy_score,
    confusion_matrix,
    f1_score,
    mean_absolute_error,
    mean_squared_error,
    precision_recall_curve,
    precision_score,
    r2_score,
    recall_score,
    roc_auc_score,
)

logger = logging.getLogger(__name__)

METRIC_ALIASES: dict[str, tuple[str, ...]] = {
    "mae_min": ("mae", "mae_minutes"),
    "mae_minutes": ("mae", "mae_min"),
    "mae": ("mae_min", "mae_minutes"),
    "ndcg_at_10": ("ndcg@10",),
    "ndcg@10": ("ndcg_at_10",),
    "hit_at_10": ("hit@10",),
    "hit@10": ("hit_at_10",),
}


def resolve_metric_value(metrics: Mapping[str, Any], metric_name: str, gate_key: str = "") -> Optional[float]:
    candidates: list[str] = [metric_name]
    if gate_key and gate_key not in candidates:
        candidates.append(gate_key)
    candidates.extend(METRIC_ALIASES.get(metric_name, ()))
    if "_at_" in metric_name:
        candidates.append(metric_name.replace("_at_", "@"))
    if "@" in metric_name:
        candidates.append(metric_name.replace("@", "_at_"))
    for key in candidates:
        val = metrics.get(key)
        if isinstance(val, (int, float)) and not isinstance(val, bool) and math.isfinite(float(val)):
            return float(val)
    return None


def _compute_ranking_metrics(
    y_true: np.ndarray,
    y_score: np.ndarray,
    groups: Optional[Sequence[Any]] = None,
) -> dict[str, float]:
    n = len(y_true)
    if n == 0:
        return {"ndcg_at_10": 0.0, "hit_at_10": 0.0, "mrr": 0.0}
    if groups is None:
        group_indices: list[list[int]] = [list(range(n))]
    else:
        grouped: dict[Any, list[int]] = {}
        for idx, gid in enumerate(groups):
            grouped.setdefault(gid, []).append(idx)
        group_indices = list(grouped.values())

    ndcgs: list[float] = []
    hits: list[float] = []
    mrrs: list[float] = []
    for idxs in group_indices:
        ranked = sorted(idxs, key=lambda i: float(y_score[i]), reverse=True)
        top10 = [float(y_true[i]) for i in ranked[:10]]
        ideal = sorted((float(y_true[i]) for i in idxs), reverse=True)[:10]
        dcg = sum(((2.0 ** rel) - 1.0) / math.log2(rank + 2) for rank, rel in enumerate(top10))
        idcg = sum(((2.0 ** rel) - 1.0) / math.log2(rank + 2) for rank, rel in enumerate(ideal))
        if idcg > 0:
            ndcgs.append(dcg / idcg)
        hits.append(1.0 if any(rel > 0 for rel in top10) else 0.0)
        rr = 0.0
        for rank, idx in enumerate(ranked, start=1):
            if float(y_true[idx]) > 0:
                rr = 1.0 / rank
                break
        mrrs.append(rr)

    return {
        "ndcg_at_10": float(np.mean(ndcgs)) if ndcgs else 0.0,
        "hit_at_10": float(np.mean(hits)) if hits else 0.0,
        "mrr": float(np.mean(mrrs)) if mrrs else 0.0,
    }


def compute_evaluation_metrics(
    y_true: np.ndarray,
    y_pred: np.ndarray,
    y_pred_proba: Optional[np.ndarray],
    task_type: Optional[str] = None,
    groups: Optional[Sequence[Any]] = None,
) -> dict[str, float]:
    metrics: dict[str, float] = {}
    unique_labels = np.unique(y_true)

    if task_type is not None and task_type.lower() == "ranking":
        scores = y_pred_proba if y_pred_proba is not None else y_pred
        metrics.update(_compute_ranking_metrics(y_true, np.asarray(scores, dtype=float), groups=groups))
        metrics["mae"] = float(mean_absolute_error(y_true, y_pred))
        metrics["rmse"] = float(np.sqrt(mean_squared_error(y_true, y_pred)))
        return metrics

    if task_type is not None:
        is_classification = task_type.lower() in ("classification", "binary", "multiclass")
    else:
        is_classification = len(unique_labels) <= 20 and all(float(v).is_integer() for v in unique_labels)

    if is_classification:
        metrics["accuracy"] = float(accuracy_score(y_true, y_pred))
        metrics["f1_score"] = float(f1_score(y_true, y_pred, average="weighted", zero_division=cast(Any, 0)))
        metrics["precision"] = float(precision_score(y_true, y_pred, average="weighted", zero_division=cast(Any, 0)))
        metrics["recall"] = float(recall_score(y_true, y_pred, average="weighted", zero_division=cast(Any, 0)))

        is_binary = len(unique_labels) <= 2 and set(int(v) for v in unique_labels).issubset({0, 1})
        if is_binary:
            tn, fp, _fn, _tp = confusion_matrix(y_true, y_pred, labels=[0, 1]).ravel()
            denom = fp + tn
            metrics["false_positive_rate"] = float(fp / denom) if denom > 0 else 0.0

        if y_pred_proba is not None and len(unique_labels) == 2:
            metrics["auc_roc"] = float(roc_auc_score(y_true, y_pred_proba))
            prec_arr, rec_arr, _ = precision_recall_curve(y_true, y_pred_proba)
            high_rec = prec_arr[rec_arr >= 0.95]
            metrics["precision_at_95_recall"] = float(np.max(high_rec)) if len(high_rec) > 0 else 0.0
    else:
        y_t = np.asarray(y_true, dtype=float)
        y_p = np.asarray(y_pred, dtype=float)
        mae = float(mean_absolute_error(y_t, y_p))
        metrics["mae"] = mae
        metrics["mae_min"] = mae
        metrics["mae_minutes"] = mae
        metrics["rmse"] = float(np.sqrt(mean_squared_error(y_t, y_p)))
        metrics["r2_score"] = float(r2_score(y_t, y_p))
        metrics["within_2min_pct"] = float(np.mean(np.abs(y_t - y_p) <= 2.0))
        if len(y_t) >= 2 and float(np.std(y_t)) > 0.0 and float(np.std(y_p)) > 0.0:
            corr = float(np.corrcoef(y_t, y_p)[0, 1])
            metrics["correlation"] = corr if math.isfinite(corr) else 0.0
        else:
            metrics["correlation"] = 0.0

        mask = y_t != 0
        if mask.sum() > 0:
            mape = float(np.mean(np.abs(y_t[mask] - y_p[mask]) / np.abs(y_t[mask])))
            metrics["mape"] = mape
            metrics["accuracy_pct"] = max(0.0, (1.0 - mape) * 100.0)

    return metrics


def evaluate_promotion_gates(
    metrics: Mapping[str, Any],
    gates: Mapping[str, Any],
    *,
    require_present: bool = True,
) -> tuple[bool, list[str]]:
    passed = True
    failures: list[str] = []
    for gate_key, gate_value in gates.items():
        if not isinstance(gate_value, (int, float)) or isinstance(gate_value, bool):
            continue
        if gate_key.startswith("min_"):
            metric_name = gate_key[4:]
            actual = resolve_metric_value(metrics, metric_name, gate_key=gate_key)
            if actual is None:
                if require_present:
                    msg = f"Missing required metric for gate '{gate_key}'"
                    logger.warning("Gate FAILED (fail-closed): %s", msg)
                    failures.append(msg)
                    passed = False
                continue
            if actual < float(gate_value):
                msg = f"{metric_name} = {actual:.4f} < {float(gate_value):.4f} (min)"
                logger.warning("Gate FAILED: %s", msg)
                failures.append(msg)
                passed = False
        elif gate_key.startswith("max_"):
            metric_name = gate_key[4:]
            actual = resolve_metric_value(metrics, metric_name, gate_key=gate_key)
            if actual is None:
                if require_present:
                    msg = f"Missing required metric for gate '{gate_key}'"
                    logger.warning("Gate FAILED (fail-closed): %s", msg)
                    failures.append(msg)
                    passed = False
                continue
            if actual > float(gate_value):
                msg = f"{metric_name} = {actual:.4f} > {float(gate_value):.4f} (max)"
                logger.warning("Gate FAILED: %s", msg)
                failures.append(msg)
                passed = False
    return passed, failures


def compute_fairness_metrics(
    test_data: Any,
    y_pred: np.ndarray,
    y_true: Optional[np.ndarray],
    protected_attributes: Sequence[str],
) -> tuple[dict[str, Any], dict[str, float], dict[str, float], bool, list[str]]:
    demographic_parity: dict[str, Any] = {}
    equalized_odds: dict[str, float] = {}
    disparate_impact: dict[str, float] = {}
    passed = True
    details: list[str] = []

    for attr in protected_attributes:
        if attr not in test_data.columns:
            continue
        selection_rates: dict[str, float] = {}
        for group in test_data[attr].unique():
            mask = test_data[attr] == group
            group_pred = y_pred[mask]
            selection_rates[str(group)] = float(group_pred.mean())
            if y_true is not None:
                group_true = y_true[mask]
                tp = ((group_pred == 1) & (group_true == 1)).sum()
                fn = ((group_pred == 0) & (group_true == 1)).sum()
                fp = ((group_pred == 1) & (group_true == 0)).sum()
                tn = ((group_pred == 0) & (group_true == 0)).sum()
                equalized_odds[f"{attr}_{group}_tpr"] = float(tp / (tp + fn)) if (tp + fn) > 0 else 0.0
                equalized_odds[f"{attr}_{group}_fpr"] = float(fp / (fp + tn)) if (fp + tn) > 0 else 0.0
        demographic_parity[attr] = selection_rates
        rates = list(selection_rates.values())
        if rates and max(rates) > 0:
            di_ratio = float(min(rates) / max(rates))
            disparate_impact[attr] = di_ratio
            if di_ratio < 0.8:
                passed = False
                details.append(f"Disparate impact for {attr}: {di_ratio:.4f} < 0.8")
    return demographic_parity, equalized_odds, disparate_impact, passed, details
