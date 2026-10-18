package com.instacommerce.catalog.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.instacommerce.catalog.domain.model.OutboxEvent;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.context.annotation.Primary;
import org.springframework.kafka.core.KafkaTemplate;
import com.instacommerce.contracts.topics.TopicNames;
import java.util.concurrent.TimeUnit;
import org.springframework.kafka.support.SendResult;
import org.springframework.stereotype.Component;

@Primary
@Component
@ConditionalOnBean(KafkaTemplate.class)
public class KafkaOutboxEventPublisher implements OutboxEventPublisher {
    private static final Logger log = LoggerFactory.getLogger(KafkaOutboxEventPublisher.class);
    private static final String TOPIC = TopicNames.CATALOG_EVENTS;

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

        try {
            SendResult<String, Object> result = kafkaTemplate.send(TOPIC, key, envelope)
                .get(5, TimeUnit.SECONDS);
            log.debug("Published outbox event id={} to Kafka partition={} offset={}",
                event.getId(),
                result.getRecordMetadata().partition(),
                result.getRecordMetadata().offset());
        } catch (Exception ex) {
            log.error("Failed to publish outbox event id={} to Kafka topic={}", event.getId(), TOPIC, ex);
            throw new RuntimeException("Kafka publish failed for event " + event.getId(), ex);
        }
    }

    private Map<String, Object> buildEnvelope(OutboxEvent event) {
        Map<String, Object> envelope = new LinkedHashMap<>();
        String eventId = event.getEventId() != null ? event.getEventId().toString() : event.getId().toString();
        envelope.put("id", eventId);
        envelope.put("eventId", eventId);
        envelope.put("eventType", event.getEventType());
        envelope.put("aggregateType", event.getAggregateType());
        envelope.put("aggregateId", event.getAggregateId());
        Instant time = event.getEventTimestamp() != null ? event.getEventTimestamp() : event.getCreatedAt();
        envelope.put("eventTime", time != null ? time.toString() : Instant.now().toString());
        envelope.put("schemaVersion", event.getSchemaVersion() != null ? event.getSchemaVersion() : "v1");
        envelope.put("source_service", event.getSourceService() != null ? event.getSourceService() : "catalog-service");
        envelope.put("sourceService", event.getSourceService() != null ? event.getSourceService() : "catalog-service");
        if (event.getCorrelationId() != null) {
            envelope.put("correlation_id", event.getCorrelationId());
            envelope.put("correlationId", event.getCorrelationId());
        }
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
