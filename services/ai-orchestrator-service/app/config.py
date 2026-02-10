from typing import List, Optional

from pydantic import AnyHttpUrl, Field
from pydantic_settings import BaseSettings, SettingsConfigDict


class Settings(BaseSettings):
    model_config = SettingsConfigDict(env_prefix="AI_ORCHESTRATOR_", case_sensitive=False)

    app_name: str = "ai-orchestrator-service"
    log_level: str = "INFO"
    server_host: str = "0.0.0.0"
    server_port: int = 8100
    request_timeout_seconds: float = 3.0
    tool_call_timeout_seconds: float = 2.5
    tool_total_timeout_seconds: float = 6.0
    tool_call_max: int = 8
    tool_circuit_breaker_failures: int = 3
    tool_circuit_breaker_reset_seconds: float = 30.0
    tool_allowlist: List[str] = Field(
        default_factory=lambda: [
            "catalog.search",
            "catalog.get_product",
            "catalog.list_products",
            "pricing.calculate",
            "pricing.get_product",
            "inventory.check",
            "cart.get",
            "order.get",
        ]
    )
    pii_redaction_enabled: bool = True
    rag_cache_ttl_seconds: float = 300.0
    rag_cache_max_entries: int = 256
    rag_max_results: int = 5
    otel_enabled: bool = True
    otel_service_name: str = "ai-orchestrator-service"
    otel_exporter_otlp_endpoint: Optional[AnyHttpUrl] = None

    internal_service_name: str = "ai-orchestrator-service"
    internal_service_token: Optional[str] = None
    user_id_header_name: str = "X-User-Id"

    catalog_service_url: AnyHttpUrl = "http://catalog-service:8080"
    pricing_service_url: AnyHttpUrl = "http://pricing-service:8087"
    inventory_service_url: AnyHttpUrl = "http://inventory-service:8080"
    cart_service_url: AnyHttpUrl = "http://cart-service:8088"
    order_service_url: AnyHttpUrl = "http://order-service:8080"

    llm_api_url: Optional[AnyHttpUrl] = None
    llm_api_key: Optional[str] = None
    llm_model: Optional[str] = None
