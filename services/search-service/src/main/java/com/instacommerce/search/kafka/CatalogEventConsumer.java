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
    public void handleCatalogEvent(Map<String, Object> event) {
        String eventType = (String) event.get("eventType");
        if (eventType == null) {
            log.warn("Received catalog event with no eventType, skipping: {}", event);
            return;
        }

        log.info("Processing catalog event: type={}", eventType);

        switch (eventType) {
            case "ProductCreated", "ProductUpdated" -> handleProductUpsert(event);
            case "ProductDelisted" -> handleProductDelisted(event);
            default -> log.debug("Ignoring unhandled catalog event type={}", eventType);
        }
    }

    private void handleProductUpsert(Map<String, Object> event) {
        try {
            UUID productId = UUID.fromString((String) event.get("productId"));
            String name = (String) event.get("name");
            String description = (String) event.get("description");
            String brand = (String) event.get("brand");
            String category = (String) event.get("category");
            long priceCents = ((Number) event.get("priceCents")).longValue();
            String imageUrl = (String) event.get("imageUrl");
            boolean inStock = event.get("inStock") != null && (Boolean) event.get("inStock");

            searchIndexService.upsertDocument(productId, name, description, brand,
                    category, priceCents, imageUrl, inStock);
        } catch (Exception ex) {
            log.error("Failed to process product upsert event: {}", event, ex);
            throw ex;
        }
    }

    private void handleProductDelisted(Map<String, Object> event) {
        try {
            UUID productId = UUID.fromString((String) event.get("productId"));
            searchIndexService.deleteDocument(productId);
        } catch (Exception ex) {
            log.error("Failed to process product delisted event: {}", event, ex);
            throw ex;
        }
    }
}
