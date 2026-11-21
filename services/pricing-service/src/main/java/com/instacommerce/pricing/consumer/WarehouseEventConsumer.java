package com.instacommerce.pricing.consumer;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.instacommerce.contracts.topics.TopicNames;
import com.instacommerce.pricing.service.StoreSurgeStateStore;
import java.math.BigDecimal;
import java.util.Map;
import java.util.UUID;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

@Component
public class WarehouseEventConsumer {

    private static final Logger log = LoggerFactory.getLogger(WarehouseEventConsumer.class);

    private final ObjectMapper objectMapper;
    private final StoreSurgeStateStore storeSurgeStateStore;

    @Autowired
    public WarehouseEventConsumer(ObjectMapper objectMapper, StoreSurgeStateStore storeSurgeStateStore) {
        this.objectMapper = objectMapper != null ? objectMapper : new ObjectMapper();
        this.storeSurgeStateStore = storeSurgeStateStore;
    }

    public WarehouseEventConsumer(StoreSurgeStateStore storeSurgeStateStore) {
        this(new ObjectMapper(), storeSurgeStateStore);
    }

    @KafkaListener(topics = TopicNames.WAREHOUSE_EVENTS, groupId = "pricing-service-warehouse")
    public void onWarehouseEvent(ConsumerRecord<String, String> record) {
        if (record == null || record.value() == null || record.value().isBlank()) {
            log.warn("Received empty warehouse event record, skipping");
            return;
        }
        handleEvent(record.value());
    }

    public void handleEvent(Map<String, Object> envelope) {
        if (envelope == null || envelope.isEmpty()) {
            return;
        }
        handleJsonNode(objectMapper.valueToTree(envelope));
    }

    public void handleEvent(String rawJson) {
        if (rawJson == null || rawJson.isBlank()) {
            return;
        }
        try {
            JsonNode root = objectMapper.readTree(rawJson);
            handleJsonNode(root);
        } catch (JsonProcessingException ex) {
            log.error("Failed to deserialize warehouse event JSON", ex);
            throw new IllegalStateException("Failed to deserialize warehouse event JSON", ex);
        }
    }

    private void handleJsonNode(JsonNode root) {
        if (root == null || !root.isObject()) {
            return;
        }
        String eventType = textValue(root, "eventType", "event_type");
        if (eventType == null || eventType.isBlank()) {
            log.warn("Received warehouse event without eventType, skipping");
            return;
        }

        JsonNode payload = root.has("payload") && root.get("payload").isObject()
                ? root.get("payload")
                : root;

        UUID storeId = extractStoreId(payload, root);
        if (storeId == null) {
            log.warn("Warehouse event type={} missing valid storeId, skipping", eventType);
            return;
        }

        switch (eventType) {
            case "StoreSurgeModeChanged" -> handleSurgeModeChanged(storeId, payload);
            case "StoreCapacityThrottled" -> handleCapacityThrottled(storeId, payload);
            case "StoreCapacityPaused" -> handleCapacityPaused(storeId, payload);
            default -> log.debug("Ignoring unhandled warehouse event type={}", eventType);
        }
    }

    private void handleSurgeModeChanged(UUID storeId, JsonNode payload) {
        Boolean surgeEnabled = booleanValue(payload, "surgeModeEnabled", "enabled", "surgeMode");
        BigDecimal explicitMultiplier = decimalValue(payload, "surgeMultiplier", "multiplier");
        Integer etaExtensionMinutes = intValue(payload, "etaExtensionMinutes", "eta_extension_minutes");
        boolean enabled = surgeEnabled != null
                ? surgeEnabled
                : (explicitMultiplier != null && explicitMultiplier.compareTo(BigDecimal.ONE) > 0);
        storeSurgeStateStore.recordSurgeModeChanged(storeId, enabled, explicitMultiplier, etaExtensionMinutes);
    }

    private void handleCapacityThrottled(UUID storeId, JsonNode payload) {
        Integer currentPending = intValue(payload, "currentPending", "current_pending");
        Integer maxConcurrent = intValue(payload, "maxConcurrent", "max_concurrent");
        Boolean surgeMode = booleanValue(payload, "surgeMode", "surgeModeEnabled");
        Integer etaExtensionMinutes = intValue(payload, "etaExtensionMinutes", "eta_extension_minutes");
        BigDecimal explicitMultiplier = decimalValue(payload, "surgeMultiplier", "multiplier");
        storeSurgeStateStore.recordCapacityThrottled(
                storeId, currentPending, maxConcurrent, surgeMode, etaExtensionMinutes, explicitMultiplier);
    }

    private void handleCapacityPaused(UUID storeId, JsonNode payload) {
        Integer currentPending = intValue(payload, "currentPending", "current_pending");
        Integer maxConcurrent = intValue(payload, "maxConcurrent", "maxOrdersPerHour");
        BigDecimal explicitMultiplier = decimalValue(payload, "surgeMultiplier", "multiplier");
        storeSurgeStateStore.recordCapacityPaused(storeId, currentPending, maxConcurrent, explicitMultiplier);
    }

    private static UUID extractStoreId(JsonNode payload, JsonNode root) {
        String raw = textValue(payload, "storeId", "store_id");
        if (raw == null) {
            raw = textValue(root, "aggregateId", "aggregate_id");
        }
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return UUID.fromString(raw.trim());
        } catch (IllegalArgumentException ex) {
            log.warn("Invalid storeId UUID '{}' in warehouse event", raw);
            return null;
        }
    }

    private static String textValue(JsonNode node, String... fieldNames) {
        for (String field : fieldNames) {
            JsonNode child = node.get(field);
            if (child != null && !child.isNull() && !child.asText().isBlank()) {
                return child.asText();
            }
        }
        return null;
    }

    private static Integer intValue(JsonNode node, String... fieldNames) {
        for (String field : fieldNames) {
            JsonNode child = node.get(field);
            if (child != null && !child.isNull()) {
                if (child.isNumber()) {
                    return child.intValue();
                }
                if (child.isTextual() && !child.asText().isBlank()) {
                    try {
                        return Integer.parseInt(child.asText().trim());
                    } catch (NumberFormatException ex) {
                        log.debug("Ignoring non-integer field {}='{}'", field, child.asText());
                    }
                }
            }
        }
        return null;
    }

    private static Boolean booleanValue(JsonNode node, String... fieldNames) {
        for (String field : fieldNames) {
            JsonNode child = node.get(field);
            if (child != null && !child.isNull()) {
                if (child.isBoolean()) {
                    return child.booleanValue();
                }
                if (child.isTextual() && !child.asText().isBlank()) {
                    return Boolean.parseBoolean(child.asText().trim());
                }
            }
        }
        return null;
    }

    private static BigDecimal decimalValue(JsonNode node, String... fieldNames) {
        for (String field : fieldNames) {
            JsonNode child = node.get(field);
            if (child != null && !child.isNull()) {
                if (child.isNumber()) {
                    return child.decimalValue();
                }
                if (child.isTextual() && !child.asText().isBlank()) {
                    try {
                        return new BigDecimal(child.asText().trim());
                    } catch (NumberFormatException ex) {
                        log.debug("Ignoring invalid decimal field {}='{}'", field, child.asText());
                    }
                }
            }
        }
        return null;
    }
}
