from typing import Any

def init(*args: Any, **kwargs: Any) -> None: ...

class CustomJob:
    resource_name: str
    def __init__(self, *args: Any, **kwargs: Any) -> None: ...
    def run(self, *args: Any, **kwargs: Any) -> None: ...

class Model:
    @classmethod
    def upload(cls, *args: Any, **kwargs: Any) -> Model: ...

class Endpoint:
    @classmethod
    def list(cls, *args: Any, **kwargs: Any) -> list[Endpoint]: ...
    def deploy(self, *args: Any, **kwargs: Any) -> None: ...

class EntityType:
    display_name: str
    gca_resource: Any
    def __init__(self, *args: Any, **kwargs: Any) -> None: ...
    def ingest_from_bq(self, *args: Any, **kwargs: Any) -> None: ...

class Featurestore:
    display_name: str
    @classmethod
    def list(cls, *args: Any, **kwargs: Any) -> list[Featurestore]: ...
    def list_entity_types(self, *args: Any, **kwargs: Any) -> list[EntityType]: ...

class _FeaturestoreModule:
    EntityType: type[EntityType]

featurestore: _FeaturestoreModule
