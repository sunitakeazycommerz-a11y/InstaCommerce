package com.instacommerce.catalog.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.instacommerce.catalog.domain.model.OutboxEvent;
import java.util.LinkedHashMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Primary;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

@Primary
@Component
public class KafkaOutboxEventPublisher implements OutboxEventPublisher {
    private static final Logger log = LoggerFactory.getLogger(KafkaOutboxEventPublisher.class);
    private static final String TOPIC = "catalog.events";

    private final KafkaTemplate<String, Object> kafkaTemplate;
    private final ObjectMapper objectMapper;

    public KafkaOutboxEventPublisher(KafkaTemplate<String, Object> kafkaTemplate,
                                     ObjectMapper objectMapper) {
        this.kafkaTemplate = kafkaTemplate;
        this.objectMapper = objectMapper;
    }

    @Override
    public void publish(OutboxEvent event) {
        Map<String, Object> envelope = buildEnvelope(event);
        String key = event.getAggregateId();

        log.info("Publishing outbox event to Kafka: topic={} key={} eventType={}",
            TOPIC, key, event.getEventType());

        kafkaTemplate.send(TOPIC, key, envelope)
            .whenComplete((result, ex) -> {
                if (ex != null) {
                    log.error("Failed to publish outbox event id={} to Kafka",
                        event.getId(), ex);
                    throw new RuntimeException("Kafka publish failed for event " + event.getId(), ex);
                }
                log.debug("Published outbox event id={} to Kafka partition={} offset={}",
                    event.getId(),
                    result.getRecordMetadata().partition(),
                    result.getRecordMetadata().offset());
            });
    }

    private Map<String, Object> buildEnvelope(OutboxEvent event) {
        Map<String, Object> envelope = new LinkedHashMap<>();
        envelope.put("eventId", event.getId().toString());
        envelope.put("eventType", event.getEventType());
        envelope.put("aggregateType", event.getAggregateType());
        envelope.put("aggregateId", event.getAggregateId());
        envelope.put("eventTime", event.getCreatedAt().toString());
        envelope.put("schemaVersion", "v1");
        envelope.put("sourceService", "catalog-service");
        envelope.put("payload", parsePayload(event.getPayload()));
        return envelope;
    }

    private Map<String, Object> parsePayload(String payload) {
        try {
            return objectMapper.readValue(payload, new TypeReference<>() {});
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("Failed to parse outbox event payload", ex);
        }
    }
}
