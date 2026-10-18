"""Feature Store module for AI inference service."""

from .redis_client import RedisFeatureStoreClient

__all__ = ["RedisFeatureStoreClient"]
