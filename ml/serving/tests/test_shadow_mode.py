"""Tests for ShadowRunner Kafka/BigQuery streaming and CI promotion gate."""

import pytest
from ml.serving.predictor import BasePredictor, PredictionResult
from ml.serving.shadow_mode import (
    ShadowAgreementGateFailedError,
    ShadowRunner,
)


class DummyPredictor(BasePredictor):
    def __init__(self, name: str, version: str, output_fn):
        super().__init__(model_name=name, model_dir="/tmp")
        self._version = version
        self._output_fn = output_fn

    def _load_model(self, version=None):
        pass

    def predict(self, features):
        output = self._output_fn(features)
        return PredictionResult(
            output=output,
            model_version=self._version,
            model_name=self.model_name,
            latency_ms=10.0,
        )

    def rule_based_fallback(self, features):
        return self.predict(features)


def test_shadow_runner_streams_and_buffers_logs():
    kafka_events = []

    def mock_kafka(topic, payload):
        kafka_events.append((topic, payload))

    runner = ShadowRunner(kafka_producer=mock_kafka)

    champion = DummyPredictor("search_ranking", "v1.0", lambda f: {"score": 0.85})
    shadow = DummyPredictor("search_ranking", "v2.0", lambda f: {"score": 0.86})

    prod_res = champion.predict({"query": "milk"})
    res = runner.run_shadow(prod_res, shadow, {"query": "milk"})

    assert res is not None
    assert res["agrees"] is True
    assert runner.agreement_rate == 1.0
    assert len(kafka_events) == 1
    assert kafka_events[0][0] == "ml.shadow.events"
    assert kafka_events[0][1]["model_name"] == "search_ranking"
    assert kafka_events[0][1]["champion_version"] == "v1.0"
    assert kafka_events[0][1]["shadow_version"] == "v2.0"

    # BigQuery row export
    bq_rows = runner.export_to_bigquery_rows()
    assert len(bq_rows) == 1
    assert bq_rows[0]["agrees"] is True
    assert "features_json" in bq_rows[0]


def test_shadow_runner_promotion_gate_threshold():
    runner = ShadowRunner()

    champion = DummyPredictor("search_ranking", "v1.0", lambda f: {"score": 1.0})
    # Shadow that agrees
    shadow_agree = DummyPredictor("search_ranking", "v2.0", lambda f: {"score": 1.05})
    # Shadow that diverges (> 10% relative diff)
    shadow_disagree = DummyPredictor("search_ranking", "v2.0", lambda f: {"score": 2.0})

    prod_res = champion.predict({})

    # 9 agreements, 1 disagreement = 90% (< 92%)
    for _ in range(9):
        runner.run_shadow(prod_res, shadow_agree, {})
    runner.run_shadow(prod_res, shadow_disagree, {})

    assert runner.total_comparisons == 10
    assert runner.agreement_rate == 0.90

    with pytest.raises(ShadowAgreementGateFailedError):
        runner.verify_promotion_gate(threshold=0.92, raise_on_failure=True)

    assert runner.verify_promotion_gate(threshold=0.92, raise_on_failure=False) is False
    assert runner.verify_promotion_gate(threshold=0.85, raise_on_failure=False) is True
