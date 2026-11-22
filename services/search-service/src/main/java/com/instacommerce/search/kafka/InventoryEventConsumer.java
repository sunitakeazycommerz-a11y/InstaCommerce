package com.instacommerce.search.kafka;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.instacommerce.contracts.topics.TopicNames;
import com.instacommerce.search.service.SearchIndexService;
import jakarta.annotation.Nullable;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.cache.CacheManager;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

@Component
public class InventoryEventConsumer {

    private static final Logger log = LoggerFactory.getLogger(InventoryEventConsumer.class);
    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {};

    private final SearchIndexService searchIndexService;
    private final CacheManager cacheManager;
    private final ObjectMapper objectMapper;

    @Autowired
    public InventoryEventConsumer(
            SearchIndexService searchIndexService,
            @Nullable CacheManager cacheManager,
            @Nullable ObjectMapper objectMapper) {
        this.searchIndexService = searchIndexService;
        this.cacheManager = cacheManager;
        this.objectMapper = objectMapper != null ? objectMapper : new ObjectMapper();
    }

    @KafkaListener(topics = TopicNames.INVENTORY_EVENTS, groupId = "search-service")
    public void handleInventoryEvent(Map<String, Object> envelope) {
        if (envelope == null || envelope.isEmpty()) {
            return;
        }
        String eventType = asString(envelope.get("eventType"));
        if (eventType == null) {
            eventType = asString(envelope.get("event_type"));
        }
        if (eventType == null) {
            log.warn("Received inventory event with no eventType, skipping");
            return;
        }

        Map<String, Object> payload = envelope.get("payload") instanceof Map<?, ?> nested
                ? objectMapper.convertValue(nested, MAP_TYPE)
                : envelope;

        switch (eventType) {
            case "StockAdjusted" -> {
                handleStockAdjusted(payload);
                evictSearchCaches();
            }
            case "LowStockAlert" -> {
                handleLowStockAlert(payload);
                evictSearchCaches();
            }
            case "StockConfirmed" -> {
                handleStockConfirmed(payload);
                evictSearchCaches();
            }
            case "StockReleased" -> {
                handleStockReleased(payload);
                evictSearchCaches();
            }
            case "OutOfStock" -> {
                handleOutOfStock(payload);
                evictSearchCaches();
            }
            case "ExpiredStockWrittenOff" -> {
                handleExpiredStockWrittenOff(payload);
                evictSearchCaches();
            }
            default -> log.debug("Ignoring unhandled inventory event type={}", eventType);
        }
    }

    private void evictSearchCaches() {
        if (cacheManager != null) {
            var searchCache = cacheManager.getCache("searchResults");
            if (searchCache != null) {
                searchCache.clear();
            }
            var autoCache = cacheManager.getCache("autocomplete");
            if (autoCache != null) {
                autoCache.clear();
            }
            log.info("Evicted searchResults and autocomplete caches following inventory change");
        }
    }

    private void handleStockAdjusted(Map<String, Object> payload) {
        UUID productId = extractProductId(payload);
        if (productId == null) {
            log.warn("StockAdjusted event missing productId");
            return;
        }
        Number newOnHand = firstNumber(payload, "newOnHand", "new_on_hand");
        if (newOnHand == null) {
            log.warn("StockAdjusted event for productId={} missing newOnHand", productId);
            return;
        }
        boolean inStock = newOnHand.intValue() > 0;
        searchIndexService.updateStockStatus(productId, inStock);
    }

    private void handleLowStockAlert(Map<String, Object> payload) {
        UUID productId = extractProductId(payload);
        if (productId == null) {
            log.warn("LowStockAlert event missing productId");
            return;
        }
        Number currentQuantity = firstNumber(payload, "currentQuantity", "current_quantity");
        if (currentQuantity == null) {
            log.warn("LowStockAlert event for productId={} missing currentQuantity", productId);
            return;
        }
        boolean inStock = currentQuantity.intValue() > 0;
        searchIndexService.updateStockStatus(productId, inStock);
    }

    private void handleOutOfStock(Map<String, Object> payload) {
        UUID productId = extractProductId(payload);
        if (productId == null) {
            log.warn("OutOfStock event missing productId");
            return;
        }
        searchIndexService.updateStockStatus(productId, false);
    }

    private void handleExpiredStockWrittenOff(Map<String, Object> payload) {
        UUID productId = extractProductId(payload);
        if (productId == null) {
            log.warn("ExpiredStockWrittenOff event missing productId");
            return;
        }
        Boolean explicitInStock = firstBoolean(payload, "inStock", "in_stock");
        if (explicitInStock != null) {
            searchIndexService.updateStockStatus(productId, explicitInStock);
            return;
        }
        Number remaining = firstNumber(
                payload,
                "remainingAvailable",
                "remaining_available",
                "remainingOnHand",
                "remaining_on_hand",
                "newOnHand",
                "new_on_hand",
                "currentQuantity",
                "current_quantity");
        boolean inStock = remaining != null && remaining.intValue() > 0;
        searchIndexService.updateStockStatus(productId, inStock);
    }

    private void handleStockConfirmed(Map<String, Object> payload) {
        Object itemsObj = payload.get("items");
        if (itemsObj instanceof List<?> itemsList) {
            for (Object obj : itemsList) {
                if (obj instanceof Map<?, ?> itemMap) {
                    processConfirmedItem(objectMapper.convertValue(itemMap, MAP_TYPE));
                }
            }
        } else if (extractProductId(payload) != null) {
            processConfirmedItem(payload);
        }
    }

    private void processConfirmedItem(Map<String, Object> item) {
        UUID productId = extractProductId(item);
        if (productId == null) {
            return;
        }
        Boolean inStock = firstBoolean(item, "inStock", "in_stock");
        if (inStock != null) {
            searchIndexService.updateStockStatus(productId, inStock);
            return;
        }
        Number remainingAvailable = firstNumber(item, "remainingAvailable", "remaining_available");
        if (remainingAvailable != null) {
            searchIndexService.updateStockStatus(productId, remainingAvailable.intValue() > 0);
            return;
        }
        Number remainingOnHand = firstNumber(item, "remainingOnHand", "remaining_on_hand");
        if (remainingOnHand != null) {
            searchIndexService.updateStockStatus(productId, remainingOnHand.intValue() > 0);
        }
    }

    private void handleStockReleased(Map<String, Object> payload) {
        Object itemsObj = payload.get("items");
        if (itemsObj instanceof List<?> itemsList) {
            for (Object obj : itemsList) {
                if (obj instanceof Map<?, ?> itemMap) {
                    UUID productId = extractProductId(objectMapper.convertValue(itemMap, MAP_TYPE));
                    if (productId != null) {
                        searchIndexService.updateStockStatus(productId, true);
                    }
                }
            }
        } else {
            UUID productId = extractProductId(payload);
            if (productId != null) {
                searchIndexService.updateStockStatus(productId, true);
            }
        }
    }

    private static UUID extractProductId(Map<String, Object> payload) {
        String raw = asString(payload.get("productId"));
        if (raw == null) {
            raw = asString(payload.get("product_id"));
        }
        if (raw == null) {
            return null;
        }
        return UUID.fromString(raw);
    }

    private static String asString(Object value) {
        if (value == null) {
            return null;
        }
        String text = value.toString().trim();
        return text.isEmpty() ? null : text;
    }

    private static Number firstNumber(Map<String, Object> payload, String... keys) {
        for (String key : keys) {
            Object val = payload.get(key);
            if (val instanceof Number number) {
                return number;
            }
        }
        return null;
    }

    private static Boolean firstBoolean(Map<String, Object> payload, String... keys) {
        for (String key : keys) {
            Object val = payload.get(key);
            if (val instanceof Boolean bool) {
                return bool;
            }
        }
        return null;
    }
}
