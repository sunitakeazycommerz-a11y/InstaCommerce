"""Feature store client base, unavailable sentinel, and BigQuery implementation."""

from __future__ import annotations

import logging
from typing import Any, Dict, Sequence

try:
    from google.cloud import bigquery as _bigquery  # type: ignore[import-untyped]
    bigquery: Any = _bigquery
except ImportError:
    bigquery = None

logger = logging.getLogger("ai_inference.feature_store")


class FeatureStoreClient:
    backend = "none"

    def get_features(self, entity_id: str, feature_names: Sequence[str]) -> Dict[str, float]:
        return {}

    def health(self) -> Dict[str, str]:
        return {"backend": self.backend, "status": "disabled"}

    def close(self) -> None:
        return None


class UnavailableFeatureStoreClient(FeatureStoreClient):
    def __init__(self, backend: str, reason: str) -> None:
        self.backend = backend
        self._reason = reason

    def get_features(self, entity_id: str, feature_names: Sequence[str]) -> Dict[str, float]:
        raise RuntimeError(self._reason)

    def health(self) -> Dict[str, str]:
        return {"backend": self.backend, "status": "unavailable", "reason": self._reason}


class BigQueryFeatureStoreClient(FeatureStoreClient):
    backend = "bigquery"

    def __init__(self, project: str, dataset: str, table: str) -> None:
        if bigquery is None:
            raise ImportError("google-cloud-bigquery is not installed")
        self._bigquery = bigquery
        self._client = bigquery.Client(project=project or None)
        if project:
            self._table = f"{project}.{dataset}.{table}"
        else:
            self._table = f"{dataset}.{table}"

    def get_features(self, entity_id: str, feature_names: Sequence[str]) -> Dict[str, float]:
        if not feature_names:
            return {}
        query = (
            f"SELECT feature_name, feature_value FROM `{self._table}` "
            "WHERE entity_id = @entity_id AND feature_name IN UNNEST(@feature_names)"
        )
        job_config = self._bigquery.QueryJobConfig(
            query_parameters=[
                self._bigquery.ScalarQueryParameter("entity_id", "STRING", entity_id),
                self._bigquery.ArrayQueryParameter("feature_names", "STRING", list(feature_names)),
            ]
        )
        rows = self._client.query(query, job_config=job_config).result()
        result: Dict[str, float] = {}
        for row in rows:
            try:
                result[str(row.feature_name)] = float(row.feature_value)
            except (TypeError, ValueError):
                continue
        return result

    def health(self) -> Dict[str, str]:
        return {"backend": self.backend, "status": "configured"}

    def close(self) -> None:
        try:
            self._client.close()
        except Exception as exc:
            logger.warning("Failed to close BigQuery feature store client: %s", exc)
