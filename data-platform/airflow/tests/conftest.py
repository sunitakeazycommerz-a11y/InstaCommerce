import os
import sys
import types
from typing import Any
from unittest.mock import MagicMock


def _stub_module(name: str, **attrs: Any) -> Any:
    module = types.ModuleType(name)
    for attr_name, attr_value in attrs.items():
        setattr(module, attr_name, attr_value)
    return module


class _MockOperator:
    def __init__(
        self,
        task_id: str = "",
        trigger_rule: str = "all_success",
        python_callable: Any = None,
        op_kwargs: dict[str, Any] | None = None,
        **kwargs: Any,
    ) -> None:
        self.task_id = task_id
        self.trigger_rule = trigger_rule
        self.python_callable = python_callable
        self.op_kwargs = op_kwargs or {}
        self.kwargs = kwargs
        self.log = MagicMock()
        self.upstream_task_ids: set[str] = set()
        self.downstream_task_ids: set[str] = set()
        if _MockDAG._current is not None and task_id:
            _MockDAG._current.task_dict[task_id] = self

    def _link_downstream(self, other: Any) -> None:
        targets = other if isinstance(other, (list, tuple)) else [other]
        for target in targets:
            if isinstance(target, _MockOperator):
                self.downstream_task_ids.add(target.task_id)
                target.upstream_task_ids.add(self.task_id)

    def __rshift__(self, other: Any) -> Any:
        self._link_downstream(other)
        return other

    def __rrshift__(self, other: Any) -> "_MockOperator":
        sources = other if isinstance(other, (list, tuple)) else [other]
        for source in sources:
            if isinstance(source, _MockOperator):
                source._link_downstream(self)
        return self


class _MockDAG:
    _current: "_MockDAG | None" = None

    def __init__(self, dag_id: str = "", **kwargs: Any) -> None:
        self.dag_id = dag_id
        self.kwargs = kwargs
        self.task_dict: dict[str, _MockOperator] = {}
        self._previous: _MockDAG | None = None

    def __enter__(self) -> "_MockDAG":
        self._previous = _MockDAG._current
        _MockDAG._current = self
        return self

    def __exit__(self, exc_type: Any, exc_val: Any, exc_tb: Any) -> None:
        _MockDAG._current = self._previous


class _MockTaskGroup:
    def __init__(self, group_id: str = "", **kwargs: Any) -> None:
        self.group_id = group_id
        self.kwargs = kwargs

    def __enter__(self) -> "_MockTaskGroup":
        return self

    def __exit__(self, exc_type: Any, exc_val: Any, exc_tb: Any) -> None:
        return None


def _install_airflow_stubs() -> None:
    if "airflow" not in sys.modules:
        mock_bash = _stub_module("airflow.operators.bash", BashOperator=_MockOperator)
        mock_python = _stub_module(
            "airflow.operators.python",
            PythonOperator=_MockOperator,
            BranchPythonOperator=_MockOperator,
        )
        mock_operators = _stub_module(
            "airflow.operators",
            bash=mock_bash,
            python=mock_python,
        )
        mock_sensors_base = _stub_module(
            "airflow.sensors.base",
            BaseSensorOperator=_MockOperator,
        )
        mock_external_task = _stub_module(
            "airflow.sensors.external_task",
            ExternalTaskSensor=_MockOperator,
        )
        mock_sensors = _stub_module(
            "airflow.sensors",
            base=mock_sensors_base,
            external_task=mock_external_task,
        )
        mock_hooks_base = _stub_module("airflow.hooks.base", BaseHook=MagicMock())
        mock_hooks = _stub_module("airflow.hooks", base=mock_hooks_base)
        mock_models = _stub_module(
            "airflow.models",
            DagRun=MagicMock(),
            Variable=MagicMock(get=MagicMock(return_value=None)),
        )
        mock_slack_webhook = _stub_module(
            "airflow.providers.slack.hooks.slack_webhook",
            SlackWebhookHook=MagicMock(),
        )
        mock_slack_hooks = _stub_module(
            "airflow.providers.slack.hooks",
            slack_webhook=mock_slack_webhook,
        )
        mock_slack = _stub_module("airflow.providers.slack", hooks=mock_slack_hooks)
        mock_providers = _stub_module("airflow.providers", slack=mock_slack)
        mock_task_group = _stub_module("airflow.utils.task_group", TaskGroup=_MockTaskGroup)
        mock_utils = _stub_module("airflow.utils", task_group=mock_task_group)
        mock_airflow = _stub_module(
            "airflow",
            DAG=_MockDAG,
            operators=mock_operators,
            sensors=mock_sensors,
            hooks=mock_hooks,
            models=mock_models,
            providers=mock_providers,
            utils=mock_utils,
        )

        sys.modules["airflow"] = mock_airflow
        sys.modules["airflow.operators"] = mock_operators
        sys.modules["airflow.operators.bash"] = mock_bash
        sys.modules["airflow.operators.python"] = mock_python
        sys.modules["airflow.sensors"] = mock_sensors
        sys.modules["airflow.sensors.base"] = mock_sensors_base
        sys.modules["airflow.sensors.external_task"] = mock_external_task
        sys.modules["airflow.hooks"] = mock_hooks
        sys.modules["airflow.hooks.base"] = mock_hooks_base
        sys.modules["airflow.models"] = mock_models
        sys.modules["airflow.providers"] = mock_providers
        sys.modules["airflow.providers.slack"] = mock_slack
        sys.modules["airflow.providers.slack.hooks"] = mock_slack_hooks
        sys.modules["airflow.providers.slack.hooks.slack_webhook"] = mock_slack_webhook
        sys.modules["airflow.utils"] = mock_utils
        sys.modules["airflow.utils.task_group"] = mock_task_group

    airflow_dir = os.path.abspath(os.path.join(os.path.dirname(__file__), ".."))
    if airflow_dir not in sys.path:
        sys.path.insert(0, airflow_dir)


_install_airflow_stubs()
