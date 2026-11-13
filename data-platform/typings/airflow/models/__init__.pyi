from datetime import datetime
from typing import Any

from airflow.models.baseoperator import BaseOperator

class DagRun:
    execution_date: datetime
    start_date: datetime | None
    end_date: datetime | None
    @classmethod
    def find(cls, *args: Any, **kwargs: Any) -> list[DagRun]: ...

__all__ = ["BaseOperator", "DagRun"]
