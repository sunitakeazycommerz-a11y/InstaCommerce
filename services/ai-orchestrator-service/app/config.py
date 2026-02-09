from typing import Optional

from pydantic import AnyHttpUrl
from pydantic_settings import BaseSettings, SettingsConfigDict


class Settings(BaseSettings):
    model_config = SettingsConfigDict(env_prefix="AI_ORCHESTRATOR_", case_sensitive=False)

    app_name: str = "ai-orchestrator-service"
    log_level: str = "INFO"
    server_host: str = "0.0.0.0"
    server_port: int = 8100
    request_timeout_seconds: float = 3.0

    internal_service_name: str = "ai-orchestrator-service"
    internal_service_token: Optional[str] = "dev-internal-token-change-in-prod"
    user_id_header_name: str = "X-User-Id"

    catalog_service_url: AnyHttpUrl = "http://catalog-service:8080"
    pricing_service_url: AnyHttpUrl = "http://pricing-service:8087"
    inventory_service_url: AnyHttpUrl = "http://inventory-service:8080"
    cart_service_url: AnyHttpUrl = "http://cart-service:8088"
    order_service_url: AnyHttpUrl = "http://order-service:8080"

    llm_api_url: Optional[AnyHttpUrl] = None
    llm_api_key: Optional[str] = None
    llm_model: Optional[str] = None
