# InstaCommerce ML Platform

Production machine learning platform powering search, recommendations, fraud detection, demand forecasting, ETA prediction, and customer lifetime value for Q-commerce.

## Model Inventory

| Model | Algorithm | Objective | Key Metric | Target |
|-------|-----------|-----------|------------|--------|
| **Search Ranking** | LambdaMART (LightGBM) | Maximize search conversion | NDCG@10 ≥ 0.65 | +15% search conversion |
| **Fraud Detection** | XGBoost Ensemble | Minimize fraud rate | AUC, Precision@95%Recall | 2% → 0.3% fraud rate |
| **ETA Prediction** | LightGBM Regression | Minimize delivery time error | MAE ≤ 1.5 min | ±5 min → ±1.5 min |
| **Demand Forecast** | Prophet + TFT | Forecast per store×SKU×hour | MAPE ≤ 8% | 92% accuracy |
| **Personalization** | Two-Tower NCF | Maximize engagement & orders | Hit@10, NDCG@10 | +20% homepage CTR |
| **CLV Prediction** | BG/NBD + Gamma-Gamma | Predict customer lifetime value | MAE, Calibration | Segment-level accuracy |

## Directory Structure

```
ml/
├── README.md
├── requirements.txt
├── train/
│   ├── search_ranking/      # LambdaMART search ranking
│   │   ├── train.py
│   │   └── config.yaml
│   ├── fraud_detection/     # XGBoost fraud model
│   │   ├── train.py
│   │   └── config.yaml
│   ├── eta_prediction/      # LightGBM ETA
│   │   ├── train.py
│   │   └── config.yaml
│   ├── demand_forecast/     # Prophet + TFT
│   │   ├── train.py
│   │   └── config.yaml
│   ├── personalization/     # Two-Tower NCF
│   │   ├── train.py
│   │   └── config.yaml
│   └── clv_prediction/      # BG/NBD + Gamma-Gamma
│       ├── train.py
│       └── config.yaml
├── eval/
│   └── evaluate.py          # Unified evaluation framework
├── serving/
│   └── predictor.py         # Base predictor interface
├── feature_store/           # Feature definitions & ingestion
└── mlops/                   # CI/CD & orchestration
```

## Quick Start

### Prerequisites

```bash
pip install -r ml/requirements.txt
```

### Training a Model

Each model can be trained via CLI:

```bash
# Search Ranking
python -m ml.train.search_ranking.train \
  --config ml/train/search_ranking/config.yaml \
  --experiment search-ranking-v1

# Fraud Detection
python -m ml.train.fraud_detection.train \
  --config ml/train/fraud_detection/config.yaml \
  --experiment fraud-detection-v1

# ETA Prediction
python -m ml.train.eta_prediction.train \
  --config ml/train/eta_prediction/config.yaml \
  --experiment eta-prediction-v1

# Demand Forecast
python -m ml.train.demand_forecast.train \
  --config ml/train/demand_forecast/config.yaml \
  --experiment demand-forecast-v1

# Personalization
python -m ml.train.personalization.train \
  --config ml/train/personalization/config.yaml \
  --experiment personalization-v1

# CLV Prediction
python -m ml.train.clv_prediction.train \
  --config ml/train/clv_prediction/config.yaml \
  --experiment clv-prediction-v1
```

### Evaluation

```bash
python -m ml.eval.evaluate \
  --model-path artifacts/search-ranking/v1/model.onnx \
  --test-data gs://instacommerce-ml/datasets/search_ranking/test.parquet \
  --gates-config ml/train/search_ranking/config.yaml
```

## Deployment

Models are deployed via Vertex AI Endpoints with shadow mode validation:

1. **Train** — Run training pipeline, log to MLflow
2. **Evaluate** — Check promotion gates (min metrics, bias checks)
3. **Shadow Deploy** — Run new model in shadow mode alongside production
4. **Promote** — Swap traffic to new model after validation
5. **Monitor** — Track drift, latency, and business KPIs

## MLflow Tracking

All experiments are tracked in MLflow:

- **Tracking URI**: Set via `MLFLOW_TRACKING_URI` environment variable
- **Artifacts**: Stored in GCS (`gs://instacommerce-ml/mlflow-artifacts/`)
- **Model Registry**: MLflow Model Registry for versioning and stage transitions

## Infrastructure

- **Training**: Google Cloud Vertex AI Training (GPU instances for NCF/TFT)
- **Serving**: Vertex AI Endpoints with ONNX Runtime
- **Feature Store**: Feast on GCP (BigQuery offline, Redis online)
- **Orchestration**: Cloud Composer (Airflow) DAGs
- **Monitoring**: Prometheus + Grafana for model metrics
