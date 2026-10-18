package com.instacommerce.catalog.kafka;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.instacommerce.contracts.topics.TopicNames;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

@Component
public class CatalogCacheInvalidationConsumer {

    private static final Logger log = LoggerFactory.getLogger(CatalogCacheInvalidationConsumer.class);

    private final CacheManager cacheManager;
    private final ObjectMapper objectMapper;

    public CatalogCacheInvalidationConsumer(CacheManager cacheManager, ObjectMapper objectMapper) {
        this.cacheManager = cacheManager;
        this.objectMapper = objectMapper;
    }

    @KafkaListener(
        topics = {TopicNames.CATALOG_EVENTS, TopicNames.PRICING_EVENTS, TopicNames.INVENTORY_EVENTS},
        groupId = "catalog-cache-invalidator"
    )
    @SuppressWarnings("unchecked")
    public void onEvent(Map<String, Object> envelope) {
        try {
            String eventType = (String) envelope.get("eventType");
            if (eventType == null && envelope.containsKey("event_type")) {
                eventType = (String) envelope.get("event_type");
            }
            if (eventType == null) {
                return;
            }

            Map<String, Object> payload = envelope.containsKey("payload") && envelope.get("payload") instanceof Map
                    ? (Map<String, Object>) envelope.get("payload")
                    : envelope;

            log.info("Catalog cache invalidator received event: type={}", eventType);

            switch (eventType) {
                case "ProductUpdated", "ProductDeactivated", "ProductCreated" -> {
                    evictProductCaches(payload);
                    evictNamedCache("categories");
                    evictNamedCache("search");
                }
                case "PriceUpdated" -> {
                    evictProductCaches(payload);
                    evictNamedCache("search");
                }
                case "LowStockAlert", "StockAdjusted", "StockConfirmed", "StockReleased" -> {
                    evictProductCaches(payload);
                }
                default -> log.debug("Unhandled event type for catalog cache invalidation: {}", eventType);
            }
        } catch (Exception e) {
            log.warn("Error processing cache invalidation event: {}", e.getMessage());
        }
    }

    private void evictProductCaches(Map<String, Object> payload) {
        Object idObj = payload.get("productId");
        if (idObj == null) {
            idObj = payload.get("id");
        }
        if (idObj != null) {
            String productIdStr = idObj.toString();
            Cache productsCache = cacheManager.getCache("products");
            if (productsCache != null) {
                productsCache.evict(productIdStr);
                try {
                    productsCache.evict(UUID.fromString(productIdStr));
                } catch (IllegalArgumentException ignored) {}
                log.info("Evicted product {} from catalog cache", productIdStr);
            }
        } else {
            evictNamedCache("products");
        }
    }

    private void evictNamedCache(String cacheName) {
        Cache cache = cacheManager.getCache(cacheName);
        if (cache != null) {
            cache.clear();
            log.info("Cleared entire {} cache in catalog-service", cacheName);
        }
    }
}
