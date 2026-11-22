"""Shared utilities for ML training pipelines: numeric coercion, gates, and optional MLflow logging."""

from __future__ import annotations

import argparse
from contextlib import AbstractContextManager
from dataclasses import dataclass
import logging
import math
from pathlib import Path
from typing import Any, Literal, Mapping, Optional, Protocol, Sequence

import yaml

logger = logging.getLogger(__name__)


class MlflowProtocol(Protocol):
    """Minimal structural interface for optional mlflow module."""

    def set_experiment(self, experiment_name: str | None = ..., experiment_id: str | None = ...) -> Any: ...
    def start_run(self, *args: Any, **kwargs: Any) -> AbstractContextManager[Any]: ...
    def log_params(self, params: Mapping[str, Any]) -> None: ...
    def log_metric(self, key: str, value: float | int, step: int | None = ...) -> None: ...
    def log_metrics(self, metrics: Mapping[str, float | int], step: int | None = ...) -> None: ...
    def log_artifact(self, local_path: str, artifact_path: str | None = ...) -> None: ...
    def log_artifacts(self, local_dir: str, artifact_path: str | None = ...) -> None: ...
    def log_dict(self, dictionary: Mapping[str, Any], artifact_file: str) -> None: ...


class MlflowRegistryClientProtocol(Protocol):
    """Minimal structural interface for optional MlflowClient."""

    def transition_model_version_stage(
        self,
        name: str,
        version: str,
        stage: str,
        archive_existing_versions: bool = ...,
    ) -> Any: ...
    def set_model_version_tag(
        self,
        name: str,
        version: str,
        key: str,
        value: Any,
    ) -> None: ...


@dataclass(frozen=True)
class GateRule:
    """Declarative promotion gate threshold check."""

    metric_key: str
    gate_key: str
    op: Literal["le", "ge"]
    default_gate: float
    label: str
    log_format: str = ".4f"
    unit: str = ""
    missing_default: Any = None


def as_finite_float(value: Any) -> float | None:
    """Convert non-bool numeric values to float if finite, otherwise None."""
    if isinstance(value, (int, float)) and not isinstance(value, bool):
        num = float(value)
        if math.isfinite(num):
            return num
    return None


def check_metric_gates(
    metrics: Mapping[str, Any],
    gates: Mapping[str, Any],
    rules: Sequence[GateRule],
    *,
    log: Optional[logging.Logger] = None,
    warn_each: bool = True,
) -> tuple[bool, list[str]]:
    """Evaluate fail-closed promotion gates for a sequence of GateRules."""
    passed = True
    reasons: list[str] = []
    active_logger = log or logger

    for rule in rules:
        raw_val = metrics.get(rule.metric_key, rule.missing_default)
        parsed = as_finite_float(raw_val)
        threshold = float(gates.get(rule.gate_key, rule.default_gate))
        if parsed is None:
            reason = f"{rule.label} {raw_val!r}{rule.unit} is non-finite"
            passed = False
            reasons.append(reason)
            if warn_each:
                active_logger.warning(
                    "%s %r%s %s gate %s%s",
                    rule.label,
                    raw_val,
                    rule.unit,
                    "above" if rule.op == "le" else "below",
                    format(threshold, rule.log_format),
                    rule.unit,
                )
            continue

        breached = parsed > threshold if rule.op == "le" else parsed < threshold
        if breached:
            comparator = ">" if rule.op == "le" else "<"
            direction = "above" if rule.op == "le" else "below"
            reason = (
                f"{rule.label} {format(parsed, rule.log_format)}{rule.unit} "
                f"{comparator} gate {format(threshold, rule.log_format)}{rule.unit}"
            )
            passed = False
            reasons.append(reason)
            if warn_each:
                active_logger.warning(
                    "%s %r%s %s gate %s%s",
                    rule.label,
                    raw_val,
                    rule.unit,
                    direction,
                    format(threshold, rule.log_format),
                    rule.unit,
                )

    return passed, reasons


def build_training_parser(
    description: str,
    default_experiment: str,
    default_output_dir: str,
) -> argparse.ArgumentParser:
    """Create standard CLI parser shared by training entrypoints."""
    parser = argparse.ArgumentParser(description=description)
    parser.add_argument("--config", type=str, required=True, help="Path to config YAML")
    parser.add_argument("--experiment", type=str, default=default_experiment, help="MLflow experiment name")
    parser.add_argument("--output-dir", type=str, default=default_output_dir, help="Output directory")
    parser.add_argument("--run-name", type=str, default=None, help="MLflow run name")
    return parser


def load_training_config(config_path: str | Path) -> dict[str, Any]:
    """Load YAML training configuration file."""
    with open(config_path, "r") as f:
        return yaml.safe_load(f)


def log_mlflow_run(
    mlflow_client: Optional[MlflowProtocol],
    *,
    experiment: str,
    run_name: Optional[str],
    params: Mapping[str, Any],
    metrics: Mapping[str, Any],
    gate_passed: bool,
    artifact_paths: Sequence[str | Path] = (),
    artifact_dir: Optional[str | Path] = None,
    dicts: Optional[Mapping[str, Mapping[str, Any]]] = None,
) -> None:
    """Log training parameters, metrics, and artifacts when mlflow is available."""
    if mlflow_client is None:
        return

    mlflow_client.set_experiment(experiment)
    with mlflow_client.start_run(run_name=run_name):
        scalar_params = {
            k: v for k, v in params.items() if isinstance(v, (str, int, float, bool))
        }
        if scalar_params:
            mlflow_client.log_params(scalar_params)

        loggable_metrics: dict[str, float | int] = {
            k: num
            for k, v in metrics.items()
            if (num := as_finite_float(v)) is not None
        }
        loggable_metrics["promotion_gate_passed"] = int(gate_passed)
        mlflow_client.log_metrics(loggable_metrics)

        if dicts:
            for artifact_file, payload in dicts.items():
                mlflow_client.log_dict(payload, artifact_file)
        if artifact_dir is not None:
            mlflow_client.log_artifacts(str(artifact_dir))
        for path in artifact_paths:
            mlflow_client.log_artifact(str(path))
