"""Kafka consumer lag sensor and shared lag collection helper for Airflow DAGs."""

from __future__ import annotations

from typing import Dict, Sequence

from airflow.sensors.base import BaseSensorOperator

try:
    from confluent_kafka import Consumer  # type: ignore[import-not-found]
    from confluent_kafka.admin import AdminClient  # type: ignore[import-not-found]
except ImportError:
    Consumer = None  # type: ignore[assignment,misc]
    AdminClient = None  # type: ignore[assignment,misc]


def collect_topic_lags(
    bootstrap_servers: str,
    topics: Sequence[str],
    consumer_group_id: str = "airflow-kafka-lag-sensor",
) -> Dict[str, Dict[str, int]]:
    """Collect per-topic consumer-group lag in O(G) admin calls."""
    if AdminClient is None or Consumer is None:
        raise ImportError("confluent_kafka is required for Kafka lag inspection")

    topic_set = set(topics)
    lags_by_topic: Dict[str, Dict[str, int]] = {topic: {} for topic in topic_set}

    client = AdminClient({"bootstrap.servers": bootstrap_servers})
    consumer = Consumer(
        {
            "bootstrap.servers": bootstrap_servers,
            "group.id": consumer_group_id,
            "enable.auto.commit": False,
        }
    )
    try:
        consumer_groups = client.list_consumer_groups().result()
        for group in consumer_groups.valid:
            futures_dict = client.list_consumer_group_offsets([group.group_id])
            for _, future in futures_dict.items():
                result_obj = future.result()
                offsets_iter = (
                    result_obj.items()
                    if isinstance(result_obj, dict)
                    else [
                        (tp, tp)
                        for tp in getattr(result_obj, "topic_partitions", []) or []
                    ]
                )
                for tp, offset_info in offsets_iter:
                    if tp.topic in topic_set and offset_info.offset >= 0:
                        watermarks = consumer.get_watermark_offsets(tp)
                        if watermarks and watermarks[1] >= 0:
                            lag = max(0, watermarks[1] - offset_info.offset)
                            topic_lags = lags_by_topic[tp.topic]
                            topic_lags[group.group_id] = topic_lags.get(group.group_id, 0) + lag
    finally:
        consumer.close()

    return lags_by_topic


class KafkaLagSensor(BaseSensorOperator):
    """Sensor that checks Kafka consumer lag before proceeding with daily dbt runs (Issue #655)."""

    def __init__(
        self,
        topic: str = "orders.events",
        max_lag: int = 100,
        bootstrap_servers: str = "kafka-bootstrap:9092",
        **kwargs,
    ):
        super().__init__(**kwargs)
        self.topic = topic
        self.max_lag = max_lag
        self.bootstrap_servers = bootstrap_servers

    def poke(self, context):
        try:
            lags_by_topic = collect_topic_lags(
                self.bootstrap_servers,
                [self.topic],
                consumer_group_id="airflow-kafka-lag-sensor",
            )
            group_lags = lags_by_topic.get(self.topic, {})
            if not group_lags:
                self.log.warning("No committed Kafka offsets found for topic %s", self.topic)
                return False

            max_observed_lag = max(group_lags.values())
            within_tolerance = max_observed_lag <= self.max_lag
            self.log.info(
                "Kafka lag for topic %s: %d (max_lag=%d, within_tolerance=%s)",
                self.topic,
                max_observed_lag,
                self.max_lag,
                within_tolerance,
            )
            return within_tolerance
        except Exception as e:
            self.log.warning("Kafka lag probe failed: %s", e)
            return False
