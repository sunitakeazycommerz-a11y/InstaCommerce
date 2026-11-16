"""Kafka consumer lag sensor for Airflow DAGs."""
from airflow.sensors.base import BaseSensorOperator


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
            from confluent_kafka import Consumer
            from confluent_kafka.admin import AdminClient

            client = AdminClient({"bootstrap.servers": self.bootstrap_servers})
            consumer = Consumer(
                {
                    "bootstrap.servers": self.bootstrap_servers,
                    "group.id": "airflow-kafka-lag-sensor",
                    "enable.auto.commit": False,
                }
            )
            try:
                consumer_groups = client.list_consumer_groups().result()
                max_observed_lag = 0
                found_partition = False

                for group in consumer_groups.valid:
                    futures_dict = client.list_consumer_group_offsets([group.group_id])
                    group_lag = 0
                    group_has_topic = False
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
                            if tp.topic == self.topic and offset_info.offset >= 0:
                                _, high_watermark = consumer.get_watermark_offsets(tp)
                                if high_watermark >= 0:
                                    group_lag += max(0, high_watermark - offset_info.offset)
                                    group_has_topic = True
                                    found_partition = True
                    if group_has_topic:
                        max_observed_lag = max(max_observed_lag, group_lag)
            finally:
                consumer.close()

            if not found_partition:
                self.log.warning("No committed Kafka offsets found for topic %s", self.topic)
                return False

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
