"""Unit tests for A/B Testing ExperimentClient and cross-language consistency."""

import pytest
from ml.experiments.experiment_client import (
    ExperimentClient,
    compute_bucket_percent,
    murmur3_32,
)


def test_murmur3_cross_language_parity_with_java_guava():
    # In Java: ExperimentEvaluationServiceTest asserts computeBucketPercent("user_123", "exp_pricing_v1") == 27
    bucket = compute_bucket_percent("user_123", "exp_pricing_v1")
    assert bucket == 27, f"Expected bucket 27 matching Java Guava, got {bucket}"


def test_deterministic_user_variant_assignment():
    client = ExperimentClient()
    variants = [
        {"name": "control", "weight": 50},
        {"name": "treatment_dynamic_eta", "weight": 50},
    ]

    # Repeated calls for the same user must return the exact same variant
    res1 = client.evaluate("exp_dynamic_eta", "user_1001", variants, salt="salt_alpha")
    res2 = client.evaluate("exp_dynamic_eta", "user_1001", variants, salt="salt_alpha")

    assert res1["variant"] == res2["variant"]
    assert res1["bucket"] == res2["bucket"]
    assert res1["variant"] in ["control", "treatment_dynamic_eta"]


def test_experiment_logging_search_and_checkout_events():
    emitted = []

    def mock_emitter(topic, event):
        emitted.append((topic, event))

    client = ExperimentClient(event_emitter=mock_emitter)
    variants = [
        {"name": "control", "weight": 50},
        {"name": "treatment_surge", "weight": 50},
    ]

    res = client.evaluate("exp_pricing", "user_99", variants)
    variant_name = res["variant"]

    # Log search interaction
    client.log_search_interaction("user_99", "exp_pricing", variant_name, "organic milk", "prod_123")

    # Log checkout conversion
    client.log_checkout_conversion("user_99", "exp_pricing", variant_name, "ord_555", 3500)

    exposures = client.get_exposures()
    assert len(exposures) == 3
    assert exposures[0]["experiment_key"] == "exp_pricing"
    assert exposures[1]["event_type"] == "search_experiment_interaction"
    assert exposures[2]["event_type"] == "checkout_experiment_conversion"
    assert exposures[2]["total_cents"] == 3500
