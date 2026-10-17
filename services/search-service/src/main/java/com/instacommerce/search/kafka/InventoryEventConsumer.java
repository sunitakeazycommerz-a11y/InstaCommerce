package com.instacommerce.search.kafka;

import com.instacommerce.contracts.topics.TopicNames;
import com.instacommerce.search.service.SearchIndexService;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

@Component
public class InventoryEventConsumer {

    private static final Logger log = LoggerFactory.getLogger(InventoryEventConsumer.class);

    private final SearchIndexService searchIndexService;

    public InventoryEventConsumer(SearchIndexService searchIndexService) {
        this.searchIndexService = searchIndexService;
    }

    @KafkaListener(topics = TopicNames.INVENTORY_EVENTS, groupId = "search-service")
    @SuppressWarnings("unchecked")
    public void handleInventoryEvent(Map<String, Object> envelope) {
        String eventType = (String) envelope.get("eventType");
        if (eventType == null) {
            log.warn("Received inventory event with no eventType, skipping");
            return;
        }

        // Support both envelope format (nested payload) and flat format
        Map<String, Object> payload = envelope.containsKey("payload")
                ? (Map<String, Object>) envelope.get("payload")
                : envelope;

        switch (eventType) {
            case "StockAdjusted" -> handleStockAdjusted(payload);
            case "LowStockAlert" -> handleLowStockAlert(payload);
            case "StockConfirmed" -> handleStockConfirmed(payload);
            case "StockReleased" -> handleStockReleased(payload);
            default -> log.debug("Ignoring unhandled inventory event type={}", eventType);
        }
    }

    private void handleStockAdjusted(Map<String, Object> payload) {
        UUID productId = UUID.fromString((String) payload.get("productId"));
        Number newOnHand = (Number) payload.get("newOnHand");
        if (newOnHand == null) {
            log.warn("StockAdjusted event for productId={} missing newOnHand", productId);
            return;
        }
        boolean inStock = newOnHand.intValue() > 0;
        searchIndexService.updateStockStatus(productId, inStock);
    }

    private void handleLowStockAlert(Map<String, Object> payload) {
        UUID productId = UUID.fromString((String) payload.get("productId"));
        Number currentQuantity = (Number) payload.get("currentQuantity");
        if (currentQuantity == null) {
            log.warn("LowStockAlert event for productId={} missing currentQuantity", productId);
            return;
        }
        boolean inStock = currentQuantity.intValue() > 0;
        searchIndexService.updateStockStatus(productId, inStock);
    }

    @SuppressWarnings("unchecked")
    private void handleStockConfirmed(Map<String, Object> payload) {
        Object itemsObj = payload.get("items");
        if (itemsObj instanceof List<?> itemsList) {
            for (Object obj : itemsList) {
                if (obj instanceof Map<?, ?> itemMap) {
                    processConfirmedItem((Map<String, Object>) itemMap);
                }
            }
        } else if (payload.containsKey("productId")) {
            processConfirmedItem(payload);
        }
    }

    private void processConfirmedItem(Map<String, Object> item) {
        Object pidObj = item.get("productId");
        if (pidObj == null) {
            return;
        }
        UUID productId = UUID.fromString(pidObj.toString());
        Boolean inStock = (Boolean) item.get("inStock");
        if (inStock != null) {
            searchIndexService.updateStockStatus(productId, inStock);
            return;
        }
        Number remainingAvailable = (Number) item.get("remainingAvailable");
        if (remainingAvailable != null) {
            searchIndexService.updateStockStatus(productId, remainingAvailable.intValue() > 0);
            return;
        }
        Number remainingOnHand = (Number) item.get("remainingOnHand");
        if (remainingOnHand != null) {
            searchIndexService.updateStockStatus(productId, remainingOnHand.intValue() > 0);
        }
    }

    private void handleStockReleased(Map<String, Object> payload) {
        Object itemsObj = payload.get("items");
        if (itemsObj instanceof List<?> itemsList) {
            for (Object obj : itemsList) {
                if (obj instanceof Map<?, ?> itemMap) {
                    Object pidObj = ((Map<?, ?>) itemMap).get("productId");
                    if (pidObj != null) {
                        UUID productId = UUID.fromString(pidObj.toString());
                        searchIndexService.updateStockStatus(productId, true);
                    }
                }
            }
        } else if (payload.containsKey("productId")) {
            UUID productId = UUID.fromString(payload.get("productId").toString());
            searchIndexService.updateStockStatus(productId, true);
        }
    }
}
