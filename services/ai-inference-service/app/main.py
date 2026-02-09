from __future__ import annotations

from math import exp
from pathlib import Path
from typing import Dict
import json

from fastapi import FastAPI
from pydantic import BaseModel, Field

app = FastAPI(title="AI Inference Service", version="1.0.0")

DEFAULT_WEIGHTS = {
    "eta": {
        "version": "eta-linear-v1",
        "bias": 4.5,
        "weights": {
            "distance_km": 2.3,
            "item_count": 0.6,
            "traffic_factor": 3.1,
        },
    },
    "ranking": {
        "version": "ranking-linear-v1",
        "bias": 0.15,
        "weights": {
            "relevance_score": 0.55,
            "price_score": 0.2,
            "availability_score": 0.35,
            "user_affinity": 0.4,
        },
    },
    "fraud": {
        "version": "fraud-logit-v1",
        "bias": -1.4,
        "weights": {
            "order_amount": 0.012,
            "chargeback_rate": 4.8,
            "device_risk": 2.1,
            "account_age_days": -0.015,
        },
    },
}

WEIGHTS_PATH = Path(__file__).resolve().parent / "weights.json"


def load_weights() -> Dict[str, Dict[str, object]]:
    if WEIGHTS_PATH.exists():
        with WEIGHTS_PATH.open("r", encoding="utf-8") as file:
            data = json.load(file)
        return {**DEFAULT_WEIGHTS, **data}
    return DEFAULT_WEIGHTS


MODEL_WEIGHTS = load_weights()


def linear_score(features: Dict[str, float], weights: Dict[str, float], bias: float) -> tuple[float, Dict[str, float]]:
    contributions = {name: float(features.get(name, 0.0)) * weight for name, weight in weights.items()}
    return bias + sum(contributions.values()), contributions


def safe_sigmoid(value: float) -> float:
    if value >= 50:
        return 1.0
    if value <= -50:
        return 0.0
    return 1.0 / (1.0 + exp(-value))


class EtaRequest(BaseModel):
    distance_km: float = Field(..., ge=0, le=200)
    item_count: int = Field(..., ge=0, le=200)
    traffic_factor: float = Field(..., ge=0.5, le=3.0)


class EtaResponse(BaseModel):
    eta_minutes: float
    feature_contributions: Dict[str, float]
    bias: float
    model_version: str


class RankingRequest(BaseModel):
    relevance_score: float = Field(..., ge=0, le=1)
    price_score: float = Field(..., ge=0, le=1)
    availability_score: float = Field(..., ge=0, le=1)
    user_affinity: float = Field(..., ge=0, le=1)


class RankingResponse(BaseModel):
    ranking_score: float
    raw_score: float
    feature_contributions: Dict[str, float]
    bias: float
    model_version: str


class FraudRequest(BaseModel):
    order_amount: float = Field(..., ge=0, le=10000)
    chargeback_rate: float = Field(..., ge=0, le=1)
    device_risk: float = Field(..., ge=0, le=1)
    account_age_days: int = Field(..., ge=0, le=36500)


class FraudResponse(BaseModel):
    fraud_probability: float
    raw_score: float
    feature_contributions: Dict[str, float]
    bias: float
    model_version: str


@app.get("/health")
def health() -> Dict[str, str]:
    return {"status": "ok"}


@app.post("/inference/eta", response_model=EtaResponse)
def eta_inference(request: EtaRequest) -> EtaResponse:
    model = MODEL_WEIGHTS["eta"]
    features = {
        "distance_km": request.distance_km,
        "item_count": float(request.item_count),
        "traffic_factor": request.traffic_factor,
    }
    raw_score, contributions = linear_score(features, model["weights"], model["bias"])
    eta_minutes = max(0.0, raw_score)
    return EtaResponse(
        eta_minutes=eta_minutes,
        feature_contributions=contributions,
        bias=model["bias"],
        model_version=model["version"],
    )


@app.post("/inference/ranking", response_model=RankingResponse)
def ranking_inference(request: RankingRequest) -> RankingResponse:
    model = MODEL_WEIGHTS["ranking"]
    features = {
        "relevance_score": request.relevance_score,
        "price_score": request.price_score,
        "availability_score": request.availability_score,
        "user_affinity": request.user_affinity,
    }
    raw_score, contributions = linear_score(features, model["weights"], model["bias"])
    ranking_score = safe_sigmoid(raw_score)
    return RankingResponse(
        ranking_score=ranking_score,
        raw_score=raw_score,
        feature_contributions=contributions,
        bias=model["bias"],
        model_version=model["version"],
    )


@app.post("/inference/fraud", response_model=FraudResponse)
def fraud_inference(request: FraudRequest) -> FraudResponse:
    model = MODEL_WEIGHTS["fraud"]
    features = {
        "order_amount": request.order_amount,
        "chargeback_rate": request.chargeback_rate,
        "device_risk": request.device_risk,
        "account_age_days": float(request.account_age_days),
    }
    raw_score, contributions = linear_score(features, model["weights"], model["bias"])
    fraud_probability = safe_sigmoid(raw_score)
    return FraudResponse(
        fraud_probability=fraud_probability,
        raw_score=raw_score,
        feature_contributions=contributions,
        bias=model["bias"],
        model_version=model["version"],
    )
