from typing import Any

class Connection:
    def get_uri(self) -> str: ...

class BaseHook:
    @classmethod
    def get_connection(cls, conn_id: str, *args: Any, **kwargs: Any) -> Connection: ...
