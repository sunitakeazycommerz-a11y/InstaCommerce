package com.instacommerce.audit.consumer;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.instacommerce.audit.dto.AuditEventRequest;
import com.instacommerce.audit.service.AuditIngestionService;
import java.util.Map;
import java.util.UUID;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

@Component
public class DomainEventConsumer {

    private static final Logger log = LoggerFactory.getLogger(DomainEventConsumer.class);

    private final AuditIngestionService ingestionService;
    private final ObjectMapper objectMapper;
    private final KafkaTemplate<String, String> kafkaTemplate;
    private final String dlqTopic;

    public DomainEventConsumer(AuditIngestionService ingestionService,
                               ObjectMapper objectMapper,
                               KafkaTemplate<String, String> kafkaTemplate,
                               org.springframework.core.env.Environment env) {
        this.ingestionService = ingestionService;
        this.objectMapper = objectMapper;
        this.kafkaTemplate = kafkaTemplate;
        this.dlqTopic = env.getProperty("audit.dlq-topic", "audit.dlq");
    }

    @KafkaListener(
            topics = {
                    "identity.events",
                    "catalog.events",
                    "order.events",
                    "orders.events",
                    "payment.events",
                    "payments.events",
                    "inventory.events",
                    "fulfillment.events",
                    "rider.events",
                    "notification.events",
                    "search.events",
                    "pricing.events",
                    "promotion.events",
                    "customer-support.events",
                    "returns.events",
                    "warehouse.events"
            },
            groupId = "${spring.kafka.consumer.group-id}",
            concurrency = "3"
    )
    public void onDomainEvent(ConsumerRecord<String, String> record) {
        try {
            JsonNode envelope = objectMapper.readTree(record.value());
            AuditEventRequest request = transformToAuditEvent(record.topic(), envelope);
            ingestionService.ingest(request);
        } catch (Exception ex) {
            log.error("Failed to process domain event from topic={} offset={} partition={}: {}",
                    record.topic(), record.offset(), record.partition(), ex.getMessage(), ex);
            sendToDlq(record);
        }
    }

    private AuditEventRequest transformToAuditEvent(String topic, JsonNode envelope) {
        String eventType = envelope.has("eventType") ? envelope.get("eventType").asText() : "UNKNOWN";
        String sourceService = deriveSourceService(topic);

        UUID actorId = null;
        if (envelope.has("userId") && !envelope.get("userId").isNull()) {
            actorId = UUID.fromString(envelope.get("userId").asText());
        }

        String actorType = envelope.has("actorType") ? envelope.get("actorType").asText() : "SYSTEM";

        String aggregateType = envelope.has("aggregateType") ? envelope.get("aggregateType").asText() : null;
        String aggregateId = envelope.has("aggregateId") ? envelope.get("aggregateId").asText() : null;

        String correlationId = envelope.has("correlationId") ? envelope.get("correlationId").asText() : null;
        String action = envelope.has("action") ? envelope.get("action").asText() : eventType;

        Map<String, Object> details = null;
        if (envelope.has("payload")) {
            details = objectMapper.convertValue(envelope.get("payload"), Map.class);
        }

        return new AuditEventRequest(
                eventType,
                sourceService,
                actorId,
                actorType,
                aggregateType,
                aggregateId,
                action,
                details,
                null,
                null,
                correlationId
        );
    }

    private String deriveSourceService(String topic) {
        // topic format: "service.events" -> extract service name
        int dotIndex = topic.indexOf('.');
        if (dotIndex <= 0) {
            return topic;
        }
        String service = topic.substring(0, dotIndex);
        if ("orders".equals(service)) {
            service = "order";
        } else if ("payments".equals(service)) {
            service = "payment";
        }
        return service + "-service";
    }

    private void sendToDlq(ConsumerRecord<String, String> record) {
        try {
            kafkaTemplate.send(dlqTopic, record.key(), record.value());
            log.warn("Sent failed event to DLQ: topic={} offset={}", record.topic(), record.offset());
        } catch (Exception ex) {
            log.error("Failed to send event to DLQ: topic={} offset={}", record.topic(), record.offset(), ex);
        }
    }
}
