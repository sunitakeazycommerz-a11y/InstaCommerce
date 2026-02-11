# Model Serving Architecture — InstaCommerce

## Overview

Production ML serving infrastructure for the InstaCommerce Q-commerce platform.
All models serve behind a unified `BasePredictor` interface with automatic fallback
to deterministic rules when ML predictions are unavailable.

## Architecture

```
┌──────────────────────────────────────────────────────────┐
│                      API Gateway                         │
└────────────────────────┬─────────────────────────────────┘
                         │
┌────────────────────────▼─────────────────────────────────┐
│                   Model Registry                         │
│  ┌──────────┐  ┌────────────┐  ┌───────────────────┐    │
│  │ A/B Route│  │ Kill Switch│  │  Shadow Mode       │    │
│  └──────────┘  └────────────┘  └───────────────────┘    │
└────────────────────────┬─────────────────────────────────┘
                         │
     ┌───────────┬───────┼────────┬──────────┬─────────┐
     ▼           ▼       ▼        ▼          ▼         ▼
┌─────────┐ ┌────────┐ ┌──────┐ ┌──────┐ ┌──────┐ ┌─────┐
│  ETA    │ │ Fraud  │ │Rank  │ │Demand│ │Person│ │ CLV │
│Predictor│ │Predict │ │Predict│ │Predict│ │alize │ │Pred │
└────┬────┘ └───┬────┘ └──┬───┘ └──┬───┘ └──┬───┘ └──┬──┘
     │          │         │        │         │        │
     ▼          ▼         ▼        ▼         ▼        ▼
  ONNX RT   ONNX RT   ONNX RT  Prophet   ONNX RT  ONNX RT
  /LightGBM /XGBoost  /LGBM            /LightGBM /LightGBM
```

## Models

| Model           | Framework   | Latency SLA (p95) | Fallback Strategy              |
|-----------------|-------------|--------------------|---------------------------------|
| ETA             | LightGBM    | < 15 ms            | distance×3 + items×0.5 + traffic|
| Fraud           | XGBoost     | < 10 ms            | Rule-based velocity checks      |
| Ranking         | LightGBM    | < 20 ms            | Popularity + recency sort       |
| Demand          | Prophet     | < 50 ms            | Historical weekly average       |
| Personalization | LightGBM    | < 25 ms            | Popularity-based recommendations|
| CLV             | LightGBM    | < 30 ms            | Average order value × frequency |

## Key Capabilities

### Rule-Based Fallback
Every predictor implements `rule_based_fallback()` — a deterministic function that
provides reasonable results when the ML model is unavailable (loading, failed, or
killed). This ensures zero-downtime model updates.

### A/B Routing
The `ModelRegistry` supports weighted traffic routing between model versions. Set
routing weights via `set_ab_routing("eta", {"v2.1": 0.8, "v2.2": 0.2})`.

### Shadow Mode
New models run in shadow mode alongside production. The `ShadowRunner` executes
both predictions, logs comparison metrics (agreement rate, score delta), but only
serves production results to users.

### Kill Switch
Any model can be instantly disabled via `ModelRegistry.kill(model_name)`. Requests
automatically fall back to rule-based predictions.

### Monitoring
All predictions are instrumented with Prometheus metrics:
- `model_prediction_total` — counter by model, version, outcome
- `model_prediction_latency_seconds` — histogram by model
- `model_drift_psi` — gauge tracking Population Stability Index
- `model_error_rate` — gauge tracking error rate

Drift alerts fire when PSI exceeds 0.2 (moderate drift).

## File Structure

```
ml/serving/
├── README.md                  # This file
├── __init__.py
├── predictor.py               # Base predictor interface
├── eta_predictor.py           # ETA prediction (3-stage)
├── fraud_predictor.py         # Fraud scoring
├── ranking_predictor.py       # Search & feed ranking
├── demand_predictor.py        # Demand forecasting
├── personalization_predictor.py # Personalized recommendations
├── clv_predictor.py           # Customer lifetime value
├── model_registry.py          # Model management & routing
├── shadow_mode.py             # Shadow mode execution
└── monitoring.py              # Drift detection & alerting

ml/mlops/
└── model_card_template.md     # Standardized model documentation
```

## Usage

```python
from ml.serving import ModelRegistry, ModelMonitor
from ml.serving.eta_predictor import ETAPredictor

# Initialize
registry = ModelRegistry(models_dir="/opt/models")
eta = ETAPredictor(model_name="eta", model_dir="/opt/models/eta")
eta.load(version="v2.1")
registry.register(eta)

# Predict
result = registry.get("eta").predict({
    "distance_km": 3.5,
    "item_count": 4,
    "hour_of_day": 14,
    "zone_id": "zone_west_1",
})
# PredictionResult(output={"eta_minutes": 22, ...}, latency_ms=8.3, ...)
```
