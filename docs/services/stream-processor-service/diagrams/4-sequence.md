# Stream Processor Service - Event Processing Sequence

```mermaid
sequenceDiagram
    participant KafkaCluster as Kafka
    participant Consumer as Consumer Group
    participant Processor as EventProcessor
    participant Dedup as Deduplication
    participant Window as WindowAggregator
    participant SLA as SLAMonitor
    participant Redis as Redis
    participant Prom as Prometheus

    KafkaCluster ->> Consumer: Poll Messages
    activate Consumer

    Consumer ->> Processor: Process Event
    activate Processor

    Processor ->> Dedup: ShouldProcess(topic, partition, offset)
    activate Dedup
    Dedup ->> Dedup: Check Offset Map
    alt Already Processed
        Dedup -->> Processor: false
        Processor -->> Consumer: Skip
    else New Event
        Dedup ->> Dedup: MarkProcessed()
        Dedup -->> Processor: true
    end
    deactivate Dedup

    alt Event Type = OrderPlaced
        Processor ->> Processor: Extract order_count, order_value
    else Event Type = PaymentCompleted
        Processor ->> Processor: Extract payment_count, payment_value
    else Event Type = RiderAccepted
        Processor ->> Processor: Extract rider_count, acceptance_rate
    else Event Type = InventoryReserved
        Processor ->> Processor: Extract item_count, reserve_rate
    end

    Processor ->> Window: AddEvent(window_key, metric)
    activate Window
    Window ->> Window: Update 30s, 5m, 1h windows
    Window -->> Processor: Aggregations
    deactivate Window

    Processor ->> SLA: EvaluateSLA(metrics)
    activate SLA
    SLA ->> SLA: Check delivery_compliance >= threshold
    alt SLA Breach
        SLA ->> Processor: SLA_BREACH
        Processor ->> Prom: sla_breaches_counter++
    else SLA OK
        SLA ->> Processor: OK
    end
    deactivate SLA

    Processor ->> Redis: WriteMetric(key, value)
    activate Redis
    Redis ->> Redis: HSET + TTL
    Redis -->> Processor: OK
    deactivate Redis

    Processor ->> Prom: event_counter++
    Processor ->> Prom: processing_latency.observe()
    activate Prom
    Prom -->> Processor: void
    deactivate Prom

    Processor -->> Consumer: Event Processed
    deactivate Processor

    Consumer ->> Consumer: CommitOffsets()
    Consumer -->> KafkaCluster: Offsets Committed
    deactivate Consumer
```

## Sequence Patterns

- **Consumer Polling**: Multi-partition polling with group coordination
- **Deduplication Check**: Early exit for already-processed events
- **Event Routing**: Type-based processor selection
- **Multi-Window Aggregation**: Simultaneous 30s/5m/1h updates
- **SLA Evaluation**: Real-time compliance checking
- **Redis Write**: Metrics cache update with TTL
- **Metrics Emission**: Prometheus counter and latency histograms
- **Offset Commit**: Only after successful processing
