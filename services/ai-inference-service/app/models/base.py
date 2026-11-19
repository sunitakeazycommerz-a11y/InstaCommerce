from pydantic import BaseModel, ConfigDict

from app.models.onnx_runner import (
    ManagedOnnxSession,
    check_memory_threshold,
    get_current_rss_bytes,
)


class MLBaseModel(BaseModel):
    """Base Pydantic model with protected_namespaces disabled for model_ prefix compatibility."""
    model_config = ConfigDict(protected_namespaces=())


__all__ = [
    "MLBaseModel",
    "ManagedOnnxSession",
    "check_memory_threshold",
    "get_current_rss_bytes",
]

