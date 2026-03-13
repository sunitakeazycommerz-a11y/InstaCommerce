package com.instacommerce.search.kafka;

import com.instacommerce.search.service.SearchIndexService;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

@Component
public class CatalogEventConsumer {
    private static final Logger log = LoggerFactory.getLogger(CatalogEventConsumer.class);

    private final SearchIndexService searchIndexService;

    public CatalogEventConsumer(SearchIndexService searchIndexService) {
        this.searchIndexService = searchIndexService;
    }

    @KafkaListener(topics = "catalog.events", groupId = "search-service")
    @SuppressWarnings("unchecked")
    public void handleCatalogEvent(Map<String, Object> envelope) {
        String eventType = (String) envelope.get("eventType");
        if (eventType == null) {
            log.warn("Received catalog event with no eventType, skipping: {}", envelope);
            return;
        }

        log.info("Processing catalog event: type={} eventId={}",
            eventType, envelope.get("eventId"));

        // Support both envelope format (payload nested) and flat format (backward compat)
        Map<String, Object> payload = envelope.containsKey("payload")
            ? (Map<String, Object>) envelope.get("payload")
            : envelope;

        switch (eventType) {
            case "ProductCreated", "ProductUpdated" -> handleProductUpsert(payload);
            case "ProductDeactivated", "ProductDelisted" -> handleProductDelisted(payload);
            default -> log.debug("Ignoring unhandled catalog event type={}", eventType);
        }
    }

    private void handleProductUpsert(Map<String, Object> payload) {
        try {
            UUID productId = UUID.fromString((String) payload.get("productId"));
            String name = (String) payload.get("name");
            String description = (String) payload.get("description");
            String brand = (String) payload.get("brand");
            String category = (String) payload.get("category");
            long priceCents = payload.get("priceCents") != null
                ? ((Number) payload.get("priceCents")).longValue() : 0L;
            String imageUrl = (String) payload.get("imageUrl");
            boolean inStock = payload.get("inStock") != null && (Boolean) payload.get("inStock");
            UUID storeId = payload.get("storeId") != null
                ? UUID.fromString((String) payload.get("storeId")) : null;

            searchIndexService.upsertDocument(productId, name, description, brand,
                    category, priceCents, imageUrl, inStock, storeId);
        } catch (Exception ex) {
            log.error("Failed to process product upsert event: {}", payload, ex);
            throw ex;
        }
    }

    private void handleProductDelisted(Map<String, Object> payload) {
        try {
            UUID productId = UUID.fromString((String) payload.get("productId"));
            searchIndexService.deleteDocument(productId);
        } catch (Exception ex) {
            log.error("Failed to process product delisted event: {}", payload, ex);
            throw ex;
        }
    }
}
