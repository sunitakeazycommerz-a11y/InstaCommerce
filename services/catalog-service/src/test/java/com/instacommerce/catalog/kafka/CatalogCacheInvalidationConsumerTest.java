package com.instacommerce.catalog.kafka;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;

class CatalogCacheInvalidationConsumerTest {

    private CacheManager cacheManager;
    private Cache productsCache;
    private Cache searchCache;
    private CatalogCacheInvalidationConsumer consumer;

    @BeforeEach
    void setUp() {
        cacheManager = mock(CacheManager.class);
        productsCache = mock(Cache.class);
        searchCache = mock(Cache.class);
        when(cacheManager.getCache("products")).thenReturn(productsCache);
        consumer = new CatalogCacheInvalidationConsumer(cacheManager, new ObjectMapper());
    }

    @Test
    @DisplayName("Evicts both string and UUID product keys on OutOfStock and handles non-UUID keys without failing")
    void evictsUuidAndNonUuidKeys() {
        UUID productId = UUID.randomUUID();
        consumer.onEvent(Map.of(
                "eventType", "OutOfStock",
                "payload", Map.of("productId", productId.toString())));

        verify(productsCache).evict(productId.toString());
        verify(productsCache).evict(productId);

        consumer.onEvent(Map.of(
                "eventType", "ExpiredStockWrittenOff",
                "payload", Map.of("product_id", "sku-non-uuid-key")));

        verify(productsCache).evict("sku-non-uuid-key");
    }

    @Test
    @DisplayName("Clears search cache and product cache on PriceUpdated")
    void clearsSearchCacheOnPriceUpdated() {
        when(cacheManager.getCache("search")).thenReturn(searchCache);
        UUID productId = UUID.randomUUID();
        consumer.onEvent(Map.of(
                "eventType", "PriceUpdated",
                "payload", Map.of("productId", productId.toString())));

        verify(productsCache).evict(productId.toString());
        verify(productsCache).evict(productId);
        verify(searchCache).clear();
    }
}
