package com.instacommerce.audit.consumer;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.instacommerce.audit.dto.AuditEventRequest;
import com.instacommerce.audit.service.AuditIngestionService;
import com.instacommerce.contracts.topics.TopicNames;
import java.util.Map;
import java.util.UUID;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.env.Environment;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

@Component
public class DomainEventConsumer {

    private static final Logger log = LoggerFactory.getLogger(DomainEventConsumer.class);
    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {};

    private final AuditIngestionService ingestionService;
    private final ObjectMapper objectMapper;
    private final KafkaTemplate<String, String> kafkaTemplate;
    private final String dltTopic;

    public DomainEventConsumer(AuditIngestionService ingestionService,
                               ObjectMapper objectMapper,
                               KafkaTemplate<String, String> kafkaTemplate,
                               Environment env) {
        this.ingestionService = ingestionService;
        this.objectMapper = objectMapper;
        this.kafkaTemplate = kafkaTemplate;
        this.dltTopic = env.getProperty("audit.dlt-topic", TopicNames.AUDIT_DLT);
    }

    @KafkaListener(
            topics = {
                    TopicNames.IDENTITY_EVENTS,
                    TopicNames.CATALOG_EVENTS,
                    TopicNames.ORDERS_EVENTS,
                    TopicNames.PAYMENTS_EVENTS,
                    TopicNames.INVENTORY_EVENTS,
                    TopicNames.FULFILLMENT_EVENTS,
                    TopicNames.RIDER_EVENTS,
                    TopicNames.NOTIFICATION_EVENTS,
                    TopicNames.SEARCH_EVENTS,
                    TopicNames.PRICING_EVENTS,
                    TopicNames.PROMOTION_EVENTS,
                    TopicNames.CUSTOMER_SUPPORT_EVENTS,
                    TopicNames.RETURNS_EVENTS,
                    TopicNames.WAREHOUSE_EVENTS,
                    TopicNames.AI_ORCHESTRATOR_EVENTS,
                    TopicNames.RECONCILIATION_EVENTS,
                    TopicNames.WALLET_EVENTS,
                    TopicNames.FRAUD_EVENTS,
                    TopicNames.CART_EVENTS,
                    TopicNames.DELIVERY_EVENTS,
                    TopicNames.RIDER_GEOFENCE_EVENTS
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
            sendToDlt(record);
        }
    }

    private AuditEventRequest transformToAuditEvent(String topic, JsonNode envelope) {
        String eventType = envelope.has("eventType") ? envelope.get("eventType").asText()
                : envelope.has("event_type") ? envelope.get("event_type").asText() : "UNKNOWN";

        String sourceService = null;
        if (envelope.has("source_service") && !envelope.get("source_service").isNull()) {
            sourceService = envelope.get("source_service").asText();
        } else if (envelope.has("sourceService") && !envelope.get("sourceService").isNull()) {
            sourceService = envelope.get("sourceService").asText();
        } else {
            sourceService = deriveSourceService(topic);
        }

        UUID actorId = extractUuidField(envelope, "userId");
        if (actorId == null && envelope.has("payload") && envelope.get("payload").isObject()) {
            actorId = extractUuidField(envelope.get("payload"), "userId");
        }

        String actorType = envelope.has("actorType") ? envelope.get("actorType").asText()
                : envelope.has("actor_type") ? envelope.get("actor_type").asText() : "SYSTEM";

        String aggregateType = envelope.has("aggregateType") ? envelope.get("aggregateType").asText()
                : envelope.has("aggregate_type") ? envelope.get("aggregate_type").asText() : null;
        String aggregateId = envelope.has("aggregateId") ? envelope.get("aggregateId").asText()
                : envelope.has("aggregate_id") ? envelope.get("aggregate_id").asText() : null;

        String correlationId = envelope.has("correlation_id") ? envelope.get("correlation_id").asText()
                : envelope.has("correlationId") ? envelope.get("correlationId").asText() : null;

        String action = envelope.has("action") ? envelope.get("action").asText() : eventType;

        Map<String, Object> details = null;
        if (envelope.has("payload") && !envelope.get("payload").isNull()) {
            details = objectMapper.convertValue(envelope.get("payload"), MAP_TYPE);
        } else if (envelope.isObject()) {
            details = objectMapper.convertValue(envelope, MAP_TYPE);
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

    private static UUID extractUuidField(JsonNode node, String fieldName) {
        if (node == null || !node.has(fieldName)) {
            return null;
        }
        JsonNode field = node.get(fieldName);
        if (field == null || field.isNull()) {
            return null;
        }
        String raw = field.asText(null);
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return UUID.fromString(raw.trim());
        } catch (IllegalArgumentException ex) {
            log.debug("Ignoring invalid UUID value '{}' for field '{}'", raw, fieldName, ex);
            return null;
        }
    }

    private String deriveSourceService(String topic) {
        int dotIndex = topic.indexOf('.');
        if (dotIndex <= 0) {
            return topic;
        }
        String service = topic.substring(0, dotIndex);
        if ("orders".equals(service)) {
            return "order-service";
        } else if ("payments".equals(service)) {
            return "payment-service";
        } else if ("delivery".equals(service) || "routing".equals(service)) {
            return "routing-eta-service";
        } else if ("reconciliation".equals(service)) {
            return "reconciliation-engine";
        } else if (service.endsWith("-service") || service.endsWith("-engine")) {
            return service;
        }
        return service + "-service";
    }

    private void sendToDlt(ConsumerRecord<String, String> record) {
        try {
            kafkaTemplate.send(dltTopic, record.key(), record.value());
            log.warn("Sent failed event to DLT: topic={} offset={}", record.topic(), record.offset());
        } catch (Exception ex) {
            log.error("Failed to send event to DLT: topic={} offset={}", record.topic(), record.offset(), ex);
        }
    }
}
