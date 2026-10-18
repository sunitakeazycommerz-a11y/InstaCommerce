package com.instacommerce.search.kafka;

import com.instacommerce.contracts.topics.TopicNames;
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
    private final org.springframework.cache.CacheManager cacheManager;

    public CatalogEventConsumer(SearchIndexService searchIndexService,
                                org.springframework.beans.factory.ObjectProvider<org.springframework.cache.CacheManager> cacheManagerProvider) {
        this.searchIndexService = searchIndexService;
        this.cacheManager = cacheManagerProvider != null ? cacheManagerProvider.getIfAvailable() : null;
    }

    public CatalogEventConsumer(SearchIndexService searchIndexService) {
        this(searchIndexService, null);
    }

    @KafkaListener(topics = TopicNames.CATALOG_EVENTS, groupId = "search-service")
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
            case "ProductCreated", "ProductUpdated" -> {
                handleProductUpsert(payload);
                evictSearchCaches();
            }
            case "ProductDeactivated", "ProductDelisted" -> {
                handleProductDelisted(payload);
                evictSearchCaches();
            }
            default -> log.debug("Ignoring unhandled catalog event type={}", eventType);
        }
    }

    private void evictSearchCaches() {
        if (cacheManager != null) {
            var searchCache = cacheManager.getCache("searchResults");
            if (searchCache != null) searchCache.clear();
            var autoCache = cacheManager.getCache("autocomplete");
            if (autoCache != null) autoCache.clear();
            log.info("Evicted searchResults and autocomplete caches following catalog change");
        }
    }

    private void handleProductUpsert(Map<String, Object> payload) {
        try {
            UUID productId = UUID.fromString((String) payload.get("productId"));
            Object activeObj = payload.get("active");
            if (activeObj instanceof Boolean active && !active) {
                log.info("Product {} is inactive, deleting from search index", productId);
                searchIndexService.deleteDocument(productId);
                return;
            }

            String name = (String) payload.get("name");
            String description = (String) payload.get("description");
            String brand = (String) payload.get("brand");
            String category = (String) payload.get("category");
            long priceCents = payload.get("priceCents") != null
                ? ((Number) payload.get("priceCents")).longValue()
                : (payload.get("basePriceCents") != null ? ((Number) payload.get("basePriceCents")).longValue() : 0L);
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
