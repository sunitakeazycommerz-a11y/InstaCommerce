package com.instacommerce.catalog.kafka;

import com.fasterxml.jackson.core.type.TypeReference;
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
    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {};

    private final CacheManager cacheManager;
    private final ObjectMapper objectMapper;

    public CatalogCacheInvalidationConsumer(CacheManager cacheManager, ObjectMapper objectMapper) {
        this.cacheManager = cacheManager;
        this.objectMapper = objectMapper != null ? objectMapper : new ObjectMapper();
    }

    @KafkaListener(
        topics = {TopicNames.CATALOG_EVENTS, TopicNames.PRICING_EVENTS, TopicNames.INVENTORY_EVENTS},
        groupId = "catalog-cache-invalidator"
    )
    public void onEvent(Map<String, Object> envelope) {
        if (envelope == null || envelope.isEmpty()) {
            return;
        }
        String eventType = asString(envelope.get("eventType"));
        if (eventType == null) {
            eventType = asString(envelope.get("event_type"));
        }
        if (eventType == null) {
            return;
        }

        Map<String, Object> payload;
        try {
            payload = envelope.get("payload") instanceof Map<?, ?> nested
                    ? objectMapper.convertValue(nested, MAP_TYPE)
                    : envelope;
        } catch (IllegalArgumentException ex) {
            log.warn("Skipping malformed cache invalidation payload for eventType={}", eventType, ex);
            return;
        }

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
            case "LowStockAlert", "StockAdjusted", "StockConfirmed", "StockReleased",
                    "OutOfStock", "ExpiredStockWrittenOff" -> {
                evictProductCaches(payload);
            }
            default -> log.debug("Unhandled event type for catalog cache invalidation: {}", eventType);
        }
    }

    private void evictProductCaches(Map<String, Object> payload) {
        Object idObj = payload.get("productId");
        if (idObj == null) {
            idObj = payload.get("product_id");
        }
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
                } catch (IllegalArgumentException ex) {
                    log.debug("Product cache key is not a valid UUID during cache eviction: productId={}", productIdStr);
                }
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

    private static String asString(Object value) {
        if (value == null) {
            return null;
        }
        String text = value.toString().trim();
        return text.isEmpty() ? null : text;
    }
}
