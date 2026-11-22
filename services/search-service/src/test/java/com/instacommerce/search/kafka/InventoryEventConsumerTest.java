package com.instacommerce.search.kafka;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.instacommerce.search.service.SearchIndexService;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;

@ExtendWith(MockitoExtension.class)
class InventoryEventConsumerTest {

    @Mock
    private SearchIndexService searchIndexService;

    private InventoryEventConsumer consumer;

    @BeforeEach
    void setUp() {
        consumer = new InventoryEventConsumer(searchIndexService, null, null);
    }

    @Test
    void stockAdjustedToZero_setsOutOfStock() {
        UUID productId = UUID.randomUUID();
        Map<String, Object> event = new HashMap<>();
        event.put("eventType", "StockAdjusted");
        event.put("payload", Map.of("productId", productId.toString(), "newOnHand", 0));

        consumer.handleInventoryEvent(event);

        verify(searchIndexService).updateStockStatus(productId, false);
    }

    @Test
    void stockAdjustedPositive_setsInStock() {
        UUID productId = UUID.randomUUID();
        Map<String, Object> event = new HashMap<>();
        event.put("eventType", "StockAdjusted");
        event.put("payload", Map.of("productId", productId.toString(), "newOnHand", 25));

        consumer.handleInventoryEvent(event);

        verify(searchIndexService).updateStockStatus(productId, true);
    }

    @Test
    void lowStockAlertZero_setsOutOfStock() {
        UUID productId = UUID.randomUUID();
        Map<String, Object> event = new HashMap<>();
        event.put("eventType", "LowStockAlert");
        event.put("payload", Map.of("productId", productId.toString(), "currentQuantity", 0));

        consumer.handleInventoryEvent(event);

        verify(searchIndexService).updateStockStatus(productId, false);
    }

    @Test
    void outOfStock_immediatelyMarksOutOfStockAndEvictsCaches() {
        CacheManager cacheManager = mock(CacheManager.class);
        Cache searchCache = mock(Cache.class);
        Cache autoCache = mock(Cache.class);
        when(cacheManager.getCache("searchResults")).thenReturn(searchCache);
        when(cacheManager.getCache("autocomplete")).thenReturn(autoCache);

        InventoryEventConsumer cachedConsumer = new InventoryEventConsumer(
                searchIndexService, cacheManager, new ObjectMapper());

        UUID productId = UUID.randomUUID();
        UUID storeId = UUID.randomUUID();
        Map<String, Object> event = Map.of(
                "eventType", "OutOfStock",
                "payload", Map.of(
                        "productId", productId.toString(),
                        "storeId", storeId.toString(),
                        "detectedAt", "2026-03-31T10:15:30Z"));

        cachedConsumer.handleInventoryEvent(event);

        verify(searchIndexService).updateStockStatus(productId, false);
        verify(searchCache).clear();
        verify(autoCache).clear();
    }

    @Test
    void expiredStockWrittenOff_updatesStockStatusAndEvictsCaches() {
        CacheManager cacheManager = mock(CacheManager.class);
        Cache searchCache = mock(Cache.class);
        Cache autoCache = mock(Cache.class);
        when(cacheManager.getCache("searchResults")).thenReturn(searchCache);
        when(cacheManager.getCache("autocomplete")).thenReturn(autoCache);

        InventoryEventConsumer cachedConsumer = new InventoryEventConsumer(
                searchIndexService, cacheManager, new ObjectMapper());

        UUID productId = UUID.randomUUID();
        Map<String, Object> writeOffDepletedEvent = Map.of(
                "eventType", "ExpiredStockWrittenOff",
                "payload", Map.of(
                        "store_id", UUID.randomUUID().toString(),
                        "product_id", productId.toString(),
                        "batch_number", "BATCH-01",
                        "written_off_quantity", 12));

        cachedConsumer.handleInventoryEvent(writeOffDepletedEvent);

        verify(searchIndexService).updateStockStatus(productId, false);
        verify(searchCache).clear();
        verify(autoCache).clear();

        UUID remainingProduct = UUID.randomUUID();
        Map<String, Object> writeOffWithRemainingEvent = Map.of(
                "eventType", "ExpiredStockWrittenOff",
                "payload", Map.of(
                        "productId", remainingProduct.toString(),
                        "writtenOffQuantity", 4,
                        "remainingAvailable", 6));

        cachedConsumer.handleInventoryEvent(writeOffWithRemainingEvent);

        verify(searchIndexService).updateStockStatus(remainingProduct, true);
    }

    @Test
    void flatFormatEvent_handledCorrectly() {
        UUID productId = UUID.randomUUID();
        Map<String, Object> event = new HashMap<>();
        event.put("eventType", "StockAdjusted");
        event.put("productId", productId.toString());
        event.put("newOnHand", 10);

        consumer.handleInventoryEvent(event);

        verify(searchIndexService).updateStockStatus(productId, true);
    }

    @Test
    void unknownEventType_ignored() {
        Map<String, Object> event = new HashMap<>();
        event.put("eventType", "WarehouseTransfer");

        consumer.handleInventoryEvent(event);

        verifyNoInteractions(searchIndexService);
    }

    @Test
    void nullEventType_ignored() {
        Map<String, Object> event = new HashMap<>();
        event.put("aggregateId", "something");

        consumer.handleInventoryEvent(event);

        verifyNoInteractions(searchIndexService);
    }

    @Test
    void stockConfirmed_itemsReachZero_setsOutOfStock() {
        UUID prod1 = UUID.randomUUID();
        UUID prod2 = UUID.randomUUID();

        Map<String, Object> item1 = Map.of(
            "productId", prod1.toString(),
            "quantity", 2,
            "remainingAvailable", 0,
            "inStock", false
        );
        Map<String, Object> item2 = Map.of(
            "productId", prod2.toString(),
            "quantity", 1,
            "remainingAvailable", 5,
            "inStock", true
        );

        Map<String, Object> payload = Map.of(
            "reservationId", UUID.randomUUID().toString(),
            "items", List.of(item1, item2)
        );

        Map<String, Object> event = Map.of(
            "eventType", "StockConfirmed",
            "payload", payload
        );

        consumer.handleInventoryEvent(event);

        verify(searchIndexService).updateStockStatus(prod1, false);
        verify(searchIndexService).updateStockStatus(prod2, true);
    }

    @Test
    void stockReleased_marksItemsInStock() {
        UUID prod1 = UUID.randomUUID();
        UUID prod2 = UUID.randomUUID();

        Map<String, Object> item1 = Map.of("productId", prod1.toString(), "quantity", 3);
        Map<String, Object> item2 = Map.of("productId", prod2.toString(), "quantity", 1);

        Map<String, Object> payload = Map.of(
            "reservationId", UUID.randomUUID().toString(),
            "reason", "CANCELLED",
            "items", List.of(item1, item2)
        );

        Map<String, Object> event = Map.of(
            "eventType", "StockReleased",
            "payload", payload
        );

        consumer.handleInventoryEvent(event);

        verify(searchIndexService).updateStockStatus(prod1, true);
        verify(searchIndexService).updateStockStatus(prod2, true);
    }
}
