from typing import Any

from airflow.models.baseoperator import BaseOperator

class BaseSensorOperator(BaseOperator):
    def poke(self, context: Any) -> bool: ...
