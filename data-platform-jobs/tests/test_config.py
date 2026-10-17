from data_platform_jobs.config import load_settings


def test_load_settings_default_topics():
    settings = load_settings()
    assert "orders.events" in settings.kafka_topics
    assert "payments.events" in settings.kafka_topics
    assert settings.project_id == "instacommerce-dev"

