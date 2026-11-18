from datetime import datetime
from typing import Any

from airflow.models.baseoperator import BaseOperator

class DagRun:
    execution_date: datetime
    start_date: datetime | None
    end_date: datetime | None
    @classmethod
    def find(cls, *args: Any, **kwargs: Any) -> list[DagRun]: ...

class Variable:
    @classmethod
    def get(cls, key: str, default_var: Any = ..., deserialize_json: bool = ...) -> Any: ...

__all__ = ["BaseOperator", "DagRun", "Variable"]
