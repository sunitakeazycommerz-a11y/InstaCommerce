from pydantic import BaseModel, ConfigDict


class MLBaseModel(BaseModel):
    """Base Pydantic model with protected_namespaces disabled for model_ prefix compatibility."""
    model_config = ConfigDict(protected_namespaces=())

