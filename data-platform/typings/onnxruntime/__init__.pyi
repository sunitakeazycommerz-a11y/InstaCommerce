from typing import Any, Sequence

class NodeArg:
    name: str
    type: str
    shape: Sequence[Any]

class InferenceSession:
    def __init__(
        self,
        path_or_bytes: str | bytes,
        sess_options: Any = ...,
        providers: Sequence[str] | None = ...,
        **kwargs: Any,
    ) -> None: ...
    def get_inputs(self) -> list[NodeArg]: ...
    def run(
        self,
        output_names: Sequence[str] | None,
        input_feed: dict[str, Any],
        run_options: Any = ...,
    ) -> list[Any]: ...
