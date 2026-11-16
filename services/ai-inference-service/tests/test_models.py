from unittest.mock import MagicMock

from app.models.demand_model import DemandModel, DemandFeatures
from app.models.personalization_model import PersonalizationModel, PersonalizationFeatures, RecommendationSurface
from app.models.clv_model import CLVModel, CLVFeatures, CLVSegment
from app.models.dynamic_pricing_model import (
    DynamicPricingModel,
    PricingFeatures,
    _apply_guardrails,
    clamp_multiplier,
)
from app.models.eta_model import ETAModel, ETAFeatures, ETAStage
from app.models.fraud_model import FraudModel, FraudFeatures, FraudDecision
from app.models.ranking_model import RankingModel, RankingFeatures


def test_demand_model_fallback():
    model = DemandModel()
    features = DemandFeatures(
        store_id="store-456",
        product_id="prod-123",
        hour=14,
        day_of_week=2,
        sales_same_hour_last_week=10.0,
        sales_avg_7d=8.0,
        sales_avg_30d=7.5,
    )
    response = model.predict(features)
    assert response.predicted_units >= 0.0
    assert response.confidence_lower <= response.predicted_units <= response.confidence_upper
    assert response.product_id == "prod-123"
    assert response.method == "rule_based"


def test_clv_model_fallback():
    model = CLVModel()
    features = CLVFeatures(
        user_id="user-123",
        recency_days=5,
        frequency_total=12,
        frequency_30d=3,
        monetary_avg_cents=4500,
        monetary_total_cents=54000,
        tenure_days=180,
    )
    response = model.predict(features)
    assert response.predicted_annual_value_cents >= 0
    assert 0.0 <= response.survival_probability_12m <= 1.0
    assert response.segment in [CLVSegment.PLATINUM, CLVSegment.GOLD, CLVSegment.SILVER, CLVSegment.BRONZE]


def test_dynamic_pricing_model():
    model = DynamicPricingModel()
    features = PricingFeatures(
        order_amount_cents=1500,
        distance_km=3.5,
        item_count=4,
        active_riders_in_zone=12,
        pending_orders_in_zone=3,
        surge_multiplier=1.2,
    )
    response = model.predict(features)
    assert response.delivery_fee_cents > 0
    assert response.delivery_fee_dollars == round(response.delivery_fee_cents / 100.0, 2)


def test_eta_prediction_model():
    model = ETAModel()
    features = ETAFeatures(
        distance_km=3.2,
        item_count=5,
        traffic_factor=1.2,
        store_prep_time_minutes=8.0,
        rider_avg_speed_kmh=24.0,
        weather_multiplier=1.0,
        queue_depth=2,
        time_of_day_hour=18,
        is_weekend=False,
        stage=ETAStage.PRE_ORDER,
    )
    response = model.predict(features)
    assert response.eta_minutes > 0.0
    assert response.confidence_lower_minutes <= response.eta_minutes <= response.confidence_upper_minutes


def test_fraud_model():
    model = FraudModel()
    features = FraudFeatures(
        order_amount_cents=15000,
        orders_last_1h=0,
        orders_last_24h=1,
        account_age_days=180,
    )
    response = model.predict(features)
    assert 0.0 <= response.fraud_score <= 100.0
    assert 0.0 <= response.fraud_probability <= 1.0
    assert response.decision in [FraudDecision.AUTO_APPROVE, FraudDecision.SOFT_REVIEW, FraudDecision.BLOCK]


def test_ranking_model():
    model = RankingModel()
    items = [
        RankingFeatures(product_id="p1", bm25_score=3.5, query_title_similarity=0.8),
        RankingFeatures(product_id="p2", bm25_score=5.0, query_title_similarity=0.9),
    ]
    response = model.predict(items)
    assert len(response.ranked_items) == 2
    assert response.ranked_items[0]["product_id"] == "p2"


def test_personalization_model():
    model = PersonalizationModel()
    features = PersonalizationFeatures(
        user_id="user-123",
        surface=RecommendationSurface.HOMEPAGE,
        candidate_product_ids=["prod-1", "prod-2", "prod-3"],
    )
    response = model.predict(features)
    assert response.user_id == "user-123"
    assert len(response.recommendations) > 0


def test_dynamic_pricing_floor_clamped_to_ceiling():
    """Verify floor = min(floor, ceiling) after min_margin_pct so np.clip never inverts."""
    # min_margin_pct pushes floor (1.0 + 0.95 = 1.95) above ceiling (1.5)
    clamped_mult = clamp_multiplier(raw_multiplier=1.2, floor=0.8, ceiling=1.5, min_margin_pct=0.95)
    assert clamped_mult == 1.5

    # Fee guardrails where cost + margin exceeds 2x base_fee ceiling
    fee, guardrail = _apply_guardrails(
        fee_cents=400,
        cost_cents=900,
        min_margin_cents=300,
        base_fee_cents=500,  # ceiling = 1000, raw floor = 1200 -> clamped floor = 1000
        min_margin_pct=0.5,
    )
    assert fee == 1000
    assert guardrail == "price_floor"


def test_personalization_predict_ml_blends_model_scores():
    """Verify _predict_ml calls self._model.predict / predict_proba and blends with feature scores."""
    model = PersonalizationModel()
    mock_estimator = MagicMock()
    mock_estimator.predict.return_value = [0.1, 0.95, 0.2]
    model._model = mock_estimator

    features = PersonalizationFeatures(
        user_id="user-ml-blend",
        surface=RecommendationSurface.HOMEPAGE,
        candidate_product_ids=["prod-a", "prod-b", "prod-c"],
        user_embedding=[0.5, 0.5, 0.5, 0.5],
        candidate_embeddings=[
            [0.4, 0.4, 0.4, 0.4],
            [0.4, 0.4, 0.4, 0.4],
            [0.4, 0.4, 0.4, 0.4],
        ],
    )
    response = model.predict(features)
    assert mock_estimator.predict.called
    assert response.method == "ml"
    # prod-b has highest ML score (0.95) with identical embeddings, so it ranks #1
    assert response.recommendations[0]["product_id"] == "prod-b"
