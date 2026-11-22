from typing import Any

class MlflowClient:
    def __init__(self, tracking_uri: str | None = ..., registry_uri: str | None = ...) -> None: ...
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
