# Stream Processor Service - Low-Level Design

```mermaid
classDiagram
    class Config {
        -kafka_brokers: []string
        -kafka_group_id: string
        -kafka_topics: []string
        -redis_url: string
        -sla_window_minutes: int
        -sla_threshold_percent: float
        -dedup_cache_size: int
    }

    class KafkaConsumer {
        -reader: kafka.Reader
        -topics: []string
        +FetchMessages() []Message
        +CommitOffsets()
    }

    class DomainEvent {
        -event_type: string
        -aggregate_id: string
        -timestamp: int64
        -data: JSON
    }

    class EventProcessor {
        -process_type: string
        +Process(event) Metric
    }

    class OrderProcessor {
        +Process(event) OrderMetric
        -aggregateOrderMetrics()
    }

    class PaymentProcessor {
        +Process(event) PaymentMetric
        -aggregatePaymentMetrics()
    }

    class RiderProcessor {
        +Process(event) RiderMetric
        -aggregateRiderMetrics()
    }

    class InventoryProcessor {
        +Process(event) InventoryMetric
        -aggregateInventoryMetrics()
    }

    class DeduplicationManager {
        -processed_offsets: map[string]int64
        +ShouldProcess(topic, partition, offset) bool
        +MarkProcessed(topic, partition, offset)
    }

    class WindowAggregator {
        -window_size: Duration
        -windows: map[WindowKey]Aggregation
        +AddEvent(event, metric)
        +GetAggregation(key) Aggregation
        -evictStaleWindows()
    }

    class SLAMonitor {
        -window_size: Duration
        -zone_thresholds: map[string]float
        +EvaluateSLA(metrics) bool
        +RecordSLABreach(zone, actual)
    }

    class RedisWriter {
        -redis_client: RedisClient
        -ttl: Duration
        +WriteMetric(key, value)
        +WriteGauge(key, value)
        +WriteHistogram(key, buckets)
    }

    class MetricsCollector {
        -event_counter: Counter
        -processing_latency: Histogram
        -sla_breaches: Counter
        -window_aggregation: Gauge
        +RecordEvent(type, latency)
        +RecordSLABreach()
    }

    Config --> KafkaConsumer
    KafkaConsumer --> DomainEvent
    DomainEvent --> EventProcessor
    EventProcessor <|-- OrderProcessor
    EventProcessor <|-- PaymentProcessor
    EventProcessor <|-- RiderProcessor
    EventProcessor <|-- InventoryProcessor
    OrderProcessor --> DeduplicationManager
    PaymentProcessor --> DeduplicationManager
    RiderProcessor --> DeduplicationManager
    InventoryProcessor --> DeduplicationManager
    DeduplicationManager --> WindowAggregator
    WindowAggregator --> SLAMonitor
    SLAMonitor --> RedisWriter
    EventProcessor --> MetricsCollector
```

## Component Responsibilities

| Component | Responsibility |
|-----------|-----------------|
| **KafkaConsumer** | Multi-topic Kafka consumption |
| **EventProcessor** | Domain-specific metric extraction |
| **OrderProcessor** | Order event aggregation (created, placed, delivered) |
| **PaymentProcessor** | Payment event aggregation (completed, failed) |
| **RiderProcessor** | Rider event aggregation (accepted, started, completed) |
| **InventoryProcessor** | Inventory event aggregation (reserved, released) |
| **DeduplicationManager** | Per-topic/partition/offset idempotent tracking |
| **WindowAggregator** | Sliding windows (30s, 5m, 1h) |
| **SLAMonitor** | Delivery compliance evaluation per zone |
| **RedisWriter** | TTL-bounded metrics cache |
| **MetricsCollector** | Prometheus emission |
