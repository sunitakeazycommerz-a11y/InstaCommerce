from logging import Logger
from typing import Any

from airflow.models.taskmixin import DependencyMixin

class BaseOperator(DependencyMixin):
    task_id: str
    log: Logger
    trigger_rule: str
    upstream_task_ids: set[str]
    def __init__(self, *args: Any, **kwargs: Any) -> None: ...
